(() => {
  "use strict";

  const page = document.getElementById("room-page");
  if (!page) return;
  const roomId = page.dataset.roomId;
  const configuredDebounce = Number(page.dataset.liveTextDebounceMs);
  const LIVE_DEBOUNCE_MS = configuredDebounce >= 700 && configuredDebounce <= 900
    ? configuredDebounce : 800;
  const roomStatus = document.getElementById("room-status");
  const speakButton = document.getElementById("mode-speak");
  const typeButton = document.getElementById("mode-type");
  const liveButton = document.getElementById("text-mode-live");
  const totalButton = document.getElementById("text-mode-total");
  const speakSection = document.getElementById("speech-section");
  const typeSection = document.getElementById("type-section");
  const typeEyebrow = document.getElementById("type-eyebrow");
  const typeHeading = document.getElementById("type-heading");
  const typeIntro = document.getElementById("type-intro");
  const typeActions = document.getElementById("type-actions");
  const source = document.getElementById("type-source");
  const translate = document.getElementById("type-translate");
  const send = document.getElementById("type-send");
  const status = document.getElementById("type-status");
  const reviewSection = document.getElementById("type-review");
  const reviewHeading = document.getElementById("type-review-heading");
  const original = document.getElementById("type-review-original");
  const translated = document.getElementById("type-review-translation");
  const finalEvents = document.getElementById("final-text-events");
  const announcement = document.getElementById("turn-announcement");
  if (!typeButton || !typeSection || !liveButton || !totalButton) return;

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
  let debounceTimer = null;
  let activeController = null;
  let lastInputValue = source.value;
  let lastAutomaticSource = null;

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
    if (activeController) {
      activeController.abort();
      activeController = null;
    }
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
    typeActions.hidden = textMode === "live";
    source.disabled = !available || sending;
    translate.disabled = !available || textMode !== "total" || translating || sending;
    send.disabled = !available || sending || translating || !review || review.revision !== revision
      || review.originalText !== source.value || Date.now() >= Date.parse(review.expiresAt);
  }

  function invalidate(message, state = "idle") {
    revision++;
    cancelPending();
    translating = false;
    lastAutomaticSource = null;
    clearReview();
    if (message) setStatus(message, state);
    update();
  }

  function liveCopy() {
    typeEyebrow.textContent = "Live private translation";
    typeHeading.textContent = "Type and pause to preview";
    typeIntro.textContent = "Only you can see this preview until you send. Gemini processes text after a short typing pause.";
    reviewHeading.textContent = "Private preview — Not sent";
  }

  function totalCopy() {
    typeEyebrow.textContent = "Total text translation";
    typeHeading.textContent = "Type a complete message";
    typeIntro.textContent = "Translate privately to review. Your partner sees it only after you select Send. Gemini processes text when you select Translate.";
    reviewHeading.textContent = "Translated — Not sent";
  }

  function selectMode(next) {
    if (next === mode) return;
    mode = next;
    speakButton.setAttribute("aria-pressed", String(mode === "speak"));
    typeButton.setAttribute("aria-pressed", String(mode === "type"));
    speakSection.hidden = mode !== "speak";
    typeSection.hidden = mode !== "type";
    invalidate(mode === "type" ? (textMode === "live"
      ? "Live private preview is ready for input." : "Ready to translate.")
      : "Text translation paused while Speak is selected.", "idle");
    update();
    if (mode === "type") {
      source.focus();
      if (textMode === "live") scheduleLive();
    } else speakButton.focus();
  }

  function selectTextMode(next) {
    if (next === textMode) return;
    textMode = next;
    typeSection.dataset.textMode = next;
    liveButton.setAttribute("aria-pressed", String(next === "live"));
    totalButton.setAttribute("aria-pressed", String(next === "total"));
    if (next === "live") liveCopy(); else totalCopy();
    invalidate(next === "live" ? "Live private preview is ready for input."
      : "Total mode selected. Select Translate when your message is complete.", "idle");
    source.focus();
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

  async function translateCurrent(requestMode) {
    if (!current(requestMode) || translating || sending || composing) return;
    const text = source.value;
    if (!validSource(text)) {
      setStatus(text.trim() ? "Use no more than 1,000 Unicode characters."
        : (requestMode === "live" ? "Waiting for input." : "Enter 1 to 1,000 characters before translating."),
      text.trim() ? "error" : "idle");
      return;
    }
    invalidate();
    const requestRevision = revision;
    translating = true;
    if (requestMode === "live") lastAutomaticSource = text;
    const controller = typeof AbortController === "function" ? new AbortController() : null;
    activeController = controller;
    setStatus(requestMode === "live" ? "Creating your private live preview…"
      : "Translating for private review…", "translating");
    update();
    try {
      const { response, result } = await post("reviews",
        { sourceText: text, revision: requestRevision }, controller?.signal);
      if (requestRevision !== revision || !current(requestMode) || source.value !== text) return;
      if (!response.ok) {
        setStatus(messageFor(response, result, "Translation could not finish. Try again."),
          response.status === 429 ? "rate-limited" : "error");
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
      setStatus(requestMode === "live" ? "Private preview ready — Not sent."
        : "Translated — Not sent. Review before sending.", "ready");
      if (requestMode === "total") send.focus();
    } catch (error) {
      if (requestRevision === revision && error?.name !== "AbortError")
        setStatus("Connection failed. Try translating again.", "error");
    } finally {
      if (activeController === controller) activeController = null;
      if (requestRevision === revision) translating = false;
      update();
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
    if (text === lastAutomaticSource) return;
    setStatus("Waiting for typing to pause…", "waiting");
    debounceTimer = setTimeout(() => {
      debounceTimer = null;
      if (current("live") && !composing && source.value === text && text !== lastAutomaticSource)
        translateCurrent("live");
    }, LIVE_DEBOUNCE_MS);
  }

  function sourceChanged() {
    if (source.value === lastInputValue && !composing) return;
    lastInputValue = source.value;
    invalidate(composing ? "Composing text…" : (textMode === "live"
      ? "Waiting for typing to pause…" : "Message changed. Select Translate to review it."),
    composing ? "typing" : (textMode === "live" ? "waiting" : "idle"));
    if (textMode === "live" && !composing) scheduleLive();
  }

  function appendFinal(message, sender) {
    if (!message?.finalId || seen.has(message.finalId)) return;
    seen.add(message.finalId);
    if (seen.size > 256) seen.delete(seen.values().next().value);
    const row = document.createElement("li");
    row.className = "turn-card text-turn-card";
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
    while (finalEvents.childElementCount > 16) finalEvents.firstElementChild.remove();
    if (announcement) announcement.textContent = sender + " sent translated text.";
  }

  speakButton.addEventListener("click", () => selectMode("speak"));
  typeButton.addEventListener("click", () => selectMode("type"));
  liveButton.addEventListener("click", () => selectTextMode("live"));
  totalButton.addEventListener("click", () => selectTextMode("total"));
  source.addEventListener("compositionstart", () => {
    composing = true;
    invalidate("Composing text…", "typing");
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
      invalidate("Sent. Both connected participants can see the translated message.", "sent");
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
      event.senderParticipantId + "'] strong");
    appendFinal({ ...payload, finalId: event.turnId }, participant?.textContent || "Participant");
  });
  window.addEventListener("voicelink-glossary-changed", () => {
    if (mode !== "type") return;
    invalidate("The glossary changed. A fresh translation is required.", "waiting");
    if (textMode === "live") scheduleLive();
  });
  window.addEventListener("voicelink-room-state", () => {
    if (roomStatus.textContent === "CLOSED") {
      invalidate("This room is closed.", "error");
      finalEvents.replaceChildren();
      seen.clear();
    }
    update();
  });
  setInterval(() => {
    if (review && Date.now() >= Date.parse(review.expiresAt))
      invalidate("Private review expired. Translate again.", "error");
  }, 1000);
  totalCopy();
  setStatus("Ready to translate.", "idle");
  update();
})();
