const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../../main/resources');
const script = fs.readFileSync(path.join(root, 'static/js/room-history.js'), 'utf8');
const css = fs.readFileSync(path.join(root, 'static/css/voicelink.css'), 'utf8');
const html = fs.readFileSync(path.join(root, 'templates/room.html'), 'utf8');

function element() {
  const callbacks = {};
  return { callbacks, children: [], dataset: {}, hidden: false, attributes: {},
    scrollHeight: 0, scrollTop: 0, clientHeight: 200,
    classList: { toggle() {}, contains() { return false; } },
    addEventListener(name, fn) { callbacks[name] = fn; },
    setAttribute(name, value) { this.attributes[name] = value; },
    scrollTo(options) { this.scrollTop = options.top; }
  };
}

function setup(reducedMotion = false) {
  const ids = ['conversation-events', 'turn-empty', 'history-latest',
    'history-translated', 'history-quick', 'history-filter-status'];
  const nodes = Object.fromEntries(ids.map(id => [id, element()]));
  nodes['history-translated'].textContent = 'Translated';
  nodes['history-quick'].textContent = 'Quick chat';
  nodes['history-latest'].hidden = true;
  const window = { matchMedia: () => ({ matches: reducedMotion }) };
  vm.runInNewContext(script, { document: { getElementById: id => nodes[id] }, window });
  return { nodes, history: window.voicelinkHistory };
}

test('Translated is default; only completed translated messages are shown', () => {
  const { nodes, history } = setup();
  const list = nodes['conversation-events'];
  list.children.push({ dataset: { messageKind: 'translated' }, hidden: false },
    { dataset: { messageKind: 'quick' }, hidden: false },
    { dataset: { messageKind: 'processing' }, hidden: false });
  history.refresh();
  assert.equal(nodes['history-translated'].attributes['aria-pressed'], 'true');
  assert.equal(list.children[0].hidden, false);
  assert.equal(list.children[1].hidden, true);
  assert.equal(list.children[2].hidden, true);
  assert.equal(nodes['turn-empty'].hidden, true);
  nodes['history-quick'].callbacks.click();
  assert.equal(nodes['history-quick'].attributes['aria-pressed'], 'true');
  assert.equal(list.children[0].hidden, true);
  assert.equal(list.children[1].hidden, false);
  assert.match(nodes['history-filter-status'].textContent, /Quick chat selected/);
});

test('both filters remain selectable when empty and keep selection on message arrivals', () => {
  const { nodes, history } = setup();
  const list = nodes['conversation-events'];
  assert.equal(nodes['turn-empty'].textContent, 'No translated messages yet.');
  nodes['history-quick'].callbacks.click();
  assert.equal(nodes['turn-empty'].textContent, 'No quick chat messages yet.');
  list.children.push({ dataset: { messageKind: 'translated' }, hidden: false });
  history.changed(true, 'translated');
  assert.equal(nodes['history-quick'].attributes['aria-pressed'], 'true');
  assert.equal(list.children[0].hidden, true);
  list.children.push({ dataset: { messageKind: 'quick' }, hidden: false });
  history.changed(true, 'quick');
  assert.equal(list.children[1].hidden, false);
  nodes['history-translated'].callbacks.click();
  assert.equal(list.children[0].hidden, false);
  assert.equal(list.children[1].hidden, true);
  assert.doesNotMatch(html, /id="history-all"|>All messages</);
});

test('long history follows only near bottom and exposes the true end with reduced-motion support', () => {
  const { nodes, history } = setup(true);
  const list = nodes['conversation-events'];
  list.scrollHeight = 800;
  list.scrollTop = 600;
  const near = history.beforeAppend();
  list.scrollHeight = 1000;
  history.changed(near, 'translated');
  assert.equal(list.scrollTop, 1000);
  assert.equal(nodes['history-latest'].hidden, true);
  list.scrollTop = 100;
  const readingEarlier = history.beforeAppend();
  list.scrollHeight = 1200;
  history.changed(readingEarlier, 'translated');
  assert.equal(list.scrollTop, 100);
  assert.equal(nodes['history-latest'].hidden, false);
  let behavior;
  list.scrollTo = options => { behavior = options.behavior; list.scrollTop = options.top; };
  nodes['history-latest'].callbacks.click();
  assert.equal(behavior, 'auto');
  assert.equal(list.scrollTop, 1200);
  assert.match(css, /padding: 0 4px 24px 0; scroll-padding-block: 12px 24px/);
  assert.match(css, /@media \(max-width: 520px\)[\s\S]*\.turn-list \{ max-height/);
});
