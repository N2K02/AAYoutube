'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('app/src/main/assets/aay.js', 'utf8');

function playerPage(options = {}) {
  const callbacks = new Map();
  const windowCallbacks = new Map();
  const timers = [];
  const classNames = new Set();
  const stateReports = [];
  const accountReports = [];
  const lyricReports = [];
  const intervals = [];
  const requests = [];
  const qualityCalls = [];
  const qualityStorage = new Map();
  const perfReports = [];
  const observers = [];
  const localStorage = {
    getItem(key) { return qualityStorage.get(key) || null; },
    setItem(key, value) { qualityStorage.set(key, String(value)); },
    removeItem(key) { qualityStorage.delete(key); },
  };
  const selectedQualities = [];
  let nextVideoCalls = 0, previousVideoCalls = 0;
  const mockPlayer = {
    setPlaybackQualityRange(from, to) { qualityCalls.push([from, to]); },
    nextVideo() { nextVideoCalls++; },
    previousVideo() { previousVideoCalls++; },
  };
  if (options.currentQuality) {
    let current = options.currentQuality;
    mockPlayer.getPlaybackQuality = () => current;
    mockPlayer.setCurrentQuality = (q) => { current = q; };
  }
  let miniRequests = 0, resizeEvents = 0;
  const styleElements = [];
  const seekBar = {
    contains(target) { return target === this; },
    getBoundingClientRect() { return { left: 10, top: 30, right: 210, bottom: 48, width: 200 }; },
  };
  const navigator = { mediaSession: null };

  class Media {
    constructor() {
      this.tagName = 'VIDEO';
      this.paused = false;
      this.ended = false;
      this.readyState = 4;
      this.duration = 300;
      this.currentTime = 20;
      this.playbackRate = 1;
      this.videoWidth = 1920;
      this.videoHeight = 1080;
      this.parentElement = null;
      this.events = new Map();
    }
    pause() { this.paused = true; }
    play() { this.paused = false; return Promise.resolve(); }
    getBoundingClientRect() { return { left: 0, top: 0, width: 320, height: 180 }; }
    addEventListener(type, fn) {
      if (!this.events.has(type)) this.events.set(type, []);
      this.events.get(type).push(fn);
    }
    trigger(type) { for (const fn of this.events.get(type) || []) fn({ target: this }); }
  }
  class Doc {
    hasFocus() { return true; }
    addEventListener(type, fn) {
      if (!callbacks.has(type)) callbacks.set(type, []);
      callbacks.get(type).push(fn);
    }
    querySelector(selector) {
      if (selector.includes('ProgressBarDragContainer')) return seekBar;
      if (selector === 'video.html5-main-video' || selector === 'video')
        return options.noVideo ? null : activeVideo;
      return dom ? dom.querySelectorAll(selector)[0] || null : null;
    }
    querySelectorAll(selector) { return dom ? dom.querySelectorAll(selector) : []; }
    getElementById(id) {
      if (dom && dom.byId(id)) return dom.byId(id);
      if ((options.quality || options.skipPlayer) && id === 'movie_player') {
        return mockPlayer;
      }
      return null;
    }
    createElement(tag) {
      const element = { tagName: tag.toUpperCase(), style: {}, parentNode: null };
      if (tag === 'style') styleElements.push(element);
      return element;
    }
  }
  const pageState = { hidden: false };
  Object.defineProperty(Doc.prototype, 'hidden', { configurable: true, get() { return pageState.hidden; } });

  const video = new Media();
  let activeVideo = video;
  const dom = options.dom ? options.dom(video) : null;
  const document = new Doc();
  document.title = 'Test video - YouTube';
  document.readyState = 'loading';
  document.fullscreenElement = null;
  document.documentElement = {
    classList: {
      toggle(name, on) { if (on) classNames.add(name); else classNames.delete(name); },
      add(name) { classNames.add(name); },
      remove(name) { classNames.delete(name); },
      contains(name) { return classNames.has(name); },
    },
  };
  document.head = {
    appendChild(element) { element.parentNode = this; },
  };
  const window = {
    __AAY_CFG: Object.assign({ backgroundPlay: true, blockAds: false, sponsorBlock: false, returnDislike: false }, options.cfg || {}),
    __AAY_BROWSE: !!options.browse,
    __AAY_CAR: !!options.car,
    ytcfg: options.signedIn === undefined ? null : {
      get(name) { return name === 'LOGGED_IN' ? options.signedIn : undefined; },
    },
    AAYNative: {
      account(status) { accountReports.push(status); },
      lyricsTime(...args) { lyricReports.push(args); },
      mini() { miniRequests++; },
      quality(choice) { selectedQualities.push(choice); },
      perf(payload) { perfReports.push(JSON.parse(payload)); },
      state(json) { stateReports.push(JSON.parse(json)); },
      otherMediaHold() { return !!(options.otherMedia && options.otherMedia.active); },
    },
    addEventListener(type, fn) {
      if (!windowCallbacks.has(type)) windowCallbacks.set(type, []);
      windowCallbacks.get(type).push(fn);
    },
    dispatchEvent(event) { if (event && event.type === 'resize') resizeEvents++; },
  };
  window.top = window;
  window.self = window;
  const location = {
    hostname: 'm.youtube.com', pathname: '/watch',
    search: '?v=86p7SP34qSc' + (options.paused ? '&aay=paused' : (options.autoplay ? '&aay=1' : '')),
    href: 'https://m.youtube.com/watch?v=86p7SP34qSc' + (options.paused ? '&aay=paused' : (options.autoplay ? '&aay=1' : '')),
  };
  const clock = { t: 1_000_000 };
  class FakeMediaSource { static isTypeSupported() { return true; } }
  window.MediaSource = FakeMediaSource;
  const FakeDate = class extends Date { static now() { return clock.t; } };
  const context = {
    Date: FakeDate,
    window, document, location,
    navigator, localStorage,
    history: { pushState() {}, replaceState() {} },
    Document: Doc, HTMLMediaElement: Media, MediaSession: class {},
    MutationObserver: class {
      constructor() { this.active = false; observers.push(this); }
      observe(target) { this.active = true; this.target = target; }
      disconnect() { this.active = false; }
    },
    URL, console, Promise, Error, DOMException, TextEncoder,
    crypto: { subtle: { digest() { return Promise.resolve(new Uint8Array(32).buffer); } } },
    fetch(url) {
      requests.push(url);
      return Promise.resolve({ ok: true, json: () => Promise.resolve([]) });
    },
    innerWidth: 320, innerHeight: 180,
    getComputedStyle() { return { visibility: 'visible', direction: 'ltr' }; },
    Event: class { constructor(type) { this.type = type; } },
    setTimeout(fn) { timers.push(fn); return timers.length; },
    setInterval(fn) { intervals.push(fn); return intervals.length; },
    clearInterval(id) { intervals[id - 1] = null; },
    clearTimeout(id) { timers[id - 1] = null; },
  };
  vm.runInNewContext(source, context, { filename: 'aay.js', timeout: 1000 });

  return {
    domNodes: dom ? dom.nodes : null,
    MediaSource: FakeMediaSource,
    observers,
    window, navigator, video, seekBar, document, location, classNames, styleElements, stateReports, accountReports, lyricReports, requests, qualityCalls, selectedQualities, qualityStorage, perfReports,
    runIntervals() { for (const fn of intervals) if (fn) fn(); },
    advance(ms) { clock.t += ms; },
    setRealHidden(on) { pageState.hidden = !!on; },
    emitWindow(type, event) {
      for (const fn of windowCallbacks.get(type) || []) fn(event);
    },
    get miniRequests() { return miniRequests; },
    get resizeEvents() { return resizeEvents; },
    replaceVideo() { activeVideo = new Media(); return activeVideo; },
    player: mockPlayer,
    get nextVideoCalls() { return nextVideoCalls; },
    get previousVideoCalls() { return previousVideoCalls; },
    emit(type, event) {
      for (const fn of callbacks.get(type) || []) fn({ type, ...event });
    },
    flush() {
      let budget = 100;
      while (timers.length && budget-- > 0) { const fn = timers.shift(); if (fn) fn(); }
      assert.ok(budget > 0, 'page script scheduled an endless timer chain');
    },
  };
}

// Minimal element tree for the car layout guard (simple #id / .class / tag selectors).
function fakeDom(build) {
  return (video) => {
    const all = [];
    const el = (tag, attrs = {}, children = []) => {
      const classes = new Set(attrs.cls || []);
      const node = {
        tagName: tag.toUpperCase(), id: attrs.id || '', parentElement: null,
        style: { setProperty() {}, removeProperty() {} },
        classList: {
          contains: (n) => classes.has(n),
          toggle: (n, on) => { if (on) classes.add(n); else classes.delete(n); },
          add: (n) => classes.add(n),
          remove: (n) => classes.delete(n),
        },
        contains(other) {
          for (let x = other; x; x = x.parentElement) if (x === node) return true;
          return false;
        },
      };
      for (const c of children) c.parentElement = node;
      all.push(node);
      return node;
    };
    const nodes = build(el, video);
    const matches = (n, sel) => sel.startsWith('#') ? n.id === sel.slice(1)
      : sel.startsWith('.') ? n.classList.contains(sel.slice(1))
      : n.tagName === sel.toUpperCase();
    return {
      nodes,
      byId: (id) => all.find((n) => n.id === id) || null,
      querySelectorAll: (selector) => {
        const parts = selector.split(',').map((x) => x.trim());
        return all.filter((n) => parts.some((sel) => matches(n, sel)));
      },
    };
  };
}

// m.youtube.com watch page: fixed player first, its flow spacer later in the DOM.
function watchPageDom() {
  return fakeDom((el, video) => {
    const videoBox = el('div', { cls: ['html5-video-container'] });
    video.parentElement = videoBox;
    const moviePlayer = el('div', { id: 'movie_player', cls: ['html5-video-player'] }, [videoBox]);
    const player = el('div', { id: 'player' }, [moviePlayer]);
    const controls = el('div', { id: 'player-control-container' });
    const container = el('div', { id: 'player-container-id', cls: ['player-container'] }, [player, controls]);
    const placeholder = el('div', { cls: ['player-size', 'player-placeholder'] });
    const metadata = el('ytm-watch-metadata');
    const watch = el('ytm-watch', {}, [placeholder, metadata]);
    el('ytm-app', {}, [container, watch]);
    return { videoBox, moviePlayer, player, controls, container, placeholder, watch };
  });
}

test('document-start script initializes the player and its native bridge', () => {
  const p = playerPage();
  assert.equal(typeof p.window.__aay.mini, 'function');
  assert.equal(typeof p.window.__aay.cmd, 'function');
});

test('a quick downward gesture on the video requests a mini player', () => {
  const p = playerPage();
  p.emit('touchstart', {
    touches: [{ clientX: 100, clientY: 100 }],
    target: { closest: () => ({}) },
  });
  p.emit('touchend', { changedTouches: [{ clientX: 110, clientY: 220 }] });
  assert.equal(p.miniRequests, 1);
});

test('horizontal swipe and gestures outside the video do not minimize', () => {
  const p = playerPage();
  const start = (target) => p.emit('touchstart', {
    touches: [{ clientX: 100, clientY: 100 }], target,
  });
  start({ closest: () => ({}) });
  p.emit('touchend', { changedTouches: [{ clientX: 240, clientY: 200 }] });
  start({ closest: () => null });
  p.emit('touchend', { changedTouches: [{ clientX: 100, clientY: 220 }] });
  assert.equal(p.miniRequests, 0);
});

test('mini layout exposes the video surface and preserves play until explicitly paused', () => {
  const p = playerPage();
  p.window.__aay.mini(true);
  assert.ok(p.classNames.has('aay-video-only'));
  assert.ok(p.classNames.has('aay-mini'));
  assert.equal(p.classNames.has('aay-pip'), false);
  assert.ok(p.styleElements.some(e => e.textContent &&
    e.textContent.includes('html.aay-video-only video.html5-main-video')));
  p.video.pause();
  assert.equal(p.video.paused, false, 'YouTube resize pauses must be ignored in mini mode');
  p.window.__aay.cmd('pause');
  assert.equal(p.video.paused, true, 'explicit pause must still work');
  p.window.__aay.mini(false);
  assert.equal(p.classNames.has('aay-video-only'), false);
  assert.equal(p.classNames.has('aay-mini'), false);
});

test('car mini player recovers an unexpected pause but honours explicit pause', () => {
  const p = playerPage({ car: true });
  p.flush(); // Bind the initially-playing video before simulating the interruption.
  p.window.__aay.mini(true);
  // Simulate a Chromium pause that bypasses HTMLMediaElement.pause(), such
  // as an audio focus handoff when the browse WebView becomes visible.
  p.video.paused = true;
  p.video.trigger('pause');
  p.advance(3000); // focus-handoff settle window
  p.runIntervals();
  assert.equal(p.video.paused, false, 'mini player must resume an unsolicited pause');
  p.window.__aay.cmd('pause');
  p.runIntervals();
  assert.equal(p.video.paused, true, 'mini player must honour the user pause');
});

test('car video to audio handoff recovers before the normal watchdog interval', () => {
  const p = playerPage({ car: true });
  p.flush();
  p.window.__aay.modeTransit();
  p.window.__aay.car(false);
  p.window.__aay.setBg(true);
  p.video.paused = true;
  p.video.trigger('pause');
  p.advance(800);
  p.flush();
  assert.equal(p.video.paused, false, 'handoff must recover without waiting five seconds');
});

test('surface-detach pause during a mode handoff resumes within about 300 ms', () => {
  const p = playerPage({ car: true });
  p.flush();
  p.window.__aay.modeTransit();
  p.video.paused = true;
  p.video.trigger('pause');
  p.advance(300);
  p.flush();
  assert.equal(p.video.paused, false);
});

test('retained audio mode preserves quality, position and explicit pause', () => {
  const p = playerPage({ car: true, quality: true });
  p.flush();
  const n = p.qualityCalls.length;
  p.video.currentTime = 42;
  p.window.__aay.modeTransit();
  p.window.__aay.audioMode(true);
  p.window.__aay.car(false);
  p.window.__aay.setBg(true);
  p.advance(2000);
  p.runIntervals();
  assert.equal(p.qualityCalls.length, n, 'audio mode does not request a different stream');
  assert.equal(p.video.currentTime, 42, 'handoff does not seek');
  assert.equal(p.video.paused, false);
  p.window.__aay.cmd('pause');
  p.runIntervals();
  assert.equal(p.video.paused, true, 'explicit pause remains authoritative');
});

test('car handoff recovery never overrides explicit Pause', () => {
  const p = playerPage({ car: true });
  p.flush();
  p.window.__aay.modeTransit();
  p.video.paused = true;
  p.video.trigger('pause');
  p.window.__aay.cmd('pause');
  p.advance(800);
  p.flush();
  assert.equal(p.video.paused, true);
});

test('car handoff recovery respects other active audio', () => {
  const other = { active: false };
  const p = playerPage({ car: true, otherMedia: other });
  p.flush();
  p.window.__aay.modeTransit();
  p.video.paused = true;
  p.video.trigger('pause');
  other.active = true;
  p.advance(800);
  p.flush();
  assert.equal(p.video.paused, true);
});

test('pause caused by another app playing media is not resumed until it stops', () => {
  const other = { active: false };
  const p = playerPage({ otherMedia: other });
  p.flush();
  p.video.paused = true;          // Chromium paused on audio-focus loss
  p.video.trigger('pause');
  p.runIntervals();
  assert.equal(p.video.paused, true, 'no instant resume during the focus handoff');
  other.active = true;            // the other app's audio is now playing
  p.advance(3000);
  p.runIntervals();
  assert.equal(p.video.paused, true, 'must not steal audio focus from another app');
  p.advance(60000);
  p.runIntervals();
  assert.equal(p.video.paused, true, 'held while other media plays');
  other.active = false;           // native sees quiet audio and ends the hold
  p.window.__aay.mediaHold(false);
  p.runIntervals();
  assert.equal(p.video.paused, false, 'resumes where it was once other media stopped');
});

test('other app audio focus immediately pauses an actively playing video without user Pause', () => {
  const p = playerPage({ quality: true });
  p.flush();
  assert.equal(p.video.paused, false);
  p.window.__aay.mediaHold(true);
  assert.equal(p.video.paused, true, 'yield sound immediately, do not wait for Chromium');
  p.window.__aay.cmd('play');
  assert.equal(p.video.paused, true, 'native Play is deferred while another app owns sound');
  p.video.paused = false; // YouTube emits a delayed Play after focus was taken
  p.video.trigger('play');
  assert.equal(p.video.paused, true, 'late page Play cannot steal audio focus');
  p.runIntervals();
  assert.equal(p.video.paused, true, 'watchdogs respect the hold');
  p.window.__aay.mediaHold(false);
  p.advance(3500);
  p.runIntervals();
  assert.equal(p.video.paused, false, 'resume only when focus hold is released');
});

test('user Pause during external focus hold stays paused after the other app stops', () => {
  const p = playerPage();
  p.flush();
  p.window.__aay.mediaHold(true);
  p.window.__aay.cmd('pause');
  p.window.__aay.mediaHold(false);
  p.advance(60000);
  p.runIntervals();
  assert.equal(p.video.paused, true, 'user Pause overrides earlier auto-resume intent');
});

test('YouTube stale Play icon repair never restarts held audio', () => {
  const p = playerPage({ quality: true });
  p.flush();
  const player = p.document.getElementById('movie_player');
  let repairs = 0;
  player.getPlayerState = () => 2;
  player.playVideo = () => { repairs++; };
  p.window.__aay.mediaHold(true);
  p.video.paused = false; // stale Chromium playback state before a pause event
  p.runIntervals();
  assert.equal(repairs, 0, 'UI state reconciliation must respect focus loss');
});

test('expired media hold gives up instead of resuming much later', () => {
  const p = playerPage({ otherMedia: { active: true } });
  p.flush();
  p.video.paused = true;
  p.video.trigger('pause');
  p.advance(3000);
  p.runIntervals();
  p.window.__aay.mediaHold(false, true);
  p.runIntervals();
  assert.equal(p.video.paused, true);
});

test('PiP caps quality at 720p and restores the saved quality on exit', () => {
  const p = playerPage({ quality: true });
  p.window.__aay.config && p.window.__aay.config({ preferredQuality: 'hd1440' });
  p.flush();
  assert.deepEqual(p.qualityCalls.at(-1), ['hd1440', 'hd1440']);
  p.window.__aay.pip(true);
  assert.deepEqual(p.qualityCalls.at(-1), ['hd720', 'hd720'], 'PiP must not decode 1440p');
  p.window.__aay.pip(false);
  assert.deepEqual(p.qualityCalls.at(-1), ['hd1440', 'hd1440'], 'saved quality returns');
  p.window.__aay.mini(true);
  assert.deepEqual(p.qualityCalls.at(-1), ['hd720', 'hd720'], 'mini player must not decode 1440p');
  p.window.__aay.mini(false);
  assert.deepEqual(p.qualityCalls.at(-1), ['hd1440', 'hd1440']);
});

test('a wrong play/pause state is reported again without waiting', () => {
  const p = playerPage();
  p.flush();
  p.runIntervals();
  const n = p.stateReports.length;
  assert.equal(p.stateReports.at(-1).playing, true);
  p.video.paused = true;      // a pause the page never announced
  p.runIntervals();
  assert.ok(p.stateReports.length > n, 'reconciled on the watchdog tick');
  assert.equal(p.stateReports.at(-1).playing, false);
});

test('system PiP changes only its own mode, preserves video-only view until exit', () => {
  const p = playerPage();
  p.window.__aay.mini(true);
  p.window.__aay.pip(true);
  assert.equal(p.classNames.has('aay-mini'), false);
  assert.equal(p.classNames.has('aay-pip'), true);
  assert.equal(p.classNames.has('aay-video-only'), true);
  p.window.__aay.pip(false);
  assert.equal(p.classNames.has('aay-mini'), true);
  assert.equal(p.classNames.has('aay-pip'), false);
  assert.equal(p.classNames.has('aay-video-only'), true);
  p.window.__aay.mini(false);
  assert.equal(p.classNames.has('aay-video-only'), false);
});

test('unmute overlay is hidden but video itself is not suppressed', () => {
  const p = playerPage();
  const css = p.styleElements.map(e => e.textContent).join('');
  assert.ok(css.includes('ytm-unmute-button'));
  assert.ok(css.includes('video.html5-main-video'));
  assert.ok(!css.includes('body *{visibility:hidden'));
});

test('fullscreen car video taps reach YouTube control surface while mini stays touch-through', () => {
  const p = playerPage({ car: true });
  const css = p.styleElements.map(e => e.textContent).join('');
  assert.ok(css.includes('html.aay-car #player-control-container{'));
  assert.ok(css.includes('background:transparent!important;pointer-events:auto!important'),
    'fullscreen YouTube controls must receive the tap that reveals quality/seeker');
  assert.ok(css.includes('html.aay-car .aay-car-clear:not(#aay-x):not(#aay-y)'));
  assert.ok(css.includes('background:transparent!important;pointer-events:none!important'),
    'unused player wrappers must stay touch-through');
  assert.ok(!css.includes('html.aay-mini #player-control-container{pointer-events:auto'));
  for (const selector of [
    '#player-control-container button',
    '#player-control-container [role="button"]',
    '#player-control-container .ytp-settings-button',
    '#player-control-container .ytp-popup',
    '#player-control-container .ytp-menuitem',
    '#player-control-container [role="menuitem"]',
    '#player-control-container ytm-icon-button',
    '#player-control-container ytm-menu-item-renderer',
    '.aay-car-clear .ytp-popup',
    '.aay-car-clear ytm-menu-popup-renderer',
  ]) {
    assert.ok(css.includes('html.aay-car ' + selector), 'missing interactive menu target ' + selector);
  }
  // Never give the entire black frame a pointer-events:auto override.
  assert.ok(!css.includes('html.aay-car .aay-car-clear{pointer-events:auto'));
  assert.ok(css.includes('html.aay-car.aay-car-controls .ytp-chrome-top'));
  assert.ok(css.includes('html.aay-car.aay-car-controls .ytp-chrome-bottom'));
  assert.ok(css.includes('html.aay-car.aay-car-controls .ytp-progress-bar-container'));
  p.window.__aay.car(true);
  p.window.__aay.showCarControls();
  assert.ok(p.classNames.has('aay-car-controls'), 'native car tap makes native controls visible');
  p.window.__aay.showCarControls();
  assert.ok(p.classNames.has('aay-car-controls'), 'repeated tap extends visibility');
  p.window.__aay.mini(true);
  p.window.__aay.showCarControls();
  assert.ok(!p.classNames.has('aay-car'), 'mini player must not use fullscreen hit targets');
  p.window.__aay.car(false);
  assert.ok(!p.classNames.has('aay-car-controls'), 'leaving car removes reveal mode');
});

test('direct Chromium car taps reveal YouTube controls without seeking or reacting to drags', () => {
  const p = playerPage({ car: true });
  p.window.__aay.car(true);
  const point = (x, y) => ({ pointerId: 2, isPrimary: true, clientX: x, clientY: y });
  p.emitWindow('pointerdown', point(60, 75));
  p.emitWindow('pointerup', point(60, 75));
  assert.equal(p.classNames.has('aay-car-controls'), true,
    'a tap sent directly to Chromium reveals top quality and bottom seek controls');
  assert.equal(p.video.currentTime, 20, 'an ordinary tap must not seek');
  p.window.__aay.mini(true);
  assert.equal(p.classNames.has('aay-car-controls'), false,
    'minimizing clears fullscreen control visibility');
  p.emitWindow('pointerdown', point(60, 75));
  p.emitWindow('pointerup', point(60, 75));
  assert.equal(p.classNames.has('aay-car-controls'), false, 'mini taps do not trigger fullscreen UI');
  p.window.__aay.mini(false);
  p.emitWindow('pointerdown', point(40, 40));
  p.emitWindow('pointerup', point(120, 120));
  assert.equal(p.classNames.has('aay-car-controls'), false, 'swipe gesture is not a tap');
  p.emitWindow('pointerdown', point(60, 75));
  p.emitWindow('pointerup', point(60, 75));
  assert.equal(p.classNames.has('aay-car-controls'), true);
});

test('720p is requested on startup and does not get reset on small-layout toggles', () => {
  const p = playerPage({ quality: true });
  p.flush();
  assert.deepEqual(p.qualityCalls.at(-1), ['hd720', 'hd720']);
  assert.equal(JSON.parse(p.qualityStorage.get('yt-player-quality')).data, 'hd720');
  const n = p.qualityCalls.length;
  p.window.__aay.mini(true);
  p.window.__aay.pip(true);
  p.window.__aay.pip(false);
  p.window.__aay.mini(false);
  assert.equal(p.qualityCalls.length, n, 'mode switches must not force a redundant reload');
});

test('menu pick already applied by YouTube is not re-forced (smooth switch)', () => {
  const p = playerPage({ quality: true, currentQuality: 'hd720' });
  p.flush();
  const option = (label) => ({
    tagName: 'BUTTON', textContent: label,
    closest(selector) {
      return selector === 'a[href]' || selector === '[class*="renderer"],[class*="lockup"]' ? null : this;
    },
  });
  const n = p.qualityCalls.length;
  p.emit('click', { target: option('1080p') });
  p.player.setCurrentQuality('hd1080'); // YouTube's own handler switched seamlessly
  p.flush();
  assert.deepEqual(p.selectedQualities, ['hd1080']);
  assert.equal(p.qualityCalls.length, n, 'no second source switch after a menu pick');
  p.window.__aay.setBg(false);
  assert.equal(p.qualityCalls.length, n, 'already-applied pick stays without reload');
});

test('mobile YouTube quality labels and nested menu choices persist', () => {
  const p = playerPage({ quality: true, currentQuality: 'hd720' });
  p.flush();
  const option = (label, aria, childLabel) => {
    const item = {
      tagName: 'YTM-MENU-ITEM', textContent: label,
      getAttribute(name) { return name === 'aria-label' ? aria || '' : ''; },
    };
    const child = {
      tagName: 'SPAN', textContent: childLabel === undefined ? label : childLabel,
      closest(selector) {
        if (selector.includes('ytm-menu-item')) return item;
        return null;
      },
    };
    return child;
  };
  p.emit('click', { target: option('1080p60 HDR') });
  assert.deepEqual(p.selectedQualities, ['hd1080']);
  assert.equal(JSON.parse(p.qualityStorage.get('yt-player-quality')).data, 'hd1080');
  p.emit('click', { target: option('1080p Premium') });
  assert.deepEqual(p.selectedQualities, ['hd1080', 'hd1080']);
  p.emit('click', { target: option('Quality', '720p') });
  assert.deepEqual(p.selectedQualities.at(-1), 'hd720', 'aria label fallback saves resolution');
  assert.equal(JSON.parse(p.qualityStorage.get('yt-player-quality')).data, 'hd720');
  p.emit('click', { target: option('Quality 144p') });
  assert.equal(p.selectedQualities.length, 3, 'parent quality row must not be saved');
  p.emit('click', { target: option('Auto (720p)') });
  assert.equal(p.selectedQualities.at(-1), 'auto');
  assert.equal(p.qualityStorage.has('yt-player-quality'), false);
});

test('explicit quality-menu selection is saved; adaptive changes are not', () => {
  const p = playerPage({ quality: true });
  p.flush();
  function option(label) {
    return {
      tagName: 'BUTTON', textContent: label,
      closest(selector) {
        return selector === 'a[href]' || selector === '[class*="renderer"],[class*="lockup"]'
          ? null : this;
      },
    };
  }
  p.emit('click', { target: option('1080p') });
  assert.deepEqual(p.selectedQualities, ['hd1080']);
  assert.equal(JSON.parse(p.qualityStorage.get('yt-player-quality')).data, 'hd1080');
  p.flush();
  assert.deepEqual(p.qualityCalls.at(-1), ['hd1080', 'hd1080']);
  p.window.__aay.mini(true);
  assert.deepEqual(p.qualityCalls.at(-1), ['hd720', 'hd720'], 'mini player is capped at 720p');
  p.window.__aay.mini(false);
  assert.deepEqual(p.qualityCalls.at(-1), ['hd1080', 'hd1080']);
  p.emit('click', { target: option('Quality 144p') }); // parent menu, not a user pick
  assert.deepEqual(p.selectedQualities, ['hd1080']);
  p.emit('click', { target: option('Auto (720p)') });
  p.flush();
  assert.deepEqual(p.selectedQualities, ['hd1080', 'auto']);
  assert.equal(p.qualityStorage.has('yt-player-quality'), false);
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'highres']);
});

function hideForSaver(p) {
  p.setRealHidden(true);
  p.window.__aay.setBg(true);
  p.advance(60600);
  p.flush();
}

test('background saver waits 60 s and restores on return', () => {
  const p = playerPage({ quality: true });
  p.window.__aay.config({ lowQuality: true });
  p.setRealHidden(true);
  p.window.__aay.setBg(true);
  assert.notDeepEqual(p.qualityCalls.at(-1), ['tiny', 'tiny'], 'quick app switch keeps quality');
  p.window.__aay.setBg(false);
  p.advance(70000);
  p.flush();
  assert.notDeepEqual(p.qualityCalls.at(-1), ['tiny', 'tiny']);
  hideForSaver(p);
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'tiny']);
  p.setRealHidden(false);
  p.window.__aay.setBg(false);
  assert.deepEqual(p.qualityCalls.at(-1), ['hd720', 'hd720'], 'visible again: saved quality at once');
});

test('background saver trusts the app off-screen state even if Chromium still says visible; never on live streams', () => {
  // Measured on device: the attached background-play WebView kept reporting
  // visible and decoded 720p at full frame rate behind other apps.
  const p = playerPage({ quality: true });
  p.window.__aay.config({ lowQuality: true });
  p.window.__aay.setBg(true); // native says off screen, Chromium does not
  p.advance(60600); p.flush();
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'tiny']);
  p.window.__aay.setBg(false);
  assert.deepEqual(p.qualityCalls.at(-1), ['hd720', 'hd720'], 'on screen again: saved quality at once');
  const live = playerPage({ quality: true });
  live.window.__aay.config({ lowQuality: true });
  live.video.duration = Infinity;
  hideForSaver(live);
  assert.notDeepEqual(live.qualityCalls.at(-1), ['tiny', 'tiny']);
});

test('background saver backs off for the session if audio stalls after lowering quality', () => {
  const p = playerPage({ quality: true });
  p.window.__aay.config({ lowQuality: true });
  p.video.paused = false;
  hideForSaver(p);
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'tiny']);
  const t = p.video.currentTime;
  p.runIntervals();          // sample position
  p.advance(9000);           // audio clock does not move for 9 s
  p.video.currentTime = t;
  p.runIntervals();
  assert.deepEqual(p.qualityCalls.at(-1), ['hd720', 'hd720'], 'stall restores quality');
  p.window.__aay.setBg(false);
  hideForSaver(p);
  assert.notDeepEqual(p.qualityCalls.at(-1), ['tiny', 'tiny'], 'not retried this session');
});

test('background quality policy restores visible PiP frames', () => {
  const p = playerPage({ quality: true });
  p.window.__aay.config({ lowQuality: true });
  hideForSaver(p);
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'tiny']);
  p.window.__aay.pip(true);
  assert.deepEqual(p.qualityCalls.at(-1), ['hd720', 'hd720'], 'PiP restores 720p instead of tiny');
  p.window.__aay.pip(false);
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'tiny'], 'normal background playback may reduce quality');
  p.window.__aay.setBg(false);
  assert.deepEqual(p.qualityCalls.at(-1), ['hd720', 'hd720']);
});

test('car handoff removes background-only quality cap and preserves mini layout on viewport refit', () => {
  // Start on the phone in hidden/background mode, THEN connect Android Auto.
  const p = playerPage({ quality: true });
  p.window.__aay.config({ lowQuality: true });
  hideForSaver(p);
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'tiny']);
  p.window.__aay.car(true);
  assert.deepEqual(p.qualityCalls.at(-1), ['hd720', 'hd720']);
  p.window.__aay.mini(true);
  p.window.__aay.refitCar();
  assert.equal(p.classNames.has('aay-mini'), true, 'refit must preserve mini player');
  p.window.__aay.car(false);
  assert.deepEqual(p.qualityCalls.at(-1), ['hd720', 'hd720'], 'still-visible mini returns to saved HD');
  p.window.__aay.mini(false);
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'tiny'], 'true hidden background may use low quality');
});

test('video state reaches the media process after initialization', () => {
  const p = playerPage();
  // Script can attach after YouTube's original playing event; the first report
  // must not depend on another media event occurring.
  p.flush();
  assert.ok(p.stateReports.length > 0);
  const snap = p.stateReports.at(-1);
  assert.equal(snap.id, '86p7SP34qSc');
  assert.equal(snap.playing, true);
  assert.equal(snap.pos, 20);
});

test('browse page cannot trigger the native mini-player gesture', () => {
  const p = playerPage({ browse: true });
  p.emit('touchstart', {
    touches: [{ clientX: 100, clientY: 100 }],
    target: { closest: () => ({}) },
  });
  p.emit('touchend', { changedTouches: [{ clientX: 100, clientY: 240 }] });
  assert.equal(p.miniRequests, 0);
});

test('WebView mediaSession shim reports metadata and playlist transport actions', () => {
  const p = playerPage();
  assert.equal(typeof p.navigator.mediaSession.setActionHandler, 'function');
  assert.equal(typeof p.window.MediaMetadata, 'function');
  p.navigator.mediaSession.metadata = new p.window.MediaMetadata({
    title: 'Track', artist: 'Artist',
    artwork: [{ src: 'https://i.ytimg.com/vi/86p7SP34qSc/hqdefault.jpg' }],
  });
  let nextCalls = 0;
  p.navigator.mediaSession.setActionHandler('nexttrack', () => { nextCalls++; });
  p.flush();
  assert.equal(p.stateReports.at(-1).title, 'Track');
  assert.equal(p.stateReports.at(-1).artist, 'Artist');
  assert.equal(p.stateReports.at(-1).next, true);
  p.window.__aay.cmd('next');
  assert.equal(nextCalls, 1);
});

test('a silent YouTube Next handler receives one bounded player fallback', () => {
  const p = playerPage({ skipPlayer: true });
  let handlers = 0;
  p.navigator.mediaSession.setActionHandler('nexttrack', () => { handlers++; });
  p.window.__aay.cmd('next');
  assert.equal(handlers, 1);
  assert.equal(p.nextVideoCalls, 0);
  p.flush();
  assert.equal(p.nextVideoCalls, 1);
});

test('skip fallback cancels when YouTube navigates or the user pauses', () => {
  const p = playerPage({ skipPlayer: true });
  p.navigator.mediaSession.setActionHandler('nexttrack', () => {
    p.location.search = '?v=newVideo9999';
    p.location.href = 'https://m.youtube.com/watch?v=newVideo9999';
  });
  p.window.__aay.cmd('next');
  p.flush();
  assert.equal(p.nextVideoCalls, 0);
  const q = playerPage({ skipPlayer: true });
  q.navigator.mediaSession.setActionHandler('nexttrack', () => {});
  q.window.__aay.cmd('next');
  q.window.__aay.cmd('pause');
  q.flush();
  assert.equal(q.nextVideoCalls, 0);
});

test('rapid commands only allow the latest JavaScript fallback', () => {
  const p = playerPage({ skipPlayer: true });
  p.navigator.mediaSession.setActionHandler('nexttrack', () => {});
  p.window.__aay.cmd('next');
  p.window.__aay.cmd('next');
  p.flush();
  assert.equal(p.nextVideoCalls, 1);
});

test('Previous restarts current video after five seconds without changing tracks', () => {
  const p = playerPage({ skipPlayer: true });
  let handlers = 0;
  p.navigator.mediaSession.setActionHandler('previoustrack', () => { handlers++; });
  p.video.currentTime = 12;
  p.window.__aay.cmd('prev');
  p.flush();
  assert.equal(p.video.currentTime, 0);
  assert.equal(handlers, 0);
  assert.equal(p.previousVideoCalls, 0);
});

test('car surface tap seeks within visible progress bar but ignores ordinary taps', () => {
  const p = playerPage({ car: true });
  const start = (x, y, target = p.seekBar) =>
    p.emitWindow('pointerdown', {
      isPrimary: true, pointerId: 1, clientX: x, clientY: y, target,
    });
  const end = (x, y) =>
    p.emitWindow('pointerup', { pointerId: 1, clientX: x, clientY: y });
  start(110, 40);
  end(110, 40);
  assert.equal(p.video.currentTime, 150);
  start(120, 100);
  end(120, 100);
  assert.equal(p.video.currentTime, 150);
  start(110, 40);
  p.emitWindow('pointermove', { pointerId: 1, clientX: 150, clientY: 40 });
  end(150, 40);
  assert.equal(p.video.currentTime, 150);
});

test('phone taps never use the car-only seek override', () => {
  const p = playerPage();
  p.emitWindow('pointerdown', {
    isPrimary: true, pointerId: 1, clientX: 110, clientY: 40, target: p.seekBar,
  });
  p.emitWindow('pointerup', { pointerId: 1, clientX: 110, clientY: 40 });
  assert.equal(p.video.currentTime, 20);
});

test('logged-out YouTube page reports signed_out but never signed_in', () => {
  const p = playerPage({ signedIn: false });
  p.flush();
  p.runIntervals();
  assert.deepEqual(p.accountReports, ['signed_out']);
});

test('sign-in status follows YouTube configuration changes without cookie guesses', () => {
  const p = playerPage();
  p.flush();
  p.runIntervals();
  assert.deepEqual(p.accountReports, [], 'unknown auth state must not be treated as login');
  p.window.ytcfg = { get(name) { return name === 'LOGGED_IN' ? true : undefined; } };
  p.runIntervals();
  assert.deepEqual(p.accountReports, ['signed_in']);
  p.runIntervals();
  assert.deepEqual(p.accountReports, ['signed_in'], 'unchanged login must not spam native UI');
  p.window.ytcfg = { get(name) { return name === 'LOGGED_IN' ? false : undefined; } };
  p.runIntervals();
  assert.deepEqual(p.accountReports, ['signed_in', 'signed_out']);
});

test('browse WebView does not report login over main player auth state', () => {
  const p = playerPage({ browse: true, signedIn: true });
  p.flush();
  p.runIntervals();
  assert.deepEqual(p.accountReports, []);
});

test('hide promos switch also controls YouTube open-app banners', () => {
  const p = playerPage();
  p.window.__aay.config({ hidePromos: true });
  assert.ok(p.styleElements.some(e => e.textContent && e.textContent.includes('ytm-open-app-button')));
  p.window.__aay.config({ hidePromos: false });
  assert.ok(p.styleElements.every(e => !e.textContent || !e.textContent.includes('ytm-open-app-button')));
});

test('SponsorBlock re-queries when extra categories change on the same video', async () => {
  const p = playerPage();
  const step = async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); };
  p.window.__aay.config({ sponsorBlock: true });
  await step();
  assert.equal(p.requests.length, 1);
  assert.ok(p.requests[0].includes('sponsor'));
  p.window.__aay.config({ sbExtra: true });
  await step();
  assert.equal(p.requests.length, 2);
  assert.ok(p.requests[1].includes('intro'));
  p.window.__aay.config({ sponsorBlock: false });
  p.window.__aay.config({ sponsorBlock: true });
  await step();
  assert.equal(p.requests.length, 3);
});

test('lyrics sync reports the actual video position during playback and seeking', () => {
  const p = playerPage();
  p.window.__aay.lyricsSync(true);
  assert.equal(p.lyricReports.length, 1);
  assert.equal(p.lyricReports[0][0], '86p7SP34qSc');
  assert.equal(p.lyricReports[0][1], 20);
  assert.equal(p.lyricReports[0][2], true);
  p.video.currentTime = 84.5;
  p.video.playbackRate = 1.5;
  p.runIntervals();
  assert.equal(p.lyricReports.at(-1)[1], 84.5, 'a seek must immediately replace the previous position');
  assert.equal(p.lyricReports.at(-1)[4], 1.5);
  p.video.readyState = 2;
  p.runIntervals();
  assert.equal(p.lyricReports.at(-1)[3], true, 'buffering reaches the lyrics clock');
  p.window.__aay.cmd('pause'); // intentional user Pause, not a spontaneous WebView drop
  p.runIntervals();
  assert.equal(p.lyricReports.at(-1)[2], false, 'manual pause reaches the lyrics clock');
});

test('lyrics sync stops reporting when hidden and never runs in the browse page', () => {
  const p = playerPage();
  p.window.__aay.lyricsSync(true);
  p.runIntervals();
  const beforeStop = p.lyricReports.length;
  p.window.__aay.lyricsSync(false);
  p.video.currentTime = 123;
  p.runIntervals();
  assert.equal(p.lyricReports.length, beforeStop);
  const browse = playerPage({ browse: true });
  browse.window.__aay.lyricsSync(true);
  browse.runIntervals();
  assert.equal(browse.lyricReports.length, 0);
});

test('car layout never lets a wrapper without the video cover it', () => {
  const p = playerPage({ car: true, dom: watchPageDom() });
  p.window.__aay.car(true);
  const n = p.domNodes;
  assert.ok(p.classNames.has('aay-car'));
  assert.ok(n.placeholder.classList.contains('aay-car-off'),
    'the spacer after the player must not become a black full-screen box over the video');
  assert.equal(n.container.classList.contains('aay-car-off'), false);
  assert.equal(n.container.classList.contains('aay-car-clear'), false);
  for (const ancestor of [n.videoBox, n.moviePlayer, n.player, n.container])
    assert.ok(ancestor.classList.contains('aay-car-chain'), 'video ancestors stay visible');
  const css = p.styleElements.map((e) => e.textContent).join('');
  assert.ok(css.includes('html.aay-car .aay-car-off:not(#aay-x):not(#aay-y){'));
  assert.ok(/aay-car-off:not\(#aay-x\):not\(#aay-y\)\{[^}]*visibility:hidden!important/.test(css));
  assert.ok(css.indexOf('html.aay-car .aay-car-off') > css.indexOf('html.aay-car .player-placeholder'),
    'guard rules must come after the car wrapper rules');
});

test('car guard clears only the background of a wrapper that holds just the controls', () => {
  const p = playerPage({
    car: true,
    dom: fakeDom((el, video) => {
      const videoBox = el('div', { cls: ['html5-video-container'] });
      video.parentElement = videoBox;
      const container = el('div', { id: 'player-container-id', cls: ['player-container'] }, [videoBox]);
      const controls = el('div', { id: 'player-control-container' });
      const controlsHost = el('ytm-player-container', {}, [controls]);
      el('ytm-app', {}, [container, controlsHost]);
      return { container, controlsHost };
    }),
  });
  p.window.__aay.car(true);
  assert.ok(p.domNodes.controlsHost.classList.contains('aay-car-clear'));
  assert.equal(p.domNodes.controlsHost.classList.contains('aay-car-off'), false);
});

test('leaving the car layout removes every guard mark', () => {
  const p = playerPage({ car: true, dom: watchPageDom() });
  p.window.__aay.car(true);
  p.window.__aay.car(false);
  const n = p.domNodes;
  assert.equal(p.classNames.has('aay-car'), false);
  for (const node of Object.values(n)) {
    assert.equal(node.classList.contains('aay-car-off'), false);
    assert.equal(node.classList.contains('aay-car-clear'), false);
    assert.equal(node.classList.contains('aay-car-chain'), false);
  }
});

test('phone layout never marks car guard classes', () => {
  const p = playerPage({ dom: watchPageDom() });
  p.flush();
  assert.equal(p.domNodes.placeholder.classList.contains('aay-car-off'), false);
});

test('resolution source reset keeps current video at its old position', () => {
  const p = playerPage();
  p.flush();
  p.video.currentTime = 137;
  p.video.trigger('timeupdate');
  p.video.trigger('emptied'); // YouTube swaps its media source on quality change
  p.video.currentTime = 0;
  p.video.readyState = 1;
  p.video.trigger('loadedmetadata');
  assert.equal(p.video.currentTime, 0, 'YouTube gets a moment to restore it first (no double seek)');
  p.flush();
  assert.ok(p.video.currentTime >= 136, 'switching quality must not restart at 0');
  assert.equal(p.location.pathname, '/watch', 'no page navigation is necessary');
});

test('quality switch: YouTube own position restore is not seeked again', () => {
  const p = playerPage();
  p.flush();
  p.video.currentTime = 137;
  p.video.trigger('timeupdate');
  p.video.trigger('emptied');
  p.video.currentTime = 0;
  p.video.readyState = 1;
  p.video.trigger('loadedmetadata');
  p.video.currentTime = 137.4; // YouTube restored and kept playing
  p.flush();
  assert.equal(p.video.currentTime, 137.4, 'no second seek on top of YouTube');
});

test('saved quality missing on a video: nearest lower tier, preference kept', () => {
  const p = playerPage({ quality: true });
  p.player.getAvailableQualityLevels = () => ['hd1080', 'hd720', 'large', 'medium', 'auto'];
  p.window.__aay.config && p.window.__aay.config({ preferredQuality: 'hd2160' });
  p.flush();
  assert.deepEqual(p.qualityCalls.at(-1), ['hd1080', 'hd1080'], 'best available at or below the choice');
  p.player.getAvailableQualityLevels = () => ['medium', 'small'];
  p.window.__aay.setBg(true); p.window.__aay.setBg(false); // force re-apply
  assert.deepEqual(p.qualityCalls.at(-1), ['medium', 'medium']);
});

test('an intentional seek to zero is never undone by the quality guard', () => {
  const p = playerPage();
  p.flush();
  p.video.currentTime = 125;
  p.video.trigger('timeupdate');
  p.video.currentTime = 0;
  p.video.trigger('seeked');
  p.video.trigger('loadedmetadata');
  assert.equal(p.video.currentTime, 0, 'ordinary seek must remain authoritative');
});

test('background watchdog resumes an unexpected pause, but not an explicit media pause', () => {
  const p = playerPage();
  p.flush();
  p.window.__aay.setBg(true);
  p.video.paused = true; // WebView/YouTube native pause, not user action
  p.runIntervals();
  assert.equal(p.video.paused, false, 'background video should resume when intent is playing');
  p.window.__aay.cmd('pause');
  assert.equal(p.video.paused, true);
  p.runIntervals();
  assert.equal(p.video.paused, true, 'a media pause must not be overridden');
});

test('visible phone recovers an unrequested player pause without a user gesture', () => {
  const p = playerPage();
  p.runIntervals(); // remember actual playing intent
  p.video.pause();  // YouTube or Chromium initiated; no user event or media command
  p.runIntervals();
  assert.equal(p.video.paused, false, 'unrequested page pause should recover in foreground too');
});

test('trusted YouTube Pause is authoritative even with background protection enabled', () => {
  const p = playerPage();
  const nativePauses = [];
  p.window.AAYNative.userPause = () => nativePauses.push(true);
  p.window.__aay.setBg(true);
  p.runIntervals(); // remember that playback was requested
  p.emit('pointerdown', { isTrusted: true, target: { closest: selector => selector.includes('.ytp-play-button') ? {} : null } });
  p.video.pause();
  assert.equal(p.video.paused, true, 'trusted page Pause must bypass the pause interceptor');
  assert.equal(nativePauses.length, 1, 'the native media owner must know this pause was intentional');
  p.flush();
  assert.equal(p.stateReports.at(-1).userPaused, true, 'manual Pause must survive IPC reconnection');
  p.runIntervals();
  assert.equal(p.video.paused, true, 'the recovery watchdog must not undo manual Pause');
});

test('car playback recovers a dropped stream without reviving a user pause', () => {
  const p = playerPage({ car: true });
  p.runIntervals(); // the car's playing video sets watchdog intent
  p.video.pause();
  assert.equal(p.video.paused, true, 'simulate an unexpected car-side playback loss');
  p.runIntervals();
  assert.equal(p.video.paused, false, 'the car player should be retried automatically');
  p.window.__aay.cmd('pause');
  p.runIntervals();
  assert.equal(p.video.paused, true, 'explicit Android Auto Pause always cancels recovery');
});

test('native Play prefers YouTube MediaSession and falls back to HTML video if its handler throws', () => {
  const p = playerPage();
  let received = 0;
  p.navigator.mediaSession.setActionHandler('play', () => {
    received++;
    p.video.paused = false;
  });
  p.video.paused = true;
  p.window.__aay.cmd('play');
  assert.equal(received, 1, 'YouTube registered Play should run first');
  assert.equal(p.video.paused, false);

  p.navigator.mediaSession.setActionHandler('play', () => {
    received++;
    throw new Error('YouTube player not ready');
  });
  p.video.paused = true;
  p.window.__aay.cmd('play');
  assert.equal(received, 2);
  assert.equal(p.video.paused, false, 'HTML video is the fallback after handler failure');
});

test('same-video document reload can recover the position through the native bridge', () => {
  const p = playerPage();
  p.flush();
  p.video.currentTime = 0;
  p.video.readyState = 1;
  p.window.__aay.restoreAt('86p7SP34qSc', 145.5);
  assert.equal(p.video.currentTime, 0, 'give the player time to restore itself');
  p.advance(1100); p.flush();
  assert.ok(p.video.currentTime >= 145, 'fallback restores when the player has not recovered');
  p.video.currentTime = 0;
  p.window.__aay.restoreAt('different-video-id', 220);
  assert.equal(p.video.currentTime, 0, 'never restore a different video ID');
});

test('leaving watch clears stale media identity instead of infinitely recovering a ghost stream', () => {
  const p = playerPage();
  p.flush();
  p.runIntervals();
  assert.equal(p.stateReports.at(-1).id, '86p7SP34qSc');
  p.location.pathname = '/';
  p.emitWindow('yt-navigate-finish', {});
  p.flush();
  assert.equal(p.stateReports.at(-1).id, '');
  assert.equal(p.stateReports.at(-1).playing, false);
  p.video.paused = true;
  p.runIntervals();
  assert.equal(p.video.paused, true, 'watchdog must not restart a stale video on YouTube Home');
  const count = p.stateReports.length;
  p.runIntervals();
  assert.equal(p.stateReports.length, count, 'clear state sent exactly once');
});

test('reopened paused video never automatically resumes after timers', () => {
  const p = playerPage({ paused: true });
  p.video.paused = true;
  p.flush();
  p.video.play();
  p.video.trigger('play');
  assert.equal(p.video.paused, true, 'YouTube autoplay suppressed until user Play');
  p.runIntervals();
  assert.equal(p.video.paused, true, 'recovery watchdog must respect saved Pause');
  p.flush();
  assert.equal(p.stateReports.at(-1).userPaused, true);
  p.window.__aay.cmd('play');
  assert.equal(p.video.paused, false);
});

test('native Pause survives delayed playback with background protection armed', () => {
  const p = playerPage();
  p.flush(); // bind video listeners before simulating YouTube's delayed Play
  p.window.__aay.cmd('pause');
  p.window.__aay.setBg(true);
  p.video.play();
  p.video.trigger('play');
  assert.equal(p.video.paused, true, 'later YouTube play must not undo Pause');
  p.runIntervals();
  assert.equal(p.video.paused, true, 'background watchdog must not undo Pause');
  p.window.__aay.cmd('play');
  assert.equal(p.video.paused, false, 'native Play releases Pause');
});

test('only a trusted player Play gesture releases Pause, not an unrelated click', () => {
  const p = playerPage({ paused: true });
  p.video.paused = true;
  p.flush(); // bind native player events
  p.emit('click', { isTrusted: true, target: { tagName: 'DIV', closest: () => null } });
  p.video.play();
  p.video.trigger('play');
  assert.equal(p.video.paused, true, 'unrelated click is not Play');
  const playButton = { tagName: 'BUTTON', closest(selector) {
    return selector.includes('.ytp-play-button') ? { tagName: 'BUTTON' } : null;
  } };
  p.emit('click', { isTrusted: true, target: playButton });
  p.video.play();
  p.video.trigger('play');
  assert.equal(p.video.paused, false, 'trusted player gesture can Play');
});

test('anonymous center div tap resumes an explicitly paused video', () => {
  const p = playerPage({ paused: true });
  p.video.paused = true;
  p.flush();
  // YouTube renders the center Play overlay as an anonymous div without selectors.
  p.emit('click', {
    isTrusted: true, clientX: 160, clientY: 90,
    target: { tagName: 'DIV', closest: () => null },
  });
  p.video.play();
  p.video.trigger('play');
  assert.equal(p.video.paused, false, 'real video-area Play tap must not be force-paused');
  p.runIntervals();
  assert.equal(p.video.paused, false, 'watchdog also respects resumed play');
});

test('dialog and scrim taps over the video do not release explicit pause', () => {
  for (const event of ['pointerdown', 'click']) {
    const p = playerPage({ paused: true, car: true });
    p.video.paused = true;
    p.flush();
    p.emit(event, {
      isTrusted: true, clientX: 160, clientY: 90,
      target: { tagName: 'DIV', closest: selector => selector.includes('ytw-scrim') ? {} : null },
    });
    p.video.play();
    p.video.trigger('play');
    assert.equal(p.video.paused, true, 'overlay must not permit autoplay');
  }
});

test('pointerdown and click on one Play control do not arm an opposite pause', () => {
  const p = playerPage({ paused: true });
  p.video.paused = true;
  p.flush();
  p.window.__aay.setBg(true);
  const target = { closest: selector => selector.includes('.ytp-play-button') ? {} : null };
  p.emit('pointerdown', { type: 'pointerdown', isTrusted: true, target });
  p.video.play();
  p.video.trigger('play');
  p.emit('click', { type: 'click', isTrusted: true, target });
  p.video.pause();
  assert.equal(p.video.paused, false, 'the duplicate click must not authorize Pause');
});

test('pointerdown alone does not release an explicit pause', () => {
  const p = playerPage({ paused: true, car: true });
  p.video.paused = true;
  p.flush();
  p.emit('pointerdown', {
    isTrusted: true, clientX: 160, clientY: 90,
    target: { tagName: 'DIV', closest: () => null },
  });
  p.video.play();
  p.video.trigger('play');
  assert.equal(p.video.paused, true, 'a drag start must not permit autoplay');
});

test('outside video tap does not override an explicit pause', () => {
  const p = playerPage({ paused: true });
  p.video.paused = true;
  p.flush();
  p.emit('pointerdown', {
    isTrusted: true, clientX: 400, clientY: 350,
    target: { tagName: 'DIV', closest: () => null },
  });
  p.video.play();
  p.video.trigger('play');
  assert.equal(p.video.paused, true, 'unrelated tap must not permit autoplay');
});

test('manual Pause before autoplay video appears cancels the deferred request', () => {
  const opts = { noVideo: true, autoplay: true };
  const p = playerPage(opts);
  p.window.__aay.cmd('pause'); // user wins even before the video element loads
  opts.noVideo = false;
  p.video.paused = true;
  p.flush();
  p.video.trigger('loadedmetadata');
  p.runIntervals();
  assert.equal(p.video.paused, true, 'pending page autoplay must never defeat explicit Pause');
});

test('native Play before video exists starts it after the player becomes available', () => {
  const opts = { noVideo: true };
  const p = playerPage(opts);
  p.window.__aay.cmd('play'); // no HTML video to control yet
  opts.noVideo = false;
  p.video.paused = true;
  p.flush();
  p.video.trigger('loadedmetadata');
  assert.equal(p.video.paused, false, 'deferred Play reaches the late video element');
});

test('mini and PiP recovery never changes accelerated video opacity', () => {
  assert.equal(source.includes("v.style.setProperty('opacity'"), false,
    'opacity changes break hardware video overlay composition');
  assert.match(source, /function repaintVideo\(v\) \{/);
  assert.match(source, /if \(now - refitAt < 6000\) return/);
  const p = playerPage({ quality: true });
  p.window.__aay.mini(true);
  p.flush();
  assert.equal(p.video.paused, false);
  p.window.__aay.pip(true);
  p.flush();
  assert.equal(p.classNames.has('aay-video-only'), true);
});

test('mini player watchdog restarts a frozen picture while sound plays', () => {
  const p = playerPage();
  let frames = 100, seeks = 0, t = 10;
  p.video.paused = false;
  p.video.getVideoPlaybackQuality = () => ({ totalVideoFrames: frames, droppedVideoFrames: 0 });
  Object.defineProperty(p.video, 'currentTime', {
    get: () => t, set: () => { seeks++; }, configurable: true,
  });
  p.window.__aay.mini(true);
  p.runIntervals(); // baseline sample
  t += 2;           // audio clock advances, frame counter does not
  p.runIntervals();
  assert.equal(seeks, 1, 'stalled mini picture is restarted');
  p.window.__aay.mini(false);
  t += 2;
  p.runIntervals();
  assert.equal(seeks, 1, 'watchdog stops when leaving the mini player');
});

test('mini player re-fits a video YouTube moved outside the small window', () => {
  const p = playerPage();
  p.window.__aay.mini(true);
  p.flush();
  p.classNames.delete('aay-video-only');
  p.video.getBoundingClientRect = () => ({ left: 0, top: 132, width: 290, height: 163 });
  p.runIntervals();
  assert.equal(p.classNames.has('aay-video-only'), true, 'small-window layout is reapplied');
});

test('Next rebinds the small video surface without resetting playback or the WebView', () => {
  for (const mode of ['mini', 'pip']) {
    const p = playerPage({ quality: true });
    let activeVideoId = '86p7SP34qSc';
    p.player.getVideoData = () => ({ video_id: activeVideoId });
    p.window.__aay[mode](true);
    p.flush();
    const before = p.resizeEvents;
    p.video.paused = false;
    // YouTube's media-session Next can replace the current stream before the
    // browser URL changes; trust the player identity, not the stale route.
    activeVideoId = 'newTrack987';
    p.runIntervals();
    assert.ok(p.resizeEvents > before, mode + ' track switch re-fits the decoder viewport');
    assert.equal(p.classNames.has('aay-video-only'), true);
    assert.equal(p.video.paused, false, 'do not pause or recreate audio to recover the picture');
    const next = p.resizeEvents;
    p.runIntervals();
    assert.equal(p.resizeEvents, next, 'do not repeatedly resize when track and video are unchanged');
  }
});

test('Next rebinds a replacement video element in mini-player and PiP', () => {
  for (const mode of ['mini', 'pip']) {
    const p = playerPage();
    p.window.__aay[mode](true);
    p.flush();
    p.runIntervals();
    const before = p.resizeEvents;
    const nextVideo = p.replaceVideo();
    p.runIntervals();
    assert.ok(p.resizeEvents > before, mode + ' must refresh a replacement decoder element');
    assert.equal(nextVideo.paused, false, 'new track continues playing without an artificial pause');
    assert.equal(p.classNames.has('aay-video-only'), true);
  }
});

test('retired video events cannot change the active replacement playback state', () => {
  const p = playerPage();
  p.flush();
  const old = p.video;
  const next = p.replaceVideo();
  p.emit('loadstart', { target: next });
  p.flush();
  next.paused = false;
  next.trigger('play');
  p.flush();
  old.paused = true;
  old.ended = true;
  for (const event of ['pause', 'ended', 'loadedmetadata', 'timeupdate', 'emptied']) {
    old.trigger(event);
  }
  p.flush();
  assert.equal(next.paused, false, 'retired events must not pause the replacement');
  assert.equal(p.stateReports.at(-1).playing, true, 'media session must keep the active playback state');
});

test('PiP Next repairs the picture even if Chromium marks the page hidden', () => {
  const p = playerPage({ quality: true });
  let id = '86p7SP34qSc';
  p.player.getVideoData = () => ({ video_id: id });
  p.window.__aay.pip(true);
  p.flush();
  p.runIntervals();
  p.setRealHidden(true);
  id = 'newTrack987';
  const before = p.resizeEvents;
  p.runIntervals();
  assert.ok(p.resizeEvents > before, 'visible PiP cannot lose its surface watchdog');
  assert.equal(p.video.paused, false);
});

test('phone landscape fills the screen with the video without car behaviour', () => {
  const p = playerPage({ dom: watchPageDom() });
  p.window.__aay.land(true);
  assert.ok(p.classNames.has('aay-car'), 'video and controls fill the screen');
  assert.equal(p.window.__aay.isCar, false, 'car gestures, quality and background rules stay off');
  p.window.__aay.mini(true);
  assert.equal(p.classNames.has('aay-car'), false, 'the mini player never uses the fill layout');
  p.window.__aay.mini(false);
  assert.ok(p.classNames.has('aay-car'));
  p.window.__aay.land(false);
  assert.equal(p.classNames.has('aay-car'), false, 'rotating back restores the page');
  p.window.__aay.land(true);
  p.location.pathname = '/';
  p.window.__aay.land(true);
  assert.equal(p.classNames.has('aay-car'), false, 'only watch pages fill the screen');
});

function swipeDown(p, target) {
  const t = target || { closest: () => null };
  p.emit('touchstart', { touches: [{ clientX: 100, clientY: 50 }], target: t });
  p.emit('touchend', { changedTouches: [{ clientX: 105, clientY: 200 }], target: t });
}

test('landscape fill: swipe down anywhere asks native for the watch page step', () => {
  const p = playerPage({ dom: watchPageDom() });
  p.window.__aay.land(true);
  const before = p.miniRequests;
  swipeDown(p); // controls overlay target, not inside the video wrappers
  assert.equal(p.miniRequests, before + 1);
});

test('YouTube fullscreen: swipe down exits fullscreen instead of opening the mini player', () => {
  const p = playerPage();
  let exits = 0;
  p.document.fullscreenElement = {};
  p.document.exitFullscreen = () => { exits++; };
  const before = p.miniRequests;
  swipeDown(p);
  assert.equal(exits, 1);
  assert.equal(p.miniRequests, before, 'the watch page is shown first, like the YouTube app');
});

test('YouTube shown as paused while the video plays is told to play (centre button)', () => {
  const p = playerPage();
  let state = 2, plays = 0;
  const player = p.document.getElementById('movie_player') || {};
  player.getPlayerState = () => state;
  player.playVideo = () => { plays++; state = 1; };
  p.document.getElementById = (id) => (id === 'movie_player' ? player : null);
  p.video.paused = false;
  p.video.ended = false;
  p.video.readyState = 4;
  p.window.__aay.mini(true);
  p.runIntervals();
  assert.equal(plays, 1, 'player state resynced');
  state = 2;
  p.runIntervals();
  assert.equal(plays, 1, 'throttled');
  p.video.paused = true;
  p.window.__aay.mini(false);
});


test('blocked pause storms cannot reset player reconciliation cooldown', () => {
  const p = playerPage({ quality: true });
  p.flush();
  const player = p.document.getElementById('movie_player');
  let repairs = 0;
  player.getPlayerState = () => 2;
  player.playVideo = () => { repairs++; p.video.pause(); };
  p.window.__aay.mini(true);
  p.runIntervals();
  assert.equal(repairs, 1);
  for (let i = 0; i < 40; i++) {
    p.video.pause();
    p.advance(250);
    p.flush();
    p.runIntervals();
  }
  assert.equal(repairs, 1, 'blocked pauses cannot cause a Play repair storm');
  assert.equal(p.video.paused, false, 'audio continues playing');
  p.advance(2000);
  p.runIntervals();
  assert.equal(repairs, 2, 'repair remains available after cooldown');
});

test('phone center Play resumes after a previous explicit native Pause', () => {
  const p = playerPage();
  p.flush();
  p.runIntervals();
  p.window.__aay.cmd('pause');
  assert.equal(p.video.paused, true, 'a real native pause remains in effect');
  // The phone YouTube center overlay can be a generic div within #movie_player.
  // It must count as an explicit resume gesture when the video is paused.
  p.emit('pointerdown', {
    isTrusted: true,
    target: { closest: selector => selector.includes('#movie_player') ? {} : null },
  });
  p.video.play();
  p.video.trigger('play');
  assert.equal(p.video.paused, false, 'a trusted Play must not immediately be re-paused');
  p.runIntervals();
  assert.equal(p.video.paused, false, 'recovery watchdog must honor the resumed playback');
});

test('automatic codec selection never forces H.264, VP9 or AV1', () => {
  const VP9 = 'video/webm; codecs="vp09.00.40.08"';
  const AV1 = 'video/mp4; codecs="av01.0.08M.08"';
  const AVC = 'video/mp4; codecs="avc1.4d401f"';
  for (const old of ['h264', 'vp9', 'av1']) {
    const p = playerPage({ cfg: { videoCodec: old } });
    assert.equal(p.MediaSource.isTypeSupported(VP9), true);
    assert.equal(p.MediaSource.isTypeSupported(AV1), true);
    assert.equal(p.MediaSource.isTypeSupported(AVC), true);
    assert.equal(p.qualityStorage.get('yt-player-av1-pref'), undefined);
  }
});


test('8K stays available through the codec whose hardware decodes it', () => {
  const sized = (codec, w, h) => codec + '; width=' + w + '; height=' + h + '; framerate=30';
  const VP9 = 'video/webm; codecs="vp09.00.51.08"', AV1 = 'video/mp4; codecs="av01.0.16M.08"';
  const AVC = 'video/mp4; codecs="avc1.640033"';
  // Like this phone: VP9 hardware to 4K, AV1 hardware to 8K, H.264 to 4K.
  const p = playerPage({ cfg: { decodeCaps: { vp9: 2160, av1: 4320, h264: 2160 } } });
  const ms = p.MediaSource;
  assert.equal(ms.isTypeSupported(sized(VP9, 7680, 4320)), false, '8K VP9 froze: hidden');
  assert.equal(ms.isTypeSupported(sized(AV1, 7680, 4320)), true, '8K offered through AV1');
  assert.equal(ms.isTypeSupported(sized(VP9, 3840, 2160)), true);
  assert.equal(ms.isTypeSupported(sized(AVC, 1920, 1080)), true);
  assert.equal(ms.isTypeSupported(sized(VP9, 2160, 3840)), true, 'vertical 4K counts by its short side');
  assert.equal(ms.isTypeSupported(VP9), true, 'type without size is never blocked');
  assert.equal(ms.isTypeSupported('audio/webm; codecs="opus"'), true);

  // No AV1 hardware: software AV1 is hidden where H.264/VP9 hardware can decode.
  const sw = playerPage({ cfg: { decodeCaps: { vp9: 2160, av1: 0, h264: 1080 } } });
  assert.equal(sw.MediaSource.isTypeSupported(sized(AV1, 1920, 1080)), false);
  assert.equal(sw.MediaSource.isTypeSupported(sized(AV1, 2560, 1440)), false);
  assert.equal(sw.MediaSource.isTypeSupported(sized(VP9, 7680, 4320)), false, 'no 8K at all here');
  assert.equal(sw.MediaSource.isTypeSupported(sized(AVC, 1920, 1080)), true);
});

test('software VP9 and AV1 are blocked when a hardware fallback exists at the same size', () => {
  const vp9 = 'video/webm; codecs="vp09.00.40.08"';
  const av1 = 'video/mp4; codecs="av01.0.08M.08"';
  const avc = 'video/mp4; codecs="avc1.4d401f"';
  const sized = (codec, w, h, fps = 30) => codec + '; width=' + w + '; height=' + h + '; framerate=' + fps;
  const older = playerPage({ cfg: { decodeCaps: { vp9: 0, av1: 0, h264: 1080,
    vp9_60: 0, av1_60: 0, h264_60: 720 } } });
  assert.equal(older.MediaSource.isTypeSupported(sized(vp9, 1920, 1080)), false);
  assert.equal(older.MediaSource.isTypeSupported(sized(av1, 1920, 1080)), false);
  assert.equal(older.MediaSource.isTypeSupported(sized(avc, 1920, 1080)), true);
  assert.equal(older.MediaSource.isTypeSupported(sized(vp9, 1280, 720, 60)), false);
  assert.equal(older.MediaSource.isTypeSupported(sized(avc, 1280, 720, 60)), true);
  assert.equal(older.MediaSource.isTypeSupported(vp9), false, 'MIME-only VP9 must not offer software when H.264 hardware exists');
  assert.equal(older.MediaSource.isTypeSupported(av1), false, 'MIME-only AV1 must also avoid software');
  const softwareOnly = playerPage({ cfg: { decodeCaps: { vp9: 0, av1: 0, h264: 0 } } });
  assert.equal(softwareOnly.MediaSource.isTypeSupported(vp9), true, 'MIME-only software fallback remains when no hardware available');
  assert.equal(softwareOnly.MediaSource.isTypeSupported(sized(vp9, 1280, 720)), true,
    'software fallback allowed when no hardware codec covers the size');
  assert.equal(softwareOnly.MediaSource.isTypeSupported(sized(vp9, 1920, 1080)), true);
  assert.equal(softwareOnly.MediaSource.isTypeSupported(sized(vp9, 2560, 1440)), false);
});

test('Auto retains adaptive quality under thermal and mini or PiP ceilings', () => {
  const p = playerPage({ quality: true, cfg: { preferredQuality: 'auto' } });
  p.flush();
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'highres']);
  p.window.__aay.thermal(3);
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'hd1080']);
  p.window.__aay.mini(true);
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'hd720']);
  p.window.__aay.mini(false);
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'hd1080']);
  p.window.__aay.thermal(0);
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'highres']);
  p.window.__aay.pip(true);
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'hd720']);
  p.window.__aay.pip(false);
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'highres']);
});

test('captured media loadstart binds a new video immediately without DOM ticks', () => {
  const p = playerPage({ noVideo: true });
  const next = new p.video.constructor();
  next.tagName = 'VIDEO';
  assert.equal(next.events.has('play'), false);
  p.emit('loadstart', { target: next });
  assert.equal(next.events.has('play'), true);
  assert.equal(next.events.has('ended'), true);
  const before = next.events.get('play').length;
  p.emit('emptied', { target: next });
  assert.equal(next.events.get('play').length, before, 'same video never double binds');
  p.emit('play', { target: next });
  next.trigger('play');
  assert.ok(p.stateReports.length > 0 || next.events.has('pause'), 'new video is ready to report state');
});

test('settings button anchors quality choice when YouTube uses unknown sheet markup', () => {
  const p = playerPage({ quality: true, currentQuality: 'hd720' });
  p.flush();
  const button = { tagName: 'BUTTON', textContent: '',
    closest(sel) { return sel.includes('player-settings-icon') ? this : null; } };
  const unmarked = label => ({ tagName: 'DIV', textContent: label, parentElement: null,
    closest: () => null, getAttribute: () => null });
  p.emit('click', { isTrusted: true, target: unmarked('2160p') });
  assert.equal(p.selectedQualities.length, 0, 'arbitrary resolution text cannot save quality');
  p.emit('click', { isTrusted: true, target: button });
  assert.equal(p.selectedQualities.length, 0, 'settings button is not itself a quality selection');
  p.emit('click', { isTrusted: true, target: unmarked('2160p') });
  assert.equal(p.selectedQualities.at(-1), 'hd2160');
  assert.equal(JSON.parse(p.qualityStorage.get('yt-player-quality')).data, 'hd2160');
  p.emit('click', { isTrusted: true, target: unmarked('1080p') });
  assert.equal(p.selectedQualities.at(-1), 'hd2160', 'settings tap window ends after selection');
});

function overloadPage(quality, cfg) {
  const p = playerPage({ quality: true, currentQuality: quality, cfg: cfg || {} });
  p.window.__aay.config && p.window.__aay.config({ preferredQuality: quality });
  p.flush();
  return p;
}
function badWindow(p, kind) {
  if (kind === 'stall') { p.video.buffered = { length: 1, end: () => p.video.currentTime + 60 }; }
  p.advance(9000);
  p.runIntervals();
}

test('overload: a sustained decoder stall steps down one tier; one bad window does not', () => {
  const p = overloadPage('hd2160');
  p.video.buffered = { length: 1, end: () => 80 };
  p.runIntervals();                       // first sample
  badWindow(p, 'stall');
  assert.notDeepEqual(p.qualityCalls.at(-1), ['hd1440', 'hd1440'], 'isolated spike ignored');
  badWindow(p, 'stall');
  assert.deepEqual(p.qualityCalls.at(-1), ['hd1440', 'hd1440'], 'sustained: 4K -> 1440p');
  assert.equal(p.qualityStorage.get('aay-max-smooth-h'), '1440', 'remembered without native caps');
});

test('overload: buffering from the network (nothing buffered) never lowers quality', () => {
  const p = overloadPage('hd2160');
  p.video.buffered = { length: 1, end: () => p.video.currentTime + 0.5 };
  p.runIntervals();
  for (let i = 0; i < 4; i++) { p.advance(9000); p.runIntervals(); }
  assert.deepEqual(p.qualityCalls.at(-1), ['hd2160', 'hd2160']);
});

test('overload: sustained frame dropping steps down gradually with a cooldown, floor 720p', () => {
  const p = overloadPage('hd2160');
  let f = 0, d = 0;
  p.video.getVideoPlaybackQuality = () => ({ totalVideoFrames: f, droppedVideoFrames: d });
  const drop = () => { f += 300; d += 240; p.video.currentTime += 9; p.advance(9000); p.runIntervals(); };
  p.runIntervals();
  drop(); drop();
  assert.deepEqual(p.qualityCalls.at(-1), ['hd1440', 'hd1440']);
  p.player.setCurrentQuality('hd1440');
  drop(); drop();
  assert.deepEqual(p.qualityCalls.at(-1), ['hd1440', 'hd1440'], 'cooldown: no second step within 60 s');
  for (let i = 0; i < 6; i++) drop();
  assert.deepEqual(p.qualityCalls.at(-1), ['hd1080', 'hd1080'], 'then the next tier');
  p.player.setCurrentQuality('hd720');
  for (let i = 0; i < 20; i++) drop();
  assert.notDeepEqual(p.qualityCalls.at(-1), ['medium', 'medium'], 'never below 720p');
});

test('overload: stable playback raises the limit again; preference untouched', () => {
  const p = overloadPage('hd2160', { decodeCaps: { vp9: 4320, av1: 4320, h264: 2160 } });
  let f = 0, d = 0;
  p.video.getVideoPlaybackQuality = () => ({ totalVideoFrames: f, droppedVideoFrames: d });
  p.runIntervals();
  for (let i = 0; i < 2; i++) { f += 300; d += 240; p.video.currentTime += 9; p.advance(9000); p.runIntervals(); }
  assert.deepEqual(p.qualityCalls.at(-1), ['hd1440', 'hd1440']);
  assert.equal(p.qualityStorage.has('aay-max-smooth-h'), false, 'native caps: session only');
  p.player.setCurrentQuality('hd1440');
  for (let i = 0; i < 25; i++) { f += 300; p.video.currentTime += 9; p.advance(9000); p.runIntervals(); }
  assert.deepEqual(p.qualityCalls.at(-1), ['hd2160', 'hd2160'], 'back to the saved 4K after 3 min clean');
});

test('thermal: severe heat caps at 1080p and cooling restores the saved quality', () => {
  const p = overloadPage('hd2160');
  assert.deepEqual(p.qualityCalls.at(-1), ['hd2160', 'hd2160']);
  p.window.__aay.thermal(3);
  assert.deepEqual(p.qualityCalls.at(-1), ['hd1080', 'hd1080']);
  p.window.__aay.thermal(1);
  assert.deepEqual(p.qualityCalls.at(-1), ['hd2160', 'hd2160']);
  const hot = overloadPage('hd2160', { thermal: 4 });
  assert.deepEqual(hot.qualityCalls.at(-1), ['hd1080', 'hd1080'], 'already hot at page start');
});

test('decoder gate: 60 fps and HDR use their own hardware limits', () => {
  const ty = (c, w, h, fps) => c + '; width=' + w + '; height=' + h + '; framerate=' + fps;
  const VP9 = 'video/webm; codecs="vp09.00.51.08"', VP9HDR = 'video/webm; codecs="vp09.02.51.10.01.09.16.09.00"';
  const AV1 = 'video/mp4; codecs="av01.0.16M.08"', AV1HDR = 'video/mp4; codecs="av01.0.16M.10.0.110.09.16.09.0"';
  const p = playerPage({ cfg: { decodeCaps: { vp9: 2160, vp9_60: 1440, vp9_hdr: 0, av1: 4320, av1_60: 2160, av1_hdr: 1, h264: 1080, h264_60: 1080 } } });
  const ms = p.MediaSource;
  assert.equal(ms.isTypeSupported(ty(VP9, 3840, 2160, 30)), true);
  assert.equal(ms.isTypeSupported(ty(VP9, 3840, 2160, 60)), false, '4K60 VP9 above its 60 fps limit');
  assert.equal(ms.isTypeSupported(ty(AV1, 7680, 4320, 30)), true, '8K30 AV1 kept');
  assert.equal(ms.isTypeSupported(ty(AV1, 7680, 4320, 60)), false, '8K60 AV1 above its 60 fps limit');
  assert.equal(ms.isTypeSupported(ty(AV1, 3840, 2160, 60)), true);
  assert.equal(ms.isTypeSupported(ty(VP9HDR, 1920, 1080, 30)), false, 'no 10-bit VP9 decoder: HDR VP9 hidden');
  assert.equal(ms.isTypeSupported(ty(AV1HDR, 3840, 2160, 30)), true, 'HDR through AV1 instead');
});

test('hidden page sends progress less often but play/pause changes at once', () => {
  const p = playerPage();
  p.flush();
  p.window.__aay.setBg(true);
  p.video.trigger('timeupdate'); // first heartbeat
  p.flush();
  const n = p.stateReports.length;
  for (let i = 0; i < 4; i++) { p.advance(1500); p.video.currentTime += 1.5; p.video.trigger('timeupdate'); p.flush(); }
  assert.equal(p.stateReports.length, n, 'no 1.4 s heartbeat while hidden');
  p.advance(10000); p.video.trigger('timeupdate'); p.flush();
  assert.equal(p.stateReports.length, n + 1, '10 s heartbeat');
  p.video.paused = true; p.video.trigger('timeupdate');
  assert.equal(p.stateReports.at(-1).playing, false, 'state change is immediate');
});

function sheetOption(label) {
  const sheet = { tagName: 'DIV', textContent: 'Quality for current video ' + label, parentElement: null,
    matches: (sel) => sel.includes('#bottom-sheet'), getAttribute: () => null };
  const row = { tagName: 'DIV', textContent: label, parentElement: sheet, matches: () => false, getAttribute: () => null };
  const text = { tagName: 'DIV', textContent: label, parentElement: row, matches: () => false, getAttribute: () => null,
    closest(sel) { if (sel.includes('#bottom-sheet')) return sheet; return null; } };
  return text;
}

test('quality pick in the new mobile bottom sheet (plain divs) is saved', () => {
  const p = playerPage({ quality: true });
  p.flush();
  p.emit('click', { target: sheetOption('2160p (4K)') });
  p.flush();
  assert.deepEqual(p.selectedQualities.at(-1), 'hd2160');
});

test('unidentified quality change after a tap never overwrites the saved maximum', () => {
  const p = playerPage({ quality: true, currentQuality: 'hd720' });
  p.flush();
  p.advance(4000);                        // well after our own last range change
  const before = p.qualityCalls.length;
  // An unrecognised quality-sheet row still identifies a deliberate menu tap.
  p.emit('click', { isTrusted: true, target: sheetOption('Higher picture quality') });
  assert.equal(p.window.__aay.qualityTapVideo, '86p7SP34qSc');
  p.player.setCurrentQuality('hd2160');   // YouTube switched after the user's sheet tap
  p.emit('emptied', { target: p.video });   // capture listeners on document
  p.emit('loadstart', { target: p.video });
  assert.equal(p.qualityCalls.length, before, 'do not interrupt an in-progress quality switch');
  p.flush();
  assert.equal(p.selectedQualities.length, 0, 'lower or higher observed tier is not explicit user intent');
  assert.equal(p.window.__aay.__cfg().preferredQuality || 'hd720', 'hd720',
    'only an exact menu selection is allowed to change the saved maximum');
});

test('unrelated taps never save adaptive quality changes', () => {
  const p = playerPage({ quality: true, currentQuality: 'hd720' });
  p.flush();
  p.advance(4000);
  const n = p.selectedQualities.length;
  p.emit('click', { isTrusted: true, target: { tagName: 'DIV', textContent: 'random video tap',
    getAttribute: () => null, closest: () => null } });
  p.player.setCurrentQuality('hd2160');
  p.emit('emptied', { target: p.video });
  p.flush();
  assert.equal(p.selectedQualities.length, n, 'adaptive quality change is not a saved manual choice');
  assert.deepEqual(p.qualityCalls.at(-1), ['hd720', 'hd720'], 'saved resolution is restored');
});

test('same-video reload WITHOUT a recent tap still restores the saved quality', () => {
  const p = playerPage({ quality: true, currentQuality: 'hd2160' });
  p.flush();
  p.advance(10000);
  p.emit('emptied', { target: p.video });
  assert.deepEqual(p.qualityCalls.at(-1), ['hd720', 'hd720'], 'YouTube picked 4K on its own: saved 720p restored');
});

test('YouTube writing its own saved pick is adopted; our own writes are not', () => {
  const p = playerPage({ quality: true });
  p.flush();
  const n = p.selectedQualities.length;
  p.window.__aay.config && p.window.__aay.config({ preferredQuality: 'hd720' });
  assert.equal(p.selectedQualities.length, n, 'own config write ignored');
  if (typeof p.window.Storage === 'function' && p.window.localStorage instanceof p.window.Storage) {
    p.window.localStorage.setItem('yt-player-quality', JSON.stringify({ data: JSON.stringify({ quality: 1440, previousQuality: 720 }) }));
    p.flush();
    assert.equal(p.selectedQualities.at(-1), 'hd1440');
  }
});

test('menu labels with suffixes parse', () => {
  const p = playerPage({ quality: true });
  p.flush();
  const opt = (label) => ({ tagName: 'BUTTON', textContent: label, getAttribute: () => null,
    closest(sel) { return sel.includes('[role="option"]') ? this : null; } });
  p.emit('click', { target: opt('2160p60 HDR Premium') });
  assert.equal(p.selectedQualities.at(-1), 'hd2160');
  p.emit('click', { target: opt('Higher picture quality') });
  assert.equal(p.selectedQualities.at(-1), 'hd2160', 'preset rows are not tiers');
});

test('navigation diagnostics redact queries, video identifiers and fragments', () => {
  const start = source.indexOf('  function logRoute(url) {');
  const end = source.indexOf('\n  }', start) + 4;
  assert.ok(start >= 0 && end > start, 'route-only log formatter exists');
  const sandbox = { URL, location: { href: 'https://m.youtube.com/watch?v=secret' } };
  vm.runInNewContext(source.slice(start, end), sandbox);
  assert.equal(sandbox.logRoute('https://m.youtube.com/watch?v=private-id&token=secret#frag'),
    'https://m.youtube.com/watch');
  assert.equal(sandbox.logRoute('/shorts/private-id?si=secret'), 'https://m.youtube.com/shorts');
  assert.equal(sandbox.logRoute('/results?search_query=private+search'), 'https://m.youtube.com/results');
  assert.equal(sandbox.logRoute(null), '[none]');
  assert.match(source, /opening route: ' \+ logRoute\(href\)/);
  assert.match(source, /page change ' \+ logRoute\(url\)/);
  assert.match(source, /link ' \+ logRoute\(a\.getAttribute\('href'\)\)/);
});


test('auto quality obeys thermal cap and returns to adaptive when cooled', () => {
  const p = playerPage({ quality: true, currentQuality: 'hd2160' });
  p.window.__aay.config({ preferredQuality: 'auto' });
  p.flush();
  p.window.__aay.thermal(3);
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'hd1080'], 'Auto may adapt below thermal cap');
  p.window.__aay.thermal(0);
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'highres'], 'cooling restores the full adaptive quality range');
});

test('performance diagnostics aggregate playback counters without personal data', () => {
  const p = playerPage({ quality: true, currentQuality: 'hd720' });
  p.flush();
  p.window.__aay.mini(true);
  p.window.__aay.mini(true); // duplicate must not resize the video surface again
  p.emit('loadstart', { target: p.video });
  p.advance(30000);
  p.runIntervals();
  assert.equal(p.perfReports.length, 1);
  const perf = p.perfReports[0];
  assert.equal(perf.sec, 30);
  assert.equal(perf.layout, 1, 'duplicate mini mode does not trigger surface re-layout');
  assert.equal(perf.sourceLoads, 1);
  assert.equal(perf.width, 1920);
  assert.equal(perf.height, 1080);
  assert.equal(perf.tier, 'hd720');
  assert.equal('videoId' in perf, false);
  assert.equal('url' in perf, false);
  assert.equal('rendererCpuPct' in perf, false, 'JavaScript cannot claim renderer CPU measurements');
});

test('paused video produces no periodic performance bridge reports', () => {
  const p = playerPage({ quality: true });
  p.video.paused = true;
  p.advance(30000);
  p.runIntervals();
  assert.equal(p.perfReports.length, 0);
});


test('saved 1080p is a maximum, a 720p-only video plays 720p and next 1080p video restores it', () => {
  const p = playerPage({ quality: true, cfg: { preferredQuality: 'hd1080' } });
  p.player.getAvailableQualityLevels = () => ['hd720', 'large', 'medium', 'small'];
  p.window.__aay.applyQuality();
  p.flush();
  assert.deepEqual(p.qualityCalls.at(-1), ['hd720', 'hd720']);
  assert.equal(p.window.__aay.__cfg().preferredQuality, 'hd1080');
  assert.equal(p.selectedQualities.length, 0, 'lower offered resolution is never adopted as default');
  p.player.getAvailableQualityLevels = () => ['hd1080', 'hd720', 'medium'];
  p.location.search = '?v=anotherVideo';
  p.window.__aay.applyQuality();
  assert.deepEqual(p.qualityCalls.at(-1), ['hd1080', 'hd1080']);
  assert.equal(p.window.__aay.__cfg().preferredQuality, 'hd1080');
});

test('2160p preference remains the maximum after a lower-only video and PiP cap', () => {
  const p = playerPage({ quality: true, cfg: { preferredQuality: 'hd2160' } });
  p.player.getAvailableQualityLevels = () => ['hd720', 'large', 'medium'];
  p.window.__aay.applyQuality();
  assert.deepEqual(p.qualityCalls.at(-1), ['hd720', 'hd720']);
  p.window.__aay.pip(true);
  assert.equal(p.window.__aay.__cfg().preferredQuality, 'hd2160');
  p.window.__aay.pip(false);
  p.player.getAvailableQualityLevels = () => ['hd2160', 'hd1080', 'hd720'];
  p.location.search = '?v=another4KVideo';
  p.window.__aay.applyQuality();
  assert.deepEqual(p.qualityCalls.at(-1), ['hd2160', 'hd2160']);
  assert.equal(p.selectedQualities.length, 0);
});

test('a stale YouTube Play button pauses genuinely playing video and respects user intent', () => {
  const p = playerPage({ quality: true });
  p.flush();
  p.player.getPlayerState = () => 2; // YouTube thinks paused; media still playing
  const paused = [];
  p.window.AAYNative.userPause = () => paused.push('pause');
  const target = { tagName: 'BUTTON', closest(sel) {
    return sel.includes('.ytp-play-button') ? this : null;
  } };
  const calls = [];
  const e = { target, isTrusted: true,
    preventDefault() { calls.push('prevent'); },
    stopImmediatePropagation() { calls.push('stop'); } };
  p.emit('click', e);
  assert.equal(p.video.paused, true);
  assert.deepEqual(calls, ['prevent', 'stop']);
  assert.equal(paused.length, 1);
  p.runIntervals();
  assert.equal(p.video.paused, true, 'watchdog never undoes explicit Pause');
});

test('ordinary player pause gesture is not swallowed when player state is consistent', () => {
  const p = playerPage({ quality: true });
  p.flush();
  p.player.getPlayerState = () => 1; // playing
  const target = { tagName: 'BUTTON', closest(sel) {
    return sel.includes('.ytp-play-button') ? this : null;
  } };
  let swallowed = false;
  p.emit('click', { target, isTrusted: true,
    preventDefault() { swallowed = true; },
    stopImmediatePropagation() { swallowed = true; } });
  assert.equal(swallowed, false);
});

test('expanding a playing mini player resumes a pause caused by surface resize', () => {
  const p = playerPage();
  p.flush();
  p.window.__aay.mini(true);
  p.flush();
  assert.equal(p.video.paused, false);
  p.window.__aay.mini(false); // intent recorded while still playing
  p.video.paused = true;      // a Chromium resize interrupted playback
  p.flush();
  assert.equal(p.video.paused, false, 'video must continue after mini expands');
  assert.equal(p.window.__aay.isMini, false);
});

test('expanding a user-paused mini player must not autoplay', () => {
  const p = playerPage();
  p.flush();
  p.window.__aay.mini(true);
  p.window.__aay.cmd('pause');
  assert.equal(p.video.paused, true);
  p.window.__aay.mini(false);
  p.flush();
  assert.equal(p.video.paused, true, 'explicit Pause is authoritative');
});

test('user Pause after mini expansion cancels deferred recovery', () => {
  const p = playerPage();
  p.flush();
  p.window.__aay.mini(true);
  p.window.__aay.mini(false);
  p.window.__aay.cmd('pause');
  p.flush();
  assert.equal(p.video.paused, true);
});

test('mini expansion respects other-media audio focus hold', () => {
  const p = playerPage();
  p.flush();
  p.window.__aay.mini(true);
  p.window.__aay.mini(false);
  p.window.__aay.mediaHold(true);
  p.video.paused = true;
  p.flush();
  assert.equal(p.video.paused, true, 'do not steal focus from another app');
});

test('re-entering mini cancels outdated full-player recovery callbacks', () => {
  const p = playerPage();
  p.flush();
  p.window.__aay.mini(true);
  p.window.__aay.mini(false);
  p.window.__aay.mini(true);
  p.video.paused = true;
  p.flush();
  assert.equal(p.video.paused, true, 'an old expand callback must not restart mini playback');
});

test('tapping a playlist video auto-plays the newly navigated watch item', () => {
  const p = playerPage();
  p.flush();
  p.window.__aay.cmd('pause'); // previous video had been paused deliberately
  assert.equal(p.video.paused, true);
  const link = { href: 'https://m.youtube.com/watch?v=newPlaylistVideo&list=PLtest',
    getAttribute() { return '/watch?v=newPlaylistVideo&list=PLtest'; } };
  p.emit('click', { isTrusted: true, target: {
    tagName: 'A',
    closest(selector) {
      if (selector === 'a[href]') return link;
      if (selector.includes('ytm-playlist-panel-video-renderer')) return {};
      return null;
    },
  } });
  // A click alone must not restart the previously paused video.
  assert.equal(p.video.paused, true);
  p.location.search = '?v=newPlaylistVideo&list=PLtest';
  p.location.href = 'https://m.youtube.com/watch?v=newPlaylistVideo&list=PLtest';
  p.emitWindow('yt-navigate-finish', {});
  p.video.trigger('loadedmetadata');
  assert.equal(p.video.paused, false, 'selected playlist video must play without another tap');
});

test('playlist selection does not override Pause pressed while next track loads', () => {
  const p = playerPage();
  p.flush();
  const link = { href: 'https://m.youtube.com/watch?v=nextPlaylistItem',
    getAttribute() { return '/watch?v=nextPlaylistItem'; } };
  p.emit('click', { isTrusted: true, target: {
    tagName: 'A', closest(selector) {
      return selector === 'a[href]' ? link : null;
    },
  } });
  p.window.__aay.cmd('pause');
  p.location.search = '?v=nextPlaylistItem';
  p.emitWindow('yt-navigate-finish', {});
  p.video.trigger('loadedmetadata');
  p.flush();
  assert.equal(p.video.paused, true, 'explicit Pause cancels pending playlist autoplay');
});

test('an unrelated tap or same-video navigation never starts a paused video', () => {
  const p = playerPage();
  p.flush();
  p.window.__aay.cmd('pause');
  p.emit('click', { isTrusted: true, target: { tagName: 'DIV', closest: () => null } });
  p.emitWindow('yt-navigate-finish', {});
  p.video.trigger('loadedmetadata');
  assert.equal(p.video.paused, true);
  const link = { href: p.location.href, getAttribute() { return '/watch?v=86p7SP34qSc'; } };
  p.emit('click', { isTrusted: true, target: { tagName: 'A',
    closest(selector) { return selector === 'a[href]' ? link : null; },
  } });
  p.emitWindow('yt-navigate-finish', {});
  assert.equal(p.video.paused, true, 'reselecting same route is not a new video');
});

test('document restore does not force a second seek when YouTube restores the timestamp', () => {
  const p = playerPage(); p.flush();
  p.video.currentTime = 0; p.video.readyState = 1;
  p.window.__aay.restoreAt('86p7SP34qSc', 145.5);
  p.video.currentTime = 146;
  p.advance(1100); p.flush();
  assert.equal(p.video.currentTime, 146);
});

test('adaptive performance level sets primary quality ceilings and keeps Auto adaptive', () => {
  const p = playerPage({ quality: true, cfg: { preferredQuality: 'auto' } });
  p.flush();
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'highres']);
  const calls = p.qualityCalls.length;
  p.window.__aay.perfLevel(1);
  assert.equal(p.qualityCalls.length, calls, 'elevated changes no quality');
  p.window.__aay.perfLevel(2);
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'hd1080']);
  p.window.__aay.perfLevel(3);
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'hd720']);
  const severe = p.qualityCalls.length;
  p.window.__aay.perfLevel(3);
  assert.equal(p.qualityCalls.length, severe, 'repeated level never switches quality again');
  p.window.__aay.perfLevel(2);
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'hd1080']);
  p.window.__aay.perfLevel(0);
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'highres']);
});

test('adaptive ceiling never raises a lower saved quality', () => {
  const p = playerPage({ quality: true, cfg: { preferredQuality: 'large' } });
  p.flush();
  const before = p.qualityCalls.at(-1);
  p.window.__aay.perfLevel(3);
  assert.deepEqual(p.qualityCalls.at(-1), before);
});

test('page mutation observer is suspended while hidden and restored when visible', () => {
  const p = playerPage();
  p.flush();
  const page = p.observers.find((o) => o.target === p.document.documentElement);
  assert.ok(page, 'document observer exists');
  assert.equal(page.active, true);
  p.window.__aay.setBg(true);
  assert.equal(page.active, false, 'hidden page builds no mutation records');
  assert.equal(p.window.__aay.pageObserved(), false);
  p.window.__aay.setBg(false);
  assert.equal(page.active, true);
  assert.equal(p.window.__aay.pageObserved(), true);
});

test('unchanged state is not sent across the bridge again within 15 s', () => {
  const p = playerPage();
  p.flush();
  p.video.paused = true;
  p.video.trigger('pause');
  p.flush();
  const sent = p.stateReports.length;
  p.video.trigger('pause');
  p.flush();
  assert.equal(p.stateReports.length, sent, 'duplicate state suppressed');
  p.advance(16000);
  p.video.trigger('pause');
  p.flush();
  assert.equal(p.stateReports.length, sent + 1, 'heartbeat after the repeat window');
  p.video.paused = false;
  p.video.trigger('play');
  p.flush();
  assert.equal(p.stateReports.at(-1).playing, true, 'a change is sent at once');
});

test('diagnostics report decoded, presented frames and stall estimates', () => {
  const p = playerPage();
  p.flush();
  p.advance(31000);
  p.runIntervals();
  const r = p.perfReports.at(-1);
  assert.ok(r, 'a playing page reports a diagnostics window');
  for (const key of ['decoded', 'presentedEst', 'displayed', 'bufferEvents', 'bufferMs', 'clockStallMs', 'dupReports', 'observed', 'level'])
    assert.ok(key in r, key);
  assert.ok(r.presentedEst <= r.decoded);
});

test('a stalled lyrics clock is sampled once, not every 250 ms', () => {
  const p = playerPage();
  p.window.__aay.lyricsSync(true);
  p.video.readyState = 2;
  p.runIntervals();
  const n = p.lyricReports.length;
  p.runIntervals();
  p.runIntervals();
  assert.equal(p.lyricReports.length, n, 'unchanged buffering sample is not resent');
  p.video.readyState = 4;
  p.runIntervals();
  assert.equal(p.lyricReports.length, n + 1, 'moving clock reports again');
});

test('brief mini/full toggles never switch quality; a sustained mini player decodes for its size', () => {
  const p = playerPage({ quality: true });
  p.flush();
  assert.deepEqual(p.qualityCalls.at(-1), ['hd720', 'hd720']);
  const n = p.qualityCalls.length;
  // Brief: open for 5 s, ticks run, back to full.
  p.window.__aay.mini(true);
  p.advance(5000);
  p.emitWindow('yt-navigate-finish');
  p.flush();
  p.window.__aay.mini(false);
  p.flush();
  assert.equal(p.qualityCalls.length, n, 'brief toggle: no quality switch');
  assert.equal(p.window.__aay.miniSized(), '');
  // Sustained: after 20 s the fake 320x180 player gets 240p.
  p.window.__aay.mini(true);
  p.advance(21000);
  p.emitWindow('yt-navigate-finish');
  p.flush();
  assert.equal(p.window.__aay.miniSized(), 'small');
  assert.deepEqual(p.qualityCalls.at(-1), ['small', 'small']);
  const sized = p.qualityCalls.length;
  p.emitWindow('yt-navigate-finish');
  p.flush();
  assert.equal(p.qualityCalls.length, sized, 'applied once, not on every tick');
  // Back to full: the saved quality returns.
  p.window.__aay.mini(false);
  p.flush();
  assert.deepEqual(p.qualityCalls.at(-1), ['hd720', 'hd720']);
  assert.equal(p.window.__aay.miniSized(), '');
});

test('sustained mini sizing is skipped in PiP and on the car', () => {
  const p = playerPage({ quality: true });
  p.flush();
  p.window.__aay.mini(true);
  p.window.__aay.pip(true);
  p.advance(25000);
  p.emitWindow('yt-navigate-finish');
  p.flush();
  assert.equal(p.window.__aay.miniSized(), '');
  assert.notDeepEqual(p.qualityCalls.at(-1), ['small', 'small']);
});

test('diagnostics separate a silent clock stall from buffering and read displayed frames once per window', () => {
  const p = playerPage();
  let rvfc = 0, presented = 1000;
  p.video.requestVideoFrameCallback = (cb) => { rvfc++; cb(0, { presentedFrames: presented }); };
  p.flush();
  p.advance(31000);
  p.runIntervals(); // first window: baseline clock, one frame callback
  // Clock frozen while claiming enough data (readyState 4, no 'waiting').
  presented = 1600;
  p.advance(31000);
  p.runIntervals();
  const stalled = p.perfReports.at(-1);
  assert.ok(stalled.clockStallMs >= 30000, 'silent clock stall counted');
  assert.equal(stalled.bufferMs, 0, 'not counted as buffering');
  assert.equal(stalled.displayed, -1, 'no second compositor reading yet');
  // Moving clock: no stall; displayed = frames between the last two compositor readings.
  p.video.currentTime += 31;
  p.advance(31000);
  p.runIntervals();
  assert.equal(p.perfReports.at(-1).clockStallMs, 0);
  assert.equal(p.perfReports.at(-1).displayed, 600, 'compositor-presented frames between readings');
  // Buffering: readyState < 3 is buffering, never a clock stall.
  p.video.readyState = 2;
  p.video.trigger('waiting');
  p.advance(31000);
  p.runIntervals();
  const buffering = p.perfReports.at(-1);
  assert.equal(buffering.clockStallMs, 0);
  assert.ok(buffering.bufferMs >= 30000);
  assert.ok(rvfc <= 4, 'one frame callback per window, never per frame');
});
