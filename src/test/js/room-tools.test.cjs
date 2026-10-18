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

test("glossary does not open automatically on desktop", () => {
  assert.doesNotMatch(glossaryScript, /matchMedia\("\(min-width: 960px\)"\)\.matches\) panel\.open = true/);
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
  const glossary = makeTool();
  const details = makeTool();
  const events = {};
  const document = {
    querySelectorAll(selector) {
      assert.equal(selector, ".room-tool-bar > details");
      return [glossary, details];
    },
    addEventListener(name, callback) { events[name] = callback; }
  };
  vm.runInNewContext(script, { document });
  return { glossary, details, events };
}

test("opening one contextual room tool closes the other", () => {
  const { glossary, details } = setup();
  glossary.open = true;
  details.open = true;
  details.events.toggle();
  assert.equal(details.open, true);
  assert.equal(glossary.open, false);
});

test("Escape closes a room tool and returns focus to its trigger", () => {
  const { glossary } = setup();
  glossary.open = true;
  let stopped = false;
  glossary.events.keydown({ key: "Escape", stopPropagation() { stopped = true; } });
  assert.equal(glossary.open, false);
  assert.equal(glossary.trigger.focused, true);
  assert.equal(stopped, true);
});

test("outside pointer press closes the panel but an inside press does not", () => {
  const { glossary, events } = setup();
  glossary.open = true;
  events.pointerdown({ target: glossary.trigger });
  assert.equal(glossary.open, true);
  events.pointerdown({ target: {} });
  assert.equal(glossary.open, false);
});
