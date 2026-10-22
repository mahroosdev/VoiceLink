const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const root = path.resolve(__dirname, "../../main/resources");
const html = fs.readFileSync(path.join(root, "templates/rooms.html"), "utf8");
const script = fs.readFileSync(path.join(root, "static/js/room-create.js"), "utf8");

test("Premium room creation exposes an accessible immutable Cost Saver choice", () => {
  assert.match(html, /id="premium-cost-saver"/);
  assert.match(html, /th:field="\*\{premiumCostSaver\}" value="ON"/);
  assert.match(html, /th:field="\*\{premiumCostSaver\}" value="OFF"/);
  assert.match(html, /ON uses a longer paid preview interval/);
  assert.match(html, /Translation and voice models stay the same/);
  assert.doesNotMatch(html, /cost-saving percentage|billing balance|API key/);
  assert.doesNotMatch(script, /innerHTML|localStorage|sessionStorage/);
});

test("Cost Saver controls appear only when Premium is selected", () => {
  const costSaver = { hidden: false };
  const standard = { checked: true, value: "STANDARD", callbacks: {},
    addEventListener(name, fn) { this.callbacks[name] = fn; } };
  const premium = { checked: false, value: "PREMIUM", callbacks: {},
    addEventListener(name, fn) { this.callbacks[name] = fn; } };
  const document = {
    getElementById(id) { return id === "premium-cost-saver" ? costSaver : null; },
    querySelectorAll(selector) { return selector === 'input[name="profile"]' ? [standard, premium] : []; }
  };
  vm.runInNewContext(script, { document });
  assert.equal(costSaver.hidden, true);
  standard.checked = false;
  premium.checked = true;
  premium.callbacks.change();
  assert.equal(costSaver.hidden, false);
});

test("creator language choice explains the server-defined complementary direction", () => {
  assert.match(html, /Which language will you speak\?/);
  assert.match(html, /Creator speaks:/);
  assert.match(html, /Invited participant speaks:/);
  assert.doesNotMatch(html, /th:field="\*\{listening\}"/);
  const creatorNodes = [{ textContent: "" }, { textContent: "" }];
  const participantNodes = [{ textContent: "" }, { textContent: "" }];
  const summary = {
    hidden: true,
    querySelectorAll(selector) {
      return selector === "[data-creator-language]" ? creatorNodes : participantNodes;
    }
  };
  const speaking = { value: "en", callbacks: {}, addEventListener(name, fn) { this.callbacks[name] = fn; } };
  const document = {
    getElementById(id) {
      if (id === "speaking") return speaking;
      if (id === "direction-preview") return summary;
      return null;
    },
    querySelectorAll() { return []; }
  };
  vm.runInNewContext(script, { document });
  assert.equal(summary.hidden, false);
  assert.deepEqual(creatorNodes.map(node => node.textContent), ["English", "English"]);
  assert.deepEqual(participantNodes.map(node => node.textContent), ["Tamil", "Tamil"]);
  speaking.value = "ta";
  speaking.callbacks.change();
  assert.deepEqual(creatorNodes.map(node => node.textContent), ["Tamil", "Tamil"]);
  assert.deepEqual(participantNodes.map(node => node.textContent), ["English", "English"]);
});
