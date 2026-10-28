package com.glasshome.tv.web

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/*
 * The photo and shortcut website, ported from the Samsung version's
 * service/server.js. It runs inside the app, so the TV screen reads the state
 * directly (tvState) instead of over a loopback channel.
 *
 * Two kinds of caller:
 *   website - a phone or computer on the home LAN, signed in with the password
 *             (session cookie: HttpOnly, SameSite=Strict, 30 days)
 *   anyone  - only GET /health, the sign-in page and its images
 * Clients outside loopback and the private LAN ranges are dropped.
 */
class PhotoService(private val context: Context) {
    companion object {
        const val PORT = 8090
        const val VERSION = "1.0.0"
        private const val COOKIE = "gh_sid"
        private const val DAY = 24 * 3600 * 1000L
        private const val MAX_PHOTOS = 300
        private const val MAX_PHOTO_BYTES = 15L * 1024 * 1024
        private const val MAX_THUMB_BYTES = 1024L * 1024
        private const val MAX_TOTAL_BYTES = 500L * 1024 * 1024
        private const val MAX_SHORTCUTS = 60
        private const val SESSION_DAYS = 30
        private const val MAX_SESSIONS = 30
    }

    /** What the TV screen shows. */
    class TvState(
        val rev: Int,
        val url: String?,
        val password: String?,
        val photos: List<File>,
        val shortcuts: List<TvShortcut>,
    )

    class TvShortcut(
        val id: String, val type: String, val name: String, val url: String,
        val icon: File?, val iconW: Int, val iconH: Int,
    )

    private val log: (String) -> Unit = { android.util.Log.i("GlassHome", it) }
    private val lock = Any()                      // guards store
    @Volatile private lateinit var store: Store
    private var plain: Pair<String, String>? = null   // (password id, value)
    // At most 6 password checks wait or run at once, so they never hold all 16 web threads.
    private val limiter = Limiter(maxQueue = 6)
    private var iterations = 300_000
    private val uploadsInFlight = AtomicInteger(0)
    private val enrichQueue = Executors.newSingleThreadExecutor()
    private val housekeeping = Executors.newSingleThreadScheduledExecutor()
    private var server: HttpServer? = null
    private var webTemplate: String? = null
    private val wallpapers: List<String> by lazy {
        context.assets.list("wallpapers").orEmpty().filter { Regex("^[A-Za-z0-9._-]+\\.jpe?g$", RegexOption.IGNORE_CASE).matches(it) }.sorted()
    }

    private val _revision = MutableStateFlow(0)
    /** Bumps whenever photos, shortcuts or the password change. */
    val revision: StateFlow<Int> = _revision

    @Volatile var startError: String? = null
        private set

    fun start() {
        Thread({
            try {
                synchronized(lock) {
                    val s = Store(File(context.filesDir, "glasshome"), log)
                    s.load()
                    s.readShown()?.let { if (it.first == s.state.password?.id) plain = it }
                    store = s
                }
                if (store.state.password == null) setPassword(Auth.generatePassword(), generated = true)
                synchronized(lock) {
                    store.state.shortcuts.filter { it.iconState == "retry" || it.iconState == "pending" }.forEach { enqueue(it.id) }
                }
                housekeeping.scheduleWithFixedDelay({ limiter.sweep() }, 1, 1, TimeUnit.MINUTES)
                server = HttpServer(PORT, log, ::handle).also { it.start() }
                changed()
                log("Glass Home service $VERSION on port $PORT")
            } catch (e: Exception) {
                startError = e.message ?: e.toString()
                log("start failed: $e")
                changed()
            }
        }, "gh-service-start").start()
    }

    private fun changed() {
        _revision.value = if (::store.isInitialized) store.state.rev else _revision.value + 1
    }

    // ---------- for the TV screen ----------

    fun tvState(): TvState? {
        if (!::store.isInitialized) return null
        synchronized(lock) {
            val pw = store.state.password
            return TvState(
                rev = store.state.rev,
                url = Net.lanAddresses().firstOrNull()?.let { "http://$it:$PORT" },
                password = plain?.takeIf { it.first == pw?.id }?.second,
                photos = store.state.photos.map { store.photoPath(it.id) },
                shortcuts = store.state.shortcuts.map { sc ->
                    TvShortcut(sc.id, sc.type, sc.name, sc.url, sc.icon?.let { store.iconPath(it) }, sc.iconW, sc.iconH)
                },
            )
        }
    }

    /** A new generated password; every phone is signed out. Blocking (PBKDF2). */
    fun newPassword(): String? {
        if (!::store.isInitialized) return null
        val value = Auth.generatePassword()
        setPassword(value, generated = true)
        return value
    }

    fun removeShortcut(id: String) {
        if (!::store.isInitialized) return
        synchronized(lock) { store.removeShortcut(id) }
        changed()
    }

    fun reorderShortcuts(ids: List<String>) {
        if (!::store.isInitialized) return
        synchronized(lock) { store.reorderShortcuts(ids) }
        changed()
    }

    // ---------- password ----------

    private fun timed(iter: Int, startedAt: Long) {
        val ms = SystemClock.elapsedRealtime() - startedAt
        if (ms > 1500 && iterations > 200_000) {
            iterations = 200_000
            log("PBKDF2 is slow here ($ms ms for $iter iterations); new hashes use 200000")
        }
    }

    /** Sets the password and returns its id. Only here is the hashing cost measured, never during a login. */
    private fun setPassword(value: String, generated: Boolean): String {
        val t0 = SystemClock.elapsedRealtime()
        val rec = Auth.hashPassword(value, iterations, generated)
        timed(rec.iter, t0)
        synchronized(lock) {
            store.state.password = rec
            store.state.sessions.clear()                  // every existing sign-in ends
            plain = rec.id to (if (generated) Auth.canonicalGenerated(value) else value)
            store.writeShown(rec.id, plain!!.second)      // so the TV can show it after a restart
            store.bump()
        }
        changed()
        return rec.id
    }

    private class Check(val ok: Boolean, val locked: Boolean = false, val retryAfter: Int = 0, val remaining: Int = 0)

    /** Wrong passwords are throttled per address and globally (see [Limiter]); throws [Busy] when refused. */
    private fun checkPassword(ip: String, attempt: String): Check {
        val started = SystemClock.elapsedRealtime()
        val key = Net.clientKey(ip)                       // an IPv6 /64 is one device
        val st = limiter.status(key)
        if (st.locked) return Check(false, locked = true, retryAfter = st.retryAfter)
        val result = limiter.serial(key) {
            val again = limiter.status(key)
            if (again.locked) return@serial Check(false, locked = true, retryAfter = again.retryAfter)
            Thread.sleep(limiter.globalDelay())
            val rec = synchronized(lock) { store.state.password }
            val ok = Auth.verifyPassword(attempt, rec)
            if (ok) { limiter.success(key); Check(true) } else {
                val r = limiter.fail(key)
                Check(false, locked = r.locked, retryAfter = r.retryAfter, remaining = r.remaining)
            }
        }
        if (!result.ok) Thread.sleep(maxOf(0L, 600 - (SystemClock.elapsedRealtime() - started)))
        return result
    }

    private fun authFailure(r: Check): Response =
        if (r.locked) HttpServer.json(429, JSONObject().put("error", "locked").put("retryAfter", r.retryAfter), mapOf("Retry-After" to r.retryAfter.toString()))
        else HttpServer.json(401, JSONObject().put("error", "wrong-password").put("remaining", r.remaining))

    // ---------- sessions ----------

    private fun cookieValue(tok: String, maxAge: Int) = "$COOKIE=$tok; Max-Age=$maxAge; Path=/; HttpOnly; SameSite=Strict"

    /** The signed-in session, renewing it (sliding 30 days, once a day) into [out]'s headers. */
    private fun sessionFor(req: Request, out: MutableMap<String, String>): Session? {
        val now = System.currentTimeMillis()
        // Every gh_sid sent counts: another site on this address can set one of its own.
        for (tok in req.cookies(COOKIE).take(4)) {
            if (tok.length > 128) continue
            sessionForToken(tok, now, out)?.let { return it }
        }
        return null
    }

    private fun sessionForToken(tok: String, now: Long, out: MutableMap<String, String>): Session? {
        val h = Auth.sha256hex(tok)
        synchronized(lock) {
            val pw = store.state.password ?: return null
            val s = store.state.sessions.firstOrNull { it.h == h } ?: return null
            if (s.expires <= now || s.pw != pw.id) return null
            if (now - s.renewed > DAY) {
                s.renewed = now
                s.expires = now + SESSION_DAYS * DAY
                store.save()
                out["Set-Cookie"] = cookieValue(tok, SESSION_DAYS * 86400)
            }
            return s
        }
    }

    /** Signs the caller in, for password [pwId] only: if it changed meanwhile (New password on the TV), no session. */
    private fun createSession(out: MutableMap<String, String>, pwId: String? = null) {
        val tok = Auth.randomToken(32)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val pw = store.state.password ?: return
            if (pwId != null && pw.id != pwId) return
            store.state.sessions.add(Session(Auth.sha256hex(tok), now, now, now + SESSION_DAYS * DAY, pw.id))
            store.state.sessions.sortByDescending { it.renewed }
            while (store.state.sessions.size > MAX_SESSIONS) store.state.sessions.removeAt(store.state.sessions.size - 1)
            store.save()
        }
        out["Set-Cookie"] = cookieValue(tok, SESSION_DAYS * 86400)
    }

    /**
     * CSRF guard for the website API: a custom header (which a cross-site page
     * can't send without a CORS preflight this server never approves), plus the
     * Origin and Fetch Metadata headers when the browser sends them.
     */
    private fun sameOrigin(req: Request): Boolean {
        if (req.header("x-gh") != "1") return false
        req.header("origin")?.let { if (it.lowercase() != "http://" + (req.header("host") ?: "").lowercase()) return false }
        req.header("sec-fetch-site")?.let { if (it != "same-origin" && it != "none") return false }
        return true
    }

    // ---------- views ----------

    private fun photoView(p: UserPhoto) = JSONObject().put("id", p.id).put("added", p.added).put("bytes", p.bytes)
        .put("w", p.w).put("h", p.h).put("thumb", p.thumb)

    private fun shortcutView(x: Shortcut) = JSONObject()
        .put("id", x.id).put("type", x.type).put("name", x.name).put("url", x.url)
        .put("videoId", x.videoId ?: JSONObject.NULL).put("playlistId", x.playlistId ?: JSONObject.NULL).put("start", x.start)
        .put("icon", x.icon?.let { "/media/icons/$it" } ?: JSONObject.NULL).put("iconW", x.iconW).put("iconH", x.iconH)
        .put("iconState", x.iconState).put("added", x.added)

    private fun limitsView() = JSONObject()
        .put("photos", store.state.photos.size).put("maxPhotos", MAX_PHOTOS)
        .put("bytes", store.totalBytes()).put("maxBytes", MAX_TOTAL_BYTES)
        .put("shortcuts", store.state.shortcuts.size).put("maxShortcuts", MAX_SHORTCUTS)

    private fun lanInfo(): JSONObject {
        val ip = Net.lanAddresses().firstOrNull()
        return JSONObject().put("ip", ip ?: JSONObject.NULL).put("port", PORT)
            .put("url", ip?.let { "http://$it:$PORT" } ?: JSONObject.NULL)
    }

    // ---------- shortcut enrichment (icons, titles, thumbnails) ----------

    private fun saveIcon(sc: Shortcut, bytes: ByteArray, ext: String, w: Int, h: Int) {
        val name = sc.id + "-" + Auth.randomHex(4) + "." + ext
        store.iconPath(name).writeBytes(bytes)
        sc.icon?.let { Store.unlinkQuiet(store.iconPath(it)) }
        sc.icon = name
        sc.iconW = w
        sc.iconH = h
    }

    private fun enqueue(id: String) {
        enrichQueue.execute {
            try { enrich(id) } catch (e: Exception) { log("enrich failed: $e") }
            changed()
        }
    }

    private fun ytCacheFile(p: YouTube.Link) = "yt-" + Auth.sha256hex((p.videoId ?: "") + "|" + (p.playlistId ?: "")).take(16) + ".jpg"

    private fun enrich(id: String) {
        val sc = synchronized(lock) { store.findShortcut(id) } ?: return
        if (sc.type == "youtube") {
            val p = YouTube.Link(sc.videoId, sc.playlistId, sc.start)
            val info = Fetcher.youtubeInfo(p)
            val cached = store.cachePath(ytCacheFile(p))
            val thumb = cached.takeIf { it.exists() }?.readBytes()?.let { b -> Images.jpegSize(b)?.let { Fetcher.Thumb(b, it.first, it.second) } }
                ?: Fetcher.youtubeThumb(p, info)
            synchronized(lock) {
                val cur = store.findShortcut(id) ?: return
                thumb?.let { saveIcon(cur, it.bytes, "jpg", it.width, it.height) }
                if (info != null && info.title.isNotEmpty() && cur.autoName) {
                    cur.name = cleanName(info.title, 80)
                    cur.autoName = false
                }
                cur.iconState = if (thumb != null) "ok" else "none"
                store.bump()
            }
            return
        }
        val icon = Fetcher.findSiteIcon(sc.url)
        synchronized(lock) {
            val cur = store.findShortcut(id) ?: return
            icon?.let { saveIcon(cur, it.bytes, it.ext, it.width, it.height) }
            cur.iconState = if (icon != null) "ok" else "none"
            store.bump()
        }
    }

    private fun cleanName(s: Any?, max: Int = 40): String =
        (s?.toString() ?: "").replace(Regex("[\\u0000-\\u001f\\u007f]"), " ").replace(Regex("\\s+"), " ").trim().take(max)

    private fun normalizeWebUrl(raw: String?): String? {
        var s = (raw ?: "").trim()
        if (s.isEmpty() || s.length > 2048) return null
        if (!Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(s)) s = "https://" + s.removePrefix("//")
        val u = s.toHttpUrlOrNull() ?: return null          // http and https only
        if (u.username.isNotEmpty() || u.password.isNotEmpty() || u.host.isEmpty()) return null
        if (!Regex("^[a-z0-9.\\[\\]:-]+$", RegexOption.IGNORE_CASE).matches(u.host)) return null
        return u.toString()
    }

    private fun hostLabel(href: String) = href.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: "Website"

    private fun addShortcut(body: JSONObject): Response = synchronized(lock) { addShortcutLocked(body) }.also { changed() }

    private fun addShortcutLocked(body: JSONObject): Response {
        run {
            if (store.state.shortcuts.size >= MAX_SHORTCUTS) return HttpServer.error(507, "too-many-shortcuts")
            var type = if (body.optString("type") == "youtube") "youtube" else "web"
            val name = cleanName(body.opt("name").takeIf { it != JSONObject.NULL }, 80)
            val p: YouTube.Link?
            var url: String? = null
            if (type == "web") {
                url = normalizeWebUrl(body.optString("url")) ?: return HttpServer.error(400, "bad-url")
                p = YouTube.parse(url)                      // a YouTube video link becomes a video tile
                if (p != null) type = "youtube"
            } else {
                p = YouTube.parse(body.optString("url")) ?: return HttpServer.error(400, "not-youtube")
            }
            val sc = if (type == "youtube") Shortcut(
                id = Auth.randomHex(8), type = type,
                name = name.ifEmpty { if (p!!.videoId != null) "YouTube video" else "YouTube playlist" },
                url = YouTube.canonicalUrl(p!!), videoId = p.videoId, playlistId = p.playlistId, start = p.start,
                icon = null, iconW = 0, iconH = 0, iconState = "pending", added = System.currentTimeMillis(), autoName = name.isEmpty(),
            ) else Shortcut(
                id = Auth.randomHex(8), type = type, name = name.ifEmpty { hostLabel(url!!) }, url = url!!,
                videoId = null, playlistId = null, start = 0,
                icon = null, iconW = 0, iconH = 0, iconState = "pending", added = System.currentTimeMillis(), autoName = false,
            )
            store.state.shortcuts.add(sc)
            store.bump()
            enqueue(sc.id)
            return HttpServer.json(201, JSONObject().put("shortcut", shortcutView(sc)))
        }
    }

    // ---------- uploads ----------

    private class Refused(val res: Response) : Exception()

    /**
     * Streams a JPEG body to a temporary file, checks it, then renames it to
     * [dest]. Under the lock, [check] runs just before the rename and may refuse,
     * and [kept] runs just after it to record the file.
     */
    private fun receiveJpeg(
        req: Request,
        maxBytes: Long,
        dest: File,
        check: (Long) -> Response?,
        kept: (bytes: Long, w: Int, h: Int) -> Response,
    ): Response {
        val headMax = 512 * 1024
        val ct = (req.header("content-type") ?: "").substringBefore(';').trim().lowercase()
        if (ct != "image/jpeg" && ct != "image/jpg") throw Refused(HttpServer.error(415, "jpeg-only"))
        if (req.contentLength > maxBytes) throw Refused(HttpServer.json(413, JSONObject().put("error", "too-large").put("max", maxBytes)))
        val tmp = File(dest.parentFile, ".up-" + Auth.randomHex(6))
        val head = ByteArray(headMax)
        var headLen = 0
        var got = 0L
        try {
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = req.body.read(buf)
                    if (n < 0) break
                    got += n
                    if (got > maxBytes) throw Refused(HttpServer.json(413, JSONObject().put("error", "too-large").put("max", maxBytes)))
                    if (headLen < headMax) {
                        val take = minOf(n, headMax - headLen)
                        System.arraycopy(buf, 0, head, headLen, take)
                        headLen += take
                        if (headLen >= 3 && !Images.isJpegStart(head, headLen)) throw Refused(HttpServer.error(415, "not-jpeg"))
                    }
                    out.write(buf, 0, n)
                }
            }
            if (got < req.contentLength) throw Refused(HttpServer.error(400, "incomplete"))
            val size = Images.jpegSize(head, headLen)
            if (size == null || got < 125) throw Refused(HttpServer.error(415, "not-jpeg"))
            if (size.first > 16000 || size.second > 16000) throw Refused(HttpServer.error(415, "too-big-dimensions"))
            return synchronized(lock) {
                check(got)?.let { throw Refused(it) }
                if (!tmp.renameTo(dest)) throw Refused(HttpServer.error(500, "disk"))
                kept(got, size.first, size.second)
            }
        } catch (e: java.io.IOException) {
            // The phone went away mid-upload (or, rarely, the disk failed).
            throw Refused(HttpServer.error(400, "upload-failed").also { it.close = true })
        } finally {
            Store.unlinkQuiet(tmp)
        }
    }

    private fun handlePhotoUpload(req: Request): Response {
        synchronized(lock) {
            if (store.state.photos.size >= MAX_PHOTOS) return refuse(507, JSONObject().put("error", "full").put("reason", "count").put("max", MAX_PHOTOS))
            if (store.totalBytes() + req.contentLength > MAX_TOTAL_BYTES) return refuse(507, JSONObject().put("error", "full").put("reason", "bytes").put("max", MAX_TOTAL_BYTES))
        }
        if (uploadsInFlight.incrementAndGet() > 3) {
            uploadsInFlight.decrementAndGet()
            return refuse(429, JSONObject().put("error", "busy"))
        }
        try {
            val id = Auth.randomHex(12)
            val res = receiveJpeg(req, MAX_PHOTO_BYTES, store.photoPath(id), check = { got ->
                if (store.state.photos.size >= MAX_PHOTOS || store.totalBytes() + got > MAX_TOTAL_BYTES) HttpServer.error(507, "full") else null
            }) { bytes, w, h ->
                val rec = UserPhoto(id, System.currentTimeMillis(), bytes, w, h, false, 0)
                store.state.photos.add(rec)
                store.bump()
                HttpServer.json(201, JSONObject().put("photo", photoView(rec)).put("limits", limitsView()))
            }
            changed()
            return res
        } catch (e: Refused) {
            return e.res.also { if (req.consumed < req.contentLength) it.close = true }
        } finally {
            uploadsInFlight.decrementAndGet()
        }
    }

    /** Thumbnails count towards the storage cap like photos; a new one replaces the old one's bytes. */
    private fun handleThumbUpload(req: Request, id: String): Response {
        val full = JSONObject().put("error", "full").put("reason", "bytes").put("max", MAX_TOTAL_BYTES)
        synchronized(lock) {
            val p = store.findPhoto(id) ?: return refuse(404, JSONObject().put("error", "not-found"))
            if (store.totalBytes() - p.thumbBytes + req.contentLength > MAX_TOTAL_BYTES) return refuse(507, full)
        }
        if (uploadsInFlight.incrementAndGet() > 3) {
            uploadsInFlight.decrementAndGet()
            return refuse(429, JSONObject().put("error", "busy"))
        }
        return try {
            receiveJpeg(req, MAX_THUMB_BYTES, store.thumbPath(id), check = { got ->
                val cur = store.findPhoto(id)
                when {
                    cur == null -> HttpServer.error(404, "not-found")
                    store.totalBytes() - cur.thumbBytes + got > MAX_TOTAL_BYTES -> HttpServer.json(507, full)
                    else -> null
                }
            }) { bytes, _, _ ->
                // Same lock as the check: the photo can't have gone in between.
                val cur = store.findPhoto(id)!!
                cur.thumb = true
                cur.thumbBytes = bytes
                store.save()
                HttpServer.empty(204)
            }
        } catch (e: Refused) {
            e.res.also { if (req.consumed < req.contentLength) it.close = true }
        } finally {
            uploadsInFlight.decrementAndGet()
        }
    }

    // ---------- routes ----------

    /** Answers without reading a body we refuse; the server hangs up if it's large. */
    private fun refuse(status: Int, obj: JSONObject) = HttpServer.json(status, obj)

    private fun readJson(req: Request, limit: Int): JSONObject {
        val ct = (req.header("content-type") ?: "").substringBefore(';').trim().lowercase()
        if (ct != "application/json") throw Refused(HttpServer.error(415, "json-required"))
        if (req.contentLength > limit) throw Refused(HttpServer.error(413, "too-large").also { it.close = true })
        val text = req.body.readBytes().toString(Charsets.UTF_8)
        return try { JSONObject(text.ifEmpty { "{}" }) } catch (e: Exception) { throw Refused(HttpServer.error(400, "bad-json")) }
    }

    private fun website(): Response {
        val template = webTemplate ?: run {
            val html = context.assets.open("web/index.html").bufferedReader().use { it.readText() }
            val parser = context.assets.open("web/ytparse.js").bufferedReader().use { it.readText() }
            html.replace("/*@@YTPARSE@@*/", parser.replace(Regex("</script", RegexOption.IGNORE_CASE), "<\\\\/script"))
        }.also { webTemplate = it }
        val nonce = Auth.randomToken(16)
        return Response(
            200,
            linkedMapOf(
                "Content-Type" to "text/html; charset=utf-8",
                "Cache-Control" to "no-store",
                "X-Frame-Options" to "DENY",
                "Content-Security-Policy" to "default-src 'none'; img-src 'self' blob: data:; style-src 'nonce-$nonce'; script-src 'nonce-$nonce'; connect-src 'self'; manifest-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
            ),
            template.replace("{{NONCE}}", nonce).toByteArray(Charsets.UTF_8),
        )
    }

    private fun asset(path: String, type: String, cacheSeconds: Int): Response = runCatching {
        val bytes = context.assets.open(path).use { it.readBytes() }
        Response(200, linkedMapOf("Content-Type" to type, "Cache-Control" to "private, max-age=$cacheSeconds"), bytes)
    }.getOrElse { HttpServer.error(404, "not-found") }

    private fun file(f: File, type: String, cacheSeconds: Int): Response {
        if (!f.isFile) return HttpServer.error(404, "not-found")
        return Response(200, linkedMapOf(
            "Content-Type" to type,
            "Cache-Control" to "private, max-age=$cacheSeconds" + if (cacheSeconds > 86400) ", immutable" else "",
        ), file = f)
    }

    private fun handle(req: Request): Response {
        if (!Net.hostAllowed(req.header("host"))) return HttpServer.error(421, "host").also { it.close = true }
        val s = req.segs
        val top = s.firstOrNull() ?: ""
        if (top == "health" && s.size == 1) return HttpServer.json(200, JSONObject().put("ok", true).put("name", "Glass Home").put("version", VERSION))
        if (top == "media") return handleMedia(req)
        if (top == "api") {
            val extra = linkedMapOf<String, String>()
            val res = try { handleApi(req, extra) } catch (e: Refused) { e.res } catch (e: Busy) {
                HttpServer.json(503, JSONObject().put("error", "busy"), mapOf("Retry-After" to "5"))
            }
            extra.forEach { (k, v) -> res.headers.putIfAbsent(k, v) }
            return res
        }
        if (req.method != "GET" && req.method != "HEAD") return HttpServer.error(405, "method")
        if (s.size == 1 && (top == "" || top == "index.html")) return website()
        if (s.size == 1 && (top == "apple-touch-icon.png" || top == "favicon.png")) return asset("web/$top", "image/png", 86400)
        if (s.size == 1 && top == "bg.jpg") {
            if (wallpapers.isEmpty()) return HttpServer.error(404, "not-found")
            val day = (System.currentTimeMillis() / DAY).toInt()
            return asset("wallpapers/" + wallpapers[day % wallpapers.size], "image/jpeg", 3600)
        }
        return HttpServer.error(404, "not-found")
    }

    private fun handleMedia(req: Request): Response {
        if (req.method != "GET" && req.method != "HEAD") return HttpServer.error(405, "method")
        val extra = linkedMapOf<String, String>()
        if (sessionFor(req, extra) == null) return HttpServer.error(401, "signin")
        val s = req.segs
        if (s.size != 3) return HttpServer.error(404, "not-found")
        val name = s[2]
        val year = 365 * 86400
        val res = when (s[1]) {
            "photos", "thumbs" -> {
                val id = Regex("^([a-f0-9]{24})\\.jpg$").find(name)?.groupValues?.get(1)
                if (id == null || synchronized(lock) { store.findPhoto(id) } == null) HttpServer.error(404, "not-found")
                else file(if (s[1] == "photos") store.photoPath(id) else store.thumbPath(id), "image/jpeg", year)
            }
            "icons" -> {
                if (!Store.ICON_FILE.matches(name)) HttpServer.error(404, "not-found")
                else file(store.iconPath(name), when (name.substringAfterLast('.')) {
                    "png" -> "image/png"; "jpg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; else -> "image/x-icon"
                }, year)
            }
            "cache" -> if (!Store.CACHE_FILE.matches(name)) HttpServer.error(404, "not-found") else file(store.cachePath(name), "image/jpeg", 3600)
            else -> HttpServer.error(404, "not-found")
        }
        extra.forEach { (k, v) -> res.headers.putIfAbsent(k, v) }
        return res
    }

    private fun handleApi(req: Request, out: MutableMap<String, String>): Response {
        val m = req.method
        val s = req.segs
        if (!sameOrigin(req)) return refuse(403, JSONObject().put("error", "csrf"))

        if (s.getOrNull(1) == "login" && s.size == 2 && m == "POST") {
            val body = readJson(req, 4096)
            val pwId = synchronized(lock) { store.state.password?.id }
            val r = checkPassword(req.ip, body.opt("password")?.takeIf { it != JSONObject.NULL }?.toString() ?: "")
            if (!r.ok) return authFailure(r)
            createSession(out, pwId)
            return HttpServer.json(200, JSONObject().put("ok", true))
        }

        val session = sessionFor(req, out) ?: return refuse(401, JSONObject().put("error", "signin"))

        when (s.getOrNull(1)) {
            "logout" -> if (s.size == 2 && m == "POST") {
                synchronized(lock) {
                    store.state.sessions.removeAll { it.h == session.h }
                    store.save()
                }
                return HttpServer.empty(204, mapOf("Set-Cookie" to cookieValue("", 0)))
            }
            "me" -> if (s.size == 2 && m == "GET") {
                val limits = synchronized(lock) { limitsView() }
                return HttpServer.json(200, JSONObject().put("ok", true).put("limits", limits).put("tv", lanInfo()).put("version", VERSION))
            }
            "password" -> if (s.size == 2 && m == "POST") {
                val body = readJson(req, 4096)
                val next = body.opt("next")?.takeIf { it != JSONObject.NULL }?.toString() ?: ""
                if (next.length < 6 || next.length > 128 || next.first().isWhitespace() || next.last().isWhitespace()) {
                    return HttpServer.error(400, "weak-password")
                }
                val r = checkPassword(req.ip, body.opt("current")?.takeIf { it != JSONObject.NULL }?.toString() ?: "")
                if (!r.ok) return authFailure(r)
                createSession(out, setPassword(next, generated = false))
                return HttpServer.json(200, JSONObject().put("ok", true))
            }
            "photos" -> {
                if (s.size == 2 && m == "GET") {
                    return synchronized(lock) {
                        HttpServer.json(200, JSONObject()
                            .put("photos", JSONArray(store.state.photos.reversed().map { photoView(it) }))
                            .put("limits", limitsView()))
                    }
                }
                if (s.size == 2 && m == "POST") return handlePhotoUpload(req)
                if (s.size >= 3 && !Store.PHOTO_ID.matches(s[2])) return refuse(400, JSONObject().put("error", "bad-id"))
                if (s.size == 4 && s[3] == "thumb" && m == "PUT") return handleThumbUpload(req, s[2])
                if (s.size == 3 && m == "DELETE") {
                    val res = synchronized(lock) {
                        if (!store.removePhoto(s[2])) HttpServer.error(404, "not-found")
                        else HttpServer.json(200, JSONObject().put("ok", true).put("limits", limitsView()))
                    }
                    changed()
                    return res
                }
            }
            "shortcuts" -> {
                if (s.size == 2 && m == "GET") {
                    return synchronized(lock) {
                        HttpServer.json(200, JSONObject()
                            .put("shortcuts", JSONArray(store.state.shortcuts.map { shortcutView(it) }))
                            .put("limits", limitsView()))
                    }
                }
                if (s.size == 2 && m == "POST") return addShortcut(readJson(req, 8192))
                if (s.size == 3 && s[2] == "order" && m == "POST") {
                    val ids = readJson(req, 16384).optJSONArray("ids") ?: return HttpServer.error(400, "ids-required")
                    val list = (0 until ids.length()).mapNotNull { ids.opt(it) as? String }.filter { Store.SHORTCUT_ID.matches(it) }
                    val res = synchronized(lock) {
                        store.reorderShortcuts(list)
                        HttpServer.json(200, JSONObject().put("shortcuts", JSONArray(store.state.shortcuts.map { shortcutView(it) })))
                    }
                    changed()
                    return res
                }
                if (s.size == 3 && !Store.SHORTCUT_ID.matches(s[2])) return refuse(400, JSONObject().put("error", "bad-id"))
                if (s.size == 3 && m == "PATCH") {
                    val body = readJson(req, 4096)
                    val res = synchronized(lock) {
                        val sc = store.findShortcut(s[2]) ?: return HttpServer.error(404, "not-found")
                        val name = cleanName(body.opt("name")?.takeIf { it != JSONObject.NULL }, 80)
                        if (name.isEmpty()) return HttpServer.error(400, "name-required")
                        sc.name = name
                        sc.autoName = false
                        store.bump()
                        HttpServer.json(200, JSONObject().put("shortcut", shortcutView(sc)))
                    }
                    changed()
                    return res
                }
                if (s.size == 3 && m == "DELETE") {
                    val ok = synchronized(lock) { store.removeShortcut(s[2]) }
                    changed()
                    return if (ok) HttpServer.json(200, JSONObject().put("ok", true)) else HttpServer.error(404, "not-found")
                }
            }
            "youtube" -> if (s.getOrNull(2) == "preview" && s.size == 3 && m == "GET") return youtubePreview(req)
        }
        return refuse(404, JSONObject().put("error", "not-found"))
    }

    private fun youtubePreview(req: Request): Response {
        val p = YouTube.parse(req.query["url"]) ?: return HttpServer.error(400, "not-youtube")
        val out = JSONObject()
            .put("videoId", p.videoId ?: JSONObject.NULL).put("playlistId", p.playlistId ?: JSONObject.NULL)
            .put("start", p.start).put("url", YouTube.canonicalUrl(p))
            .put("title", JSONObject.NULL).put("author", JSONObject.NULL).put("thumb", JSONObject.NULL)
        try {
            // On a web thread, so at most 12 s however slow YouTube is.
            val until = Fetcher.deadline(12_000)
            val info = Fetcher.youtubeInfo(p, until)
            if (info != null) out.put("title", info.title).put("author", info.author)
            val name = ytCacheFile(p)
            val cached = store.cachePath(name)
            var have = cached.exists()
            if (!have) Fetcher.youtubeThumb(p, info, until)?.let { t ->
                val tmp = File(store.cacheDir, ".tmp-" + Auth.randomHex(6))
                tmp.writeBytes(t.bytes)
                synchronized(lock) {
                    trimCache()
                    have = tmp.renameTo(cached)
                }
                Store.unlinkQuiet(tmp)
            }
            if (have) out.put("thumb", "/media/cache/$name")
        } catch (e: Exception) {
            log("preview failed: $e")
        }
        return HttpServer.json(200, out)
    }

    private fun trimCache() {
        val files = store.cacheDir.listFiles().orEmpty().filter { Store.CACHE_FILE.matches(it.name) }
        if (files.size < 40) return
        files.sortedBy { it.lastModified() }.take(files.size - 30).forEach { Store.unlinkQuiet(it) }
    }
}
