/*
 * AAYoutube page script. Injected before YouTube's own scripts run (document start).
 * Settings arrive as window.__AAY_CFG and can be changed live with __aay.config({...}).
 * Everything is best-effort: YouTube changes its page often, so each feature is isolated in
 * try/catch and a failing one never breaks the others.
 */
(function () {
  'use strict';
  if (window.top !== window.self) return;
  if (window.__aay) return;
  if (!/(^|\.)youtube\.com$/.test(location.hostname)) return;

  var CFG = window.__AAY_CFG || {};
  var N = window.AAYNative || null;
  var BROWSE = !!window.__AAY_BROWSE;
  // YouTube reads this preference during player creation. Persist it before
  // its scripts run so initial playback need not start at adaptive 144p.
  function writeYouTubeQuality(q) {
    if (BROWSE) return;
    try {
      if (q === 'auto') localStorage.removeItem('yt-player-quality');
      else if (/^(tiny|small|medium|large|hd720|hd1080|hd1440|hd2160|highres)$/.test(q)) {
        var now = Date.now();
        localStorage.setItem('yt-player-quality', JSON.stringify({
          data: q, creation: now, expiration: now + 2419200000
        }));
      }
    } catch (e) {} // Storage can be disabled by a WebView profile.
  }
  if (!BROWSE) writeYouTubeQuality(CFG.preferredQuality || 'hd720');
  // The secondary browse WebView is never allowed competing audible playback.
  if (BROWSE) {
    try {
      var browsePlay = HTMLMediaElement.prototype.play;
      HTMLMediaElement.prototype.play = function () {
        this.muted = true;
        return browsePlay.apply(this, arguments);
      };
      document.addEventListener('playing', function (event) {
        if (event.target && event.target instanceof HTMLMediaElement)
          event.target.muted = true;
      }, true);
    } catch (e) {}
  }
  var A = window.__aay = { H: {}, isBg: false, isCar: !!window.__AAY_CAR };
  var H = A.H; // media session action handlers YouTube registers (nexttrack, previoustrack, ...)
  // YouTube provides a real login flag in its page configuration. Generic Google
  // cookies and avatar placeholders are not proof that YouTube is signed in.
  var lastAccountReport = '';
  function reportAccount() {
    if (BROWSE || !N || typeof N.account !== 'function') return;
    try {
      var cfg = window.ytcfg;
      if (!cfg) return;
      var signedIn = typeof cfg.get === 'function' ? cfg.get('LOGGED_IN') : undefined;
      if (typeof signedIn !== 'boolean' && cfg.data_)
        signedIn = cfg.data_.LOGGED_IN;
      if (typeof signedIn !== 'boolean') return;
      var status = signedIn ? 'signed_in' : 'signed_out';
      if (status !== lastAccountReport) {
        lastAccountReport = status;
        N.account(status);
      }
    } catch (e) {}
  }
  A.checkAccount = reportAccount;
  if (!BROWSE) {
    window.addEventListener('yt-navigate-finish', reportAccount);
    window.addEventListener('pageshow', reportAccount);
    // Navigation/events handle normal updates; use a slow fallback while a tab stays open.
    setInterval(reportAccount, 30000);
  }

  function later(fn, ms) { return setTimeout(function () { try { fn(); } catch (e) {} }, ms || 0); }

  /* ---------- 1. Background play: make the page believe it is always visible ---------- */
  try {
    var spoof = function (prop, fake) {
      var real = Object.getOwnPropertyDescriptor(Document.prototype, prop);
      Object.defineProperty(document, prop, {
        configurable: true,
        get: function () {
          return CFG.backgroundPlay !== false ? fake : (real && real.get ? real.get.call(document) : fake);
        }
      });
    };
    spoof('hidden', false);
    spoof('webkitHidden', false);
    spoof('visibilityState', 'visible');
    spoof('webkitVisibilityState', 'visible');
    var stopVis = function (e) { if (CFG.backgroundPlay !== false) e.stopImmediatePropagation(); };
    document.addEventListener('visibilitychange', stopVis, true);
    document.addEventListener('webkitvisibilitychange', stopVis, true);
    // Picture in picture and the background also take the focus away; YouTube treats that as "left".
    var realHasFocus = Document.prototype.hasFocus;
    document.hasFocus = function () { return CFG.backgroundPlay !== false ? true : realHasFocus.call(document); };
    ['blur', 'pagehide', 'freeze'].forEach(function (t) {
      window.addEventListener(t, function (e) {
        if (CFG.backgroundPlay !== false && (e.target === window || e.target === document)) e.stopImmediatePropagation();
      }, true);
    });
    document.addEventListener('freeze', stopVis, true);
  } catch (e) {}

  /* ---------- 1b. Keep playing when the screen is hidden or in picture in picture ---------- */
  // YouTube's own code (or the browser) may call pause() when the screen changes. While the app is
  // in the background or in a small window, such a pause is ignored; real ones (A.cmd 'pause') go through.
  var allowPause = false, ignored = 0, userPauseEligibleUntil = 0;
  function holding() { return CFG.backgroundPlay !== false && (A.hold || A.isBg || A.inPip || A.isMini); }

  // YouTube's on-screen controls are a real Pause even if background/PiP
  // protection is armed. Only trusted playback gestures grant this short window;
  // unrelated DOM clicks must not disable interruption recovery.
  function manualPauseGesture(e) {
    if (BROWSE || e.isTrusted === false) return;
    var v = getVideo();
    if (!v) return;
    var t = e.target;
    if (t && t.closest && t.closest(
      'video, .ytp-play-button, #play-pause-button, .play-pause-button,' +
      'ytm-play-pause-button, .ytm-player-controls-play-pause-button,' +
      'button[aria-label*="pause" i], button[title*="pause" i],' +
      '[data-testid="control-button-pause"], [data-testid="control-button-playpause"]')) {
      if (v.paused) {
        PAUSED = false;
        bgWatch.explicitPause = false;
        bgWatch.wanted = true;
        userPauseEligibleUntil = 0;
      } else userPauseEligibleUntil = Date.now() + 2000;
    }
  }
  document.addEventListener('pointerdown', manualPauseGesture, true);
  document.addEventListener('click', manualPauseGesture, true);
  document.addEventListener('keydown', function (e) {
    if (BROWSE || e.isTrusted === false || e.repeat) return;
    var t = e.target, v = getVideo();
    if (!v || (t && t.closest && t.closest('input, textarea, [contenteditable]'))) return;
    if (e.key === 'k' || e.key === 'K' || e.key === ' ' ||
        e.key === 'MediaPause' || e.key === 'MediaPlayPause') {
      if (v.paused) {
        PAUSED = false;
        bgWatch.explicitPause = false;
        bgWatch.wanted = true;
        userPauseEligibleUntil = 0;
      } else userPauseEligibleUntil = Date.now() + 2000;
    }
  }, true);
  function noteManualPause() {
    AUTO = false; // cancel autoplay still pending during YouTube page load
    PAUSED = true;
    bgWatch.explicitPause = true;
    bgWatch.wanted = false;
    bgWatch.attempts = 0;
    userPauseEligibleUntil = 0;
    try { if (N && typeof N.userPause === 'function') N.userPause(); } catch (e) {}
    console.log('[aay] intentional YouTube player pause');
  }
  try {
    var origPause = HTMLMediaElement.prototype.pause;
    HTMLMediaElement.prototype.pause = function () {
      var manual = !allowPause && Date.now() <= userPauseEligibleUntil;
      if (!allowPause && !manual && holding() && !this.ended) {
        ignored++;
        // Some YouTube builds request hundreds of pauses during a PiP resize.
        // Capturing an Error stack on every intercepted call causes real UI jank.
        if (ignored <= 2 || ignored % 250 === 0) {
          console.log('[aay] pause() ignored x' + ignored +
            ' (bg=' + A.isBg + ' pip=' + A.inPip + ' hold=' + A.hold + ')');
        }
        return;
      }
      // Intent is established by a trusted player gesture or an explicit
      // native command, not by the pause() call alone: YouTube and Chromium
      // can issue that same call after an involuntary interruption.
      if (manual) noteManualPause();
      if (allowPause) console.log('[aay] user pause accepted');
      return origPause.apply(this, arguments);
    };
    window.addEventListener('pause', function (e) {
      if (e.target && e.target.tagName === 'VIDEO') {
        console.log('[aay] video paused event: realHidden=' + (Object.getOwnPropertyDescriptor(Document.prototype, 'hidden').get.call(document)) +
          ' focus=' + document.hasFocus() + ' ready=' + e.target.readyState + ' bg=' + A.isBg + ' pip=' + A.inPip + ' hold=' + A.hold);
      }
    }, true);
    window.addEventListener('play', function (e) {
      if (e.target && e.target.tagName === 'VIDEO') console.log('[aay] video play event');
    }, true);
  } catch (e) {}

  /* ---------- 1c. Taps and player events (for the Logs page), and links that do nothing ---------- */
  try {
    document.addEventListener('click', function (e) {
      var t = e.target;
      var a = t && t.closest ? t.closest('a[href]') : null;
      var tag = t ? (t.tagName || '').toLowerCase() : '?';
      var host = t && t.closest ? t.closest('[class*="renderer"],[class*="lockup"]') : null;
      console.log('[aay] tap ' + tag + (host ? ' in ' + host.tagName.toLowerCase() : '') + (a ? ' link ' + a.getAttribute('href') : ' (no link)'));
      if (!a) return;
      var href = a.href, before = location.href;
      // Browse page (under the mini player): a video opens in the player, not here.
      if (BROWSE && /\/watch\?|\/shorts\/|\/live\/|youtu\.be\//.test(href)) {
        e.preventDefault(); e.stopImmediatePropagation();
        try { window.AAYBrowse.open(href); } catch (x) {}
        return;
      }
      // A video or mix link that YouTube's page ignored (seen with Mix playlists): open it ourselves.
      if (/\/watch\?|[?&]list=|\/playlist\?/.test(href) && href !== before) {
        later(function () {
          if (location.href === before) {
            console.log('[aay] link did nothing after 1.5 s, opening it: ' + href);
            location.href = href;
          }
        }, 1500);
      }
    }, true);
    var origPush = history.pushState;
    history.pushState = function (st, title, url) {
      console.log('[aay] page change ' + url);
      return origPush.apply(this, arguments);
    };
    ['waiting', 'stalled', 'error', 'emptied', 'loadstart', 'loadedmetadata', 'resize', 'seeking', 'ended'].forEach(function (type) {
      document.addEventListener(type, function (e) {
        var v = e.target;
        if (!v || v.tagName !== 'VIDEO') return;
        var err = v.error ? ' error=' + v.error.code + ' ' + (v.error.message || '') : '';
        console.log('[aay] video ' + type + ' ' + v.videoWidth + 'x' + v.videoHeight + ' t=' + v.currentTime.toFixed(1) +
          ' ready=' + v.readyState + ' net=' + v.networkState + ' paused=' + v.paused + err);
      }, true);
    });
  } catch (e) {}

  /* ---------- 1d. No "Tap to unmute": YouTube mutes videos that start without a tap ---------- */
  // During the first seconds of each video, a mute that did not come from you is undone.
  // A mute you choose later (or the ad skipper's) is left alone.
  try {
    var startedAt = 0, lastTap = 0;
    document.addEventListener('pointerdown', function () { lastTap = Date.now(); }, true);
    document.addEventListener('loadstart', function (e) {
      if (e.target && e.target.tagName === 'VIDEO') startedAt = Date.now();
    }, true);
    var unmute = function (v) {
      if (!v || !v.muted || BROWSE) return;
      // Feed previews (small videos that play by themselves) stay muted, as YouTube intends.
      if (location.pathname !== '/watch' && location.pathname.indexOf('/live/') !== 0) return;
      if (v.closest && v.closest('ytm-inline-playback-renderer,ytm-rich-item-renderer,ytm-video-with-context-renderer,ytm-reel-shelf-renderer,[class*="inline-player"],[class*="preview"]')) return;
      var mainV = document.querySelector('#player-container-id video, #player video, video.html5-main-video');
      if (mainV && v !== mainV) return;
      if (typeof adSped !== 'undefined' && adSped) return;         // the ad skipper muted it
      if (Date.now() - lastTap < 1500) return;                     // you just tapped mute
      if (Date.now() - startedAt > 15000) return;                  // only at the start of a video
      var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
      try { if (p && p.unMute) p.unMute(); } catch (e) {}
      try { if (p && p.setVolume && p.getVolume && p.getVolume() === 0) p.setVolume(100); } catch (e) {}
      v.muted = false;
      // The temporary muted-autoplay overlay is hidden by CSS. Never click it
      // programmatically: a second click could mute the playing video again.
      console.log('[aay] unmuted the video (YouTube started it muted)');
    };
    ['volumechange', 'play', 'playing', 'loadedmetadata'].forEach(function (type) {
      document.addEventListener(type, function (e) {
        if (e.target && e.target.tagName === 'VIDEO') later(function () { unmute(e.target); }, 50);
      }, true);
    });
  } catch (e) {}

  /* ---------- 1f. Browse page: no video plays here (feed previews would fight the mini player) ---------- */
  if (BROWSE) {
    try {
      HTMLMediaElement.prototype.play = function () {
        try { this.pause(); } catch (e) {}
        return Promise.reject(new DOMException('Videos play in the AAYoutube player', 'NotAllowedError'));
      };
    } catch (e) {}
  }

  /* ---------- 1e. Swipe down on the video to enter the in-app mini player ---------- */
  // Listen at capture phase: YouTube can stop propagation and replace its player DOM.
  if (!BROWSE) {
    var swipeStart = null;
    document.addEventListener('touchstart', function(e) {
      swipeStart = null;
      if (e.touches.length !== 1 || A.inPip || A.isMini || A.isCar ||
          document.fullscreenElement || document.webkitFullscreenElement) return;
      if (!/^\/(watch|live\/)/.test(location.pathname)) return;
      var target = e.target;
      if (!target || !target.closest) return;
      var player = target.closest('.html5-video-player,#player-container-id,#player,' +
        'ytm-player,ytm-watch-player,ytm-player-component,video');
      if (!player) return;
      swipeStart = { x: e.touches[0].clientX, y: e.touches[0].clientY, at: Date.now() };
    }, { capture: true, passive: true });
    document.addEventListener('touchend', function(e) {
      var start = swipeStart;
      swipeStart = null;
      if (!start || !e.changedTouches.length) return;
      var dx = e.changedTouches[0].clientX - start.x;
      var dy = e.changedTouches[0].clientY - start.y;
      if (dy < 75 || Math.abs(dx) > dy * 0.65 || Date.now() - start.at > 1100) return;
      console.log('[aay] player swipe down -> mini');
      try { if (window.AAYNative) window.AAYNative.mini(); }
      catch (err) { console.warn('[aay] mini bridge failed', err); }
    }, { capture: true, passive: true });
    document.addEventListener('touchcancel', function() { swipeStart = null; }, true);
  }

  /* ---------- 1g. AABrowser-style tap-to-seek for Android Auto ---------- */
  // The Car App Library surface exposes taps, not horizontal scrubbing gestures.
  // A tap within a visible YouTube progress bar seeks without altering other controls.
  if (!BROWSE) {
    var seekTap = null;
    window.addEventListener('pointerdown', function (e) {
      seekTap = e.isPrimary && A.isCar && /^\/(watch|live\/)/.test(location.pathname) ?
        { id: e.pointerId, x: e.clientX, y: e.clientY, target: e.target } : null;
    }, true);
    window.addEventListener('pointermove', function (e) {
      if (seekTap && e.pointerId === seekTap.id &&
          Math.hypot(e.clientX - seekTap.x, e.clientY - seekTap.y) > 12) seekTap = null;
    }, true);
    window.addEventListener('pointercancel', function () { seekTap = null; }, true);
    window.addEventListener('pointerup', function (e) {
      var t = seekTap; seekTap = null;
      if (!t || t.id !== e.pointerId || !A.isCar || isAd()) return;
      var bar = document.querySelector('.ytPlayerProgressBarDragContainer,.ytp-progress-bar-container,.ytp-progress-bar');
      if (!bar || getComputedStyle(bar).visibility !== 'visible') return;
      var rect = bar.getBoundingClientRect();
      if (rect.width <= 0 || t.x < rect.left || t.x > rect.right || t.y < rect.top || t.y > rect.bottom) return;
      var el = t.target;
      if (el && el.closest && !bar.contains(el) && el.closest('button,a,[role="button"]')) return;
      var v = getVideo();
      if (!v || !isFinite(v.duration) || v.duration <= 0) return;
      var fraction = Math.max(0, Math.min(1,
        (getComputedStyle(bar).direction === 'rtl' ? rect.right - t.x : t.x - rect.left) / rect.width));
      v.currentTime = fraction * v.duration;
      console.log('[aay] car progress tap seeks to ' + Math.round(v.currentTime) + 's');
      schedReport(120);
    }, true);
  }

  // Some Android Auto hosts inject touch events straight into Chromium, bypassing
  // CarFrameLayout.dispatchTouchEvent. Observe them inside the page as well.
  // Neither listener consumes the gesture: settings taps and seeker taps remain native.
  if (!BROWSE) {
    var carControlsTap = null;
    window.addEventListener('pointerdown', function (e) {
      carControlsTap = A.isCar && !A.isMini && !A.inPip && e.isPrimary !== false ?
        { id: e.pointerId, x: e.clientX, y: e.clientY, at: Date.now() } : null;
    }, true);
    window.addEventListener('pointerup', function (e) {
      var t = carControlsTap; carControlsTap = null;
      if (!t || t.id !== e.pointerId || !A.isCar || A.isMini || A.inPip ||
          Math.hypot(e.clientX - t.x, e.clientY - t.y) > 18 || Date.now() - t.at > 700) return;
      if (A.showCarControls) A.showCarControls();
    }, true);
    window.addEventListener('pointercancel', function () { carControlsTap = null; }, true);
  }

  /* ---------- 2. Ads: remove ad data from YouTube's responses before the player sees it ---------- */
  var AD_KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];
  function strip(o, depth) {
    if (!CFG.blockAds || !o || typeof o !== 'object') return o;
    depth = depth || 0;
    if (depth > 4) return o;
    for (var i = 0; i < AD_KEYS.length; i++) {
      if (AD_KEYS[i] in o) { try { delete o[AD_KEYS[i]]; } catch (e) {} }
    }
    if (o.playerResponse) strip(o.playerResponse, depth + 1);
    if (o.response) strip(o.response, depth + 1);
    if (Array.isArray(o)) { for (var j = 0; j < o.length && j < 5; j++) strip(o[j], depth + 1); }
    return o;
  }

  /* ---------- 2b. Queue for Android Auto: playlist / Mix, or Up next ---------- */
  var Q = { kind: '', items: [], source: '' };
  function textOf(x) {
    if (!x) return '';
    if (typeof x === 'string') return x;
    if (x.simpleText) return x.simpleText;
    if (x.content) return x.content;
    if (x.runs) return x.runs.map(function (r) { return r.text; }).join('');
    return '';
  }
  function grabQueue(o, requestedVideoId) {
    try {
      // Ignore late /next responses from a previous watch route.
      var currentRoute = urlId();
      if (requestedVideoId && currentRoute && requestedVideoId !== currentRoute) return;
      var c = o && o.contents;
      var w = c && (c.singleColumnWatchNextResults || c.twoColumnWatchNextResults);
      if (!w) return;
      var pl = [], up = [], seen = {}, count = 0;
      (function walk(n, depth) {
        if (!n || typeof n !== 'object' || depth > 16 || count > 6000) return;
        count++;
        if (Array.isArray(n)) { for (var i = 0; i < n.length; i++) walk(n[i], depth + 1); return; }
        var r = n.playlistPanelVideoRenderer;
        if (r && r.videoId) { pl.push({ id: r.videoId, t: textOf(r.title), a: textOf(r.shortBylineText || r.longBylineText) }); return; }
        r = n.compactVideoRenderer || n.videoWithContextRenderer;
        if (r && r.videoId) {
          if (!seen[r.videoId]) { seen[r.videoId] = 1; up.push({ id: r.videoId, t: textOf(r.title || r.headline), a: textOf(r.shortBylineText || r.longBylineText) }); }
          return;
        }
        r = n.lockupViewModel;
        if (r && r.contentId && /VIDEO/.test(r.contentType || '')) {
          var md = r.metadata && r.metadata.lockupMetadataViewModel;
          if (!seen[r.contentId]) { seen[r.contentId] = 1; up.push({ id: r.contentId, t: textOf(md && md.title), a: '' }); }
          return;
        }
        for (var k in n) { if (Object.prototype.hasOwnProperty.call(n, k)) walk(n[k], depth + 1); }
      })(w, 0);
      if (pl.length) Q = { kind: 'playlist', items: pl, source: requestedVideoId || currentRoute };
      else if (up.length) Q = { kind: 'upnext', items: up.slice(0, 25), source: requestedVideoId || currentRoute };
      else return;
      console.log('[aay] queue for Android Auto: ' + Q.kind + ', ' + Q.items.length + ' videos');
      schedReport(300);
    } catch (e) {}
  }
  try {
    var yid;
    Object.defineProperty(window, 'ytInitialData', {
      configurable: true,
      get: function () { return yid; },
      set: function (v) { yid = v; if (!BROWSE) grabQueue(v); }
    });
  } catch (e) {}

  try {
    var origParse = JSON.parse;
    JSON.parse = function (text, reviver) {
      var o = origParse.call(JSON, text, reviver);
      try { if (CFG.blockAds && o && typeof o === 'object') strip(o); } catch (e) {}
      try { if (!BROWSE && o && o.contents) grabQueue(o); } catch (e) {}
      return o;
    };
  } catch (e) {}

  try {
    var origFetch = window.fetch;
    window.fetch = function (input) {
      var url = typeof input === 'string' ? input : ((input && input.url) || '');
      var p = origFetch.apply(this, arguments);
      if (!BROWSE && /\/youtubei\/v1\/next/.test(url)) {
        var requestedVideoId = urlId();
        p.then(function (res) {
          try { res.clone().json().then(function (data) {
            grabQueue(data, requestedVideoId);
          }).catch(function () {}); } catch (e) {}
        }).catch(function () {});
      }
      if (!CFG.blockAds || !/\/youtubei\/v1\/(player|next|browse|search|get_watch)/.test(url)) return p;
      return p.then(function (res) {
        try {
          if (!res.ok) return res;
          return res.clone().json().then(function (j) {
            strip(j);
            return new Response(JSON.stringify(j), { status: res.status, statusText: res.statusText, headers: res.headers });
          }).catch(function () { return res; });
        } catch (e) { return res; }
      });
    };
  } catch (e) {}

  try {
    var ipr;
    Object.defineProperty(window, 'ytInitialPlayerResponse', {
      configurable: true,
      get: function () { return ipr; },
      set: function (v) { ipr = strip(v); }
    });
  } catch (e) {}

  /* ---------- 3. Look: hide ad slots, Shorts and promos; Picture-in-picture layout ---------- */
  var CSS_ADS =
    'ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,ytm-companion-slot,ytm-ad-slot-renderer,' +
    'ad-slot-renderer,ytd-ad-slot-renderer,ytd-display-ad-renderer,ytd-promoted-sparkles-web-renderer,' +
    'ytd-promoted-video-renderer,ytd-player-legacy-desktop-watch-ads-renderer,#player-ads,.video-ads,' +
    '.ytp-ad-module,.ytp-ad-overlay-container,ytm-statement-banner-renderer,ytd-banner-promo-renderer' +
    '{display:none!important}';
  var CSS_SHORTS =
    'ytm-reel-shelf-renderer,ytd-reel-shelf-renderer,ytm-shorts-lockup-view-model,ytm-shorts-lockup-view-model-v2,' +
    'ytd-rich-shelf-renderer[is-shorts],ytm-item-section-renderer:has(ytm-reel-shelf-renderer),' +
    'ytm-rich-item-renderer:has(a[href^="/shorts/"]),ytm-video-with-context-renderer:has(a[href^="/shorts/"]),' +
    'ytd-rich-item-renderer:has(a[href^="/shorts/"]),ytd-video-renderer:has(a[href^="/shorts/"]),' +
    'ytm-pivot-bar-item-renderer:has(.pivot-shorts),ytd-guide-entry-renderer:has(a[title="Shorts"]),' +
    'ytd-mini-guide-entry-renderer[aria-label="Shorts"]{display:none!important}';
  var CSS_PROMO =
    'ytm-mealbar-promo-renderer,ytm-upsell-dialog-renderer,ytm-app-promo-renderer,ytd-mealbar-promo-renderer,' +
    'ytd-popup-container ytd-mealbar-promo-renderer{display:none!important}';
  // Hide YouTube's mobile Open App promotion without hiding the search or account controls.
  var CSS_OPEN_APP =
    'ytm-open-app-button,ytm-open-app-button-renderer,ytm-app-button-renderer,' +
    'ytm-mobile-topbar-renderer a[href*="intent://"],' +
    'ytm-mobile-topbar-renderer a[href*="market://"],' +
    'ytm-mobile-topbar-renderer a[aria-label="Open App"],' +
    'ytm-mobile-topbar-renderer button[aria-label="Open App"]{display:none!important}';
  // A real 16:9 WebView viewport, not a scaled graphics layer, shows the
  // small and PiP video. Do NOT hide every ancestor with visibility:hidden:
  // Chromium's accelerated video surface may disappear even if <video> is visible.
  var CSS_PIP =
    'html.aay-video-only,html.aay-video-only body{background:#000!important;overflow:hidden!important;' +
    'width:100%!important;height:100%!important;margin:0!important;padding:0!important}' +
    'html.aay-video-only #player-container-id,html.aay-video-only .player-container,' +
    'html.aay-video-only #player-container,html.aay-video-only ytm-watch-player,' +
    'html.aay-video-only ytm-player,html.aay-video-only ytm-player-component,' +
    'html.aay-video-only ytm-player-container,html.aay-video-only .player-size{' +
    'position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;' +
    'max-width:none!important;max-height:none!important;min-height:0!important;' +
    'margin:0!important;padding:0!important;transform:none!important;' +
    'background:#000!important;z-index:2147483000!important}' +
    'html.aay-video-only #player,html.aay-video-only #movie_player,' +
    'html.aay-video-only .html5-video-player,html.aay-video-only .html5-video-container{' +
    'position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;' +
    'max-width:none!important;max-height:none!important;transform:none!important;' +
    'margin:0!important;padding:0!important;z-index:2147483001!important}' +
    'html.aay-video-only video.html5-main-video{' +
    'position:absolute!important;inset:0!important;width:100%!important;height:100%!important;' +
    'max-width:none!important;max-height:none!important;object-fit:contain!important;' +
    'transform:none!important;visibility:visible!important;z-index:2147483002!important}' +
    'html.aay-video-only .ytp-chrome-top,html.aay-video-only .ytp-chrome-bottom,' +
    'html.aay-video-only .ytp-gradient-top,html.aay-video-only .ytp-gradient-bottom,' +
    'html.aay-video-only .ytp-pause-overlay,html.aay-video-only .ytp-ce-element,' +
    'html.aay-video-only .ytp-cards-teaser,html.aay-video-only .ytp-endscreen-content{' +
    'display:none!important}' +
    'html.aay-fit:not(.aay-video-only) video.html5-main-video{left:0!important;top:0!important;' +
    'width:100%!important;height:100%!important;object-fit:contain!important}';
  // Suppress only the muted-autoplay prompt, not normal player volume controls.
  var CSS_UNMUTE =
    '.ytp-unmute,ytm-unmute-button,.ytmUnmuteButton,.ytp-unmute-button,' +
    '[class*="tap-to-unmute" i],[class*="unmute-overlay" i]{display:none!important}';
  // Car screen (wide and short): the player, with YouTube's own controls, fills the whole screen,
  // like the YouTube app held sideways. The rest of the page is under it (back = mini player).
  var CSS_CAR =
    'html.aay-car,html.aay-car body{overflow:hidden!important;background:#000!important}' +
    'html.aay-car #player-container-id,html.aay-car .player-container,html.aay-car #player-container{' +
    'position:fixed!important;top:0!important;left:0!important;right:0!important;bottom:0!important;' +
    'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;' +
    'min-height:0!important;margin:0!important;padding:0!important;transform:none!important;' +
    'background:#000!important;z-index:2147483000!important}' +
    'html.aay-car #player,html.aay-car #movie_player,html.aay-car .html5-video-player{' +
    'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;' +
    'padding:0!important;margin:0!important;top:0!important;left:0!important}' +
    'html.aay-car .html5-video-container{width:100%!important;height:100%!important}' +
    'html.aay-car video.html5-main-video{left:0!important;top:0!important;width:100%!important;' +
    'height:100%!important;object-fit:contain!important}' +
    'html.aay-car #player-control-container{position:fixed!important;top:0!important;left:0!important;' +
    'width:100vw!important;height:100vh!important;z-index:2147483001!important}' +
    // YouTube mobile watch pages use ytm-player and ytm-watch-player wrappers. Without
    // these selectors the player remains at its original short mobile height, leaving
    // the title/queue panel visible below it on wide Android Auto screens.
    'html.aay-car ytm-watch-player,html.aay-car ytm-player,html.aay-car ytm-player-component,' +
    'html.aay-car ytm-watch-player-component,html.aay-car ytm-player-container,' +
    'html.aay-car .player-size,html.aay-car .player-placeholder{' +
    'position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;' +
    'max-width:none!important;max-height:none!important;min-height:0!important;' +
    'margin:0!important;padding:0!important;aspect-ratio:auto!important;' +
    'transform:none!important;background:#000!important;z-index:2147483000!important}' +
    'html.aay-car ytm-watch,html.aay-car ytm-watch-flexy{' +
    'overflow:hidden!important}' +
    'html.aay-car ytm-watch-metadata,html.aay-car ytm-watch-next-feed,' +
    'html.aay-car ytm-item-section-renderer,html.aay-car ytm-playlist-panel-renderer{' +
    'visibility:hidden!important}';
  // The rules above turn every player-like wrapper into a full-screen black box at the
  // same z-index. A wrapper WITHOUT the video (m.youtube.com spacers such as
  // .player-placeholder/.player-size come after the player in the DOM) then paints over
  // the video: Android Auto showed black while the video decoded and played audio.
  // carGuard() marks those wrappers; the :not(#id) chain outranks the #id rules above.
  var CSS_CAR_GUARD =
    'html.aay-car .aay-car-off:not(#aay-x):not(#aay-y){position:static!important;z-index:auto!important;' +
    'background:transparent!important;visibility:hidden!important;width:0!important;height:0!important;' +
    'min-height:0!important;overflow:hidden!important;pointer-events:none!important}' +
    // On the FULL car player, YouTube's control surface MUST receive video taps:
    // its click handler opens the top quality menu and bottom progress bar.
    // Car MINI has no aay-car class; native Compose only intercepts the small rail.
    'html.aay-car #player-control-container{' +
    'background:transparent!important;pointer-events:auto!important}' +
    // Only video-free wrappers should pass touches through to the actual player.
    'html.aay-car .aay-car-clear:not(#aay-x):not(#aay-y){' +
    'background:transparent!important;pointer-events:none!important}' +
    'html.aay-car #player-control-container button,html.aay-car #player-control-container a,' +
    'html.aay-car #player-control-container [role="button"],' +
    'html.aay-car .aay-car-clear button,html.aay-car .aay-car-clear a,' +
    'html.aay-car .aay-car-clear [role="button"],' +
    // YouTube renders settings/quality as custom menu elements, not always <button>.
    // Only real controls and their open menus accept taps; the large backdrop stays transparent.
    'html.aay-car #player-control-container .ytp-settings-button,' +
    'html.aay-car #player-control-container .ytp-popup,' +
    'html.aay-car #player-control-container .ytp-menuitem,' +
    'html.aay-car #player-control-container [role="menuitem"],' +
    'html.aay-car #player-control-container [role="option"],' +
    'html.aay-car #player-control-container ytm-icon-button,' +
    'html.aay-car #player-control-container ytm-menu-item-renderer,' +
    'html.aay-car #player-control-container ytm-menu-popup-renderer,' +
    'html.aay-car .aay-car-clear .ytp-settings-button,' +
    'html.aay-car .aay-car-clear .ytp-popup,' +
    'html.aay-car .aay-car-clear .ytp-menuitem,' +
    'html.aay-car .aay-car-clear [role="menuitem"],' +
    'html.aay-car .aay-car-clear [role="option"],' +
    'html.aay-car .aay-car-clear ytm-icon-button,' +
    'html.aay-car .aay-car-clear ytm-menu-item-renderer,' +
    'html.aay-car .aay-car-clear ytm-menu-popup-renderer{pointer-events:auto!important}' +
    // During a car tap, briefly expose the native YouTube top/bottom controls.
    // Do not create a fake seeker or move the controls outside the YouTube player.
    'html.aay-car.aay-car-controls .ytp-chrome-top,' +
    'html.aay-car.aay-car-controls .ytp-chrome-bottom,' +
    'html.aay-car.aay-car-controls .ytp-progress-bar-container,' +
    'html.aay-car.aay-car-controls .ytp-gradient-top,' +
    'html.aay-car.aay-car-controls .ytp-gradient-bottom,' +
    'html.aay-car.aay-car-controls #player-control-container,' +
    'html.aay-car.aay-car-controls ytm-player-controls,' +
    'html.aay-car.aay-car-controls .player-controls-top,' +
    'html.aay-car.aay-car-controls .player-controls-bottom{' +
    'opacity:1!important;visibility:visible!important}' +
    'html.aay-car .aay-car-chain:not(#aay-x):not(#aay-y),' +
    'html.aay-car video.html5-main-video:not(#aay-x){visibility:visible!important}';

  var styleEl = null;
  function applyCss() {
    var css = CSS_PIP + CSS_CAR + CSS_CAR_GUARD + CSS_UNMUTE;
    // Hide promos must control the app-install banners as well as upsell dialogs.
    if (CFG.hidePromos) css += CSS_OPEN_APP;
    if (CFG.blockAds) css += CSS_ADS;
    if (CFG.hideShorts) css += CSS_SHORTS;
    if (CFG.hidePromos) css += CSS_PROMO;
    if (!styleEl) { styleEl = document.createElement('style'); styleEl.id = 'aay-style'; }
    styleEl.textContent = css;
    var root = document.head || document.documentElement;
    if (root && styleEl.parentNode !== root) root.appendChild(styleEl);
  }
  function whenRoot(fn) {
    if (document.documentElement) { fn(); return; }
    var mo = new MutationObserver(function () {
      if (document.documentElement) { mo.disconnect(); fn(); }
    });
    mo.observe(document, { childList: true });
  }
  whenRoot(applyCss);
  document.addEventListener('DOMContentLoaded', function () { later(applyCss); });

  // Shorts open as normal videos (the normal player has the controls and background play).
  function fixShorts() {
    if (!CFG.hideShorts) return;
    var m = location.pathname.match(/^\/shorts\/([\w-]{6,})/);
    if (m) location.replace('/watch?v=' + m[1] + '&aay=1');
  }
  try {
    fixShorts();
    ['pushState', 'replaceState'].forEach(function (k) {
      var orig = history[k];
      history[k] = function () {
        var r = orig.apply(this, arguments);
        later(fixShorts);
        return r;
      };
    });
    window.addEventListener('popstate', fixShorts);
  } catch (e) {}

  /* ---------- 4. Media session: title, artwork and next/previous from YouTube itself ---------- */
  var curMeta = null;
  // AABrowser's WebView compatibility technique: Android WebView may not expose the
  // Media Session API at all. Supply a minimal implementation before YouTube loads,
  // so artist, artwork and playlist transport actions are still reported to MediaCompat.
  try {
    if (!navigator.mediaSession) {
      var virtualSession = {
        metadata: null, playbackState: 'none',
        setActionHandler: function () {}, setPositionState: function () {}
      };
      Object.defineProperty(navigator, 'mediaSession', {
        configurable: true, get: function () { return virtualSession; }
      });
      if (!window.MediaMetadata) {
        window.MediaMetadata = function (values) {
          values = values || {};
          this.title = values.title || '';
          this.artist = values.artist || '';
          this.album = values.album || '';
          this.artwork = values.artwork || [];
        };
      }
    }
    var ms = navigator.mediaSession;
    if (ms) {
      var realProto = typeof MediaSession !== 'undefined' ? MediaSession.prototype : null;
      var desc = realProto && Object.getOwnPropertyDescriptor(realProto, 'metadata');
      curMeta = ms.metadata || null;
      Object.defineProperty(ms, 'metadata', {
        configurable: true,
        get: function () { return curMeta; },
        set: function (m) {
          curMeta = m;
          try { if (desc && desc.set) desc.set.call(ms, m); } catch (e) {}
          schedReport(150);
        }
      });
      var origSet = ms.setActionHandler && ms.setActionHandler.bind(ms);
      ms.setActionHandler = function (action, handler) {
        if (typeof handler === 'function') H[action] = handler;
        else delete H[action];
        try { if (origSet) return origSet(action, handler); } catch (e) {}
      };
    }
  } catch (e) { console.log('[aay] media session compatibility: ' + e); }

  /* ---------- 5. Reporting what the video is doing to the app ---------- */
  var lastId = '';
  function getVideo() {
    return document.querySelector('video.html5-main-video') || document.querySelector('video');
  }
  function urlId() {
    try {
      var u = new URL(location.href);
      var v = u.searchParams.get('v');
      if (v) return v;
      var m = u.pathname.match(/^\/(?:shorts|embed|live)\/([\w-]{6,})/);
      return m ? m[1] : '';
    } catch (e) { return ''; }
  }
  function isAd() {
    var p = document.querySelector('.html5-video-player');
    return !!(p && p.classList.contains('ad-showing'));
  }
  function metaInfo(id) {
    var t = '', a = '', art = '';
    if (curMeta) {
      t = curMeta.title || '';
      a = curMeta.artist || '';
      var arts = curMeta.artwork || [];
      if (arts.length) art = arts[arts.length - 1].src || '';
      // Metadata left over from the previous video: ignore it.
      if (art && id && /ytimg\.com\/vi/.test(art) && art.indexOf(id) < 0) { t = ''; a = ''; art = ''; }
    }
    if (!t) t = (document.title || '').replace(/\s*-\s*YouTube$/, '');
    return { t: t, a: a, art: art };
  }

  var reportTimer = 0;
  function schedReport(ms) {
    if (reportTimer) return;
    reportTimer = setTimeout(function () { reportTimer = 0; try { report(); } catch (e) {} }, ms || 120);
  }
  // The player knows which video it really plays; the page address can lag behind (for example
  // when the next video of a Mix starts while the screen is off).
  function playerVideo() {
    var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
    try { var d = p && p.getVideoData && p.getVideoData(); if (d && d.video_id) return d; } catch (e) {}
    return null;
  }
  function queueFor(id, mi) {
    var q = Q.items;
    if (!q.length || (Q.kind === 'upnext' && Q.source && id !== Q.source)) return [];
    var i = -1;
    for (var k = 0; k < q.length; k++) { if (q[k].id === id) { i = k; break; } }
    if (i < 0) {
      if (Q.kind !== 'upnext') return [];
      q = [{ id: id, t: mi.t, a: mi.a }].concat(q); i = 0;
    }
    return q.slice(Math.max(0, i - 10), i + 25);
  }
  var lastSent = null;
  function report() {
    if (!N) return;
    // Away from a watch page, only small feed previews play: they are not "the video". Tell the app
    // nothing is playing (no picture in picture, no media controls for a preview).
    if (!/^\/(watch|live\/)/.test(location.pathname)) {
      bgWatch.id = '';
      bgWatch.wanted = false;
      bgWatch.explicitPause = false;
      // Do not leave a stale video ID in the media service: it would keep
      // trying to recover a ghost stream after navigating away from Watch.
      if (lastSent && lastSent.id) {
        lastSent = { id: '', playing: false, userPaused: false, buffering: false,
          ended: false, queue: [], pos: 0, dur: 0 };
        N.state(JSON.stringify(lastSent));
      }
      return;
    }
    var v = getVideo();
    var pv = playerVideo();
    var id = (pv && pv.video_id) || urlId();
    if (v && !qualityState.reset) notePosition(v);
    if (id) lastId = id; else if (v && !v.paused) id = lastId; else id = '';
    if (!v || !id) return;
    var mi = metaInfo(id);
    if (pv && pv.video_id === id) {
      if (pv.title) mi.t = pv.title;
      if (pv.author) mi.a = pv.author;
    }
    var d = v.duration;
    N.state(JSON.stringify(lastSent = {
      id: id, title: mi.t, artist: mi.a, art: mi.art, queue: queueFor(id, mi),
      playing: !v.paused && !v.ended,
      // Persist explicit user Pause across IPC disconnect/reconnect.
      userPaused: !!(bgWatch && bgWatch.explicitPause && v.paused),
      ended: !!v.ended,
      buffering: !v.paused && !v.ended && v.readyState < 3,
      live: d === Infinity,
      ad: isAd(),
      pos: v.currentTime || 0,
      dur: isFinite(d) ? d : 0,
      rate: v.playbackRate || 1,
      next: !!H.nexttrack,
      prev: !!H.previoustrack
    }));
  }

  /* ---------- 6. SponsorBlock ---------- */
  var sb = { id: '', segs: [], request: 0 };
  function sha256hex(s) {
    return crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)).then(function (buf) {
      return Array.prototype.map.call(new Uint8Array(buf), function (b) { return ('0' + b.toString(16)).slice(-2); }).join('');
    });
  }
  function sbLoad(id) {
    if (!CFG.sponsorBlock || !id || sb.id === id) return;
    sb.id = id; sb.segs = [];
    var request = ++sb.request;
    var cats = CFG.sbExtra ? ['sponsor', 'selfpromo', 'interaction', 'intro', 'outro'] : ['sponsor', 'selfpromo', 'interaction'];
    sha256hex(id).then(function (h) {
      return fetch('https://sponsor.ajay.app/api/skipSegments/' + h.slice(0, 4) +
        '?categories=' + encodeURIComponent(JSON.stringify(cats)) +
        '&actionTypes=' + encodeURIComponent('["skip"]'));
    }).then(function (r) { return r.ok ? r.json() : []; }).then(function (list) {
      // Ignore results for an earlier video or a previous category selection.
      if (request !== sb.request || sb.id !== id || !CFG.sponsorBlock) return;
      for (var i = 0; i < list.length; i++) {
        if (list[i].videoID === id) {
          sb.segs = list[i].segments.map(function (s) { return { a: s.segment[0], b: s.segment[1] }; });
        }
      }
    }).catch(function () {});
  }
  function sbTick(v) {
    if (!CFG.sponsorBlock || !sb.segs.length || isAd()) return;
    var t = v.currentTime;
    for (var i = 0; i < sb.segs.length; i++) {
      var s = sb.segs[i];
      if (t >= s.a && t < s.b - 0.3) { v.currentTime = s.b; return; }
    }
  }

  /* ---------- 7. Return YouTube Dislike (best effort: depends on the page layout) ---------- */
  var ryd = { id: '', txt: '', request: 0 };
  function fmt(n) {
    if (n >= 1e9) return (n / 1e9).toFixed(1).replace(/\.0$/, '') + 'B';
    if (n >= 1e6) return (n / 1e6).toFixed(1).replace(/\.0$/, '') + 'M';
    if (n >= 1e3) return (n / 1e3).toFixed(1).replace(/\.0$/, '') + 'K';
    return String(n);
  }
  function rydLoad(id) {
    if (!CFG.returnDislike || !id || ryd.id === id) return;
    ryd.id = id; ryd.txt = '';
    var request = ++ryd.request;
    fetch('https://returnyoutubedislikeapi.com/votes?videoId=' + encodeURIComponent(id))
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (j) {
        if (request !== ryd.request || ryd.id !== id || !CFG.returnDislike) return;
        if (j && typeof j.dislikes === 'number') { ryd.txt = fmt(j.dislikes); rydApply(); }
      })
      .catch(function () {});
  }
  function rydApply() {
    if (!CFG.returnDislike || !ryd.txt) return;
    var btn = document.querySelector('dislike-button-view-model button, #dislike-button button, button[aria-label*="islike"]');
    if (!btn) return;
    var tag = btn.querySelector('.aay-ryd');
    if (!tag) {
      tag = document.createElement('span');
      tag.className = 'aay-ryd';
      tag.style.cssText = 'margin-left:6px;font-size:1.4rem;font-weight:500';
      btn.appendChild(tag);
    }
    if (tag.textContent !== ryd.txt) tag.textContent = ryd.txt;
  }

  /* ---------- 8. Ads that still get through: skip them, then restore the video ---------- */
  var adSped = false, saved = { rate: 1, muted: false };
  function skipAd() {
    if (!CFG.blockAds) {
      // If the user switches blocking off mid-ad, restore playback speed and mute.
      if (adSped) {
        adSped = false;
        var current = getVideo();
        if (current) { current.playbackRate = saved.rate; current.muted = saved.muted; }
      }
      return;
    }
    var p = document.querySelector('.html5-video-player');
    var v = getVideo();
    if (!p || !v) return;
    if (p.classList.contains('ad-showing')) {
      var b = p.querySelector('.ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button__text, .ytp-ad-skip-button-container button');
      if (b) { try { b.click(); } catch (e) {} }
      if (!adSped) { saved.rate = v.playbackRate || 1; saved.muted = v.muted; adSped = true; }
      v.muted = true;
      v.playbackRate = 16;
      if (isFinite(v.duration) && v.duration > 0 && v.duration < 180) {
        try { v.currentTime = Math.max(0, v.duration - 0.05); } catch (e) {}
      }
    } else if (adSped) {
      adSped = false;
      v.playbackRate = saved.rate;
      v.muted = saved.muted;
    }
  }

  // "Video paused. Continue watching?" and the ad-blocker warning dialog.
  function dialogs() {
    var list = document.querySelectorAll('yt-confirm-dialog-renderer,ytm-confirm-dialog-renderer,tp-yt-paper-dialog,ytd-enforcement-message-view-model');
    for (var i = 0; i < list.length; i++) {
      var d = list[i];
      if (d.tagName.toLowerCase() === 'ytd-enforcement-message-view-model') {
        if (CFG.blockAds) {
          var host = d.closest('tp-yt-paper-dialog') || d;
          host.remove();
          var bg = document.querySelector('tp-yt-iron-overlay-backdrop');
          if (bg) bg.remove();
          var v0 = getVideo(); if (v0 && v0.paused) { try { v0.play(); } catch (e) {} }
        }
        continue;
      }
      if (CFG.autoContinue === false) continue;
      var text = (d.textContent || '').toLowerCase();
      if (/still watching|continue watching|are you there|toujours l|continuer à regarder|reprendre/.test(text)) {
        var bs = d.querySelectorAll('button');
        for (var k = 0; k < bs.length; k++) {
          if (/^\s*(yes|continue|oui|continuer|ok|resume)/i.test(bs[k].textContent || '')) { bs[k].click(); break; }
        }
        var v1 = getVideo(); if (v1 && v1.paused) { try { v1.play(); } catch (e) {} }
      }
    }
  }

  /* ---------- 9. Wiring: bind the video element, react to page changes ---------- */
  var AUTO = /[?&]aay=1/.test(location.search);       // start playing as soon as ready
  var SEARCH_PLAY = /[?&]aay=play/.test(location.search); // open the first result of a search

  // An explicit Pause never expires after a timer or an unrelated page tap.
  var PAUSED = /[?&]aay=paused/.test(location.search);
  function enforceExplicitPause(v) {
    if (!v) return;
    bgWatch.explicitPause = true;
    bgWatch.wanted = false;
    allowPause = true; // bypass background pause interception
    try { v.pause(); } finally { allowPause = false; }
  }

  function tryPlay(v) {
    var n = 0;
    (function step() {
      if (!AUTO) return;
      if (v.paused && n++ < 12) {
        try { var pr = v.play(); if (pr && pr.catch) pr.catch(function () {}); } catch (e) {}
        setTimeout(step, 800);
      } else if (!v.paused) { AUTO = false; }
    })();
  }

  function onLoaded(v) {
    var id = urlId();
    sbLoad(id);
    rydLoad(id);
    // Apply the saved resolution as soon as the player initializes. Re-run once
    // after YouTube has attached its player API; neither path reloads the page.
    if (!BROWSE) {
      A.applyQuality();
      later(function () { A.applyQuality(); }, 900);
    }
    if (AUTO) tryPlay(v);
  }

  /* Preserve playback across a YouTube quality/source replacement.
   * Unlike an ordinary seek, a quality change empties/replaces the media resource.
   * Never restore after a seek gesture, a different video, or genuine completion.
   */
  var qualityState = { id: '', position: 0, element: null, reset: null, seekAt: 0 };
  function qualityVideoId() {
    var pv = playerVideo();
    return (pv && pv.video_id) || urlId();
  }
  function notePosition(v) {
    var id = qualityVideoId();
    if (!id || !v || !isFinite(v.currentTime)) return;
    if (qualityState.id !== id) {
      qualityState.id = id;
      qualityState.position = 0;
      qualityState.reset = null;
      qualityState.element = v;
    }
    if (v.ended) { qualityState.reset = null; return; }
    if (!qualityState.reset || v.currentTime > qualityState.reset.seconds - 4) {
      qualityState.position = Math.max(0, v.currentTime);
    }
  }
  function rememberSourceChange(v, reason) {
    var id = qualityVideoId();
    if (BROWSE || !id || id !== qualityState.id || v.ended ||
        qualityState.position < 10 || Date.now() - qualityState.seekAt < 3500) return;
    qualityState.reset = { id: id, seconds: qualityState.position, started: Date.now(), tries: 0 };
    console.log('[aay] video source ' + reason + ': preserving ' + Math.round(qualityState.position) + 's');
  }
  function restoreAfterSourceChange(v) {
    var pending = qualityState.reset;
    if (!pending || !v || v.readyState < 1) return;
    if (Date.now() - pending.started > 12000 || pending.id !== qualityVideoId() ||
        v.ended || Date.now() - qualityState.seekAt < 3500) {
      qualityState.reset = null;
      return;
    }
    if (v.currentTime >= pending.seconds - 4) {
      qualityState.reset = null;
      return;
    }
    if (pending.tries++ >= 3) { qualityState.reset = null; return; }
    try {
      v.currentTime = Math.min(pending.seconds, isFinite(v.duration) && v.duration > 0
        ? Math.max(0, v.duration - 0.5) : pending.seconds);
      console.log('[aay] video source restored to ' + Math.round(v.currentTime) + 's');
      schedReport(120);
    } catch (e) {
      console.log('[aay] restore position deferred: ' + e);
    }
  }
  // Android can also reload the entire document when YouTube changes resolution.
  // Kotlin passes the last known position only when the new document is the SAME video.
  A.restoreAt = function (id, seconds) {
    if (BROWSE || !id || id !== qualityVideoId() || !isFinite(seconds) || seconds < 10) return;
    qualityState.id = id;
    qualityState.position = Math.max(qualityState.position, seconds);
    qualityState.reset = { id: id, seconds: seconds, started: Date.now(), tries: 0 };
    console.log('[aay] same-video document reload: restoring ' + Math.round(seconds) + 's');
    restoreAfterSourceChange(getVideo());
  };

  function trackNewVideoElement(v) {
    if (!v || qualityState.element === v) return;
    if (qualityState.element && qualityVideoId() === qualityState.id)
      rememberSourceChange(qualityState.element, 'replaced');
    qualityState.element = v;
  }
  // A human seeking to 0 is not a resolution change and must never be undone.
  document.addEventListener('pointerdown', function (e) {
    var t = e.target;
    if (t && t.closest && t.closest('.ytp-progress-bar,.ytp-progress-bar-container,.ytPlayerProgressBarDragContainer'))
      { qualityState.seekAt = Date.now(); qualityState.reset = null; }
  }, true);
  document.addEventListener('keydown', function (e) {
    if (/^(ArrowLeft|ArrowRight|Home|End|[0-9]|j|l)$/i.test(e.key || ''))
      { qualityState.seekAt = Date.now(); qualityState.reset = null; }
  }, true);

  /* WebView timers may be throttled with the screen off. Check only when the
   * user's last intent was to play; explicit media pauses and completed videos win.
   */
  var bgWatch = { id: '', wanted: false, explicitPause: false, attempts: 0, lastAttemptAt: 0 };
  function backgroundWatchdog() {
    // Only a watch/live page owns an active media session. A stale video
    // element/URL parameter can survive SPA navigation back to YouTube Home.
    if (BROWSE || !/^\/(watch|live\/)/.test(location.pathname)) {
      bgWatch.id = ''; bgWatch.wanted = false; bgWatch.explicitPause = false;
      return;
    }
    var v = getVideo(), id = qualityVideoId();
    if (!v || !id || v.ended) return;
    // An intentionally disabled Background play setting still wins offscreen.
    // In the phone, PiP, mini-player and car, an unexpected pause is recoverable.
    if ((A.isBg || A.hold) && !A.isCar && CFG.backgroundPlay === false) return;
    if (bgWatch.id !== id) {
      bgWatch.id = id; bgWatch.wanted = AUTO || (!v.paused && !PAUSED);
      bgWatch.explicitPause = false; bgWatch.attempts = 0; bgWatch.lastAttemptAt = 0;
    }
    if (PAUSED) {
      bgWatch.wanted = false;
      bgWatch.explicitPause = true;
      if (!v.paused) enforceExplicitPause(v);
      return;
    }
    if (!v.paused) {
      bgWatch.wanted = true;
      bgWatch.attempts = 0; bgWatch.lastAttemptAt = 0;
      return;
    }
    if (bgWatch.explicitPause || !bgWatch.wanted) return;
    // Never give up solely because a retry limit was reached. Use exponential
    // backoff (up to 60 s) instead of busy-looping when a stream is unavailable.
    var now = Date.now();
    var retryMs = Math.min(60000, 5000 * Math.pow(2, Math.min(bgWatch.attempts, 4)));
    if (bgWatch.lastAttemptAt && now - bgWatch.lastAttemptAt < retryMs) return;
    bgWatch.lastAttemptAt = now;
    bgWatch.attempts++;
    console.log('[aay] unexpected pause: resume attempt ' + bgWatch.attempts +
      ' (bg=' + A.isBg + ' pip=' + A.inPip + ' mini=' + A.isMini + ' car=' + A.isCar + ')');
    try { var p = v.play(); if (p && p.catch) p.catch(function (e) {
      console.log('[aay] playback recovery rejected: ' + (e && e.name || 'unknown'));
    }); } catch (e) { console.log('[aay] playback recovery failed: ' + e); }
    schedReport(150);
  }
  setInterval(backgroundWatchdog, 5000);

  function bind(v) {
    if (!v || v.__aay) return;
    trackNewVideoElement(v);
    if (bgWatch.id !== qualityVideoId()) {
      bgWatch.id = qualityVideoId();
      bgWatch.wanted = !v.paused;
      bgWatch.explicitPause = false;
      bgWatch.attempts = 0; bgWatch.lastAttemptAt = 0;
    }
    v.__aay = 1;
    ['play', 'pause', 'ended', 'waiting', 'playing', 'seeked', 'ratechange',
      'durationchange', 'emptied', 'loadstart'].forEach(function (ev) {
      v.addEventListener(ev, function () {
        if (ev === 'emptied' || ev === 'loadstart') rememberSourceChange(v, ev);
        if (ev === 'ended') { qualityState.reset = null; bgWatch.wanted = false; }
        if (ev === 'play') {
          if (PAUSED && !AUTO) enforceExplicitPause(v);
          else {
            bgWatch.wanted = true; bgWatch.explicitPause = false;
            bgWatch.attempts = 0; bgWatch.lastAttemptAt = 0;
          }
        }
        if (ev === 'pause' && Date.now() <= userPauseEligibleUntil) noteManualPause();
        if (ev === 'seeked') {
          if (qualityState.reset) restoreAfterSourceChange(v);
          else notePosition(v);
        }
        schedReport(ev === 'seeked' ? 250 : 120);
      }, true);
    });
    v.addEventListener('loadedmetadata', function () {
      if (PAUSED && !AUTO) enforceExplicitPause(v);
      onLoaded(v);
      restoreAfterSourceChange(v);
      later(function () { restoreAfterSourceChange(v); }, 300);
      schedReport(120);
    }, true);
    var lastProgressReportAt = 0;
    v.addEventListener('timeupdate', function () {
      if (qualityState.reset) restoreAfterSourceChange(v);
      else notePosition(v);
      sbTick(v);
      // This also notices a new video playing in an existing element (Mix/Autoplay)
      // without waiting for the 30-second heartbeat. Limit cross-process traffic.
      var now = Date.now();
      if (now - lastProgressReportAt > 1400) {
        lastProgressReportAt = now;
        schedReport(0);
      }
    }, true);
    // Ads are detected from the player's class, not by polling.
    var p = document.querySelector('.html5-video-player');
    if (p) new MutationObserver(function () { skipAd(); }).observe(p, { attributes: true, attributeFilter: ['class'] });
    if (v.readyState >= 1) { onLoaded(v); restoreAfterSourceChange(v); }
    // If the script attaches after YouTube's initial play event, report immediately rather
    // than waiting for the 30-second heartbeat. The media service needs the active state.
    schedReport(120);
  }

  function openFirstResult() {
    if (!SEARCH_PLAY) return;
    var links = document.querySelectorAll('a[href*="/watch?v="]');
    for (var i = 0; i < links.length; i++) {
      var a = links[i];
      if (a.closest('[class*="promoted"],ytm-promoted-sparkles-web-renderer,ad-slot-renderer')) continue;
      SEARCH_PLAY = false;
      location.assign(a.href + (a.href.indexOf('?') < 0 ? '?' : '&') + 'aay=1');
      return;
    }
  }

  var tickTimer = 0;
  function tick() {
    tickTimer = 0;
    try {
      var v = getVideo();
      if (v) bind(v);
      // Background audio still needs player bindings and quality recovery,
      // but visual DOM work is unnecessary when the phone is hidden.
      if (v && !BROWSE && A.applyQuality) A.applyQuality();
      if (A.isBg && !A.inPip && !A.isCar) return;
      skipAd();
      dialogs();
      rydApply();
      openFirstResult();
      carLayout();
      reportAccount();
    } catch (e) {}
  }
  // YouTube mutates the page continuously, even when the player is unchanged.
  // Coalesce those mutations to avoid repeated DOM traversal and layout work.
  // Playback, source and route event listeners remain immediate.
  function schedTick() {
    if (tickTimer) return;
    tickTimer = setTimeout(tick, (A.isBg && !A.inPip && !A.isCar) ? 5000 : 900);
  }

  whenRoot(function () {
    new MutationObserver(schedTick).observe(document.documentElement, { childList: true, subtree: true });
    schedTick();
  });

  // Fresh position now and then while playing, in case an event was missed.
  setInterval(function () {
    // Media element events and the background watchdog keep audio state
    // current; a hidden phone does not need periodic UI position reports.
    if (BROWSE || (A.isBg && !A.inPip && !A.isCar)) return;
    var v = getVideo();
    if (v && !v.paused) schedReport(0);
  }, 10000);
  // Route changes can replace the video without raising loadedmetadata.
  window.addEventListener('yt-navigate-finish', function () { schedReport(0); });

  /* ---------- 9b. Timed lyrics: use real HTMLVideoElement.currentTime ---------- */
  // Media-session snapshots are infrequent. A dedicated clock is necessary for
  // seeking, buffering and playback rate changes while lyrics are actually visible.
  // Never run this polling loop for the mini-browser or a hidden/plain-text panel.
  var lyricClockTimer = 0;
  function sendLyricClock() {
    if (!lyricClockTimer || BROWSE || !N || typeof N.lyricsTime !== 'function') return;
    var v = getVideo();
    var pv = playerVideo();
    var id = (pv && pv.video_id) || urlId();
    if (!v || !id || !isFinite(v.currentTime) || isAd()) return;
    N.lyricsTime(id, v.currentTime, !v.paused && !v.ended,
      v.readyState < 3, v.playbackRate || 1);
  }
  A.lyricsSync = function (on) {
    if (BROWSE) return;
    if (!on) {
      if (lyricClockTimer) { clearInterval(lyricClockTimer); lyricClockTimer = 0; }
      return;
    }
    if (lyricClockTimer) return;
    lyricClockTimer = setInterval(sendLyricClock, 250);
    sendLyricClock();
  };

  /* ---------- 10. Commands from the app ---------- */
  // A YouTube MediaSession handler may return successfully without navigating.
  // Verify one change before invoking a single alternate player control.
  // Cancel old timers whenever a newer transport command supersedes them.
  var skipSerial = 0;
  function skipIdentity() {
    var pv = playerVideo();
    return { player: (pv && pv.video_id) || '', route: urlId() };
  }
  function hasNavigated(origin) {
    var now = skipIdentity();
    return !!((now.player && now.player !== origin.player && now.player !== origin.route) ||
      (now.route && now.route !== origin.route && now.route !== origin.player));
  }
  function fallbackSkip(direction) {
    var player = document.getElementById('movie_player');
    var method = direction === 'next' ? 'nextVideo' : 'previousVideo';
    if (player && typeof player[method] === 'function') {
      try { player[method](); console.log('[aay] ' + direction + ' fallback: player API'); return; }
      catch (e) { console.log('[aay] ' + direction + ' player API failed'); }
    }
    var selector = direction === 'next' ?
      '.ytp-next-button, button[aria-label^="Next"]' :
      '.ytp-prev-button, button[aria-label^="Previous"]';
    var button = document.querySelector(selector);
    if (button && !button.disabled) {
      button.click();
      console.log('[aay] ' + direction + ' fallback: button');
    } else console.log('[aay] ' + direction + ' fallback unavailable');
  }
  function skip(direction) {
    var serial = ++skipSerial;
    var original = skipIdentity();
    var handler = H[direction === 'next' ? 'nexttrack' : 'previoustrack'];
    if (typeof handler !== 'function') {
      console.log('[aay] ' + direction + ' no media handler; player fallback');
      fallbackSkip(direction);
      return;
    }
    try {
      handler();
      console.log('[aay] ' + direction + ' media handler invoked');
    } catch (e) {
      console.log('[aay] ' + direction + ' media handler threw; player fallback');
      fallbackSkip(direction);
      return;
    }
    later(function () {
      if (serial !== skipSerial || hasNavigated(original) ||
          (location.pathname !== '/watch' && location.pathname.indexOf('/live/') !== 0)) return;
      console.log('[aay] ' + direction + ' handler did not advance after 2500ms');
      fallbackSkip(direction);
    }, 2500);
  }
  function next() { skip('next'); }
  function prev() {
    var v = getVideo();
    if (v && v.currentTime > 5) {
      ++skipSerial;
      v.currentTime = 0;
      console.log('[aay] previous restarts current video');
      return;
    }
    skip('prev');
  }

  // Prefer YouTube's registered MediaSession Play handler, as AABrowser does.
  // It can update player state when directly calling video.play() is rejected
  // or does not fully wake YouTube's own player UI.
  function requestPlayerPlay(v) {
    if (typeof H.play === 'function') {
      try { H.play(); return; } catch (e) {
        console.log('[aay] YouTube Play handler failed, trying HTML video');
      }
    }
    if (v) {
      try {
        var p = v.play();
        if (p && p.catch) p.catch(function (e) {
          console.log('[aay] Play rejected: ' + (e && e.name || 'unknown'));
        });
      } catch (e) { console.log('[aay] Play failed: ' + e); }
    }
  }
  A.cmd = function (c, arg) {
    var v = getVideo();
    try {
      switch (c) {
        case 'play': ++skipSerial; PAUSED = false; bgWatch.explicitPause = false; bgWatch.wanted = true; bgWatch.attempts = 0; bgWatch.lastAttemptAt = 0; if (!v) AUTO = true; else requestPlayerPlay(v); break;
        case 'pause': ++skipSerial; AUTO = false; PAUSED = true; userPauseEligibleUntil = 0; bgWatch.explicitPause = true; bgWatch.wanted = false; if (v) enforceExplicitPause(v); break;
        case 'seek': if (v) v.currentTime = arg; break;
        case 'rate': if (v) v.playbackRate = arg; break;
        case 'next': PAUSED = false; bgWatch.explicitPause = false; bgWatch.wanted = true; next(); break;
        case 'prev': PAUSED = false; bgWatch.explicitPause = false; bgWatch.wanted = true; prev(); break;
      }
    } catch (e) {}
    schedReport(200);
  };

  A.config = function (c) {
    var oldSponsor = CFG.sponsorBlock, oldExtra = CFG.sbExtra, oldDislike = CFG.returnDislike;
    for (var k in c) { if (Object.prototype.hasOwnProperty.call(c, k)) CFG[k] = c[k]; }
    applyCss();
    if (oldSponsor !== CFG.sponsorBlock || oldExtra !== CFG.sbExtra) {
      sb.request++;
      sb.id = ''; sb.segs = [];
      if (CFG.sponsorBlock) sbLoad(urlId());
    }
    if (oldDislike !== CFG.returnDislike) {
      ryd.request++;
      ryd.id = ''; ryd.txt = '';
      if (!CFG.returnDislike) {
        document.querySelectorAll('.aay-ryd').forEach(function (tag) { tag.remove(); });
      } else {
        rydLoad(urlId());
      }
    }
    writeYouTubeQuality(savedQuality());
    A.setBg(A.isBg);
    skipAd();
    schedTick();
  };

  // Set by the app as soon as the screen starts to leave (before PiP or the background kick in).
  A.setHold = function (on) { A.hold = !!on; };

  // While in picture in picture, check every 2 s that video frames still arrive. If the sound
  // plays but the picture is stuck (black), seek to the current position: that restarts the
  // video decoder without stopping the sound for more than a moment.
  var pipTimer = 0, pipLast = null, pipFixAt = 0;
  function videoInfo(v) {
    var q = v.getVideoPlaybackQuality ? v.getVideoPlaybackQuality() : null;
    var r = v.getBoundingClientRect();
    return {
      frames: q ? q.totalVideoFrames : -1,
      dropped: q ? q.droppedVideoFrames : -1,
      t: v.currentTime,
      text: v.videoWidth + 'x' + v.videoHeight + ' frames=' + (q ? q.totalVideoFrames : '?') +
        ' dropped=' + (q ? q.droppedVideoFrames : '?') + ' ready=' + v.readyState +
        ' box=' + Math.round(r.left) + ',' + Math.round(r.top) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height) +
        ' vis=' + getComputedStyle(v).visibility + ' realHidden=' +
        Object.getOwnPropertyDescriptor(Document.prototype, 'hidden').get.call(document) +
        ' win=' + innerWidth + 'x' + innerHeight
    };
  }
  function pipCheck() {
    var v = getVideo();
    if (!v) return;
    var i = videoInfo(v);
    if (pipLast && !v.paused && i.t > pipLast.t + 0.5 && i.frames >= 0 && i.frames === pipLast.frames) {
      var now = Date.now();
      console.log('[aay] pip: sound plays but no new video frames (' + i.text + ')');
      if (now - pipFixAt > 6000) {
        pipFixAt = now;
        console.log('[aay] pip: restarting the picture');
        try { v.currentTime = v.currentTime; } catch (e) {}
      }
    }
    pipLast = i;
  }

  // position:fixed only covers the window when no ancestor has a transform, contain or filter.
  // lift() removes those from the ancestors of [v] (and puts back the ones in [store]).
  function lift(store, v, on) {
    store.forEach(function (el) {
      el.style.removeProperty('transform'); el.style.removeProperty('contain'); el.style.removeProperty('filter');
      el.style.removeProperty('will-change');
    });
    var done = [];
    if (!on || !v) return done;
    for (var el = v.parentElement; el && el !== document.documentElement; el = el.parentElement) {
      var cs = getComputedStyle(el);
      if (cs.transform !== 'none' || (cs.contain && cs.contain !== 'none') || cs.filter !== 'none' || cs.willChange.indexOf('transform') >= 0) {
        el.style.setProperty('transform', 'none', 'important');
        el.style.setProperty('contain', 'none', 'important');
        el.style.setProperty('filter', 'none', 'important');
        el.style.setProperty('will-change', 'auto', 'important');
        done.push(el);
      }
    }
    return done;
  }
  var lifted = [];
  function liftAncestors(v, on) { lifted = lift(lifted, v, on); }

  // Car screen: the video fills the screen on a watch page (not in the mini player).
  var carOn = false, carBox = null, carLifted = [], carVideo = null, carGuarded = -1;
  function playerBox() {
    return document.getElementById('player-container-id') || document.querySelector('.player-container') ||
      document.getElementById('player-container') || document.getElementById('movie_player');
  }
  // Same wrappers the car CSS makes full-screen black boxes.
  var CAR_WRAPPERS = '#player-container-id,.player-container,#player-container,ytm-watch-player,' +
    'ytm-player,ytm-player-component,ytm-watch-player-component,ytm-player-container,' +
    '.player-size,.player-placeholder';
  function mark(el, name, on) {
    if (!el || !el.classList || el.classList.contains(name) === !!on) return;
    el.classList.toggle(name, !!on);
  }
  function unmarkAll(name) {
    var list = document.querySelectorAll('.' + name);
    for (var i = 0; i < list.length; i++) mark(list[i], name, false);
  }
  // Only wrappers that contain the video stay black full-screen boxes. One holding just
  // YouTube's controls loses its background; one holding neither is hidden.
  function carGuard(v) {
    if (!v) {
      unmarkAll('aay-car-off'); unmarkAll('aay-car-clear'); unmarkAll('aay-car-chain');
      return 0;
    }
    var chain = [];
    for (var el = v.parentElement; el && el !== document.body && el !== document.documentElement; el = el.parentElement)
      chain.push(el);
    var old = document.querySelectorAll('.aay-car-chain');
    for (var i = 0; i < old.length; i++) if (chain.indexOf(old[i]) < 0) mark(old[i], 'aay-car-chain', false);
    chain.forEach(function (c) { mark(c, 'aay-car-chain', true); });
    var ctl = document.getElementById('player-control-container');
    var list = document.querySelectorAll(CAR_WRAPPERS), off = 0;
    for (var j = 0; j < list.length; j++) {
      var w = list[j];
      var hasVideo = w.contains(v), hasControls = !!(ctl && w !== ctl && w.contains(ctl));
      mark(w, 'aay-car-off', !hasVideo && !hasControls);
      mark(w, 'aay-car-clear', !hasVideo && hasControls);
      if (!hasVideo) off++;
    }
    return off;
  }
  function carLayout() {
    var on = !!A.isCar && !A.isMini && !A.inPip && !BROWSE && /^\/(watch|live\/)/.test(location.pathname);
    var box = on ? playerBox() : null;
    var v = on ? getVideo() : null;
    var guarded;
    // On phones this runs after many YouTube DOM mutations; avoid three
    // document-wide car-guard queries when car mode has never been active.
    if (!on && !carOn && !carBox && !carVideo) return;
    if (on === carOn && box === carBox && v === carVideo) {
      // YouTube adds wrappers after the player: guard them on every DOM change.
      guarded = carGuard(on ? v : null);
      if (on && guarded !== carGuarded) {
        carGuarded = guarded;
        console.log('[aay] car guard: ' + guarded + ' wrapper(s) without the video neutralised');
      }
      return;
    }
    var changed = on !== carOn;
    carOn = on; carBox = box; carVideo = v;
    carLifted = lift(carLifted, box, on);
    if (document.documentElement) {
      document.documentElement.classList.toggle('aay-car', on);
      if (!on) document.documentElement.classList.remove('aay-car-controls');
    }
    guarded = carGuard(on ? v : null);
    if (on && guarded !== carGuarded) {
      carGuarded = guarded;
      console.log('[aay] car guard: ' + guarded + ' wrapper(s) without the video neutralised');
    }
    if (changed) {
      console.log('[aay] car screen: video fills the screen=' + on);
      fixLayout();
    }
  }
  // Native car surfaces sometimes deliver taps to WebView without triggering
  // YouTube's mobile overlay click. Reveal the existing YouTube controls,
  // preserving its real quality picker, seeker and other button handlers.
  var carControlsTimer = 0;
  A.showCarControls = function () {
    if (BROWSE || !carOn || A.isMini || A.inPip) return;
    var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
    try { if (p && typeof p.showControls === 'function') p.showControls(); } catch (e) {}
    try { if (p && p.classList) p.classList.remove('ytp-autohide'); } catch (e) {}
    if (!document.documentElement) return;
    document.documentElement.classList.add('aay-car-controls');
    if (carControlsTimer) clearTimeout(carControlsTimer);
    carControlsTimer = setTimeout(function () {
      carControlsTimer = 0;
      if (document.documentElement) document.documentElement.classList.remove('aay-car-controls');
    }, 6500);
  };
  A.car = function (on) {
    A.isCar = !!on;
    if (!A.isCar && document.documentElement) document.documentElement.classList.remove('aay-car-controls');
    carLayout();
    // Android Auto can take over an existing background-audio WebView.
    // Undo our own tiny-only quality cap before the projected video is shown.
    A.setBg(A.isBg);
  };
  // Reflow against measured car dimensions after transferring a mini WebView.
  A.refitCar = function () {
    if (!A.isCar || BROWSE || A.isMini) return;
    // A viewport resize must never silently expand a car mini player.
    carLayout();
    window.dispatchEvent(new Event('resize'));
    var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
    try { if (p && p.setInternalSize) p.setInternalSize(); } catch (e) {}
    try { if (p && p.setSize) p.setSize(); } catch (e) {}
    var v = getVideo();
    if (v) {
      var r = v.getBoundingClientRect();
      console.log('[aay] car viewport=' + window.innerWidth + 'x' + window.innerHeight +
        ' video=' + Math.round(r.width) + 'x' + Math.round(r.height));
    }
  };

  // After the small layout ends, YouTube keeps the small video size it measured (black border
  // around a small picture). Tell it the size changed a few times while the window grows back,
  // and if the picture still does not fill the player, stretch it ourselves.
  var layoutRevision = 0;
  function fixLayout() {
    var revision = ++layoutRevision;
    [150, 500, 1000, 2000].forEach(function (ms) {
      later(function () {
        // A later mini/PiP transition supersedes this delayed fullscreen resize.
        if (revision !== layoutRevision || A.isMini || A.inPip) return;
        window.dispatchEvent(new Event('resize'));
        var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
        try { if (p && p.setInternalSize) p.setInternalSize(); } catch (e) {}
        try { if (p && p.setSize) p.setSize(); } catch (e) {}
        var v = getVideo();
        if (ms === 2000 && v && p) {
          var vr = v.getBoundingClientRect(), pr = p.getBoundingClientRect();
          if (pr.width > 0 && vr.width < pr.width * 0.92 && vr.height < pr.height * 0.92) {
            console.log('[aay] video smaller than the player after resize (' + Math.round(vr.width) + 'x' + Math.round(vr.height) +
              ' in ' + Math.round(pr.width) + 'x' + Math.round(pr.height) + '): stretching it');
            document.documentElement.classList.add('aay-fit');
          }
        }
      }, ms);
    });
  }
  function smallLayout() {
    var on = !!(A.inPip || A.isMini);
    var revision = ++layoutRevision;
    liftAncestors(getVideo(), on);
    document.documentElement.classList.toggle('aay-video-only', on);
    document.documentElement.classList.toggle('aay-mini', !!A.isMini && !A.inPip);
    document.documentElement.classList.toggle('aay-pip', !!A.inPip);
    // Clear YouTube's cached video dimensions when Android resizes into PiP.
    if (on) {
      document.documentElement.classList.remove('aay-fit');
      // Recalculate YouTube's internal player size after native WebView resizes.
      [0, 150, 450].forEach(function (ms) { later(function () {
        if (revision !== layoutRevision) return;
        var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
        try { if (p && p.setInternalSize) p.setInternalSize(); } catch (e) {}
        window.dispatchEvent(new Event('resize'));
      }, ms); });
    } else fixLayout();
  }

  A.mini = function (on) {
    A.isMini = !!on;
    smallLayout();
    carLayout();
    A.setBg(A.isBg);
    window.dispatchEvent(new Event('resize'));
  };

  A.pip = function (on) {
    A.inPip = !!on;
    smallLayout();
    carLayout();
    clearInterval(pipTimer); pipTimer = 0; pipLast = null;
    var v = getVideo();
    if (v) console.log('[aay] pip=' + !!on + ' video ' + videoInfo(v).text);
    if (on) {
      pipTimer = setInterval(pipCheck, 2000);
      later(function () { var w = getVideo(); if (w) console.log('[aay] pip after layout: ' + videoInfo(w).text); }, 1500);
    }
    A.setBg(A.isBg);
    window.dispatchEvent(new Event('resize'));
  };

  /* ---------- 11. Video quality preference ----------
   * Request 720p on first play and retain deliberate YouTube quality-menu choices.
   * YouTube may temporarily lower the decoded stream for bandwidth/device limits;
   * never mistake its adaptive changes for a user's preference.
   */
  var qualityLabels = {
    '144': 'tiny', '240': 'small', '360': 'medium', '480': 'large',
    '720': 'hd720', '1080': 'hd1080', '1440': 'hd1440',
    '2160': 'hd2160', '4320': 'highres'
  };
  var qualityApply = { player: null, key: '', last: '' };
  function savedQuality() {
    var q = CFG.preferredQuality || 'hd720';
    return q === 'auto' || Object.values(qualityLabels).indexOf(q) >= 0 ? q : 'hd720';
  }
  function desiredQuality() {
    // Audio-only background may use 144p only when the user enables the setting.
    // PiP, draggable mini and car video always retain the selected picture quality.
    return A.isBg && !A.isCar && !A.inPip && !A.isMini &&
      CFG.lowQuality && CFG.backgroundPlay !== false ? 'tiny' : savedQuality();
  }
  function applyPreferredQuality() {
    if (BROWSE) return;
    var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
    if (!p) return;
    var chosen = desiredQuality();
    var key = qualityVideoId() + ':' + chosen;
    if (qualityApply.player === p && qualityApply.key === key) return;
    try {
      if (typeof p.setPlaybackQualityRange === 'function') {
        p.setPlaybackQualityRange(chosen === 'auto' ? 'tiny' : chosen,
          chosen === 'auto' ? 'highres' : chosen);
      } else if (typeof p.setPlaybackQuality === 'function') {
        p.setPlaybackQuality(chosen === 'auto' ? 'default' : chosen);
      } else return; // YouTube's API is not ready; the next DOM tick will retry.
      qualityApply.player = p;
      qualityApply.key = key;
      // Record actual vs requested quality for diagnosis, without forcing more
      // source changes when the server/device cannot serve the requested tier.
      later(function () {
        try {
          if (qualityApply.key === key && typeof p.getPlaybackQuality === 'function')
            console.log('[aay] quality requested=' + chosen + ' actual=' + p.getPlaybackQuality());
        } catch (e) {}
      }, 1500);
      if (qualityApply.last !== key) {
        qualityApply.last = key;
        console.log('[aay] preferred video quality=' + chosen +
          ' (video=' + qualityVideoId() + ')');
      }
    } catch (e) {
      // Player API is private/unstable; never interrupt playback when unavailable.
    }
  }
  A.applyQuality = applyPreferredQuality;
  function menuQuality(text) {
    var label = String(text || '').trim();
    // Do not interpret the parent "Quality 720p" settings button as a pick.
    if (/quality/i.test(label) || label.length > 36) return null;
    if (/^auto(?:\s*\([^)]*\))?$/i.test(label)) return 'auto';
    var m = /^(144|240|360|480|720|1080|1440|2160|4320)p(?:\s*(?:60|HDR|HD|4K|8K))*$/i.exec(label);
    return m ? qualityLabels[m[1]] : null;
  }
  document.addEventListener('click', function (event) {
    if (BROWSE || !event.target || !event.target.closest) return;
    var item = event.target.closest(
      '.ytp-quality-menu .ytp-menuitem, .ytp-settings-menu .ytp-menuitem,' +
      'ytm-select-menu-item-renderer, ytm-menu-service-item-renderer,' +
      '[role="menuitemradio"], [role="option"]');
    if (!item) return;
    var q = menuQuality(item.textContent || item.getAttribute('aria-label'));
    if (!q) return;
    CFG.preferredQuality = q;
    writeYouTubeQuality(q);
    qualityApply.key = ''; // release the old range after the user's new choice
    if (N && typeof N.quality === 'function') {
      try { N.quality(q); } catch (e) {}
    }
    console.log('[aay] user selected video quality=' + q);
    later(applyPreferredQuality, 500); // allow YouTube's menu handler to finish first
  }, true);

  A.setBg = function (on) {
    var wasBg = A.isBg;
    A.isBg = !!on;
    applyPreferredQuality();
    // Catch up with the DOM once when returning to the foreground.
    if (wasBg && !A.isBg) schedTick();
  };

})();
