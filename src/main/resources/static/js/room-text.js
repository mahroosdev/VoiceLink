(() => {
  "use strict";

  const page = document.getElementById("room-page");
  if (!page) return;
  const roomId = page.dataset.roomId;
  const configuredDebounce = Number(page.dataset.liveTextDebounceMs);
  const LIVE_DEBOUNCE_MS = configuredDebounce >= 700 && configuredDebounce <= 2000
    ? configuredDebounce : 800;
  const roomStatus = document.getElementById("room-status");
  const speakButton = document.getElementById("mode-speak");
  const typeButton = document.getElementById("mode-type");
  const liveButton = document.getElementById("text-mode-live");
  const totalButton = document.getElementById("text-mode-total");
  const quickButton = document.getElementById("text-mode-quick");
  const translationComposer = document.getElementById("translation-composer");
  const quickComposer = document.getElementById("live-text-section");
  const quickInput = document.getElementById("live-text-input");
  const speakSection = document.getElementById("speech-section");
  const typeSection = document.getElementById("type-section");
  const typeEyebrow = document.getElementById("type-eyebrow");
  const typeHeading = document.getElementById("type-heading");
  const typeIntro = document.getElementById("type-intro");
  const typeActions = document.getElementById("type-actions");
  const privacyControls = document.getElementById("type-privacy-controls");
  const privateButton = document.getElementById("text-privacy-private");
  const sharedButton = document.getElementById("text-privacy-shared");
  const sharingNotice = document.getElementById("type-sharing-notice");
  const stopSharingButton = document.getElementById("text-sharing-stop");
  const source = document.getElementById("type-source");
  const translate = document.getElementById("type-translate");
  const retry = document.getElementById("type-retry");
  const send = document.getElementById("type-send");
  const status = document.getElementById("type-status");
  const reviewSection = document.getElementById("type-review");
  const reviewHeading = document.getElementById("type-review-heading");
  const original = document.getElementById("type-review-original");
  const translated = document.getElementById("type-review-translation");
  const finalEvents = document.getElementById("conversation-events");
  const turnEmpty = document.getElementById("turn-empty");
  const announcement = document.getElementById("turn-announcement");
  const sharedDraft = document.getElementById("shared-text-draft");
  const sharedDraftHeading = document.getElementById("shared-text-draft-heading");
  const sharedDraftText = document.getElementById("shared-text-draft-text");
  const sharedDraftStatus = document.getElementById("shared-text-draft-status");
  const sharedDraftAnnouncement = document.getElementById("shared-text-draft-announcement");
  if (!typeButton || !typeSection || !liveButton || !totalButton || !quickButton) return;

  const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
  const seen = new Set();
  let revision = 0;
  let review = null;
  let translating = false;
  let sending = false;
  let composing = false;
  let mode = "speak";
  let textMode = "total";
  let privacyMode = "private";
  let sharingEpoch = null;
  let sharingChanging = false;
  let sharingOperation = 0;
  let sharedServerCurrent = false;
  let sharedInvalidation = Promise.resolve();
  let receivedDraft = null;
  let debounceTimer = null;
  let lastInputValue = source.value;
  let lastAutomaticKey = null;
  let pendingLive = false;
  let requestSequence = 0;
  let activeRequest = 0;
  let retryRequest = null;

  function setStatus(message, state) {
    if (status.textContent !== message) status.textContent = message;
    status.dataset.state = state;
  }

  function clearReview() {
    review = null;
    reviewSection.hidden = true;
    original.textContent = "";
    translated.textContent = "";
    send.disabled = true;
  }

  function clearReceivedDraft(message) {
    const existed = receivedDraft !== null;
    receivedDraft = null;
    sharedDraft.hidden = true;
    sharedDraftText.textContent = "";
    sharedDraftText.removeAttribute?.("lang");
    if (turnEmpty) turnEmpty.hidden = finalEvents.childElementCount !== 0;
    window.voicelinkHistory?.refresh();
    if (existed && message && sharedDraftAnnouncement)
      sharedDraftAnnouncement.textContent = message;
  }

  function cancelPending() {
    if (debounceTimer !== null) {
      clearTimeout(debounceTimer);
      debounceTimer = null;
    }
    pendingLive = false;
  }

  function clearRetry() {
    retryRequest = null;
    retry.hidden = true;
    retry.disabled = true;
  }

  function current(expectedTextMode = textMode) {
    return roomStatus.textContent === "ACTIVE" && mode === "type" && textMode === expectedTextMode;
  }

  function validSource(text) {
    return text.trim().length > 0 && [...text].length <= 1000;
  }

  function update() {
    const available = current();
    speakSection.hidden = roomStatus.textContent !== "ACTIVE" || mode !== "speak";
    typeSection.hidden = roomStatus.textContent !== "ACTIVE" || mode !== "type";
    translationComposer.hidden = textMode === "quick";
    quickComposer.hidden = roomStatus.textContent !== "ACTIVE" || mode !== "type" || textMode !== "quick";
    typeActions.hidden = textMode !== "total";
    privacyControls.hidden = textMode !== "live";
    privateButton.setAttribute("aria-pressed", String(privacyMode === "private"));
    sharedButton.setAttribute("aria-pressed", String(privacyMode === "shared"));
    privateButton.disabled = !available || sharingChanging;
    sharedButton.disabled = !available || sharingChanging;
    sharingNotice.hidden = textMode !== "live" || privacyMode !== "shared";
    source.disabled = !available || sending || sharingChanging;
    translate.disabled = !available || textMode !== "total" || translating || sending;
    retry.disabled = !available || translating || sending || !retryRequest;
    send.disabled = !available || sending || translating || !review || review.revision !== revision
      || review.originalText !== source.value || Date.now() >= Date.parse(review.expiresAt);
  }

  function invalidate(message, state = "idle") {
    revision++;
    cancelPending();
    lastAutomaticKey = null;
    clearRetry();
    clearReview();
    if (message) setStatus(message, state);
    update();
  }

  function liveCopy() {
    typeEyebrow.textContent = privacyMode === "shared"
      ? "Shared live translation" : "Live private translation";
    typeHeading.textContent = "Type and pause to preview";
    typeIntro.textContent = privacyMode === "shared"
      ? "Your translated drafts are visible to the other participant. Nothing is final until you send."
      : "Only you can see this preview until you send. Gemini processes text after a short typing pause.";
    reviewHeading.textContent = privacyMode === "shared"
      ? "Shared live draft — Not sent" : "Private preview — Not sent";
  }

  function totalCopy() {
    typeEyebrow.textContent = "Translate message";
    typeHeading.textContent = "Type a complete message";
    typeIntro.textContent = "Translate and review your message before sending. Your partner sees it only after you select Send.";
    reviewHeading.textContent = "Translated — Not sent";
  }

  function selectMode(next) {
    if (next === mode) return;
    if (next === "type") window.dispatchEvent(new Event("voicelink-speech-cancel-private"));
    if (next === "speak" && privacyMode === "shared") stopSharing("Text sharing stopped in Speak mode.");
    mode = next;
    speakButton.setAttribute("aria-pressed", String(mode === "speak"));
    typeButton.setAttribute("aria-pressed", String(mode === "type"));
    speakSection.hidden = mode !== "speak";
    typeSection.hidden = mode !== "type";
    invalidate(mode === "type" ? (textMode === "live"
      ? "Live private preview is ready for input." : textMode === "quick"
        ? "Quick chat is ready." : "Ready to translate.")
      : "Text translation paused while Speak is selected.", "idle");
    update();
    if (mode === "type") {
      (textMode === "quick" ? quickInput : source).focus();
      if (textMode === "live") scheduleLive();
    } else speakButton.focus();
  }

  function selectTextMode(next) {
    if (next === textMode) return;
    if (next !== "live" && privacyMode === "shared")
      stopSharing("Shared Live stopped. Translation drafts are private until Send.");
    textMode = next;
    typeSection.dataset.textMode = next;
    liveButton.setAttribute("aria-pressed", String(next === "live"));
    totalButton.setAttribute("aria-pressed", String(next === "total"));
    quickButton.setAttribute("aria-pressed", String(next === "quick"));
    if (next === "live") liveCopy(); else if (next === "total") totalCopy();
    invalidate(next === "live" ? "Live private preview is ready for input."
      : next === "quick" ? "Quick chat is ready. Messages are sent as written."
        : "Translate message selected. Select Translate when your message is complete.", "idle");
    (next === "quick" ? quickInput : source).focus();
    if (next === "live") scheduleLive();
  }

  function messageFor(response, body, fallback) {
    if (typeof body?.error === "string" && body.error.length < 180) return body.error;
    if (response.status === 413) return "Message is too large. Shorten it and try again.";
    if (response.status === 403) return "This action is no longer authorized. Refresh the room.";
    if (response.status === 429) return "Live translation is paused by the request limit. Wait before typing more.";
    return fallback;
  }

  async function post(path, body, signal) {
    const response = await fetch("/api/rooms/" + roomId + "/text/" + path, {
      method: "POST", credentials: "same-origin", signal,
      headers: { "Content-Type": "application/json", Accept: "application/json",
        ...(csrfHeader && csrfToken ? { [csrfHeader]: csrfToken } : {}) },
      body: JSON.stringify(body)
    });
    let result;
    try { result = await response.json(); } catch { result = {}; }
    return { response, result };
  }

  async function sharingRequest(path, method, body, keepalive = false) {
    const response = await fetch("/api/rooms/" + roomId + "/text/" + path, {
      method, credentials: "same-origin", keepalive,
      headers: { Accept: "application/json",
        ...(body ? { "Content-Type": "application/json" } : {}),
        ...(csrfHeader && csrfToken ? { [csrfHeader]: csrfToken } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {})
    });
    let result;
    try { result = await response.json(); } catch { result = {}; }
    return { response, result };
  }

  function sharingFailure(message) {
    privacyMode = "private";
    sharingEpoch = null;
    sharedServerCurrent = false;
    sharedInvalidation = Promise.resolve();
    liveCopy();
    setStatus(message, "error");
    window.dispatchEvent(new Event("voicelink-force-live-disconnect"));
    update();
  }

  async function startSharing() {
    if (!current("live") || privacyMode === "shared" || sharingChanging) return;
    const operation = ++sharingOperation;
    invalidate("Enabling Shared Live…", "waiting");
    sharingChanging = true;
    update();
    try {
      const { response, result } = await sharingRequest("sharing", "POST");
      if (!response.ok || !result.sharingEpoch) {
        if (operation === sharingOperation)
          setStatus(messageFor(response, result, "Shared Live could not be enabled."), "error");
        return;
      }
      if (operation !== sharingOperation || !current("live")) {
        await sharingRequest("sharing/" + result.sharingEpoch, "DELETE");
        return;
      }
      privacyMode = "shared";
      sharingEpoch = result.sharingEpoch;
      sharedServerCurrent = false;
      sharedInvalidation = Promise.resolve();
      liveCopy();
      setStatus("Sharing translated drafts. Type and pause to update.", "ready");
      scheduleLive();
    } catch {
      if (operation === sharingOperation)
        setStatus("Shared Live could not be enabled. Check the live connection.", "error");
    } finally {
      if (operation === sharingOperation) sharingChanging = false;
      update();
    }
  }

  async function stopSharing(message = "Shared Live stopped. Preview is private.") {
    const epoch = sharingEpoch;
    const operation = ++sharingOperation;
    privacyMode = "private";
    sharingEpoch = null;
    sharedServerCurrent = false;
    sharedInvalidation = Promise.resolve();
    sharingChanging = !!epoch;
    invalidate(message, "idle");
    liveCopy();
    update();
    if (!epoch) {
      sharingChanging = false;
      update();
      return;
    }
    try {
      const { response, result } = await sharingRequest("sharing/" + epoch, "DELETE");
      if (!response.ok && response.status !== 409)
        sharingFailure(messageFor(response, result,
          "Sharing stop could not be confirmed. The live connection was reset."));
    } catch {
      sharingFailure("Sharing stop could not be confirmed. The live connection was reset.");
    } finally {
      if (operation === sharingOperation) sharingChanging = false;
      update();
    }
  }

  async function clearSharedDraft(clearRevision) {
    const epoch = sharingEpoch;
    if (!epoch || privacyMode !== "shared") return;
    try {
      const { response, result } = await sharingRequest(
        "sharing/" + epoch + "/clear", "POST", { revision: clearRevision });
      if (!response.ok) sharingFailure(messageFor(response, result,
        "Draft clearing could not be confirmed. The live connection was reset."));
      else if (epoch === sharingEpoch) sharedServerCurrent = false;
    } catch {
      sharingFailure("Draft clearing could not be confirmed. The live connection was reset.");
    }
  }

  async function invalidateSharedRevision(invalidRevision, epoch) {
    try {
      const { response, result } = await sharingRequest(
        "sharing/" + epoch + "/invalidate", "POST", { revision: invalidRevision });
      if (!response.ok && epoch === sharingEpoch) sharingFailure(messageFor(response, result,
        "Draft invalidation could not be confirmed. The live connection was reset."));
    } catch {
      if (epoch === sharingEpoch)
        sharingFailure("Draft invalidation could not be confirmed. The live connection was reset.");
    }
  }

  function retryAllowed(response, result) {
    return ["TIMED_OUT", "TEMPORARILY_UNAVAILABLE", "CONNECTION_PROBLEM",
      "RESPONSE_INVALID"].includes(result?.code)
      || response === null;
  }

  function offerRetry(request, response, result) {
    if (!retryAllowed(response, result)) return;
    retryRequest = request;
    retry.hidden = false;
    retry.disabled = false;
  }

  async function translateCurrent(requestMode, savedRequest = null) {
    if (!current(requestMode) || translating || sending || composing) return;
    const text = savedRequest?.text ?? source.value;
    if (!validSource(text)) {
      setStatus(text.trim() ? "Use no more than 1,000 Unicode characters."
        : (requestMode === "live" ? "Waiting for input." : "Enter 1 to 1,000 characters before translating."),
      text.trim() ? "error" : "idle");
      return;
    }
    if (savedRequest) {
      if (source.value !== savedRequest.text || revision !== savedRequest.revision
          || privacyMode !== savedRequest.privacy || sharingEpoch !== savedRequest.sharingEpoch) {
        clearRetry();
        setStatus("The message or sharing mode changed. Translate the current message instead.", "error");
        update();
        return;
      }
      clearReview();
      clearRetry();
    } else invalidate();
    const requestRevision = savedRequest?.revision ?? revision;
    const requestPrivacy = savedRequest?.privacy ?? privacyMode;
    const requestSharingEpoch = savedRequest?.sharingEpoch ?? sharingEpoch;
    const request = { mode: requestMode, text, revision: requestRevision,
      privacy: requestPrivacy, sharingEpoch: requestSharingEpoch };
    if (requestMode === "live" && requestPrivacy === "shared" && !requestSharingEpoch) {
      setStatus("Enable Shared Live again before translating.", "error");
      return;
    }
    if (requestSharingEpoch) {
      await sharedInvalidation;
      if (requestRevision !== revision || !current(requestMode) || source.value !== text
          || requestPrivacy !== privacyMode || requestSharingEpoch !== sharingEpoch) return;
      sharedServerCurrent = true;
    }
    translating = true;
    pendingLive = false;
    if (requestMode === "live") lastAutomaticKey = requestRevision + "\n" + text;
    const requestId = ++requestSequence;
    activeRequest = requestId;
    setStatus(requestMode === "live" ? (requestPrivacy === "shared"
      ? "Updating the shared translated draft…" : "Creating your private live preview…")
      : "Translating for private review…", "translating");
    update();
    try {
      const { response, result } = await post("reviews",
        { sourceText: text, revision: requestRevision,
          ...(requestSharingEpoch ? { sharingEpoch: requestSharingEpoch } : {}) });
      if (requestRevision !== revision || !current(requestMode) || source.value !== text
          || requestPrivacy !== privacyMode || requestSharingEpoch !== sharingEpoch) return;
      if (!response.ok) {
        setStatus(messageFor(response, result, "Translation could not finish. Try again."),
          response.status === 429 ? "rate-limited" : "error");
        offerRetry(request, response, result);
        return;
      }
      if (result.originalText !== text || result.revision !== requestRevision || !result.reviewId) {
        setStatus("Translation review is unavailable. Try again.", "error");
        return;
      }
      review = result;
      original.textContent = result.originalText;
      translated.textContent = result.translatedText;
      original.lang = result.sourceLanguage;
      translated.lang = result.targetLanguage;
      reviewSection.hidden = false;
      setStatus(requestMode === "live" ? (requestPrivacy === "shared"
        ? "Shared live draft updated — Not sent."
        : "Private preview ready — Not sent.")
        : "Translated — Not sent. Review before sending.", "ready");
      if (requestMode === "total") send.focus();
    } catch (error) {
      if (requestRevision === revision && current(requestMode)) {
        setStatus("Connection problem. Your message remains here; check your internet connection and try again.", "error");
        offerRetry(request, null, {});
      }
    } finally {
      if (activeRequest === requestId) {
        activeRequest = 0;
        translating = false;
      }
      update();
      if (current("live") && !composing && !sending
          && (pendingLive || (source.value.trim()
            && lastAutomaticKey !== revision + "\n" + source.value))) {
        pendingLive = false;
        scheduleLive();
      }
    }
  }

  function scheduleLive() {
    if (!current("live") || composing || sending) return;
    const text = source.value;
    if (debounceTimer !== null) clearTimeout(debounceTimer);
    debounceTimer = null;
    if (!text.trim()) {
      setStatus("Waiting for input.", "idle");
      return;
    }
    if ([...text].length > 1000) {
      setStatus("Use no more than 1,000 Unicode characters.", "error");
      return;
    }
    if (revision + "\n" + text === lastAutomaticKey) return;
    setStatus("Waiting for typing to pause…", "waiting");
    debounceTimer = setTimeout(() => {
      debounceTimer = null;
      if (current("live") && !composing && source.value === text
          && revision + "\n" + text !== lastAutomaticKey) {
        if (translating) {
          pendingLive = true;
          setStatus("Latest message queued. Translation will update after the current request finishes.", "waiting");
        } else translateCurrent("live");
      }
    }, LIVE_DEBOUNCE_MS);
  }

  function sourceChanged() {
    if (source.value === lastInputValue && !composing) return;
    lastInputValue = source.value;
    invalidate(composing ? "Composing text…" : (textMode === "live"
      ? "Waiting for typing to pause…" : "Message changed. Select Translate to review it."),
    composing ? "typing" : (textMode === "live" ? "waiting" : "idle"));
    if (textMode === "live" && privacyMode === "shared" && !composing) {
      if (!source.value.trim()) {
        sharedServerCurrent = false;
        sharedInvalidation = clearSharedDraft(revision);
      } else if (sharedServerCurrent && sharingEpoch) {
        sharedServerCurrent = false;
        sharedInvalidation = invalidateSharedRevision(revision, sharingEpoch);
      }
    }
    if (textMode === "live" && !composing) scheduleLive();
  }

  function appendFinal(message, sender) {
    if (!message?.finalId || seen.has(message.finalId)) return;
    const wasNearBottom = window.voicelinkHistory?.beforeAppend();
    seen.add(message.finalId);
    if (seen.size > 256) seen.delete(seen.values().next().value);
    const row = document.createElement("li");
    row.className = "turn-card text-turn-card turn-card--" + sender.toLowerCase();
    row.dataset.senderRole = sender;
    row.dataset.timelineKind = "text";
    row.dataset.messageKind = "translated";
    const heading = document.createElement("strong");
    heading.className = "turn-card__speaker";
    heading.textContent = sender + " · Sent translated text";
    const sourceBlock = document.createElement("div");
    sourceBlock.className = "turn-caption";
    const sourceLabel = document.createElement("span");
    sourceLabel.className = "turn-caption__label";
    sourceLabel.textContent = "Original · " + (message.sourceLanguage === "ta" ? "Tamil" : "English");
    const sourceText = document.createElement("p");
    sourceText.textContent = message.originalText;
    sourceText.lang = message.sourceLanguage;
    sourceBlock.append(sourceLabel, sourceText);
    const targetBlock = document.createElement("div");
    targetBlock.className = "turn-caption turn-caption--translated";
    const targetLabel = document.createElement("span");
    targetLabel.className = "turn-caption__label";
    targetLabel.textContent = "Translation · " + (message.targetLanguage === "ta" ? "Tamil" : "English");
    const targetText = document.createElement("p");
    targetText.textContent = message.translatedText;
    targetText.lang = message.targetLanguage;
    targetBlock.append(targetLabel, targetText);
    row.append(heading, sourceBlock, targetBlock);
    finalEvents.append(row);
    if (turnEmpty) turnEmpty.hidden = true;
    window.voicelinkHistory?.refresh();
    const textRows = [...finalEvents.children].filter(item => item.dataset.timelineKind === "text");
    while (textRows.length > 16) textRows.shift().remove();
    if (announcement) announcement.textContent = sender + " sent translated text.";
    window.voicelinkHistory?.changed(wasNearBottom, "translated");
  }

  speakButton.addEventListener("click", () => selectMode("speak"));
  typeButton.addEventListener("click", () => selectMode("type"));
  liveButton.addEventListener("click", () => selectTextMode("live"));
  totalButton.addEventListener("click", () => selectTextMode("total"));
  quickButton.addEventListener("click", () => selectTextMode("quick"));
  privateButton.addEventListener("click", () => stopSharing());
  sharedButton.addEventListener("click", startSharing);
  stopSharingButton.addEventListener("click", () => stopSharing());
  source.addEventListener("compositionstart", () => {
    composing = true;
    invalidate("Composing text…", "typing");
    if (textMode === "live" && privacyMode === "shared" && sharedServerCurrent && sharingEpoch) {
      sharedServerCurrent = false;
      sharedInvalidation = invalidateSharedRevision(revision, sharingEpoch);
    }
  });
  source.addEventListener("compositionend", () => {
    composing = false;
    lastInputValue = source.value;
    invalidate(textMode === "live" ? "Waiting for typing to pause…"
      : "Message changed. Select Translate to review it.", textMode === "live" ? "waiting" : "idle");
    if (textMode === "live") scheduleLive();
  });
  source.addEventListener("input", sourceChanged);
  translate.addEventListener("click", () => translateCurrent("total"));
  retry.addEventListener("click", () => {
    if (!retryRequest || translating || sending) return;
    const request = retryRequest;
    translateCurrent(request.mode, request);
  });

  send.addEventListener("click", async () => {
    if (!current() || sending || translating || !review || review.revision !== revision
        || review.originalText !== source.value || Date.now() >= Date.parse(review.expiresAt)) {
      setStatus("Translation changed or expired. Translate again.", "error");
      clearReview();
      update();
      return;
    }
    const selected = review;
    const selectedMode = textMode;
    sending = true;
    setStatus("Sending translated message…", "sending");
    update();
    try {
      const { response, result } = await post("send", {
        reviewId: selected.reviewId, sourceText: selected.originalText, revision: selected.revision
      });
      if (!response.ok) {
        setStatus(messageFor(response, result, "Sending failed. Try again."), "error");
        if (response.status === 409) clearReview();
        return;
      }
      appendFinal(result, "You");
      source.value = "";
      lastInputValue = "";
      sharedServerCurrent = false;
      sharedInvalidation = Promise.resolve();
      invalidate(privacyMode === "shared"
        ? "Sent. The shared draft was finalized and cleared; sharing remains on."
        : "Sent. Both connected participants can see the translated message.", "sent");
      if (mode === "type" && textMode === selectedMode) source.focus();
    } catch {
      setStatus("Connection failed. You can retry Send; it will not duplicate a successful message.", "error");
    } finally {
      sending = false;
      update();
    }
  });

  window.addEventListener("voicelink-final-text", ({ detail: event }) => {
    const payload = event?.payload;
    if (!payload || event.roomId !== roomId) return;
    const participant = document.querySelector("#participants-list [data-participant-id='" +
      event.senderParticipantId + "']");
    if (receivedDraft?.senderParticipantId === event.senderParticipantId)
      clearReceivedDraft("The shared draft was finalized.");
    appendFinal({ ...payload, finalId: event.turnId }, participant?.dataset.role || "Partner");
  });
  window.addEventListener("voicelink-text-draft", ({ detail: event }) => {
    if (!event || event.roomId !== roomId) return;
    if (event.type === "TEXT_DRAFT_CLEARED") {
      if (receivedDraft?.draftId === event.turnId
          && Number(event.payload?.revision) >= receivedDraft.revision)
        clearReceivedDraft("The participant's shared draft was cleared.");
      return;
    }
    const payload = event.payload;
    const incomingRevision = Number(payload?.revision);
    if (event.type !== "TEXT_DRAFT_UPDATED" || payload?.provisional !== true
        || typeof payload.translatedText !== "string" || !Number.isSafeInteger(incomingRevision)) return;
    if (receivedDraft?.draftId === event.turnId && incomingRevision <= receivedDraft.revision) return;
    const first = receivedDraft === null || receivedDraft.draftId !== event.turnId;
    receivedDraft = { draftId: event.turnId, revision: incomingRevision,
      senderParticipantId: event.senderParticipantId };
    const participant = document.querySelector("#participants-list [data-participant-id='" +
      event.senderParticipantId + "']");
    const sender = participant?.dataset.role || "Partner";
    if (sharedDraftHeading) sharedDraftHeading.textContent = sender + " · Live translation draft";
    sharedDraftText.textContent = payload.translatedText;
    sharedDraftText.lang = payload.targetLanguage;
    sharedDraftStatus.textContent = "Updating may continue until the participant sends or stops sharing.";
    sharedDraft.hidden = false;
    if (turnEmpty) turnEmpty.hidden = true;
    if (first && sharedDraftAnnouncement)
      sharedDraftAnnouncement.textContent = sender + " started sharing a translated draft.";
  });
  window.addEventListener("voicelink-text-draft-reset", () => {
    clearReceivedDraft("Shared draft cleared after the live connection changed.");
    if (privacyMode === "shared" || sharingEpoch) {
      sharingOperation++;
      privacyMode = "private";
      sharingEpoch = null;
      sharedServerCurrent = false;
      sharedInvalidation = Promise.resolve();
      sharingChanging = false;
      invalidate("Shared Live ended when the live connection changed. Enable it again to share.", "error");
      liveCopy();
      update();
    }
  });
  window.addEventListener("voicelink-glossary-changed", () => {
    if (mode !== "type") return;
    invalidate("The glossary changed. A fresh translation is required.", "waiting");
    if (textMode === "live" && privacyMode === "shared") {
      sharedInvalidation = clearSharedDraft(revision);
      sharedInvalidation.then(scheduleLive);
    } else if (textMode === "live") scheduleLive();
  });
  window.addEventListener("voicelink-room-state", () => {
    if (roomStatus.textContent === "CLOSED") {
      sharingOperation++;
      privacyMode = "private";
      sharingEpoch = null;
      sharedServerCurrent = false;
      sharedInvalidation = Promise.resolve();
      invalidate("This room is closed.", "error");
      finalEvents.replaceChildren();
      seen.clear();
      clearReceivedDraft("Shared draft cleared because the room closed.");
    }
    update();
  });
  setInterval(() => {
    if (review && Date.now() >= Date.parse(review.expiresAt))
      invalidate("Private review expired. Translate again.", "error");
  }, 1000);
  window.addEventListener("pagehide", () => {
    if (sharingEpoch) sharingRequest("sharing/" + sharingEpoch, "DELETE", null, true)
      .catch(() => {});
  });
  totalCopy();
  setStatus("Ready to translate.", "idle");
  update();
})();
