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
  const classes = new Set();
  return { callbacks, children: [], dataset: {}, hidden: false, attributes: {},
    scrollHeight: 0, scrollTop: 0, clientHeight: 200,
    classList: { toggle(name, on) { on ? classes.add(name) : classes.delete(name); },
      contains(name) { return classes.has(name); } },
    addEventListener(name, fn) { callbacks[name] = fn; },
    setAttribute(name, value) { this.attributes[name] = value; },
    scrollTo(options) { this.scrollTop = options.top; }
  };
}

function setup(reducedMotion = false) {
  const ids = ['conversation-events', 'turn-empty', 'shared-text-draft', 'history-latest',
    'history-all', 'history-translated', 'history-quick', 'history-filter-status'];
  const nodes = Object.fromEntries(ids.map(id => [id, element()]));
  for (const id of ['history-all', 'history-translated', 'history-quick'])
    nodes[id].textContent = id === 'history-all' ? 'All messages'
      : id === 'history-translated' ? 'Translated' : 'Quick chat';
  nodes['shared-text-draft'].hidden = true;
  nodes['history-latest'].hidden = true;
  const window = { matchMedia: () => ({ matches: reducedMotion }) };
  vm.runInNewContext(script, { document: { getElementById: id => nodes[id] }, window });
  return { nodes, history: window.voicelinkHistory };
}

test('message filters retain one event stream and keep provisional drafts out of Quick chat', () => {
  const { nodes, history } = setup();
  const list = nodes['conversation-events'];
  list.children.push({ dataset: { messageKind: 'translated' }, hidden: false },
    { dataset: { messageKind: 'quick' }, hidden: false });
  history.refresh();
  assert.equal(list.children.every(row => !row.hidden), true);
  nodes['shared-text-draft'].hidden = false;
  nodes['history-quick'].callbacks.click();
  assert.equal(list.children[0].hidden, true);
  assert.equal(list.children[1].hidden, false);
  assert.equal(nodes['shared-text-draft'].classList.contains('history-filtered'), true);
  assert.equal(nodes['history-quick'].attributes['aria-pressed'], 'true');
  nodes['history-translated'].callbacks.click();
  assert.equal(list.children[0].hidden, false);
  assert.equal(list.children[1].hidden, true);
  assert.equal(nodes['shared-text-draft'].classList.contains('history-filtered'), true);
  assert.match(nodes['history-filter-status'].textContent, /Translated selected/);
  assert.equal(list.children.length, 2);
});

test('all filters remain selectable, show empty states, and keep selection through new arrivals', () => {
  const { nodes, history } = setup();
  const list = nodes['conversation-events'];
  nodes['history-translated'].callbacks.click();
  assert.equal(nodes['history-translated'].attributes['aria-pressed'], 'true');
  assert.equal(nodes['turn-empty'].hidden, false);
  assert.equal(nodes['turn-empty'].textContent, 'No translated messages yet.');
  list.children.push({ dataset: { messageKind: 'quick' }, hidden: false });
  history.changed(true, 'quick');
  assert.equal(nodes['history-translated'].attributes['aria-pressed'], 'true');
  assert.equal(list.children[0].hidden, true);
  list.children.push({ dataset: { messageKind: 'processing' }, hidden: false });
  history.changed(true, 'processing');
  assert.equal(list.children[1].hidden, true);
  list.children[1].dataset.messageKind = 'translated';
  history.changed(true, 'translated');
  assert.equal(list.children[1].hidden, false);
  assert.equal(nodes['turn-empty'].hidden, true);
  nodes['history-quick'].callbacks.click();
  assert.equal(list.children[0].hidden, false);
  assert.equal(list.children[1].hidden, true);
  nodes['history-all'].callbacks.click();
  assert.equal(list.children.every(row => !row.hidden), true);
  assert.equal(nodes['history-all'].attributes['aria-pressed'], 'true');
  assert.equal(list.children.length, 2);
  assert.match(html, /id="history-filter-status"[^>]*aria-live="polite"/);
  assert.doesNotMatch(script, /text-mode-(?:total|live|quick)|text-privacy-(?:private|shared)/);
});

test('provisional shared draft appears only in All messages', () => {
  const { nodes } = setup();
  nodes['shared-text-draft'].hidden = false;
  nodes['history-translated'].callbacks.click();
  assert.equal(nodes['shared-text-draft'].classList.contains('history-filtered'), true);
  assert.equal(nodes['turn-empty'].hidden, false);
  nodes['history-all'].callbacks.click();
  assert.equal(nodes['shared-text-draft'].classList.contains('history-filtered'), false);
  assert.equal(nodes['turn-empty'].hidden, true);
});

test('long history follows only near bottom and offers a keyboard-accessible jump', () => {
  const { nodes, history } = setup();
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
  history.changed(readingEarlier, 'quick');
  assert.equal(list.scrollTop, 100);
  assert.equal(nodes['history-latest'].hidden, false);
  nodes['history-latest'].callbacks.click();
  assert.equal(list.scrollTop, 1200);
  assert.equal(nodes['history-latest'].hidden, true);
  assert.match(html, /id="history-latest"[^>]*type="button"/);
  assert.match(css, /\.turn-list \{[^}]*max-height: min\(52dvh, 560px\); overflow-y: auto/);
  assert.match(css, /\.history-filters__button:focus-visible/);
  assert.match(css, /overflow-wrap: anywhere/);
});

test('jump to latest respects reduced motion', () => {
  const { nodes } = setup(true);
  const list = nodes['conversation-events'];
  let behavior;
  list.scrollTo = options => { behavior = options.behavior; list.scrollTop = options.top; };
  list.scrollHeight = 900;
  nodes['history-latest'].callbacks.click();
  assert.equal(behavior, 'auto');
  assert.equal(list.scrollTop, 900);
});
