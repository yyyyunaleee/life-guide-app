package com.example.mci_project

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

internal enum class WeatherCity(val label: String, val latitude: Double, val longitude: Double) {
    SEOUL("서울", 37.5665, 126.9780),
    INCHEON("인천", 37.4563, 126.7052),
    DAEJEON("대전", 36.3504, 127.3845),
    DAEGU("대구", 35.8714, 128.6014),
    GWANGJU("광주", 35.1595, 126.8526),
    BUSAN("부산", 35.1796, 129.0756),
    JEJU("제주", 33.4996, 126.5312)
}

internal class LifeWeather(context: Context) {
    data class Reading(val summary: String, val fetchedAt: Long)
    private val prefs = context.getSharedPreferences("life_weather", Context.MODE_PRIVATE)

    var city: WeatherCity
        get() = WeatherCity.entries.firstOrNull { it.name == prefs.getString("city", null) } ?: WeatherCity.SEOUL
        set(value) { prefs.edit().putString("city", value.name).apply() }

    fun cached(city: WeatherCity): Reading? {
        val text = prefs.getString("${city.name}:text", null) ?: return null
        return Reading(text, prefs.getLong("${city.name}:time", 0))
    }

    fun fetch(city: WeatherCity): Reading {
        val url = URL("https://api.open-meteo.com/v1/forecast?latitude=${city.latitude}" +
            "&longitude=${city.longitude}&current=temperature_2m,weather_code&timezone=Asia%2FSeoul")
        val connection = url.openConnection() as HttpURLConnection
        val current = try {
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            val data = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(data).getJSONObject("current")
        } finally {
            connection.disconnect()
        }
        val temperature = current.getDouble("temperature_2m").roundToInt()
        val condition = when (current.getInt("weather_code")) {
            0 -> "맑음"
            in 1..2 -> "구름 조금"
            3 -> "흐림"
            45, 48 -> "안개"
            in 51..67, in 80..82 -> "비"
            in 71..77, 85, 86 -> "눈"
            in 95..99 -> "천둥번개"
            else -> "날씨 상태 미확인"
        }
        val reading = Reading("$condition · $temperature°C", System.currentTimeMillis())
        prefs.edit().putString("${city.name}:text", reading.summary)
            .putLong("${city.name}:time", reading.fetchedAt).apply()
        return reading
    }
}
