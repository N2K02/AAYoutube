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
  const intents = [];
  const navigations = [];
  const windowListeners = new Map();
  const posted = [];
  let pathname = options.pathname || '/watch';
  let videoData = options.videoData || null;
  const listeners = new Map();
  const classes = new Set();
  const inserted = new Map();
  let adShowing = false;
  let search = options.search !== undefined ? options.search : '?v=AAAAAAAAAAA';

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

  const qualityCalls = [];
  const makePlayer = () => ({
    classList: { contains: (n) => n === 'ad-showing' && adShowing },
    setInternalSize() {},
    setPlaybackQualityRange: (a, b) => qualityCalls.push([a, b]),
    getVideoData: () => videoData,
  });
  let player = makePlayer();

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
    querySelectorAll() { return [video]; },
    createElement() { return { id: '', textContent: '' }; },
  };

  const sandbox = {
    HTMLMediaElement: FakeMedia,
    document,
    location: { get search() { return search; }, get pathname() { return pathname; }, assign: (url) => navigations.push(url) },
    URLSearchParams,
    Promise,
    Event: class { constructor(t) { this.type = t; } },
    Date: { now: () => clock },
    setTimeout(fn, ms) { const id = nextTimer++; timers.set(id, { fn, ms }); return id; },
    clearTimeout(id) { timers.delete(id); },
    getComputedStyle: () => ({ transform: 'matrix(1, 0, 0, 1, 0, 10)', contain: 'none', filter: 'none', willChange: '' }),
    AAYMirror: {
      control(action, value, token, doc, seq, vid) { bridgeCalls.push({ action, value, token, doc, seq, vid }); },
      hidden: () => !!options.hidden,
      videoOnly: () => !!options.videoOnly,
      initialCap: () => options.initialCap || '',
      open: () => 7,
      intent(url, token, doc) { intents.push({ url, token, doc }); },
    },
    __AAYM_TOKEN: 'tok',
    addEventListener(type, fn) { windowListeners.set(type, fn); },
    frames: Object.assign([{ postMessage: (m) => posted.push(m) }], {}),
    parent: options.parent || null,
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
    M: sandbox.__aayMirror, video, bridgeCalls, intents, posted, windowListeners, qualityCalls,
    swapPlayer() { player = makePlayer(); },
    setPath(v) { pathname = v; },
    setVideoData(v) { videoData = v; }, transformedParent, timers, sandbox, fire,
    advance(ms) { clock += ms; },
    setAd(on) { adShowing = on; },
    setSearch(s) { search = s; },
    seekWrites: () => seekWrites,
    setTime(v) { timeValue = v; },
  };
}


const parts = (r) => { const [d, a, h, ph, sec, t, l] = r.split('|'); return { drift: +d, action: a, height: +h, phase: ph, phoneMs: +sec, target: +t, landed: +l }; };
/** Bring the page into the stable phase at [posMs]. */
const aligned = (p, posMs, rate = 1) => { p.setTime(posMs / 1000); const r = parts(p.M.sync(posMs, true, rate, 'AAAAAAAAAAA')); assert.equal(r.phase, 'stable'); return r; };

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
  const r = parts(p.M.sync(5000, true, 1, 'AAAAAAAAAAA'));
  assert.equal(r.action, 'ok');
  assert.equal(r.phase, 'stable');
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

test('large drift seeks once and verifies the seek before any other correction', () => {
  const p = page();
  aligned(p, 0);
  const w0 = p.seekWrites();
  p.advance(4000);
  const r = parts(p.M.sync(30_000, true, 1, 'AAAAAAAAAAA'));
  assert.equal(r.action, 'seek');
  assert.equal(r.phase, 'seeking');
  assert.equal(p.seekWrites(), w0 + 1);
  p.advance(1500);
  assert.equal(parts(p.M.sync(31_500, true, 1, 'AAAAAAAAAAA')).action, 'wait', 'unverified seek: no new correction');
  assert.equal(p.seekWrites(), w0 + 1);
  p.setTime(30.25);
  p.fire('seeked', p.video);
  p.setTime(31.5);
  const v = parts(p.M.sync(31_500, true, 1, 'AAAAAAAAAAA'));
  assert.equal(v.target, 30_250);
  assert.equal(v.landed, 30_250);
  assert.equal(v.phase, 'stable');
  p.video.seeking = true;
  assert.equal(parts(p.M.sync(40_000, true, 1, 'AAAAAAAAAAA')).action, 'wait');
});
test('small drift nudges the rate, keeps the selected speed, and settles', () => {
  const p = page();
  aligned(p, 10_000, 1.5);
  p.setTime(10.8); // 800 ms ahead
  assert.equal(parts(p.M.sync(10_000, true, 1.5, 'AAAAAAAAAAA')).action, 'nudge');
  assert.ok(Math.abs(p.video.playbackRate - 1.5 * 0.94) < 1e-9);
  p.setTime(10.0);
  assert.equal(parts(p.M.sync(10_000, true, 1.5, 'AAAAAAAAAAA')).action, 'ok');
  assert.equal(p.video.playbackRate, 1.5, 'the user speed is restored once within 300 ms');
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
  const before = new Set(p.timers.keys());
  p.M.layout(true);
  const first = [...p.timers.keys()].filter((id) => !before.has(id));
  assert.equal(first.length, 3);
  p.M.layout(false);
  first.forEach((id) => assert.equal(p.timers.has(id), false, 'obsolete delayed pass still scheduled'));
  assert.equal([...p.timers.keys()].filter((id) => !before.has(id)).length, 3, 'only the newest mode\'s passes remain');
});
test('the phone player decodes a size-appropriate quality', () => {
  const p = page();
  aligned(p, 0);
  p.M.layout(true);
  assert.deepEqual(p.qualityCalls.at(-1), ['medium', 'medium']);
  p.advance(16_000);
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
  assert.match(p.M.sync(30_000, true, 1, 'AAAAAAAAAAA', false, ''), /\|seek\|/);
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

test('no steady-state seek while the engine is buffering or seeking', () => {
  const p = page();
  aligned(p, 0);
  p.advance(4000);
  p.setTime(0);
  const w = p.seekWrites();
  assert.match(p.M.sync(30_000, true, 1, 'AAAAAAAAAAA', true, ''), /\|wait\|/);
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

test('commands carry the document, an increasing sequence and the video they saw', () => {
  const p = page();
  const t = tapTarget();
  p.fire('click', t); p.fire('pause', p.video);
  p.advance(2000);
  p.fire('click', t); p.fire('play', p.video);
  assert.deepEqual(p.bridgeCalls.map((c) => [c.doc, c.seq, c.vid]), [[7, 1, 'AAAAAAAAAAA'], [7, 2, 'AAAAAAAAAAA']]);
});

test('a touch followed by its click authorises one action and forwards Next once', () => {
  const p = page();
  const t = tapTarget();
  p.fire('touchstart', t, { touches: [{ clientX: 5, clientY: 5 }] });
  p.fire('touchend', t, { changedTouches: [{ clientX: 5, clientY: 6 }] });
  p.fire('click', t);
  p.fire('pause', p.video);
  p.fire('play', p.video);
  assert.deepEqual(p.bridgeCalls.map((c) => c.action), ['pause']);
  const next = { tagName: 'BUTTON', closest: (sel) => (/ytp-next-button/.test(sel) || /html5-video-player/.test(sel) ? next : null) };
  p.fire('touchstart', next, { touches: [{ clientX: 5, clientY: 5 }] });
  p.fire('touchend', next, { changedTouches: [{ clientX: 5, clientY: 5 }] });
  p.fire('click', next);
  assert.equal(p.bridgeCalls.filter((c) => c.action === 'next').length, 1);
});

test('taps on other player controls (settings, captions, title) authorise nothing', () => {
  const p = page();
  const settings = { tagName: 'BUTTON', closest: (sel) => (/html5-video-player/.test(sel) ? settings : null) };
  p.fire('click', settings);
  p.fire('pause', p.video);
  p.fire('seeked', p.video);
  assert.equal(p.bridgeCalls.length, 0);
});

test('double tap on the video seeks; a single tap does not', () => {
  const p = page();
  const t = tapTarget();
  p.fire('click', t);
  p.fire('seeked', p.video);
  assert.equal(p.bridgeCalls.length, 0);
  p.advance(2000);
  p.fire('click', t); p.advance(200); p.fire('click', t);
  p.fire('seeked', p.video);
  assert.deepEqual(p.bridgeCalls.map((c) => c.action), ['seek']);
});

test('authorisation is bound to the media element the user touched', () => {
  const p = page();
  p.fire('click', tapTarget());
  const other = { tagName: 'VIDEO', paused: false, ended: false };
  p.fire('pause', other);
  assert.equal(p.bridgeCalls.length, 0);
});

test('our own play/pause/seek events are never reported, even when a user gesture follows', async () => {
  const p = page();
  p.setTime(0);
  p.M.sync(30_000, true, 1, 'AAAAAAAAAAA', false, ''); // plays and seeks
  p.advance(100);
  p.fire('click', barTarget());
  p.fire('seeked', p.video);      // belongs to our seek, delivered late
  assert.equal(p.bridgeCalls.length, 0);
  p.fire('seeked', p.video);      // the user's own scrub afterwards
  assert.deepEqual(p.bridgeCalls.map((c) => c.action), ['seek']);
});

test('a link click tells the app what the user chose', () => {
  const p = page();
  const link = { tagName: 'A', href: 'https://m.youtube.com/watch?v=BBBBBBBBBBB&t=90', closest: (sel) => (/^a\[href\]/.test(sel) ? link : null) };
  p.fire('click', link);
  assert.deepEqual(p.intents.map((i) => i.url), ['https://m.youtube.com/watch?v=BBBBBBBBBBB&t=90']);
  assert.equal(p.intents[0].doc, 7);
});

test('no playback-rate correction while either player is buffering or seeking', () => {
  const p = page();
  aligned(p, 10_000);
  p.setTime(10.8);
  assert.equal(parts(p.M.sync(10_000, true, 1, 'AAAAAAAAAAA', true, '')).action, 'wait');
  assert.equal(p.video.playbackRate, 1);
  p.video.readyState = 2;
  assert.equal(parts(p.M.sync(10_000, true, 1, 'AAAAAAAAAAA', false, '')).action, 'wait');
  p.video.readyState = 4;
  assert.equal(parts(p.M.sync(10_000, true, 1, 'AAAAAAAAAAA', false, '')).action, 'nudge');
});
test('identity: the player\'s own data wins, live and Shorts routes are understood', () => {
  const p = page({ pathname: '/shorts/SSSSSSSSSSS', search: '' });
  p.setTime(0);
  assert.match(p.M.sync(0, true, 1, 'SSSSSSSSSSS'), /^0\|ok/);
  assert.equal(p.M.sync(0, true, 1, 'AAAAAAAAAAA'), 'mismatch');
  p.setVideoData({ video_id: 'DDDDDDDDDDD' });
  assert.equal(p.M.sync(0, true, 1, 'SSSSSSSSSSS'), 'mismatch', 'the loaded media, not the URL, is authoritative');
  assert.match(p.M.sync(0, true, 1, 'DDDDDDDDDDD'), /^0\|ok/);
  const live = page({ pathname: '/live/LLLLLLLLLLL', search: '' });
  assert.match(live.M.sync(0, true, 1, 'LLLLLLLLLLL'), /^0\|ok/);
});

test('the decoded height and the phone position are reported with every sync', () => {
  const p = page();
  p.video.videoHeight = 360;
  p.setTime(12.5);
  const r = parts(p.M.sync(12_500, true, 1, 'AAAAAAAAAAA'));
  assert.equal(r.height, 360);
  assert.equal(r.phoneMs, 12_500);
});
test('hidden and visible states reach existing frames', () => {
  const p = page();
  p.M.stop();
  assert.equal(JSON.stringify(p.posted.at(-1)), '{"aayMirrorStopped":true}');
  p.M.sync(0, true, 1, 'AAAAAAAAAAA');
  assert.equal(JSON.stringify(p.posted.at(-1)), '{"aayMirrorStopped":false}');
  const parent = {};
  const f = page({ frame: true, parent });
  const onMessage = f.windowListeners.get('message');
  f.video.paused = false;
  onMessage({ data: { aayMirrorStopped: true }, source: parent });
  assert.equal(f.video.paused, true);
  const f2 = page({ frame: true, parent });
  f2.windowListeners.get('message')({ data: { aayMirrorStopped: true }, source: {} });
  assert.equal(f2.video.paused, true, 'unchanged: messages from other sources are ignored');
  f2.video.paused = false;
  f2.windowListeners.get('message')({ data: { aayMirrorStopped: true }, source: {} });
  assert.equal(f2.video.paused, false);
});

test('a replaced YouTube player gets the quality limit again', () => {
  const p = page();
  p.M.layout(true);
  const n = p.qualityCalls.length;
  p.swapPlayer();
  p.M.sync(0, true, 1, 'AAAAAAAAAAA');
  p.M.layout(true);
  assert.ok(p.qualityCalls.length > n, 'quality was not reapplied to the new player');
});

test('a buffering primary holds the secondary media clock', () => {
  const p = page();
  aligned(p, 12_000);
  p.video.paused = false;
  p.setTime(12);
  assert.match(p.M.sync(10000, true, 1, 'AAAAAAAAAAA', true, 'small'), /\|wait\|/);
  assert.equal(p.video.paused, true);
  assert.equal(p.video.playbackRate, 1);
});
test('native accepted navigation consumes the click before YouTube navigates again', () => {
  const p = page();
  p.sandbox.AAYMirror.intent = () => true;
  const link = { tagName: 'A', href: 'https://m.youtube.com/watch?v=BBBBBBBBBBB&t=42',
    closest: (sel) => /^a\[href\]/.test(sel) ? link : null };
  let prevented = false, stopped = false;
  p.fire('click', link, { preventDefault: () => { prevented = true; }, stopImmediatePropagation: () => { stopped = true; } });
  assert.equal(prevented, true);
  assert.equal(stopped, true);
});

test('startup quality cap is enforced before the first play', async () => {
  const p = page({ initialCap: 'small' });
  await p.video.play();
  assert.deepEqual(p.qualityCalls.at(-1), ['small', 'small']);
});

test('authoritative pause is not delayed by a phone gesture', () => {
  const p = page();
  p.M.sync(0, true, 1, 'AAAAAAAAAAA');
  p.fire('click', tapTarget());
  p.M.sync(0, false, 1, 'AAAAAAAAAAA', false, '');
  assert.equal(p.video.paused, true);
});
test('secondary advertisements stay paused during primary pause or buffering', () => {
  const p = page();
  p.setAd(true);
  p.video.paused = false;
  assert.equal(p.M.sync(0, false, 1, 'AAAAAAAAAAA', false, 'small'), 'ad');
  assert.equal(p.video.paused, true);
  assert.equal(p.M.sync(0, true, 1, 'AAAAAAAAAAA', true, 'small'), 'ad');
  assert.equal(p.video.paused, true);
  assert.equal(p.M.sync(0, true, 1, 'AAAAAAAAAAA', false, 'small'), 'ad');
  assert.equal(p.video.paused, false);
});
test('rejected native navigation does not consume the click', () => {
  const p = page();
  p.sandbox.AAYMirror.intent = () => false;
  const link = { tagName: 'A', href: 'https://m.youtube.com/watch?v=BBBBBBBBBBB',
    closest: (sel) => /^a\[href\]/.test(sel) ? link : null };
  let prevented = false;
  p.fire('click', link, { preventDefault: () => { prevented = true; } });
  assert.equal(prevented, false);
});

test('acknowledged link waits for authorization before navigating once', () => {
  const p = page();
  const navigations = [];
  p.sandbox.location.assign = url => navigations.push(url);
  let request;
  p.sandbox.AAYMirror.intentRequest = (url, token, doc, req) => { request = req; return true; };
  const link = { tagName: 'A', href: 'https://m.youtube.com/watch?v=BBBBBBBBBBB&t=42',
    closest: (sel) => /^a\[href\]/.test(sel) ? link : null };
  p.fire('click', link, { preventDefault() {}, stopImmediatePropagation() {} });
  assert.equal(navigations.length, 0);
  p.M.navigationResult(p.M.doc, request, true);
  p.M.navigationResult(p.M.doc, request, true);
  assert.deepEqual(navigations, [link.href]);
});

test('initial sync with a large difference seeks while hidden, even before the page buffered', () => {
  const p = page();
  const html = p.sandbox.document.documentElement.classList;
  assert.equal(html.contains('aaym-aligning'), true, 'hidden from the first frame');
  p.video.readyState = 1; // metadata only (the 138 s case: never buffered, so never corrected)
  p.setTime(0);
  const r = parts(p.M.sync(138_905, true, 1, 'AAAAAAAAAAA'));
  assert.equal(r.action, 'seek');
  assert.equal(r.phase, 'align');
  assert.equal(r.drift, -138_905);
  assert.equal(p.video.paused, true, 'does not play at the wrong position');
  assert.equal(html.contains('aaym-aligning'), true);
  p.setTime(139.155); p.fire('seeked', p.video); p.video.readyState = 4;
  p.advance(300);
  const s2 = parts(p.M.sync(139_200, true, 1, 'AAAAAAAAAAA'));
  assert.equal(s2.phase, 'stable');
  assert.equal(html.contains('aaym-aligning'), false, 'shown once aligned');
  assert.equal(p.video.paused, false);
});

test('resume after a long hidden period re-aligns before showing the video', () => {
  const p = page();
  aligned(p, 5_000);
  p.M.stop();
  const html = p.sandbox.document.documentElement.classList;
  assert.equal(html.contains('aaym-aligning'), true);
  p.advance(600_000);
  const r = parts(p.M.sync(605_000, true, 1, 'AAAAAAAAAAA'));
  assert.equal(r.action, 'seek');
  assert.equal(r.phase, 'align');
  assert.equal(html.contains('aaym-aligning'), true);
});

test('repeated failed seeks end in a controlled reload request', () => {
  const p = page();
  aligned(p, 0);
  let result = '';
  for (let i = 0; i < 3; i++) {
    p.advance(5000);
    p.setTime(0);
    const r = p.M.sync(60_000, true, 1, 'AAAAAAAAAAA');
    if (r === 'seekfail') { result = r; break; }
    p.setTime(0);              // the seek "landed" back at the start
    p.fire('seeked', p.video);
    p.advance(100);
    result = p.M.sync(60_000, true, 1, 'AAAAAAAAAAA');
    if (result === 'seekfail') break;
  }
  assert.equal(result, 'seekfail');
  assert.equal(p.M.phase, 'align');
});

test('a seek that never completes counts as failed after the verify window', () => {
  const p = page();
  aligned(p, 0);
  p.advance(4000);
  p.setTime(0);
  assert.equal(parts(p.M.sync(60_000, true, 1, 'AAAAAAAAAAA')).action, 'seek');
  p.advance(4100);
  p.M.sync(64_000, true, 1, 'AAAAAAAAAAA');
  assert.equal(p.M.seekFails, 1);
});

test('rate correction follows the drift direction immediately when it changes sign', () => {
  const p = page();
  aligned(p, 10_000);
  p.setTime(10.4);
  p.M.sync(10_000, true, 1, 'AAAAAAAAAAA');
  assert.ok(Math.abs(p.video.playbackRate - 0.97) < 1e-9);
  p.setTime(9.6);
  p.M.sync(10_000, true, 1, 'AAAAAAAAAAA');
  assert.ok(Math.abs(p.video.playbackRate - 1.03) < 1e-9, 'no obsolete slow-down after overshooting');
});

test('seek targets are clamped to the media', () => {
  const p = page();
  aligned(p, 0);
  p.advance(4000);
  p.video.duration = 100;
  p.setTime(0);
  p.M.sync(500_000, true, 1, 'AAAAAAAAAAA');
  assert.ok(p.video.currentTime <= 99.75);
});

test('live streams are corrected by a bounded seek only, never by rate', () => {
  const p = page();
  p.video.duration = Infinity;
  p.video.seekable = { length: 1, start: () => 100, end: () => 200 };
  p.setTime(150);
  assert.equal(parts(p.M.sync(151_000, true, 1, 'AAAAAAAAAAA')).phase, 'stable', '1 s is in step for live');
  p.setTime(150);
  p.M.sync(152_000, true, 1, 'AAAAAAAAAAA');
  assert.equal(p.video.playbackRate, 1);
  p.advance(5000);
  p.M.sync(400_000, true, 1, 'AAAAAAAAAAA');
  assert.equal(p.video.currentTime, 200, 'clamped to the live edge');
});

test('no quality increase while the phone is re-aligning', () => {
  const p = page();
  aligned(p, 0);
  p.M.layout(true);
  const n = p.qualityCalls.length;
  p.M.stop();
  p.advance(20_000);
  p.M.layout(false); // would raise medium -> large
  assert.equal(p.qualityCalls.length, n);
});

test('quality ceilings match 240p/360p/480p/720p', () => {
  const p = page();
  assert.equal(JSON.stringify(p.M.qualityHeight), '{"small":240,"medium":360,"large":480,"hd720":720}');
});

test('a new document in mini-player/PiP starts video-only, so the YouTube header never shows', () => {
  const p = page({ videoOnly: true });
  assert.equal(p.sandbox.document.documentElement.classList.contains('aaym-video-only'), true);
  assert.equal(p.M.videoOnly, true);
  const full = page();
  assert.equal(full.sandbox.document.documentElement.classList.contains('aaym-video-only'), false);
});

test('the phone is shown (not left black) while the car buffers once it is close to the car position', () => {
  const p = page();
  const html = p.sandbox.document.documentElement.classList;
  p.setTime(68.5);
  p.video.readyState = 2;
  const r = parts(p.M.sync(68_700, true, 1, 'AAAAAAAAAAA', true, ''));
  assert.equal(r.action, 'wait');
  assert.equal(r.phase, 'stable');
  assert.equal(html.contains('aaym-aligning'), false);
});

test('far from the car position while it buffers: one seek, then shown after the give-up window at the latest', () => {
  const p = page();
  const html = p.sandbox.document.documentElement.classList;
  p.setTime(0);
  p.video.readyState = 2;
  assert.equal(parts(p.M.sync(68_700, true, 1, 'AAAAAAAAAAA', true, '')).action, 'seek');
  p.setTime(68.9); p.fire('seeked', p.video);
  p.advance(9000);
  p.M.sync(68_700, true, 1, 'AAAAAAAAAAA', true, '');
  assert.equal(html.contains('aaym-aligning'), false);
});

const inPlayer = () => { const t = { tagName: 'DIV', closest: (sel) => (/progress/.test(sel) ? null : t) }; return t; };
const touch = (x, y) => ({ clientX: x, clientY: y, screenX: x, screenY: y });

test('a tap on the full player never opens the mini-player, even if the page shifts under the finger', () => {
  const p = page();
  const t = inPlayer();
  p.fire('touchstart', t, { touches: [touch(100, 100)] });
  p.fire('touchend', t, { touches: [], changedTouches: [touch(100, 260)] }); // coordinates jumped, no drag
  assert.equal(p.bridgeCalls.filter((c) => c.action === 'mini').length, 0);
});

test('a real downward drag on the player opens the mini-player', () => {
  const p = page();
  const t = inPlayer();
  p.fire('touchstart', t, { touches: [touch(100, 100)] });
  [130, 170, 210].forEach((y) => p.fire('touchmove', t, { touches: [touch(102, y)] }));
  p.fire('touchend', t, { touches: [], changedTouches: [touch(102, 212)] });
  assert.equal(p.bridgeCalls.filter((c) => c.action === 'mini').length, 1);
});

test('a drag that goes down then back up, sideways, or on the seek bar is not a swipe', () => {
  const p = page();
  const t = inPlayer();
  p.fire('touchstart', t, { touches: [touch(100, 100)] });
  [150, 200, 120].forEach((y) => p.fire('touchmove', t, { touches: [touch(100, y)] }));
  p.fire('touchend', t, { touches: [], changedTouches: [touch(100, 120)] });
  p.fire('touchstart', t, { touches: [touch(100, 100)] });
  [[150, 130], [220, 160], [300, 190]].forEach(([x, y]) => p.fire('touchmove', t, { touches: [touch(x, y)] }));
  p.fire('touchend', t, { touches: [], changedTouches: [touch(300, 190)] });
  const bar = { tagName: 'DIV', closest: () => bar };
  p.fire('touchstart', bar, { touches: [touch(100, 100)] });
  [130, 170, 210].forEach((y) => p.fire('touchmove', bar, { touches: [touch(100, y)] }));
  p.fire('touchend', bar, { touches: [], changedTouches: [touch(100, 212)] });
  assert.equal(p.bridgeCalls.filter((c) => c.action === 'mini').length, 0);
});
