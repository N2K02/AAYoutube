// AAYouTube phone mirror: the original YouTube player on the phone while
// Android Auto owns the audible player. Always muted; follows the car's clock.
// User taps on its own controls are forwarded to the single playback engine.
(function () {
  if (window.top !== window.self || window.__aayMirror) return;
  var TOKEN = window.__AAYM_TOKEN || '';
  var M = window.__aayMirror = { videoOnly: false, stopped: false, lastId: '', lastSeekAt: 0, quality: '' };
  var ourUntil = 0, gestureAt = 0, lifted = [], layoutTimers = [];
  var touchStart = null;
  var PLAYER_SEL = '.html5-video-player,#player-container-id,#player,ytm-player,ytm-watch-player,' +
    'ytm-player-component,#player-control-container,video';
  var SEEKBAR_SEL = '.ytp-progress-bar,.ytp-progress-bar-container,ytm-progress-bar,.progress-bar';

  function bridge() { return window.AAYMirror || null; }
  function send(action, value) {
    try { var b = bridge(); if (b) b.control(action, value || 0, TOKEN); } catch (e) {}
  }
  function vid() {
    return document.querySelector('video.html5-main-video') || document.querySelector('video');
  }
  function adShowing() {
    var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
    return !!(p && p.classList && p.classList.contains('ad-showing'));
  }

  function closest(t, sel) {
    try { return !!(t && t.closest && t.closest(sel)); } catch (e) { return false; }
  }
  function currentVideoId() {
    try {
      var q = new URLSearchParams(location.search).get('v');
      if (q) return q;
      var m = /^\/live\/([\w-]{11})/.exec(location.pathname);
      return m ? m[1] : '';
    } catch (e) { return ''; }
  }

  // Never audible, from the first frame: the car plays the sound. The element's own
  // muted/volume setters are locked, so YouTube cannot unmute it (startup, ads, navigation).
  function silence(v) {
    try { if (v && v.tagName === 'VIDEO') { v.muted = true; v.volume = 0; } } catch (e) {}
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
      if (M.stopped) return Promise.resolve();
      return origPlay.apply(this, arguments);
    };
  } catch (e) {}
  ['loadstart', 'loadedmetadata', 'canplay', 'play', 'playing', 'volumechange', 'seeking'].forEach(function (t) {
    document.addEventListener(t, function (e) {
      silence(e.target);
      if (M.stopped && e.target && e.target.tagName === 'VIDEO' && !e.target.paused && t !== 'volumechange') {
        ourUntil = Date.now() + 800;
        try { e.target.pause(); } catch (err) {}
      }
    }, true);
  });
  silence(document.querySelector && document.querySelector('video'));

  // Only a trusted tap (or a drag on the seek bar) INSIDE the player is the user's own control action.
  // Scrolling, taps elsewhere on the page and YouTube's own events never count.
  document.addEventListener('touchstart', function (e) {
    var t = e.touches && e.touches[0];
    touchStart = t ? { x: t.clientX, y: t.clientY } : null;
  }, { capture: true, passive: true });
  function gesture(e, needTap) {
    if (!e.isTrusted || !closest(e.target, PLAYER_SEL)) return;
    if (needTap && touchStart && e.changedTouches && e.changedTouches.length &&
        !closest(e.target, SEEKBAR_SEL)) {
      var dx = e.changedTouches[0].clientX - touchStart.x, dy = e.changedTouches[0].clientY - touchStart.y;
      if (dx * dx + dy * dy > 24 * 24) return; // a scroll or swipe, not a control tap
    }
    gestureAt = Date.now();
  }
  document.addEventListener('click', function (e) { gesture(e, false); }, true);
  document.addEventListener('touchend', function (e) { gesture(e, true); }, true);
  function userAction(windowMs) {
    var now = Date.now();
    return now - gestureAt < windowMs && now > ourUntil;
  }
  document.addEventListener('pause', function (e) {
    var v = e.target;
    if (!v || v.tagName !== 'VIDEO' || v.ended || adShowing()) return;
    if (userAction(900)) send('pause', 0);
  }, true);
  document.addEventListener('play', function (e) {
    var v = e.target;
    if (!v || v.tagName !== 'VIDEO' || adShowing()) return;
    if (userAction(900)) send('play', 0);
  }, true);
  document.addEventListener('seeked', function (e) {
    var v = e.target;
    if (!v || v.tagName !== 'VIDEO' || adShowing()) return;
    if (userAction(4000)) send('seek', Math.round(v.currentTime * 1000));
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
   */
  M.sync = function (posMs, playing, rate, id) {
    var v = vid();
    if (!v) return 'novideo';
    // Only ever correct towards the same content: a stale or foreign page is left alone.
    if (id && currentVideoId() !== id) return 'mismatch';
    if (adShowing()) return 'ad';
    if (M.lastId !== id) { M.lastId = id; M.lastSeekAt = 0; if (v.playbackRate !== 1) v.playbackRate = 1; }
    M.stopped = false;
    if (Date.now() - gestureAt < 1500) return 'user'; // let the user's action reach the engine first
    rate = rate > 0 ? rate : 1;
    var drift = v.currentTime - posMs / 1000;
    var action = 'ok';
    ourUntil = Date.now() + 800;
    if (playing) {
      if (v.paused) { try { var p = v.play(); if (p && p.catch) p.catch(function () {}); } catch (e) {} }
    } else if (!v.paused) {
      v.pause();
    }
    // Hysteresis: only touch the media pipeline when really out of step.
    var want = rate;
    if (Math.abs(drift) > 1.5 && isFinite(v.duration) && v.readyState > 0) {
      // Never stack seeks while the page is still seeking or buffering from the last one.
      if (!v.seeking && Date.now() - M.lastSeekAt > 3000) {
        M.lastSeekAt = Date.now();
        v.currentTime = posMs / 1000;
        action = 'seek';
      } else {
        action = 'wait';
      }
    } else if (playing && Math.abs(drift) > 0.5) {
      want = rate * (drift > 0 ? 0.95 : 1.05);
      action = 'nudge';
    } else if (playing && v.playbackRate !== rate && Math.abs(drift) > 0.15) {
      want = v.playbackRate; // keep nudging until close, then settle
      action = 'nudge';
    }
    if (v.playbackRate !== want) v.playbackRate = want;
    return String(Math.round(drift * 1000)) + '|' + action;
  };

  /** Hidden: stop decoding and keep YouTube from restarting playback. */
  M.stop = function () {
    M.stopped = true;
    var v = vid();
    ourUntil = Date.now() + 800;
    if (v && !v.paused) v.pause();
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
  // Decode only what the screen can show: a small tile needs far fewer pixels than the full page.
  function applyQuality() {
    var want = M.videoOnly ? 'medium' : 'large';
    if (M.quality === want) return;
    try {
      var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
      if (p && p.setPlaybackQualityRange) { p.setPlaybackQualityRange(want, want); M.quality = want; }
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
