const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const root = path.resolve(__dirname, "../../main/resources");
const html = fs.readFileSync(path.join(root, "templates/room.html"), "utf8");
const script = fs.readFileSync(path.join(root, "static/js/room-text.js"), "utf8");
const liveScript = fs.readFileSync(path.join(root, "static/js/room-live.js"), "utf8");
const css = fs.readFileSync(path.join(root, "static/css/voicelink.css"), "utf8");

test("room keeps Speak as default and adds private Type controls in the same workspace", () => {
  assert.match(html, /id="mode-speak"[^>]*aria-pressed="true"/);
  assert.match(html, /id="mode-type"[^>]*aria-pressed="false"/);
  assert.match(html, /id="type-section"[^>]*hidden/);
  assert.match(html, /Translated — Not sent/);
  assert.match(html, /id="type-send"[^>]*disabled/);
  assert.match(html, /id="speech-section"/);
  assert.match(html, /id="live-text-section"/);
  assert.match(liveScript, /TEXT_TRANSLATION_SENT/);
  assert.doesNotMatch(script, /innerHTML|insertAdjacentHTML|localStorage|sessionStorage/);
  assert.match(css, /max-width: 390px/);
  assert.match(css, /conversation-mode__button:focus-visible/);
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
  const ids = ["room-page", "room-status", "mode-speak", "mode-type", "speech-section",
    "type-section", "type-source", "type-translate", "type-send", "type-status",
    "type-review", "type-review-original", "type-review-translation", "final-text-events",
    "turn-announcement"];
  const nodes = Object.fromEntries(ids.map(id => [id, element()]));
  nodes["room-page"].dataset.roomId = "room-1";
  nodes["room-status"].textContent = "ACTIVE";
  nodes["mode-speak"].attributes["aria-pressed"] = "true";
  nodes["mode-type"].attributes["aria-pressed"] = "false";
  nodes["type-section"].hidden = true;
  const listeners = {};
  const timers = [];
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
  vm.runInNewContext(script, { document, window, fetch, setInterval(fn) { timers.push(fn); }, Date });
  return { nodes, listeners, timers };
}

const next = () => new Promise(resolve => setImmediate(resolve));
const response = (body, ok = true, status = 200) => ({ ok, status, json: async () => body });
const privateReview = (sourceText, revision, reviewId = "review-1") => ({
  reviewId, originalText: sourceText, translatedText: "வணக்கம்", sourceLanguage: "en",
  targetLanguage: "ta", profile: "STANDARD", revision,
  expiresAt: "2099-01-01T00:00:00Z"
});

test("typing makes no provider request, Translate stays private, edit and mode change disable Send", async () => {
  const calls = [];
  const { nodes } = setup(async (url, request) => {
    calls.push({ url, request });
    return response(privateReview("Hello", 3));
  });
  nodes["mode-type"].callbacks.click();
  assert.equal(nodes["type-section"].hidden, false);
  assert.equal(nodes["speech-section"].hidden, true);
  nodes["type-source"].value = "Hello";
  nodes["type-source"].callbacks.input();
  assert.equal(calls.length, 0);
  nodes["type-translate"].callbacks.click();
  await next();
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, "/api/rooms/room-1/text/reviews");
  assert.equal(calls[0].request.headers["X-CSRF-TOKEN"], "test-token");
  assert.equal(nodes["type-send"].disabled, false);
  assert.equal(nodes["type-review"].hidden, false);
  nodes["type-source"].value = "Hello!";
  nodes["type-source"].callbacks.input();
  assert.equal(nodes["type-send"].disabled, true);
  assert.equal(nodes["type-review"].hidden, true);
  nodes["mode-speak"].callbacks.click();
  assert.equal(nodes["speech-section"].hidden, false);
  assert.equal(nodes["type-send"].disabled, true);
});

test("older asynchronous review cannot overwrite a newer edit and translation", async () => {
  const deferred = [];
  const { nodes } = setup(() => new Promise(resolve => deferred.push(resolve)));
  nodes["mode-type"].callbacks.click();
  nodes["type-source"].value = "First";
  nodes["type-source"].callbacks.input();
  nodes["type-translate"].callbacks.click();
  nodes["type-source"].value = "Second";
  nodes["type-source"].callbacks.input();
  nodes["type-translate"].callbacks.click();
  assert.equal(deferred.length, 2);
  deferred[1](response(privateReview("Second", 5, "review-new")));
  await next();
  assert.equal(nodes["type-review-original"].textContent, "Second");
  deferred[0](response(privateReview("First", 3, "review-old")));
  await next();
  assert.equal(nodes["type-review-original"].textContent, "Second");
  assert.equal(nodes["type-send"].disabled, false);
});

test("Send finalizes once in the UI, deduplicates matching socket event, and escapes content", async () => {
  const calls = [];
  const { nodes, listeners } = setup(async (url, request) => {
    calls.push(url);
    if (url.endsWith("/reviews")) return response(privateReview("<unsafe>", 3));
    assert.deepEqual(JSON.parse(request.body), {
      reviewId: "review-1", sourceText: "<unsafe>", revision: 3
    });
    return response({ finalId: "final-1", originalText: "<unsafe>", translatedText: "வணக்கம்",
      sourceLanguage: "en", targetLanguage: "ta", profile: "STANDARD", duplicate: false });
  });
  nodes["mode-type"].callbacks.click();
  nodes["type-source"].value = "<unsafe>";
  nodes["type-source"].callbacks.input();
  nodes["type-translate"].callbacks.click();
  await next();
  assert.equal(calls.length, 1);
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
  listeners["voicelink-final-text"]({ detail: { roomId: "room-1", turnId: "final-2",
    senderParticipantId: "person-2", payload: { originalText: "Another message",
      translatedText: "மற்றொரு செய்தி", sourceLanguage: "en", targetLanguage: "ta" } } });
  assert.equal(nodes["turn-announcement"].textContent, "Participant sent translated text.");
});

test("rate-limit and stale-send errors do not show a sent message", async () => {
  let reviews = 0;
  const { nodes } = setup(async url => {
    if (url.endsWith("/reviews")) {
      reviews++;
      return reviews === 1
        ? response({ error: "Too many text translations. Please wait and try again." }, false, 429)
        : response(privateReview("Hello", 4));
    }
    return response({ error: "The glossary changed. Translate again." }, false, 409);
  });
  nodes["mode-type"].callbacks.click();
  nodes["type-source"].value = "Hello";
  nodes["type-source"].callbacks.input();
  nodes["type-translate"].callbacks.click();
  await next();
  assert.match(nodes["type-status"].textContent, /Too many text translations/);
  assert.equal(nodes["type-review"].hidden, true);
  nodes["type-translate"].callbacks.click();
  await next();
  nodes["type-send"].callbacks.click();
  await next();
  assert.match(nodes["type-status"].textContent, /glossary changed/);
  assert.equal(nodes["type-review"].hidden, true);
  assert.equal(nodes["final-text-events"].childElementCount, 0);
});

test("expiry and room closure clear private and temporary visible state", async () => {
  const { nodes, listeners, timers } = setup(async () => response({
    ...privateReview("Hello", 3), expiresAt: "2000-01-01T00:00:00Z"
  }));
  nodes["mode-type"].callbacks.click();
  nodes["type-source"].value = "Hello";
  nodes["type-source"].callbacks.input();
  nodes["type-translate"].callbacks.click();
  await next();
  timers[0]();
  assert.equal(nodes["type-review"].hidden, true);
  assert.equal(nodes["type-send"].disabled, true);
  nodes["room-status"].textContent = "CLOSED";
  listeners["voicelink-room-state"]();
  assert.equal(nodes["type-section"].hidden, true);
  assert.equal(nodes["final-text-events"].childElementCount, 0);
});
