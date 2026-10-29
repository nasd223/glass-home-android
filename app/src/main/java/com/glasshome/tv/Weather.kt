package com.glasshome.tv

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

class Day(val name: String, val code: Int, val high: Int, val low: Int, val rainPct: Int?)
class Forecast(val temp: Int, val code: Int, val isDay: Boolean, val days: List<Day>)

/** Where the forecast is for. */
data class Place(val name: String, val latitude: Double, val longitude: Double, val timezone: String, val fahrenheit: Boolean) {
    /** Which place a saved forecast belongs to. */
    val key get() = "$latitude,$longitude,$fahrenheit"
}

/**
 * Open-Meteo forecast, with the last answer kept so it shows straight away after a
 * restart. The place is set on the TV (select the weather, press OK) and kept on
 * the device; until then it's the one built in from glasshome.properties, if any.
 */
class WeatherSource(context: Context, default: Config.Weather) {
    private val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)

    /** Null when the weather is off. */
    var place by mutableStateOf(
        when {
            prefs.getBoolean("off", false) -> null
            prefs.contains("lat") -> Place(
                prefs.getString("name", "") ?: "",
                prefs.getString("lat", null)?.toDoubleOrNull() ?: 0.0,
                prefs.getString("lon", null)?.toDoubleOrNull() ?: 0.0,
                prefs.getString("tz", "auto") ?: "auto",
                prefs.getBoolean("fahrenheit", false),
            )
            default.enabled -> Place(default.place, default.latitude, default.longitude, default.timezone, default.fahrenheit)
            else -> null
        },
    )
        private set

    /** Shows [p]'s weather from now on, or none if null. */
    fun save(p: Place?) {
        prefs.edit().apply {
            clear()
            if (p == null) putBoolean("off", true)
            else putString("name", p.name).putString("lat", p.latitude.toString()).putString("lon", p.longitude.toString())
                .putString("tz", p.timezone).putBoolean("fahrenheit", p.fahrenheit)
        }.apply()
        place = p
    }

    fun cached(p: Place): Forecast? =
        prefs.getString("json", null)?.takeIf { prefs.getString("json_for", null) == p.key }
            ?.let { runCatching { parse(it) }.getOrNull() }

    /** Fetches a fresh forecast for [p]. Blocking: call off the main thread. */
    fun fetch(p: Place): Forecast {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${p.latitude}&longitude=${p.longitude}" +
            "&current=temperature_2m,weather_code,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&forecast_days=7&timezone=${URLEncoder.encode(p.timezone, "UTF-8")}" +
            if (p.fahrenheit) "&temperature_unit=fahrenheit" else ""
        val body = get(url)
        val forecast = parse(body)
        prefs.edit().putString("json", body).putString("json_for", p.key).apply()
        return forecast
    }

    /**
     * The place for a ZIP or postal code (or a town's name), from Open-Meteo's
     * geocoding, in this TV's country first. Null if there's none. Blocking.
     */
    fun find(query: String): Place? {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val country = Locale.getDefault().country
        val base = "https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=en&format=json"
        val o = (if (country.length == 2) results(get("$base&countryCode=$country")) else null)
            ?: results(get(base))
            ?: return null
        val name = o.getString("name")
        val region = o.optString("admin1").takeIf { it.isNotBlank() && it != name } ?: o.optString("country")
        return Place(
            name = if (region.isBlank()) name else "$name, $region",
            latitude = o.getDouble("latitude"),
            longitude = o.getDouble("longitude"),
            timezone = o.optString("timezone").ifBlank { "auto" },
            fahrenheit = o.optString("country_code") in Fahrenheit,
        )
    }

    private fun results(body: String) = JSONObject(body).optJSONArray("results")?.optJSONObject(0)

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode != 200) error("Open-Meteo HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): Forecast {
        val o = JSONObject(body)
        val cur = o.getJSONObject("current")
        val d = o.getJSONObject("daily")
        val dates = d.getJSONArray("time")
        val rain = d.optJSONArray("precipitation_probability_max")
        // "Today" where the forecast is for, which may not be the TV's own time zone.
        val today = runCatching { LocalDate.parse(cur.getString("time").take(10)) }.getOrElse { LocalDate.now() }
        val days = (0 until dates.length()).map { i ->
            val date = LocalDate.parse(dates.getString(i))
            Day(
                name = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                code = d.getJSONArray("weather_code").getInt(i),
                high = Math.round(d.getJSONArray("temperature_2m_max").getDouble(i)).toInt(),
                low = Math.round(d.getJSONArray("temperature_2m_min").getDouble(i)).toInt(),
                rainPct = rain?.takeUnless { it.isNull(i) }?.getInt(i),
            ) to date
        }.filter { !it.second.isBefore(today) }.map { it.first }
        return Forecast(
            temp = Math.round(cur.getDouble("temperature_2m")).toInt(),
            code = cur.getInt("weather_code"),
            isDay = cur.optInt("is_day", 1) == 1,
            days = days,
        )
    }
}

/** Where temperatures are given in Fahrenheit. */
private val Fahrenheit = setOf("US", "PR", "GU", "VI", "AS", "MP", "LR", "BS", "BZ", "KY", "PW", "FM", "MH")

/** WMO weather code to a label and a symbol. */
object WeatherCodes {
    fun label(code: Int, isDay: Boolean = true): String = when (code) {
        0 -> if (isDay) "Sunny" else "Clear"
        1 -> if (isDay) "Mostly sunny" else "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51 -> "Light drizzle"
        53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73, 77 -> "Snow"
        75 -> "Heavy snow"
        80, 81 -> "Showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Storms"
        96, 99 -> "Storms with hail"
        else -> ""
    }

    fun symbol(code: Int, isDay: Boolean = true): String = when (code) {
        0, 1 -> if (isDay) "☀️" else "🌙"
        2 -> if (isDay) "⛅" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..67, 80, 81, 82 -> "🌧️"
        in 71..77, 85, 86 -> "🌨️"
        in 95..99 -> "⛈️"
        else -> "☁️"
    }
}
