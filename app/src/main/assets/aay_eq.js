// AAYouTube equalizer: mRadio's SoundEngine signal path as a native Web Audio
// graph on YouTube's own <video>. Runs only inside this WebView, affects no other
// app, needs no ADB, and uses built-in nodes only (no AudioWorklet/blob module,
// so YouTube's CSP cannot block it).
//
//   video -> preamp -> 5x peaking (Q 0.9) -> 120 Hz low shelf
//         -> mid/side widening | 8D orbit pan -> delayed low-passed cross-feed
//         -> dry + reverb send -> mRadio soft limiter -> speakers
//
// Only the stages the current settings use are connected, because Chromium
// renders this graph on a realtime thread: idle stages would still cost CPU
// and cause audible dropouts (Chromium "SyncReader::Read timed out").
//
// Safety: a media element is routed into Web Audio only after the user enables
// the equalizer, only for MediaSource (blob:) playback (cross-origin progressive
// media would be silenced by CORS tainting), and only once the AudioContext is
// actually running, so playback can never be captured into a suspended graph.
(function () {
  if (window.__AAY_BROWSE || window.top !== window || window.__aayEqInstalled) return;
  window.__aayEqInstalled = true;
  var C = window.AudioContext || window.webkitAudioContext;
  // cfg holds live settings only, pushed by the app (never a stale install-time copy).
  var EQ = { cfg: null, ctx: null, g: null, hooked: new WeakSet(), els: [], count: 0, status: '', ir: '', idle: false, idleTimer: 0, latency: -1 };

  // Added output delay of the routed path, so lip-sync can be checked in Details.
  function latencyMs() {
    var c = EQ.ctx;
    if (!c) return -1;
    var l = (c.baseLatency || 0) + (c.outputLatency || 0);
    return isFinite(l) && l > 0 ? Math.round(l * 1000) : -1;
  }
  function report(s) {
    var msg = s === 'active' && latencyMs() >= 0 ? s + ':' + latencyMs() : s;
    if (EQ.status === s && EQ.sent === msg) return;
    EQ.status = s; EQ.sent = msg;
    try { var N = window.AAYNative; if (N && typeof N.eqStatus === 'function') N.eqStatus(msg); } catch (e) {}
  }
  function want() { return !!(EQ.cfg && EQ.cfg.enabled); }
  // Flat + 3D Off changes nothing: keep Chromium's native audio path (no Web
  // Audio rendering, CPU or battery cost) until a setting actually alters sound.
  function needsRoute() {
    if (!want()) return false;
    var p = planOf(EQ.cfg);
    return !!(p.eq || p.space || p.wet || p.limit);
  }
  function settled() { return EQ.count ? (want() ? 'active' : 'off') : (want() ? 'waiting' : 'off'); }

  function soft(x) { // mRadio SoundEngine.soft()
    var a = Math.abs(x);
    if (a <= 0.8) return x;
    var y = 0.8 + 0.2 * Math.tanh((a - 0.8) / 0.2);
    return x < 0 ? -y : y;
  }
  var LIMIT_CURVE = (function () {
    // The shaper's input is pre-scaled by 0.5, so its -1..1 range covers -2..2.
    var n = 4096, c = new Float32Array(n);
    for (var k = 0; k < n; k++) c[k] = soft(2 * (k * 2 / (n - 1) - 1));
    return c;
  })();

  function set(p, v) {
    if (!p || !isFinite(v)) return;
    try { p.setTargetAtTime(v, EQ.ctx.currentTime, 0.03); } catch (e) { p.value = v; }
  }

  function impulse(feedback, damp) {
    // Freeverb-like decay of four ~28 ms combs at this feedback. Capped at
    // 1.2 s: the convolver is the costliest node and runs on the realtime thread.
    var key = feedback + ':' + damp;
    if (EQ.ir === key) return;
    EQ.ir = key;
    var ctx = EQ.ctx, rate = ctx.sampleRate;
    var t60 = Math.min(1.2, Math.max(0.3, 3 * 0.028 / -Math.log10(Math.max(0.01, Math.min(0.97, feedback)))));
    var len = Math.floor(rate * t60), buf = ctx.createBuffer(2, len, rate);
    for (var ch = 0; ch < 2; ch++) {
      var d = buf.getChannelData(ch), lp = 0, a = 0.2 + 0.75 * damp;
      for (var i = 0; i < len; i++) {
        lp = (Math.random() * 2 - 1) * (1 - a) + lp * a; // damping darkens the tail
        d[i] = lp * Math.exp(-6.9 * i / len);
      }
    }
    EQ.g.verb.buffer = buf; // costly kernel setup: only when the tail actually changes
  }

  // Every node is created once; only the stages the current settings need are
  // wired. Unwired nodes are never pulled, so Flat + Off costs no DSP at all.
  function build() {
    var ctx = EQ.ctx, g = { all: [], plan: '' };
    var keep = function (n) { g.all.push(n); return n; };
    var gain = function (v) { var n = keep(ctx.createGain()); n.gain.value = v; return n; };
    g.input = ctx.createGain();
    // Like mRadio, play mono streams as stereo: the splitter below does not upmix.
    g.input.channelCount = 2; g.input.channelCountMode = 'explicit'; g.input.channelInterpretation = 'speakers';
    keep(g.input);
    g.pre = gain(1);
    g.bands = (EQ.cfg && EQ.cfg.bandHz || [60, 230, 910, 3600, 14000]).map(function (hz) {
      var f = keep(ctx.createBiquadFilter()); f.type = 'peaking'; f.frequency.value = hz; f.Q.value = 0.9; f.gain.value = 0; return f;
    });
    g.shelf = keep(ctx.createBiquadFilter()); g.shelf.type = 'lowshelf'; g.shelf.frequency.value = 120; g.shelf.gain.value = 0;

    g.split = keep(ctx.createChannelSplitter(2));
    g.mid = gain(1); g.sideRaw = gain(1);
    g.lm = gain(0.5); g.rm = gain(0.5); g.ls = gain(0.5); g.rs = gain(-0.5);
    g.wl = gain(1); g.wr = gain(1); g.side = gain(1); g.inv = gain(-1);
    g.accL = gain(1); g.accR = gain(1);
    g.merge = keep(ctx.createChannelMerger(2));
    var cross = function () {
      var d = keep(ctx.createDelay(0.05)), lp = keep(ctx.createBiquadFilter()), cg = gain(0);
      d.delayTime.value = 0; lp.type = 'lowpass'; lp.frequency.value = 700; lp.Q.value = 0.7;
      return { d: d, lp: lp, g: cg };
    };
    g.toL = cross(); g.toR = cross();
    g.pan = keep(ctx.createStereoPanner()); g.pan.pan.value = 0;
    g.amp = gain(Math.SQRT2); g.ps = keep(ctx.createChannelSplitter(2));
    g.orbitDepth = gain(0); g.osc = null;

    g.verbIn = gain(1); // mRadio feeds its reverb the mono sum
    g.verbIn.channelCount = 1; g.verbIn.channelCountMode = 'explicit'; g.verbIn.channelInterpretation = 'speakers';
    g.preDelay = keep(ctx.createDelay(0.1)); g.verb = keep(ctx.createConvolver()); g.wet = gain(0);
    g.sum = gain(1);
    g.limitIn = gain(0.5); g.shaper = keep(ctx.createWaveShaper()); g.shaper.curve = LIMIT_CURVE;
    g.out = gain(1);
    g.out.connect(ctx.destination);
    EQ.g = g;
  }

  function planOf(c) {
    var on = !!(c && c.enabled);
    if (!on) return { key: 'pass' };
    var eq = c.bassDb > 0 || c.bands.some(function (b) { return b !== 0; });
    var orbit = c.orbit > 0, cross = c.crossGain > 0, wet = c.wet > 0;
    var space = orbit || cross || Math.abs(c.sideGain - 1) > 1e-4;
    var limit = !!c.limiter;
    return { eq: eq, space: space, orbit: orbit, cross: cross, wet: wet, limit: limit,
      // The tail is part of the key: replacing a convolver kernel while audible
      // glitches, so Hall <-> Cinema also fades around the swap.
      key: [eq, space, orbit, cross, wet ? c.feedback + '/' + c.damp : 0, limit].join() };
  }

  function wire(p) {
    var g = EQ.g, ctx = EQ.ctx;
    g.all.forEach(function (n) { if (n !== g.out) try { n.disconnect(); } catch (e) {} });
    if (g.osc) { try { g.osc.stop(); g.osc.disconnect(); } catch (e) {} g.osc = null; }
    var n = g.input;
    if (p.eq) {
      n.connect(g.pre); n = g.pre;
      g.bands.forEach(function (f) { n.connect(f); n = f; });
      n.connect(g.shelf); n = g.shelf;
    }
    if (p.space) {
      n.connect(g.split);
      g.split.connect(g.lm, 0); g.split.connect(g.rm, 1); g.split.connect(g.ls, 0); g.split.connect(g.rs, 1);
      g.lm.connect(g.mid); g.rm.connect(g.mid); g.ls.connect(g.sideRaw); g.rs.connect(g.sideRaw);
      g.sideRaw.connect(g.side); g.side.connect(g.wl); g.side.connect(g.inv); g.inv.connect(g.wr);
      if (p.orbit) {
        g.mid.connect(g.pan); g.pan.connect(g.amp); g.amp.connect(g.ps);
        g.ps.connect(g.wl, 0); g.ps.connect(g.wr, 1);
        g.osc = ctx.createOscillator();
        g.osc.frequency.value = 1 / ((EQ.cfg && EQ.cfg.orbitPeriod) || 9);
        g.osc.connect(g.orbitDepth); g.orbitDepth.connect(g.pan.pan); g.osc.start();
      } else {
        g.mid.connect(g.wl); g.mid.connect(g.wr);
      }
      g.wl.connect(g.accL); g.wr.connect(g.accR);
      if (p.cross) {
        [[g.wr, g.toL, g.accL], [g.wl, g.toR, g.accR]].forEach(function (x) {
          x[0].connect(x[1].d); x[1].d.connect(x[1].lp); x[1].lp.connect(x[1].g); x[1].g.connect(x[2]);
        });
      }
      g.accL.connect(g.merge, 0, 0); g.accR.connect(g.merge, 0, 1);
      n = g.merge;
    }
    n.connect(g.sum);
    if (p.wet) { n.connect(g.verbIn); g.verbIn.connect(g.preDelay); g.preDelay.connect(g.verb); g.verb.connect(g.wet); g.wet.connect(g.sum); }
    if (p.limit) { g.sum.connect(g.limitIn); g.limitIn.connect(g.shaper); g.shaper.connect(g.out); }
    else g.sum.connect(g.out);
  }

  // Values only: smoothed, and only when they differ, so slider drags stay cheap.
  function setIf(p, v, last) {
    if (!p || !isFinite(v) || last[0] === v) return;
    last[0] = v;
    set(p, v);
  }
  var LAST = {};
  function slot(k) { return LAST[k] || (LAST[k] = [NaN]); }

  function values(c) {
    var g = EQ.g;
    setIf(g.pre.gain, c.preamp, slot('pre'));
    g.bands.forEach(function (f, i) {
      setIf(f.Q, c.q || 0.9, slot('q' + i));
      setIf(f.gain, c.bands[i] || 0, slot('b' + i));
    });
    setIf(g.shelf.frequency, c.bassHz || 120, slot('sf'));
    setIf(g.shelf.gain, c.bassDb, slot('sg'));
    setIf(g.side.gain, c.orbit > 0 ? c.orbitKeep : c.sideGain, slot('side'));
    setIf(g.orbitDepth.gain, c.orbit * 0.8, slot('od'));
    [g.toL, g.toR].forEach(function (x, i) {
      setIf(x.g.gain, c.crossGain, slot('cg' + i));
      setIf(x.d.delayTime, c.crossDelay || 0, slot('cd' + i));
    });
    setIf(g.accL.gain, c.crossNorm, slot('nL'));
    setIf(g.accR.gain, c.crossNorm, slot('nR'));
    setIf(g.preDelay.delayTime, c.pre || 0, slot('pd'));
    setIf(g.wet.gain, c.wet * 0.6, slot('wet'));
    if (c.wet > 0) impulse(c.feedback, c.damp);
  }

  function apply() {
    var g = EQ.g, c = EQ.cfg;
    if (!g || !c) return;
    var p = planOf(c);
    if (p.key === g.plan) { if (c.enabled) values(c); return; }
    // Stage change: fade out, rewire, fade in, so switching modes cannot click.
    var ctx = EQ.ctx, now = ctx.currentTime, first = !g.plan;
    g.plan = p.key;
    clearTimeout(g.rewire);
    // Hidden pages throttle timers; never leave the output faded out there.
    if (first || document.hidden) { if (c.enabled) values(c); wire(p); try { g.out.gain.cancelScheduledValues(now); g.out.gain.value = 1; } catch (e) {} return; }
    try { g.out.gain.cancelScheduledValues(now); g.out.gain.setTargetAtTime(0, now, 0.008); } catch (e) {}
    g.rewire = setTimeout(function () {
      var cur = planOf(EQ.cfg);
      if (EQ.cfg.enabled) values(EQ.cfg);
      wire(cur);
      g.plan = cur.key;
      try { var t = ctx.currentTime; g.out.gain.cancelScheduledValues(t); g.out.gain.setTargetAtTime(1, t, 0.015); } catch (e) {}
    }, 50);
  }

  function attach(v) {
    if (!v || EQ.hooked.has(v)) return;
    if (!want()) { report(settled()); return; }
    if (!needsRoute()) { if (!EQ.count) report('waiting'); return; }
    var src = v.currentSrc || v.src || '';
    if (src.indexOf('blob:') !== 0) {
      // Hysteresis: a short non-MediaSource clip (preview, ad) must not switch on
      // the system-mix fallback. Only a clip still playing after 4 s counts.
      if (!EQ.count && !v.__aayEqProbe) {
        v.__aayEqProbe = setTimeout(function () {
          v.__aayEqProbe = 0;
          var now = v.currentSrc || v.src || '';
          if (!EQ.count && want() && !v.paused && !v.ended && now.indexOf('blob:') !== 0) report('unsupported');
        }, 4000);
      }
      return;
    }
    if (!C) { report('unsupported'); return; }
    if (!EQ.ctx) {
      try {
        EQ.ctx = new C({ latencyHint: 'playback' });
        build();
        EQ.ctx.onstatechange = keepRunning;
      } catch (e) { EQ.ctx = null; EQ.g = null; report('unsupported'); return; }
    }
    var ctx = EQ.ctx;
    var go = function () {
      if (EQ.hooked.has(v)) return;
      if (ctx.state !== 'running') { if (!EQ.count) report('unsupported'); return; }
      try {
        ctx.createMediaElementSource(v).connect(EQ.g.input);
        EQ.hooked.add(v);
        EQ.els.push(v);
        EQ.count++;
        apply();
        report(settled());
      } catch (e) { report(EQ.count ? settled() : 'unsupported'); }
    };
    if (ctx.state === 'running') { go(); return; }
    report('waiting');
    try { ctx.resume().then(go, function () { if (!EQ.count) report('unsupported'); }); } catch (e) {}
    setTimeout(function () { if (!EQ.hooked.has(v) && ctx.state !== 'running' && !EQ.count) report('unsupported'); }, 3000);
  }

  function anyPlaying() {
    for (var i = 0; i < EQ.els.length; i++) if (!EQ.els[i].paused && !EQ.els[i].ended) return true;
    return false;
  }
  // A routed element is silent while the context is suspended: resume at once,
  // unless we suspended it ourselves because nothing routed is playing.
  function keepRunning() {
    if (EQ.idle && anyPlaying()) EQ.idle = false;
    if (EQ.ctx && EQ.count && !EQ.idle && EQ.ctx.state !== 'running' && EQ.ctx.state !== 'closed') {
      try { EQ.ctx.resume().then(function () { if (EQ.status === 'active') report('active'); }, function () {}); } catch (e) {}
    }
  }
  // Paused or ended: stop rendering after a grace period so the device's audio
  // output closes (battery, and Android no longer sees an active player).
  function idleCheck() {
    clearTimeout(EQ.idleTimer);
    EQ.idleTimer = setTimeout(function () {
      if (!EQ.ctx || !EQ.count || anyPlaying() || EQ.ctx.state !== 'running') return;
      EQ.idle = true;
      try { EQ.ctx.suspend(); } catch (e) {}
    }, 2000);
  }
  function wake(e) {
    var t = e.target;
    if (!(t instanceof HTMLMediaElement)) return;
    clearTimeout(EQ.idleTimer);
    EQ.idle = false;
    keepRunning();
  }
  document.addEventListener('play', wake, true);
  ['pause', 'ended', 'emptied'].forEach(function (n) {
    document.addEventListener(n, function (e) {
      if (e.target instanceof HTMLMediaElement && EQ.hooked.has(e.target)) idleCheck();
    }, true);
  });

  function scan() {
    var list = document.getElementsByTagName('video');
    for (var i = 0; i < list.length; i++) if (!list[i].paused) attach(list[i]);
  }

  document.addEventListener('playing', function (e) {
    var t = e.target;
    if (!(t instanceof HTMLMediaElement)) return;
    clearTimeout(EQ.idleTimer);
    EQ.idle = false;
    keepRunning();
    attach(t);
  }, true);

  var A = window.__aay || (window.__aay = {});
  A.eq = function (cfg) {
    if (!cfg || typeof cfg !== 'object') return;
    EQ.cfg = cfg;
    apply();
    if (needsRoute()) scan();
    if (!want()) report('off');
    else if (!(EQ.status === 'unsupported' && !EQ.count)) report(settled());
  };
  A.eqStatus = function () { return EQ.status || settled(); };
})();
