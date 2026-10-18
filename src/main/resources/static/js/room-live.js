(() => {
  const page = document.getElementById("room-page");
  if (!page) return;

  const roomId = page.dataset.roomId;
  const status = document.getElementById("room-status");
  const connection = document.getElementById("connection-state");
  const members = document.getElementById("participants-list");
  const waiting = document.getElementById("waiting-section");
  const active = document.getElementById("active-section");
  const live = document.getElementById("live-text-section");
  const notice = document.getElementById("closed-notice");
  const closeForm = document.getElementById("close-room-form");
  const closeButton = closeForm?.querySelector("button");
  const form = document.getElementById("live-text-form");
  const input = document.getElementById("live-text-input");
  const send = document.getElementById("live-send");
  const events = document.getElementById("live-events");
  const speechSection = document.getElementById("speech-section");
  const glossarySection = document.getElementById("glossary-section");
  const roomToolBar = document.querySelector(".room-tool-bar");
  const roomDetails = document.getElementById("room-details");
  const roomMainGrid = document.querySelector(".room-main-grid");
  const recordStart = document.getElementById("record-start");
  const recordStartLabel = recordStart.querySelector("span");
  const recordStop = document.getElementById("record-stop");
  const recordingStatus = document.getElementById("recording-status");
  const recordingTimer = document.getElementById("recording-timer");
  const speechEvents = document.getElementById("speech-events");
  const turnEmpty = document.getElementById("turn-empty");
  const turnAnnouncement = document.getElementById("turn-announcement");
  const inviteCode = document.getElementById("invite-code");
  const copyButton = document.getElementById("invite-copy");
  const copyStatus = document.getElementById("copy-status");
  const inviteExpiry = document.getElementById("invite-expiry");
  const speechType = "audio/webm;codecs=opus";
  const speechSupported = !!(navigator.mediaDevices?.getUserMedia &&
    window.MediaRecorder?.isTypeSupported(speechType));
  const turnRows = new Map();
  const participantNames = new Map();

  let recorder;
  let microphone;
  let startedAt;
  let stopTimer;
  let displayTimer;
  let busy = false;
  let discardRecording = false;
  let connected = false;
  let socket;
  let retry = 0;
  let stopped = false;
  let streamId;
  let lastSequence = 0;
  const seen = new Set();

  function make(tag, className, value) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (value !== undefined) element.textContent = value;
    return element;
  }

  function languageName(tag) {
    return tag === "ta" ? "Tamil" : tag === "en" ? "English" : "Language";
  }

  function updateConnection(message, state) {
    connection.textContent = message;
    connection.dataset.state = state;
  }

  function socketOpen() {
    return typeof WebSocket !== "undefined" && socket?.readyState === WebSocket.OPEN;
  }

  function setStatus(value) {
    const wasWaiting = status.textContent === "WAITING";
    status.textContent = value;
    status.classList.remove("badge--active", "badge--waiting", "badge--closed");
    status.classList.add(value === "ACTIVE" ? "badge--active" :
      value === "WAITING" ? "badge--waiting" : "badge--closed");
    waiting.hidden = value !== "WAITING";
    active.hidden = value !== "ACTIVE";
    if (wasWaiting && value === "ACTIVE") active.classList.add("room-state-entered");
    live.hidden = value !== "ACTIVE";
    speechSection.hidden = value !== "ACTIVE";
    if (value === "CLOSED" && glossarySection?.open) glossarySection.close();
    if (glossarySection) glossarySection.hidden = value === "CLOSED";
    if (roomDetails) roomDetails.hidden = value === "CLOSED";
    if (roomToolBar) roomToolBar.hidden = value === "CLOSED";
    roomMainGrid.classList.toggle("room-main-grid--waiting", value === "WAITING");
    roomMainGrid.classList.toggle("room-main-grid--closed", value === "CLOSED");
    if (value === "CLOSED") {
      if (roomDetails) roomDetails.open = false;
    }
    notice.hidden = value !== "CLOSED";
    if (closeForm) closeForm.hidden = value === "CLOSED";
    if (closeButton) closeButton.textContent =
      value === "WAITING" ? "Cancel room" : "Leave and close room";
    input.disabled = value !== "ACTIVE" || !connected || !socketOpen();
    send.disabled = input.disabled;
    recordStart.disabled = value !== "ACTIVE" || !connected ||
      !socketOpen() || !speechSupported || busy;
    if (value !== "ACTIVE" && recorder?.state === "recording") {
      discardRecording = true;
      recorder.stop();
    }
    if (value === "CLOSED") {
      speechSection.dataset.phase = "idle";
      stopped = true;
      speechEvents.replaceChildren();
      turnRows.clear();
      events.replaceChildren();
      turnEmpty.hidden = false;
      recordingStatus.textContent = "This room is closed.";
    }
  }

  function rowFor(turnId, turnIndex, senderId) {
    if (turnRows.has(turnId)) {
      const existing = turnRows.get(turnId);
      if (senderId) {
        existing.row.dataset.senderId = senderId;
        existing.speaker.textContent = participantNames.get(senderId) || "Participant";
      }
      return existing;
    }
    const row = make("li", "turn-card");
    row.dataset.index = String(turnIndex);
    if (senderId) row.dataset.senderId = senderId;
    const top = make("div", "turn-card__top");
    const meta = make("div", "turn-card__meta");
    const speaker = make("strong", "turn-card__speaker", participantNames.get(senderId) || "Participant");
    const index = make("span", "turn-card__index", "Turn " + turnIndex);
    const progress = make("span", "turn-card__status", "Processing");
    meta.append(speaker, index);
    top.append(meta, progress);

    const sourceBlock = make("div", "turn-caption");
    const sourceLabel = make("span", "turn-caption__label");
    const source = make("p");
    sourceBlock.append(sourceLabel, source);
    sourceBlock.hidden = true;

    const targetBlock = make("div", "turn-caption turn-caption--translated");
    const targetLabel = make("span", "turn-caption__label");
    const target = make("p");
    targetBlock.append(targetLabel, target);
    targetBlock.hidden = true;

    const playback = make("div", "turn-card__audio");
    row.append(top, sourceBlock, targetBlock, playback);
    const next = [...speechEvents.children].find(item => Number(item.dataset.index) > turnIndex);
    speechEvents.insertBefore(row, next || null);
    turnEmpty.hidden = true;
    const parts = { row, speaker, progress, sourceBlock, sourceLabel, source,
      targetBlock, targetLabel, target, playback };
    turnRows.set(turnId, parts);
    while (turnRows.size > 16) {
      let oldestId;
      let oldestIndex = Number.POSITIVE_INFINITY;
      for (const [id, entry] of turnRows) {
        const index = Number(entry.row.dataset.index);
        if (index < oldestIndex) {
          oldestId = id;
          oldestIndex = index;
        }
      }
      turnRows.get(oldestId).row.remove();
      turnRows.delete(oldestId);
    }
    return parts;
  }

  function showCaption(parts, kind, language, textValue) {
    const isSource = kind === "source";
    const block = isSource ? parts.sourceBlock : parts.targetBlock;
    const label = isSource ? parts.sourceLabel : parts.targetLabel;
    const content = isSource ? parts.source : parts.target;
    label.textContent = (isSource ? "Original · " : "Translation · ") + languageName(language);
    content.textContent = textValue;
    if (language === "en" || language === "ta") content.lang = language;
    block.hidden = false;
    block.classList.add("turn-caption--revealed");
  }

  function showAudio(parts, url) {
    if (!url?.startsWith("/api/rooms/" + roomId + "/turns/") ||
        parts.playback.childElementCount) return;
    const label = make("p", "small muted", "Play translated voice");
    const player = make("audio");
    player.controls = true;
    player.preload = "none";
    player.src = url;
    player.setAttribute("aria-label", "Play translated voice");
    player.addEventListener("error", () => {
      parts.progress.textContent = "Translated audio is unavailable or has expired.";
      parts.progress.dataset.failed = "true";
    });
    parts.playback.append(label, player);
  }

  function failureText(stage) {
    if (stage === "STT") return "Speech recognition failed. Try another turn.";
    if (stage === "TRANSLATION") return "Translation failed. Try another turn.";
    if (stage === "TTS") return "Voice generation failed. Captions are still available.";
    return "Speech processing could not finish. Try another turn.";
  }

  function receiveTurn(event) {
    const payload = event.payload || {};
    if (!Number.isSafeInteger(payload.turnIndex) || !event.turnId) return;
    const parts = rowFor(event.turnId, payload.turnIndex, event.senderParticipantId);
    if (event.type === "TURN_ACCEPTED") parts.progress.textContent = "Recognizing speech";
    if (event.type === "TURN_ACCEPTED") parts.row.dataset.phase = "recognizing";
    if (event.type === "TRANSCRIPT_READY") {
      showCaption(parts, "source", payload.sourceLanguage, payload.transcript);
      parts.progress.textContent = "Translating";
      parts.row.dataset.phase = "translating";
    }
    if (event.type === "TRANSLATION_READY") {
      showCaption(parts, "target", payload.targetLanguage, payload.translatedText);
      parts.progress.textContent = "Creating voice";
      parts.row.dataset.phase = "voice";
    }
    if (event.type === "AUDIO_READY") {
      showAudio(parts, payload.audioUrl);
      parts.progress.textContent = "Audio ready";
      parts.row.dataset.phase = "ready";
    }
    if (event.type === "TURN_FAILED") {
      parts.progress.textContent = failureText(payload.stage);
      parts.progress.dataset.failed = "true";
      parts.row.dataset.phase = "error";
    }
    turnAnnouncement.textContent = "Turn " + payload.turnIndex + ": " + parts.progress.textContent;
  }

  async function refreshTurns() {
    try {
      const knownIdsAtStart = new Set(turnRows.keys());
      const response = await fetch("/api/rooms/" + roomId + "/turns/recent",
        { credentials: "same-origin" });
      if (!response.ok || status.textContent === "CLOSED") return;
      const recent = await response.json();
      if (status.textContent === "CLOSED") return;
      const currentIds = new Set(recent.map(turn => turn.turnId));
      for (const turn of recent) {
        const parts = rowFor(turn.turnId, turn.turnIndex, turn.senderParticipantId);
        if (turn.transcript) showCaption(parts, "source", turn.sourceLanguage, turn.transcript);
        if (turn.translatedText) showCaption(parts, "target", turn.targetLanguage, turn.translatedText);
        if (turn.audioAvailable) showAudio(parts,
          "/api/rooms/" + roomId + "/turns/" + turn.turnId + "/audio");
        parts.progress.textContent = turn.status === "FAILED" ? failureText(turn.failedStage) :
          turn.status === "READY" ? "Audio ready" :
          turn.status === "TRANSLATED" ? "Creating voice" :
          turn.status === "TRANSCRIBED" ? "Translating" : "Recognizing speech";
        parts.progress.dataset.failed = String(turn.status === "FAILED");
        parts.row.dataset.phase = turn.status === "FAILED" ? "error" :
          turn.status === "READY" ? "ready" :
          turn.status === "TRANSLATED" ? "voice" :
          turn.status === "TRANSCRIBED" ? "translating" : "recognizing";
      }
      for (const [turnId, parts] of turnRows) {
        if (knownIdsAtStart.has(turnId) && !currentIds.has(turnId)) {
          parts.row.remove();
          turnRows.delete(turnId);
        }
      }
      turnEmpty.hidden = turnRows.size !== 0;
    } catch {
      // The live connection can remain available if the snapshot request fails.
    }
  }

  function showParticipants(people) {
    if (!Array.isArray(people)) return;
    members.replaceChildren();
    participantNames.clear();
    for (const person of people) {
      participantNames.set(person.participantId, person.displayName);
      const item = make("li", "participant");
      const avatar = make("span", "participant__avatar", String(person.slot));
      avatar.setAttribute("aria-hidden", "true");
      const details = make("span", "participant__details");
      const name = make("strong", "", person.displayName);
      const direction = make("small", "",
        "Speaks " + languageName(person.speaking) + " · Hears " + languageName(person.listening));
      details.append(name, direction);
      item.append(avatar, details);
      members.append(item);
    }
    for (const parts of turnRows.values()) {
      parts.speaker.textContent = participantNames.get(parts.row.dataset.senderId) || "Participant";
    }
  }

  function addText(payload) {
    events.append(make("li", "", payload.text));
  }

  function receive(raw) {
    let event;
    try { event = JSON.parse(raw); } catch { return; }
    if (event.protocolVersion !== 1 || event.roomId !== roomId) return;
    if (stopped && event.type !== "ROOM_CLOSED") return;
    if (streamId !== event.streamId) {
      streamId = event.streamId;
      lastSequence = 0;
      seen.clear();
    }
    if (event.sequence <= lastSequence || seen.has(event.eventId)) return;
    lastSequence = event.sequence;
    seen.add(event.eventId);
    if (seen.size > 256) seen.delete(seen.values().next().value);
    if (event.type === "ROOM_STATE") {
      setStatus(event.payload.status);
      showParticipants(event.payload.participants);
      window.dispatchEvent(new Event("voicelink-room-state"));
    } else if (event.type === "TEXT_MESSAGE") {
      addText(event.payload);
    } else if (event.type === "ROOM_CLOSED") {
      setStatus("CLOSED");
      window.dispatchEvent(new Event("voicelink-room-state"));
      updateConnection("Live connection closed", "closed");
      socket.close();
    } else if (event.type === "ERROR") {
      updateConnection(event.payload.message || "Live message rejected", "disconnected");
    } else if (["TURN_ACCEPTED", "TRANSCRIPT_READY", "TRANSLATION_READY",
      "AUDIO_READY", "TURN_FAILED"].includes(event.type)) {
      receiveTurn(event);
    }
  }

  async function mayReconnect() {
    try {
      const response = await fetch("/api/rooms/" + roomId + "/live-state", {
        credentials: "same-origin", headers: { Accept: "application/json" }
      });
      if (response.redirected || response.status === 401 || response.status === 403 ||
          response.status === 404) return false;
      if (!response.ok) return true;
      const state = await response.json();
      if (state.status === "CLOSED") {
        setStatus("CLOSED");
        window.dispatchEvent(new Event("voicelink-room-state"));
        updateConnection("Live connection closed", "closed");
        return false;
      }
      return true;
    } catch { return true; }
  }

  async function connect() {
    if (stopped) return;
    if (typeof WebSocket === "undefined") {
      updateConnection("This browser does not support live rooms", "disconnected");
      return;
    }
    updateConnection(retry ? "Disconnected · Reconnecting" : "Connecting", "connecting");
    const scheme = location.protocol === "https:" ? "wss:" : "ws:";
    socket = new WebSocket(scheme + "//" + location.host + "/ws/rooms/" + roomId);
    socket.onopen = () => {
      connected = true;
      retry = 0;
      updateConnection("Connected", "connected");
      setStatus(status.textContent);
      refreshTurns();
      window.dispatchEvent(new Event("voicelink-room-state"));
    };
    socket.onmessage = message => receive(message.data);
    socket.onclose = async event => {
      connected = false;
      input.disabled = true;
      send.disabled = true;
      recordStart.disabled = true;
      if (recorder?.state === "recording") {
        discardRecording = true;
        recorder.stop();
      }
      if (stopped) return;
      if (event.code === 4001) {
        stopped = true;
        updateConnection("This room is open in a newer tab", "disconnected");
        return;
      }
      updateConnection("Disconnected · Reconnecting; missed live text is unavailable",
        "disconnected");
      if (!(await mayReconnect())) {
        stopped = true;
        if (status.textContent !== "CLOSED")
          updateConnection("Live access ended. Sign in or reopen this room.", "disconnected");
        return;
      }
      const delay = Math.min(15000, 1000 * (2 ** Math.min(retry++, 4)));
      setTimeout(connect, delay);
    };
  }

  form.addEventListener("submit", event => {
    event.preventDefault();
    if (!connected || !socketOpen() ||
        status.textContent !== "ACTIVE") return;
    const value = input.value.trim();
    if (!value || [...value].length > 1000) return;
    socket.send(JSON.stringify({
      protocolVersion: 1, type: "SEND_TEXT", clientMessageId: crypto.randomUUID(), text: value
    }));
    input.value = "";
  });

  if (!speechSupported) {
    recordingStatus.textContent = "This browser cannot record WebM/Opus audio for VoiceLink.";
    speechSection.dataset.phase = "error";
  } else recordingStatus.textContent = "Ready to record. Speak for up to 15 seconds.";
  setStatus(status.textContent);

  recordStart.addEventListener("click", async () => {
    if (busy || !connected || !socketOpen() ||
        status.textContent !== "ACTIVE" || !speechSupported) return;
    busy = true;
    speechSection.dataset.phase = "requesting";
    recordingStatus.textContent = "Requesting microphone access";
    discardRecording = false;
    recordStart.disabled = true;
    try {
      microphone = await navigator.mediaDevices.getUserMedia({ audio: true });
      if (status.textContent !== "ACTIVE" || !connected) {
        microphone.getTracks().forEach(track => track.stop());
        busy = false;
        speechSection.dataset.phase = "idle";
        setStatus(status.textContent);
        return;
      }
      const chunks = [];
      recorder = new MediaRecorder(microphone, { mimeType: speechType });
      recorder.ondataavailable = event => { if (event.data.size) chunks.push(event.data); };
      recorder.onstop = async () => {
        clearTimeout(stopTimer);
        clearInterval(displayTimer);
        microphone.getTracks().forEach(track => track.stop());
        recordStartLabel.textContent = "Start speaking";
        recordStop.disabled = true;
        speechSection.dataset.recording = "false";
        speechSection.dataset.phase = "uploading";
        recordingTimer.hidden = true;
        const durationMillis = Math.min(15000, Math.round(performance.now() - startedAt));
        const blob = new Blob(chunks, { type: speechType });
        if (discardRecording || !connected || status.textContent !== "ACTIVE") {
          recordingStatus.textContent = "Recording ended because the room connection changed. Reconnect and try again.";
          speechSection.dataset.phase = "error";
          busy = false; setStatus(status.textContent); return;
        }
        if (!blob.size || blob.size > 1024 * 1024 || durationMillis < 100) {
          recordingStatus.textContent = "That recording could not be used. Keep it under 15 seconds and try again.";
          speechSection.dataset.phase = "error";
          busy = false; setStatus(status.textContent); return;
        }
        recordingStatus.textContent = "Uploading speech";
        const data = new FormData();
        data.append("clientRequestId", crypto.randomUUID());
        data.append("durationMillis", String(durationMillis));
        data.append("audio", blob, "turn.webm");
        let accepted = false;
        try {
          const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;
          const csrfToken = document.querySelector('meta[name="_csrf"]').content;
          const response = await fetch("/api/rooms/" + roomId + "/turns", {
            method: "POST", credentials: "same-origin", headers: { [csrfHeader]: csrfToken },
            body: data
          });
          recordingStatus.textContent = response.ok ? "Speech accepted for processing" :
            response.status === 429 ? "Too many speech turns. Wait a minute and try again." :
            response.status === 503 ? "Speech processing is busy. Try again shortly." :
            "Speech upload failed. Check room access and recording limits.";
          accepted = response.ok;
          if (response.ok) refreshTurns();
        } catch {
          recordingStatus.textContent = "Speech upload failed. Check your connection and try again.";
        } finally {
          busy = false;
          speechSection.dataset.phase = accepted ? "accepted" : "error";
          setStatus(status.textContent);
        }
      };
      recorder.start();
      startedAt = performance.now();
      recordStop.disabled = false;
      speechSection.dataset.recording = "true";
      speechSection.dataset.phase = "listening";
      recordStartLabel.textContent = "Listening";
      recordingStatus.textContent = "Recording. Stop within 15 seconds.";
      recordingTimer.hidden = false;
      recordingTimer.textContent = "0.0s / 15s";
      displayTimer = setInterval(() => {
        recordingTimer.textContent = Math.min(15, (performance.now() - startedAt) / 1000)
          .toFixed(1) + "s / 15s";
      }, 100);
      stopTimer = setTimeout(() => {
        if (recorder.state === "recording") recorder.stop();
      }, 15000);
    } catch (error) {
      clearTimeout(stopTimer);
      clearInterval(displayTimer);
      microphone?.getTracks().forEach(track => track.stop());
      recordStop.disabled = true;
      recordingTimer.hidden = true;
      speechSection.dataset.recording = "false";
      speechSection.dataset.phase = "error";
      recordStartLabel.textContent = "Start speaking";
      recordingStatus.textContent = error?.name === "NotAllowedError" ||
        error?.name === "SecurityError"
        ? "Microphone access was denied. Allow access in your browser and try again."
        : "Microphone recording is unavailable. Check your device and try again.";
      busy = false;
      setStatus(status.textContent);
    }
  });
  recordStop.addEventListener("click", () => {
    if (recorder?.state === "recording") recorder.stop();
  });

  if (copyButton && inviteCode) {
    copyButton.addEventListener("click", async () => {
      try {
        if (!navigator.clipboard?.writeText) throw new Error("Clipboard unavailable");
        await navigator.clipboard.writeText(inviteCode.textContent.trim());
        copyStatus.textContent = "Copied";
      } catch {
        const selection = window.getSelection();
        const range = document.createRange();
        range.selectNodeContents(inviteCode);
        selection.removeAllRanges();
        selection.addRange(range);
        copyStatus.textContent = "Code selected. Copy it with your keyboard.";
      }
    });
  }
  if (inviteExpiry?.dateTime) {
    const date = new Date(inviteExpiry.dateTime);
    if (!Number.isNaN(date.getTime())) inviteExpiry.textContent = date.toLocaleString();
  }
  closeForm?.addEventListener("submit", event => {
    const message = status.textContent === "WAITING"
      ? "Cancel this room? Its invite and saved glossary will be deleted."
      : "Close this room for both people? The conversation ends and its saved glossary is deleted.";
    if (!window.confirm(message)) event.preventDefault();
  });

  setInterval(() => {
    if (connected && status.textContent === "ACTIVE") refreshTurns();
  }, 60_000);
  connect();
})();
