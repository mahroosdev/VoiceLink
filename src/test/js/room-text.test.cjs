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

test("room keeps Speak and Total defaults with a secondary Live private mode", () => {
  assert.match(html, /id="mode-speak"[^>]*aria-pressed="true"/);
  assert.match(html, /id="mode-type"[^>]*aria-pressed="false"/);
  assert.match(html, /id="text-mode-live"[^>]*type="button"[^>]*aria-pressed="false"/);
  assert.match(html, /id="text-mode-total"[^>]*type="button"[^>]*aria-pressed="true"/);
  assert.match(html, /data-live-text-debounce-ms="800"/);
  assert.match(html, /Private preview/);
  assert.match(html, /id="type-section"[^>]*data-text-mode="total"[^>]*hidden/);
  assert.match(html, /Translated — Not sent/);
  assert.match(html, /id="type-send"[^>]*disabled/);
  assert.match(html, /id="speech-section"/);
  assert.match(html, /id="live-text-section"/);
  assert.doesNotMatch(html, /Shared Live[^<]*selectable/i);
  assert.match(liveScript, /TEXT_TRANSLATION_SENT/);
  assert.doesNotMatch(script, /innerHTML|insertAdjacentHTML|localStorage|sessionStorage/);
  assert.match(glossaryScript, /voicelink-glossary-changed/);
  assert.match(css, /type-mode__button:focus-visible/);
  assert.match(css, /max-width: 390px/);
});

function element() {
  const callbacks = {};
  return {
    callbacks, dataset: {}, hidden: false, disabled: false, value: "", textContent: "",
    children: [], attributes: {}, focused: false,
    addEventListener(name, fn) { callbacks[name] = fn; },
    setAttribute(name, value) { this.attributes[name] = value; },
    getAttribute(name) { return this.attributes[name]; },
    focus() { this.focused = true; },
    append(...items) { this.children.push(...items); },
    replaceChildren() { this.children = []; },
    get childElementCount() { return this.children.length; },
    get firstElementChild() { return { remove: () => this.children.shift() }; }
  };
}

function setup(fetch) {
  const ids = ["room-page", "room-status", "mode-speak", "mode-type", "text-mode-live",
    "text-mode-total", "speech-section", "type-section", "type-eyebrow", "type-heading",
    "type-intro", "type-actions", "type-source", "type-translate", "type-send", "type-status",
    "type-review", "type-review-heading", "type-review-original", "type-review-translation",
    "final-text-events", "turn-announcement"];
  const nodes = Object.fromEntries(ids.map(id => [id, element()]));
  nodes["room-page"].dataset.roomId = "room-1";
  nodes["room-page"].dataset.liveTextDebounceMs = "800";
  nodes["room-status"].textContent = "ACTIVE";
  nodes["mode-speak"].attributes["aria-pressed"] = "true";
  nodes["mode-type"].attributes["aria-pressed"] = "false";
  nodes["text-mode-live"].attributes["aria-pressed"] = "false";
  nodes["text-mode-total"].attributes["aria-pressed"] = "true";
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
  const window = { addEventListener(name, fn) { listeners[name] = fn; } };
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
    setTimeout, clearTimeout, AbortController: FakeAbortController, Date });
  return { nodes, listeners, intervals, timeouts, runTimeouts };
}

const next = () => new Promise(resolve => setImmediate(resolve));
const response = (body, ok = true, status = 200) => ({ ok, status, json: async () => body });
const privateReview = (sourceText, revision, reviewId = "review-1") => ({
  reviewId, originalText: sourceText, translatedText: "வணக்கம்", sourceLanguage: "en",
  targetLanguage: "ta", profile: "STANDARD", revision,
  expiresAt: "2099-01-01T00:00:00Z"
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
  assert.match(nodes["type-intro"].textContent, /Only you can see this preview until you send/);
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

test("older and aborted Live responses cannot replace a newer preview", async () => {
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
  assert.equal(requests[0].signal.aborted, true);
  runTimeouts(800);
  assert.equal(deferred.length, 2);
  const newerBody = JSON.parse(requests[1].body);
  deferred[1](response(privateReview("Second", newerBody.revision, "review-new")));
  await next();
  assert.equal(nodes["type-review-original"].textContent, "Second");
  const oldBody = JSON.parse(requests[0].body);
  deferred[0](response(privateReview("First", oldBody.revision, "review-old")));
  await next();
  assert.equal(nodes["type-review-original"].textContent, "Second");
  assert.equal(nodes["type-send"].disabled, false);
});

test("mode switching aborts Live work and restores the existing Total workflow", async () => {
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
  assert.equal(request.signal.aborted, true);
  assert.equal(nodes["type-actions"].hidden, false);
  assert.equal(nodes["type-send"].disabled, true);
  const body = JSON.parse(request.body);
  resolve(response(privateReview("Pending", body.revision)));
  await next();
  assert.equal(nodes["type-review"].hidden, true);
  assert.match(nodes["type-status"].textContent, /Total mode selected/);
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
  assert.equal(nodes["final-text-events"].childElementCount, 1);
  assert.equal(nodes["final-text-events"].children[0].children[1].children[1].textContent, "<unsafe>");
  listeners["voicelink-final-text"]({ detail: { roomId: "room-1", turnId: "final-1",
    senderParticipantId: "person-1", payload: { originalText: "<unsafe>",
      translatedText: "வணக்கம்", sourceLanguage: "en", targetLanguage: "ta" } } });
  assert.equal(nodes["final-text-events"].childElementCount, 1);
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

test("expiry and room closure clear and cancel all private state", async () => {
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
  assert.equal(pendingRequest.signal.aborted, true);
  assert.equal(nodes["type-section"].hidden, true);
  assert.equal(nodes["final-text-events"].childElementCount, 0);
});
