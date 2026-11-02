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
 * Schedules exact Android alarms that fire reliably even in Doze mode.
 * Also supports direct millisecond delay scheduling (`scheduleExactAfterMillis`)
 * so "in 1m", "in 1 hr", "in 1.5 hrs" fire at the exact target second.
 */
class ReminderScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun canScheduleExact(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(true)
        } else {
            true
        }
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun scheduleInstance(instance: ScheduledReminderInstance, overrideTriggerEpochMs: Long? = null) {
        val triggerEpochMs = overrideTriggerEpochMs ?: instance.triggerAt.toInstant().toEpochMilli()
        scheduleRaw(
            reminderId = instance.reminderId,
            taskStableId = instance.taskStableId,
            rawSpec = instance.spec.rawSpec,
            taskDescription = instance.taskDescription,
            typeCode = instance.spec.type.code,
            triggerEpochMs = triggerEpochMs
        )
    }

    fun scheduleRaw(
        reminderId: String,
        taskStableId: String,
        rawSpec: String,
        taskDescription: String,
        typeCode: String,
        triggerEpochMs: Long
    ) {
        val intent = Intent(context, AlarmBroadcastReceiver::class.java).apply {
            action = ACTION_FIRE_REMINDER
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_TASK_ID, taskStableId)
            putExtra(EXTRA_RAW_SPEC, rawSpec)
            putExtra(EXTRA_TASK_TITLE, taskDescription)
            putExtra(EXTRA_REMINDER_TYPE, typeCode)
        }
        val requestCode = reminderId.hashCode()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val safeTriggerMs = triggerEpochMs.coerceAtLeast(System.currentTimeMillis() + 1000L)

        try {
            if (canScheduleExact()) {
                val showIntent = PendingIntent.getActivity(
                    context,
                    requestCode,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                // setAlarmClock is never throttled by Doze and guarantees exact delivery
                val clockInfo = AlarmManager.AlarmClockInfo(safeTriggerMs, showIntent)
                alarmManager.setAlarmClock(clockInfo, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    safeTriggerMs,
                    pendingIntent
                )
            }
        } catch (_: SecurityException) {
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP,
                safeTriggerMs,
                5_000L,
                pendingIntent
            )
        }
    }

    fun fireImmediateNotification(taskTitle: String, rawSpec: String = "0m/n", typeCode: String = "n") {
        val intent = Intent(context, AlarmBroadcastReceiver::class.java).apply {
            action = ACTION_FIRE_REMINDER
            putExtra(EXTRA_REMINDER_ID, "immediate_${System.currentTimeMillis()}")
            putExtra(EXTRA_TASK_ID, "immediate")
            putExtra(EXTRA_RAW_SPEC, rawSpec)
            putExtra(EXTRA_TASK_TITLE, taskTitle)
            putExtra(EXTRA_REMINDER_TYPE, typeCode)
        }
        context.sendBroadcast(intent)
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
        const val ACTION_TASKS_UPDATED = "com.ticktxt.app.ACTION_TASKS_UPDATED"

        const val EXTRA_REMINDER_ID = "extra_reminder_id"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_RAW_SPEC = "extra_raw_spec"
        const val EXTRA_TASK_TITLE = "extra_task_title"
        const val EXTRA_REMINDER_TYPE = "extra_reminder_type"
        const val EXTRA_SNOOZE_PRESET = "extra_snooze_preset"
    }
}
