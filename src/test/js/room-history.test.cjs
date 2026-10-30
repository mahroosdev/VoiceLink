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
  return { callbacks, children: [], dataset: {}, hidden: false, attributes: {}, textContent: '',
    scrollHeight: 0, scrollTop: 0, clientHeight: 200,
    addEventListener(name, fn) { callbacks[name] = fn; },
    setAttribute(name, value) { this.attributes[name] = value; },
    scrollTo(options) { this.scrollTop = options.top; } };
}

function setup(storage = new Map(), reducedMotion = false) {
  const ids = ['room-page', 'conversation-events', 'turn-empty', 'history-latest',
    'history-translated', 'history-quick', 'history-translated-unread', 'history-quick-unread',
    'history-filter-status'];
  const nodes = Object.fromEntries(ids.map(id => [id, element()]));
  nodes['room-page'].dataset.roomId = 'room-1';
  nodes['history-translated'].textContent = 'Translated';
  nodes['history-quick'].textContent = 'Quick chat';
  nodes['history-latest'].hidden = true;
  const window = { sessionStorage: { getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value) },
    matchMedia: () => ({ matches: reducedMotion }) };
  vm.runInNewContext(script, { document: { getElementById: id => nodes[id] }, window });
  return { nodes, history: window.voicelinkHistory, storage };
}

test('inactive stream arrival shows an accessible unread indicator; viewing clears only that stream', () => {
  const { nodes, history } = setup();
  const list = nodes['conversation-events'];
  const translated = { dataset: { messageKind: 'translated' }, hidden: false };
  const quick = { dataset: { messageKind: 'quick' }, hidden: false };
  list.children.push(translated, quick);
  history.refresh();
  assert.equal(nodes['history-translated'].attributes['aria-pressed'], 'true');
  assert.equal(translated.hidden, false);
  assert.equal(quick.hidden, true);
  history.changed(true, 'quick');
  assert.equal(nodes['history-quick-unread'].hidden, false);
  assert.match(nodes['history-quick'].attributes['aria-label'], /unread messages/);
  history.changed(true, 'translated', true);
  assert.equal(nodes['history-quick-unread'].hidden, false);
  nodes['history-quick'].callbacks.click();
  assert.equal(nodes['history-quick-unread'].hidden, true);
  assert.equal(quick.hidden, false);
  assert.equal(translated.hidden, true);
});

test('unread state survives refresh in this browser session; active messages and live revisions do not add unread', () => {
  const storage = new Map();
  let { nodes, history } = setup(storage);
  history.changed(true, 'quick');
  assert.equal(nodes['history-quick-unread'].hidden, false);
  ({ nodes, history } = setup(storage));
  assert.equal(nodes['history-quick-unread'].hidden, false);
  history.changed(true, 'translated', true);
  history.changed(true, 'quick', false, true);
  assert.equal(nodes['history-translated-unread'].hidden, true);
  assert.equal(nodes['history-quick-unread'].hidden, false);
  nodes['history-translated'].callbacks.click();
  assert.equal(nodes['history-translated-unread'].hidden, true);
  assert.equal(nodes['history-quick-unread'].hidden, false);
});

test('translated default, empty messages and latest-message scrolling remain usable', () => {
  const { nodes, history } = setup(new Map(), true);
  const list = nodes['conversation-events'];
  assert.equal(nodes['turn-empty'].textContent, 'No translated messages yet.');
  nodes['history-quick'].callbacks.click();
  assert.equal(nodes['turn-empty'].textContent, 'No quick chat messages yet.');
  list.scrollHeight = 800;
  list.scrollTop = 100;
  const readingEarlier = history.beforeAppend();
  list.scrollHeight = 1200;
  history.changed(readingEarlier, 'quick');
  assert.equal(nodes['history-latest'].hidden, false);
  let behavior;
  list.scrollTo = options => { behavior = options.behavior; list.scrollTop = options.top; };
  nodes['history-latest'].callbacks.click();
  assert.equal(behavior, 'auto');
  assert.equal(list.scrollTop, 1200);
  assert.match(html, /id="history-translated-unread"/);
  assert.match(html, /id="history-quick-unread"/);
  assert.match(css, /@media \(max-width: 520px\)[\s\S]*\.turn-list \{ max-height/);
});
