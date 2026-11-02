package com.ticktxt.app.core.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.ticktxt.app.core.todotxt.TodoTask
import com.ticktxt.app.ui.screens.FullScreenAlarmActivity
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

data class PermissionDiagnostics(
    val canScheduleExactAlarms: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val notificationsEnabled: Boolean,
    val fullScreenIntentAllowed: Boolean,
    val scheduledCount: Int
)

/**
 * Schedules exact Android alarms that fire reliably even in Doze mode.
 * Includes incremental diff-based scheduling (`syncTaskSchedules`) so unchanged tasks
 * never trigger redundant `AlarmManager` binder calls, while completed/deleted tasks
 * have their pending alarms canceled immediately.
 */
class ReminderScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val diffPrefs = context.getSharedPreferences(DIFF_PREFS_NAME, Context.MODE_PRIVATE)

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

    /**
     * Optimization #1: Incremental Diff-Based Alarm Sync.
     * Compares desired future reminder triggers against `scheduledCache`:
     * - Cancels any previously scheduled reminderId that was removed or completed.
     * - Registers `AlarmManager.setAlarmClock` ONLY for newly added or modified trigger times.
     */
    fun syncTaskSchedules(
        tasks: List<TodoTask>,
        now: ZonedDateTime = ZonedDateTime.now(),
        forceAll: Boolean = false
    ) {
        ensureCacheLoaded()
        val nowMs = System.currentTimeMillis()
        val desired = HashMap<String, Pair<ScheduledReminderInstance, Long>>()

        for (task in tasks) {
            if (task.isCompleted || task.isBlankOrComment) continue
            val instances = ReminderScheduleCalculator.computeSchedulesForTask(task, now)
            for (inst in instances) {
                val targetMs = inst.triggerAt.toInstant().toEpochMilli()
                if (targetMs > nowMs - 5_000L) {
                    desired[inst.reminderId] = inst to targetMs
                }
            }
        }

        val staleIds = scheduledCache.keys.filter { it !in desired.keys }
        val prefsEditor = diffPrefs.edit()
        var changed = false

        for (staleId in staleIds) {
            cancelInstance(staleId)
            scheduledCache.remove(staleId)
            prefsEditor.remove(staleId)
            changed = true
        }

        for ((remId, pair) in desired) {
            val (inst, targetMs) = pair
            val previousMs = scheduledCache[remId]
            if (forceAll || previousMs == null || kotlin.math.abs(previousMs - targetMs) > 500L) {
                scheduleInstance(inst, overrideTriggerEpochMs = targetMs)
                scheduledCache[remId] = targetMs
                prefsEditor.putLong(remId, targetMs)
                changed = true
            }
        }

        if (changed) {
            prefsEditor.apply()
        }
    }

    private fun ensureCacheLoaded() {
        if (cacheInitialized) return
        synchronized(scheduledCache) {
            if (!cacheInitialized) {
                for ((k, v) in diffPrefs.all) {
                    if (v is Long) {
                        scheduledCache[k] = v
                    }
                }
                cacheInitialized = true
            }
        }
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
        scheduledCache[reminderId] = safeTriggerMs
        diffPrefs.edit().putLong(reminderId, safeTriggerMs).apply()

        try {
            if (canScheduleExact()) {
                val showIntent = PendingIntent.getActivity(
                    context,
                    requestCode,
                    Intent(context, FullScreenAlarmActivity::class.java).apply {
                        putExtra(EXTRA_REMINDER_ID, reminderId)
                        putExtra(EXTRA_TASK_ID, taskStableId)
                        putExtra(EXTRA_RAW_SPEC, rawSpec)
                        putExtra(EXTRA_TASK_TITLE, taskDescription)
                        putExtra(EXTRA_REMINDER_TYPE, typeCode)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                // setAlarmClock is never throttled by Doze and guarantees exact second-level delivery
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
                2_000L,
                pendingIntent
            )
        }
    }

    fun fireImmediateNotification(
        taskTitle: String,
        rawSpec: String = "0m/a",
        typeCode: String = "a",
        taskId: String = "immediate"
    ) {
        val remId = "$taskId|$rawSpec"
        val intent = Intent(context, AlarmBroadcastReceiver::class.java).apply {
            action = ACTION_FIRE_REMINDER
            putExtra(EXTRA_REMINDER_ID, remId)
            putExtra(EXTRA_TASK_ID, taskId)
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
        private const val DIFF_PREFS_NAME = "tick_alarm_diff_registry"
        private val scheduledCache = ConcurrentHashMap<String, Long>()
        @Volatile
        private var cacheInitialized = false

        const val ACTION_FIRE_REMINDER = "com.ticktxt.app.ACTION_FIRE_REMINDER"
        const val ACTION_SNOOZE_REMINDER = "com.ticktxt.app.ACTION_SNOOZE_REMINDER"
        const val ACTION_DISMISS_REMINDER = "com.ticktxt.app.ACTION_DISMISS_REMINDER"
        const val ACTION_MARK_DONE = "com.ticktxt.app.ACTION_MARK_DONE"
        const val ACTION_TASKS_UPDATED = "com.ticktxt.app.ACTION_TASKS_UPDATED"
        const val ACTION_ALARM_FIRED_UI = "com.ticktxt.app.ACTION_ALARM_FIRED_UI"

        const val EXTRA_REMINDER_ID = "extra_reminder_id"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_RAW_SPEC = "extra_raw_spec"
        const val EXTRA_TASK_TITLE = "extra_task_title"
        const val EXTRA_REMINDER_TYPE = "extra_reminder_type"
        const val EXTRA_SNOOZE_PRESET = "extra_snooze_preset"
    }
}
