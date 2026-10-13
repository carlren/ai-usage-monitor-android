package com.carlren.aiusagemonitor

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WeatherApiTest {
    private val response = """{
        "current": {"time": 1791868500, "temperature_2m": 9.3,
        "apparent_temperature": 6.5, "weather_code": 0, "wind_speed_10m": 4.1, "is_day": 0}
    }"""

    @Test fun parsesCurrentConditionsAndMarksOldReadings() {
        val weather = WeatherApi.parse(response)
        assertEquals(9.3, weather.temperature, 0.001)
        assertEquals(6.5, weather.feelsLike, 0.001)
        assertEquals(4.1, weather.windMph, 0.001)
        assertEquals(Instant.ofEpochSecond(1791868500), weather.observedAt)
        assertEquals("Clear sky", weather.condition)
        assertEquals("🌙", weather.icon)
        assertEquals("☀️", weather.copy(isDay = true).icon)
        assertEquals("🌧️", weather.copy(weatherCode = 63).icon)
        assertEquals("❄️", weather.copy(weatherCode = 75).icon)
        assertEquals("⛈️", weather.copy(weatherCode = 95).icon)
        assertFalse(weather.isStale(weather.observedAt.plusSeconds(15 * 60)))
        assertTrue(weather.isStale(weather.observedAt.plusSeconds(31 * 60)))
        assertEquals("Freezing rain", weather.copy(weatherCode = 67).condition)
        assertEquals("Thunderstorm", weather.copy(weatherCode = 97).condition)
        assertEquals("Conditions unavailable", weather.copy(weatherCode = 999).condition)
    }

    @Test fun missingAndInvalidReadingsFailInsteadOfShowingZero() {
        for (body in listOf(
            response.replace("9.3", "null"),
            response.replace("4.1", "-1"),
            response.replace("1791868500", "0"),
            "{}",
        )) {
            assertTrue(runCatching { WeatherApi.parse(body) }.isFailure)
        }
    }
}
