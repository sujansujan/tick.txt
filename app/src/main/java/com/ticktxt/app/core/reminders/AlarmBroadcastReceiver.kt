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
import com.ticktxt.app.ui.screens.AlarmAudioController
import com.ticktxt.app.ui.screens.FullScreenAlarmActivity
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Pure Full-Screen + Persistent Shade Notification Receiver:
 * - No continuous Alarm siren mode—every reminder is a Notification.
 * - When a reminder triggers, it posts a notification in the system shade (`setAutoCancel(false)`)
 *   AND pops up `FullScreenAlarmActivity` in front of any app.
 * - If the user marks `[✓ Done]` or `[zZ Snooze]`, the notification is removed from the shade.
 * - If the user dismisses the full-screen window (`[X Dismiss]`) without Done or Snooze,
 *   the notification remains in the notification shade until dismissed from there as well.
 */
class AlarmBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(context, nm)

        val reminderId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: "default_id"
        val taskId = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_ID) ?: "task_id"
        val rawSpec = intent.getStringExtra(ReminderScheduler.EXTRA_RAW_SPEC) ?: "0m/n"
        val taskTitle = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_TITLE) ?: "tick.txt task"
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
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    putExtra(ReminderScheduler.EXTRA_REMINDER_ID, reminderId)
                    putExtra(ReminderScheduler.EXTRA_TASK_ID, taskId)
                    putExtra(ReminderScheduler.EXTRA_RAW_SPEC, rawSpec)
                    putExtra(ReminderScheduler.EXTRA_TASK_TITLE, taskTitle)
                    putExtra(ReminderScheduler.EXTRA_REMINDER_TYPE, "n")
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
                        putExtra(ReminderScheduler.EXTRA_REMINDER_TYPE, "n")
                        putExtra("extra_notif_id", notifId)
                        if (preset != null) putExtra(ReminderScheduler.EXTRA_SNOOZE_PRESET, preset)
                    }
                    return PendingIntent.getBroadcast(
                        context,
                        notifId + reqOffset,
                        aIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                }

                val cleanSpec = rawSpec.substringBefore("/")
                val asciiBigText = buildString {
                    appendLine("+--[NOTIFICATION]----------------------+")
                    appendLine("| [ ] ${taskTitle.take(32).padEnd(32)} |")
                    appendLine("| Spec: rem:${cleanSpec.take(26).padEnd(26)} |")
                    append("+--------------------------------------+")
                }

                // Stays in the notification shade (`setAutoCancel(false)`) until Done, Snooze,
                // or explicitly swiped away from the shade (`setDeleteIntent`).
                val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle("[NOTIFICATION] $taskTitle")
                    .setContentText("rem:$cleanSpec • Tap to open or use actions below")
                    .setStyle(NotificationCompat.BigTextStyle().bigText(asciiBigText))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setAutoCancel(false)
                    .setContentIntent(fullScreenPendingIntent)
                    .setFullScreenIntent(fullScreenPendingIntent, true)
                    .setDeleteIntent(buildActionIntent(ReminderScheduler.ACTION_DISMISS_REMINDER, null, 9))
                    .addAction(
                        0,
                        "[✓ Done]",
                        buildActionIntent(ReminderScheduler.ACTION_MARK_DONE, null, 1)
                    )
                    .addAction(
                        0,
                        "[zZ +10m]",
                        buildActionIntent(ReminderScheduler.ACTION_SNOOZE_REMINDER, "10m", 2)
                    )
                    .addAction(
                        0,
                        "[zZ Snooze...]",
                        fullScreenPendingIntent
                    )

                nm.notify(notifId, builder.build())

                // Launch FullScreenAlarmActivity directly in front as well
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
                    }
                )
            }

            ReminderScheduler.ACTION_SNOOZE_REMINDER -> {
                AlarmAudioController.stopAll()
                val preset = intent.getStringExtra(ReminderScheduler.EXTRA_SNOOZE_PRESET) ?: "10m"
                val targetZdt = ReminderScheduleCalculator.computeSnoozeTarget(ZonedDateTime.now(), preset)
                val targetEpochMs = targetZdt.toInstant().toEpochMilli()
                snoozeStore.recordSnooze(taskId, rawSpec, targetEpochMs, 99)
                ReminderScheduler(context).scheduleRaw(
                    reminderId = reminderId,
                    taskStableId = taskId,
                    rawSpec = rawSpec,
                    taskDescription = taskTitle,
                    typeCode = "n",
                    triggerEpochMs = targetEpochMs
                )
                // Snoozing clears the notification from the shade
                nm.cancel(notifId)
                val fmtTime = targetZdt.format(DateTimeFormatter.ofPattern("HH:mm"))
                context.sendBroadcast(
                    Intent(ReminderScheduler.ACTION_TASKS_UPDATED).apply {
                        setPackage(context.packageName)
                        putExtra("extra_status_msg", "[zZ Snooze] +$preset ($fmtTime)")
                    }
                )
            }

            ReminderScheduler.ACTION_MARK_DONE -> {
                AlarmAudioController.stopAll()
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
                ReminderScheduler(context).syncTaskSchedules(updated, ZonedDateTime.now())
                // Completing clears the notification from the shade
                nm.cancel(notifId)
                context.sendBroadcast(
                    Intent(ReminderScheduler.ACTION_TASKS_UPDATED).apply {
                        setPackage(context.packageName)
                        putExtra("extra_status_msg", "[✓ Done] $taskTitle")
                    }
                )
            }

            ReminderScheduler.ACTION_DISMISS_REMINDER -> {
                // Triggered when the user explicitly swipes away the notification from the shade
                AlarmAudioController.stopAll()
                snoozeStore.acknowledgeAndDismiss(taskId, rawSpec)
                nm.cancel(notifId)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "tick_fullscreen_notif_channel_v5"

        fun ensureChannel(
            context: Context,
            nm: NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ) {
            val notifUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val notifAudioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID,
                "tick.txt Task Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full-screen + notification shade task reminders"
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
                setSound(notifUri, notifAudioAttrs)
            }

            nm.createNotificationChannel(channel)
        }
    }
}
