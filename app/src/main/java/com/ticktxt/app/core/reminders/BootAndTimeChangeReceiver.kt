package com.ticktxt.app.core.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ticktxt.app.core.storage.TickStorageRepository
import java.time.ZonedDateTime

/**
 * Listens for:
 * - ACTION_BOOT_COMPLETED / ACTION_LOCKED_BOOT_COMPLETED
 * - ACTION_MY_PACKAGE_REPLACED (app update)
 * - ACTION_TIMEZONE_CHANGED / ACTION_TIME_CHANGED ("android.intent.action.TIME_SET")
 *
 * Reloads `todo.txt` and reschedules all pending alarms and reminders immediately.
 */
class BootAndTimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            "android.intent.action.TIME_SET"
        )
        if (intent.action in validActions) {
            AlarmResyncWorker.enqueueImmediateResync(context, reason = intent.action ?: "boot")
        }
    }
}

object AlarmResyncWorker {
    fun enqueueImmediateResync(context: Context, @Suppress("UNUSED_PARAMETER") reason: String) {
        runCatching {
            val repo = TickStorageRepository(context)
            val tasks = repo.loadTasks()
            ReminderScheduler(context).syncTaskSchedules(tasks, ZonedDateTime.now(), forceAll = true)
        }
    }
}
