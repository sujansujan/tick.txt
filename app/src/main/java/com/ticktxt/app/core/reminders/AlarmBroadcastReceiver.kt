package com.ticktxt.app.core.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles firing notifications/full-screen alarms, granular quick-snooze buttons
 * ("5m", "15m", "1h", "tomorrow_morning"), dismiss, and direct "mark done" from notification.
 */
class AlarmBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReminderScheduler.ACTION_FIRE_REMINDER -> {
                // Post high-priority notification or launch FullScreenAlarmActivity
            }
            ReminderScheduler.ACTION_SNOOZE_REMINDER -> {
                // Record snooze in .tick/alarm-state.json without touching due: in todo.txt
            }
            ReminderScheduler.ACTION_DISMISS_REMINDER -> {
                // Mark acknowledged in .tick/alarm-state.json
            }
            ReminderScheduler.ACTION_MARK_DONE -> {
                // Atomically mark task `x YYYY-MM-DD` in todo.txt, spawn next `rec:` if present, and cancel alarms
            }
        }
    }
}
