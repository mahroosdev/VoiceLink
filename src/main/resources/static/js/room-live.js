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
  const quickStatus = document.getElementById("quick-status");
  const events = document.getElementById("conversation-events");
  const liveDrafts = document.getElementById("live-drafts");
  const speechSection = document.getElementById("speech-section");
  const glossarySection = document.getElementById("glossary-section");
  const roomToolBar = document.querySelector(".room-tool-bar");
  const roomDetails = document.getElementById("room-details");
  const roomDetailsOpen = document.getElementById("room-details-open");
  const roomDetailsClose = document.getElementById("room-details-close");
  const roomMainGrid = document.querySelector(".room-main-grid");
  const languageForm = document.getElementById("room-language-form");
  const languageSelect = document.getElementById("owner-speaking-language");
  const languageStatus = document.getElementById("room-language-status");
  const recordStart = document.getElementById("record-start");
  const recordStartLabel = recordStart.querySelector("span");
  const recordStop = document.getElementById("record-stop");
  const speechModeLive = document.getElementById("speech-mode-live");
  const speechModeFinish = document.getElementById("speech-mode-finish");
  const speechModeHelp = document.getElementById("speech-mode-help");
  const speechReview = document.getElementById("speech-review");
  const speechReviewTranscript = document.getElementById("speech-review-transcript");
  const speechReviewTranslation = document.getElementById("speech-review-translation");
  const speechReviewExpiry = document.getElementById("speech-review-expiry");
  const speechReviewSend = document.getElementById("speech-review-send");
  const speechReviewRecordAgain = document.getElementById("speech-review-record-again");
  const speechReviewCancel = document.getElementById("speech-review-cancel");
  const translationRetryPanel = document.getElementById("speech-translation-retry");
  const translationRetryTranscript = document.getElementById("speech-translation-retry-transcript");
  const translationRetryStatus = document.getElementById("speech-translation-retry-status");
  const translationRetryButton = document.getElementById("speech-translation-retry-button");
  const recordingStatus = document.getElementById("recording-status");
  const recordingTimer = document.getElementById("recording-timer");
  const speechEvents = document.getElementById("conversation-events");
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
  const participantRoles = new Map();

  let recorder;
  let microphone;
  let startedAt;
  let stopTimer;
  let displayTimer;
  let busy = false;
  let discardRecording = false;
  let speechWorkflow = "LIVE_SEND";
  let privateTurnId;
  let reviewGeneration = 0;
  let reviewPollTimer;
  let connected = false;
  let socket;
  let retry = 0;
  let stopped = false;
  let streamId;
  let lastSequence = 0;
  const seen = new Set();
  const seenQuickIds = new Set();
  const draftRows = new Map();
  const QUICK_RETRY_WINDOW_MS = 30 * 60 * 1000;
  let pendingQuick = null;
  let pendingQuickExpiryTimer;
  let failedTranslationTurnId = null;
  let translationRetryBusy = false;

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

  function csrfHeaders() {
    const header = document.querySelector('meta[name="_csrf_header"]').content;
    const token = document.querySelector('meta[name="_csrf"]').content;
    return { [header]: token };
  }

  languageForm?.addEventListener("submit", async event => {
    event.preventDefault();
    if (!languageSelect || !languageStatus) return;
    languageStatus.textContent = "Saving language settings…";
    try {
      const response = await fetch("/api/rooms/" + roomId + "/languages", {
        method: "POST", credentials: "same-origin",
        headers: { ...csrfHeaders(), "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({ ownerSpeaking: languageSelect.value })
      });
      if (!response.ok) {
        let body = {};
        try { body = await response.json(); } catch { /* plain error response */ }
        languageStatus.textContent = body.message || "Language settings could not be saved. Refresh and try again.";
        return;
      }
      languageStatus.textContent = "Languages updated for both participants.";
    } catch {
      languageStatus.textContent = "Language settings could not be saved. Check your connection and try again.";
    }
  });

  function resetPrivateReview() {
    clearTimeout(reviewPollTimer);
    reviewGeneration++;
    privateTurnId = undefined;
    speechReview.hidden = true;
    speechReviewTranscript.textContent = "";
    speechReviewTranslation.textContent = "";
    speechReviewExpiry.textContent = "";
    speechReviewSend.disabled = false;
  }

  function clearQuickExpiryTimer() {
    clearTimeout(pendingQuickExpiryTimer);
    pendingQuickExpiryTimer = undefined;
  }

  function refreshQuickRetryState() {
    if (!pendingQuick || pendingQuick.expired) return;
    const elapsed = Math.max(performance.now() - pendingQuick.firstAttemptAt,
      Date.now() - pendingQuick.firstAttemptWallAt);
    if (elapsed < QUICK_RETRY_WINDOW_MS) return;
    pendingQuick.expired = true;
    clearQuickExpiryTimer();
    quickStatus.textContent = "Delivery unconfirmed after 30 minutes. Automatic Quick chat retries stopped. Sending again may create a duplicate.";
    send.textContent = "Send again (may duplicate)";
  }

  function beginQuickAttempt(id, text) {
    clearQuickExpiryTimer();
    pendingQuick = { id, text, firstAttemptAt: performance.now(),
      firstAttemptWallAt: Date.now(), expired: false };
    pendingQuickExpiryTimer = setTimeout(refreshQuickRetryState, QUICK_RETRY_WINDOW_MS);
  }

  function transmitPendingQuick() {
    refreshQuickRetryState();
    if (!pendingQuick || pendingQuick.expired || !connected || !socketOpen()
        || status.textContent !== "ACTIVE") return;
    try {
      socket.send(JSON.stringify({
        protocolVersion: 1, type: "SEND_TEXT", clientMessageId: pendingQuick.id, text: pendingQuick.text
      }));
      quickStatus.textContent = "Sending quick chat message…";
    } catch {
      quickStatus.textContent = "Delivery is unconfirmed. Your message remains here; retry uses the same ID before the 30-minute deadline.";
    }
  }

  async function cancelPrivateReview() {
    const turnId = privateTurnId;
    resetPrivateReview();
    if (!turnId) return;
    try {
      await fetch("/api/rooms/" + roomId + "/turns/" + turnId + "/review", {
        method: "DELETE", credentials: "same-origin", headers: csrfHeaders()
      });
    } catch {
      // Session and room lifecycle cleanup also revoke unsent private reviews.
    }
  }

  function selectSpeechMode(workflow) {
    if (speechWorkflow === workflow) return;
    cancelPrivateReview();
    speechWorkflow = workflow;
    speechModeLive.setAttribute("aria-pressed", String(workflow === "LIVE_SEND"));
    speechModeFinish.setAttribute("aria-pressed", String(workflow === "FINISH_AND_SEND"));
    speechModeHelp.textContent = workflow === "LIVE_SEND"
      ? "Record and send the translated turn."
      : "Review your speech before sending.";
    recordingStatus.textContent = workflow === "LIVE_SEND"
      ? "Ready for Live Send. Speak for up to 15 seconds."
      : "Ready for private review. Speak for up to 15 seconds.";
  }

  async function pollPrivateReview(turnId, generation, attempt = 0) {
    if (generation !== reviewGeneration || turnId !== privateTurnId) return;
    if (attempt >= 80) {
      cancelPrivateReview();
      recordingStatus.textContent = "Private review timed out. Record again.";
      speechSection.dataset.phase = "error";
      return;
    }
    try {
      const response = await fetch("/api/rooms/" + roomId + "/turns/" + turnId + "/review",
        { credentials: "same-origin", headers: { Accept: "application/json" } });
      if (generation !== reviewGeneration || turnId !== privateTurnId) return;
      if (response.status === 404 || response.status === 409) {
        resetPrivateReview();
        recordingStatus.textContent = "That private review is no longer available. Record again.";
        speechSection.dataset.phase = "error";
        return;
      }
      if (!response.ok) throw new Error("Review unavailable");
      const review = await response.json();
      if (review.status === "READY_FOR_REVIEW") {
        speechReviewTranscript.textContent = review.transcript;
        speechReviewTranscript.lang = review.sourceLanguage;
        speechReviewTranslation.textContent = review.translatedText;
        speechReviewTranslation.lang = review.targetLanguage;
        speechReviewExpiry.textContent = "Available for two minutes";
        speechReview.hidden = false;
        speechReviewSend.focus();
        recordingStatus.textContent = "Private review ready. Check it, then send or record again.";
        speechSection.dataset.phase = "review";
        const expiresIn = Math.max(0, Date.parse(review.expiresAt) - Date.now());
        reviewPollTimer = setTimeout(() => {
          if (generation !== reviewGeneration || turnId !== privateTurnId) return;
          resetPrivateReview();
          recordingStatus.textContent = "That private review expired. Record again.";
          speechSection.dataset.phase = "error";
        }, Math.min(expiresIn + 50, 120050));
        return;
      }
      recordingStatus.textContent = review.status === "TRANSCRIBED"
        ? "Speech recognized. Translating privately."
        : "Recognizing speech privately.";
      if (review.status === "FAILED") {
        resetPrivateReview();
        recordingStatus.textContent = failureText(review.failedStage, review.errorCode);
        speechSection.dataset.phase = "error";
        return;
      }
      reviewPollTimer = setTimeout(() => pollPrivateReview(turnId, generation, attempt + 1), 750);
    } catch {
      reviewPollTimer = setTimeout(() => pollPrivateReview(turnId, generation, attempt + 1), 750);
    }
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
    live.hidden = value !== "ACTIVE" || document.getElementById("mode-type")?.getAttribute("aria-pressed") !== "true"
      || document.getElementById("text-mode-quick")?.getAttribute("aria-pressed") !== "true";
    speechSection.hidden = value !== "ACTIVE" || document.getElementById("mode-type")?.getAttribute("aria-pressed") === "true";
    if (value === "CLOSED" && glossarySection?.open) glossarySection.close();
    if (value === "CLOSED" && roomDetails?.open) roomDetails.close();
    if (glossarySection) glossarySection.hidden = value === "CLOSED";
    if (roomDetails) roomDetails.hidden = value === "CLOSED";
    if (roomToolBar) roomToolBar.hidden = value === "CLOSED";
    roomMainGrid.classList.toggle("room-main-grid--waiting", value === "WAITING");
    roomMainGrid.classList.toggle("room-main-grid--closed", value === "CLOSED");
    if (value === "CLOSED") {
      if (roomDetails) roomDetails.hidden = true;
    }
    notice.hidden = value !== "CLOSED";
    if (closeForm) closeForm.hidden = value === "CLOSED";
    if (closeButton) closeButton.textContent =
      value === "WAITING" ? "Cancel room" : "Leave and close room";
    refreshQuickRetryState();
    input.disabled = value !== "ACTIVE" || !connected || !socketOpen() || !!pendingQuick;
    send.disabled = value !== "ACTIVE" || !connected || !socketOpen();
    send.textContent = pendingQuick?.expired ? "Send again (may duplicate)" : pendingQuick ? "Retry send" : "Send";
    recordStart.disabled = value !== "ACTIVE" || !connected ||
      !socketOpen() || !speechSupported || busy;
    if (value !== "ACTIVE" && recorder?.state === "recording") {
      discardRecording = true;
      recorder.stop();
    }
    if (value === "CLOSED") {
      resetPrivateReview();
      clearQuickExpiryTimer();
      speechSection.dataset.phase = "idle";
      stopped = true;
      speechEvents.replaceChildren();
      turnRows.clear();
      events.replaceChildren();
      pendingQuick = null;
      seenQuickIds.clear();
      turnEmpty.hidden = false;
      recordingStatus.textContent = "This room is closed.";
    }
  }

  roomDetailsOpen?.addEventListener("click", () => {
    if (status.textContent === "CLOSED" || !roomDetails) return;
    if (glossarySection?.open) glossarySection.close();
    roomDetails.showModal();
    roomDetailsClose?.focus();
  });
  roomDetailsClose?.addEventListener("click", () => roomDetails?.close());
  roomDetails?.addEventListener("close", () => {
    if (status.textContent !== "CLOSED") roomDetailsOpen?.focus();
  });

  function rowFor(turnId, turnIndex, senderId) {
    if (turnRows.has(turnId)) {
      const existing = turnRows.get(turnId);
      if (senderId) {
        existing.row.dataset.senderId = senderId;
        applySender(existing, senderId);
      }
      return existing;
    }
    const wasNearBottom = window.voicelinkHistory?.beforeAppend();
    const role = participantRoles.get(senderId) || "Participant";
    const row = make("li", "turn-card turn-card--" + role.toLowerCase());
    row.dataset.index = String(turnIndex);
    row.dataset.timelineKind = "speech";
    row.dataset.messageKind = "processing";
    if (senderId) row.dataset.senderId = senderId;
    const top = make("div", "turn-card__top");
    const meta = make("div", "turn-card__meta");
    row.dataset.senderRole = role;
    const speaker = make("strong", "turn-card__speaker", role);
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
    window.voicelinkHistory?.changed(wasNearBottom, "processing");
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

  function applySender(parts, senderId) {
    const role = participantRoles.get(senderId) || "Participant";
    parts.speaker.textContent = role;
    parts.row.dataset.senderRole = role;
    parts.row.classList.toggle("turn-card--you", role === "You");
    parts.row.classList.toggle("turn-card--partner", role === "Partner");
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
    if (!isSource && typeof textValue === "string" && textValue.trim())
      parts.row.dataset.messageKind = "translated";
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

  function failureText(stage, code) {
    if (stage === "STT") {
      if (code === "TIMEOUT") return "Speech recognition took too long. Try another turn.";
      if (code === "RATE_LIMIT" || code === "QUOTA")
        return "Speech recognition is busy or limited. Wait and try another turn.";
      return "Speech recognition failed. Check your connection and try another turn.";
    }
    if (stage === "TRANSLATION") {
      if (code === "TIMEOUT") return "Translation took too long. The recognized caption remains available.";
      if (code === "RATE_LIMIT" || code === "QUOTA")
        return "Translation is busy or limited. The recognized caption remains available.";
      if (code === "INVALID_RESPONSE")
        return "The translation response could not be processed. The recognized caption remains available.";
      return "Translation is temporarily unavailable. The recognized caption remains available.";
    }
    if (stage === "TTS") {
      if (code === "TIMEOUT") return "Audio generation took too long. Captions are still available.";
      return "Audio generation failed. Captions are still available.";
    }
    return "Speech processing could not finish. Try another turn.";
  }

  function receiveTurn(event) {
    const payload = event.payload || {};
    if (!Number.isSafeInteger(payload.turnIndex) || !event.turnId) return;
    if (event.type === "TURN_FAILED" && payload.stage === "TRANSLATION"
        && typeof payload.transcript === "string") {
      showTranslationRetry(event.turnId, payload.transcript, payload.sourceLanguage, payload.message);
      return;
    }
    const parts = rowFor(event.turnId, payload.turnIndex, event.senderParticipantId);
    if (event.type === "TURN_ACCEPTED") {
      if (failedTranslationTurnId === event.turnId) {
        translationRetryPanel.hidden = true;
        translationRetryStatus.textContent = "Your recognized text is kept temporarily for retry.";
        translationRetryButton.disabled = false;
        failedTranslationTurnId = null;
      }
      parts.progress.textContent = "Recognizing speech";
    }
    if (event.type === "TURN_ACCEPTED") parts.row.dataset.phase = "recognizing";
    if (event.type === "TRANSCRIPT_READY") {
      showCaption(parts, "source", payload.sourceLanguage, payload.transcript);
      parts.progress.textContent = "Translating";
      parts.row.dataset.phase = "translating";
    }
    if (event.type === "TRANSLATION_READY") {
      const wasNearBottom = window.voicelinkHistory?.beforeAppend();
      showCaption(parts, "target", payload.targetLanguage, payload.translatedText);
      const sender = participantRoles.get(event.senderParticipantId) || "Participant";
      window.voicelinkHistory?.changed(wasNearBottom, "translated", sender === "You");
      parts.progress.textContent = "Creating voice";
      parts.row.dataset.phase = "voice";
    }
    if (event.type === "AUDIO_READY") {
      showAudio(parts, payload.audioUrl);
      parts.progress.textContent = "Audio ready";
      parts.row.dataset.phase = "ready";
    }
    if (event.type === "TURN_FAILED") {
      parts.progress.textContent = failureText(payload.stage, payload.code);
      parts.progress.dataset.failed = "true";
      parts.row.dataset.phase = "error";
    }
    turnAnnouncement.textContent = "Turn " + payload.turnIndex + ": " + parts.progress.textContent;
  }

  function showTranslationRetry(turnId, transcript, sourceLanguage, message) {
    failedTranslationTurnId = turnId;
    translationRetryTranscript.textContent = transcript;
    translationRetryTranscript.lang = sourceLanguage === "ta" ? "ta" : "en";
    translationRetryStatus.textContent = message || "Translation failed. Your recognized text is kept temporarily.";
    translationRetryButton.disabled = translationRetryBusy;
    translationRetryPanel.hidden = false;
    recordingStatus.textContent = "Translation failed. Retry with the recognized text, or record a new turn.";
    turnAnnouncement.textContent = "Translation failed. The recognized speech is available only to you for retry.";
  }

  async function refreshTurns() {
    if (status.textContent !== "ACTIVE") return;
    try {
      const knownIdsAtStart = new Set(turnRows.keys());
      const response = await fetch("/api/rooms/" + roomId + "/turns/recent",
        { credentials: "same-origin" });
      if (!response.ok || status.textContent === "CLOSED") return;
      const recent = await response.json();
      if (status.textContent === "CLOSED") return;
      const currentIds = new Set(recent.map(turn => turn.turnId));
      for (const turn of recent) {
        if (turn.status === "FAILED" && turn.failedStage === "TRANSLATION"
            && typeof turn.transcript === "string") {
          showTranslationRetry(turn.turnId, turn.transcript, turn.sourceLanguage,
            "Translation failed. Your recognized text is kept temporarily.");
          continue;
        }
        const parts = rowFor(turn.turnId, turn.turnIndex, turn.senderParticipantId);
        if (turn.transcript) showCaption(parts, "source", turn.sourceLanguage, turn.transcript);
        if (turn.translatedText) showCaption(parts, "target", turn.targetLanguage, turn.translatedText);
        if (turn.audioAvailable) showAudio(parts,
          "/api/rooms/" + roomId + "/turns/" + turn.turnId + "/audio");
        parts.progress.textContent = turn.status === "FAILED" ? failureText(turn.failedStage, turn.errorCode) :
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
      turnEmpty.hidden = speechEvents.childElementCount !== 0;
      window.voicelinkHistory?.refresh();
    } catch {
      // The live connection can remain available if the snapshot request fails.
    }
  }

  translationRetryButton.addEventListener("click", async () => {
    if (!failedTranslationTurnId || translationRetryBusy || !connected
        || status.textContent !== "ACTIVE") return;
    translationRetryBusy = true;
    translationRetryButton.disabled = true;
    translationRetryStatus.textContent = "Retrying translation with the recognized text…";
    try {
      const response = await fetch("/api/rooms/" + roomId + "/turns/"
        + failedTranslationTurnId + "/retry-translation", {
        method: "POST", credentials: "same-origin", headers: csrfHeaders()
      });
      if (response.ok) {
        translationRetryStatus.textContent = "Translation is processing. Your partner will see the turn only if translation succeeds.";
      } else {
        let body = {};
        try { body = await response.json(); } catch { /* response may have no JSON body */ }
        translationRetryStatus.textContent = body.message || body.error
          || "Retry is unavailable. Your recognized text remains here while this room is open.";
        translationRetryButton.disabled = response.status === 404 || response.status === 409;
      }
    } catch {
      translationRetryStatus.textContent = "Retry could not reach the server. Reconnect and try again.";
      translationRetryButton.disabled = false;
    } finally {
      translationRetryBusy = false;
      if (!translationRetryButton.disabled) translationRetryButton.disabled = false;
    }
  });

  function showParticipants(people) {
    if (!Array.isArray(people)) return;
    members.replaceChildren();
    participantRoles.clear();
    for (const person of people) {
      const role = person.current === true ? "You" : "Partner";
      participantRoles.set(person.participantId, role);
      const item = make("li", "participant");
      item.dataset.participantId = person.participantId;
      item.dataset.role = role;
      item.dataset.current = String(person.current === true);
      item.dataset.owner = String(person.owner === true);
      item.dataset.speaking = person.speaking || "";
      item.dataset.listening = person.listening || "";
      const avatar = make("span", "participant__avatar", role === "You" ? "Y" : "P");
      avatar.setAttribute("aria-hidden", "true");
      const details = make("span", "participant__details");
      const name = make("strong", "", (person.current === true ? "You" : person.displayName) +
        (person.owner === true ? " · Conversation Owner" : " · Partner"));
      const direction = make("small", "",
        role + " speak" + (role === "Partner" ? "s" : "") + ": " + languageName(person.speaking) +
        "\n" + (role === "You" ? "Partner receives: " : "You receive: ")
        + languageName(person.listening));
      details.append(name, direction);
      if (person.current === true) {
        details.append(make("span", "participant__account-name",
          "Conversation name: " + person.displayName));
      }
      item.append(avatar, details);
      members.append(item);
    }
    const current = people.find(person => person.current === true);
    const owner = people.find(person => person.owner === true);
    if (owner) {
      const ownerLanguage = owner.speaking === "ta" ? "Tamil" : "English";
      const partnerLanguage = owner.speaking === "ta" ? "English" : "Tamil";
      const direction = ownerLanguage + " ↔ " + partnerLanguage;
      const roomDirection = document.getElementById("room-direction");
      const roomPair = document.getElementById("room-language-pair");
      if (roomDirection) roomDirection.textContent = direction + " · Two people";
      if (roomPair) roomPair.textContent = direction;
      if (languageSelect && document.activeElement !== languageSelect) languageSelect.value = owner.speaking;
    }
    if (current) {
      page.dataset.sourceLanguage = current.speaking || page.dataset.sourceLanguage;
      const romanizedOption = document.querySelector(".romanized-tamil-option");
      const romanizedToggle = document.getElementById("romanized-tamil");
      const available = current.speaking === "ta" && current.listening === "en";
      if (romanizedOption) romanizedOption.hidden = !available;
      if (!available && romanizedToggle) romanizedToggle.checked = false;
    }
    for (const parts of turnRows.values()) {
      applySender(parts, parts.row.dataset.senderId);
    }
  }

  function addText(event) {
    const payload = event.payload || {};
    if (typeof payload.text !== "string") return;
    const role = participantRoles.get(event.senderParticipantId) || "Participant";
    const messageId = payload.clientMessageId;
    if (role === "You" && pendingQuick?.id === messageId) {
      if (input.value.trim() === pendingQuick.text) input.value = "";
      pendingQuick = null;
      quickStatus.textContent = "Sent. Quick chat is ready for the next message.";
      setStatus(status.textContent);
      input.focus();
    }
    const deliveryId = event.senderParticipantId + ":" + messageId;
    if (messageId && seenQuickIds.has(deliveryId)) return;
    if (messageId) {
      seenQuickIds.add(deliveryId);
      if (seenQuickIds.size > 256) seenQuickIds.delete(seenQuickIds.values().next().value);
    }
    const wasNearBottom = window.voicelinkHistory?.beforeAppend();
    const row = make("li", "turn-card text-turn-card untranslated-turn turn-card--" + role.toLowerCase());
    row.dataset.senderId = event.senderParticipantId || "";
    row.dataset.senderRole = role;
    row.dataset.timelineKind = "text";
    row.dataset.messageKind = "quick";
    const heading = make("strong", "turn-card__speaker", role + " · Quick chat");
    const content = make("p", "quick-chat__text", payload.text);
    const delivery = make("span", "quick-chat__delivery", role === "You" ? "Sent" : "Received");
    row.append(heading, content, delivery);
    events.append(row);
    const textRows = [...events.children].filter(item => item.dataset.timelineKind === "text");
    while (textRows.length > 16) textRows.shift().remove();
    turnEmpty.hidden = true;
    turnAnnouncement.textContent = role + " sent a quick chat message.";
    window.voicelinkHistory?.changed(wasNearBottom, "quick", role === "You");
  }

  function updateLiveDraft(event) {
    const payload = event.payload || {};
    if (!liveDrafts || typeof payload.draftId !== "string"
        || typeof payload.translatedText !== "string"
        || !Number.isSafeInteger(payload.revision)) return;
    let row = draftRows.get(payload.draftId);
    if (row && Number(row.dataset.revision) >= payload.revision) return;
    const role = participantRoles.get(event.senderParticipantId) || "Participant";
    if (!row) {
      row = make("article", "live-draft");
      row.dataset.draftId = payload.draftId;
      row.dataset.senderId = event.senderParticipantId || "";
      const label = make("strong", "live-draft__label");
      const text = make("p", "live-draft__text");
      row.append(label, text);
      draftRows.set(payload.draftId, row);
      liveDrafts.append(row);
    }
    row.dataset.revision = String(payload.revision);
    row.querySelector(".live-draft__label").textContent = role + " · Live translation · Updating";
    const text = row.querySelector(".live-draft__text");
    text.textContent = payload.translatedText;
    text.lang = payload.targetLanguage;
  }

  function clearLiveDraft(event) {
    const draftId = event.payload?.draftId;
    const row = typeof draftId === "string" ? draftRows.get(draftId) : null;
    if (row) row.remove();
    if (typeof draftId === "string") draftRows.delete(draftId);
  }

  function clearAllLiveDrafts() {
    draftRows.clear();
    liveDrafts?.replaceChildren();
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
      seenQuickIds.clear();
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
      addText(event);
    } else if (event.type === "TEXT_TRANSLATION_SENT") {
      if (event.payload?.draftId) clearLiveDraft(event);
      window.dispatchEvent(new CustomEvent("voicelink-final-text", { detail: event }));
    } else if (event.type === "TEXT_DRAFT_UPDATE") {
      updateLiveDraft(event);
    } else if (event.type === "TEXT_DRAFT_CLEAR") {
      clearLiveDraft(event);
    } else if (event.type === "ROOM_CLOSED") {
      clearAllLiveDrafts();
      setStatus("CLOSED");
      window.dispatchEvent(new Event("voicelink-room-state"));
      updateConnection("Live connection closed", "closed");
      socket.close();
    } else if (event.type === "ERROR") {
      updateConnection(event.payload.message || "Live action rejected",
        ["ROOM_NOT_ACTIVE", "NOT_AUTHORIZED"].includes(event.payload?.code) || !connected
          ? "disconnected" : "connected");
      if (pendingQuick && ["INVALID_MESSAGE", "MESSAGE_TOO_LARGE", "ROOM_NOT_ACTIVE", "NOT_AUTHORIZED"]
          .includes(event.payload?.code)) {
        clearQuickExpiryTimer();
        pendingQuick = null;
        quickStatus.textContent = "Quick chat was not sent. Your message remains here; check it and try again.";
        setStatus(status.textContent);
      } else if (pendingQuick && event.payload?.code === "RATE_LIMITED") {
        clearQuickExpiryTimer();
        pendingQuick = null;
        quickStatus.textContent = event.payload.message || "Quick chat is temporarily limited. Your message remains here.";
        setStatus(status.textContent);
      }
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
      refreshQuickRetryState();
      if (pendingQuick && !pendingQuick.expired) {
        quickStatus.textContent = "Delivery is unconfirmed. Retrying with the same message ID.";
        transmitPendingQuick();
      }
      refreshTurns();
      window.dispatchEvent(new Event("voicelink-room-state"));
      window.dispatchEvent(new Event("voicelink-live-connected"));
      window.dispatchEvent(new CustomEvent("voicelink-connection-state", { detail: { connected: true } }));
    };
    socket.onmessage = message => receive(message.data);
    socket.onclose = async event => {
      connected = false;
      window.dispatchEvent(new CustomEvent("voicelink-connection-state", { detail: { connected: false } }));
      input.disabled = true;
      send.disabled = true;
      refreshQuickRetryState();
      if (pendingQuick && !pendingQuick.expired) quickStatus.textContent = "Delivery is unconfirmed. Your message remains here; the same ID is used for retries for up to 30 minutes.";
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

  window.addEventListener("voicelink-force-live-disconnect", () => {
    if (socketOpen()) socket.close(1000, "Sharing state reset");
  });
  window.addEventListener("voicelink-remove-live-draft", event => {
    const draftId = event.detail?.draftId;
    if (typeof draftId === "string") clearLiveDraft({ payload: { draftId } });
  });
  window.addEventListener("voicelink-speech-cancel-private", cancelPrivateReview);

  form.addEventListener("submit", event => {
    event.preventDefault();
    refreshQuickRetryState();
    if (!connected || !socketOpen() ||
        status.textContent !== "ACTIVE") return;
    const value = input.value.trim();
    if (!value || [...value].length > 1000) return;
    if (pendingQuick && pendingQuick.text !== value) return;
    if (pendingQuick?.expired) {
      if (!window.confirm("Delivery is unconfirmed. Sending again will use a new message ID and may create a duplicate. Send again?")) return;
      beginQuickAttempt(crypto.randomUUID(), value);
    } else if (!pendingQuick) {
      beginQuickAttempt(crypto.randomUUID(), value);
    }
    setStatus(status.textContent);
    transmitPendingQuick();
  });

  if (!speechSupported) {
    recordingStatus.textContent = "This browser cannot record WebM/Opus audio for VoiceLink.";
    speechSection.dataset.phase = "error";
  } else recordingStatus.textContent = "Ready to record. Speak for up to 15 seconds.";
  setStatus(status.textContent);

  speechModeLive.addEventListener("click", () => selectSpeechMode("LIVE_SEND"));
  speechModeFinish.addEventListener("click", () => selectSpeechMode("FINISH_AND_SEND"));

  speechReviewCancel.addEventListener("click", async () => {
    await cancelPrivateReview();
    recordingStatus.textContent = "Private review cancelled. Ready to record.";
    speechSection.dataset.phase = "idle";
    setStatus(status.textContent);
    recordStart.focus();
  });

  speechReviewRecordAgain.addEventListener("click", async () => {
    await cancelPrivateReview();
    recordingStatus.textContent = "Ready to record again.";
    speechSection.dataset.phase = "idle";
    setStatus(status.textContent);
    recordStart.click();
  });

  speechReviewSend.addEventListener("click", async () => {
    if (!privateTurnId || speechReviewSend.disabled || !connected || !socketOpen()) return;
    const turnId = privateTurnId;
    speechReviewSend.disabled = true;
    recordingStatus.textContent = "Sending translated turn";
    try {
      const response = await fetch("/api/rooms/" + roomId + "/turns/" + turnId + "/send", {
        method: "POST", credentials: "same-origin", headers: csrfHeaders()
      });
      if (!response.ok) {
        recordingStatus.textContent = response.status === 404 || response.status === 409
          ? "That private review changed or expired. Record again."
          : "The translated turn could not be sent. Try again.";
        speechSection.dataset.phase = "error";
        speechReviewSend.disabled = false;
        if (response.status === 404 || response.status === 409) resetPrivateReview();
        return;
      }
      resetPrivateReview();
      recordingStatus.textContent = "Translated turn sent. Creating voice.";
      speechSection.dataset.phase = "accepted";
      refreshTurns();
    } catch {
      recordingStatus.textContent = "The translated turn could not be sent. Check your connection and try again.";
      speechSection.dataset.phase = "error";
      speechReviewSend.disabled = false;
    }
  });

  recordStart.addEventListener("click", async () => {
    if (busy || !connected || !socketOpen() ||
        status.textContent !== "ACTIVE" || !speechSupported) return;
    if (privateTurnId) await cancelPrivateReview();
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
        data.append("workflow", speechWorkflow);
        data.append("audio", blob, "turn.webm");
        let accepted = false;
        try {
          const response = await fetch("/api/rooms/" + roomId + "/turns", {
            method: "POST", credentials: "same-origin", headers: csrfHeaders(),
            body: data
          });
          recordingStatus.textContent = response.ok
            ? speechWorkflow === "FINISH_AND_SEND"
              ? "Preparing your private transcript and translation"
              : "Speech accepted for processing"
            :
            response.status === 429 ? "Too many speech turns. Wait a minute and try again." :
            response.status === 503 ? "Speech processing is busy. Try again shortly." :
            "Speech upload failed. Check room access and recording limits.";
          accepted = response.ok;
          if (response.ok) {
            const result = await response.json();
            if (speechWorkflow === "FINISH_AND_SEND") {
              privateTurnId = result.turnId;
              const generation = ++reviewGeneration;
              pollPrivateReview(privateTurnId, generation);
            } else refreshTurns();
          }
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
