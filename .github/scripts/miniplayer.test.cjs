'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('app/src/main/assets/aay.js', 'utf8');

function playerPage(options = {}) {
  const callbacks = new Map();
  const timers = [];
  const classNames = new Set();
  const stateReports = [];
  let miniRequests = 0;
  const styleElements = [];

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
      return selector === 'video.html5-main-video' || selector === 'video' ? video : null;
    }
    querySelectorAll() { return []; }
    getElementById() { return null; }
    createElement(tag) {
      const element = { tagName: tag.toUpperCase(), style: {}, parentNode: null };
      if (tag === 'style') styleElements.push(element);
      return element;
    }
  }
  Object.defineProperty(Doc.prototype, 'hidden', { configurable: true, get() { return false; } });

  const video = new Media();
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
    __AAY_CAR: false,
    AAYNative: {
      mini() { miniRequests++; },
      state(json) { stateReports.push(JSON.parse(json)); },
    },
    addEventListener() {},
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
    navigator: { mediaSession: null },
    history: { pushState() {}, replaceState() {} },
    Document: Doc, HTMLMediaElement: Media, MediaSession: class {},
    MutationObserver: class { observe() {} disconnect() {} },
    URL, console, Promise, Error, DOMException,
    Event: class { constructor(type) { this.type = type; } },
    setTimeout(fn) { timers.push(fn); return timers.length; },
    setInterval() { return 1; },
    clearInterval() {},
  };
  vm.runInNewContext(source, context, { filename: 'aay.js', timeout: 1000 });

  return {
    window, video, document, classNames, styleElements, stateReports,
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
  assert.ok(p.classNames.has('aay-pip'));
  assert.ok(p.styleElements.some(e => e.textContent &&
    e.textContent.includes('html.aay-pip video{visibility:visible!important;')));
  p.video.pause();
  assert.equal(p.video.paused, false, 'YouTube resize pauses must be ignored in mini mode');
  p.window.__aay.cmd('pause');
  assert.equal(p.video.paused, true, 'explicit pause must still work');
  p.window.__aay.mini(false);
  assert.equal(p.classNames.has('aay-pip'), false);
});

test('video state reaches the media process after initialization', () => {
  const p = playerPage();
  p.flush();
  p.video.trigger('playing');
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
