package com.glasshome.tv

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.glasshome.tv.web.PhotoService
import java.io.File

/**
 * Website and YouTube shortcuts added from the phone, as dock tiles. A YouTube
 * tile shows the video's thumbnail; a website tile its icon and name.
 */
object Shortcuts {
    const val PREFIX = "sc:"
    private const val YOUTUBE_TV = "com.google.android.youtube.tv"
    private const val STUBS = "com.android.tv.frameworkpackagestubs"

    /**
     * The app YouTube links go to: the YouTube app when it's enabled, otherwise
     * another app that opens YouTube links (such as TizenTube), or null.
     */
    fun youtubeApp(pm: android.content.pm.PackageManager): String? {
        val official = runCatching { pm.getApplicationInfo(YOUTUBE_TV, 0).enabled }.getOrDefault(false)
        if (official) return YOUTUBE_TV
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        return pm.queryIntentActivities(probe, 0).map { it.activityInfo.packageName }.firstOrNull { it != STUBS }
    }

    // Decoded images by file: icon files are renamed when they change, so a name is never stale.
    private val cache = HashMap<String, ImageBitmap?>()

    fun isShortcut(id: String?) = id?.startsWith(PREFIX) == true
    fun serviceId(id: String) = id.removePrefix(PREFIX)

    /** Dock tiles for [list]. Decodes images: call off the main thread. */
    @Synchronized
    fun tiles(list: List<PhotoService.TvShortcut>, youtubeApp: String?): List<TvApp> {
        val live = list.mapNotNull { it.icon?.path }.toSet()
        cache.keys.retainAll(live)
        return list.map { sc ->
            val youtube = sc.type == "youtube"
            val image = sc.icon?.let { f -> cache.getOrPut(f.path) { decode(f, banner = youtube) } }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(sc.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // The plain watch link opens the YouTube app at the video (what worked best on Samsung too).
            if (youtube && youtubeApp != null) intent.setPackage(youtubeApp)
            TvApp(
                id = PREFIX + sc.id,
                label = sc.name,
                banner = if (youtube) image else null,
                icon = if (youtube) null else image,
                intent = intent,
            )
        }
    }

    /** A 320 x 180 centre crop (a YouTube thumbnail, letterbox bars cut off), or an icon of at most 96 px. */
    private fun decode(f: File, banner: Boolean): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        // Icons come from any website: refuse silly sizes rather than run out of memory.
        if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) return null
        val target = if (banner) 320 else 96
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target * 9 / 16) sample *= 2
        val src = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val out = if (banner) {
            val h = minOf(src.height, src.width * 9 / 16).coerceAtLeast(1)
            val w = (h * 16 / 9).coerceIn(1, src.width)
            val crop = Bitmap.createBitmap(src, (src.width - w) / 2, (src.height - h) / 2, w, h)
            Bitmap.createScaledBitmap(crop, 320, 180, true)
        } else {
            val scale = minOf(1f, 96f / maxOf(src.width, src.height))
            Bitmap.createScaledBitmap(src, maxOf(1, (src.width * scale).toInt()), maxOf(1, (src.height * scale).toInt()), true)
        }
        out.asImageBitmap()
    }.getOrNull()
}
