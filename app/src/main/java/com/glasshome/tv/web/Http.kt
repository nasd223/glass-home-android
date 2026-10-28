package com.glasshome.tv.web

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/*
 * A small HTTP/1.1 server for the phone website: one request at a time per
 * connection, keep-alive, bodies by Content-Length only (what browsers send
 * for fetch() and XHR uploads). Connections from outside loopback and the
 * private LAN ranges are dropped before a byte is read.
 */

class Request(
    val method: String,
    val segs: List<String>,
    val query: Map<String, String>,
    /** Lower-case names. */
    val headers: Map<String, String>,
    val ip: String,
    val loopback: Boolean,
    val contentLength: Long,
    private val input: InputStream,
) {
    /** Bytes of the body read so far; the server drains or drops the rest. */
    var consumed = 0L
        private set

    /** The body, at most [max] bytes; reading past Content-Length gives EOF. */
    val body: InputStream = object : InputStream() {
        override fun read(): Int {
            if (consumed >= contentLength) return -1
            val b = input.read()
            if (b >= 0) consumed++
            return b
        }

        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            if (consumed >= contentLength) return -1
            val n = input.read(buf, off, minOf(len.toLong(), contentLength - consumed).toInt())
            if (n > 0) consumed += n
            return n
        }
    }

    fun header(name: String): String? = headers[name]

    /** Every value of cookie [name]: another site on the same address can add its own, so callers try them all. */
    fun cookies(name: String): List<String> =
        (header("cookie") ?: "").split(';').mapNotNull { part ->
            val j = part.indexOf('=')
            if (j >= 0 && part.substring(0, j).trim() == name) part.substring(j + 1).trim() else null
        }
}

class Response(
    val status: Int,
    val headers: MutableMap<String, String> = linkedMapOf(),
    val body: ByteArray? = null,
    val file: File? = null,
) {
    /** Close the connection after this response (a refused body we won't read). */
    var close = false
}

class HttpServer(
    private val port: Int,
    private val log: (String) -> Unit,
    private val handle: (Request) -> Response,
) {
    private var server: ServerSocket? = null
    // A thread per connection, at most 16; past that a new connection is closed
    // at once rather than queued, so slow clients can't pile up sockets.
    private val pool = ThreadPoolExecutor(16, 16, 30, TimeUnit.SECONDS, SynchronousQueue())
    private val perClient = ConcurrentHashMap<String, AtomicInteger>()

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(java.net.InetSocketAddress(port), 50)
        server = ss
        Thread({
            while (!ss.isClosed) {
                val sock = try { ss.accept() } catch (e: SocketException) { break }
                val addr = Net.normalize(sock.inetAddress.hostAddress ?: "")
                if (!Net.isPrivate(addr)) { runCatching { sock.close() }; continue }
                // At most 6 connections per device (an IPv6 /64 counts as one device).
                val count = perClient.getOrPut(Net.clientKey(addr)) { AtomicInteger() }
                if (count.incrementAndGet() > 6) {
                    count.decrementAndGet()
                    runCatching { sock.close() }
                    continue
                }
                try {
                    pool.execute {
                        try { serve(sock, addr) } finally { count.decrementAndGet() }
                    }
                } catch (e: RejectedExecutionException) {
                    count.decrementAndGet()
                    runCatching { sock.close() }
                }
            }
        }, "gh-http-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { server?.close() }
        pool.shutdownNow()
    }

    private fun serve(sock: Socket, ip: String) {
        sock.use { s ->
            val input = BufferedInputStream(s.getInputStream(), 16 * 1024)
            val out = BufferedOutputStream(s.getOutputStream(), 16 * 1024)
            val loopback = Net.isLoopback(ip)
            repeat(100) {
                // The request line and headers must arrive within 15 s (idle keep-alive included).
                s.soTimeout = 5_000
                val deadline = System.nanoTime() + 15_000_000_000L
                val req = try { readRequest(input, ip, loopback, deadline) } catch (e: Exception) { null } ?: return
                s.soTimeout = 30_000
                val res = if (req.second != null) {
                    error(req.second!!.first, req.second!!.second).also { it.close = true }
                } else try {
                    handle(req.first!!)
                } catch (e: Exception) {
                    log("handler crashed: $e")
                    error(500, "server")
                }
                val r = req.first
                // A body the handler did not read: drain a small one, otherwise hang up.
                // Anything left unread would be taken for the next request.
                if (r != null && r.consumed < r.contentLength) {
                    if (r.contentLength - r.consumed > 64 * 1024 || res.close) res.close = true
                    else runCatching {
                        val buf = ByteArray(8192)
                        while (r.body.read(buf) >= 0) { /* discard */ }
                    }
                    if (r.consumed < r.contentLength) res.close = true
                }
                val keepAlive = r != null && !res.close &&
                    r.headers["connection"]?.lowercase() != "close" && r.headers[":version"] == "HTTP/1.1"
                try {
                    write(out, res, keepAlive, r?.method == "HEAD")
                } catch (e: Exception) {
                    return
                }
                if (!keepAlive) return
            }
        }
    }

    private fun readLine(input: InputStream, max: Int, deadline: Long): String? {
        val buf = java.io.ByteArrayOutputStream(128)
        while (true) {
            if (System.nanoTime() > deadline) throw java.net.SocketTimeoutException("headers too slow")
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else throw java.io.EOFException()
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > max) throw IllegalStateException("line too long")
        }
        return buf.toString("ISO-8859-1")
    }

    /** The request, or an error to answer with (status, code). */
    private fun readRequest(input: InputStream, ip: String, loopback: Boolean, deadline: Long): Pair<Request?, Pair<Int, String>?>? {
        var line = readLine(input, 8192, deadline) ?: return null
        var blanks = 0
        while (line.isEmpty()) {
            if (++blanks > 2) return null
            line = readLine(input, 8192, deadline) ?: return null
        }
        val parts = line.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return null to (400 to "bad-request")
        val headers = HashMap<String, String>()
        headers[":version"] = parts[2]
        var total = 0
        while (true) {
            val h = readLine(input, 8192, deadline) ?: return null
            if (h.isEmpty()) break
            total += h.length
            if (total > 16 * 1024 || headers.size > 100) return null to (431 to "headers")
            val c = h.indexOf(':')
            if (c <= 0) return null to (400 to "bad-header")
            val name = h.substring(0, c).trim().lowercase()
            val value = h.substring(c + 1).trim()
            when {
                name !in headers -> headers[name] = value
                // Two of these can mean different things to different readers (request smuggling).
                name == "content-length" || name == "host" -> return null to (400 to "duplicate-header")
                name == "cookie" -> headers[name] = headers[name] + "; " + value
            }
        }
        if (headers.containsKey("transfer-encoding")) return null to (411 to "length-required")
        val length = headers["content-length"]?.let { it.toLongOrNull() ?: return null to (400 to "bad-length") } ?: 0L
        if (length < 0) return null to (400 to "bad-length")

        val target = parts[1]
        val q = target.indexOf('?')
        val rawPath = if (q >= 0) target.substring(0, q) else target
        val segs = splitPath(rawPath) ?: return null to (400 to "bad-path")
        val query = if (q >= 0) parseQuery(target.substring(q + 1)) else emptyMap()
        return Request(parts[0].uppercase(), segs, query, headers, ip, loopback, length, input) to null
    }

    private fun write(out: OutputStream, res: Response, keepAlive: Boolean, head: Boolean) {
        val length = res.body?.size?.toLong() ?: res.file?.length() ?: 0L
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(res.status).append(' ').append(reason(res.status)).append("\r\n")
        res.headers["X-Content-Type-Options"] = "nosniff"
        res.headers["Referrer-Policy"] = "no-referrer"
        res.headers["Content-Length"] = length.toString()
        res.headers["Connection"] = if (keepAlive) "keep-alive" else "close"
        for ((k, v) in res.headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (!head) {
            res.body?.let { out.write(it) }
            res.file?.inputStream()?.use { it.copyTo(out, 64 * 1024) }
        }
        out.flush()
    }

    companion object {
        fun error(status: Int, code: String) = json(status, org.json.JSONObject().put("error", code))

        fun json(status: Int, obj: org.json.JSONObject, extra: Map<String, String> = emptyMap()) = Response(
            status,
            linkedMapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "Cache-Control" to "no-store",
            ).apply { putAll(extra) },
            obj.toString().toByteArray(Charsets.UTF_8),
        )

        fun empty(status: Int, extra: Map<String, String> = emptyMap()) =
            Response(status, linkedMapOf("Cache-Control" to "no-store").apply { putAll(extra) })

        /** "/api/photos/abc" -> ["api", "photos", "abc"]; null for "..", "." or encoded slashes. */
        fun splitPath(p: String): List<String>? {
            if (!p.startsWith("/") || p.length > 1024) return null
            val out = p.split('/').drop(1).map { raw ->
                val s = percentDecode(raw) ?: return null
                if (s == "." || s == ".." || s.any { it == '/' || it == '\\' || it == '\u0000' }) return null
                s
            }.toMutableList()
            if (out.size > 1 && out.last().isEmpty()) out.removeAt(out.size - 1)
            return out
        }

        fun parseQuery(s: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (kv in s.split('&')) {
                if (kv.isEmpty()) continue
                val j = kv.indexOf('=')
                val k = percentDecode((if (j < 0) kv else kv.substring(0, j)).replace('+', ' ')) ?: continue
                val v = percentDecode((if (j < 0) "" else kv.substring(j + 1)).replace('+', ' ')) ?: continue
                if (k !in out) out[k] = v
            }
            return out
        }

        /** Strict percent-decoding (UTF-8); null when malformed, like decodeURIComponent throwing. */
        fun percentDecode(s: String): String? {
            if ('%' !in s) return s
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%') {
                    if (i + 2 >= s.length) return null
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                    bytes.write(v)
                    i += 3
                } else {
                    bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            val decoder = Charsets.UTF_8.newDecoder()
            return try { decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString() } catch (e: Exception) { null }
        }

        private fun reason(code: Int) = when (code) {
            200 -> "OK"; 201 -> "Created"; 204 -> "No Content"
            400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 411 -> "Length Required"; 413 -> "Payload Too Large"
            415 -> "Unsupported Media Type"; 421 -> "Misdirected Request"; 429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"; 500 -> "Internal Server Error"
            503 -> "Service Unavailable"; 507 -> "Insufficient Storage"
            else -> "Status"
        }
    }
}
