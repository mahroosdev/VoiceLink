(() => {
  "use strict";

  const page = document.getElementById("room-page");
  if (!page) return;
  const roomId = page.dataset.roomId;
  const configuredDebounce = Number(page.dataset.liveTextDebounceMs);
  const LIVE_DEBOUNCE_MS = configuredDebounce >= 700 && configuredDebounce <= 2000
    ? configuredDebounce : 800;
  const roomStatus = document.getElementById("room-status");
  let sourceLanguage = page.dataset.sourceLanguage || "en";
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
  const romanizedToggle = document.getElementById("romanized-tamil");
  const romanizedState = document.getElementById("romanized-tamil-state");
  const sourceLabel = document.getElementById("type-source-label");
  const source = document.getElementById("type-source");
  const translate = document.getElementById("type-translate");
  const retry = document.getElementById("type-retry");
  const send = document.getElementById("type-send");
  const finish = document.getElementById("type-finish");
  const status = document.getElementById("type-status");
  const reviewSection = document.getElementById("type-review");
  const reviewHeading = document.getElementById("type-review-heading");
  const originalLabel = document.getElementById("type-review-original-label");
  const original = document.getElementById("type-review-original");
  const translated = document.getElementById("type-review-translation");
  const finalEvents = document.getElementById("conversation-events");
  const turnEmpty = document.getElementById("turn-empty");
  const announcement = document.getElementById("turn-announcement");
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
  let romanizedTamil = false;
  source.lang = sourceLanguage;
  let debounceTimer = null;
  let lastInputValue = source.value;
  let lastAutomaticKey = null;
  let pendingLive = false;
  let sharingEpoch = null;
  let shareStartPromise = null;
  let shareGeneration = 0;
  let activeController = null;
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
    if (romanizedToggle) romanizedToggle.disabled = !available || translating || sending;
    source.disabled = !available || sending;
    translate.disabled = !available || textMode !== "total" || translating || sending;
    retry.disabled = !available || translating || sending || !retryRequest;
    send.disabled = !available || sending || translating || !review || review.revision !== revision
      || review.originalText !== source.value || Date.now() >= Date.parse(review.expiresAt);
    send.hidden = textMode !== "total";
    if (finish) {
      finish.hidden = textMode !== "live";
      finish.disabled = !available || sending || translating || !review || review.revision !== revision
        || review.originalText !== source.value || Date.now() >= Date.parse(review.expiresAt);
    }
  }

  function invalidate(message, state = "idle") {
    revision++;
    if (textMode === "live" && sharingEpoch && current("live")) {
      const epoch = sharingEpoch;
      const invalidatedRevision = revision;
      post("shares/" + epoch + "/invalidate", { revision: invalidatedRevision })
        .catch(() => {});
    }
    activeController?.abort();
    cancelPending();
    lastAutomaticKey = null;
    clearRetry();
    clearReview();
    if (message) setStatus(message, state);
    update();
  }

  function liveCopy() {
    typeEyebrow.textContent = "Live translation";
    typeHeading.textContent = "Type and pause to translate";
    typeIntro.textContent = "Your partner sees one live draft as it updates. Finish the message to finalize it.";
    reviewHeading.textContent = "Live translation ready to finish";
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
    mode = next;
    speakButton.setAttribute("aria-pressed", String(mode === "speak"));
    typeButton.setAttribute("aria-pressed", String(mode === "type"));
    speakSection.hidden = mode !== "speak";
    typeSection.hidden = mode !== "type";
    invalidate(mode === "type" ? (textMode === "live"
      ? "Live translation is ready for input." : textMode === "quick"
        ? "Quick chat is ready." : "Ready to translate.")
      : "Text translation paused while Speak is selected.", "idle");
    update();
    if (mode === "type") {
      (textMode === "quick" ? quickInput : source).focus();
      if (textMode === "live") scheduleAutomatic();
    } else if (textMode === "live") {
      stopSharing();
    } else speakButton.focus();
  }

  function selectTextMode(next) {
    if (next === textMode) return;
    const wasLive = textMode === "live";
    textMode = next;
    typeSection.dataset.textMode = next;
    liveButton.setAttribute("aria-pressed", String(next === "live"));
    totalButton.setAttribute("aria-pressed", String(next === "total"));
    quickButton.setAttribute("aria-pressed", String(next === "quick"));
    if (next === "live") liveCopy(); else if (next === "total") totalCopy();
    invalidate(next === "live" ? "Live translation is ready for input."
      : next === "quick" ? "Quick chat is ready. Messages are sent as written."
        : "Type your message. A translation will appear after a short pause.", "idle");
    if (wasLive && next !== "live") stopSharing();
    (next === "quick" ? quickInput : source).focus();
    if (next === "live") scheduleAutomatic();
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

  async function ensureSharing() {
    if (sharingEpoch) return sharingEpoch;
    if (shareStartPromise) return shareStartPromise;
    const generation = shareGeneration;
    shareStartPromise = (async () => {
      try {
        const { response, result } = await post("shares", {});
        if (!response.ok || typeof result.sharingEpoch !== "string") {
          setStatus(messageFor(response, result, "Live translation could not start. Reconnect and try again."), "error");
          return null;
        }
        if (generation !== shareGeneration) {
          stopShareEpoch(result.sharingEpoch);
          return null;
        }
        sharingEpoch = result.sharingEpoch;
        return sharingEpoch;
      } catch {
        setStatus("Live translation could not start. Reconnect and try again.", "error");
        return null;
      } finally {
        shareStartPromise = null;
      }
    })();
    return shareStartPromise;
  }

  function stopShareEpoch(epoch) {
    if (!epoch) return;
    fetch("/api/rooms/" + roomId + "/text/shares/" + epoch, {
      method: "DELETE", credentials: "same-origin",
      headers: csrfHeader && csrfToken ? { [csrfHeader]: csrfToken } : {}
    }).catch(() => {});
  }

  function stopSharing() {
    const epoch = sharingEpoch;
    shareGeneration++;
    shareStartPromise = null;
    sharingEpoch = null;
    if (!epoch) return;
    window.dispatchEvent(new CustomEvent("voicelink-remove-live-draft", { detail: { draftId: review?.draftId } }));
    stopShareEpoch(epoch);
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
    const requestRomanized = savedRequest?.romanizedTamil ?? !!romanizedToggle?.checked;
    if (savedRequest) {
      if (source.value !== savedRequest.text || revision !== savedRequest.revision
          || requestRomanized !== !!romanizedToggle?.checked) {
        clearRetry();
        setStatus("The message or language mode changed. Translate the current message instead.", "error");
        update();
        return;
      }
      clearReview();
      clearRetry();
    } else invalidate();
    if (!savedRequest && requestMode === "live") revision++;
    const requestRevision = savedRequest?.revision ?? revision;
    const request = { mode: requestMode, text, revision: requestRevision,
      romanizedTamil: requestRomanized };
    translating = true;
    pendingLive = false;
    lastAutomaticKey = requestRevision + "\n" + text;
    const requestId = ++requestSequence;
    activeRequest = requestId;
    activeController = new AbortController();
    setStatus(requestMode === "live" ? "Translating and updating your partner…"
      : "Translating your message…", "translating");
    update();
    try {
      let epoch = null;
      if (requestMode === "live") {
        epoch = await ensureSharing();
        if (!epoch) return;
        if (requestRevision !== revision || !current(requestMode) || source.value !== text) return;
      }
      const { response, result } = await post("reviews",
        { sourceText: text, revision: requestRevision, romanizedTamil: requestRomanized,
          ...(epoch ? { sharingEpoch: epoch } : {}) }, activeController.signal);
      if (requestRevision !== revision || !current(requestMode) || source.value !== text
          || requestRomanized !== !!romanizedToggle?.checked) return;
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
      if (originalLabel) originalLabel.textContent = result.romanizedTamil
        ? "Original · Romanized Tamil" : "Original · " + (result.sourceLanguage === "ta" ? "Tamil" : "English");
      original.lang = result.romanizedTamil ? "ta-Latn" : result.sourceLanguage;
      translated.lang = result.targetLanguage;
      reviewSection.hidden = false;
      setStatus(requestMode === "live" ? "Your live translation is visible to your partner. Finish it when ready."
        : "Translation ready. Review it before sending.", "ready");
      if (requestMode === "total") send.focus();
    } catch (error) {
      if (error?.name !== "AbortError" && requestRevision === revision && current(requestMode)) {
        setStatus("Connection problem. Your message remains here; check your internet connection and try again.", "error");
        offerRetry(request, null, {});
      }
    } finally {
      if (activeRequest === requestId) {
        activeRequest = 0;
        translating = false;
        activeController = null;
      }
      update();
      if ((current("live") || current("total")) && !composing && !sending
          && (pendingLive || (source.value.trim()
            && lastAutomaticKey !== revision + "\n" + source.value))) {
        pendingLive = false;
        scheduleAutomatic();
      }
    }
  }

  function scheduleAutomatic() {
    const requestMode = textMode;
    if (!(requestMode === "live" || requestMode === "total")
        || !current(requestMode) || composing || sending) return;
    const text = source.value;
    if (debounceTimer !== null) clearTimeout(debounceTimer);
    debounceTimer = null;
    if (!text.trim()) {
      setStatus("Waiting for input.", "idle");
      if (requestMode === "live") stopSharing();
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
      if (current(requestMode) && !composing && source.value === text
          && revision + "\n" + text !== lastAutomaticKey) {
        if (translating) {
          pendingLive = true;
          setStatus("Latest text queued. It will update after the current translation finishes.", "waiting");
        } else translateCurrent(requestMode);
      }
    }, LIVE_DEBOUNCE_MS);
  }

  function sourceChanged() {
    if (source.value === lastInputValue && !composing) return;
    lastInputValue = source.value;
    invalidate(composing ? "Composing text…" : (textMode === "quick"
      ? "Quick chat is ready." : "Waiting for typing to pause…"),
    composing ? "typing" : (textMode === "quick" ? "idle" : "waiting"));
    if (textMode !== "quick" && !composing) scheduleAutomatic();
  }

  function appendFinal(message, sender) {
    if (!message?.finalId || seen.has(message.finalId)) return;
    if (message.draftId) window.dispatchEvent(new CustomEvent("voicelink-remove-live-draft",
      { detail: { draftId: message.draftId } }));
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
    sourceLabel.textContent = "Original · " + (message.romanizedTamil ? "Romanized Tamil" :
      message.sourceLanguage === "ta" ? "Tamil" : "English");
    const sourceText = document.createElement("p");
    sourceText.textContent = message.originalText;
    sourceText.lang = message.romanizedTamil ? "ta-Latn" : message.sourceLanguage;
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
    window.voicelinkHistory?.changed(wasNearBottom, "translated", sender === "You");
  }

  speakButton.addEventListener("click", () => selectMode("speak"));
  typeButton.addEventListener("click", () => selectMode("type"));
  liveButton.addEventListener("click", () => selectTextMode("live"));
  totalButton.addEventListener("click", () => selectTextMode("total"));
  quickButton.addEventListener("click", () => selectTextMode("quick"));
  source.addEventListener("compositionstart", () => {
    composing = true;
    invalidate("Composing text…", "typing");
  });
  source.addEventListener("compositionend", () => {
    composing = false;
    lastInputValue = source.value;
    invalidate(textMode === "quick" ? "Quick chat is ready." : "Waiting for typing to pause…",
      textMode === "quick" ? "idle" : "waiting");
    if (textMode !== "quick") scheduleAutomatic();
  });
  source.addEventListener("input", sourceChanged);
  translate.addEventListener("click", () => translateCurrent("total"));
  retry.addEventListener("click", () => {
    if (!retryRequest || translating || sending) return;
    const request = { ...retryRequest };
    if (request.mode === "live") {
      revision++;
      request.revision = revision;
    }
    translateCurrent(request.mode, request);
  });

  async function sendCurrent() {
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
      if (selectedMode === "live") stopSharing();
      source.value = "";
      lastInputValue = "";
      invalidate("Sent. Both connected participants can see the translated message.", "sent");
      if (mode === "type" && textMode === selectedMode) source.focus();
    } catch {
      setStatus("Connection failed. You can retry Send; it will not duplicate a successful message.", "error");
    } finally {
      sending = false;
      update();
    }
  }

  send.addEventListener("click", sendCurrent);
  finish?.addEventListener("click", sendCurrent);

  window.addEventListener("voicelink-final-text", ({ detail: event }) => {
    const payload = event?.payload;
    if (!payload || event.roomId !== roomId) return;
    const participant = document.querySelector("#participants-list [data-participant-id]='" +
      event.senderParticipantId + "']");
    appendFinal({ ...payload, finalId: event.turnId }, participant?.dataset.role || "Partner");
  });
  window.addEventListener("voicelink-glossary-changed", () => {
    if (mode !== "type") return;
    invalidate("The glossary changed. A fresh translation is required.", "waiting");
    if (textMode !== "quick") scheduleAutomatic();
  });
  window.addEventListener("voicelink-room-state", () => {
    const current = document.querySelector("#participants-list [data-current=\"true\"]");
    const speaking = current?.dataset.speaking;
    const directionChanged = (speaking === "en" || speaking === "ta") && speaking !== sourceLanguage;
    if (speaking === "en" || speaking === "ta") {
      sourceLanguage = speaking;
      source.lang = romanizedTamil && sourceLanguage === "ta" ? "ta-Latn" : sourceLanguage;
      if (romanizedToggle && sourceLanguage !== "ta") {
        romanizedToggle.checked = false;
        romanizedTamil = false;
        if (romanizedState) romanizedState.textContent = "Transliteration: Off";
        sourceLabel.textContent = "Your message";
      }
    }
    if (directionChanged && mode === "type" && textMode === "live"
        && source.value.trim() && roomStatus.textContent !== "CLOSED") {
      invalidate("Room languages changed. Updating the live translation direction…", "waiting");
      stopSharing();
      ensureSharing().then(epoch => { if (epoch) scheduleAutomatic(); });
    }
    if (roomStatus.textContent === "CLOSED") {
      stopSharing();
      invalidate("This room is closed.", "error");
      finalEvents.replaceChildren();
      seen.clear();
    }
    update();
  });
  romanizedToggle?.addEventListener("change", () => {
    romanizedTamil = romanizedToggle.checked;
    if (romanizedState) romanizedState.textContent = "Transliteration: " + (romanizedTamil ? "On" : "Off");
    sourceLabel.textContent = romanizedTamil ? "Your message · Romanized Tamil" : "Your message";
    source.lang = romanizedTamil ? "ta-Latn" : sourceLanguage;
    invalidate(romanizedTamil ? "Romanized Tamil input selected. Translate to review it."
      : "Tamil input selected. Translate to review it.", "idle");
    if (textMode !== "quick") scheduleAutomatic();
  });
  window.addEventListener("voicelink-connection-state", event => {
    if (!event.detail?.connected) {
      shareGeneration++;
      shareStartPromise = null;
      sharingEpoch = null;
      activeController?.abort();
      if (textMode === "live") setStatus("Live connection lost. Reconnecting; your draft will be cleared.", "waiting");
      return;
    }
    if (mode === "type" && textMode === "live" && source.value.trim()) {
      ensureSharing().then(epoch => { if (epoch) scheduleAutomatic(); });
    }
  });
  setInterval(() => {
    if (review && Date.now() >= Date.parse(review.expiresAt))
      invalidate("Private review expired. Translate again.", "error");
  }, 1000);
  totalCopy();
  setStatus("Type your message. A translation will appear after a short pause.", "idle");
  update();
})();
