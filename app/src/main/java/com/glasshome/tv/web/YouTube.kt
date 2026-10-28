package com.glasshome.tv.web

/**
 * YouTube link parsing, ported from js/ytparse.js (the phone page still uses
 * that file). Channels, @handles and searches aren't videos: those are website
 * shortcuts. ID patterns follow yt-dlp.
 */
object YouTube {
    class Link(val videoId: String?, val playlistId: String?, val start: Int)

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val LIST_ID = Regex("^(?:(?:PL|UU|LL|FL|OL|RD|UL|TL|PU|EC|OLAK5uy_)[A-Za-z0-9_-]{10,}|RDMM|WL|LL|LM)$")
    private val YT_HOST = Regex("^(?:[a-z0-9-]+\\.)*(?:youtube\\.com|youtube-nocookie\\.com|youtube\\.googleapis\\.com)$")
    private val SHORT_HOST = Regex("^(?:www\\.)?youtu\\.be$")
    private val URL = Regex("^[a-z][a-z0-9+.-]*://(?:[^@/?#]*@)?([^/?#:]+)(?::\\d+)?([^?#]*)(?:\\?([^#]*))?(?:#(.*))?$", RegexOption.IGNORE_CASE)
    private val PATH_ID = Regex("^/(?:shorts|live|embed|v|e)/([A-Za-z0-9_-]{11})(?:[/?#]|$)")

    /** At most a week: a start time past that is a typo, and huge ones would overflow. */
    private fun clamp(sec: Long) = sec.coerceIn(0, 7 * 86400L).toInt()

    fun parseTime(raw: String?): Int {
        val v = raw?.lowercase() ?: return 0
        if (v.isEmpty()) return 0
        if (Regex("^\\d+(?:\\.\\d+)?s?$").matches(v)) return clamp(v.removeSuffix("s").toDouble().toLong())
        Regex("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$").find(v)?.let { m ->
            val (h, mi, s) = m.destructured
            if (h.isNotEmpty() || mi.isNotEmpty() || s.isNotEmpty()) {
                return clamp((h.toLongOrNull() ?: 0) * 3600 + (mi.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0))
            }
        }
        Regex("^(?:(\\d+):)?(\\d{1,2}):(\\d{2})$").find(v)?.let { m ->
            val (h, mi, s) = m.destructured
            return clamp((h.toLongOrNull() ?: 0) * 3600 + mi.toLong() * 60 + s.toLong())
        }
        return 0
    }

    private fun params(str: String?): Map<String, String> {
        val out = HashMap<String, String>()
        for (kv in (str ?: "").split('&', ';')) {
            if (kv.isEmpty()) continue
            val j = kv.indexOf('=')
            var k = if (j < 0) kv else kv.substring(0, j)
            var v = if (j < 0) "" else kv.substring(j + 1)
            val dk = HttpServer.percentDecode(k)
            val dv = HttpServer.percentDecode(v.replace('+', ' '))
            if (dk != null && dv != null) { k = dk; v = dv }       // keep raw when malformed
            if (k !in out) out[k] = v
        }
        return out
    }

    fun parse(input: String?, depth: Int = 0): Link? {
        var s = (input ?: "").trim()
        if (s.isEmpty() || s.length > 2048) return null
        if (VIDEO_ID.matches(s)) return Link(s, null, 0)
        if (LIST_ID.matches(s)) return Link(null, s, 0)
        if (!Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(s)) s = "https://" + s.removePrefix("//")
        val m = URL.find(s) ?: return null
        val host = m.groupValues[1].lowercase()
        val path = m.groupValues[2].ifEmpty { "/" }
        val q = params(m.groups[3]?.value)
        val frag = (m.groups[4]?.value ?: "").removePrefix("!")
        val f = params(if ('?' in frag) frag.substring(frag.indexOf('?') + 1) else frag)
        var videoId: String? = null
        if (SHORT_HOST.matches(host)) {
            val seg = path.split('/').getOrNull(1) ?: ""
            if (VIDEO_ID.matches(seg)) videoId = seg
        } else if (YT_HOST.matches(host)) {
            if (path.startsWith("/attribution_link") && q["u"] != null && depth < 2) {
                return parse("https://www.youtube.com" + q["u"], depth + 1)
            }
            val seg = PATH_ID.find(path)?.groupValues?.get(1)
            videoId = when {
                seg != null && seg != "videoseries" && seg != "live_stream" -> seg
                VIDEO_ID.matches(q["v"] ?: "") -> q["v"]
                VIDEO_ID.matches(f["v"] ?: "") -> f["v"]
                else -> null
            }
        } else {
            return null
        }
        val list = q["list"]?.ifEmpty { null } ?: f["list"] ?: ""
        val playlistId = if (LIST_ID.matches(list)) list else null
        if (videoId == null && playlistId == null) return null
        val t = listOf(q["t"], q["start"], f["t"], f["start"]).firstOrNull { !it.isNullOrEmpty() }
        return Link(videoId, playlistId, parseTime(t))
    }

    /** A clean https://www.youtube.com/... link, for oEmbed and for opening the YouTube app. */
    fun canonicalUrl(p: Link): String {
        if (p.videoId != null) {
            return "https://www.youtube.com/watch?v=" + p.videoId +
                (if (p.playlistId != null) "&list=" + p.playlistId else "") +
                (if (p.start > 0) "&t=" + p.start + "s" else "")
        }
        return "https://www.youtube.com/playlist?list=" + p.playlistId
    }
}
