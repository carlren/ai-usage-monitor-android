package com.carlren.aiusagemonitor

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

data class WeatherSnapshot(
    val temperature: Double,
    val feelsLike: Double,
    val windMph: Double,
    val weatherCode: Int,
    val observedAt: Instant,
    val isDay: Boolean,
) {
    val icon: String get() = when (weatherCode) {
        0 -> if (isDay) "☀️" else "🌙"
        1, 2 -> if (isDay) "🌤️" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "❄️"
        95, 96, 97, 99 -> "⛈️"
        else -> "❔"
    }

    val condition: String get() = when (weatherCode) {
        0 -> "Clear sky"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95, 97 -> "Thunderstorm"
        96, 99 -> "Thunderstorm / hail"
        else -> "Conditions unavailable"
    }

    fun isStale(now: Instant): Boolean = observedAt.plusSeconds(30 * 60).isBefore(now)
}

object WeatherApi {
    // Bellevue, WA. Fixed coordinates avoid requesting location permission.
    private const val API_URL = "https://api.open-meteo.com/v1/forecast" +
        "?latitude=47.6101&longitude=-122.2015" +
        "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,is_day" +
        "&temperature_unit=celsius&wind_speed_unit=mph" +
        "&timezone=America%2FLos_Angeles&timeformat=unixtime&forecast_days=1"

    fun fetch(): WeatherSnapshot {
        if (BuildConfig.DEMO_MODE) {
            return WeatherSnapshot(17.0, 16.0, 4.0, 2, Instant.now(), isDay = true)
        }
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
        }
        try {
            check(connection.responseCode in 200..299) {
                "Weather service returned HTTP ${connection.responseCode}"
            }
            return parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(body: String): WeatherSnapshot {
        val current = JSONObject(body).getJSONObject("current")
        val temperature = current.getDouble("temperature_2m")
        val feelsLike = current.getDouble("apparent_temperature")
        val wind = current.getDouble("wind_speed_10m")
        val time = current.getLong("time")
        val isDay = current.getInt("is_day")
        require(temperature.isFinite() && feelsLike.isFinite() && wind.isFinite() && wind >= 0 && time > 0 && isDay in 0..1) {
            "Weather service returned invalid readings"
        }
        return WeatherSnapshot(temperature, feelsLike, wind, current.getInt("weather_code"), Instant.ofEpochSecond(time), isDay == 1)
    }
}
