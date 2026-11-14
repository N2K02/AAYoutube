// AAYouTube equalizer: mRadio's SoundEngine signal path as a native Web Audio
// graph on YouTube's own <video>. Runs only inside this WebView, affects no other
// app, needs no ADB, and uses built-in nodes only (no AudioWorklet/blob module,
// so YouTube's CSP cannot block it).
//
//   video -> preamp -> 5x peaking (Q 0.9) -> 120 Hz low shelf
//         -> mid/side widening | 8D orbit pan -> delayed low-passed cross-feed
//         -> dry + reverb send -> linked peak compressor + emergency full-scale guard -> speakers
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
  var EQ = { cfg: null, ctx: null, g: null, hooked: new WeakSet(), els: [], count: 0, status: '', idle: false, idleTimer: 0, latency: -1 };

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

  function soft(x) { // Emergency guard; transparent throughout the normal operating range.
    var a = Math.abs(x);
    if (a <= 0.999) return x;
    var y = 0.999 + 0.001 * Math.tanh((a - 0.999) / 0.001);
    return x < 0 ? -y : y;
  }
  var LIMIT_CURVE = (function () {
    // The shaper's input is pre-scaled by 0.5, so its -1..1 range covers -2..2.
    var n = 4096, c = new Float32Array(n);
    for (var k = 0; k < n; k++) c[k] = soft(2 * (k * 2 / (n - 1) - 1));
    return c;
  })();

  function set(p, v, start) {
    if (!p || !isFinite(v)) return;
    try {
      var now = EQ.ctx.currentTime;
      if (start !== undefined) {
        var held = p.value;
        p.cancelScheduledValues(now); p.setValueAtTime(held, now);
      }
      p.setTargetAtTime(v, start === undefined ? now : start, 0.03);
    } catch (e) { p.value = v; }
  }

  // Stereo room: four damped, gain-normalised feedback echoes (short tunings at
  // 48 kHz). Costs a few delay/filter nodes per sample, unlike a convolution
  // reverb, which was the single heaviest part of the equalizer.
  var COMBS = [0.0253, 0.0269, 0.0290, 0.0307];
  function setVerb(feedback, damp) {
    var g = EQ.g, fb = Math.max(0, Math.min(0.75, feedback || 0));
    var cutoff = 1200 + (1 - Math.max(0, Math.min(1, damp || 0))) * 6000; // damping darkens the tail
    g.combs.forEach(function (c) { set(c.fb.gain, fb); set(c.lp.frequency, cutoff); });
    set(g.verbIn.gain, 1 - fb);
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

    g.verbIn = gain(0.3); // Normalised stereo send; dry stereo stays untouched.
    g.verbIn.channelCount = 2; g.verbIn.channelCountMode = 'explicit';
    g.verbHp = keep(ctx.createBiquadFilter()); g.verbHp.type = 'highpass'; g.verbHp.frequency.value = 180; g.verbHp.Q.value = 0.5;
    g.verbSplit = keep(ctx.createChannelSplitter(2));
    g.verbMerge = keep(ctx.createChannelMerger(2));
    g.preDelay = keep(ctx.createDelay(0.1)); g.wet = gain(0);
    g.combs = COMBS.map(function (sec) {
      var d = keep(ctx.createDelay(0.05)), lp = keep(ctx.createBiquadFilter()), fb = gain(0);
      d.delayTime.value = sec; lp.type = 'lowpass'; lp.frequency.value = 4000; lp.Q.value = 0.5;
      return { d: d, lp: lp, fb: fb };
    });
    g.combOut = gain(0.5);
    g.sum = gain(1);
    g.limitIn = gain(0.5); g.shaper = keep(ctx.createWaveShaper()); g.shaper.curve = LIMIT_CURVE;
    g.compressor = keep(ctx.createDynamicsCompressor());
    g.compressor.threshold.value = -0.18; g.compressor.knee.value = 0;
    g.compressor.ratio.value = 20; g.compressor.attack.value = 0; g.compressor.release.value = 0.08;
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
      key: [eq, space, orbit, cross, wet, limit, wet ? c.feedback : 0, wet ? c.pre : 0].join() };
  }

  function wire(p) {
    var g = EQ.g, ctx = EQ.ctx;
    g.all.forEach(function (n) { if (n !== g.out) try { n.disconnect(); } catch (e) {} });
    if (g.osc) { try { g.osc.stop(); g.osc.disconnect(); } catch (e) {} g.osc = null; }
    var verbKey = EQ.cfg.feedback + ":" + EQ.cfg.pre;
    if (p.wet && (!g.wasWet || g.verbKey !== verbKey)) {
      g.combs.forEach(function (c, i) {
        var old = c.d; c.d = ctx.createDelay(0.05); c.d.delayTime.value = COMBS[i];
        g.all[g.all.indexOf(old)] = c.d;
      });
    }
    g.wasWet = p.wet; g.verbKey = verbKey;
    g.preDelay.delayTime.cancelScheduledValues(ctx.currentTime);
    g.preDelay.delayTime.value = EQ.cfg.pre || 0;
    var n = g.input;
    if (p.eq || p.space || p.wet) { n.connect(g.pre); n = g.pre; }
    if (p.eq) {
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
    if (p.wet) {
      n.connect(g.verbHp); g.verbHp.connect(g.verbIn); g.verbIn.connect(g.preDelay);
      g.preDelay.connect(g.verbSplit);
      g.combs.forEach(function (c, i) {
        g.verbSplit.connect(c.d, i % 2);
        c.d.connect(c.lp); c.lp.connect(c.fb); c.fb.connect(c.d); // damped feedback loop
        c.d.connect(g.verbMerge, 0, i % 2);
      });
      g.verbMerge.connect(g.combOut); g.combOut.connect(g.wet); g.wet.connect(g.sum);
    }
    if (p.limit) { g.sum.connect(g.compressor); g.compressor.connect(g.limitIn); g.limitIn.connect(g.shaper); g.shaper.connect(g.out); }
    else g.sum.connect(g.out);
  }

  // Values only: smoothed, and only when they differ, so slider drags stay cheap.
  function setIf(p, v, last, start) {
    if (!p || !isFinite(v) || last[0] === v) return;
    last[0] = v;
    set(p, v, start);
  }
  var LAST = {};
  function slot(k) { return LAST[k] || (LAST[k] = [NaN]); }

  // Gain is linear but filter gains are in dB. Independent ramps can overload
  // between two safe presets. Hold a conservative bound across the whole ramp,
  // then restore the measured steady-state headroom on the audio timeline.
  function reserveTransitionHeadroom(c) {
    var g = EQ.g, old = g.valueConfig;
    var changed = !old || c.bassDb !== old.bassDb || c.preamp !== old.preamp ||
      c.bands.some(function (v, i) { return v !== old.bands[i]; });
    if (!changed) return undefined;
    var now = EQ.ctx.currentTime, p = g.pre.gain;
    var boost = Math.max(0, g.shelf.gain.value, c.bassDb);
    g.bands.forEach(function (f, i) { boost += Math.max(0, f.gain.value, c.bands[i] || 0); });
    function spatial(x) {
      return Math.max(1, x.sideGain || 1, x.orbit > 0 ? 1 + 1.1 * x.orbit : 1) * (1 + 2 * (x.wet || 0));
    }
    var reserve = Math.pow(10, -(boost + 0.5) / 20) / Math.max(spatial(c), old ? spatial(old) : 1);
    var held = p.value;
    p.cancelScheduledValues(now);
    p.setValueAtTime(held, now);
    var safe = Math.min(held, c.preamp, reserve);
    p.linearRampToValueAtTime(safe, now + 0.01);
    p.setValueAtTime(safe, now + 0.31);
    // Ten time constants leave less than 0.005 dB of even a 100 dB change;
    // the 0.5 dB reserve covers the residual. Native scheduling works hidden.
    p.setTargetAtTime(c.preamp, now + 0.31, 0.03);
    g.valueConfig = { bassDb: c.bassDb, preamp: c.preamp, bands: c.bands.slice(),
      sideGain: c.sideGain, orbit: c.orbit, wet: c.wet };
    return now + 0.01;
  }

  function values(c) {
    var g = EQ.g;
    var eqStart = reserveTransitionHeadroom(c);
    g.bands.forEach(function (f, i) {
      setIf(f.Q, c.q || 0.9, slot('q' + i), eqStart);
      setIf(f.gain, c.bands[i] || 0, slot('b' + i), eqStart);
    });
    setIf(g.shelf.frequency, c.bassHz || 120, slot('sf'), eqStart);
    setIf(g.shelf.gain, c.bassDb, slot('sg'), eqStart);
    setIf(g.side.gain, c.orbit > 0 ? c.orbitKeep : c.sideGain, slot('side'));
    setIf(g.orbitDepth.gain, c.orbit * 0.8, slot('od'));
    [g.toL, g.toR].forEach(function (x, i) {
      setIf(x.g.gain, c.crossGain, slot('cg' + i));
      setIf(x.d.delayTime, c.crossDelay || 0, slot('cd' + i));
    });
    setIf(g.accL.gain, c.crossNorm, slot('nL'));
    setIf(g.accR.gain, c.crossNorm, slot('nR'));
    setIf(g.preDelay.delayTime, c.pre || 0, slot('pd'));
    setIf(g.wet.gain, c.wet, slot('wet'));
    if (c.wet > 0) setVerb(c.feedback, c.damp);
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
