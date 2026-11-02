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
import com.ticktxt.app.core.storage.TickStorageRepository
import com.ticktxt.app.core.todotxt.TodoParser
import com.ticktxt.app.ui.screens.FullScreenAlarmActivity
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Coordinates system notification audio vs. full-screen alarm audio:
 * - Default (`alarmMutedByDefault == true`): Alarm siren is muted; the notification uses
 *   `CHANNEL_NOTIF_SOUND` so only the standard system notification sound plays.
 * - Unmuted (`alarmMutedByDefault == false`): System notification sound is silenced
 *   (`CHANNEL_SILENT_NOTIF` + `setSilent(true)`), and `FullScreenAlarmActivity` plays the
 *   loud alarm ringtone (`TYPE_ALARM`).
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
        val settings = repo.loadSettings()
        val isAlarmMuted = settings.alarmMutedByDefault
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
                    putExtra("extra_alarm_muted", isAlarmMuted)
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

                // When alarm is muted (default), play system notification sound;
                // when alarm is NOT muted, silence the system notification sound!
                val channelToUse = if (isAlarmMuted) CHANNEL_ID else CHANNEL_SILENT_ID
                val audioModeLabel = if (isAlarmMuted) "Muted (System Notification Sound)" else "Unmuted (System Notification Silenced)"

                val asciiBigText = buildString {
                    appendLine("+--------------------------------------+")
                    appendLine("| Task: ${taskTitle.take(30).padEnd(30)} |")
                    appendLine("| Spec: rem:${rawSpec.take(26).padEnd(26)} |")
                    appendLine("| Audio: ${audioModeLabel.take(29).padEnd(29)} |")
                    append("+--------------------------------------+")
                }

                val builder = NotificationCompat.Builder(context, channelToUse)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(taskTitle)
                    .setContentText("rem:$rawSpec • Tap to Snooze or Complete")
                    .setStyle(NotificationCompat.BigTextStyle().bigText(asciiBigText))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setSilent(!isAlarmMuted)
                    .setContentIntent(fullScreenPendingIntent)
                    .setFullScreenIntent(fullScreenPendingIntent, true)
                    .setAutoCancel(true)
                    .addAction(
                        0,
                        "Complete",
                        buildActionIntent(ReminderScheduler.ACTION_MARK_DONE, null, 1)
                    )
                    .addAction(
                        0,
                        "Snooze +5m",
                        buildActionIntent(ReminderScheduler.ACTION_SNOOZE_REMINDER, "5m", 2)
                    )
                    .addAction(
                        0,
                        "Snooze...",
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
                        putExtra("extra_notif_id", notifId)
                        putExtra("extra_alarm_muted", isAlarmMuted)
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
                val fmtTime = targetZdt.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                context.sendBroadcast(
                    Intent(ReminderScheduler.ACTION_TASKS_UPDATED).apply {
                        setPackage(context.packageName)
                        putExtra("extra_status_msg", "Snoozed +$preset (until $fmtTime)")
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
                        putExtra("extra_status_msg", "Completed: $taskTitle")
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
        const val CHANNEL_ID = "tick_notif_sound_channel_v4"
        const val CHANNEL_SILENT_ID = "tick_silent_notif_channel_v4"

        fun ensureChannel(
            context: Context,
            nm: NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ) {
            val notifUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val notifAudioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val soundChannel = NotificationChannel(
                CHANNEL_ID,
                "tick.txt Reminders (System Notification Sound)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Plays standard system notification sound when alarm is in default muted mode"
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setSound(notifUri, notifAudioAttrs)
            }

            val silentChannel = NotificationChannel(
                CHANNEL_SILENT_ID,
                "tick.txt Reminders (Silent Notification for Loud Alarm)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Silences system notification sound when unmuted loud alarm is active"
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setSound(null, null)
            }

            nm.createNotificationChannel(soundChannel)
            nm.createNotificationChannel(silentChannel)
        }
    }
}
