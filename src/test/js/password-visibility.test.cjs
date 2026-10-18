const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const script = fs.readFileSync(path.resolve(__dirname,
  "../../main/resources/static/js/password-visibility.js"), "utf8");

for (const [page, field] of [
  ["login", "password"],
  ["register", "password"],
  ["delete-account", "currentPassword"],
  ["reset-password", "newPassword"],
  ["reset-password", "confirmPassword"]
]) {
  test(`${page} ${field} switches label, icon state, and input type without changing the value`, () => {
    const template = fs.readFileSync(path.resolve(__dirname,
      `../../main/resources/templates/${page}.html`), "utf8");
    assert.match(template, new RegExp(`aria-controls="${field}"[^>]*data-password-toggle data-visible="false"`));
    const input = { type: "password", value: "example-secret" };
    const attributes = { "aria-controls": field, "aria-label": "Show password",
      "aria-pressed": "false" };
    const button = {
      type: "button",
      title: "Show password",
      dataset: { visible: "false" },
      getAttribute(name) { return attributes[name]; },
      setAttribute(name, value) { attributes[name] = value; },
      addEventListener(name, callback) { assert.equal(name, "click"); this.click = callback; }
    };
    const document = {
      querySelectorAll(selector) { assert.equal(selector, "[data-password-toggle]"); return [button]; },
      getElementById(id) { assert.equal(id, field); return input; }
    };
    vm.runInNewContext(script, { document });
    assert.equal(input.type, "password");
    assert.equal(attributes["aria-label"], "Show password");

    button.click();
    assert.equal(input.type, "text");
    assert.equal(attributes["aria-label"], "Hide password");
    assert.equal(attributes["aria-pressed"], "true");
    assert.equal(button.title, "Hide password");
    assert.equal(button.dataset.visible, "true");
    assert.equal(input.value, "example-secret");

    button.click();
    assert.equal(input.type, "password");
    assert.equal(attributes["aria-label"], "Show password");
    assert.equal(attributes["aria-pressed"], "false");
    assert.equal(button.title, "Show password");
    assert.equal(button.dataset.visible, "false");
    assert.equal(input.value, "example-secret");
  });
}
