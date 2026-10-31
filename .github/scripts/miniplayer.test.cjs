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
  let miniRequests = 0;
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
      if (selector === 'video.html5-main-video' || selector === 'video') return video;
      return dom ? dom.querySelectorAll(selector)[0] || null : null;
    }
    querySelectorAll(selector) { return dom ? dom.querySelectorAll(selector) : []; }
    getElementById(id) {
      if (dom && dom.byId(id)) return dom.byId(id);
      if (options.quality && id === 'movie_player') {
        return { setPlaybackQualityRange(from, to) { qualityCalls.push([from, to]); } };
      }
      return null;
    }
    createElement(tag) {
      const element = { tagName: tag.toUpperCase(), style: {}, parentNode: null };
      if (tag === 'style') styleElements.push(element);
      return element;
    }
  }
  Object.defineProperty(Doc.prototype, 'hidden', { configurable: true, get() { return false; } });

  const video = new Media();
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
    __AAY_CFG: { backgroundPlay: true, blockAds: false, sponsorBlock: false, returnDislike: false },
    __AAY_BROWSE: !!options.browse,
    __AAY_CAR: !!options.car,
    ytcfg: options.signedIn === undefined ? null : {
      get(name) { return name === 'LOGGED_IN' ? options.signedIn : undefined; },
    },
    AAYNative: {
      account(status) { accountReports.push(status); },
      lyricsTime(...args) { lyricReports.push(args); },
      mini() { miniRequests++; },
      state(json) { stateReports.push(JSON.parse(json)); },
    },
    addEventListener(type, fn) {
      if (!windowCallbacks.has(type)) windowCallbacks.set(type, []);
      windowCallbacks.get(type).push(fn);
    },
    dispatchEvent() {},
  };
  window.top = window;
  window.self = window;
  const location = {
    hostname: 'm.youtube.com', pathname: '/watch', search: '?v=86p7SP34qSc',
    href: 'https://m.youtube.com/watch?v=86p7SP34qSc',
  };
  const context = {
    window, document, location,
    navigator,
    history: { pushState() {}, replaceState() {} },
    Document: Doc, HTMLMediaElement: Media, MediaSession: class {},
    MutationObserver: class { observe() {} disconnect() {} },
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
  };
  vm.runInNewContext(source, context, { filename: 'aay.js', timeout: 1000 });

  return {
    domNodes: dom ? dom.nodes : null,
    window, navigator, video, seekBar, document, classNames, styleElements, stateReports, accountReports, lyricReports, requests, qualityCalls,
    runIntervals() { for (const fn of intervals) if (fn) fn(); },
    emitWindow(type, event) {
      for (const fn of windowCallbacks.get(type) || []) fn(event);
    },
    get miniRequests() { return miniRequests; },
    emit(type, event) {
      for (const fn of callbacks.get(type) || []) fn(event);
    },
    flush() {
      let budget = 100;
      while (timers.length && budget-- > 0) timers.shift()();
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

test('background quality policy restores visible PiP frames', () => {
  const p = playerPage({ quality: true });
  p.window.__aay.config({ lowQuality: true });
  p.window.__aay.setBg(true);
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'tiny']);
  p.window.__aay.pip(true);
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'highres'], 'PiP must not use tiny-only quality');
  p.window.__aay.pip(false);
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'tiny'], 'normal background playback may reduce quality');
  p.window.__aay.setBg(false);
  assert.deepEqual(p.qualityCalls.at(-1), ['tiny', 'highres']);
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
  p.video.pause();
  p.runIntervals();
  assert.equal(p.lyricReports.at(-1)[2], false, 'paused state reaches the lyrics clock');
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
