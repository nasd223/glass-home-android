package com.glasshome.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** An app the dock can show: its 16:9 banner if it has one, otherwise its icon and name. */
class TvApp(
    val id: String,
    val label: String,
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
    val intent: Intent,
)

/** Installed TV apps, and the dock's order (kept on the device). */
class Apps(private val context: Context) {
    companion object {
        /** The system settings, as a dock entry: on Google TV there is no other way back to them. */
        const val SETTINGS = "@settings"
    }

    private val prefs = context.getSharedPreferences("dock", Context.MODE_PRIVATE)

    // Decoded banners, by package and install time: Home is pressed often, apps rarely change.
    private val cache = HashMap<String, Pair<Long, TvApp>>()

    /** Every launchable TV app plus Settings, keyed by id, in name order. Slow: call off the main thread. */
    @Synchronized
    fun installed(): Map<String, TvApp> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val apps = pm.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { ri ->
                val info = ri.activityInfo
                val updated = runCatching { pm.getPackageInfo(info.packageName, 0).lastUpdateTime }.getOrDefault(0L)
                cache[info.packageName]?.takeIf { it.first == updated }?.let { return@map it.second }
                val banner = info.loadBanner(pm) ?: info.applicationInfo.loadBanner(pm)
                TvApp(
                    id = info.packageName,
                    label = ri.loadLabel(pm).toString(),
                    banner = banner?.toBitmap(320, 180)?.asImageBitmap(),
                    icon = if (banner == null) ri.loadIcon(pm)?.toBitmap(96, 96)?.asImageBitmap() else null,
                    intent = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                        .setComponent(ComponentName(info.packageName, info.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                ).also { cache[info.packageName] = updated to it }
            }
            .sortedBy { it.label.lowercase() }
        // If the settings app is also listed as a TV app, show it once, as Settings, with its banner.
        val settingsIntent = Intent(Settings.ACTION_SETTINGS)
        val resolved = pm.resolveActivity(settingsIntent, 0)?.activityInfo
        val settingsPkg = resolved?.packageName
        val listed = apps.firstOrNull { it.id == settingsPkg }
        val settings = TvApp(
            id = SETTINGS,
            label = "Settings",
            banner = listed?.banner ?: resolved?.loadBanner(pm)?.toBitmap(320, 180)?.asImageBitmap(),
            icon = if (listed == null) resolved?.loadIcon(pm)?.toBitmap(96, 96)?.asImageBitmap() else listed.icon,
            // A fresh task, so it opens at the top rather than on the screen last used.
            intent = settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        return (apps.filter { it.id != settingsPkg } + settings).associateBy { it.id }
    }

    /** The dock's ids in order. Ids of apps that are uninstalled are kept, so they return if reinstalled. */
    fun dockIds(installed: Map<String, TvApp>): List<String> {
        prefs.getString("order", null)?.let { saved ->
            return saved.split(',').filter { it.isNotEmpty() }
        }
        val defaults = Config.defaultDock.filter { it in installed }
        // None of the usual apps installed: start with the first few there are.
        if (defaults.size <= 1) return installed.keys.filter { it != SETTINGS }.take(6) + SETTINGS
        return defaults
    }

    fun saveDock(ids: List<String>) {
        prefs.edit().putString("order", ids.joinToString(",")).apply()
    }
}
