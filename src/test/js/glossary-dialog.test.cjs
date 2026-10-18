const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const script = fs.readFileSync(path.resolve(__dirname,
  "../../main/resources/static/js/room-glossary.js"), "utf8");

async function setup({ entries = [], editable = true, saveStatus = 200 } = {}) {
  const nodes = new Map();
  const windowEvents = new Map();
  const requests = [];
  const document = {
    activeElement: null,
    getElementById(id) { return nodes.get(id); },
    createElement(tag) { return makeNode(tag); },
    createTextNode(text) { return { textContent: text }; },
    querySelector(selector) {
      return { content: selector === 'meta[name="_csrf_header"]' ? "X-CSRF-TOKEN" : "test-token" };
    }
  };
  function makeNode(tag = "div") {
    const events = new Map();
    return {
      tag, events, children: [], dataset: {}, hidden: false, disabled: false,
      textContent: "", value: "", attributes: {}, open: false,
      addEventListener(name, callback) { events.set(name, callback); },
      dispatch(name, event = {}) { return events.get(name)?.(event); },
      append(...children) { this.children.push(...children); },
      replaceChildren(...children) { this.children = children; },
      setAttribute(name, value) { this.attributes[name] = value; },
      removeAttribute(name) { delete this.attributes[name]; },
      focus() { document.activeElement = this; },
      showModal() { this.open = true; },
      close() { this.open = false; this.dispatch("close"); }
    };
  }
  const ids = ["room-page", "glossary-section", "glossary-open", "glossary-close",
    "glossary-view", "glossary-empty", "glossary-add", "glossary-list",
    "glossary-status", "glossary-capacity", "glossary-form-heading", "glossary-save",
    "glossary-cancel", "glossary-source-language", "glossary-target-language",
    "glossary-source-term", "glossary-preferred-term", "glossary-form",
    "glossary-refresh", "room-status", "room-details"];
  for (const id of ids) nodes.set(id, makeNode());
  nodes.get("room-page").dataset.roomId = "room-1";
  nodes.get("room-status").textContent = "WAITING";
  nodes.get("glossary-form").dataset.canEdit = String(editable);
  nodes.get("glossary-form").hidden = true;
  nodes.get("glossary-add").hidden = true;
  nodes.get("glossary-form").elements = ["glossary-save", "glossary-cancel",
    "glossary-source-language", "glossary-target-language", "glossary-source-term",
    "glossary-preferred-term"].map(id => nodes.get(id));
  nodes.get("glossary-form").reset = () => {
    nodes.get("glossary-source-language").value = "en";
    nodes.get("glossary-target-language").value = "ta";
    nodes.get("glossary-source-term").value = "";
    nodes.get("glossary-preferred-term").value = "";
  };
  nodes.get("glossary-form").reset();
  const window = {
    addEventListener(name, callback) { windowEvents.set(name, callback); },
    confirm() { return true; }
  };
  async function fetch(url, options = {}) {
    requests.push({ url, options });
    if (!options.method) return { ok: true, json: async () => entries };
    if (options.method === "DELETE") {
      entries = [];
      return { ok: true };
    }
    if (saveStatus !== 200) return {
      ok: false, status: saveStatus,
      json: async () => ({ error: "Invalid glossary term" })
    };
    return { ok: true };
  }
  vm.runInNewContext(script, { document, window, fetch });
  await settle();
  return { nodes, document, windowEvents, requests };
}

async function settle() {
  for (let i = 0; i < 5; i++) await Promise.resolve();
}

test("empty modal keeps the form hidden and returns focus after native close", async () => {
  const { nodes, document } = await setup();
  const get = id => nodes.get(id);
  assert.equal(get("glossary-capacity").textContent, "0/12 terms");
  assert.equal(get("glossary-empty").hidden, false);
  assert.equal(get("glossary-form").hidden, true);
  get("room-details").open = true;
  get("glossary-open").dispatch("click");
  assert.equal(get("glossary-section").open, true);
  assert.equal(get("room-details").open, false);
  assert.equal(document.activeElement, get("glossary-close"));
  get("glossary-section").close(); // Native Escape dispatches close.
  assert.equal(document.activeElement, get("glossary-open"));
});

test("Add term reveals the existing fields and Cancel returns to terms", async () => {
  const { nodes, document } = await setup();
  const get = id => nodes.get(id);
  get("glossary-open").dispatch("click");
  get("glossary-add").dispatch("click");
  assert.equal(get("glossary-view").hidden, true);
  assert.equal(get("glossary-form").hidden, false);
  assert.equal(document.activeElement, get("glossary-source-language"));
  get("glossary-cancel").dispatch("click");
  assert.equal(get("glossary-view").hidden, false);
  assert.equal(get("glossary-form").hidden, true);
  assert.equal(document.activeElement, get("glossary-add"));
});

test("populated terms keep edit and confirmed delete with row version", async () => {
  const entry = { id: "term-1", rowVersion: 3, sourceLanguage: "en",
    targetLanguage: "ta", sourceTerm: "REST API", preferredTerm: "REST API" };
  const { nodes, document, requests } = await setup({ entries: [entry] });
  const get = id => nodes.get(id);
  get("glossary-open").dispatch("click");
  assert.equal(get("glossary-empty").hidden, true);
  assert.equal(get("glossary-capacity").textContent, "1/12 terms");
  const actions = get("glossary-list").children[0].children[2].children;
  actions[0].dispatch("click");
  assert.equal(get("glossary-form").hidden, false);
  assert.equal(get("glossary-save").textContent, "Save changes");
  assert.equal(get("glossary-source-term").value, "REST API");
  assert.equal(document.activeElement, get("glossary-source-term"));
  get("glossary-source-term").value = "REST API v2";
  await get("glossary-form").dispatch("submit", { preventDefault() {} });
  const update = requests.find(request => request.options.method === "PUT");
  assert.equal(update.url, "/api/rooms/room-1/glossary/term-1");
  assert.equal(JSON.parse(update.options.body).expectedVersion, 3);
  assert.equal(update.options.headers["X-CSRF-TOKEN"], "test-token");
  assert.equal(get("glossary-form").hidden, true);
  await actions[1].dispatch("click");
  assert.equal(get("glossary-capacity").textContent, "0/12 terms");
  const deletion = requests.find(request => request.options.method === "DELETE");
  assert.equal(deletion.url, "/api/rooms/room-1/glossary/term-1");
  assert.equal(JSON.parse(deletion.options.body).expectedVersion, 3);
});

test("server validation feedback remains in the form and marks terms invalid", async () => {
  const { nodes } = await setup({ saveStatus: 400 });
  const get = id => nodes.get(id);
  get("glossary-open").dispatch("click");
  get("glossary-add").dispatch("click");
  await get("glossary-form").dispatch("submit", { preventDefault() {} });
  assert.equal(get("glossary-form").hidden, false);
  assert.equal(get("glossary-source-term").attributes["aria-invalid"], "true");
  assert.match(get("glossary-status").textContent, /Check term lengths/);
  assert.equal(get("glossary-status").dataset.kind, "error");
});

test("a full or read-only glossary prevents Add while retaining term access", async () => {
  const term = i => ({ id: String(i), rowVersion: 0, sourceLanguage: "en",
    targetLanguage: "ta", sourceTerm: "Term " + i, preferredTerm: "Term " + i });
  const full = await setup({ entries: Array.from({ length: 12 }, (_, i) => term(i)) });
  assert.equal(full.nodes.get("glossary-add").disabled, true);
  assert.equal(full.nodes.get("glossary-list").children.length, 12);
  const readOnly = await setup({ entries: [term(1)], editable: false });
  assert.equal(readOnly.nodes.get("glossary-add").hidden, true);
  assert.equal(readOnly.nodes.get("glossary-list").children[0].children.length, 2);
});
