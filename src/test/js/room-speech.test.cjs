const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const script = fs.readFileSync(path.resolve(__dirname,
  "../../main/resources/static/js/room-live.js"), "utf8");

function node() {
  const callbacks = {};
  const classes = new Set();
  return {
    callbacks, dataset: {}, attributes: {}, children: [], hidden: false, disabled: false,
    textContent: "", value: "", focused: false,
    classList: { add: (...v) => v.forEach(x => classes.add(x)),
      remove: (...v) => v.forEach(x => classes.delete(x)),
      toggle: (v, on) => on ? classes.add(v) : classes.delete(v) },
    addEventListener(type, fn) { callbacks[type] = fn; },
    click() { return callbacks.click?.(); },
    setAttribute(name, value) { this.attributes[name] = value; },
    getAttribute(name) { return this.attributes[name]; },
    removeAttribute(name) { delete this.attributes[name]; },
    focus() { this.focused = true; },
    append(...items) { this.children.push(...items); },
    replaceChildren() { this.children = []; },
    insertBefore(item, before) { const at = before ? this.children.indexOf(before) : -1;
      if (at < 0) this.children.push(item); else this.children.splice(at, 0, item); },
    remove() { this.removed = true; },
    querySelector(selector) { return selector === "span" ? this.label : null; },
    get childElementCount() { return this.children.length; },
    get firstElementChild() { return this.children[0]; }
  };
}

function setup(fetchImpl, microphoneError) {
  const ids = ["room-page", "room-status", "connection-state", "participants-list",
    "waiting-section", "active-section", "live-text-section", "closed-notice",
    "live-text-form", "live-text-input", "live-send", "quick-status", "conversation-events", "speech-section",
    "glossary-section", "room-details", "record-start", "record-stop", "recording-status",
    "recording-timer", "turn-empty", "turn-announcement", "mode-type", "text-mode-quick",
    "speech-mode-live", "speech-mode-finish", "speech-mode-help", "speech-review",
    "speech-review-transcript", "speech-review-translation", "speech-review-expiry",
    "speech-review-send", "speech-review-record-again", "speech-review-cancel",
    "speech-translation-retry", "speech-translation-retry-transcript",
    "speech-translation-retry-status", "speech-translation-retry-button"];
  const nodes = Object.fromEntries(ids.map(id => [id, node()]));
  nodes["room-page"].dataset.roomId = "room-1";
  nodes["room-status"].textContent = "ACTIVE";
  nodes["mode-type"].attributes["aria-pressed"] = "false";
  nodes["text-mode-quick"].attributes["aria-pressed"] = "false";
  nodes["record-start"].label = node();
  nodes["record-start"].label.textContent = "Start speaking";
  nodes["speech-mode-live"].attributes["aria-pressed"] = "true";
  nodes["speech-mode-finish"].attributes["aria-pressed"] = "false";
  nodes["speech-review"].hidden = true;
  const toolBar = node(), mainGrid = node();
  const listeners = {};
  const timers = new Map();
  let timerId = 1, now = 1000, uuid = 0;
  let confirmResult = true;
  const confirmationMessages = [];
  class FakeDate extends Date { static now() { return now; } }
  class FakeFormData {
    constructor() { this.values = new Map(); }
    append(name, value) { this.values.set(name, value); }
  }
  class FakeRecorder {
    static instances = [];
    static isTypeSupported(type) { return type === "audio/webm;codecs=opus"; }
    constructor() { this.state = "inactive"; FakeRecorder.instances.push(this); }
    start() { this.state = "recording"; }
    stop() { this.state = "inactive"; this.onstop(); }
    audio(bytes = new Uint8Array(64)) { this.ondataavailable({ data: new Blob([bytes]), size: bytes.length }); }
  }
  class FakeSocket {
    static OPEN = 1;
    static latest;
    constructor() { this.readyState = FakeSocket.OPEN; this.sent = []; FakeSocket.latest = this; }
    send(data) { this.sent.push(JSON.parse(data)); }
    close() { this.readyState = 3; }
  }
  class FakeEvent { constructor(type) { this.type = type; } }
  class FakeCustomEvent extends FakeEvent { constructor(type, options) { super(type); this.detail = options?.detail; } }
  const track = { stopped: false, stop() { this.stopped = true; } };
  const navigator = { mediaDevices: { getUserMedia: async () => {
    if (microphoneError) throw microphoneError;
    return { getTracks: () => [track] };
  } } };
  const document = {
    getElementById: id => nodes[id] || null,
    querySelector(selector) {
      if (selector === ".room-tool-bar") return toolBar;
      if (selector === ".room-main-grid") return mainGrid;
      if (selector === 'meta[name="_csrf_header"]') return { content: "X-CSRF-TOKEN" };
      if (selector === 'meta[name="_csrf"]') return { content: "token" };
      return null;
    },
    createElement: () => node()
  };
  const window = {
    MediaRecorder: FakeRecorder,
    addEventListener(type, fn) { listeners[type] = fn; },
    dispatchEvent(event) { listeners[event.type]?.(event); },
    confirm(message) { confirmationMessages.push(message); return confirmResult; }
  };
  const setTimeout = (fn, delay) => { const id = timerId++; timers.set(id, { fn, delay }); return id; };
  const clearTimeout = id => timers.delete(id);
  const runTimer = delay => {
    const found = [...timers.entries()].find(([, timer]) => timer.delay === delay);
    if (!found) return false;
    timers.delete(found[0]); found[1].fn(); return true;
  };
  vm.runInNewContext(script, { document, window, navigator, MediaRecorder: FakeRecorder,
    WebSocket: FakeSocket, location: { protocol: "https:", host: "example.test" },
    fetch: fetchImpl, FormData: FakeFormData, Blob, Date, Event: FakeEvent,
    CustomEvent: FakeCustomEvent, Date: FakeDate, crypto: { randomUUID: () => "id-" + (++uuid) },
    performance: { now: () => now }, setTimeout, clearTimeout, setInterval: () => 1,
    clearInterval: () => {} });
  FakeSocket.latest.onopen();
  return { nodes, listeners, timers, runTimer, confirmationMessages,
    setConfirm(value) { confirmResult = value; }, socket: FakeSocket.latest,
    currentSocket() { return FakeSocket.latest; },
    recorder: () => FakeRecorder.instances.at(-1),
    advance(ms) { now += ms; } };
}

const tick = () => new Promise(resolve => setImmediate(resolve));
const response = (body, status = 200) => ({ ok: status >= 200 && status < 300, status,
  json: async () => body, redirected: false });

test("translation failure keeps recognized text sender-only and retry posts the same turn ID", async () => {
  const calls = [];
  const h = setup(async (url, options = {}) => {
    calls.push({ url, options });
    return url.endsWith("/retry-translation") ? response({}, 202) : response([]);
  });
  const receive = event => h.socket.onmessage({ data: JSON.stringify(event) });
  receive({ protocolVersion: 1, streamId: "stream-retry", sequence: 1, eventId: "state",
    roomId: "room-1", type: "ROOM_STATE", payload: { status: "ACTIVE", participants: [
      { participantId: "sender", current: true, owner: true, speaking: "ta", listening: "en" },
      { participantId: "partner", current: false, owner: false, speaking: "en", listening: "ta" }
    ] } });
  receive({ protocolVersion: 1, streamId: "stream-retry", sequence: 2, eventId: "failed",
    roomId: "room-1", type: "TURN_FAILED", senderParticipantId: "sender", turnId: "turn-retry",
    payload: { turnIndex: 1, stage: "TRANSLATION", sourceLanguage: "ta",
      transcript: "ungalukku project update venuma", message: "Translation unavailable." } });
  assert.equal(h.nodes["speech-translation-retry"].hidden, false);
  assert.equal(h.nodes["speech-translation-retry-transcript"].textContent,
    "ungalukku project update venuma");
  assert.equal(h.nodes["speech-translation-retry-transcript"].lang, "ta");
  assert.equal(h.nodes["conversation-events"].children.length, 0);
  assert.match(h.nodes["speech-translation-retry-status"].textContent, /Translation unavailable/);
  await h.nodes["speech-translation-retry-button"].click();
  const retryCall = calls.find(call => call.url.endsWith("/retry-translation"));
  assert.equal(retryCall.url, "/api/rooms/room-1/turns/turn-retry/retry-translation");
  assert.equal(retryCall.options.method, "POST");
  assert.equal(retryCall.options.headers["X-CSRF-TOKEN"], "token");
  assert.match(h.nodes["speech-translation-retry-status"].textContent, /Translation is processing/);
});

async function finishRecording(harness) {
  harness.nodes["record-start"].callbacks.click();
  await tick();
  harness.recorder().audio();
  harness.advance(1000);
  harness.nodes["record-stop"].callbacks.click();
  await tick();
}

test("Finish and Send records a complete clip and renders only the private server review", async () => {
  const calls = [];
  let reviews = 0;
  const h = setup(async (url, options = {}) => {
    calls.push({ url, options });
    if (url.endsWith("/turns")) {
      assert.equal(options.body.values.get("workflow"), "FINISH_AND_SEND");
      return response({ turnId: "turn-private" }, 202);
    }
    if (url.endsWith("/review")) return response(++reviews === 1
      ? { status: "PENDING" }
      : { status: "READY_FOR_REVIEW", transcript: "<hello>", translatedText: "வணக்கம்",
          sourceLanguage: "en", targetLanguage: "ta", expiresAt: new Date(Date.now() + 120000).toISOString() });
    return response([]);
  });
  h.nodes["speech-mode-finish"].callbacks.click();
  assert.equal(h.nodes["speech-mode-help"].textContent, "Review your speech before sending.");
  await finishRecording(h);
  assert.equal(h.nodes["speech-review"].hidden, true);
  assert.match(h.nodes["recording-status"].textContent, /Recognizing speech privately/);
  h.runTimer(750);
  await tick();
  assert.equal(h.nodes["speech-review"].hidden, false);
  assert.equal(h.nodes["speech-review-transcript"].textContent, "<hello>");
  assert.equal(h.nodes["speech-review-translation"].textContent, "வணக்கம்");
  assert.equal(h.nodes["speech-review-send"].focused, true);
  assert.equal(h.nodes["conversation-events"].children.length, 0);
  assert.equal(calls.filter(call => call.url.endsWith("/turns")).length, 1);
});

test("server current marker keeps identical names distinct and attributes untranslated text", async () => {
  const h = setup(async () => response([]));
  const receive = event => h.socket.onmessage({ data: JSON.stringify(event) });
  receive({ protocolVersion: 1, streamId: "stream-1", sequence: 1, eventId: "event-1",
    roomId: "room-1", type: "ROOM_STATE", payload: { status: "ACTIVE", participants: [
      { participantId: "person-1", displayName: "Same Name", slot: 1,
        speaking: "en", listening: "ta", current: true, owner: true },
      { participantId: "person-2", displayName: "Same Name", slot: 2,
        speaking: "ta", listening: "en", current: false, owner: false }
    ] } });
  assert.equal(h.nodes["participants-list"].children[0].dataset.role, "You");
  assert.equal(h.nodes["participants-list"].children[1].dataset.role, "Partner");
  assert.equal(h.nodes["participants-list"].children[0].children[1].children[0].textContent,
    "You · Conversation Owner");
  assert.equal(h.nodes["participants-list"].children[1].children[1].children[0].textContent,
    "Same Name · Partner");
  assert.match(h.nodes["participants-list"].children[0].children[1].children[1].textContent,
    /You speak: English\nPartner receives: Tamil/);
  assert.match(h.nodes["participants-list"].children[1].children[1].children[1].textContent,
    /Partner speaks: Tamil\nYou receive: English/);
  receive({ protocolVersion: 1, streamId: "stream-1", sequence: 2, eventId: "event-2",
    roomId: "room-1", type: "ROOM_STATE", payload: { status: "ACTIVE", participants: [
      { participantId: "person-1", displayName: "Same Name", slot: 1,
        speaking: "en", listening: "ta", current: false, owner: true },
      { participantId: "person-2", displayName: "Same Name", slot: 2,
        speaking: "ta", listening: "en", current: true, owner: false }
    ] } });
  assert.match(h.nodes["participants-list"].children[0].children[1].children[1].textContent,
    /Partner speaks: English\nYou receive: Tamil/);
  assert.match(h.nodes["participants-list"].children[1].children[1].children[1].textContent,
    /You speak: Tamil\nPartner receives: English/);
  assert.equal(h.nodes["participants-list"].children[0].children[1].children[0].textContent,
    "Same Name · Conversation Owner");
  assert.equal(h.nodes["participants-list"].children[1].children[1].children[0].textContent,
    "You · Partner");
  receive({ protocolVersion: 1, streamId: "stream-1", sequence: 3, eventId: "event-3",
    roomId: "room-1", type: "TEXT_MESSAGE", senderParticipantId: "person-1",
    payload: { text: "<b>safe text</b>" } });
  const message = h.nodes["conversation-events"].children[0];
  assert.equal(message.dataset.senderRole, "Partner");
  assert.equal(message.children[1].textContent, "<b>safe text</b>");
  assert.equal(message.dataset.messageKind, "quick");
  assert.equal(h.nodes["turn-announcement"].textContent,
    "Partner sent a quick chat message.");
  receive({ protocolVersion: 1, streamId: "stream-1", sequence: 4, eventId: "event-4",
    roomId: "room-1", type: "TURN_ACCEPTED", senderParticipantId: "person-1", turnId: "turn-1",
    payload: { turnIndex: 1, sourceLanguage: "en", targetLanguage: "ta" } });
  assert.equal(h.nodes["conversation-events"].children[1].dataset.messageKind, "processing");
  receive({ protocolVersion: 1, streamId: "stream-1", sequence: 5, eventId: "event-5",
    roomId: "room-1", type: "TRANSLATION_READY", senderParticipantId: "person-1", turnId: "turn-1",
    payload: { turnIndex: 1, targetLanguage: "ta", translatedText: "பாதுகாப்பான உரை" } });
  assert.equal(h.nodes["conversation-events"].children.length, 2);
  assert.equal(h.nodes["conversation-events"].children[1].dataset.senderRole, "Partner");
  assert.equal(h.nodes["conversation-events"].children[1].dataset.messageKind, "translated");
  receive({ protocolVersion: 1, streamId: "stream-1", sequence: 5, eventId: "event-5",
    roomId: "room-1", type: "TRANSLATION_READY", senderParticipantId: "person-1", turnId: "turn-1",
    payload: { turnIndex: 1, targetLanguage: "ta", translatedText: "duplicate" } });
  assert.equal(h.nodes["conversation-events"].children.length, 2);
});

test("Quick chat waits for the server echo, stays open, and deduplicates retry echoes", async () => {
  const h = setup(async () => response([]));
  h.nodes["mode-type"].attributes["aria-pressed"] = "true";
  h.nodes["text-mode-quick"].attributes["aria-pressed"] = "true";
  h.socket.onmessage({ data: JSON.stringify({ protocolVersion: 1, streamId: "stream-quick",
    sequence: 1, eventId: "state", roomId: "room-1", type: "ROOM_STATE",
    payload: { status: "ACTIVE", participants: [{ participantId: "person-1", current: true,
      owner: true, displayName: "Same Name", speaking: "en", listening: "ta" }] } }) });
  assert.equal(h.nodes["live-text-section"].hidden, false);
  h.nodes["live-text-input"].value = "First quick message";
  h.nodes["live-text-form"].callbacks.submit({ preventDefault() {} });
  assert.equal(h.nodes["live-text-input"].value, "First quick message");
  assert.equal(h.nodes["live-text-input"].disabled, true);
  assert.equal(h.socket.sent.length, 1);
  const first = h.socket.sent[0];
  h.socket.onmessage({ data: JSON.stringify({ protocolVersion: 1, streamId: "stream-quick",
    sequence: 2, eventId: "echo-1", roomId: "room-1", type: "TEXT_MESSAGE",
    senderParticipantId: "person-1", payload: { clientMessageId: first.clientMessageId,
      text: "First quick message" } }) });
  assert.equal(h.nodes["live-text-input"].value, "");
  assert.equal(h.nodes["live-text-input"].disabled, false);
  assert.equal(h.nodes["live-text-section"].hidden, false);
  assert.equal(h.nodes["conversation-events"].children.length, 1);
  h.socket.onmessage({ data: JSON.stringify({ protocolVersion: 1, streamId: "stream-quick",
    sequence: 3, eventId: "echo-retry", roomId: "room-1", type: "TEXT_MESSAGE",
    senderParticipantId: "person-1", payload: { clientMessageId: first.clientMessageId,
      text: "First quick message" } }) });
  assert.equal(h.nodes["conversation-events"].children.length, 1);
  h.nodes["live-text-input"].value = "Second quick message";
  h.nodes["live-text-form"].callbacks.submit({ preventDefault() {} });
  assert.equal(h.socket.sent.length, 2);
  assert.notEqual(h.socket.sent[1].clientMessageId, first.clientMessageId);
});

test("Quick chat keeps its draft when the server rejects a send", async () => {
  const h = setup(async () => response([]));
  h.nodes["live-text-input"].value = "Keep this draft";
  h.nodes["live-text-form"].callbacks.submit({ preventDefault() {} });
  h.socket.onmessage({ data: JSON.stringify({ protocolVersion: 1, streamId: "stream-error",
    sequence: 1, eventId: "error", roomId: "room-1", type: "ERROR",
    payload: { code: "INVALID_MESSAGE", message: "Invalid live message." } }) });
  assert.equal(h.nodes["live-text-input"].value, "Keep this draft");
  assert.equal(h.nodes["live-text-input"].disabled, false);
  assert.match(h.nodes["quick-status"].textContent, /not sent/);
});

test("Quick chat retries keep the message ID for 30 minutes and expired resend warns before a new ID", async () => {
  const h = setup(async () => response([]));
  h.nodes["live-text-input"].value = "Pending quick message";
  h.nodes["live-text-form"].callbacks.submit({ preventDefault() {} });
  const firstId = h.socket.sent[0].clientMessageId;
  h.nodes["live-text-form"].callbacks.submit({ preventDefault() {} });
  assert.equal(h.socket.sent[1].clientMessageId, firstId);
  assert.equal(h.confirmationMessages.length, 0);

  h.socket.onclose({ code: 1006 });
  await tick();
  assert.equal(h.runTimer(1000), true);
  const reconnectedSocket = h.currentSocket();
  reconnectedSocket.onopen();
  assert.equal(reconnectedSocket.sent[0].clientMessageId, firstId);

  h.advance(30 * 60 * 1000);
  assert.equal(h.runTimer(30 * 60 * 1000), true);
  assert.match(h.nodes["quick-status"].textContent, /Delivery unconfirmed.*Automatic Quick chat retries stopped/);
  assert.equal(h.nodes["live-send"].textContent, "Send again (may duplicate)");

  reconnectedSocket.onclose({ code: 1006 });
  await tick();
  assert.equal(h.runTimer(1000), true);
  const afterDeadlineSocket = h.currentSocket();
  afterDeadlineSocket.onopen();
  assert.equal(afterDeadlineSocket.sent.length, 0);

  h.setConfirm(false);
  h.nodes["live-text-form"].callbacks.submit({ preventDefault() {} });
  assert.equal(afterDeadlineSocket.sent.length, 0);
  assert.match(h.confirmationMessages[0], /may create a duplicate/);

  h.setConfirm(true);
  h.nodes["live-text-form"].callbacks.submit({ preventDefault() {} });
  assert.equal(afterDeadlineSocket.sent.length, 1);
  assert.notEqual(afterDeadlineSocket.sent[0].clientMessageId, firstId);
  assert.equal(h.confirmationMessages.length, 2);
});

test("explicit speech Send is single-action and mode change cancels an unsent review", async () => {
  const calls = [];
  const h = setup(async (url, options = {}) => {
    calls.push({ url, options });
    if (url.endsWith("/turns")) return response({ turnId: "turn-private" }, 202);
    if (url.endsWith("/review")) return options.method === "DELETE" ? response({}, 204)
      : response({ status: "READY_FOR_REVIEW", transcript: "Hello", translatedText: "வணக்கம்",
          sourceLanguage: "en", targetLanguage: "ta", expiresAt: new Date(Date.now() + 120000).toISOString() });
    if (url.endsWith("/send")) return response({ duplicate: false });
    return response([]);
  });
  h.nodes["speech-mode-finish"].callbacks.click();
  await finishRecording(h);
  assert.equal(h.nodes["speech-review"].hidden, false);
  h.nodes["speech-review-send"].callbacks.click();
  h.nodes["speech-review-send"].callbacks.click();
  await tick();
  assert.equal(calls.filter(call => call.url.endsWith("/send")).length, 1);
  assert.equal(h.nodes["speech-review"].hidden, true);

  await finishRecording(h);
  h.nodes["speech-mode-live"].callbacks.click();
  await tick();
  assert.equal(calls.filter(call => call.options.method === "DELETE").length, 1);
  assert.equal(h.nodes["speech-review"].hidden, true);
});

test("Record again invalidates the old review and starts a fresh bounded recording", async () => {
  const calls = [];
  const h = setup(async (url, options = {}) => {
    calls.push({ url, options });
    if (url.endsWith("/turns")) return response({ turnId: "turn-private" }, 202);
    if (url.endsWith("/review")) return options.method === "DELETE" ? response({}, 204)
      : response({ status: "READY_FOR_REVIEW", transcript: "Hello", translatedText: "வணக்கம்",
          sourceLanguage: "en", targetLanguage: "ta", expiresAt: new Date(Date.now() + 120000).toISOString() });
    return response([]);
  });
  h.nodes["speech-mode-finish"].callbacks.click();
  await finishRecording(h);
  h.nodes["speech-review-record-again"].callbacks.click();
  await tick();
  assert.equal(calls.filter(call => call.options.method === "DELETE").length, 1);
  assert.equal(h.recorder().state, "recording");
  assert.equal(h.timers.size > 0, true);
});

test("microphone denial and the 15 second stop remain accessible failures and limits", async () => {
  const denied = setup(async () => response([]), { name: "NotAllowedError" });
  denied.nodes["record-start"].callbacks.click();
  await tick();
  assert.match(denied.nodes["recording-status"].textContent, /Microphone access was denied/);
  assert.equal(denied.nodes["speech-section"].dataset.phase, "error");

  const limited = setup(async url => url.endsWith("/turns")
    ? response({ turnId: "live" }, 202) : response([]));
  limited.nodes["record-start"].callbacks.click();
  await tick();
  limited.recorder().audio();
  limited.advance(15000);
  assert.equal(limited.runTimer(15000), true);
  await tick();
  assert.equal(limited.recorder().state, "inactive");
  assert.match(limited.nodes["recording-status"].textContent, /accepted for processing/);
});
