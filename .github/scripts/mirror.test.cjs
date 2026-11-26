'use strict';

// Behavioural tests for the phone mirror page script (mirror.js) on a fake page:
// guaranteed mute, hidden playback, stale/foreign content, ads, seek hygiene,
// user-gesture detection and restoring the page's own inline styles.

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('app/src/main/assets/mirror.js', 'utf8');

class FakeStyle {
  constructor(initial = {}) { this.map = new Map(Object.entries(initial).map(([k, v]) => [k, { value: v, priority: '' }])); }
  getPropertyValue(n) { return this.map.has(n) ? this.map.get(n).value : ''; }
  getPropertyPriority(n) { return this.map.has(n) ? this.map.get(n).priority : ''; }
  setProperty(n, v, p = '') { this.map.set(n, { value: v, priority: p }); }
  removeProperty(n) { this.map.delete(n); }
}

function page(options = {}) {
  let clock = 1_000_000;
  const timers = new Map();
  let nextTimer = 1;
  const bridgeCalls = [];
  const listeners = new Map();
  const classes = new Set();
  const inserted = new Map();
  let adShowing = false;
  let search = options.search || '?v=AAAAAAAAAAA';

  class FakeMedia {
    constructor() {
      this._muted = false; this._volume = 1;
      this.tagName = 'VIDEO'; this.paused = true; this.ended = false; this.seeking = false;
      this.currentTime = 0; this.playbackRate = 1; this.duration = 300; this.readyState = 4;
      this.playCalls = 0; this.pauseCalls = 0; this.seekWrites = 0; this._time = 0;
      this.inPlayer = true;
      this.parentElement = null;
    }
    get muted() { return this._muted; }
    set muted(v) { this._muted = !!v; }
    get volume() { return this._volume; }
    set volume(v) { this._volume = v; }
    play() { this.playCalls++; this.paused = false; return Promise.resolve(); }
    pause() { this.pauseCalls++; this.paused = true; }
    closest() { return this.inPlayer ? this : null; }
  }
  const video = new FakeMedia();
  let seekWrites = 0;
  let timeValue = 0;
  Object.defineProperty(video, 'currentTime', {
    get() { return timeValue; },
    set(v) { timeValue = v; seekWrites++; },
  });

  const transformedParent = {
    style: new FakeStyle(options.parentStyle || {}),
    parentElement: null,
  };
  video.parentElement = transformedParent;
  const documentElement = {
    classList: {
      toggle(name, on) { if (on) classes.add(name); else classes.delete(name); },
      contains: (n) => classes.has(n),
    },
    appendChild(el) { if (el.id) inserted.set(el.id, el); },
  };
  transformedParent.parentElement = documentElement;

  const player = { classList: { contains: (n) => n === 'ad-showing' && adShowing }, setInternalSize() {} };
  const qualityCalls = [];
  player.setPlaybackQualityRange = (a, b) => qualityCalls.push([a, b]);

  const document = {
    head: documentElement,
    documentElement,
    fullscreenElement: null,
    addEventListener(type, fn) {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push(fn);
    },
    querySelector(sel) { return /video/.test(sel) ? video : null; },
    getElementById(id) { return id === 'movie_player' ? player : inserted.get(id) || null; },
    createElement() { return { id: '', textContent: '' }; },
  };

  const sandbox = {
    HTMLMediaElement: FakeMedia,
    document,
    location: { get search() { return search; }, pathname: '/watch' },
    URLSearchParams,
    Promise,
    Event: class { constructor(t) { this.type = t; } },
    Date: { now: () => clock },
    setTimeout(fn, ms) { const id = nextTimer++; timers.set(id, { fn, ms }); return id; },
    clearTimeout(id) { timers.delete(id); },
    getComputedStyle: () => ({ transform: 'matrix(1, 0, 0, 1, 0, 10)', contain: 'none', filter: 'none', willChange: '' }),
    AAYMirror: { control(action, value, token) { bridgeCalls.push({ action, value, token }); }, hidden: () => !!options.hidden },
    __AAYM_TOKEN: 'tok',
    addEventListener() {},
    dispatchEvent() { sandbox.resizeEvents++; },
    resizeEvents: 0,
  };
  sandbox.window = sandbox;
  sandbox.self = sandbox;
  sandbox.top = options.frame ? {} : sandbox;
  vm.createContext(sandbox);
  vm.runInContext(source, sandbox);

  const fire = (type, target, extra = {}) => {
    (listeners.get(type) || []).forEach((fn) => fn({ type, target, isTrusted: true, touches: [], changedTouches: [], ...extra }));
  };
  return {
    M: sandbox.__aayMirror, video, bridgeCalls, player, qualityCalls, transformedParent, timers, sandbox, fire,
    advance(ms) { clock += ms; },
    setAd(on) { adShowing = on; },
    setSearch(s) { search = s; },
    seekWrites: () => seekWrites,
    setTime(v) { timeValue = v; },
  };
}

test('the mirror can never become audible, however the page sets volume', () => {
  const p = page();
  p.video.muted = false;
  p.video.volume = 1;
  assert.equal(p.video.muted, true);
  assert.equal(p.video.volume, 0);
  p.video._muted = false; // the page tries a loaded-media unmute
  p.fire('playing', p.video);
  assert.equal(p.video.muted, true);
});

test('play() silences the element first and stays blocked while hidden', async () => {
  const p = page();
  p.video._muted = false;
  p.video._volume = 1;
  await p.video.play();
  assert.equal(p.video.muted, true);
  assert.equal(p.video.volume, 0);
  const before = p.video.playCalls;
  p.video.pause();
  p.M.stop();
  await p.video.play(); // YouTube tries to resume hidden playback
  assert.equal(p.video.playCalls, before, 'playback must not restart while hidden');
  assert.equal(p.video.paused, true);
});

test('a hidden page that starts playing on its own is paused again', () => {
  const p = page();
  p.M.stop();
  p.video.paused = false;
  p.fire('play', p.video);
  assert.equal(p.video.paused, true);
});

test('sync resumes playback after hidden and corrects only towards the same video', () => {
  const p = page();
  p.M.stop();
  assert.equal(p.M.sync(5000, true, 1, 'BBBBBBBBBBB'), 'mismatch');
  assert.equal(p.M.stopped, true, 'a foreign sync must not wake a hidden player');
  assert.equal(p.seekWrites(), 0);
  p.setTime(5);
  const r = p.M.sync(5000, true, 1, 'AAAAAAAAAAA');
  assert.equal(r, '0|ok');
  assert.equal(p.M.stopped, false);
  assert.equal(p.video.paused, false);
});

test('advertisement on the mirror is never synchronised to the video position', () => {
  const p = page();
  p.setAd(true);
  p.setTime(2);
  assert.equal(p.M.sync(90_000, true, 1, 'AAAAAAAAAAA'), 'ad');
  assert.equal(p.seekWrites(), 0);
  assert.equal(p.video.playbackRate, 1);
});

test('large drift seeks once; no stacked seeks while buffering or seeking', () => {
  const p = page();
  p.setTime(0);
  const w0 = p.seekWrites();
  assert.equal(p.M.sync(30_000, true, 1, 'AAAAAAAAAAA'), '-30000|seek');
  assert.equal(p.seekWrites(), w0 + 1);
  p.setTime(0); // still buffering at the old position
  p.advance(1500);
  assert.match(p.M.sync(31_000, true, 1, 'AAAAAAAAAAA'), /\|wait$/);
  assert.equal(p.seekWrites(), w0 + 2 - 1, 'second seek must be suppressed (setTime counts as one write)');
  p.advance(4000);
  p.video.seeking = true;
  assert.match(p.M.sync(35_000, true, 1, 'AAAAAAAAAAA'), /\|wait$/);
});

test('small drift nudges the rate, keeps the selected speed, and settles', () => {
  const p = page();
  p.setTime(10.8); // 800 ms ahead of 10 s
  assert.equal(p.M.sync(10_000, true, 1.5, 'AAAAAAAAAAA'), '800|nudge');
  assert.ok(Math.abs(p.video.playbackRate - 1.5 * 0.95) < 1e-9);
  p.setTime(10.0);
  assert.equal(p.M.sync(10_000, true, 1.5, 'AAAAAAAAAAA'), '0|ok');
  assert.equal(p.video.playbackRate, 1.5, 'the user speed is restored once in step');
});

test('changing video resets synchronisation state', () => {
  const p = page();
  p.setTime(0);
  p.M.sync(30_000, true, 1, 'AAAAAAAAAAA');
  assert.ok(p.M.lastSeekAt > 0);
  p.setSearch('?v=CCCCCCCCCCC');
  p.M.sync(1000, true, 1, 'CCCCCCCCCCC');
  assert.equal(p.M.lastId, 'CCCCCCCCCCC');
});

test('only a trusted tap inside the player counts as the user\'s control action', () => {
  const p = page();
  const outside = { tagName: 'DIV', closest: () => null };
  p.fire('click', outside);
  p.video.paused = false;
  p.fire('pause', p.video);
  assert.equal(p.bridgeCalls.length, 0, 'a tap outside the player (feed, scroll) is not a control');

  const inside = { tagName: 'DIV', closest: (sel) => (/progress|next|prev/i.test(sel) ? null : inside) };
  p.fire('click', inside, { isTrusted: false });
  p.fire('pause', p.video);
  assert.equal(p.bridgeCalls.length, 0, 'an untrusted (YouTube generated) event is not a control');

  p.fire('click', inside);
  p.fire('pause', p.video);
  assert.deepEqual(p.bridgeCalls.map((c) => c.action), ['pause']);
  assert.equal(p.bridgeCalls[0].token, 'tok');
});

test('a scroll that ends on the player is not a control tap', () => {
  const p = page();
  const inside = { tagName: 'DIV', closest: (sel) => (/progress|next|prev/i.test(sel) ? null : inside) };
  p.fire('touchstart', inside, { touches: [{ clientX: 10, clientY: 10 }] });
  p.fire('touchend', inside, { changedTouches: [{ clientX: 12, clientY: 200 }] });
  p.fire('pause', p.video);
  const pauses = () => p.bridgeCalls.filter((c) => c.action === 'pause').length;
  assert.equal(pauses(), 0);
  p.fire('touchstart', inside, { touches: [{ clientX: 10, clientY: 10 }] });
  p.fire('touchend', inside, { changedTouches: [{ clientX: 11, clientY: 12 }] });
  p.fire('pause', p.video);
  assert.equal(pauses(), 1);
});

test('the engine\'s own corrections are never reported back as user actions', () => {
  const p = page();
  const inside = { tagName: 'DIV', closest: (sel) => (/progress|next|prev/i.test(sel) ? null : inside) };
  p.fire('click', inside);
  p.video.paused = false;
  p.setTime(10);
  p.advance(2000); // gesture window for the sync hold has passed
  p.M.sync(10_000, false, 1, 'AAAAAAAAAAA'); // engine says paused: the page pauses itself
  p.fire('pause', p.video);
  assert.equal(p.bridgeCalls.length, 0);
});

test('leaving video-only mode restores the page\'s own inline styles', () => {
  const p = page({ parentStyle: { transform: 'translateY(0px)', filter: 'blur(0px)' } });
  p.M.layout(true);
  assert.equal(p.transformedParent.style.getPropertyValue('transform'), 'none');
  assert.equal(p.transformedParent.style.getPropertyPriority('transform'), 'important');
  p.M.layout(false);
  assert.equal(p.transformedParent.style.getPropertyValue('transform'), 'translateY(0px)');
  assert.equal(p.transformedParent.style.getPropertyPriority('transform'), '');
  assert.equal(p.transformedParent.style.getPropertyValue('filter'), 'blur(0px)');
  assert.equal(p.transformedParent.style.getPropertyValue('contain'), '', 'properties the page never set are removed');
});

test('rapid mode changes cancel the previous delayed layout passes', () => {
  const p = page();
  p.M.layout(true);
  const first = [...p.timers.keys()];
  assert.equal(first.length, 3);
  p.M.layout(false);
  first.forEach((id) => assert.equal(p.timers.has(id), false, 'obsolete delayed pass still scheduled'));
  assert.equal(p.timers.size, 3, 'only the newest mode\'s passes remain');
});

test('the phone player decodes a size-appropriate quality', () => {
  const p = page();
  p.M.layout(true);
  assert.deepEqual(p.qualityCalls.at(-1), ['medium', 'medium']);
  p.M.layout(false);
  assert.deepEqual(p.qualityCalls.at(-1), ['large', 'large']);
  const n = p.qualityCalls.length;
  p.M.layout(false);
  assert.equal(p.qualityCalls.length, n, 'no repeated quality changes');
});

const tapTarget = () => { const t = { tagName: 'DIV', closest: (sel) => (/progress|next|prev/i.test(sel) ? null : t) }; return t; };
const barTarget = () => { const t = { tagName: 'DIV', closest: (sel) => (/next|prev/i.test(sel) ? null : t) }; return t; };

test('a document loaded while hidden starts stopped and cannot autoplay', async () => {
  const p = page({ hidden: true });
  assert.equal(p.M.stopped, true);
  await p.video.play();
  assert.equal(p.video.playCalls, 0);
});

test('audio isolation also covers embedded frames, before anything else runs', async () => {
  const p = page({ frame: true });
  assert.equal(p.sandbox.__aayMirror, undefined, 'a frame gets no controls, only the mute lock');
  p.video.volume = 1;
  p.video.muted = false;
  assert.equal(p.video.muted, true);
  assert.equal(p.video.volume, 0);
  await p.video.play();
  assert.equal(p.video.muted, true);
});

test('one gesture authorises one action only', () => {
  const p = page();
  p.fire('click', tapTarget());
  p.fire('pause', p.video);
  p.fire('play', p.video);
  assert.deepEqual(p.bridgeCalls.map((c) => c.action), ['pause']);
});

test('a tap cannot authorise a seek and a seek-bar drag cannot authorise play or pause', () => {
  const p = page();
  p.fire('click', tapTarget());
  p.fire('seeked', p.video);
  assert.equal(p.bridgeCalls.length, 0);
  p.fire('click', barTarget());
  p.fire('pause', p.video); // YouTube pauses while scrubbing
  p.fire('play', p.video);
  assert.equal(p.bridgeCalls.length, 0);
  p.fire('seeked', p.video);
  assert.deepEqual(p.bridgeCalls.map((c) => c.action), ['seek']);
});

test('a seek made by synchronisation is never reported as the user\'s, even if it lands late', () => {
  const p = page();
  p.fire('click', barTarget());
  p.advance(1600); // the user hold is over, the engine moved on
  p.setTime(0);
  assert.match(p.M.sync(30_000, true, 1, 'AAAAAAAAAAA', false, ''), /seek$/);
  p.advance(3500); // the seeked event arrives long after the call
  p.fire('seeked', p.video);
  assert.equal(p.bridgeCalls.length, 0);
});

test('native Next and Previous are forwarded explicitly', () => {
  const p = page();
  const next = { tagName: 'BUTTON', closest: (sel) => (/ytp-next-button/.test(sel) || /html5-video-player/.test(sel) ? next : null) };
  const prev = { tagName: 'BUTTON', closest: (sel) => (/ytp-prev-button/.test(sel) ? prev : /html5-video-player/.test(sel) ? prev : null) };
  p.fire('click', next);
  p.fire('click', prev);
  assert.deepEqual(p.bridgeCalls.map((c) => c.action), ['next', 'prev']);
});

test('no seek while the engine is buffering or seeking', () => {
  const p = page();
  p.setTime(0);
  const w = p.seekWrites();
  assert.match(p.M.sync(30_000, true, 1, 'AAAAAAAAAAA', true, ''), /wait$/);
  assert.equal(p.seekWrites(), w);
});

test('quality drops under load and is restored when the cap is lifted, without churn', () => {
  const p = page();
  p.M.layout(false);
  p.setTime(10);
  p.M.sync(10_000, true, 1, 'AAAAAAAAAAA', false, 'small');
  assert.deepEqual(p.qualityCalls.at(-1), ['small', 'small']);
  const n = p.qualityCalls.length;
  p.M.sync(10_000, true, 1, 'AAAAAAAAAAA', false, 'small');
  assert.equal(p.qualityCalls.length, n);
  p.M.sync(10_000, true, 1, 'AAAAAAAAAAA', false, '');
  assert.deepEqual(p.qualityCalls.at(-1), ['large', 'large']);
});
