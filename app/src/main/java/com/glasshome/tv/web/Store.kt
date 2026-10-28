package com.glasshome.tv.web

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/*
 * Persistent state and files, ported from service/store.js. Everything lives in
 * the app's private storage, so it goes when the app is uninstalled.
 *
 * <dir>/state.json        password hash, sessions (hashed), photos, shortcuts
 * <dir>/state.json.bak    previous good copy
 * <dir>/shown.json        the current password, for the TV to show after a restart
 * <dir>/photos/<id>.jpg   1920x1080 JPEGs uploaded from the phone
 * <dir>/thumbs/<id>.jpg   small copies for the phone's grid
 * <dir>/icons/<file>      shortcut icons and YouTube thumbnails
 * <dir>/cache/<file>      YouTube previews shown on the phone before adding
 */

class Session(val h: String, val created: Long, var renewed: Long, var expires: Long, val pw: String) {
    fun toJson(): JSONObject = JSONObject().put("h", h).put("created", created).put("renewed", renewed)
        .put("expires", expires).put("pw", pw)
}

class UserPhoto(val id: String, val added: Long, val bytes: Long, val w: Int, val h: Int, var thumb: Boolean, var thumbBytes: Long) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("added", added).put("bytes", bytes)
        .put("w", w).put("h", h).put("thumb", thumb).put("thumbBytes", thumbBytes)
}

class Shortcut(
    val id: String,
    val type: String,                 // "web" or "youtube"
    var name: String,
    val url: String,
    val videoId: String?,
    val playlistId: String?,
    val start: Int,
    var icon: String?,
    var iconW: Int,
    var iconH: Int,
    var iconState: String,            // pending, retry, ok, none
    val added: Long,
    var autoName: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("type", type).put("name", name).put("url", url)
        .put("videoId", videoId ?: JSONObject.NULL).put("playlistId", playlistId ?: JSONObject.NULL)
        .put("start", start).put("icon", icon ?: JSONObject.NULL).put("iconW", iconW).put("iconH", iconH)
        .put("iconState", iconState).put("added", added).put("autoName", autoName)
}

class State {
    var rev = 1
    var password: Password? = null
    var sessions = mutableListOf<Session>()
    var photos = mutableListOf<UserPhoto>()
    var shortcuts = mutableListOf<Shortcut>()
}

class Store(val dir: File, private val log: (String) -> Unit) {
    companion object {
        val PHOTO_ID = Regex("^[a-f0-9]{24}$")
        val SHORTCUT_ID = Regex("^[a-f0-9]{16}$")
        val ICON_FILE = Regex("^[a-f0-9]{16}-[a-f0-9]{8}\\.(png|jpg|gif|webp|ico)$")
        val CACHE_FILE = Regex("^yt-[a-f0-9]{16}\\.jpg$")

        fun unlinkQuiet(f: File) { runCatching { f.delete() } }
    }

    val photosDir = File(dir, "photos")
    val thumbsDir = File(dir, "thumbs")
    val iconsDir = File(dir, "icons")
    val cacheDir = File(dir, "cache")
    private val file = File(dir, "state.json")
    var state = State()
        private set

    private fun read(f: File): State {
        val d = JSONObject(f.readText())
        if (d.optInt("v") != 1) error("bad state")
        return State().apply {
            rev = d.optInt("rev", 1)
            password = Password.fromJson(d.optJSONObject("password"))
            sessions = d.optJSONArray("sessions").objects().mapNotNull { o ->
                runCatching { Session(o.getString("h"), o.optLong("created"), o.optLong("renewed"), o.getLong("expires"), o.optString("pw")) }.getOrNull()
            }.toMutableList()
            photos = d.optJSONArray("photos").objects().mapNotNull { o ->
                runCatching {
                    UserPhoto(o.getString("id"), o.optLong("added"), o.optLong("bytes"), o.optInt("w"), o.optInt("h"),
                        o.optBoolean("thumb"), o.optLong("thumbBytes"))
                }.getOrNull()
            }.toMutableList()
            shortcuts = d.optJSONArray("shortcuts").objects().mapNotNull { o ->
                runCatching {
                    Shortcut(o.getString("id"), o.optString("type", "web"), o.optString("name"), o.getString("url"),
                        o.optStringOrNull("videoId"), o.optStringOrNull("playlistId"), o.optInt("start"),
                        o.optStringOrNull("icon"), o.optInt("iconW"), o.optInt("iconH"), o.optString("iconState", "none"),
                        o.optLong("added"), o.optBoolean("autoName"))
                }.getOrNull()
            }.toMutableList()
        }
    }

    fun load() {
        listOf(dir, photosDir, thumbsDir, iconsDir, cacheDir).forEach { it.mkdirs() }
        var loaded: State? = null
        for (f in listOf(file, File(dir, "state.json.bak"))) {
            if (loaded != null) break
            try { if (f.exists()) loaded = read(f) } catch (e: Exception) { log("state unreadable: $f ($e)") }
        }
        state = loaded ?: State()
        reconcile()
        save()
    }

    /** Makes state.json agree with the files on disk after a crash or power cut. */
    private fun reconcile() {
        val s = state
        val onDisk = HashSet<String>()
        photosDir.list().orEmpty().forEach { f ->
            if (f.startsWith(".up-")) { unlinkQuiet(File(photosDir, f)); return@forEach }
            Regex("^([a-f0-9]{24})\\.jpg$").find(f)?.let { onDisk += it.groupValues[1] }
        }
        s.photos = s.photos.filter { PHOTO_ID.matches(it.id) && it.id in onDisk }.toMutableList()
        val known = s.photos.map { it.id }.toSet()
        (onDisk - known).forEach { id ->
            val f = File(photosDir, "$id.jpg")
            s.photos += UserPhoto(id, f.lastModified(), f.length(), 0, 0, false, 0)
        }
        s.photos.sortBy { it.added }
        val thumbs = HashSet<String>()
        thumbsDir.list().orEmpty().forEach { f ->
            val m = Regex("^([a-f0-9]{24})\\.jpg$").find(f)
            if (m != null && m.groupValues[1] in onDisk) thumbs += m.groupValues[1] else unlinkQuiet(File(thumbsDir, f))
        }
        s.photos.forEach {
            it.thumb = it.id in thumbs
            it.thumbBytes = if (it.thumb) File(thumbsDir, "${it.id}.jpg").length() else 0
        }

        s.shortcuts = s.shortcuts.filter { SHORTCUT_ID.matches(it.id) }.toMutableList()
        val icons = HashSet<String>()
        s.shortcuts.forEach { x ->
            val icon = x.icon
            if (icon != null && !(ICON_FILE.matches(icon) && File(iconsDir, icon).exists())) x.icon = null
            x.icon?.let { icons += it }
            if (x.iconState == "pending") x.iconState = "retry"
        }
        iconsDir.list().orEmpty().forEach { if (it !in icons) unlinkQuiet(File(iconsDir, it)) }
        cacheDir.list().orEmpty().forEach { unlinkQuiet(File(cacheDir, it)) }

        val now = System.currentTimeMillis()
        s.sessions = s.sessions.filter { it.h.isNotEmpty() && it.expires > now }.toMutableList()
    }

    fun save() {
        val s = state
        val data = JSONObject()
            .put("v", 1).put("rev", s.rev)
            .put("password", s.password?.toJson() ?: JSONObject.NULL)
            .put("sessions", JSONArray(s.sessions.map { it.toJson() }))
            .put("photos", JSONArray(s.photos.map { it.toJson() }))
            .put("shortcuts", JSONArray(s.shortcuts.map { it.toJson() }))
            .toString()
        atomicWrite(file, data) { if (file.exists()) runCatching { file.copyTo(File(dir, "state.json.bak"), overwrite = true) } }
    }

    private fun atomicWrite(target: File, data: String, beforeRename: () -> Unit = {}) {
        val tmp = File(target.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(data.toByteArray(Charsets.UTF_8))
            runCatching { out.fd.sync() }
        }
        beforeRename()
        if (!tmp.renameTo(target)) error("rename failed: $target")
    }

    /** The current password in plain text, kept apart from its hash so the TV can show it after a restart. */
    fun readShown(): Pair<String, String>? = runCatching {
        val d = JSONObject(File(dir, "shown.json").readText())
        val id = d.getString("id")
        val value = d.getString("value")
        if (value.length <= 200) id to value else null
    }.getOrNull()

    fun writeShown(id: String, value: String) {
        try {
            atomicWrite(File(dir, "shown.json"), JSONObject().put("id", id).put("value", value).toString())
        } catch (e: Exception) {
            log("could not keep the password for display: $e")
        }
    }

    fun bump() {
        state.rev += 1
        save()
    }

    fun photoPath(id: String): File { require(PHOTO_ID.matches(id)); return File(photosDir, "$id.jpg") }
    fun thumbPath(id: String): File { require(PHOTO_ID.matches(id)); return File(thumbsDir, "$id.jpg") }
    fun iconPath(name: String): File { require(ICON_FILE.matches(name)); return File(iconsDir, name) }
    fun cachePath(name: String): File { require(CACHE_FILE.matches(name)); return File(cacheDir, name) }

    fun findPhoto(id: String) = state.photos.firstOrNull { it.id == id }
    fun findShortcut(id: String) = state.shortcuts.firstOrNull { it.id == id }
    fun totalBytes() = state.photos.sumOf { it.bytes + it.thumbBytes }

    fun removePhoto(id: String): Boolean {
        if (!state.photos.removeAll { it.id == id }) return false
        unlinkQuiet(photoPath(id))
        unlinkQuiet(thumbPath(id))
        bump()
        return true
    }

    fun removeShortcut(id: String): Boolean {
        val sc = findShortcut(id) ?: return false
        state.shortcuts.remove(sc)
        sc.icon?.let { unlinkQuiet(iconPath(it)) }
        bump()
        return true
    }

    /** [ids] in their new order; unknown ids are ignored, unnamed shortcuts keep their order after the named ones. */
    fun reorderShortcuts(ids: List<String>) {
        val byId = state.shortcuts.associateBy { it.id }
        val out = LinkedHashMap<String, Shortcut>()
        ids.forEach { id -> byId[id]?.let { if (id !in out) out[id] = it } }
        state.shortcuts.forEach { if (it.id !in out) out[it.id] = it }
        state.shortcuts = out.values.toMutableList()
        bump()
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else optString(k).ifEmpty { null }
