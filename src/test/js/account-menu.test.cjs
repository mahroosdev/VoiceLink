const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const template = fs.readFileSync(path.resolve(__dirname,
  "../../main/resources/templates/fragments/app-shell.html"), "utf8");
const script = template.match(/<script>\s*([\s\S]*?)\s*<\/script>/)?.[1];
assert.ok(script, "account menu script must be present");

function setup(reducedMotion = false) {
  const events = {};
  const timers = [];
  const summary = {
    focusCalled: false,
    focus() { this.focusCalled = true; },
    contains(target) { return target === this; }
  };
  const menu = {
    open: true,
    dataset: {},
    addEventListener(name, callback) { events[name] = callback; },
    querySelector(selector) { assert.equal(selector, "summary"); return summary; },
    contains(target) { return target === this || target === summary; }
  };
  const document = {
    querySelectorAll(selector) {
      assert.equal(selector, ".application-account, .application-mobile-account");
      return [menu];
    },
    addEventListener(name, callback) { events[name] = callback; }
  };
  const window = {
    matchMedia(query) {
      assert.equal(query, "(prefers-reduced-motion: reduce)");
      return { matches: reducedMotion };
    },
    setTimeout(callback, delay) { timers.push({ callback, delay }); }
  };
  vm.runInNewContext(script, { document, window });
  return { events, menu, summary, timers };
}

test("Escape closes an open account menu and restores trigger focus", () => {
  const { events, menu, summary, timers } = setup();
  let stopped = false;
  events.keydown({ key: "Escape", stopPropagation() { stopped = true; } });
  assert.equal(menu.dataset.closing, "true");
  assert.equal(menu.open, true);
  assert.equal(timers.length, 1);
  assert.equal(timers[0].delay, 190);
  timers[0].callback();
  assert.equal(menu.open, false);
  assert.equal(menu.dataset.closing, undefined);
  assert.equal(summary.focusCalled, true);
  assert.equal(stopped, true);
});

test("outside pointer press closes the menu, inside press keeps it open", () => {
  const { events, menu, summary, timers } = setup();
  events.pointerdown({ target: summary });
  assert.equal(menu.open, true);
  events.pointerdown({ target: {} });
  assert.equal(menu.dataset.closing, "true");
  timers[0].callback();
  assert.equal(menu.open, false);
});

test("summary press reverses an open menu without submitting or navigating", () => {
  const { events, menu, summary, timers } = setup();
  let prevented = false;
  events.click({ target: summary, preventDefault() { prevented = true; } });
  assert.equal(prevented, true);
  assert.equal(menu.dataset.closing, "true");
  timers[0].callback();
  assert.equal(menu.open, false);
});

test("reduced motion closes the menu immediately", () => {
  const { events, menu, timers } = setup(true);
  events.pointerdown({ target: {} });
  assert.equal(menu.open, false);
  assert.equal(timers.length, 0);
});
