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
  M.videoOnly = false; M.lastId = ''; M.lastSeekAt = 0; M.quality = ''; M.qualityAt = 0; M.cap = '';
  M.doc = 0; M.seq = 0;
  // Every command names this document: a newer document invalidates the commands of older ones.
  try { var bb = window.AAYMirror; if (bb && bb.open) M.doc = bb.open(TOKEN) || 0; } catch (e) {}
  var gestureAt = 0, auth = null, ourActAt = 0, lifted = [], layoutTimers = [], touchStart = null;
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
    gestureAt = now;
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
  // A clicked link (anywhere on the page) names what the user chose, so only that destination is accepted.
  document.addEventListener('click', function (e) {
    if (!e.isTrusted) return;
    try {
      var a = anchorOf(e.target), b = bridge();
      if (a && a.href && b && b.intent) b.intent(String(a.href), TOKEN, M.doc);
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
  var swipeStart = null;
  document.addEventListener('touchstart', function (e) {
    swipeStart = null;
    if (M.videoOnly || e.touches.length !== 1) return;
    if (!/^\/(watch|live\/)/.test(location.pathname)) return;
    var t = e.target;
    if (!t || !t.closest) return;
    var full = !!(document.fullscreenElement || document.webkitFullscreenElement);
    if (!full && !closest(t, PLAYER_SEL)) return;
    swipeStart = { x: e.touches[0].clientX, y: e.touches[0].clientY, at: Date.now() };
  }, { capture: true, passive: true });
  document.addEventListener('touchend', function (e) {
    var start = swipeStart;
    swipeStart = null;
    if (!start || !e.isTrusted || !e.changedTouches.length) return;
    var dx = e.changedTouches[0].clientX - start.x;
    var dy = e.changedTouches[0].clientY - start.y;
    if (dy < 75 || Math.abs(dx) > dy * 0.65 || Date.now() - start.at > 1100) return;
    // Like the YouTube app: from fullscreen, a swipe down first leaves fullscreen.
    if (document.fullscreenElement || document.webkitFullscreenElement) {
      try { (document.exitFullscreen || document.webkitExitFullscreen).call(document); } catch (err) {}
      return;
    }
    send('mini', 0);
  }, { capture: true, passive: true });
  document.addEventListener('touchcancel', function () { swipeStart = null; }, true);

  /**
   * Follow the engine: result is "<driftMs>|<action>" (ok, nudge, seek, wait) or a reason
   * (novideo, mismatch, ad, user). Small drift: nudge the rate; large drift: one seek.
   * busy: the engine is seeking or buffering. cap: quality ceiling under load ('' = none).
   */
  M.sync = function (posMs, playing, rate, id, busy, cap) {
    var v = vid();
    if (!v) return 'novideo';
    // Only ever correct towards the same content: a stale or foreign page is left alone.
    if (id && currentVideoId() !== id) return 'mismatch';
    if (adShowing()) {
      if (M.stopped) { M.stopped = false; broadcast(); }
      if (v.paused) { expectOwn('play'); try { var adPlay = v.play(); if (adPlay && adPlay.catch) adPlay.catch(function () {}); } catch (e) {} }
      return 'ad';
    }
    if (M.lastId !== id) { M.lastId = id; M.lastSeekAt = 0; if (v.playbackRate !== 1) v.playbackRate = 1; }
    if (M.stopped) { M.stopped = false; broadcast(); }
    if (Date.now() - gestureAt < 1500) return 'user'; // let the user's action reach the engine first
    if ((cap || '') !== M.cap) { M.cap = cap || ''; M.quality = ''; }
    applyQuality();
    rate = rate > 0 ? rate : 1;
    var drift = v.currentTime - posMs / 1000;
    var action = 'ok';
    ourActAt = Date.now();
    if (playing) {
      if (v.paused) { expectOwn('play'); try { var p = v.play(); if (p && p.catch) p.catch(function () {}); } catch (e) {} }
    } else if (!v.paused) {
      expectOwn('pause');
      v.pause();
    }
    // Hysteresis: only touch the media pipeline when really out of step.
    var want = rate;
    if (Math.abs(drift) > 1.5 && isFinite(v.duration) && v.readyState > 0) {
      // Never seek while either player is seeking or buffering, nor stack seeks.
      if (!busy && !v.seeking && v.readyState >= 3 && Date.now() - M.lastSeekAt > 3000) {
        M.lastSeekAt = Date.now();
        expectOwn('seek');
        v.currentTime = posMs / 1000;
        action = 'seek';
      } else {
        action = 'wait';
      }
    } else if (playing && Math.abs(drift) > 0.5) {
      // No rate correction while either player is buffering or seeking: the drift is not real yet.
      if (busy || v.seeking || v.readyState < 3) { action = 'wait'; want = v.playbackRate; }
      else { want = rate * (drift > 0 ? 0.95 : 1.05); action = 'nudge'; }
    } else if (playing && v.playbackRate !== rate && Math.abs(drift) > 0.15) {
      want = v.playbackRate; // keep nudging until close, then settle
      action = 'nudge';
    }
    if (v.playbackRate !== want) v.playbackRate = want;
    return String(Math.round(drift * 1000)) + '|' + action + '|' + (v.videoHeight || 0);
  };

  /** Hidden: stop decoding and keep YouTube from restarting playback. */
  M.stop = function () {
    M.stopped = true;
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
      if (cur === want && M.quality === want) return;
      if (M.quality === want && Date.now() - M.qualityAt < 15000) return; // no churn
      p.setPlaybackQualityRange(want, want);
      M.quality = want;
      M.qualityAt = Date.now();
    } catch (e) {}
  }
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
