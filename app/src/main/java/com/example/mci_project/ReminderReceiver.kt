package com.example.mci_project

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Calendar

internal object Reminders {
    const val FIRE = "com.example.mci_project.REMINDER_FIRE"
    const val CONFIRM = "com.example.mci_project.REMINDER_CONFIRM"
    const val PAUSE = "com.example.mci_project.REMINDER_PAUSE"
    const val ID = "schedule_id"
    const val DATE = "schedule_date"
    private const val OWNER = "schedule_owner"
    private const val AT = "alarm_at"
    private const val CHANNEL = "life_schedule"

    fun canNotify(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channelEnabled = Build.VERSION.SDK_INT < 26 ||
            context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        return granted && channelEnabled && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun canBeExact(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun intent(context: Context, actionName: String, id: String, owner: String?): Intent =
        Intent(context, ReminderReceiver::class.java).apply {
            action = actionName
            data = Uri.Builder().scheme("life-guide").authority("reminder").appendPath(owner ?: "device").appendPath(id).build()
            putExtra(ID, id)
            putExtra(OWNER, owner)
        }

    fun cancel(context: Context, id: String, owner: String? = null) {
        val pending = PendingIntent.getBroadcast(
            context, 0, intent(context, FIRE, id, owner),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pending != null) {
            context.getSystemService(AlarmManager::class.java).cancel(pending)
            pending.cancel()
        }
        dismiss(context, id, owner)
    }

    fun dismiss(context: Context, id: String, owner: String? = null) {
        NotificationManagerCompat.from(context).cancel(tag(id, owner), 0)
    }

    private fun tag(id: String, owner: String?) = "${owner ?: "device"}:$id"

    fun schedule(context: Context, item: LifeSchedule, owner: String? = null, after: Long = System.currentTimeMillis()) {
        if (!item.reminder || (owner != null && ScheduleStore(context, owner).isPaused(item.id))) return
        val time = item.nextAlarm(after) ?: return
        val pending = PendingIntent.getBroadcast(
            context, 0, intent(context, FIRE, item.id, owner).putExtra(AT, time),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val manager = context.getSystemService(AlarmManager::class.java)
        if (canBeExact(context)) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
                return
            } catch (_: SecurityException) {
                // Permission can change between checking and scheduling.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
    }

    fun fire(context: Context, item: LifeSchedule, trigger: Long, owner: String?) {
        val now = System.currentTimeMillis()
        if (!item.reminder || trigger <= 0L || item.nextAlarm(trigger - 1) != trigger) return
        schedule(context, item, owner, maxOf(trigger, now))
        val day = dayKey(Calendar.getInstance().apply { timeInMillis = trigger })
        if (day != dayKey(Calendar.getInstance()) || ScheduleStore(context, owner).let { it.isDone(item.id, day) || it.isPaused(item.id) }) return
        show(context, item, day, owner)
    }

    private fun show(context: Context, item: LifeSchedule, day: String, owner: String?) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "생활 일정 알림", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        if (!canNotify(context)) return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        fun action(name: String) = PendingIntent.getBroadcast(
            context, 0, intent(context, name, item.id, owner).putExtra(DATE, day), flags
        )
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).apply {
            data = Uri.Builder().scheme("life-guide").authority("open").appendPath(owner ?: "device").appendPath(item.id).build()
            putExtra(ID, item.id)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }, flags)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("생활 일정 안내")
            .setContentText("${item.timeLabel()} ${item.title}")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setContentIntent(open)
            .addAction(0, "확인", action(CONFIRM))
            .addAction(0, "알림 끄기", action(PAUSE))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(tag(item.id, owner), 0, notification)
        } catch (_: SecurityException) {
            // Notification permission was revoked after the check.
        }
    }

    fun handle(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                val uid = FirebaseLifeSchedules.uid(context)
                val owners = listOfNotNull(uid).map<String, String?> { it } + listOf(null)
                owners.forEach { owner ->
                    ScheduleStore(context, owner).all().forEach {
                        cancel(context, it.id, owner)
                        schedule(context, it, owner)
                    }
                }
            }
            FIRE, CONFIRM, PAUSE -> {
                val owner = intent.getStringExtra(OWNER)
                if (owner != null && FirebaseLifeSchedules.uid(context) != owner) return
                val store = ScheduleStore(context, owner)
                val item = store.all().firstOrNull { it.id == intent.getStringExtra(ID) } ?: return
                when (intent.action) {
                    FIRE -> fire(context, item, intent.getLongExtra(AT, 0), owner)
                    CONFIRM -> {
                        val date = intent.getStringExtra(DATE) ?: return
                        store.markDone(item.id, date)
                        dismiss(context, item.id, owner)
                    }
                    PAUSE -> {
                        if (owner == null) store.put(item.copy(reminder = false))
                        else store.pauseReminder(item.id)
                        cancel(context, item.id, owner)
                    }
                }
            }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Reminders.handle(context, intent)
}
