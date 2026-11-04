package com.ticktxt.app.core.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
 * Full-Screen First Notification Receiver:
 * - When a reminder triggers (`ACTION_FIRE_REMINDER`), it plays the notification chime and
 *   opens `FullScreenAlarmActivity` directly in front WITHOUT cluttering the system notification tray.
 * - ONLY when the user dismisses the full-screen notification (`[X Dismiss]` or Back) without
 *   choosing Done or Snooze does `postTrayNotificationOnDismiss(...)` place the notification
 *   into the system notification shade, where it stays until dismissed or acted on from the shade.
 */
class AlarmBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(context, nm)

        val reminderId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: "default_id"
        val taskId = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_ID) ?: "task_id"
        val rawSpec = intent.getStringExtra(ReminderScheduler.EXTRA_RAW_SPEC) ?: "0m/n"
        val taskTitle = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_TITLE) ?: "tick.txt task"
        val notifId = intent.getIntExtra(
            "extra_notif_id",
            (reminderId.hashCode() and 0x7FFFFFFF).coerceAtLeast(100)
        )

        val repo = TickStorageRepository(context)
        val tickDir = File(repo.baseDir, ".tick").apply { mkdirs() }
        val snoozeStore = SnoozeStateStore(File(tickDir, "alarm-state.json"))

        when (intent.action) {
            ReminderScheduler.ACTION_FIRE_REMINDER -> {
                snoozeStore.recordFired(taskId, rawSpec, System.currentTimeMillis())

                // Make sure no tray notification is showing while the Full-Screen UI is active
                nm.cancel(notifId)
                AlarmAudioController.playSystemNotificationBeepOnce(context)

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

                val launchedActivity = runCatching {
                    context.startActivity(fullScreenIntent)
                    true
                }.getOrDefault(false)

                if (!launchedActivity) {
                    // Fallback if OS blocked background activity start: post to tray with fullScreenIntent
                    postTrayNotificationOnDismiss(
                        context = context,
                        reminderId = reminderId,
                        taskId = taskId,
                        rawSpec = rawSpec,
                        taskTitle = taskTitle,
                        notifId = notifId,
                        attachFullScreenIntent = true
                    )
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
                nm.cancel(notifId)
                context.sendBroadcast(
                    Intent(ReminderScheduler.ACTION_TASKS_UPDATED).apply {
                        setPackage(context.packageName)
                        putExtra("extra_status_msg", "[✓ Done] $taskTitle")
                    }
                )
            }

            ReminderScheduler.ACTION_DISMISS_REMINDER -> {
                // Triggered when the user explicitly swipes away the notification from the system shade
                AlarmAudioController.stopAll()
                snoozeStore.acknowledgeAndDismiss(taskId, rawSpec)
                nm.cancel(notifId)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "tick_tray_on_dismiss_channel_v6"

        fun ensureChannel(
            context: Context,
            nm: NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "tick.txt Dismissed Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Holds reminders in the notification shade after dismissing the full-screen notification"
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }

        /**
         * Posts the reminder into the system notification shade ONLY when the user dismisses
         * the Full-Screen Notification without marking Done or Snooze.
         * Stays in the shade (`setAutoCancel(false)`) until swiped away or acted on there.
         */
        fun postTrayNotificationOnDismiss(
            context: Context,
            reminderId: String,
            taskId: String,
            rawSpec: String,
            taskTitle: String,
            notifId: Int = (reminderId.hashCode() and 0x7FFFFFFF).coerceAtLeast(100),
            attachFullScreenIntent: Boolean = false
        ) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(context, nm)

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

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("[NOTIFICATION] $taskTitle")
                .setContentText("Tap to reopen full-screen or use actions below")
                .setStyle(NotificationCompat.BigTextStyle().bigText(asciiBigText))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSilent(true)
                .setAutoCancel(false)
                .setContentIntent(fullScreenPendingIntent)
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

            if (attachFullScreenIntent) {
                builder.setFullScreenIntent(fullScreenPendingIntent, true)
            }

            nm.notify(notifId, builder.build())
        }
    }
}
