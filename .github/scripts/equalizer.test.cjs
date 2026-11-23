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
function page({ hz = 1000, amp = 0.5, stereo = 'mono', src = 'blob:https://m.youtube.com/x', rate = RATE, duration = 2 } = {}) {
  const off = new OfflineAudioContext(2, rate * 2, rate);
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
        const g = t.createGain(); g.gain.value = amp; g.gain.setValueAtTime(0, duration); o.connect(g); o.start();
        if (stereo === 'mono') return g;
        const m = t.createChannelMerger(2);
        const inv = t.createGain(); inv.gain.value = stereo === 'side' ? -1 : stereo === 'left' ? 0 : 1;
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
  const fire = (name, target = video) => (listeners[name] || []).forEach(f => f({ target }));
  const runTimers = (maxMs) => {
    for (const t of timers.splice(0)) if (t.f && t.at <= maxMs) t.f();
  };
  const render = async () => {
    const buf = await off.startRendering();
    const m = ch => {
      const d = buf.getChannelData(ch).subarray(rate);
      let s = 0, pk = 0;
      for (const x of d) { s += x * x; pk = Math.max(pk, Math.abs(x)); }
      return { rms: Math.sqrt(s / d.length), peak: pk };
    };
    return { L: m(0), R: m(1), left: buf.getChannelData(0), right: buf.getChannelData(1) };
  };
  return { eq: c => window.__aay.eq(c), video, statuses, calls, fire, runTimers, render,
    stateChange: () => onState && onState(),
    pending: () => timers.filter(t => t.f).map(t => t.at),
    setState: s => { state = s; }, document, offline: off };
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

test('enabled but Flat + Off keeps the native audio path (no Web Audio cost)', () => {
  const p = page();
  p.eq({ ...BASE });
  assert.equal(p.calls.routed, 0);
  assert.equal(p.statuses.at(-1), 'waiting');
  p.eq({ ...BASE, bands: [0, 0, 3, 0, 0], limiter: true }); // first real change routes
  assert.equal(p.calls.routed, 1);
  assert.equal(p.statuses.at(-1), 'active:50');
});

test('routed graph set back to flat is transparent', async () => {
  const p = page();
  p.eq({ ...BASE, bands: [0, 0, 3, 0, 0], limiter: true });
  p.eq({ ...BASE });
  p.runTimers(4000);
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

test('bass boost preserves its tonal curve with full headroom', async () => {
  const cfg = fixtures.find(f => f.name === 'Bass boost:0.0').params;
  const low = page({ hz: 60, amp: 0.3 }); low.eq(cfg);
  const high = page({ hz: 3600, amp: 0.3 }); high.eq(cfg);
  const lr = await low.render(), hr = await high.render();
  near(db(lr.L.rms, hr.L.rms), 6.4, 0.4, 'bass-to-mid tonal difference');
  near(db(hr.L.rms, 0.3 / Math.SQRT2), 20 * Math.log10(cfg.preamp), 0.15, 'full headroom');
});

test('wide preserves the side signal level after reserving spatial headroom', async () => {
  const p = page({ amp: 0.3, stereo: 'side' });
  p.eq({ ...BASE, sideGain: 2.2 });
  near(db((await p.render()).L.rms, 0.3 / Math.SQRT2), 0, 0.05, 'normalised side level');
});

test('headphone 3D and Concert keep stereo balanced with their spatial reserve', async () => {
  for (const name of ['HEADPHONE_3D:1', 'HALL:1']) {
    const cfg = fixtures.find(f => f.name === name).params;
    const p = page({ hz: 300, amp: 0.3 }); p.eq(cfg);
    const r = await p.render();
    near(db(r.L.rms, r.R.rms), 0, 1, `${name} balance`);
    near(db(r.L.rms, 0.3 / Math.SQRT2), 20 * Math.log10(cfg.preamp), 1, `${name} headroom`);
  }
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
  p.eq({ ...BASE, sideGain: 1.6, limiter: true });
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
  p.eq({ ...BASE, sideGain: 1.6, limiter: true });
  assert.equal(p.calls.routed, 0);
  assert.ok(!p.statuses.includes('unsupported'));
  p.video.paused = true; // clip ends before the 4 s probe
  p.runTimers(4000);
  assert.ok(!p.statuses.includes('unsupported'));
});

test('long non-MediaSource playback falls back after 4 s', () => {
  const p = page({ src: 'https://example.invalid/progressive.mp4' });
  p.eq({ ...BASE, sideGain: 1.6, limiter: true });
  p.runTimers(4000);
  assert.equal(p.statuses.at(-1), 'unsupported');
  assert.equal(p.calls.routed, 0);
});

test('reverb time changes fade and replace the tail instead of pitching existing audio', () => {
  const p = page();
  const hall = { ...BASE, sideGain: 1.1, wet: 0.26, feedback: 0.88, damp: 0.3, pre: 0.018, limiter: true };
  p.eq(hall);
  p.runTimers(0);
  p.eq({ ...hall, sideGain: 1.4, wet: 0.15, feedback: 0.84, damp: 0.4, pre: 0.012 }); // Cinema
  assert.ok(p.pending().includes(50), 'Hall -> Cinema fades before changing delay times');
  p.eq({ ...hall, wet: 0 , sideGain: 1.6 }); // Wide: reverb stage leaves -> faded rewire
  assert.ok(p.pending().includes(50));
});

test('Concert tail decays after playback stops', async () => {
  const p = page({ hz: 500, amp: 0.3, duration: 0.25 });
  p.eq(fixtures.find(f => f.name === 'HALL:1').params);
  const r = await p.render();
  function rms(start, end) {
    const data = r.left.subarray(start * RATE, end * RATE);
    return Math.sqrt(data.reduce((sum, x) => sum + x * x, 0) / data.length);
  }
  assert.ok(rms(0.3, 0.4) > 1e-5, 'room has an audible tail');
  assert.ok(rms(1, 1.2) < rms(0.3, 0.4) * 0.01, 'tail retires cleanly');
  assert.ok(r.left.every(x => Number.isFinite(x) && Math.abs(x) < 1));
});


// These settings come from WebAudioEqPolicy, and Kotlin CI verifies they stay in sync.
const fixtures = fs.readFileSync('.github/scripts/eq-fixtures.jsonl', 'utf8').trim().split('\n').map(JSON.parse);
function thd(samples, hz) {
  const data = samples.subarray(RATE);
  let sine = 0, cosine = 0, power = 0;
  for (let i=0; i<data.length; i++) {
    sine += data[i]*Math.sin(2*Math.PI*hz*i/RATE);
    cosine += data[i]*Math.cos(2*Math.PI*hz*i/RATE);
    power += data[i]*data[i];
  }
  const fundamental = 2*(sine*sine+cosine*cosine)/data.length;
  return Math.sqrt(Math.max(0, power-fundamental)/fundamental);
}
test('all tonal presets plus extra bass stay clean at high input level', async () => {
  for (const f of fixtures.filter(f => f.kind === 'preset')) {
    for (const hz of [60, 230, 910, 3600, 14000]) {
      const p = page({ hz, amp: 0.95 }); p.eq(f.params);
      if (!f.params.limiter) continue; // native Flat path is intentionally not routed
      const r = await p.render();
      assert.ok(r.L.peak <= 0.99, `${f.name} at ${hz} Hz peak ${r.L.peak}`);
      assert.ok(thd(r.left,hz) < 0.005, `${f.name} at ${hz} Hz distortion ${thd(r.left,hz)}`);
    }
  }
});
test('Concert keeps dry stereo and does not feed left-only music into the right ear', async () => {
  const f = fixtures.find(f => f.name === 'HALL:1');
  const p = page({ hz: 1000, amp: 0.95, stereo: 'left' }); p.eq(f.params);
  const r = await p.render();
  assert.ok(r.R.peak < 1e-6, `right channel ${r.R.peak}`);
  assert.ok(thd(r.left, 1000) < 0.005);
});
test('all zero-intensity modes keep the native path', () => {
  for (const f of fixtures.filter(f => f.kind === 'spatial' && f.name.endsWith(':0'))) {
    const p = page(); p.eq(f.params); assert.equal(p.calls.routed,0,f.name);
  }
});


test('active preset reserves headroom before boosts, including rapid retargets', async () => {
  // node-web-audio-api's OfflineAudioContext.suspend() can panic in its native
  // Rust renderer. Exercise rapid reconfiguration synchronously and keep the
  // output-level assertions on an actual offline render.
  const p = page({ hz: 60, amp: 0.95 });
  p.eq({ ...BASE, bands: [0, 0, -0.1, 0, 0] });
  const changes = [
    { ...BASE, bands: [12, 0, 0, 0, 0], preamp: 10 ** (-12.6 / 20) },
    { ...BASE, bands: [6, 4, 0, 0, 0], bassDb: 9, preamp: 10 ** (-20 / 20) },
    { ...BASE, bands: [0, 0, -0.1, 0, 0] },
  ];
  changes.forEach(cfg => p.eq(cfg));
  assert.equal(p.calls.routed, 1, 'rapid retargets reuse the same graph');
  assert.match(source, /p\.linearRampToValueAtTime\(safe, now \+ 0\.01\)/);
  assert.match(source, /p\.setValueAtTime\(safe, now \+ 0\.31\)/);
  assert.match(source, /p\.setTargetAtTime\(c\.preamp, now \+ 0\.31, 0\.03\)/);
  const result = await p.render();
  const peak = result.left.reduce((v, x) => Math.max(v, Math.abs(x)), 0);
  assert.ok(peak <= 0.981, `retargeted graph does not clip, peak=${peak}`);
});

for (const hz of [60, 1000, 14000]) {
  test(`rapid preset retargets keep output below full scale at ${hz} Hz`, async () => {
    const presets = fixtures.filter(x => x.kind === 'preset' && x.name.endsWith(':1.0'));
    assert.equal(presets.length, 10);
    const p = page({ hz, amp: 0.95 });
    for (const x of presets) p.eq({ ...x.params, limiter: false });
    assert.equal(p.calls.routed, 1, 'retargeting must not duplicate audio paths');
    const result = await p.render();
    const peak = result.left.reduce((v, x) => Math.max(v, Math.abs(x)), 0);
    assert.ok(peak <= 0.981, `peak=${peak} without limiter repair`);
  });
}

test('native filter response calibrates preset headroom at each context sample rate', async () => {
  for (const rate of [8000, 32000, 44100, 48000, 96000]) {
    for (const name of ['Bass boost:1.0', 'Treble boost:0.0']) {
      const cfg = { ...fixtures.find(f => f.name === name).params, limiter: false };
      const hz = Math.min(14000, rate * 0.4);
      const p = page({ hz, amp: 0.95, rate }); p.eq(cfg);
      const r = await p.render();
      assert.ok(r.L.peak < 0.981, `${name} at ${rate}: peak=${r.L.peak}`);
      assert.ok(r.left.every(Number.isFinite), `${name} finite at ${rate}`);
      assert.ok(r.L.rms > 0.005, `${name} not muted at ${rate}`);
    }
  }
});


test('unrelated media never wakes the suspended equalizer', () => {
  const p = page();
  p.eq({ ...BASE, bands: [6, 0, 0, 0, 0] });
  p.video.paused = true; p.fire('pause'); p.runTimers(2000);
  assert.equal(p.calls.suspend, 1);
  const unrelated = Object.assign(Object.create(Object.getPrototypeOf(p.video)),
    { paused: false, ended: false, currentSrc: 'https://example.com/unrouted.mp4' });
  p.fire('play', unrelated); p.fire('playing', unrelated);
  assert.equal(p.calls.resume, 0);
  p.video.paused = false; p.fire('play');
  assert.equal(p.calls.resume, 1, 'routed music resumes immediately');
});

test('hidden paused audio suspends without waiting for a throttled timer', () => {
  const p = page(); p.eq({ ...BASE, bands: [6, 0, 0, 0, 0] });
  p.document.hidden = true;
  p.video.paused = true; p.fire('pause');
  assert.equal(p.calls.suspend, 1);
  p.stateChange(); assert.equal(p.calls.resume, 0);
  p.video.paused = false; p.fire('playing');
  assert.equal(p.calls.resume, 1);
});

test('detached videos cannot keep a hidden audio context running', () => {
  const p = page(); p.eq({ ...BASE, bands: [6, 0, 0, 0, 0] });
  p.video.isConnected = false;
  p.document.hidden = true; p.fire('visibilitychange');
  assert.equal(p.calls.suspend, 1);
});

test('background playback continues while routed music is actually playing', () => {
  const p = page(); p.eq({ ...BASE, bands: [6, 0, 0, 0, 0] });
  p.document.hidden = true; p.fire('visibilitychange');
  assert.equal(p.calls.suspend, 0);
});
