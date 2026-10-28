package com.glasshome.tv

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

class Day(val name: String, val code: Int, val high: Int, val low: Int, val rainPct: Int?)
class Forecast(val temp: Int, val code: Int, val isDay: Boolean, val days: List<Day>)

/** Open-Meteo forecast, with the last answer kept so it shows straight away after a restart. */
class WeatherSource(private val context: Context, private val cfg: Config.Weather) {
    private val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)

    fun cached(): Forecast? = prefs.getString("json", null)?.let { runCatching { parse(it) }.getOrNull() }

    /** Fetches a fresh forecast. Blocking: call off the main thread. */
    fun fetch(): Forecast {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${cfg.latitude}&longitude=${cfg.longitude}" +
            "&current=temperature_2m,weather_code,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&forecast_days=7&timezone=${URLEncoder.encode(cfg.timezone, "UTF-8")}" +
            if (cfg.fahrenheit) "&temperature_unit=fahrenheit" else ""
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode != 200) error("Open-Meteo HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val forecast = parse(body)
            prefs.edit().putString("json", body).apply()
            return forecast
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
