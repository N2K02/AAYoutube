'use strict';

// Behavioral checks of the in-player equalizer (app/src/main/assets/aay_eq.js)
// on a real Web Audio implementation (node-web-audio-api), not source strings.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { OfflineAudioContext } = require('node-web-audio-api');

const source = fs.readFileSync('app/src/main/assets/aay_eq.js', 'utf8');
const RATE = 48000;

const BASE = {
  enabled: true, bandHz: [60, 230, 910, 3600, 14000], bands: [0, 0, 0, 0, 0], q: 0.9,
  bassDb: 0, bassHz: 120, preamp: 1, sideGain: 1, crossGain: 0, crossNorm: 1, crossDelay: 0,
  crossLp: 700, wet: 0, feedback: 0.84, damp: 0.4, pre: 0, orbit: 0, orbitPeriod: 9,
  orbitKeep: 0.5, limiter: false,
};

/** Loads the page script against a real offline context with a controllable page. */
function page({ hz = 1000, amp = 0.5, stereo = 'mono', src = 'blob:https://m.youtube.com/x' } = {}) {
  const off = new OfflineAudioContext(2, RATE * 2, RATE);
  const timers = [];
  const listeners = {};
  const statuses = [];
  const calls = { routed: 0, suspend: 0, resume: 0 };
  let state = 'running';
  let onState = null;
  const ctx = new Proxy(off, {
    get(t, p) {
      if (p === 'state') return state;
      if (p === 'baseLatency') return 0.02;
      if (p === 'outputLatency') return 0.03;
      if (p === 'resume') return () => { calls.resume++; state = 'running'; return Promise.resolve(); };
      if (p === 'suspend') return () => { calls.suspend++; state = 'suspended'; return Promise.resolve(); };
      if (p === 'createMediaElementSource') return () => {
        calls.routed++;
        const o = t.createOscillator(); o.frequency.value = hz;
        const g = t.createGain(); g.gain.value = amp; o.connect(g); o.start();
        if (stereo === 'mono') return g;
        const m = t.createChannelMerger(2);
        const inv = t.createGain(); inv.gain.value = stereo === 'side' ? -1 : 1;
        g.connect(m, 0, 0); g.connect(inv); inv.connect(m, 0, 1);
        return m;
      };
      const v = t[p];
      return typeof v === 'function' ? v.bind(t) : v;
    },
    set(t, p, v) { if (p === 'onstatechange') onState = v; else t[p] = v; return true; },
  });
  function HTMLMediaElement() {}
  const video = Object.assign(Object.create(HTMLMediaElement.prototype),
    { paused: false, ended: false, currentSrc: src });
  const window = { AudioContext: function () { return ctx; },
    AAYNative: { eqStatus: s => statuses.push(s) } };
  window.top = window;
  const document = {
    hidden: false,
    addEventListener: (n, f) => { (listeners[n] = listeners[n] || []).push(f); },
    getElementsByTagName: () => [video],
  };
  const sandbox = {
    window, document, HTMLMediaElement, Float32Array, Math, isFinite, Promise,
    setTimeout: (f, ms) => { timers.push({ f, at: ms }); return timers.length; },
    clearTimeout: id => { if (timers[id - 1]) timers[id - 1].f = null; },
  };
  vm.runInNewContext(source, sandbox);
  const fire = (name) => (listeners[name] || []).forEach(f => f({ target: video }));
  const runTimers = (maxMs) => {
    for (const t of timers.splice(0)) if (t.f && t.at <= maxMs) t.f();
  };
  const render = async () => {
    const buf = await off.startRendering();
    const m = ch => {
      const d = buf.getChannelData(ch).subarray(RATE);
      let s = 0, pk = 0;
      for (const x of d) { s += x * x; pk = Math.max(pk, Math.abs(x)); }
      return { rms: Math.sqrt(s / d.length), peak: pk };
    };
    return { L: m(0), R: m(1) };
  };
  return { eq: c => window.__aay.eq(c), video, statuses, calls, fire, runTimers, render,
    stateChange: () => onState && onState(),
    pending: () => timers.filter(t => t.f).map(t => t.at),
    setState: s => { state = s; } };
}

const db = (a, b) => 20 * Math.log10(a / b);
const near = (actual, expected, tol, msg) =>
  assert.ok(Math.abs(actual - expected) <= tol, `${msg}: ${actual.toFixed(2)} vs ${expected}`);

test('switch off: nothing is routed into Web Audio', () => {
  const p = page();
  p.eq({ ...BASE, enabled: false });
  assert.equal(p.calls.routed, 0);
  assert.deepEqual(p.statuses, ['off']);
});

test('flat is transparent and reports active with measured output delay', async () => {
  const p = page();
  p.eq({ ...BASE });
  assert.equal(p.calls.routed, 1);
  assert.equal(p.statuses.at(-1), 'active:50');
  const r = await p.render();
  near(db(r.L.rms, 0.5 / Math.SQRT2), 0, 0.05, 'flat level');
});

test('mono streams reach both ears', async () => {
  const p = page();
  p.eq({ ...BASE, sideGain: 1.6, limiter: true });
  const r = await p.render();
  near(db(r.L.rms, r.R.rms), 0, 0.1, 'L/R balance');
  assert.ok(r.R.rms > 0.1);
});

test('bass boost preset matches mRadio levels with preamp headroom', async () => {
  const cfg = { ...BASE, bands: [6, 4, 0, 0, 0], preamp: Math.pow(10, -6 * 0.5 / 20), limiter: true };
  const low = page({ hz: 60, amp: 0.3 }); low.eq(cfg);
  const high = page({ hz: 3600, amp: 0.3 }); high.eq(cfg);
  near(db((await low.render()).L.rms, 0.3 / Math.SQRT2), 3.4, 0.4, '60 Hz');
  near(db((await high.render()).L.rms, 0.3 / Math.SQRT2), -3, 0.2, '3.6 kHz');
});

test('wide scales the side signal exactly like mRadio', async () => {
  const p = page({ amp: 0.3, stereo: 'side' });
  p.eq({ ...BASE, sideGain: 2.2 });
  near(db((await p.render()).L.rms, 0.3 / Math.SQRT2), 20 * Math.log10(2.2), 0.05, 'side gain');
});

test('headphone 3D and hall stay balanced and near unity', async () => {
  const hp = page({ hz: 300, amp: 0.3 });
  hp.eq({ ...BASE, sideGain: 1.25, crossGain: 0.425, crossNorm: 1 / (1 + 0.425 * 0.6),
    crossDelay: 0.00027, wet: 0.09, feedback: 0.8, damp: 0.5, pre: 0.01, limiter: true });
  const r = await hp.render();
  near(db(r.L.rms, r.R.rms), 0, 1, 'HP3D balance');
  near(db(r.L.rms, 0.3 / Math.SQRT2), 0, 2, 'HP3D level');
  const hall = page({ hz: 500, amp: 0.3 });
  hall.eq({ ...BASE, sideGain: 1.1, wet: 0.26, feedback: 0.88, damp: 0.3, pre: 0.018, limiter: true });
  near(db((await hall.render()).L.rms, 0.3 / Math.SQRT2), 0, 2, 'hall level');
});

test('8D orbit pans with mRadio equal-power law', async () => {
  const p = page({ amp: 0.3 });
  p.eq({ ...BASE, orbit: 1, orbitKeep: 0.25, wet: 0.15, feedback: 0.8, damp: 0.5, pre: 0.008, limiter: true });
  const r = await p.render();
  assert.ok(db(r.R.rms, r.L.rms) > 8, 'source is on the right during the second second');
});

test('soft limiter never exceeds full scale', async () => {
  const p = page({ hz: 14000, amp: 0.9 });
  p.eq({ ...BASE, bands: [0, 0, 0, 0, 12], preamp: Math.pow(10, -6 / 20), limiter: true });
  assert.ok((await p.render()).L.peak <= 1.0001);
});

test('paused playback suspends rendering and play resumes it', () => {
  const p = page();
  p.eq({ ...BASE });
  p.video.paused = true;
  p.fire('pause');
  p.runTimers(2000);
  assert.equal(p.calls.suspend, 1);
  p.stateChange(); // our own suspend must not be undone while paused
  assert.equal(p.calls.resume, 0);
  p.video.paused = false;
  p.fire('play');
  assert.equal(p.calls.resume, 1);
});

test('short non-MediaSource clip never switches on the system fallback', () => {
  const p = page({ src: 'https://example.invalid/preview.mp4' });
  p.eq({ ...BASE });
  assert.equal(p.calls.routed, 0);
  assert.ok(!p.statuses.includes('unsupported'));
  p.video.paused = true; // clip ends before the 4 s probe
  p.runTimers(4000);
  assert.ok(!p.statuses.includes('unsupported'));
});

test('long non-MediaSource playback falls back after 4 s', () => {
  const p = page({ src: 'https://example.invalid/progressive.mp4' });
  p.eq({ ...BASE });
  p.runTimers(4000);
  assert.equal(p.statuses.at(-1), 'unsupported');
  assert.equal(p.calls.routed, 0);
});

test('switching between reverb modes fades around the kernel swap', () => {
  const p = page();
  const hall = { ...BASE, sideGain: 1.1, wet: 0.26, feedback: 0.88, damp: 0.3, pre: 0.018, limiter: true };
  p.eq(hall);
  p.runTimers(0);
  const cinema = { ...hall, sideGain: 1.4, wet: 0.15, feedback: 0.84, damp: 0.4, pre: 0.012 };
  p.eq(cinema);
  assert.ok(p.pending().includes(50), 'Hall -> Cinema schedules a faded rewire');
  p.runTimers(4000);
  p.eq({ ...cinema, sideGain: 1.6, wet: 0.2 }); // intensity only: same tail, no rewire
  assert.ok(!p.pending().includes(50), 'intensity change does not rewire');
});
