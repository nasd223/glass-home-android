package com.glasshome.tv.web

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/*
 * Outbound fetches: website icons and YouTube details, ported from
 * service/fetcher.js.
 *
 * Privacy: icons come from the site itself (its apple-touch-icon, web manifest
 * or <link rel=icon>), never from a favicon service, and each site is asked
 * once, when the shortcut is added.
 * Safety: only public internet addresses are contacted, so a shortcut can never
 * make the TV probe the home network. DNS answers are filtered, IP-literal
 * hosts are checked, and redirects are followed by hand so every hop is checked
 * before it connects.
 */
object Fetcher {
    private const val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"

    private val publicDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val ok = Dns.SYSTEM.lookup(hostname).filter { allowed(it) }
            if (ok.isEmpty()) throw UnknownHostException("blocked: $hostname is not a public address")
            return ok
        }
    }

    private val client = OkHttpClient.Builder()
        .dns(publicDns)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        // Belt and braces: whatever route was chosen, it must be a public address.
        .addNetworkInterceptor { chain ->
            val addr: InetAddress? = chain.connection()?.route()?.socketAddress?.address
            if (addr == null || !allowed(addr)) throw IOException("blocked address")
            chain.proceed(chain.request())
        }
        .build()

    /** A public internet address that isn't on one of the TV's own networks. */
    private fun allowed(a: InetAddress) = Net.isPublicUnicast(a.hostAddress) && !Net.isOnLocalNetwork(a)

    class Got(val status: Int, val body: ByteArray, val url: HttpUrl)

    private fun now() = android.os.SystemClock.elapsedRealtime()

    /** A time limit for a whole job (a page, its manifest and its icons), as an elapsedRealtime. */
    fun deadline(ms: Long) = now() + ms

    /**
     * GET [url], following up to 5 redirects, all within [timeoutMs] and before
     * [until]. OkHttp decompresses gzip itself.
     */
    fun get(url: String, accept: String = "*/*", maxBytes: Int = 512 * 1024, timeoutMs: Long = 8000, until: Long = Long.MAX_VALUE): Got {
        val end = minOf(until, now() + timeoutMs)
        var u = url.toHttpUrlOrNull() ?: throw IOException("bad url")
        repeat(6) {
            val left = end - now()
            if (left <= 0) throw IOException("timeout")
            if (u.username.isNotEmpty() || u.password.isNotEmpty()) throw IOException("credentials in url")
            if (Net.isIpLiteral(u.host)) {
                val literal = InetAddress.getByName(u.host)       // a literal: no DNS lookup happens
                if (!allowed(literal)) throw IOException("blocked address")
            }
            val call = client.newCall(
                Request.Builder().url(u)
                    .header("User-Agent", UA)
                    .header("Accept", accept)
                    .header("Accept-Language", "en,*;q=0.5")
                    .build(),
            )
            call.timeout().timeout(left, TimeUnit.MILLISECONDS)
            call.execute().use { res ->
                if (res.code in 300..399) {
                    val next = res.header("Location")?.let { u.resolve(it) } ?: throw IOException("bad redirect")
                    u = next
                    return@repeat
                }
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                (res.body ?: throw IOException("no body")).byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (out.size() + n > maxBytes) throw IOException("too large")
                        out.write(buf, 0, n)
                    }
                }
                return Got(res.code, out.toByteArray(), u)
            }
        }
        throw IOException("too many redirects")
    }

    // ---------- reading a site's page ----------
    // Pages come from anywhere, so reading them stays cheap whatever they hold:
    // plain searches and short forward scans, and the work is capped.
    private const val MAX_HTML_CHARS = 400_000
    private const val MAX_LINK_TAGS = 200
    private const val MAX_TITLE_TAGS = 20
    private const val MAX_TAG_CHARS = 2048
    private const val MAX_ATTRS = 32
    private const val MAX_MANIFEST_ICONS = 64

    private fun isSpace(c: Char) = c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\u000c'

    /** A start tag's attributes from s[i] (just after the tag name), and the index of its '>'; null if it doesn't end before [limit]. */
    private fun readTag(s: String, start: Int, limitIn: Int): Pair<Map<String, String>, Int>? {
        val out = HashMap<String, String>()
        val limit = minOf(limitIn, s.length)
        var i = start
        while (i < limit) {
            var c = s[i]
            if (c == '>') return out to i
            if (isSpace(c) || c == '/') { i++; continue }
            var from = i
            i++
            while (i < limit) {
                c = s[i]
                if (isSpace(c) || c == '/' || c == '>' || c == '=') break
                i++
            }
            val name = s.substring(from, i).lowercase()
            while (i < limit && isSpace(s[i])) i++
            var value = ""
            if (i < limit && s[i] == '=') {
                i++
                while (i < limit && isSpace(s[i])) i++
                if (i < limit && (s[i] == '"' || s[i] == '\'')) {
                    val close = s.indexOf(s[i], i + 1)
                    if (close < 0 || close >= limit) return null
                    value = s.substring(i + 1, close)
                    i = close + 1
                } else {
                    from = i
                    while (i < limit && !isSpace(s[i]) && s[i] != '>') i++
                    value = s.substring(from, i)
                }
            }
            if (out.size < MAX_ATTRS && name !in out) out[name] = value
        }
        return null
    }

    private fun tagStarts(html: String, tag: String) = Regex("<$tag(?=[\\s/>])", RegexOption.IGNORE_CASE).findAll(html)

    private fun linkTags(html: String): List<Map<String, String>> {
        val out = mutableListOf<Map<String, String>>()
        var after = 0
        for ((n, m) in tagStarts(html, "link").withIndex()) {
            if (n >= MAX_LINK_TAGS) break
            if (m.range.first < after) continue
            val t = readTag(html, m.range.first + 5, m.range.first + MAX_TAG_CHARS) ?: continue
            out += t.first
            after = t.second + 1
        }
        return out
    }

    private fun pageTitle(html: String): String? {
        for ((n, m) in tagStarts(html, "title").withIndex()) {
            if (n >= MAX_TITLE_TAGS) break
            val gt = html.indexOf('>', m.range.first + 6)
            if (gt < 0 || gt >= m.range.first + MAX_TAG_CHARS) continue
            val from = gt + 1
            val lt = html.indexOf('<', from)
            if (lt < from + 1 || lt > from + 200) continue
            if (!html.regionMatches(lt, "</title>", 0, 8, ignoreCase = true)) continue
            return html.substring(from, lt)
        }
        return null
    }

    private fun decodeEntities(s: String) = s
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.takeIf { it < 0x110000 }?.let { String(Character.toChars(it)) } ?: "" }
        .replace(Regex("&#x([0-9a-f]+);", RegexOption.IGNORE_CASE)) { m -> m.groupValues[1].toIntOrNull(16)?.takeIf { it < 0x110000 }?.let { String(Character.toChars(it)) } ?: "" }
        .replace("&quot;", "\"").replace(Regex("&#39;|&apos;"), "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&nbsp;", " ").replace("&amp;", "&")

    private fun rank(sizes: String?, href: String, type: String? = null): Int {
        if (Regex("svg", RegexOption.IGNORE_CASE).containsMatchIn(type ?: "") ||
            Regex("\\.svg(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(href) ||
            Regex("any", RegexOption.IGNORE_CASE).containsMatchIn(sizes ?: "")
        ) return -1   // SVG: the TV can't draw it
        var best = 0
        (sizes ?: "").split(Regex("\\s+")).forEach { s ->
            Regex("^(\\d+)x\\d+$", RegexOption.IGNORE_CASE).find(s)?.let { best = maxOf(best, it.groupValues[1].toInt()) }
        }
        return best
    }

    private class Page(val title: String?, val icons: List<Pair<String, Int>>, val manifests: List<String>)

    private fun parsePage(htmlIn: String, base: HttpUrl): Page {
        val html = htmlIn.take(MAX_HTML_CHARS)
        val icons = mutableListOf<Pair<String, Int>>()
        val manifests = mutableListOf<String>()
        for (a in linkTags(html)) {
            val rel = (a["rel"] ?: "").lowercase()
            val href = a["href"]?.takeIf { it.isNotEmpty() } ?: continue
            val abs = base.resolve(decodeEntities(href))?.toString() ?: continue
            when {
                "apple-touch-icon" in rel -> icons += abs to rank(a["sizes"], abs).let { if (it == 0) 180 else it }
                Regex("(^|\\s)icon(\\s|$)").containsMatchIn(rel) -> icons += abs to rank(a["sizes"], abs, a["type"]).let { if (it == 0) 16 else it }
                rel == "manifest" -> manifests += abs
            }
        }
        val title = pageTitle(html)?.let { decodeEntities(it).replace(Regex("\\s+"), " ").trim() }
        return Page(title, icons.filter { it.second > 0 }, manifests)
    }

    class Icon(val bytes: ByteArray, val ext: String, val width: Int, val height: Int)

    /** The best icon a site declares, or null. Blocking, at most 30 s. */
    fun findSiteIcon(pageUrl: String): Icon? {
        val until = deadline(30_000)
        val candidates = mutableListOf<Pair<String, Int>>()
        var base = pageUrl.toHttpUrlOrNull() ?: return null
        fun add(url: String?, size: Int) { if (url != null && Regex("^https?:", RegexOption.IGNORE_CASE).containsMatchIn(url)) candidates += url to size }
        runCatching {
            val r = get(pageUrl, "text/html,application/xhtml+xml", 600 * 1024, 9000, until)
            base = r.url
            if (r.status >= 400) return@runCatching
            val page = parsePage(r.body.toString(Charsets.UTF_8), base)
            page.icons.forEach { add(it.first, it.second) }
            page.manifests.firstOrNull()?.let { manUrl ->
                runCatching {
                    val m = get(manUrl, "application/manifest+json,application/json", 200 * 1024, 6000, until)
                    val list = JSONObject(m.body.toString(Charsets.UTF_8)).optJSONArray("icons")
                    for (i in 0 until minOf(list?.length() ?: 0, MAX_MANIFEST_ICONS)) {
                        val ic = list!!.optJSONObject(i) ?: continue
                        val src = ic.optString("src").ifEmpty { null } ?: continue
                        if ("monochrome" in ic.optString("purpose")) continue
                        val abs = m.url.resolve(src)?.toString() ?: continue
                        val size = rank(ic.optString("sizes"), abs, ic.optString("type"))
                        if (size > 0) add(abs, size)
                    }
                }
            }
        }
        val origin = "${base.scheme}://${base.host}" + if (base.port != HttpUrl.defaultPort(base.scheme)) ":${base.port}" else ""
        add("$origin/apple-touch-icon.png", 120)
        add("$origin/favicon.ico", 16)
        val tries = candidates.sortedByDescending { it.second }.distinctBy { it.first }.take(8)
        var fallback: Icon? = null
        for ((url, _) in tries) {
            if (now() >= until) break
            val r = runCatching { get(url, "image/*", 1024 * 1024, 7000, until) }.getOrNull() ?: continue
            if (r.status != 200) continue
            val info = Images.sniff(r.body) ?: continue
            val icon = Icon(r.body, info.ext, info.width, info.height)
            if (info.width >= 64) return icon
            if (fallback == null || info.width > fallback.width) fallback = icon
        }
        return fallback
    }

    class YtInfo(val title: String, val author: String, val thumbnail: String)

    /** Title, channel and thumbnail URL from YouTube's key-less oEmbed endpoint. Blocking. */
    fun youtubeInfo(p: YouTube.Link, until: Long = Long.MAX_VALUE): YtInfo? = runCatching {
        val link = java.net.URLEncoder.encode(YouTube.canonicalUrl(p), "UTF-8")
        val r = get("https://www.youtube.com/oembed?format=json&url=$link", "application/json", 64 * 1024, 7000, until)
        if (r.status != 200) return null
        val j = JSONObject(r.body.toString(Charsets.UTF_8))
        YtInfo(j.optString("title").take(200), j.optString("author_name").take(120), j.optString("thumbnail_url"))
    }.getOrNull()

    class Thumb(val bytes: ByteArray, val width: Int, val height: Int)

    /** The best thumbnail. A missing size comes back as a 120x90 grey JPEG, so 120 px or less counts as missing. */
    fun youtubeThumb(p: YouTube.Link, info: YtInfo?, until: Long = Long.MAX_VALUE): Thumb? {
        val urls = mutableListOf<String>()
        p.videoId?.let { id ->
            listOf("maxresdefault", "hq720", "sddefault", "hqdefault", "mqdefault").forEach { urls += "https://i.ytimg.com/vi/$id/$it.jpg" }
        }
        if (info != null && Regex("^https://i\\d?\\.ytimg\\.com/").containsMatchIn(info.thumbnail)) urls += info.thumbnail
        for (url in urls) {
            if (now() >= until) break
            val r = runCatching { get(url, "image/jpeg,image/*", 1024 * 1024, 7000, until) }.getOrNull() ?: continue
            if (r.status != 200) continue
            val size = Images.jpegSize(r.body) ?: continue
            if (size.first <= 120) continue
            return Thumb(r.body, size.first, size.second)
        }
        return null
    }
}
