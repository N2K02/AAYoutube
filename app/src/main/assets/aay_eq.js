// AAYouTube equalizer: mRadio's SoundEngine signal path as a native Web Audio
// graph on YouTube's own <video>. Runs only inside this WebView, affects no other
// app, needs no ADB, and uses built-in nodes only (no AudioWorklet/blob module,
// so YouTube's CSP cannot block it).
//
//   video -> preamp -> 5x peaking (Q 0.9) -> 120 Hz low shelf
//         -> mid/side widening | 8D orbit pan -> delayed low-passed cross-feed
//         -> dry + reverb send -> mRadio soft limiter -> speakers
//
// Safety: a media element is routed into Web Audio only after the user enables
// the equalizer, only for MediaSource (blob:) playback (cross-origin progressive
// media would be silenced by CORS tainting), and only once the AudioContext is
// actually running, so playback can never be captured into a suspended graph.
(function () {
  if (window.__AAY_BROWSE || window.top !== window || window.__aayEqInstalled) return;
  window.__aayEqInstalled = true;
  var C = window.AudioContext || window.webkitAudioContext;
  var EQ = { cfg: window.__AAY_EQ || null, ctx: null, g: null, hooked: new WeakSet(), count: 0, status: '', ir: '' };

  function report(s) {
    if (EQ.status === s) return;
    EQ.status = s;
    try { var N = window.AAYNative; if (N && typeof N.eqStatus === 'function') N.eqStatus(s); } catch (e) {}
  }
  function want() { return !!(EQ.cfg && EQ.cfg.enabled); }
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
    // Freeverb-like decay: four ~28 ms combs at the given feedback.
    var key = feedback + ':' + damp;
    if (EQ.ir === key) return;
    EQ.ir = key;
    var ctx = EQ.ctx, rate = ctx.sampleRate;
    var t60 = Math.min(3, Math.max(0.3, 3 * 0.028 / -Math.log10(Math.max(0.01, Math.min(0.97, feedback)))));
    var len = Math.floor(rate * t60), buf = ctx.createBuffer(2, len, rate);
    for (var ch = 0; ch < 2; ch++) {
      var d = buf.getChannelData(ch), lp = 0, a = 0.2 + 0.75 * damp;
      for (var i = 0; i < len; i++) {
        lp = (Math.random() * 2 - 1) * (1 - a) + lp * a; // damping darkens the tail
        d[i] = lp * Math.exp(-6.9 * i / len);
      }
    }
    EQ.g.verb.buffer = buf;
  }

  function build() {
    var ctx = EQ.ctx, g = {};
    var gain = function (v) { var n = ctx.createGain(); n.gain.value = v; return n; };
    g.input = gain(1);
    // Like mRadio, play mono streams as stereo: the splitter below does not upmix.
    g.input.channelCount = 2; g.input.channelCountMode = 'explicit'; g.input.channelInterpretation = 'speakers';
    g.bands = (EQ.cfg && EQ.cfg.bandHz || [60, 230, 910, 3600, 14000]).map(function (hz) {
      var f = ctx.createBiquadFilter(); f.type = 'peaking'; f.frequency.value = hz; f.Q.value = 0.9; f.gain.value = 0; return f;
    });
    g.shelf = ctx.createBiquadFilter(); g.shelf.type = 'lowshelf'; g.shelf.frequency.value = 120; g.shelf.gain.value = 0;
    var node = g.input;
    g.bands.forEach(function (f) { node.connect(f); node = f; });
    node.connect(g.shelf);

    var split = ctx.createChannelSplitter(2);
    g.shelf.connect(split);
    var mid = gain(1), side = gain(1);
    var lm = gain(0.5), rm = gain(0.5), ls = gain(0.5), rs = gain(-0.5);
    split.connect(lm, 0); split.connect(rm, 1); split.connect(ls, 0); split.connect(rs, 1);
    lm.connect(mid); rm.connect(mid); ls.connect(side); rs.connect(side);

    var wl = gain(1), wr = gain(1);
    g.matrix = gain(1); mid.connect(g.matrix); g.matrix.connect(wl); g.matrix.connect(wr);
    g.side = gain(1); side.connect(g.side); g.side.connect(wl);
    var inv = gain(-1); g.side.connect(inv); inv.connect(wr);

    // 8D orbit: equal-power pan of the mid driven by a 1/9 Hz sine.
    g.orbitIn = gain(0); mid.connect(g.orbitIn);
    g.pan = ctx.createStereoPanner(); g.pan.pan.value = 0;
    g.orbitIn.connect(g.pan);
    var amp = gain(Math.SQRT2), ps = ctx.createChannelSplitter(2);
    g.pan.connect(amp); amp.connect(ps); ps.connect(wl, 0); ps.connect(wr, 1);
    g.osc = ctx.createOscillator(); g.osc.frequency.value = 1 / 9;
    g.orbitDepth = gain(0); g.osc.connect(g.orbitDepth); g.orbitDepth.connect(g.pan.pan);
    g.osc.start();

    // Headphone 3D cross-feed: each ear also hears the other side, late and muffled.
    g.accL = gain(1); g.accR = gain(1);
    wl.connect(g.accL); wr.connect(g.accR);
    var mk = function (from, to) {
      var d = ctx.createDelay(0.05), lp = ctx.createBiquadFilter(), cg = gain(0);
      d.delayTime.value = 0; lp.type = 'lowpass'; lp.frequency.value = 700; lp.Q.value = 0.7;
      from.connect(d); d.connect(lp); lp.connect(cg); cg.connect(to);
      return { d: d, g: cg };
    };
    g.toL = mk(wr, g.accL); g.toR = mk(wl, g.accR);

    var merge = ctx.createChannelMerger(2);
    g.accL.connect(merge, 0, 0); g.accR.connect(merge, 0, 1);
    g.limitIn = gain(1);
    merge.connect(g.limitIn);
    g.pre = ctx.createDelay(0.1); g.verb = ctx.createConvolver(); g.wet = gain(0);
    merge.connect(g.pre); g.pre.connect(g.verb); g.verb.connect(g.wet); g.wet.connect(g.limitIn);

    g.shaper = ctx.createWaveShaper(); g.post = gain(1);
    g.limitIn.connect(g.shaper); g.shaper.connect(g.post); g.post.connect(ctx.destination);
    EQ.g = g;
  }

  function apply() {
    var g = EQ.g, c = EQ.cfg;
    if (!g || !c) return;
    var on = !!c.enabled;
    set(g.input.gain, on ? c.preamp : 1);
    g.bands.forEach(function (f, i) {
      set(f.Q, c.q || 0.9);
      set(f.gain, on ? (c.bands[i] || 0) : 0);
    });
    set(g.shelf.frequency, c.bassHz || 120);
    set(g.shelf.gain, on ? c.bassDb : 0);
    var orbit = on ? (c.orbit || 0) : 0;
    set(g.matrix.gain, orbit > 0 ? 0 : 1);
    set(g.orbitIn.gain, orbit > 0 ? 1 : 0);
    set(g.side.gain, orbit > 0 ? c.orbitKeep : (on ? c.sideGain : 1));
    set(g.orbitDepth.gain, orbit * 0.8);
    if (c.orbitPeriod > 0) set(g.osc.frequency, 1 / c.orbitPeriod);
    var cross = on ? c.crossGain : 0;
    [g.toL, g.toR].forEach(function (x) { set(x.g.gain, cross); set(x.d.delayTime, c.crossDelay || 0); });
    set(g.accL.gain, on ? c.crossNorm : 1);
    set(g.accR.gain, on ? c.crossNorm : 1);
    var wet = on ? c.wet : 0;
    if (wet > 0) impulse(c.feedback, c.damp);
    set(g.pre.delayTime, c.pre || 0);
    set(g.wet.gain, wet * 0.6);
    var limit = on && !!c.limiter;
    g.shaper.curve = limit ? LIMIT_CURVE : null;
    set(g.limitIn.gain, limit ? 0.5 : 1);
  }

  function attach(v) {
    if (!v || EQ.hooked.has(v)) return;
    if (!want()) { report(settled()); return; }
    var src = v.currentSrc || v.src || '';
    if (src.indexOf('blob:') !== 0) { if (!EQ.count) report('unsupported'); return; }
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

  // A routed element is silent while the context is suspended: resume at once.
  function keepRunning() {
    if (EQ.ctx && EQ.count && EQ.ctx.state !== 'running' && EQ.ctx.state !== 'closed') {
      try { EQ.ctx.resume(); } catch (e) {}
    }
  }

  function scan() {
    var list = document.getElementsByTagName('video');
    for (var i = 0; i < list.length; i++) if (!list[i].paused) attach(list[i]);
  }

  document.addEventListener('playing', function (e) {
    var t = e.target;
    if (!(t instanceof HTMLMediaElement)) return;
    keepRunning();
    attach(t);
  }, true);

  var A = window.__aay || (window.__aay = {});
  A.eq = function (cfg) {
    if (!cfg || typeof cfg !== 'object') return;
    EQ.cfg = cfg;
    apply();
    if (want()) scan();
    if (!want()) report('off');
    else if (!(EQ.status === 'unsupported' && !EQ.count)) report(settled());
  };
  A.eqStatus = function () { return EQ.status || settled(); };
})();
