package com.glasshome.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Shader
import org.json.JSONArray
import java.io.File
import java.time.LocalDate
import kotlin.math.max
import kotlin.random.Random

/** A bundled photo ([asset]) or one uploaded from a phone ([upload]). */
class Wallpaper(val asset: String?, val upload: File?, val title: String, val author: String)

/**
 * A photo ready to draw: the sharp one for the backdrop, and a small, blurred
 * copy that the glass panels draw behind themselves. Blurring once per photo
 * keeps the frosted look cheap enough for a Chromecast's GPU.
 */
class Photo(val sharp: Bitmap, val soft: Bitmap, val caption: String) {
    /** The blurred copy as a shader, so a glass panel is one rounded rectangle to draw, not a clip. */
    val softShader = BitmapShader(soft, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
}

/**
 * The bundled CC0 photos and the ones uploaded from a phone, shuffled together
 * once a day. A newly uploaded photo is shown next.
 */
class PhotoLibrary(private val context: Context) {
    private val bundled: List<Wallpaper> = run {
        val json = context.assets.open("wallpapers/manifest.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(json)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Wallpaper(o.getString("file"), null, o.optString("title"), o.optString("author"))
        }
    }
    private var uploads: List<File>? = null
    private var playlist: List<Wallpaper> = shuffle(bundled)
    private val fresh = ArrayDeque<File>()
    private var cursor = 0

    private fun shuffle(list: List<Wallpaper>) = list.shuffled(Random(LocalDate.now().toEpochDay()))

    /** The uploaded photos now on the TV. Ones not seen before are queued to show next. */
    @Synchronized
    fun setUploads(files: List<File>) {
        val before = uploads
        if (before != null) {
            val known = before.toSet()
            files.filter { it !in known }.forEach { fresh.addLast(it) }
        }
        fresh.retainAll(files.toSet())
        uploads = files
        playlist = shuffle(bundled + files.map { Wallpaper(null, it, "", "") })
    }

    /** The photo to show next. */
    @Synchronized
    fun next(): Wallpaper {
        fresh.removeFirstOrNull()?.let { return Wallpaper(null, it, "", "") }
        return playlist[cursor++ % playlist.size]
    }

    /** Decodes a wallpaper no larger than the screen. Slow: call off the main thread. */
    fun load(w: Wallpaper, screenW: Int, screenH: Int): Photo {
        val open = { w.upload?.inputStream() ?: context.assets.open("wallpapers/${w.asset}") }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= screenW && bounds.outHeight / (sample * 2) >= screenH) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = open().use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Can't decode ${w.asset ?: w.upload}")
        // No bigger than the slow zoom needs: a photo up to twice the screen's
        // size costs the GPU up to four times as much to draw, every frame.
        val cover = max(screenW.toFloat() / decoded.width, screenH.toFloat() / decoded.height) * Config.KEN_BURNS_SCALE
        val sized = if (cover >= 0.9f) decoded else Bitmap.createScaledBitmap(
            decoded, (decoded.width * cover).toInt().coerceAtLeast(1), (decoded.height * cover).toInt().coerceAtLeast(1), true,
        )
        val soft = blurred(sized)
        // Uploaded to the GPU here, off the main thread, rather than on the first
        // frame of the cross-fade, where it would stall it.
        val sharp = sized.copy(Bitmap.Config.HARDWARE, false) ?: sized
        soft.prepareToDraw()
        // The software copies are 8 to 30 MB each: free them now rather than at the next GC.
        if (decoded !== sized && decoded !== soft) decoded.recycle()
        if (sized !== sharp && sized !== soft) sized.recycle()
        // Credits for the bundled photos; your own photos need none.
        val caption = if (w.asset == null) "" else listOf(w.title, w.author, "CC0").filter { it.isNotBlank() }.joinToString(" · ")
        return Photo(sharp, soft, caption)
    }

    /** A 240 px wide copy with a three-pass box blur (close to a Gaussian). */
    private fun blurred(src: Bitmap): Bitmap {
        val w = 240
        val h = (src.height * w / src.width).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) {
            boxBlur(px, w, h, 5, horizontal = true)
            boxBlur(px, w, h, 5, horizontal = false)
        }
        small.setPixels(px, 0, w, 0, 0, w, h)
        return small
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val out = IntArray(len)
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val c = i.coerceIn(0, len - 1)
                return if (horizontal) line * w + c else c * w + line
            }
            var rs = 0; var gs = 0; var bs = 0
            for (i in -r..r) {
                val p = px[at(i)]
                rs += (p shr 16) and 0xff; gs += (p shr 8) and 0xff; bs += p and 0xff
            }
            val n = 2 * r + 1
            for (i in 0 until len) {
                out[i] = (0xff shl 24) or ((rs / n) shl 16) or ((gs / n) shl 8) or (bs / n)
                val add = px[at(i + r + 1)]
                val sub = px[at(i - r)]
                rs += ((add shr 16) and 0xff) - ((sub shr 16) and 0xff)
                gs += ((add shr 8) and 0xff) - ((sub shr 8) and 0xff)
                bs += (add and 0xff) - (sub and 0xff)
            }
            for (i in 0 until len) px[at(i)] = out[i]
        }
    }
}
