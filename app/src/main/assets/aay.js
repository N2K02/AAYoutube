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
  var A = window.__aay = { H: {}, isBg: false };
  var H = A.H; // media session action handlers YouTube registers (nexttrack, previoustrack, ...)

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
  } catch (e) {}

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

  try {
    var origParse = JSON.parse;
    JSON.parse = function (text, reviver) {
      var o = origParse.call(JSON, text, reviver);
      try { if (CFG.blockAds && o && typeof o === 'object') strip(o); } catch (e) {}
      return o;
    };
  } catch (e) {}

  try {
    var origFetch = window.fetch;
    window.fetch = function (input) {
      var url = typeof input === 'string' ? input : ((input && input.url) || '');
      var p = origFetch.apply(this, arguments);
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
  var CSS_PIP =
    'html.aay-pip,html.aay-pip body{background:#000!important;overflow:hidden!important}' +
    'html.aay-pip body *{visibility:hidden!important}' +
    'html.aay-pip .html5-video-player,html.aay-pip .html5-video-player *{visibility:visible!important}' +
    'html.aay-pip .html5-video-player{position:fixed!important;top:0!important;left:0!important;' +
    'width:100vw!important;height:100vh!important;z-index:2147483647!important;margin:0!important}' +
    'html.aay-pip video{position:absolute!important;top:0!important;left:0!important;width:100%!important;' +
    'height:100%!important;object-fit:contain!important}' +
    'html.aay-pip .ytp-chrome-top,html.aay-pip .ytp-chrome-bottom,html.aay-pip .ytp-gradient-top,' +
    'html.aay-pip .ytp-gradient-bottom,html.aay-pip .ytp-pause-overlay,html.aay-pip .ytp-ce-element,' +
    'html.aay-pip .ytp-cards-teaser,html.aay-pip .ytp-endscreen-content{display:none!important}';

  var styleEl = null;
  function applyCss() {
    var css = CSS_PIP;
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
  try {
    var ms = navigator.mediaSession;
    if (ms) {
      var desc = Object.getOwnPropertyDescriptor(MediaSession.prototype, 'metadata');
      if (desc && desc.set) {
        Object.defineProperty(ms, 'metadata', {
          configurable: true,
          get: function () { return curMeta; },
          set: function (m) {
            curMeta = m;
            try { desc.set.call(ms, m); } catch (e) {}
            schedReport(150);
          }
        });
      }
      var origSet = ms.setActionHandler.bind(ms);
      ms.setActionHandler = function (action, handler) {
        H[action] = handler;
        try { return origSet(action, handler); } catch (e) {}
      };
    }
  } catch (e) {}

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
  function report() {
    if (!N) return;
    var v = getVideo();
    var id = urlId();
    if (id) lastId = id; else if (v && !v.paused) id = lastId; else id = '';
    if (!v || !id) return;
    var mi = metaInfo(id);
    var d = v.duration;
    N.state(JSON.stringify({
      id: id, title: mi.t, artist: mi.a, art: mi.art,
      playing: !v.paused && !v.ended,
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
  var sb = { id: '', segs: [] };
  function sha256hex(s) {
    return crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)).then(function (buf) {
      return Array.prototype.map.call(new Uint8Array(buf), function (b) { return ('0' + b.toString(16)).slice(-2); }).join('');
    });
  }
  function sbLoad(id) {
    if (!CFG.sponsorBlock || !id || sb.id === id) return;
    sb.id = id; sb.segs = [];
    var cats = CFG.sbExtra ? ['sponsor', 'selfpromo', 'interaction', 'intro', 'outro'] : ['sponsor', 'selfpromo', 'interaction'];
    sha256hex(id).then(function (h) {
      return fetch('https://sponsor.ajay.app/api/skipSegments/' + h.slice(0, 4) +
        '?categories=' + encodeURIComponent(JSON.stringify(cats)) +
        '&actionTypes=' + encodeURIComponent('["skip"]'));
    }).then(function (r) { return r.ok ? r.json() : []; }).then(function (list) {
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
  var ryd = { id: '', txt: '' };
  function fmt(n) {
    if (n >= 1e9) return (n / 1e9).toFixed(1).replace(/\.0$/, '') + 'B';
    if (n >= 1e6) return (n / 1e6).toFixed(1).replace(/\.0$/, '') + 'M';
    if (n >= 1e3) return (n / 1e3).toFixed(1).replace(/\.0$/, '') + 'K';
    return String(n);
  }
  function rydLoad(id) {
    if (!CFG.returnDislike || !id || ryd.id === id) return;
    ryd.id = id; ryd.txt = '';
    fetch('https://returnyoutubedislikeapi.com/votes?videoId=' + encodeURIComponent(id))
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (j) { if (j && typeof j.dislikes === 'number') { ryd.txt = fmt(j.dislikes); rydApply(); } })
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
    if (!CFG.blockAds) return;
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
    if (A.isBg) later(function () { A.setBg(true); }, 1200);
    if (AUTO) tryPlay(v);
  }

  function bind(v) {
    if (!v || v.__aay) return;
    v.__aay = 1;
    ['play', 'pause', 'ended', 'waiting', 'playing', 'seeked', 'ratechange', 'durationchange', 'emptied'].forEach(function (ev) {
      v.addEventListener(ev, function () { schedReport(ev === 'seeked' ? 250 : 120); }, true);
    });
    v.addEventListener('loadedmetadata', function () { onLoaded(v); schedReport(120); }, true);
    v.addEventListener('timeupdate', function () { sbTick(v); }, true);
    // Ads are detected from the player's class, not by polling.
    var p = document.querySelector('.html5-video-player');
    if (p) new MutationObserver(function () { skipAd(); }).observe(p, { attributes: true, attributeFilter: ['class'] });
    if (v.readyState >= 1) onLoaded(v);
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
      skipAd();
      dialogs();
      rydApply();
      openFirstResult();
    } catch (e) {}
  }
  function schedTick() { if (!tickTimer) tickTimer = setTimeout(tick, 300); }

  whenRoot(function () {
    new MutationObserver(schedTick).observe(document.documentElement, { childList: true, subtree: true });
    schedTick();
  });

  // Fresh position now and then while playing, in case an event was missed.
  setInterval(function () {
    var v = getVideo();
    if (v && !v.paused) schedReport(0);
  }, 30000);

  /* ---------- 10. Commands from the app ---------- */
  function next() {
    if (H.nexttrack) { try { H.nexttrack(); return; } catch (e) {} }
    var b = document.querySelector('.ytp-next-button, button[aria-label^="Next"]');
    if (b) b.click();
  }
  function prev() {
    var v = getVideo();
    if (v && (v.currentTime > 5 || !H.previoustrack)) { v.currentTime = 0; return; }
    if (H.previoustrack) { try { H.previoustrack(); } catch (e) {} }
  }

  A.cmd = function (c, arg) {
    var v = getVideo();
    try {
      switch (c) {
        case 'play': if (v) { var pr = v.play(); if (pr && pr.catch) pr.catch(function () {}); } break;
        case 'pause': if (v) v.pause(); break;
        case 'seek': if (v) v.currentTime = arg; break;
        case 'rate': if (v) v.playbackRate = arg; break;
        case 'next': next(); break;
        case 'prev': prev(); break;
      }
    } catch (e) {}
    schedReport(200);
  };

  A.config = function (c) {
    for (var k in c) { if (Object.prototype.hasOwnProperty.call(c, k)) CFG[k] = c[k]; }
    applyCss();
    schedTick();
  };

  A.pip = function (on) {
    document.documentElement.classList.toggle('aay-pip', !!on);
    window.dispatchEvent(new Event('resize'));
  };

  // Screen hidden = background: ask for the lowest video quality (less data, less decoding, less battery).
  A.setBg = function (on) {
    A.isBg = !!on;
    if (!CFG.lowQuality) return;
    var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
    if (!p || !p.setPlaybackQualityRange) return;
    try {
      if (on) p.setPlaybackQualityRange('tiny', 'tiny');
      else p.setPlaybackQualityRange('tiny', 'highres');
    } catch (e) {}
  };
})();
