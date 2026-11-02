package com.ticktxt.app.core.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.ticktxt.app.MainActivity
import com.ticktxt.app.core.storage.TickStorageRepository
import com.ticktxt.app.core.todotxt.TodoParser
import com.ticktxt.app.ui.screens.FullScreenAlarmActivity
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * First-class Android NotificationCompat & Full-Screen Alarm receiver:
 * - Creates a MAX-importance alarm channel with alarm audio attributes, vibration, and lockscreen visibility
 * - Fires BOTH a plain-text ASCII system notification AND launches `FullScreenAlarmActivity`
 *   with granular snooze (sec / min / hr / day / wk / mo).
 */
class AlarmBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(context, nm)

        val reminderId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: "default_id"
        val taskId = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_ID) ?: "task_id"
        val rawSpec = intent.getStringExtra(ReminderScheduler.EXTRA_RAW_SPEC) ?: "0m/a"
        val taskTitle = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_TITLE) ?: "tick.txt task alarm"
        val remType = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_TYPE) ?: "a"
        val notifId = (reminderId.hashCode() and 0x7FFFFFFF).coerceAtLeast(100)

        val repo = TickStorageRepository(context)
        val tickDir = File(repo.baseDir, ".tick").apply { mkdirs() }
        val snoozeStore = SnoozeStateStore(File(tickDir, "alarm-state.json"))

        when (intent.action) {
            ReminderScheduler.ACTION_FIRE_REMINDER -> {
                snoozeStore.recordFired(taskId, rawSpec, System.currentTimeMillis())

                val fullScreenIntent = Intent(context, FullScreenAlarmActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(ReminderScheduler.EXTRA_REMINDER_ID, reminderId)
                    putExtra(ReminderScheduler.EXTRA_TASK_ID, taskId)
                    putExtra(ReminderScheduler.EXTRA_RAW_SPEC, rawSpec)
                    putExtra(ReminderScheduler.EXTRA_TASK_TITLE, taskTitle)
                    putExtra(ReminderScheduler.EXTRA_REMINDER_TYPE, remType)
                    putExtra("extra_notif_id", notifId)
                }

                val fullScreenPendingIntent = PendingIntent.getActivity(
                    context,
                    notifId + 50,
                    fullScreenIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                fun buildActionIntent(actionStr: String, preset: String?, reqOffset: Int): PendingIntent {
                    val aIntent = Intent(context, AlarmBroadcastReceiver::class.java).apply {
                        action = actionStr
                        putExtra(ReminderScheduler.EXTRA_REMINDER_ID, reminderId)
                        putExtra(ReminderScheduler.EXTRA_TASK_ID, taskId)
                        putExtra(ReminderScheduler.EXTRA_RAW_SPEC, rawSpec)
                        putExtra(ReminderScheduler.EXTRA_TASK_TITLE, taskTitle)
                        putExtra(ReminderScheduler.EXTRA_REMINDER_TYPE, remType)
                        if (preset != null) putExtra(ReminderScheduler.EXTRA_SNOOZE_PRESET, preset)
                    }
                    return PendingIntent.getBroadcast(
                        context,
                        notifId + reqOffset,
                        aIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                }

                val asciiBigText = buildString {
                    appendLine("┌─ task due now")
                    appendLine("│  $taskTitle")
                    appendLine("├─ spec : rem:$rawSpec")
                    append("└─ tap to open full-screen snooze (sec/min/hr/day/wk/mo)")
                }

                val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("[!] $taskTitle")
                    .setContentText("rem:$rawSpec // tap for granular snooze or mark [x] done")
                    .setStyle(NotificationCompat.BigTextStyle().bigText(asciiBigText))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setContentIntent(fullScreenPendingIntent)
                    .setFullScreenIntent(fullScreenPendingIntent, true)
                    .setOngoing(remType == "a")
                    .setAutoCancel(true)
                    .addAction(
                        0,
                        "[x] done",
                        buildActionIntent(ReminderScheduler.ACTION_MARK_DONE, null, 1)
                    )
                    .addAction(
                        0,
                        "[+5m]",
                        buildActionIntent(ReminderScheduler.ACTION_SNOOZE_REMINDER, "5m", 2)
                    )
                    .addAction(
                        0,
                        "[snooze...]",
                        fullScreenPendingIntent
                    )

                nm.notify(notifId, builder.build())

                runCatching {
                    context.startActivity(fullScreenIntent)
                }

                context.sendBroadcast(
                    Intent(ReminderScheduler.ACTION_ALARM_FIRED_UI).apply {
                        setPackage(context.packageName)
                        putExtra(ReminderScheduler.EXTRA_REMINDER_ID, reminderId)
                        putExtra(ReminderScheduler.EXTRA_TASK_ID, taskId)
                        putExtra(ReminderScheduler.EXTRA_RAW_SPEC, rawSpec)
                        putExtra(ReminderScheduler.EXTRA_TASK_TITLE, taskTitle)
                    }
                )
            }

            ReminderScheduler.ACTION_SNOOZE_REMINDER -> {
                val preset = intent.getStringExtra(ReminderScheduler.EXTRA_SNOOZE_PRESET) ?: "5m"
                val targetZdt = ReminderScheduleCalculator.computeSnoozeTarget(ZonedDateTime.now(), preset)
                val targetEpochMs = targetZdt.toInstant().toEpochMilli()
                snoozeStore.recordSnooze(taskId, rawSpec, targetEpochMs, 99)
                ReminderScheduler(context).scheduleRaw(
                    reminderId = reminderId,
                    taskStableId = taskId,
                    rawSpec = rawSpec,
                    taskDescription = taskTitle,
                    typeCode = remType,
                    triggerEpochMs = targetEpochMs
                )
                nm.cancel(notifId)
                val fmtTime = targetZdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                context.sendBroadcast(
                    Intent(ReminderScheduler.ACTION_TASKS_UPDATED).apply {
                        setPackage(context.packageName)
                        putExtra("extra_status_msg", "snoozed +$preset -> $fmtTime")
                    }
                )
            }

            ReminderScheduler.ACTION_MARK_DONE -> {
                snoozeStore.acknowledgeAndDismiss(taskId, rawSpec)
                val currentTasks = repo.loadTasks()
                val updated = mutableListOf<com.ticktxt.app.core.todotxt.TodoTask>()
                for (t in currentTasks) {
                    if (!t.isCompleted && (t.stableId == taskId || t.description.equals(taskTitle, ignoreCase = true))) {
                        val (doneTask, nextRec) = TodoParser.markCompleted(t, LocalDate.now())
                        updated.add(doneTask)
                        if (nextRec != null) updated.add(nextRec)
                    } else {
                        updated.add(t)
                    }
                }
                repo.saveTasks(updated)
                nm.cancel(notifId)
                context.sendBroadcast(
                    Intent(ReminderScheduler.ACTION_TASKS_UPDATED).apply {
                        setPackage(context.packageName)
                        putExtra("extra_status_msg", "[x] completed: $taskTitle")
                    }
                )
            }

            ReminderScheduler.ACTION_DISMISS_REMINDER -> {
                snoozeStore.acknowledgeAndDismiss(taskId, rawSpec)
                nm.cancel(notifId)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "tick_ascii_alarm_channel_v3"

        fun ensureChannel(
            context: Context,
            nm: NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ) {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID,
                "tick.txt Full-Screen Alarms & Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full-screen plain-text alarms and task notifications from tick.txt"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
                setSound(alarmUri, audioAttrs)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
