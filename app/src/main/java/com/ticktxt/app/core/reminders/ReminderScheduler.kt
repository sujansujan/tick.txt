package com.ticktxt.app.core.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.ticktxt.app.MainActivity

data class PermissionDiagnostics(
    val canScheduleExactAlarms: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val notificationsEnabled: Boolean,
    val fullScreenIntentAllowed: Boolean,
    val scheduledCount: Int
)

/**
 * Wraps Android's `AlarmManager` with exact Doze-aware scheduling:
 * - For `ReminderType.ALARM`: uses `AlarmManager.setAlarmClock(...)` which wakes the device
 *   out of Doze, shows in the system status bar, and launches `FullScreenAlarmActivity` over lockscreen.
 * - For `ReminderType.NOTIFICATION` and `SILENT`: uses `AlarmManager.setExactAndAllowWhileIdle(...)`.
 */
class ReminderScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun canScheduleExact(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun scheduleInstance(instance: ScheduledReminderInstance) {
        val triggerEpochMs = instance.triggerAt.toInstant().toEpochMilli()
        val intent = Intent(context, AlarmBroadcastReceiver::class.java).apply {
            action = ACTION_FIRE_REMINDER
            putExtra(EXTRA_REMINDER_ID, instance.reminderId)
            putExtra(EXTRA_TASK_ID, instance.taskStableId)
            putExtra(EXTRA_RAW_SPEC, instance.spec.rawSpec)
            putExtra(EXTRA_TASK_TITLE, instance.taskDescription)
            putExtra(EXTRA_REMINDER_TYPE, instance.spec.type.code)
        }
        val requestCode = instance.reminderId.hashCode()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (instance.spec.type == ReminderType.ALARM && canScheduleExact()) {
            val showIntent = PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val clockInfo = AlarmManager.AlarmClockInfo(triggerEpochMs, showIntent)
            alarmManager.setAlarmClock(clockInfo, pendingIntent)
        } else if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerEpochMs,
                pendingIntent
            )
        } else {
            // Graceful fallback while user hasn't granted SCHEDULE_EXACT_ALARM yet
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerEpochMs,
                pendingIntent
            )
        }
    }

    fun cancelInstance(reminderId: String) {
        val intent = Intent(context, AlarmBroadcastReceiver::class.java).apply {
            action = ACTION_FIRE_REMINDER
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminderId.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    companion object {
        const val ACTION_FIRE_REMINDER = "com.ticktxt.app.ACTION_FIRE_REMINDER"
        const val ACTION_SNOOZE_REMINDER = "com.ticktxt.app.ACTION_SNOOZE_REMINDER"
        const val ACTION_DISMISS_REMINDER = "com.ticktxt.app.ACTION_DISMISS_REMINDER"
        const val ACTION_MARK_DONE = "com.ticktxt.app.ACTION_MARK_DONE"

        const val EXTRA_REMINDER_ID = "extra_reminder_id"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_RAW_SPEC = "extra_raw_spec"
        const val EXTRA_TASK_TITLE = "extra_task_title"
        const val EXTRA_REMINDER_TYPE = "extra_reminder_type"
        const val EXTRA_SNOOZE_PRESET = "extra_snooze_preset"
    }
}
