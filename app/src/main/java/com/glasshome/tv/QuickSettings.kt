package com.glasshome.tv

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * The quick settings behind the gear by the clock: the settings screens people
 * open most, each only if this TV has it.
 *
 * There's no "clear background apps": since Android 14 an app can only end its
 * own background processes (ActivityManager.killBackgroundProcesses ignores
 * other packages), so Apps opens the system list, where Force stop is.
 */
object QuickSettings {
    fun items(pm: PackageManager): List<TvApp> = listOfNotNull(
        item(pm, "wifi", "Wi-Fi", Settings.ACTION_WIFI_SETTINGS),
        // Google TV has no display screen of its own: display and sound share
        // one, which is what the sound settings action opens there.
        item(pm, "display", "Display & Sound", Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SOUND_SETTINGS),
        item(pm, "apps", "Apps", Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
        item(pm, "all", "All settings", Settings.ACTION_SETTINGS),
    )

    /** The first of [actions] this TV can open, as an entry; null if none. */
    private fun item(pm: PackageManager, id: String, label: String, vararg actions: String): TvApp? {
        val action = actions.firstOrNull { Intent(it).resolveActivity(pm) != null } ?: return null
        return TvApp(
            id = "@qs:$id",
            label = label,
            banner = null,
            icon = null,
            // A fresh task: Settings keeps one, and reusing it can show whichever
            // screen it was last on instead of this one.
            intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }
}
