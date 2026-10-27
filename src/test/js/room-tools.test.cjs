const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const template = fs.readFileSync(path.resolve(__dirname,
  "../../main/resources/templates/room.html"), "utf8");
const roomScript = fs.readFileSync(path.resolve(__dirname,
  "../../main/resources/static/js/room-live.js"), "utf8");
const glossaryScript = fs.readFileSync(path.resolve(__dirname,
  "../../main/resources/static/js/room-glossary.js"), "utf8");

test("room details opens as a centered native modal with clear focus controls", () => {
  assert.match(template, /id="room-details-open"[^>]*aria-haspopup="dialog"[^>]*aria-controls="room-details"/);
  assert.match(template, /<dialog id="room-details" class="glossary-dialog room-details-dialog"/);
  assert.match(template, /id="room-details-close"[^>]*>Close<\/button>/);
  assert.match(roomScript, /roomDetails\.showModal\(\)/);
  assert.match(roomScript, /roomDetailsClose\?\.focus\(\)/);
  assert.match(roomScript, /roomDetailsOpen\?\.focus\(\)/);
});

test("room tools close one another and closed rooms cannot reopen room details", () => {
  assert.match(roomScript, /if \(glossarySection\?\.open\) glossarySection\.close\(\)/);
  assert.match(roomScript, /status\.textContent === "CLOSED" \|\| !roomDetails/);
  assert.match(roomScript, /value === "CLOSED" && roomDetails\?\.open\) roomDetails\.close\(\)/);
  assert.match(glossaryScript, /if \(details\?\.open\) details\.close\(\)/);
});
