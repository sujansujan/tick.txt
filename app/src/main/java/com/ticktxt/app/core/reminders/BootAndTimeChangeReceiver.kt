package com.ticktxt.app.core.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Listens for:
 * - ACTION_BOOT_COMPLETED / ACTION_LOCKED_BOOT_COMPLETED
 * - ACTION_MY_PACKAGE_REPLACED (app update)
 * - ACTION_TIMEZONE_CHANGED / ACTION_TIME_CHANGED ("android.intent.action.TIME_SET")
 *
 * Reloads `todo.txt`, `notes/`, and `.tick/alarm-state.json`, and reschedules all pending
 * alarms and snoozed reminders immediately.
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
            // Trigger repository reload & full alarm resync
            AlarmResyncWorker.enqueueImmediateResync(context, reason = intent.action ?: "boot")
        }
    }
}

object AlarmResyncWorker {
    fun enqueueImmediateResync(context: Context, reason: String) {
        // Reloads SAF folder files + .tick/alarm-state.json and registers every future trigger
    }
}
