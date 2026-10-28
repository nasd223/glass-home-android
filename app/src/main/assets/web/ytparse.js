/*
 * Glass Home - YouTube link parser (shared).
 *
 * One dependency-free ES5 file used in three places:
 *   - the TV app (loaded with a <script> tag, exposes window.GHYouTube)
 *   - the photo/shortcut service (require('../js/ytparse.js'), Node 12)
 *   - the phone website (the service inlines this file into the page)
 *
 * parse(link) -> { videoId, playlistId, start } or null.
 * Channels, @handles and searches return null: those are website shortcuts.
 * ID patterns follow yt-dlp: 11-character video IDs and its playlist ID rule.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.GHYouTube = factory();
}(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  var VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;
  var LIST_ID = /^(?:(?:PL|UU|LL|FL|OL|RD|UL|TL|PU|EC|OLAK5uy_)[A-Za-z0-9_-]{10,}|RDMM|WL|LL|LM)$/;
  var YT_HOST = /^(?:[a-z0-9-]+\.)*(?:youtube\.com|youtube-nocookie\.com|youtube\.googleapis\.com)$/;
  var SHORT_HOST = /^(?:www\.)?youtu\.be$/;

  function parseTime(v) {
    if (!v) return 0;
    v = String(v).toLowerCase();
    var m;
    if (/^\d+(?:\.\d+)?s?$/.test(v)) return Math.floor(parseFloat(v));
    m = /^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$/.exec(v);
    if (m && (m[1] || m[2] || m[3])) return (+m[1] || 0) * 3600 + (+m[2] || 0) * 60 + (+m[3] || 0);
    m = /^(?:(\d+):)?(\d{1,2}):(\d{2})$/.exec(v);
    if (m) return (+m[1] || 0) * 3600 + (+m[2]) * 60 + (+m[3]);
    return 0;
  }

  // Query-string parser. Object.create(null) so keys such as "constructor"
  // or "__proto__" cannot collide with Object.prototype.
  function params(str) {
    var out = Object.create(null);
    var parts = String(str || '').split(/[&;]/);
    for (var i = 0; i < parts.length; i++) {
      var kv = parts[i];
      if (!kv) continue;
      var j = kv.indexOf('=');
      var k = j < 0 ? kv : kv.slice(0, j);
      var val = j < 0 ? '' : kv.slice(j + 1);
      try { k = decodeURIComponent(k); val = decodeURIComponent(val.replace(/\+/g, ' ')); } catch (e) { /* keep raw */ }
      if (!(k in out)) out[k] = val;
    }
    return out;
  }

  function parse(input, depth) {
    var s = String(input == null ? '' : input).trim();
    if (!s || s.length > 2048) return null;
    if (VIDEO_ID.test(s)) return { videoId: s, playlistId: null, start: 0 };
    if (LIST_ID.test(s)) return { videoId: null, playlistId: s, start: 0 };
    if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(s)) s = 'https://' + s.replace(/^\/\//, '');
    var m = /^[a-z][a-z0-9+.-]*:\/\/(?:[^@\/?#]*@)?([^\/?#:]+)(?::\d+)?([^?#]*)(?:\?([^#]*))?(?:#(.*))?$/i.exec(s);
    if (!m) return null;
    var host = m[1].toLowerCase();
    var path = m[2] || '/';
    var q = params(m[3]);
    var frag = (m[4] || '').replace(/^!/, '');
    var f = params(frag.indexOf('?') >= 0 ? frag.slice(frag.indexOf('?') + 1) : frag);
    var videoId = null;
    var seg;
    if (SHORT_HOST.test(host)) {
      seg = path.split('/')[1] || '';
      if (VIDEO_ID.test(seg)) videoId = seg;
    } else if (YT_HOST.test(host)) {
      if (/^\/attribution_link/.test(path) && q.u && (depth || 0) < 2) {
        return parse('https://www.youtube.com' + q.u, (depth || 0) + 1);
      }
      seg = /^\/(?:shorts|live|embed|v|e)\/([A-Za-z0-9_-]{11})(?:[\/?#]|$)/.exec(path);
      if (seg && seg[1] !== 'videoseries' && seg[1] !== 'live_stream') videoId = seg[1];
      else if (VIDEO_ID.test(q.v || '')) videoId = q.v;
      else if (VIDEO_ID.test(f.v || '')) videoId = f.v;
    } else {
      return null;
    }
    var list = q.list || f.list || '';
    var playlistId = LIST_ID.test(list) ? list : null;
    if (!videoId && !playlistId) return null;
    return {
      videoId: videoId,
      playlistId: playlistId,
      start: parseTime(q.t || q.start || f.t || f.start)
    };
  }

  // The deep-link value handed to the YouTube TV app: 'v=ID&list=PL..&t=90'.
  function launchValue(p) {
    var parts = [];
    if (p.videoId) parts.push('v=' + p.videoId);
    if (p.playlistId) parts.push('list=' + p.playlistId);
    if (p.start) parts.push('t=' + p.start);
    return parts.join('&');
  }

  // A clean https://www.youtube.com/... link for oEmbed and the URI launch form.
  function canonicalUrl(p) {
    if (p.videoId) {
      return 'https://www.youtube.com/watch?v=' + p.videoId +
        (p.playlistId ? '&list=' + p.playlistId : '') +
        (p.start ? '&t=' + p.start + 's' : '');
    }
    return 'https://www.youtube.com/playlist?list=' + p.playlistId;
  }

  function formatStart(sec) {
    sec = Math.max(0, Math.floor(sec || 0));
    var h = Math.floor(sec / 3600);
    var m = Math.floor((sec % 3600) / 60);
    var s = sec % 60;
    var ss = (s < 10 ? '0' : '') + s;
    if (h) return h + ':' + (m < 10 ? '0' : '') + m + ':' + ss;
    return m + ':' + ss;
  }

  return {
    parse: function (input) { return parse(input, 0); },
    parseTime: parseTime,
    launchValue: launchValue,
    canonicalUrl: canonicalUrl,
    formatStart: formatStart,
    VIDEO_ID: VIDEO_ID,
    LIST_ID: LIST_ID
  };
}));
