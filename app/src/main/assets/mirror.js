// AAYouTube phone mirror: the original YouTube player on the phone while
// Android Auto owns the audible player. Always muted; follows the car's clock.
// User taps on its own controls are forwarded to the single playback engine.
(function () {
  if (window.top !== window.self || window.__aayMirror) return;
  var TOKEN = window.__AAYM_TOKEN || '';
  var M = window.__aayMirror = { videoOnly: false };
  var ourUntil = 0, gestureAt = 0, lifted = [];

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

  // Never audible: the car plays the sound.
  try {
    var origPlay = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () {
      this.muted = true;
      return origPlay.apply(this, arguments);
    };
  } catch (e) {}
  ['volumechange', 'playing', 'loadedmetadata'].forEach(function (t) {
    document.addEventListener(t, function (e) {
      var v = e.target;
      if (v && v.tagName === 'VIDEO' && !v.muted) v.muted = true;
    }, true);
  });

  // Only trusted touches count as the user's own control actions.
  ['pointerup', 'click', 'touchend', 'keydown'].forEach(function (t) {
    document.addEventListener(t, function (e) { if (e.isTrusted) gestureAt = Date.now(); }, true);
  });
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

  /** Follow the engine. Small drift: nudge the rate; large drift: one seek. */
  M.sync = function (posMs, playing, rate) {
    var v = vid();
    if (!v) return 'novideo';
    if (adShowing()) return 'ad';
    if (Date.now() - gestureAt < 1500) return 'user'; // let the user's action reach the engine first
    rate = rate > 0 ? rate : 1;
    var drift = v.currentTime - posMs / 1000;
    ourUntil = Date.now() + 800;
    if (playing) {
      if (v.paused) { try { var p = v.play(); if (p && p.catch) p.catch(function () {}); } catch (e) {} }
    } else if (!v.paused) {
      v.pause();
    }
    if (Math.abs(drift) > 1.5 && isFinite(v.duration) && v.readyState > 0) {
      v.currentTime = posMs / 1000;
      v.playbackRate = rate;
    } else if (playing && Math.abs(drift) > 0.25) {
      v.playbackRate = rate * (drift > 0 ? 0.95 : 1.05);
    } else if (v.playbackRate !== rate) {
      v.playbackRate = rate;
    }
    return String(Math.round(drift * 1000));
  };

  /** Hidden: stop decoding. */
  M.stop = function () {
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
    'ytm-mobile-topbar-renderer button[aria-label="Open App"]{display:none!important}';
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

  function lift(on) {
    lifted.forEach(function (el) {
      el.style.removeProperty('transform'); el.style.removeProperty('contain');
      el.style.removeProperty('filter'); el.style.removeProperty('will-change');
    });
    lifted = [];
    var v = vid();
    if (!on || !v) return;
    for (var el = v.parentElement; el && el !== document.documentElement; el = el.parentElement) {
      var cs = getComputedStyle(el);
      if (cs.transform !== 'none' || (cs.contain && cs.contain !== 'none') || cs.filter !== 'none' ||
          cs.willChange.indexOf('transform') >= 0) {
        el.style.setProperty('transform', 'none', 'important');
        el.style.setProperty('contain', 'none', 'important');
        el.style.setProperty('filter', 'none', 'important');
        el.style.setProperty('will-change', 'auto', 'important');
        lifted.push(el);
      }
    }
  }
  M.layout = function (videoOnly) {
    M.videoOnly = !!videoOnly;
    installCss();
    document.documentElement.classList.toggle('aaym-video-only', M.videoOnly);
    lift(M.videoOnly);
    [0, 200, 600].forEach(function (ms) {
      setTimeout(function () {
        try {
          var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
          if (p && p.setInternalSize) p.setInternalSize();
        } catch (e) {}
        window.dispatchEvent(new Event('resize'));
        if (M.videoOnly) lift(true);
      }, ms);
    });
  };
  // A new watch document keeps the requested layout.
  window.addEventListener('yt-navigate-finish', function () { if (M.videoOnly) M.layout(true); });
})();
