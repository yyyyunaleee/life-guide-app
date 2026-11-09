package com.example.mci_project

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

internal enum class Repeat(val label: String) {
    ONCE("한 번"), DAILY("매일"), WEEKLY("매주")
}

internal data class LifeSchedule(
    val id: String,
    val title: String,
    val date: String,
    val minutes: Int,
    val repeat: Repeat,
    val steps: List<String>,
    val reminder: Boolean
) {
    fun timeLabel(): String = String.format(Locale.KOREA, "%02d:%02d", minutes / 60, minutes % 60)

    fun occursOn(day: Calendar): Boolean {
        val key = dayKey(day)
        if (key < date) return false
        return when (repeat) {
            Repeat.ONCE -> key == date
            Repeat.DAILY -> true
            Repeat.WEEKLY -> day.get(Calendar.DAY_OF_WEEK) == dayFromKey(date).get(Calendar.DAY_OF_WEEK)
        }
    }

    fun nextAlarm(after: Long): Long? {
        val current = Calendar.getInstance().apply { timeInMillis = after }
        val candidate = if (dayKey(current) < date) dayFromKey(date) else current
        repeat(8) {
            if (occursOn(candidate)) {
                val instant = (candidate.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, minutes / 60)
                    set(Calendar.MINUTE, minutes % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                if (instant > after) return instant
            }
            if (repeat == Repeat.ONCE) return null
            candidate.add(Calendar.DATE, 1)
        }
        return null
    }

    fun toJson() = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("date", date)
        put("minutes", minutes)
        put("repeat", repeat.name)
        put("steps", JSONArray(steps))
        put("reminder", reminder)
    }

    companion object {
        fun fromJson(obj: JSONObject): LifeSchedule {
            val steps = obj.getJSONArray("steps")
            return LifeSchedule(
                obj.getString("id"), obj.getString("title"), obj.getString("date"),
                obj.getInt("minutes"), Repeat.valueOf(obj.getString("repeat")),
                List(steps.length()) { steps.getString(it) }, obj.getBoolean("reminder")
            )
        }
    }
}

internal fun formatDate(pattern: String, day: Calendar): String =
    SimpleDateFormat(pattern, Locale.KOREA).apply { timeZone = day.timeZone }.format(day.time)

internal fun dayKey(day: Calendar): String = formatDate("yyyy-MM-dd", day)

internal fun dayFromKey(key: String): Calendar {
    val parts = key.split('-').map(String::toInt)
    return Calendar.getInstance().apply {
        clear()
        set(parts[0], parts[1] - 1, parts[2], 12, 0)
    }
}

internal class ScheduleStore(context: Context, owner: String? = null) {
    private val prefs = context.getSharedPreferences(
        if (owner == null) "life_guide" else "life_guide_cloud_$owner", Context.MODE_PRIVATE
    )

    fun all(): List<LifeSchedule> {
        val data = JSONArray(prefs.getString("schedules", "[]"))
        return List(data.length()) { LifeSchedule.fromJson(data.getJSONObject(it)) }
    }

    fun put(schedule: LifeSchedule) {
        replace(all().filterNot { it.id == schedule.id } + schedule)
    }

    fun remove(id: String) {
        replace(all().filterNot { it.id == id })
        prefs.edit().remove("done:$id").apply()
    }

    fun replace(schedules: List<LifeSchedule>) {
        prefs.edit().putString("schedules", JSONArray(schedules.map { it.toJson() }).toString()).apply()
    }

    fun pauseReminder(id: String) { prefs.edit().putBoolean("paused:$id", true).apply() }
    fun resumeReminder(id: String) { prefs.edit().remove("paused:$id").apply() }
    fun isPaused(id: String): Boolean = prefs.getBoolean("paused:$id", false)

    fun isDone(id: String, date: String): Boolean = prefs.getString("done:$id", null) == date

    fun markDone(id: String, date: String) {
        prefs.edit().putString("done:$id", date).apply()
    }
}
