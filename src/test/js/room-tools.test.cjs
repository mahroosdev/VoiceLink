const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const template = fs.readFileSync(path.resolve(__dirname,
  "../../main/resources/templates/room.html"), "utf8");
const script = template.match(/<script th:if="\$\{room\.status\.name\(\) != 'CLOSED'\}">\s*([\s\S]*?)\s*<\/script>/)?.[1];
assert.ok(script, "contextual room tools script must be present");
const glossaryScript = fs.readFileSync(path.resolve(__dirname,
  "../../main/resources/static/js/room-glossary.js"), "utf8");

test("glossary uses a native modal instead of an automatic edge drawer", () => {
  assert.match(template, /<dialog id="glossary-section" class="glossary-dialog"/);
  assert.match(glossaryScript, /panel\.showModal\(\)/);
  assert.doesNotMatch(glossaryScript, /panel\.open = true/);
});

function setup() {
  function makeTool() {
    const events = {};
    const trigger = { focused: false, focus() { this.focused = true; } };
    return {
      open: false, events, trigger,
      querySelector(selector) { assert.equal(selector, "summary"); return trigger; },
      addEventListener(name, callback) { events[name] = callback; },
      contains(target) { return target === this || target === trigger; }
    };
  }
  const details = makeTool();
  const events = {};
  const document = {
    querySelectorAll(selector) {
      assert.equal(selector, ".room-tool-bar > details");
      return [details];
    },
    addEventListener(name, callback) { events[name] = callback; }
  };
  vm.runInNewContext(script, { document });
  return { details, events };
}

test("room details remains a contextual disclosure", () => {
  const { details } = setup();
  details.open = true;
  details.events.toggle();
  assert.equal(details.open, true);
});

test("Escape closes a room tool and returns focus to its trigger", () => {
  const { details } = setup();
  details.open = true;
  let stopped = false;
  details.events.keydown({ key: "Escape", stopPropagation() { stopped = true; } });
  assert.equal(details.open, false);
  assert.equal(details.trigger.focused, true);
  assert.equal(stopped, true);
});

test("outside pointer press closes the panel but an inside press does not", () => {
  const { details, events } = setup();
  details.open = true;
  events.pointerdown({ target: details.trigger });
  assert.equal(details.open, true);
  events.pointerdown({ target: {} });
  assert.equal(details.open, false);
});
