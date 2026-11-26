// AAYouTube phone mirror: the original YouTube player on the phone while
// Android Auto owns the audible player. Always muted; follows the car's clock.
// User taps on its own controls are forwarded to the single playback engine.
(function () {
  // Audio isolation runs in EVERY frame (embedded players, ads) and from document start.
  if (window.__aayMuteInstalled) return;
  window.__aayMuteInstalled = true;
  var TOKEN = window.__AAYM_TOKEN || '';
  var isTop = window.top === window.self;
  var S = { stopped: false };
  // A new document starts in the app's current visibility, not in "playing".
  try { var b0 = window.AAYMirror; if (b0 && b0.hidden) S.stopped = !!b0.hidden(); } catch (e) {}

  function closest(t, sel) {
    try { return !!(t && t.closest && t.closest(sel)); } catch (e) { return false; }
  }
  function silence(v) {
    try { if (v && /^(VIDEO|AUDIO)$/.test(v.tagName)) { v.muted = true; v.volume = 0; } } catch (e) {}
  }
  function lockProp(name, forced) {
    try {
      var d = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, name);
      if (!d || !d.set || !d.get) return;
      Object.defineProperty(HTMLMediaElement.prototype, name, {
        configurable: true, enumerable: d.enumerable,
        get: function () { return d.get.call(this); },
        set: function () { d.set.call(this, forced); }
      });
    } catch (e) {}
  }
  lockProp('muted', true);
  lockProp('volume', 0);
  try {
    var origPlay = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () {
      silence(this);
      if (isTop && window.__aayMirror && window.__aayMirror.setCap) window.__aayMirror.setCap(window.__aayMirror.cap);
      // Hidden: YouTube must not restart playback behind the user's back.
      if (S.stopped) return Promise.resolve();
      return origPlay.apply(this, arguments);
    };
  } catch (e) {}
  ['loadstart', 'loadedmetadata', 'canplay', 'play', 'playing', 'volumechange', 'seeking'].forEach(function (t) {
    document.addEventListener(t, function (e) {
      silence(e.target);
      if (S.stopped && e.target && /^(VIDEO|AUDIO)$/.test(e.target.tagName) && !e.target.paused && t !== 'volumechange') {
        try { e.target.pause(); } catch (err) {}
      }
    }, true);
  });
  silence(document.querySelector && document.querySelector('video'));
  function pauseAll() {
    try { Array.prototype.forEach.call(document.querySelectorAll('video,audio'), function (v) { if (!v.paused) v.pause(); }); } catch (e) {}
  }
  // Existing frames follow the top document's visibility (new ones ask the app when they start).
  window.addEventListener('message', function (e) {
    var d = e && e.data;
    if (isTop || !d || typeof d.aayMirrorStopped !== 'boolean' || e.source !== window.parent) return;
    S.stopped = d.aayMirrorStopped;
    if (S.stopped) pauseAll();
    try { for (var i = 0; i < window.frames.length; i++) window.frames[i].postMessage(d, '*'); } catch (err) {}
  });
  if (!isTop) return;

  var M = window.__aayMirror = S;
  M.videoOnly = false; M.lastId = ''; M.lastSeekAt = 0; M.phase = 'align'; M.seek = null; M.seekFails = 0; M.alignSince = 0; M.quality = ''; M.qualityAt = 0; M.cap = '';
  try { if (b0 && b0.initialCap) M.cap = b0.initialCap() || ''; } catch (e) {}
  M.doc = 0; M.seq = 0;
  // Every command names this document: a newer document invalidates the commands of older ones.
  try { var bb = window.AAYMirror; if (bb && bb.open) M.doc = bb.open(TOKEN) || 0; } catch (e) {}
  var auth = null, ourActAt = 0, lifted = [], layoutTimers = [], touchStart = null;
  var lastTouchAuthAt = 0, lastVideoTapAt = 0, expectUntil = { play: 0, pause: 0, seek: 0 };
  var PLAYER_SEL = '.html5-video-player,#player-container-id,#player,ytm-player,ytm-watch-player,' +
    'ytm-player-component,#player-control-container,video';
  var SEEKBAR_SEL = '.ytp-progress-bar,.ytp-progress-bar-container,ytm-progress-bar,.progress-bar';
  var PLAYBACK_SEL = 'video,.html5-video-container,.ytp-play-button,.ytp-large-play-button,' +
    '.ytp-cued-thumbnail-overlay,button[aria-label*="Play" i],button[aria-label*="Pause" i],player-middle-controls';
  var NEXT_SEL = '.ytp-next-button,button[aria-label*="Next" i],[class*="next-button" i]';
  var PREV_SEL = '.ytp-prev-button,button[aria-label*="Previous" i],[class*="prev-button" i]';

  function bridge() { return window.AAYMirror || null; }
  function send(action, value) {
    try {
      var b = bridge();
      if (b) b.control(action, isFinite(value) && value > 0 ? value : 0, TOKEN, M.doc, ++M.seq, currentVideoId());
    } catch (e) {}
  }
  function vid() {
    return document.querySelector('video.html5-main-video') || document.querySelector('video');
  }
  function player() {
    return document.getElementById('movie_player') || document.querySelector('.html5-video-player');
  }
  function adShowing() {
    var p = player();
    return !!(p && p.classList && p.classList.contains('ad-showing'));
  }
  // One identity rule for watch, live and Shorts; the player's own data wins over the URL.
  function currentVideoId() {
    try {
      var p = player(), d = p && p.getVideoData && p.getVideoData();
      if (d && d.video_id) return d.video_id;
      var q = new URLSearchParams(location.search).get('v');
      if (q) return q;
      var m = /^\/(?:live|shorts)\/([\w-]{11})/.exec(location.pathname);
      return m ? m[1] : '';
    } catch (e) { return ''; }
  }
  function anchorOf(t) {
    try { return t && t.closest ? t.closest('a[href]') : null; } catch (e) { return null; }
  }
  function expectOwn(type) { expectUntil[type] = Date.now() + 2000; }
  function ownEvent(type) {
    if (expectUntil[type] && Date.now() < expectUntil[type]) { expectUntil[type] = 0; return true; }
    return false;
  }
  function broadcast() {
    try {
      for (var i = 0; i < window.frames.length; i++) window.frames[i].postMessage({ aayMirrorStopped: !!M.stopped }, '*');
    } catch (e) {}
  }

  // A trusted gesture authorises ONE kind of event, once, on the media element it targeted:
  //   video / play button -> play or pause; double tap on the video, or seek bar -> seek.
  // Other controls (settings, captions, titles), scrolling and untrusted events authorise nothing.
  document.addEventListener('touchstart', function (e) {
    var t = e.touches && e.touches[0];
    touchStart = t ? { x: t.clientX, y: t.clientY } : null;
  }, { capture: true, passive: true });
  function gesture(e, isTouch) {
    if (!e.isTrusted || !closest(e.target, PLAYER_SEL)) return;
    var now = Date.now();
    var bar = closest(e.target, SEEKBAR_SEL);
    if (!isTouch && now - lastTouchAuthAt < 700) { // the click of a touch already handled
      forward(e);
      return;
    }
    if (isTouch && touchStart && e.changedTouches && e.changedTouches.length && !bar) {
      var dx = e.changedTouches[0].clientX - touchStart.x, dy = e.changedTouches[0].clientY - touchStart.y;
      if (dx * dx + dy * dy > 24 * 24) return; // a scroll or swipe, not a control tap
    }
    if (isTouch) lastTouchAuthAt = now;
    var kind = null;
    if (bar) kind = 'seek';
    else if (closest(e.target, NEXT_SEL) || closest(e.target, PREV_SEL)) kind = null;
    else if (closest(e.target, PLAYBACK_SEL)) {
      var onVideo = closest(e.target, 'video,.html5-video-container');
      kind = onVideo && now - lastVideoTapAt < 400 ? 'seek' : 'tap'; // double tap seeks
      if (onVideo) lastVideoTapAt = now;
    }
    auth = kind ? { kind: kind, at: now, video: vid() } : null;
    if (!isTouch) forward(e);
  }
  /** Click only: Next/Previous go to the engine once, and a clicked link names what the user chose. */
  function forward(e) {
    if (e.type !== 'click') return;
    if (closest(e.target, NEXT_SEL)) { auth = null; send('next', 0); return; }
    if (closest(e.target, PREV_SEL)) { auth = null; send('prev', 0); return; }
  }
  var linkSequence = 0, pendingLink = null;
  M.navigationResult = function(doc, request, accepted) {
    if (doc !== M.doc || !pendingLink || pendingLink.request !== request) return;
    var url = pendingLink.url;
    pendingLink = null;
    if (accepted && !M.stopped && url) location.assign(url);
    else if (!accepted && !M.stopped && typeof window.alert === 'function') window.alert('Selection changed before it could be opened. Please tap the link again.');
  };
  // A clicked link names the destination. The native side acknowledges queued ownership and execution.
  document.addEventListener('click', function (e) {
    if (!e.isTrusted) return;
    try {
      var a = anchorOf(e.target), b = bridge();
      if (a && a.href && b && !closest(e.target, NEXT_SEL) && !closest(e.target, PREV_SEL)) {
        var req = String(++linkSequence), accepted = false;
        if (b.intentRequest) {
          pendingLink = { request: req, url: String(a.href) };
          accepted = b.intentRequest(String(a.href), TOKEN, M.doc, req);
          if (!accepted) pendingLink = null;
        } else if (b.intent) accepted = b.intent(String(a.href), TOKEN, M.doc);
        if (accepted) {
          if (e.preventDefault) e.preventDefault();
          if (e.stopImmediatePropagation) e.stopImmediatePropagation();
        }
      }
    } catch (err) {}
  }, true);
  document.addEventListener('click', function (e) { gesture(e, false); }, true);
  document.addEventListener('touchend', function (e) { gesture(e, true); }, true);

  // The event must follow its gesture, on the same element, after our own last action, within the window.
  function userAction(kind, windowMs, v) {
    var now = Date.now();
    if (!auth || auth.kind !== kind || now - auth.at >= windowMs || auth.at <= ourActAt || auth.video !== v) return false;
    auth = null; // one gesture, one action
    return true;
  }
  document.addEventListener('pause', function (e) {
    var v = e.target;
    if (!v || v.tagName !== 'VIDEO' || v.ended || adShowing() || M.stopped || ownEvent('pause')) return;
    if (userAction('tap', 900, v)) send('pause', 0);
  }, true);
  document.addEventListener('play', function (e) {
    var v = e.target;
    if (!v || v.tagName !== 'VIDEO' || adShowing() || M.stopped || ownEvent('play')) return;
    if (userAction('tap', 900, v)) send('play', 0);
  }, true);
  document.addEventListener('seeked', function (e) {
    var v = e.target;
    if (!v || v.tagName !== 'VIDEO' || adShowing() || M.stopped || ownEvent('seek')) return;
    if (userAction('seek', 4000, v)) send('seek', Math.round(v.currentTime * 1000));
  }, true);

  // Swipe down on the video: the phone's mini-player (same gesture as the normal player).
  // Only a real downward drag counts: the finger must move (touchmove) at least SWIPE_MIN_PX in
  // screen space, mostly vertically. A tap never does, even if the page shifts under the finger.
  var SWIPE_MIN_PX = 75, SWIPE_MIN_MOVES = 3, SWIPE_MAX_MS = 1100;
  var swipeStart = null;
  function screenY(t) { return typeof t.screenY === 'number' ? t.screenY : t.clientY; }
  function screenX(t) { return typeof t.screenX === 'number' ? t.screenX : t.clientX; }
  document.addEventListener('touchstart', function (e) {
    swipeStart = null;
    if (M.videoOnly || e.touches.length !== 1) return;
    if (!/^\/(watch|live\/|shorts\/)/.test(location.pathname)) return;
    var t = e.target;
    if (!t || !t.closest) return;
    var full = !!(document.fullscreenElement || document.webkitFullscreenElement);
    if (!full && !closest(t, PLAYER_SEL)) return;
    if (closest(t, SEEKBAR_SEL)) return; // scrubbing is never a swipe
    var p0 = e.touches[0];
    swipeStart = { x: screenX(p0), y: screenY(p0), at: Date.now(), moves: 0, maxDy: 0, lastDy: 0, lastDx: 0 };
  }, { capture: true, passive: true });
  document.addEventListener('touchmove', function (e) {
    var st = swipeStart;
    if (!st) return;
    if (e.touches.length !== 1) { swipeStart = null; return; }
    var p1 = e.touches[0];
    st.moves++;
    st.lastDx = screenX(p1) - st.x;
    st.lastDy = screenY(p1) - st.y;
    if (st.lastDy > st.maxDy) st.maxDy = st.lastDy;
  }, { capture: true, passive: true });
  document.addEventListener('touchend', function (e) {
    var start = swipeStart;
    swipeStart = null;
    if (!start || !e.isTrusted || !e.changedTouches.length || (e.touches && e.touches.length)) return;
    var end = e.changedTouches[0];
    var dx = screenX(end) - start.x;
    var dy = screenY(end) - start.y;
    if (!M.swipeOk(start, dx, dy, Date.now() - start.at)) return;
    // Like the YouTube app: from fullscreen, a swipe down first leaves fullscreen.
    if (document.fullscreenElement || document.webkitFullscreenElement) {
      try { (document.exitFullscreen || document.webkitExitFullscreen).call(document); } catch (err) {}
      return;
    }
    send('mini', 0);
  }, { capture: true, passive: true });
  /** A swipe is a tracked drag that went down and ended down, not a tap whose coordinates jumped. */
  M.swipeOk = function (st, dx, dy, ms) {
    if (ms > SWIPE_MAX_MS || st.moves < SWIPE_MIN_MOVES) return false;
    if (dy < SWIPE_MIN_PX || st.lastDy < SWIPE_MIN_PX * 0.8 || st.maxDy < SWIPE_MIN_PX) return false;
    return Math.abs(dx) <= dy * 0.65 && Math.abs(st.lastDx) <= st.lastDy * 0.65;
  };
  // Chromium cancels the touch when it decides the drag is a page scroll: a drag that was already
  // a clear swipe down on the player still counts.
  document.addEventListener('touchcancel', function () {
    var st = swipeStart;
    swipeStart = null;
    if (!st || !M.swipeOk(st, st.lastDx, st.lastDy, Date.now() - st.at)) return;
    if (document.fullscreenElement || document.webkitFullscreenElement) {
      try { (document.exitFullscreen || document.webkitExitFullscreen).call(document); } catch (err) {}
      return;
    }
    send('mini', 0);
  }, true);

  /**
   * Follow the engine: result is "<driftMs>|<action>" (ok, nudge, seek, wait) or a reason
   * (novideo, mismatch, ad, user). Small drift: nudge the rate; large drift: one seek.
   * busy: the engine is seeking or buffering. cap: quality ceiling under load ('' = none).
   */
  // Alignment: a freshly loaded or resumed page is hidden until it shows the engine's position.
  var ALIGN_SHOW_MS = 1000, STABLE_MS = 300, SEEK_DRIFT_MS = 1500, ALIGN_SEEK_MS = 1000;
  var SEEK_VERIFY_MS = 4000, SEEK_TOLERANCE_MS = 1000, MAX_SEEK_FAILS = 3, ALIGN_GIVE_UP_MS = 8000;
  var LIVE_DRIFT_MS = 3000;
  function setAligning(on) {
    try { document.documentElement.classList.toggle('aaym-aligning', !!on); } catch (e) {}
  }
  function beginAlign() {
    M.phase = 'align'; M.seek = null; M.seekFails = 0; M.alignSince = Date.now();
    setAligning(true);
  }
  function isLive(v) {
    if (!isFinite(v.duration)) return true;
    try { var d = player() && player().getVideoData && player().getVideoData(); return !!(d && d.isLive); } catch (e) { return false; }
  }
  /** Valid target inside the media: VOD [0, end), live within the seekable window. */
  function clampTarget(v, sec) {
    if (!isFinite(sec) || sec < 0) sec = 0;
    if (isFinite(v.duration) && v.duration > 0) return Math.min(sec, Math.max(0, v.duration - 0.25));
    try {
      var r = v.seekable;
      if (r && r.length) return Math.min(Math.max(sec, r.start(0)), r.end(r.length - 1));
    } catch (e) {}
    return sec;
  }
  // Hidden from the first frame until aligned; a safety net never leaves it hidden if no sync arrives.
  setAligning(true);
  setTimeout(function () { if (M.phase === 'align' && !M.stopped && !M.alignSince) setAligning(false); }, 10000);
  document.addEventListener('seeked', function (e) {
    var v = e.target;
    if (!v || v.tagName !== 'VIDEO' || !M.seek || M.seek.done || M.seek.video !== v || v.seeking || adShowing()) return;
    M.seek.done = true;
    M.seek.landedMs = Math.round(v.currentTime * 1000);
  }, true);
  function issueSeek(v, targetMs, playing) {
    // Playing: aim a little ahead, the seek itself takes time.
    var sec = clampTarget(v, targetMs / 1000 + (playing ? 0.25 : 0));
    M.seek = { video: v, target: Math.round(sec * 1000), at: Date.now(), done: false, landedMs: -1 };
    M.lastSeekAt = Date.now();
    if (M.phase === 'stable') M.phase = 'seeking';
    expectOwn('seek');
    try { v.currentTime = sec; } catch (e) { M.seek.done = true; M.seek.landedMs = -1; }
  }
  function out(drift, action, v, seekInfo) {
    return [Math.round(drift), action, v.videoHeight || 0, M.phase, Math.round(v.currentTime * 1000),
      seekInfo ? seekInfo.target : -1, seekInfo ? seekInfo.landedMs : -1].join('|');
  }

  /**
   * Follow the engine. Result: "<driftMs>|<action>|<height>|<phase>|<phoneMs>|<seekTargetMs>|<seekLandedMs>"
   * or a bare reason (novideo, mismatch, ad, user, seekfail). Phases: align (just loaded or resumed,
   * hidden), seeking (a correction in flight, verified before the next one), stable.
   */
  M.sync = function (posMs, playing, rate, id, busy, cap) {
    var v = vid();
    if (!v) return 'novideo';
    // Only ever correct towards the same content: a stale or foreign page is left alone.
    if (id && currentVideoId() !== id) return 'mismatch';
    if ((cap || '') !== M.cap) { M.cap = cap || ''; }
    applyQuality();
    if (adShowing()) {
      if (M.seek || M.phase !== 'align') beginAlign();
      if (!playing || busy) { if (!v.paused) { expectOwn('pause'); v.pause(); } return 'ad'; }
      if (M.stopped) { M.stopped = false; broadcast(); }
      if (v.paused) { expectOwn('play'); try { var adPlay = v.play(); if (adPlay && adPlay.catch) adPlay.catch(function () {}); } catch (e) {} }
      return 'ad';
    }
    if (M.lastId !== id || M.lastVideo !== v) { M.lastId = id; M.lastVideo = v; M.lastSeekAt = 0; auth = null; if (v.playbackRate !== 1) v.playbackRate = 1; beginAlign(); }
    if (M.stopped) { M.stopped = false; broadcast(); beginAlign(); }
    // Pause and primary buffering outrank pending phone gestures. Only protect a pending seek.
    if ((!playing || busy) && !v.paused) { expectOwn('pause'); v.pause(); auth = null; }
    if (playing && !busy && auth && auth.kind === 'seek' && Date.now() - auth.at < 1500) return 'user';
    rate = rate > 0 ? rate : 1;
    var now = Date.now();
    var drift = v.currentTime * 1000 - posMs;
    var live = isLive(v);
    ourActAt = now;
    if (playing && !busy && M.phase === 'stable') {
      if (v.paused) { expectOwn('play'); try { var p = v.play(); if (p && p.catch) p.catch(function () {}); } catch (e) {} }
    } else if (!v.paused && (!playing || busy)) {
      expectOwn('pause');
      v.pause();
    }
    // A correction in flight is verified before anything else is corrected.
    var seekInfo = null;
    if (M.seek && M.seek.video !== v) beginAlign();
    if (M.seek) {
      if (!M.seek.done && now - M.seek.at < SEEK_VERIFY_MS) {
        if (v.playbackRate !== rate) v.playbackRate = rate;
        return out(drift, 'wait', v, null);
      }
      seekInfo = M.seek;
      M.seek = null;
      var landed = seekInfo.done && Math.abs(seekInfo.landedMs - seekInfo.target) <= SEEK_TOLERANCE_MS;
      if (landed) M.seekFails = 0;
      else if (++M.seekFails >= MAX_SEEK_FAILS) {
        // Controlled recovery: the app reloads the page at the engine's position.
        M.seekFails = 0;
        beginAlign();
        return 'seekfail';
      }
      if (M.phase === 'seeking') M.phase = 'stable';
    }
    // Initial alignment does not wait for the car to finish buffering: its position is known.
    if (M.phase === 'align' && busy && !v.seeking && v.readyState >= 1 && Math.abs(drift) > (isLive(v) ? LIVE_DRIFT_MS : ALIGN_SEEK_MS)) {
      issueSeek(v, posMs, false);
      return out(drift, 'seek', v, seekInfo);
    }
    // Never leave the phone black while the car buffers: close enough (or waited long enough) is shown.
    if (M.phase === 'align' && !v.seeking && v.readyState >= 2 &&
        (Math.abs(drift) <= ALIGN_SEEK_MS || now - M.alignSince > ALIGN_GIVE_UP_MS) && (busy || !playing)) {
      M.phase = 'stable';
      setAligning(false);
    }
    if (busy) { if (v.playbackRate !== rate) v.playbackRate = rate; return out(drift, 'wait', v, seekInfo); }
    var abs = Math.abs(drift), action = 'ok', want = rate;
    if (M.phase === 'align') {
      // A paused or never-buffered page still aligns: seeking does not need readyState >= 3.
      if (!v.seeking && v.readyState >= 1 && abs > (live ? LIVE_DRIFT_MS : ALIGN_SEEK_MS)) {
        issueSeek(v, posMs, playing);
        return out(drift, 'seek', v, seekInfo);
      }
      if (v.seeking || v.readyState < 1) return out(drift, 'wait', v, seekInfo);
      // Within 1 s: show now and let the steady correction close the rest (a long hidden nudge looked black).
      if (abs <= (live ? LIVE_DRIFT_MS : ALIGN_SHOW_MS) || !playing || now - M.alignSince > ALIGN_GIVE_UP_MS) {
        M.phase = 'stable';
        setAligning(false);
        if (playing && v.paused) { expectOwn('play'); try { var p2 = v.play(); if (p2 && p2.catch) p2.catch(function () {}); } catch (e) {} }
      } else {
        // Close enough to play: converge by rate while still hidden.
        if (playing && v.paused) { expectOwn('play'); try { var p3 = v.play(); if (p3 && p3.catch) p3.catch(function () {}); } catch (e) {} }
        want = rate * (drift > 0 ? 0.94 : 1.06);
        if (v.playbackRate !== want) v.playbackRate = want;
        return out(drift, 'nudge', v, seekInfo);
      }
    }
    if (v.seeking || v.readyState < 3) { if (v.playbackRate !== rate) v.playbackRate = rate; return out(drift, 'wait', v, seekInfo); }
    if (live) {
      // Live: no rate games against a moving edge; one seek inside the window when far off.
      if (abs > LIVE_DRIFT_MS && now - M.lastSeekAt > SEEK_VERIFY_MS) { issueSeek(v, posMs, playing); action = 'seek'; }
    } else if (abs > SEEK_DRIFT_MS) {
      if (now - M.lastSeekAt > 3000) { issueSeek(v, posMs, playing); action = 'seek'; }
      else action = 'wait';
    } else if (playing && abs > STABLE_MS) {
      // Direction follows the current sign of the drift on every sync: never an obsolete correction.
      var k = abs > 600 ? 0.06 : 0.03;
      want = rate * (drift > 0 ? 1 - k : 1 + k);
      action = 'nudge';
    }
    if (v.playbackRate !== want) v.playbackRate = want;
    return out(drift, action, v, seekInfo);
  };

  /** Hidden: stop decoding and keep YouTube from restarting playback. */
  M.stop = function () {
    M.stopped = true;
    M.phase = 'align'; M.seek = null;
    setAligning(true);
    var v = vid();
    ourActAt = Date.now();
    auth = null;
    broadcast();
    if (v && !v.paused) expectOwn('pause');
    pauseAll();
  };

  // Mini-player / PiP: only the video, filling the small WebView.
  var CSS =
    'html.aaym-video-only,html.aaym-video-only body{background:#000!important;overflow:hidden!important;' +
    'width:100%!important;height:100%!important;margin:0!important;padding:0!important}' +
    'html.aaym-video-only #player-container-id,html.aaym-video-only .player-container,' +
    'html.aaym-video-only #player-container,html.aaym-video-only ytm-watch-player,' +
    'html.aaym-video-only ytm-player,html.aaym-video-only ytm-player-component,' +
    'html.aaym-video-only ytm-player-container,html.aaym-video-only .player-size{' +
    'position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;' +
    'max-width:none!important;max-height:none!important;min-height:0!important;' +
    'margin:0!important;padding:0!important;transform:none!important;' +
    'background:#000!important;z-index:2147483000!important}' +
    'html.aaym-video-only #player,html.aaym-video-only #movie_player,' +
    'html.aaym-video-only .html5-video-player,html.aaym-video-only .html5-video-container{' +
    'position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;' +
    'max-width:none!important;max-height:none!important;transform:none!important;' +
    'margin:0!important;padding:0!important;z-index:2147483001!important}' +
    'html.aaym-video-only video.html5-main-video{' +
    'position:absolute!important;inset:0!important;width:100%!important;height:100%!important;' +
    'max-width:none!important;max-height:none!important;object-fit:contain!important;' +
    'transform:none!important;visibility:visible!important;z-index:2147483002!important}' +
    'html.aaym-video-only .ytp-chrome-top,html.aaym-video-only .ytp-chrome-bottom,' +
    'html.aaym-video-only .ytp-gradient-top,html.aaym-video-only .ytp-gradient-bottom,' +
    'html.aaym-video-only .ytp-pause-overlay,html.aaym-video-only .ytp-ce-element,' +
    'html.aaym-video-only .player-controls-background,html.aaym-video-only player-middle-controls,' +
    'html.aaym-video-only .ytp-endscreen-content{display:none!important}' +
    '.ytp-unmute,ytm-unmute-button,.ytmUnmuteButton,.ytp-unmute-button,' +
    '[class*="tap-to-unmute" i],[class*="unmute-overlay" i]{display:none!important}' +
    'ytm-mobile-topbar-renderer a[href*="intent://"],ytm-mobile-topbar-renderer a[aria-label="Open App"],' +
    'ytm-mobile-topbar-renderer button[aria-label="Open App"]{display:none!important}' +
    'ytm-inline-playback-renderer,.inline-player-container,#inline-preview-player{display:none!important}';
  CSS += 'html.aaym-aligning video{opacity:0!important}' +
    // Mini-player / PiP: never the page chrome, also while YouTube swaps the watch page.
    'html.aaym-video-only ytm-mobile-topbar-renderer,html.aaym-video-only #header-bar,' +
    'html.aaym-video-only header,html.aaym-video-only #masthead-container,html.aaym-video-only #masthead,' +
    'html.aaym-video-only ytm-pivot-bar-renderer,html.aaym-video-only .mobile-topbar-header,' +
    'html.aaym-video-only ytm-single-column-watch-next-results-renderer,html.aaym-video-only #below,' +
    'html.aaym-video-only ytm-engagement-panel,html.aaym-video-only .watch-below-the-player,' +
    'html.aaym-video-only ytm-slim-video-metadata-section-renderer{display:none!important}';
  function installCss() {
    if (document.getElementById('aaym-css')) return;
    var root = document.head || document.documentElement;
    if (!root) return;
    var s = document.createElement('style');
    s.id = 'aaym-css';
    s.textContent = CSS;
    root.appendChild(s);
  }
  installCss();
  document.addEventListener('DOMContentLoaded', installCss);
  // A new document (next video, autoplay) starts in the current layout, before YouTube paints.
  try {
    var b1 = window.AAYMirror;
    if (b1 && b1.videoOnly && b1.videoOnly()) { M.videoOnly = true; document.documentElement.classList.toggle('aaym-video-only', true); }
  } catch (e) {}

  var LIFT_PROPS = ['transform', 'contain', 'filter', 'will-change'];
  function lift(on) {
    // Put back exactly what the page had inline (value and priority), not just clear it.
    lifted.forEach(function (rec) {
      LIFT_PROPS.forEach(function (name) {
        var o = rec.saved[name];
        if (o && o.value) rec.el.style.setProperty(name, o.value, o.priority);
        else rec.el.style.removeProperty(name);
      });
    });
    lifted = [];
    var v = vid();
    if (!on || !v) return;
    for (var el = v.parentElement; el && el !== document.documentElement; el = el.parentElement) {
      var cs = getComputedStyle(el);
      if (cs.transform !== 'none' || (cs.contain && cs.contain !== 'none') || cs.filter !== 'none' ||
          cs.willChange.indexOf('transform') >= 0) {
        var saved = {};
        LIFT_PROPS.forEach(function (name) {
          saved[name] = { value: el.style.getPropertyValue(name), priority: el.style.getPropertyPriority(name) };
        });
        el.style.setProperty('transform', 'none', 'important');
        el.style.setProperty('contain', 'none', 'important');
        el.style.setProperty('filter', 'none', 'important');
        el.style.setProperty('will-change', 'auto', 'important');
        lifted.push({ el: el, saved: saved });
      }
    }
  }
  // Quality follows the real on-screen size (and drops under load); the choice is verified
  // against what the player reports instead of trusting the request.
  var TIERS = ['small', 'medium', 'large', 'hd720'];
  // Same table as PhoneMirrorPolicy.QUALITY_HEIGHT.
  var QUALITY_HEIGHT = { small: 240, medium: 360, large: 480, hd720: 720 };
  M.qualityHeight = QUALITY_HEIGHT;
  function wantedQuality() {
    var v = vid();
    var px = v && v.clientHeight ? v.clientHeight * (window.devicePixelRatio || 1) : 0;
    var want = px ? (px <= 300 ? 'small' : px <= 480 ? 'medium' : px <= 760 ? 'large' : 'hd720')
                  : (M.videoOnly ? 'medium' : 'large');
    if (M.videoOnly && TIERS.indexOf(want) > 1) want = 'medium';
    if (M.cap && TIERS.indexOf(M.cap) < TIERS.indexOf(want)) want = M.cap;
    return want;
  }
  function applyQuality() {
    try {
      var p = player();
      if (!p || !p.setPlaybackQualityRange) return;
      if (p !== M.qualityPlayer) { M.qualityPlayer = p; M.quality = ''; } // a replaced player starts unlimited
      var want = wantedQuality();
      var cur = p.getPlaybackQuality ? p.getPlaybackQuality() : M.quality;
      var media = vid(), height = media && media.videoHeight || 0;
      var limit = QUALITY_HEIGHT[want] || 720;
      if (cur === want && M.quality === want && (!height || height <= limit)) return;
      if (M.quality === want && Date.now() - M.qualityAt < 15000) return; // no churn
      // Recovering sync: only ever lower quality; raising it would rebuffer and undo the alignment.
      if (M.quality && M.phase !== 'stable' && TIERS.indexOf(want) > TIERS.indexOf(M.quality)) return;
      p.setPlaybackQualityRange(want, want);
      M.quality = want;
      M.qualityAt = Date.now();
    } catch (e) {}
  }
  M.setCap = function (cap) {
    if ((cap || '') !== M.cap) { M.cap = cap || ''; M.quality = ''; }
    applyQuality();
  };
  document.addEventListener('loadedmetadata', function () { applyQuality(); }, true);
  function clearLayoutTimers() {
    layoutTimers.forEach(clearTimeout);
    layoutTimers = [];
  }
  M.layout = function (videoOnly) {
    var changed = M.videoOnly !== !!videoOnly;
    M.videoOnly = !!videoOnly;
    installCss();
    document.documentElement.classList.toggle('aaym-video-only', M.videoOnly);
    lift(M.videoOnly);
    applyQuality();
    // A newer mode change supersedes the delayed passes of the previous one.
    clearLayoutTimers();
    (changed || M.videoOnly ? [0, 200, 600] : [0]).forEach(function (ms) {
      layoutTimers.push(setTimeout(function () {
        try {
          var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
          if (p && p.setInternalSize) p.setInternalSize();
        } catch (e) {}
        window.dispatchEvent(new Event('resize'));
        if (M.videoOnly) lift(true);
        applyQuality();
      }, ms));
    });
  };
  // A new watch document keeps the requested layout.
  window.addEventListener('yt-navigate-finish', function () { if (M.videoOnly) M.layout(true); });
})();
