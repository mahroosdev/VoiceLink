(() => {
  const page = document.getElementById("room-page");
  if (!page) return;
  const roomId = page.dataset.roomId;
  const roomStatus = document.getElementById("room-status");
  const speakButton = document.getElementById("mode-speak");
  const typeButton = document.getElementById("mode-type");
  const speakSection = document.getElementById("speech-section");
  const typeSection = document.getElementById("type-section");
  const source = document.getElementById("type-source");
  const translate = document.getElementById("type-translate");
  const send = document.getElementById("type-send");
  const status = document.getElementById("type-status");
  const reviewSection = document.getElementById("type-review");
  const original = document.getElementById("type-review-original");
  const translated = document.getElementById("type-review-translation");
  const finalEvents = document.getElementById("final-text-events");
  const announcement = document.getElementById("turn-announcement");
  if (!typeButton || !typeSection) return;

  const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
  const seen = new Set();
  let revision = 0;
  let review = null;
  let translating = false;
  let sending = false;
  let mode = "speak";

  function clearReview() {
    review = null;
    reviewSection.hidden = true;
    original.textContent = "";
    translated.textContent = "";
    send.disabled = true;
  }

  function current() {
    return roomStatus.textContent === "ACTIVE" && mode === "type";
  }

  function update() {
    const available = current();
    speakSection.hidden = roomStatus.textContent !== "ACTIVE" || mode !== "speak";
    typeSection.hidden = roomStatus.textContent !== "ACTIVE" || mode !== "type";
    source.disabled = !available || sending;
    translate.disabled = !available || translating || sending;
    send.disabled = !available || sending || !review || review.revision !== revision
      || review.originalText !== source.value || Date.now() >= Date.parse(review.expiresAt);
  }

  function invalidate(message) {
    revision++;
    translating = false;
    clearReview();
    if (message) status.textContent = message;
    update();
  }

  function selectMode(next) {
    if (next === mode) return;
    mode = next;
    speakButton.setAttribute("aria-pressed", String(mode === "speak"));
    typeButton.setAttribute("aria-pressed", String(mode === "type"));
    speakSection.hidden = mode !== "speak";
    typeSection.hidden = mode !== "type";
    invalidate("Mode changed. Translate again before sending.");
    update();
    if (mode === "type") source.focus();
    else speakButton.focus();
  }

  function messageFor(response, body, fallback) {
    if (typeof body?.error === "string" && body.error.length < 180) return body.error;
    if (response.status === 413) return "Message is too large. Shorten it and try again.";
    if (response.status === 403) return "This action is no longer authorized. Refresh the room.";
    return fallback;
  }

  async function post(path, body) {
    const response = await fetch("/api/rooms/" + roomId + "/text/" + path, {
      method: "POST", credentials: "same-origin",
      headers: { "Content-Type": "application/json", Accept: "application/json",
        ...(csrfHeader && csrfToken ? { [csrfHeader]: csrfToken } : {}) },
      body: JSON.stringify(body)
    });
    let result;
    try { result = await response.json(); } catch { result = {}; }
    return { response, result };
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
  source.addEventListener("input", () => invalidate("Message changed. Select Translate to review it."));
  translate.addEventListener("click", async () => {
    if (!current() || translating || sending) return;
    const text = source.value;
    if (!text.trim() || [...text].length > 1000) {
      status.textContent = "Enter 1 to 1,000 characters before translating.";
      return;
    }
    invalidate();
    const requestRevision = revision;
    translating = true;
    status.textContent = "Translating for private review…";
    update();
    try {
      const { response, result } = await post("reviews", { sourceText: text, revision: requestRevision });
      if (requestRevision !== revision || !current() || source.value !== text) return;
      if (!response.ok) {
        status.textContent = messageFor(response, result, "Translation could not finish. Try again.");
        return;
      }
      if (result.originalText !== text || result.revision !== requestRevision || !result.reviewId) {
        status.textContent = "Translation review is unavailable. Try again.";
        return;
      }
      review = result;
      original.textContent = result.originalText;
      translated.textContent = result.translatedText;
      original.lang = result.sourceLanguage;
      translated.lang = result.targetLanguage;
      reviewSection.hidden = false;
      status.textContent = "Translated — Not sent. Review before sending.";
      send.focus();
    } catch {
      if (requestRevision === revision) status.textContent = "Connection failed. Try translating again.";
    } finally {
      if (requestRevision === revision) translating = false;
      update();
    }
  });

  send.addEventListener("click", async () => {
    if (!current() || sending || !review || review.revision !== revision
        || review.originalText !== source.value || Date.now() >= Date.parse(review.expiresAt)) {
      status.textContent = "Translation changed or expired. Translate again.";
      clearReview();
      update();
      return;
    }
    const selected = review;
    sending = true;
    status.textContent = "Sending translated message…";
    update();
    try {
      const { response, result } = await post("send", {
        reviewId: selected.reviewId, sourceText: selected.originalText, revision: selected.revision
      });
      if (!response.ok) {
        status.textContent = messageFor(response, result, "Sending failed. Try again.");
        if (response.status === 409) clearReview();
        return;
      }
      appendFinal(result, "You");
      source.value = "";
      invalidate("Sent. Both connected participants can see the translated message.");
      if (mode === "type") source.focus();
    } catch {
      status.textContent = "Connection failed. You can retry Send; it will not duplicate a successful message.";
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
  window.addEventListener("voicelink-room-state", () => {
    if (roomStatus.textContent === "CLOSED") {
      invalidate("This room is closed.");
      finalEvents.replaceChildren();
      seen.clear();
    }
    update();
  });
  setInterval(() => {
    if (review && Date.now() >= Date.parse(review.expiresAt))
      invalidate("Private review expired. Translate again.");
  }, 1000);
  update();
})();
