package com.glasshome.tv

/*
 * Glass Home - settings. Edit, then rebuild and reinstall (scripts/install.sh).
 */
object Config {
    const val PHOTO_INTERVAL_MS = 45_000L
    const val FADE_MS = 1_600
    const val KEN_BURNS_SCALE = 1.06f

    // Screensaver: only the photos after this long without a button press.
    const val AMBIENT_ENABLED = true
    const val AMBIENT_AFTER_MS = 3 * 60 * 1000L

    // Evening dimming of the photos, by the TV's clock (24-hour).
    const val NIGHT_ENABLED = true
    const val NIGHT_FROM = "20:00"
    const val NIGHT_TO = "06:00"

    // Weather under the clock, from Open-Meteo (free, no key). Off until you set
    // your location in glasshome.properties (see glasshome.properties.example),
    // which stays out of git. Find coordinates with
    // https://geocoding-api.open-meteo.com/v1/search?name=YOUR+TOWN
    val weather = Weather(
        enabled = BuildConfig.WEATHER_LATITUDE != 0.0 || BuildConfig.WEATHER_LONGITUDE != 0.0,
        place = BuildConfig.WEATHER_PLACE,          // label under the forecast
        latitude = BuildConfig.WEATHER_LATITUDE,
        longitude = BuildConfig.WEATHER_LONGITUDE,
        timezone = BuildConfig.WEATHER_TIMEZONE,    // "auto", or an IANA name such as "Europe/London"
        fahrenheit = BuildConfig.WEATHER_FAHRENHEIT,
        refreshMs = 30 * 60 * 1000L,
    )

    // The dock on first start, in order: whichever of these are installed, then
    // Settings. Afterwards the dock is edited on the TV (hold OK, or +).
    val defaultDock = listOf(
        "com.google.android.youtube.tv",
        "io.gh.reisxd.tizentube.cobalt",       // TizenTube, for when stock YouTube is off
        "com.netflix.ninja",
        "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus",
        "com.wbd.stream",                       // Max
        "com.apple.atve.androidtv.appletv",
        "com.plexapp.android",
        "com.spotify.tv.android",
        "com.google.android.youtube.tvmusic",
        Apps.SETTINGS,
    )

    data class Weather(
        val enabled: Boolean,
        val place: String,
        val latitude: Double,
        val longitude: Double,
        val timezone: String,
        val fahrenheit: Boolean,
        val refreshMs: Long,
    )
}
