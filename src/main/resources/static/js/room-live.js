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
  const recordStart = document.getElementById("record-start");
  const recordStop = document.getElementById("record-stop");
  const recordingStatus = document.getElementById("recording-status");
  const speechEvents = document.getElementById("speech-events");
  const speechType = "audio/webm;codecs=opus";
  const speechSupported = !!(navigator.mediaDevices?.getUserMedia &&
    window.MediaRecorder?.isTypeSupported(speechType));
  const turnRows = new Map();
  let recorder;
  let microphone;
  let startedAt;
  let stopTimer;
  let displayTimer;
  let busy = false;
  let socket;
  let retry = 0;
  let stopped = false;
  let streamId;
  let lastSequence = 0;
  const seen = new Set();

  function setStatus(value) {
    status.textContent = value;
    waiting.hidden = value !== "WAITING";
    active.hidden = value !== "ACTIVE";
    live.hidden = value !== "ACTIVE";
    speechSection.hidden = value !== "ACTIVE";
    notice.hidden = value !== "CLOSED";
    if (closeForm) closeForm.hidden = value === "CLOSED";
    if (closeButton) closeButton.textContent =
      value === "WAITING" ? "Cancel room" : "Leave and close room";
    input.disabled = value !== "ACTIVE" || !socket || socket.readyState !== WebSocket.OPEN;
    send.disabled = input.disabled;
    recordStart.disabled = value !== "ACTIVE" || !speechSupported || busy;
    if (value !== "ACTIVE" && recorder?.state === "recording") recorder.stop();
    if (value === "CLOSED") stopped = true;
  }

  function rowFor(turnId, turnIndex) {
    if (turnRows.has(turnId)) return turnRows.get(turnId);
    const row = document.createElement("li");
    row.dataset.index = String(turnIndex);
    const title = document.createElement("strong");
    title.textContent = `Turn ${turnIndex}: `;
    const progress = document.createElement("span");
    progress.textContent = "Processing";
    const source = document.createElement("p");
    const target = document.createElement("p");
    const playback = document.createElement("div");
    row.append(title, progress, source, target, playback);
    const next = [...speechEvents.children].find(item => Number(item.dataset.index) > turnIndex);
    speechEvents.insertBefore(row, next || null);
    const parts = { row, progress, source, target, playback };
    turnRows.set(turnId, parts);
    return parts;
  }

  function showAudio(parts, url) {
    if (!url?.startsWith(`/api/rooms/${roomId}/turns/`) || parts.playback.childElementCount) return;
    const player = document.createElement("audio");
    player.controls = true;
    player.preload = "none";
    player.src = url;
    player.setAttribute("aria-label", "Play translated speech");
    parts.playback.append(player);
  }

  function receiveTurn(event) {
    const payload = event.payload || {};
    if (!Number.isSafeInteger(payload.turnIndex) || !event.turnId) return;
    const parts = rowFor(event.turnId, payload.turnIndex);
    if (event.type === "TURN_ACCEPTED") parts.progress.textContent = "Processing speech";
    if (event.type === "TRANSCRIPT_READY") {
      parts.source.textContent = `Original (${payload.sourceLanguage}): ${payload.transcript}`;
      parts.progress.textContent = "Translating";
    }
    if (event.type === "TRANSLATION_READY") {
      parts.target.textContent = `Translation (${payload.targetLanguage}): ${payload.translatedText}`;
      parts.progress.textContent = "Preparing audio";
    }
    if (event.type === "AUDIO_READY") {
      showAudio(parts, payload.audioUrl);
      parts.progress.textContent = "Audio ready";
    }
    if (event.type === "TURN_FAILED") {
      parts.progress.textContent = `${payload.stage || "Speech"}: ${payload.message || "Processing failed."}`;
    }
  }

  async function refreshTurns() {
    try {
      const response = await fetch(`/api/rooms/${roomId}/turns/recent`, { credentials: "same-origin" });
      if (!response.ok) return;
      for (const turn of await response.json()) {
        const parts = rowFor(turn.turnId, turn.turnIndex);
        if (turn.transcript) parts.source.textContent = `Original (${turn.sourceLanguage}): ${turn.transcript}`;
        if (turn.translatedText) parts.target.textContent = `Translation (${turn.targetLanguage}): ${turn.translatedText}`;
        if (turn.audioAvailable) showAudio(parts, `/api/rooms/${roomId}/turns/${turn.turnId}/audio`);
        parts.progress.textContent = turn.status === "FAILED"
          ? `${turn.failedStage}: ${turn.errorCode}` : turn.status === "READY" ? "Audio ready" : "Processing speech";
      }
    } catch { /* Live connection remains available if snapshot retrieval fails. */ }
  }

  function showParticipants(people) {
    if (!Array.isArray(people)) return;
    members.replaceChildren();
    for (const person of people) {
      const item = document.createElement("li");
      item.textContent = `${person.displayName} — speaks ${person.speaking}, receives ${person.listening}`;
      members.append(item);
    }
  }

  function addText(payload) {
    const item = document.createElement("li");
    item.textContent = payload.text;
    events.append(item);
  }

  function receive(raw) {
    let event;
    try { event = JSON.parse(raw); } catch { return; }
    if (event.protocolVersion !== 1 || event.roomId !== roomId) return;
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
    } else if (event.type === "TEXT_MESSAGE") {
      addText(event.payload);
    } else if (event.type === "ROOM_CLOSED") {
      setStatus("CLOSED");
      connection.textContent = "Live connection closed";
      socket.close();
    } else if (event.type === "ERROR") {
      connection.textContent = event.payload.message || "Live message rejected";
    } else if (["TURN_ACCEPTED", "TRANSCRIPT_READY", "TRANSLATION_READY", "AUDIO_READY", "TURN_FAILED"].includes(event.type)) {
      receiveTurn(event);
    }
  }

  async function mayReconnect() {
    try {
      const response = await fetch(`/api/rooms/${roomId}/live-state`, {
        credentials: "same-origin", headers: { Accept: "application/json" }
      });
      if (response.redirected || response.status === 401 || response.status === 403 ||
          response.status === 404) return false;
      if (!response.ok) return true;
      const state = await response.json();
      if (state.status === "CLOSED") {
        setStatus("CLOSED");
        connection.textContent = "Live connection closed";
        return false;
      }
      return true;
    } catch { return true; }
  }

  async function connect() {
    if (stopped) return;
    connection.textContent = retry ? "Disconnected / Reconnecting" : "Connecting";
    const scheme = location.protocol === "https:" ? "wss:" : "ws:";
    socket = new WebSocket(`${scheme}//${location.host}/ws/rooms/${roomId}`);
    socket.onopen = () => {
      retry = 0;
      connection.textContent = "Connected";
      setStatus(status.textContent);
      refreshTurns();
    };
    socket.onmessage = message => receive(message.data);
    socket.onclose = async event => {
      input.disabled = true;
      send.disabled = true;
      if (stopped) return;
      if (event.code === 4001) {
        stopped = true;
        connection.textContent = "Live connection moved to a newer room tab";
        return;
      }
      connection.textContent = "Disconnected / Reconnecting; missed text is unavailable";
      if (!(await mayReconnect())) {
        stopped = true;
        if (status.textContent !== "CLOSED") connection.textContent = "Live access ended. Sign in or reopen the room page.";
        return;
      }
      const delay = Math.min(15000, 1000 * (2 ** Math.min(retry++, 4)));
      setTimeout(connect, delay);
    };
  }

  form.addEventListener("submit", event => {
    event.preventDefault();
    if (!socket || socket.readyState !== WebSocket.OPEN || status.textContent !== "ACTIVE") return;
    const text = input.value.trim();
    if (!text || [...text].length > 1000) return;
    socket.send(JSON.stringify({
      protocolVersion: 1, type: "SEND_TEXT", clientMessageId: crypto.randomUUID(), text
    }));
    input.value = "";
  });
  if (!speechSupported) recordingStatus.textContent = "This browser does not support WebM/Opus microphone recording.";
  else recordingStatus.textContent = "Ready to record";

  recordStart.addEventListener("click", async () => {
    if (busy || status.textContent !== "ACTIVE" || !speechSupported) return;
    busy = true;
    recordStart.disabled = true;
    try {
      microphone = await navigator.mediaDevices.getUserMedia({ audio: true });
      if (status.textContent !== "ACTIVE") {
        microphone.getTracks().forEach(track => track.stop());
        busy = false;
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
        recordStop.disabled = true;
        const durationMillis = Math.min(15000, Math.round(performance.now() - startedAt));
        const blob = new Blob(chunks, { type: speechType });
        if (!blob.size || blob.size > 1024 * 1024 || durationMillis < 100 || status.textContent !== "ACTIVE") {
          recordingStatus.textContent = "Recording unavailable or outside the 15-second / 1 MiB limit.";
          busy = false; setStatus(status.textContent); return;
        }
        recordingStatus.textContent = "Uploading speech";
        const data = new FormData();
        data.append("clientRequestId", crypto.randomUUID());
        data.append("durationMillis", String(durationMillis));
        data.append("audio", blob, "turn.webm");
        try {
          const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;
          const csrfToken = document.querySelector('meta[name="_csrf"]').content;
          const response = await fetch(`/api/rooms/${roomId}/turns`, {
            method: "POST", credentials: "same-origin", headers: { [csrfHeader]: csrfToken }, body: data
          });
          recordingStatus.textContent = response.ok ? "Speech accepted for processing" :
            `Speech upload failed (${response.status}). Check room access and recording limits.`;
          if (response.ok) refreshTurns();
        } catch { recordingStatus.textContent = "Speech upload failed. Check the connection."; }
        finally { busy = false; setStatus(status.textContent); }
      };
      recorder.start();
      startedAt = performance.now();
      recordStop.disabled = false;
      recordingStatus.textContent = "Recording (up to 15 seconds)";
      displayTimer = setInterval(() => {
        recordingStatus.textContent = `Recording: ${Math.min(15, Math.ceil((performance.now() - startedAt) / 1000))} / 15 seconds`;
      }, 250);
      stopTimer = setTimeout(() => { if (recorder.state === "recording") recorder.stop(); }, 15000);
    } catch {
      clearTimeout(stopTimer);
      clearInterval(displayTimer);
      microphone?.getTracks().forEach(track => track.stop());
      recordingStatus.textContent = "Microphone permission or recording is unavailable.";
      busy = false; setStatus(status.textContent);
    }
  });
  recordStop.addEventListener("click", () => { if (recorder?.state === "recording") recorder.stop(); });
  connect();
})();
