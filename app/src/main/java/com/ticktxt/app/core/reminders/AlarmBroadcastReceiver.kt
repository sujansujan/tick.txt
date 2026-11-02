package com.ticktxt.app.core.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.ticktxt.app.MainActivity
import com.ticktxt.app.core.storage.TickStorageRepository
import com.ticktxt.app.core.todotxt.TodoParser
import com.ticktxt.app.ui.screens.FullScreenAlarmActivity
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * First-class Android NotificationCompat receiver:
 * - Creates high-importance notification channel with sound, vibration, and lockscreen visibility
 * - Provides quick-snooze buttons (+5m, +15m) and a working "Mark Done" button that
 *   updates `todo.txt` on disk and notifies the UI.
 */
class AlarmBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(context, nm)

        val reminderId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: "default_id"
        val taskId = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_ID) ?: "task_id"
        val rawSpec = intent.getStringExtra(ReminderScheduler.EXTRA_RAW_SPEC) ?: "0m/n"
        val taskTitle = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_TITLE) ?: "tick.txt reminder"
        val remType = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_TYPE) ?: "n"
        val notifId = (reminderId.hashCode() and 0x7FFFFFFF).coerceAtLeast(100)

        val repo = TickStorageRepository(context)
        val tickDir = File(repo.baseDir, ".tick").apply { mkdirs() }
        val snoozeStore = SnoozeStateStore(File(tickDir, "alarm-state.json"))

        when (intent.action) {
            ReminderScheduler.ACTION_FIRE_REMINDER -> {
                snoozeStore.recordFired(taskId, rawSpec, System.currentTimeMillis())

                val openAppIntent = PendingIntent.getActivity(
                    context,
                    notifId,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val fullScreenPendingIntent = PendingIntent.getActivity(
                    context,
                    notifId + 50,
                    Intent(context, FullScreenAlarmActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("extra_task_title", taskTitle)
                        putExtra("extra_raw_spec", rawSpec)
                    },
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

                val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(taskTitle)
                    .setContentText("rem:$rawSpec • tap to open or use actions below")
                    .setStyle(NotificationCompat.BigTextStyle().bigText("$taskTitle\nreminder: rem:$rawSpec"))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(if (remType == "a") NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setContentIntent(openAppIntent)
                    .setAutoCancel(true)
                    .addAction(
                        0,
                        "Mark Done",
                        buildActionIntent(ReminderScheduler.ACTION_MARK_DONE, null, 1)
                    )
                    .addAction(
                        0,
                        "+5m",
                        buildActionIntent(ReminderScheduler.ACTION_SNOOZE_REMINDER, "5m", 2)
                    )
                    .addAction(
                        0,
                        "+15m",
                        buildActionIntent(ReminderScheduler.ACTION_SNOOZE_REMINDER, "15m", 3)
                    )

                if (remType == "a") {
                    builder.setFullScreenIntent(fullScreenPendingIntent, true)
                    runCatching {
                        context.startActivity(
                            Intent(context, FullScreenAlarmActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra("extra_task_title", taskTitle)
                                putExtra("extra_raw_spec", rawSpec)
                            }
                        )
                    }
                }

                nm.notify(notifId, builder.build())
            }

            ReminderScheduler.ACTION_SNOOZE_REMINDER -> {
                val preset = intent.getStringExtra(ReminderScheduler.EXTRA_SNOOZE_PRESET) ?: "5m"
                val targetZdt = ReminderScheduleCalculator.computeSnoozeTarget(ZonedDateTime.now(), preset)
                snoozeStore.recordSnooze(taskId, rawSpec, targetZdt.toInstant().toEpochMilli(), 10)
                ReminderScheduler(context).scheduleRaw(
                    reminderId = reminderId,
                    taskStableId = taskId,
                    rawSpec = rawSpec,
                    taskDescription = taskTitle,
                    typeCode = remType,
                    triggerEpochMs = targetZdt.toInstant().toEpochMilli()
                )
                nm.cancel(notifId)
            }

            ReminderScheduler.ACTION_MARK_DONE -> {
                snoozeStore.acknowledgeAndDismiss(taskId, rawSpec)
                // Mark the matching task completed in todo.txt on disk
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
                context.sendBroadcast(Intent(ReminderScheduler.ACTION_TASKS_UPDATED).setPackage(context.packageName))
            }

            ReminderScheduler.ACTION_DISMISS_REMINDER -> {
                snoozeStore.acknowledgeAndDismiss(taskId, rawSpec)
                nm.cancel(notifId)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "tick_reminders_high_v2"

        fun ensureChannel(context: Context, nm: NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "tick.txt Reminders & Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Exact task and note reminders from tick.txt"
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    android.app.Notification.AUDIO_ATTRIBUTES_DEFAULT
                )
            }
            nm.createNotificationChannel(channel)
        }
    }
}
