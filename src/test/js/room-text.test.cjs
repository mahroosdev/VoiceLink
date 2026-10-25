const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const root = path.resolve(__dirname, "../../main/resources");
const html = fs.readFileSync(path.join(root, "templates/room.html"), "utf8");
const script = fs.readFileSync(path.join(root, "static/js/room-text.js"), "utf8");
const glossaryScript = fs.readFileSync(path.join(root, "static/js/room-glossary.js"), "utf8");
const liveScript = fs.readFileSync(path.join(root, "static/js/room-live.js"), "utf8");
const css = fs.readFileSync(path.join(root, "static/css/voicelink.css"), "utf8");

test("room keeps stable composer modes and always-private Live translation", () => {
  assert.match(html, /id="mode-speak"[^>]*aria-pressed="true"/);
  assert.match(html, /id="mode-type"[^>]*aria-pressed="false"/);
  assert.match(html, /id="text-mode-live"[^>]*type="button"[^>]*aria-pressed="false"/);
  assert.match(html, /id="text-mode-total"[^>]*aria-pressed="true"[^>]*>Translate message/);
  assert.match(html, /id="text-mode-quick"[^>]*>Quick chat/);
  assert.doesNotMatch(html, /Private Live|Shared Live|Live preview visibility|Your translated drafts will be visible/);
  assert.match(script, /Only you can see this preview until you select Send/);
  assert.doesNotMatch(script, /text\/sharing|TEXT_DRAFT_UPDATED|startSharing|stopSharing/);
  assert.doesNotMatch(html, /id="history-all"|>All messages</);
  assert.doesNotMatch(html, /shared-text-draft|Shared Live|Private Live/);
  assert.match(html, /id="history-translated"[^>]*aria-pressed="true"/);
  assert.match(html, /id="type-section"[^>]*data-text-mode="total"[^>]*hidden/);
  assert.match(html, /id="type-retry"[^>]*type="button"[^>]*hidden/);
  assert.match(html, /id="type-send"[^>]*disabled/);
  assert.match(html, /id="speech-section"/);
  assert.match(html, /id="live-text-section"/);
  assert.doesNotMatch(liveScript, /TEXT_DRAFT_UPDATED|TEXT_DRAFT_CLEARED/);
  assert.match(html, /id="romanized-tamil"[^>]*type="checkbox"/);
  assert.match(html, /th:if="\$\{room\.currentParticipant\.speaking == 'ta'\}"/);
  assert.match(html, /th:attr="data-room-id=.*data-source-language=/);
  assert.equal((html.match(/id="conversation-events"/g) || []).length, 1);
  assert.match(liveScript, /getElementById\("conversation-events"\)/);
  assert.match(script, /getElementById\("conversation-events"\)/);
  assert.doesNotMatch(script, /innerHTML|insertAdjacentHTML|localStorage|sessionStorage/);
  assert.match(glossaryScript, /voicelink-glossary-changed/);
  assert.match(css, /type-mode__button:focus-visible/);
  assert.match(css, /max-width: 390px/);
  assert.match(css, /\.room-page #room-page \{ box-sizing: border-box; width: min\(100%, 960px\)/);
  assert.match(css, /\.room-tool-bar \{[^}]*flex-wrap: wrap/);
  assert.match(css, /\.room-main-grid #active-section \{ box-sizing: border-box; width: min\(100%, 960px\)/);
  assert.match(css, /\.turn-caption--translated p \{ font-size: clamp\(\.94rem, \.92rem \+ \.15vw, 1\.05rem\)/);
  assert.match(css, /recording-card\[data-phase="listening"\] #record-stop \{ position: static/);
  assert.match(css, /\.type-mode \{ display: grid; grid-template-columns: repeat\(3, minmax\(0, 1fr\)\);/);
  assert.match(css, /@media \(max-width: 520px\)[\s\S]*\.type-mode \{ grid-template-columns: repeat\(2, minmax\(0, 1fr\)\);/);
  assert.match(css, /@media \(prefers-reduced-motion: reduce\)[\s\S]*\.turn-card \{ transition: none !important; \}/);
});
test("speech Finish and Send is explicit, private by default, bounded, and safely rendered", () => {
  assert.match(html, /id="speech-mode-live"[^>]*aria-pressed="true"[^>]*>Live Send/);
  assert.match(html, /id="speech-mode-finish"[^>]*aria-pressed="false"[^>]*>Finish &amp; Send/);
  assert.match(html, /id="speech-review"[^>]*hidden/);
  assert.match(html, /id="speech-review-send"[^>]*type="button"/);
  assert.match(html, /id="speech-review-record-again"[^>]*type="button"/);
  assert.match(html, /id="speech-review-cancel"[^>]*type="button"/);
  assert.match(liveScript, /data\.append\("workflow", speechWorkflow\)/);
  assert.match(liveScript, /attempt >= 80/);
  assert.match(liveScript, /Private review timed out\. Record again\./);
  assert.match(liveScript, /Translation took too long\. The recognized caption remains available\./);
  assert.match(liveScript, /Audio generation failed\. Captions are still available\./);
  assert.match(liveScript, /method: "DELETE"/);
  assert.match(liveScript, /\/send"/);
  assert.match(liveScript, /speechReviewTranscript\.textContent = review\.transcript/);
  assert.match(liveScript, /speechReviewTranslation\.textContent = review\.translatedText/);
  assert.doesNotMatch(liveScript, /innerHTML|insertAdjacentHTML|localStorage|sessionStorage/);
  assert.match(css, /\.speech-mode__button:focus-visible/);
  assert.match(css, /\.speech-review__actions \.button \{ width: 100%; \}/);
});

function element() {
  const callbacks = {};
  return {
    callbacks, dataset: {}, hidden: false, disabled: false, value: "", textContent: "",
    children: [], attributes: {}, focused: false,
    addEventListener(name, fn) { callbacks[name] = fn; },
    setAttribute(name, value) { this.attributes[name] = value; },
    removeAttribute(name) { delete this.attributes[name]; },
    getAttribute(name) { return this.attributes[name]; },
    focus() { this.focused = true; },
    append(...items) { this.children.push(...items); },
    replaceChildren() { this.children = []; },
    get childElementCount() { return this.children.length; },
    get firstElementChild() { return { remove: () => this.children.shift() }; }
  };
}

function setup(fetch, debounce = "800") {
  const ids = ["room-page", "room-status", "mode-speak", "mode-type", "text-mode-live",
    "text-mode-total", "text-mode-quick", "translation-composer", "live-text-section", "romanized-tamil",
    "live-text-input", "speech-section", "type-section", "type-eyebrow", "type-heading",
    "type-intro", "type-actions", "type-source-label", "type-source",
    "type-translate", "type-retry", "type-send", "type-status",
    "type-review", "type-review-heading", "type-review-original", "type-review-original-label", "type-review-translation",
    "conversation-events", "turn-empty", "turn-announcement"];
  const nodes = Object.fromEntries(ids.map(id => [id, element()]));
  nodes["room-page"].dataset.roomId = "room-1";
  nodes["room-page"].dataset.liveTextDebounceMs = debounce;
  nodes["room-page"].dataset.sourceLanguage = "en";
  nodes["room-status"].textContent = "ACTIVE";
  nodes["mode-speak"].attributes["aria-pressed"] = "true";
  nodes["mode-type"].attributes["aria-pressed"] = "false";
  nodes["text-mode-live"].attributes["aria-pressed"] = "false";
  nodes["text-mode-total"].attributes["aria-pressed"] = "true";
  nodes["text-mode-quick"].attributes["aria-pressed"] = "false";
  nodes["romanized-tamil"].checked = false;
  nodes["type-section"].hidden = true;
  const listeners = {};
  const intervals = [];
  const timeouts = new Map();
  let nextTimer = 1;
  const document = {
    getElementById(id) { return nodes[id]; },
    querySelector(selector) {
      if (selector === 'meta[name="_csrf"]') return { content: "test-token" };
      if (selector === 'meta[name="_csrf_header"]') return { content: "X-CSRF-TOKEN" };
      return null;
    },
    createElement() { return element(); }
  };
  const dispatched = [];
  class FakeEvent { constructor(type) { this.type = type; } }
  class FakeCustomEvent extends FakeEvent { constructor(type, options) { super(type); this.detail = options?.detail; } }
  const window = {
    addEventListener(name, fn) { listeners[name] = fn; },
    dispatchEvent(event) { dispatched.push(event); listeners[event.type]?.(event); }
  };
  class FakeAbortController {
    constructor() { this.signal = { aborted: false }; }
    abort() { this.signal.aborted = true; }
  }
  const setTimeout = (fn, delay) => {
    const id = nextTimer++;
    timeouts.set(id, { fn, delay });
    return id;
  };
  const clearTimeout = id => timeouts.delete(id);
  const runTimeouts = delay => {
    const ready = [...timeouts.entries()].filter(([, timer]) => delay === undefined || timer.delay === delay);
    for (const [id, timer] of ready) {
      timeouts.delete(id);
      timer.fn();
    }
  };
  vm.runInNewContext(script, { document, window, fetch, setInterval(fn) { intervals.push(fn); },
    setTimeout, clearTimeout, AbortController: FakeAbortController, Event: FakeEvent,
    CustomEvent: FakeCustomEvent, Date });
  return { nodes, listeners, intervals, timeouts, runTimeouts, dispatched, window };
}

const next = () => new Promise(resolve => setImmediate(resolve));
const response = (body, ok = true, status = 200) => ({ ok, status, json: async () => body });
const privateReview = (sourceText, revision, reviewId = "review-1", romanizedTamil = false) => ({
  reviewId, originalText: sourceText, translatedText: "வணக்கம்", sourceLanguage: romanizedTamil ? "ta" : "en",
  targetLanguage: "ta", profile: "STANDARD", revision,
  expiresAt: "2099-01-01T00:00:00Z", romanizedTamil
});

test("Total remains explicit and source edits or Speak switching invalidate Send", async () => {
  const calls = [];
  const { nodes, runTimeouts } = setup(async (url, request) => {
    calls.push({ url, request });
    return response(privateReview("Hello", JSON.parse(request.body).revision));
  });
  nodes["mode-type"].callbacks.click();
  assert.equal(nodes["type-section"].hidden, false);
  assert.equal(nodes["speech-section"].hidden, true);
  assert.equal(nodes["type-actions"].hidden, false);
  nodes["type-source"].value = "Hello";
  nodes["type-source"].callbacks.input();
  runTimeouts();
  assert.equal(calls.length, 0);
  nodes["type-translate"].callbacks.click();
  await next();
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, "/api/rooms/room-1/text/reviews");
  assert.equal(calls[0].request.headers["X-CSRF-TOKEN"], "test-token");
  assert.equal(nodes["type-send"].disabled, false);
  nodes["type-source"].value = "Hello!";
  nodes["type-source"].callbacks.input();
  assert.equal(nodes["type-send"].disabled, true);
  assert.equal(nodes["type-review"].hidden, true);
  nodes["mode-speak"].callbacks.click();
  assert.equal(nodes["speech-section"].hidden, false);
  assert.equal(nodes["type-send"].disabled, true);
});

test("Live waits 800ms, collapses rapid typing, and does not repeat unchanged text", async () => {
  const calls = [];
  const { nodes, timeouts, runTimeouts } = setup(async (url, request) => {
    calls.push({ url, request });
    const body = JSON.parse(request.body);
    return response(privateReview(body.sourceText, body.revision));
  });
  nodes["mode-type"].callbacks.click();
  nodes["text-mode-live"].callbacks.click();
  assert.equal(nodes["type-actions"].hidden, true);
  assert.match(nodes["type-intro"].textContent, /Only you can see this preview until you select Send/);
  nodes["type-source"].value = "Hel";
  nodes["type-source"].callbacks.input();
  nodes["type-source"].value = "Hello";
  nodes["type-source"].callbacks.input();
  assert.equal(calls.length, 0);
  assert.equal(timeouts.size, 1);
  assert.equal([...timeouts.values()][0].delay, 800);
  runTimeouts(800);
  await next();
  assert.equal(calls.length, 1);
  assert.equal(JSON.parse(calls[0].request.body).sourceText, "Hello");
  assert.equal(nodes["type-status"].dataset.state, "ready");
  assert.equal(nodes["type-source"].focused, true);
  nodes["type-source"].callbacks.input();
  runTimeouts(800);
  await next();
  assert.equal(calls.length, 1);
});

test("Premium Cost Saver ON uses the server-rendered 1500ms live debounce", async () => {
  const calls = [];
  const { nodes, timeouts, runTimeouts } = setup(async (url, request) => {
    calls.push({ url, request });
    const body = JSON.parse(request.body);
    return response(privateReview(body.sourceText, body.revision));
  }, "1500");
  nodes["mode-type"].callbacks.click();
  nodes["text-mode-live"].callbacks.click();
  nodes["type-source"].value = "Hello";
  nodes["type-source"].callbacks.input();
  assert.equal([...timeouts.values()][0].delay, 1500);
  runTimeouts(800);
  assert.equal(calls.length, 0);
  runTimeouts(1500);
  await next();
  assert.equal(calls.length, 1);
});

test("Tamil IME composition never requests until composition ends and pause elapses", async () => {
  const calls = [];
  const { nodes, runTimeouts } = setup(async (url, request) => {
    calls.push({ url, request });
    const body = JSON.parse(request.body);
    return response(privateReview(body.sourceText, body.revision));
  });
  nodes["mode-type"].callbacks.click();
  nodes["text-mode-live"].callbacks.click();
  nodes["type-source"].callbacks.compositionstart();
  nodes["type-source"].value = "வணக்";
  nodes["type-source"].callbacks.input();
  runTimeouts(800);
  assert.equal(calls.length, 0);
  nodes["type-source"].value = "வணக்கம்";
  nodes["type-source"].callbacks.compositionend();
  assert.equal(calls.length, 0);
  runTimeouts(800);
  await next();
  assert.equal(calls.length, 1);
  assert.equal(JSON.parse(calls[0].request.body).sourceText, "வணக்கம்");
});

test("Romanized Tamil is explicit Tamil-to-English input and keeps source labeling", async () => {
  const calls = [];
  const { nodes } = setup(async (url, request) => {
    calls.push({ url, request });
    const body = JSON.parse(request.body);
    return response(privateReview(body.sourceText, body.revision, "review-romanized", body.romanizedTamil));
  });
  nodes["room-page"].dataset.sourceLanguage = "ta";
  nodes["romanized-tamil"].checked = true;
  nodes["mode-type"].callbacks.click();
  nodes["romanized-tamil"].callbacks.change();
  nodes["type-source"].value = "ungalukku enna project update vivarangal venum";
  nodes["type-translate"].callbacks.click();
  await next();
  const body = JSON.parse(calls[0].request.body);
  assert.equal(body.romanizedTamil, true);
  assert.equal(body.sourceText, "ungalukku enna project update vivarangal venum");
  assert.equal(nodes["type-source"].lang, "ta-Latn");
  assert.equal(nodes["type-review-original-label"].textContent, "Original · Romanized Tamil");
  assert.equal(nodes["type-review-original"].lang, "ta-Latn");
});

test("Romanized Tamil defaults off and Quick chat stays untranslated", async () => {
  const calls = [];
  const { nodes } = setup(async (url, request) => {
    calls.push(JSON.parse(request.body));
    return response(privateReview("வணக்கம்", 1));
  });
  nodes["room-page"].dataset.sourceLanguage = "ta";
  assert.equal(nodes["romanized-tamil"].checked, false);
  nodes["mode-type"].callbacks.click();
  nodes["text-mode-total"].callbacks.click();
  nodes["type-source"].value = "வணக்கம்";
  nodes["type-translate"].callbacks.click();
  await next();
  assert.equal(calls[0].romanizedTamil, false);
  nodes["text-mode-quick"].callbacks.click();
  assert.equal(nodes["translation-composer"].hidden, true);
  assert.equal(calls.length, 1);
});

test("Romanized mode handles empty input and retains the mode for an explicit retry", async () => {
  const calls = [];
  let fail = true;
  const { nodes } = setup(async (url, request) => {
    const body = JSON.parse(request.body);
    calls.push(body);
    if (fail) return response({ error: "temporarily unavailable", code: "TEMPORARILY_UNAVAILABLE" }, false, 503);
    return response(privateReview(body.sourceText, body.revision, "review-romanized", body.romanizedTamil));
  });
  nodes["room-page"].dataset.sourceLanguage = "ta";
  nodes["mode-type"].callbacks.click();
  nodes["romanized-tamil"].checked = true;
  nodes["romanized-tamil"].callbacks.change();
  nodes["type-translate"].callbacks.click();
  assert.equal(calls.length, 0);
  assert.match(nodes["type-status"].textContent, /Enter 1 to 1,000/);
  nodes["type-source"].value = "enna update";
  nodes["type-translate"].callbacks.click();
  await next();
  assert.equal(calls[0].romanizedTamil, true);
  assert.equal(nodes["type-retry"].hidden, false);
  fail = false;
  nodes["type-retry"].callbacks.click();
  await next();
  assert.equal(calls[1].romanizedTamil, true);
  assert.equal(calls[1].sourceText, "enna update");
  assert.equal(nodes["type-review-original-label"].textContent, "Original · Romanized Tamil");
});
test("Live coalesces edits behind one request and only the latest pending text is translated", async () => {
  const deferred = [];
  const requests = [];
  const { nodes, runTimeouts } = setup((url, request) => {
    requests.push(request);
    return new Promise(resolve => deferred.push(resolve));
  });
  nodes["mode-type"].callbacks.click();
  nodes["text-mode-live"].callbacks.click();
  nodes["type-source"].value = "First";
  nodes["type-source"].callbacks.input();
  runTimeouts(800);
  assert.equal(deferred.length, 1);
  nodes["type-source"].value = "Second";
  nodes["type-source"].callbacks.input();
  runTimeouts(800);
  assert.equal(deferred.length, 1);
  assert.match(nodes["type-status"].textContent, /Latest message queued/);
  const oldBody = JSON.parse(requests[0].body);
  deferred[0](response(privateReview("First", oldBody.revision, "review-old")));
  await next();
  assert.equal(nodes["type-review"].hidden, true);
  runTimeouts(800);
  assert.equal(deferred.length, 2);
  const newerBody = JSON.parse(requests[1].body);
  assert.equal(newerBody.sourceText, "Second");
  deferred[1](response(privateReview("Second", newerBody.revision, "review-new")));
  await next();
  assert.equal(nodes["type-review-original"].textContent, "Second");
  assert.equal(nodes["type-send"].disabled, false);
});

test("mode switching marks Live work stale and waits for it before restoring Total translation", async () => {
  let resolve;
  let request;
  const { nodes, runTimeouts } = setup((url, options) => {
    request = options;
    return new Promise(done => { resolve = done; });
  });
  nodes["mode-type"].callbacks.click();
  nodes["text-mode-live"].callbacks.click();
  nodes["type-source"].value = "Pending";
  nodes["type-source"].callbacks.input();
  runTimeouts(800);
  nodes["text-mode-total"].callbacks.click();
  assert.equal(nodes["type-actions"].hidden, false);
  assert.equal(nodes["type-translate"].disabled, true);
  assert.equal(nodes["type-send"].disabled, true);
  const body = JSON.parse(request.body);
  resolve(response(privateReview("Pending", body.revision)));
  await next();
  assert.equal(nodes["type-review"].hidden, true);
  assert.equal(nodes["type-translate"].disabled, false);
  assert.match(nodes["type-status"].textContent, /Translate message selected/);
});

test("Live Send uses the reviewed result once and socket echo is deduplicated", async () => {
  const calls = [];
  const { nodes, listeners, runTimeouts } = setup(async (url, request) => {
    calls.push(url);
    const body = JSON.parse(request.body);
    if (url.endsWith("/reviews")) return response(privateReview("<unsafe>", body.revision));
    assert.equal(body.sourceText, "<unsafe>");
    return response({ finalId: "final-1", originalText: "<unsafe>", translatedText: "வணக்கம்",
      sourceLanguage: "en", targetLanguage: "ta", profile: "STANDARD", duplicate: false });
  });
  nodes["mode-type"].callbacks.click();
  nodes["text-mode-live"].callbacks.click();
  nodes["type-source"].value = "<unsafe>";
  nodes["type-source"].callbacks.input();
  runTimeouts(800);
  await next();
  assert.equal(calls.length, 1);
  assert.equal(nodes["type-review-translation"].textContent, "வணக்கம்");
  nodes["type-send"].callbacks.click();
  await next();
  assert.equal(calls.length, 2);
  assert.equal(nodes["conversation-events"].childElementCount, 1);
  assert.equal(nodes["conversation-events"].children[0].children[1].children[1].textContent, "<unsafe>");
  listeners["voicelink-final-text"]({ detail: { roomId: "room-1", turnId: "final-1",
    senderParticipantId: "person-1", payload: { originalText: "<unsafe>",
      translatedText: "வணக்கம்", sourceLanguage: "en", targetLanguage: "ta" } } });
  assert.equal(nodes["conversation-events"].childElementCount, 1);
  assert.equal(nodes["turn-announcement"].textContent, "You sent translated text.");
});

test("rate-limit and provider failures leave Live editable but unsendable", async () => {
  let attempts = 0;
  const { nodes, runTimeouts } = setup(async () => {
    attempts++;
    return attempts === 1
      ? response({ error: "Too many text translations. Please wait and try again." }, false, 429)
      : response({ error: "Translation timed out. Please try again." }, false, 504);
  });
  nodes["mode-type"].callbacks.click();
  nodes["text-mode-live"].callbacks.click();
  nodes["type-source"].value = "Hello";
  nodes["type-source"].callbacks.input();
  runTimeouts(800);
  await next();
  assert.equal(nodes["type-status"].dataset.state, "rate-limited");
  assert.match(nodes["type-status"].textContent, /Too many text translations/);
  assert.equal(nodes["type-send"].disabled, true);
  assert.equal(nodes["type-source"].disabled, false);
  nodes["type-source"].value = "Hello again";
  nodes["type-source"].callbacks.input();
  runTimeouts(800);
  await next();
  assert.equal(nodes["type-status"].dataset.state, "error");
  assert.match(nodes["type-status"].textContent, /timed out/);
  assert.equal(nodes["type-send"].disabled, true);
});

test("Retry preserves unchanged failed text and revision while repeated clicks stay single-flight", async () => {
  const calls = [];
  let finishRetry;
  const { nodes, runTimeouts } = setup(async (url, request) => {
    calls.push({ url, request });
    const body = JSON.parse(request.body);
    if (calls.length === 1) return response({
      code: "TIMED_OUT",
      error: "Translation took too long. Your message remains here; try again."
    }, false, 504);
    return new Promise(resolve => {
      finishRetry = () => resolve(response(privateReview(body.sourceText, body.revision, "review-retry")));
    });
  });
  nodes["mode-type"].callbacks.click();
  nodes["text-mode-live"].callbacks.click();
  nodes["type-source"].value = "Keep this message";
  nodes["type-source"].callbacks.input();
  runTimeouts(800);
  await next();
  assert.equal(nodes["type-retry"].hidden, false);
  assert.match(nodes["type-status"].textContent, /message remains here/);
  const first = JSON.parse(calls[0].request.body);
  nodes["type-retry"].callbacks.click();
  nodes["type-retry"].callbacks.click();
  assert.equal(calls.length, 2);
  assert.deepEqual(JSON.parse(calls[1].request.body), first);
  assert.equal(nodes["type-retry"].disabled, true);
  finishRetry();
  await next();
  assert.equal(nodes["type-review-original"].textContent, "Keep this message");
  assert.equal(nodes["type-send"].disabled, false);
  assert.equal(nodes["type-retry"].hidden, true);
});

test("glossary changes invalidate a ready review and request a fresh Live preview", async () => {
  const calls = [];
  const { nodes, listeners, runTimeouts } = setup(async (url, request) => {
    const body = JSON.parse(request.body);
    calls.push(body);
    return response(privateReview(body.sourceText, body.revision, "review-" + calls.length));
  });
  nodes["mode-type"].callbacks.click();
  nodes["text-mode-live"].callbacks.click();
  nodes["type-source"].value = "REST API";
  nodes["type-source"].callbacks.input();
  runTimeouts(800);
  await next();
  assert.equal(nodes["type-send"].disabled, false);
  listeners["voicelink-glossary-changed"]();
  assert.equal(nodes["type-send"].disabled, true);
  assert.equal(nodes["type-review"].hidden, true);
  runTimeouts(800);
  await next();
  assert.equal(calls.length, 2);
  assert.equal(nodes["type-send"].disabled, false);
});

test("expiry and room closure clear private state and make pending responses stale", async () => {
  let pendingRequest;
  const { nodes, listeners, intervals, runTimeouts } = setup((url, request) => {
    pendingRequest = request;
    return Promise.resolve(response({ ...privateReview("Hello", JSON.parse(request.body).revision),
      expiresAt: "2000-01-01T00:00:00Z" }));
  });
  nodes["mode-type"].callbacks.click();
  nodes["text-mode-live"].callbacks.click();
  nodes["type-source"].value = "Hello";
  nodes["type-source"].callbacks.input();
  runTimeouts(800);
  await next();
  intervals[0]();
  assert.equal(nodes["type-review"].hidden, true);
  assert.equal(nodes["type-send"].disabled, true);
  nodes["type-source"].value = "Next";
  nodes["type-source"].callbacks.input();
  runTimeouts(800);
  nodes["room-status"].textContent = "CLOSED";
  listeners["voicelink-room-state"]();
  assert.equal(pendingRequest.signal, undefined);
  assert.equal(nodes["type-section"].hidden, true);
  assert.equal(nodes["conversation-events"].childElementCount, 0);
});
