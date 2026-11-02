package com.ticktxt.app.core.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.ticktxt.app.core.todotxt.TodoParser
import com.ticktxt.app.core.todotxt.TodoTask
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

data class CalendarAccountInfo(
    val id: Long,
    val displayName: String,
    val accountName: String
)

/**
 * Syncs `todo.txt` tasks with Google Calendar on Android via `CalendarContract`.
 * Stores `gcal:<eventId>` as a standard `key:value` tag on the task so updates
 * and completions stay linked without creating duplicate calendar events.
 */
class GoogleCalendarSyncManager(private val context: Context) {

    fun hasCalendarPermissions(): Boolean {
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        return read && write
    }

    /**
     * Finds the user's primary writable Google Calendar (or falls back to any writable device calendar).
     */
    fun findPrimaryGoogleCalendar(): CalendarAccountInfo? {
        if (!hasCalendarPermissions()) return null
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )

        val candidates = mutableListOf< Pair<CalendarAccountInfo, Int> >()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.VISIBLE} = 1",
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val nameCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
                val typeCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_TYPE)
                val primCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.IS_PRIMARY)
                val accessCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)

                while (cursor.moveToNext()) {
                    val access = cursor.getInt(accessCol)
                    if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                    val id = cursor.getLong(idCol)
                    val disp = cursor.getString(nameCol) ?: "Calendar"
                    val acc = cursor.getString(accCol) ?: "local"
                    val accType = cursor.getString(typeCol) ?: ""
                    val isPrim = cursor.getInt(primCol) == 1

                    var score = 0
                    if (accType.contains("com.google", ignoreCase = true)) score += 10
                    if (isPrim) score += 5
                    if (acc.contains("@gmail.com", ignoreCase = true)) score += 3
                    candidates.add(CalendarAccountInfo(id, disp, acc) to score)
                }
            }
        }
        return candidates.maxByOrNull { it.second }?.first
    }

    /**
     * Inserts or updates a task in the user's Google Calendar and returns the updated `TodoTask`
     * with `gcal:<eventId>` attached.
     */
    fun syncTaskToGoogleCalendar(task: TodoTask): Pair<TodoTask, Boolean> {
        val dueDate = task.dueDate ?: return task to false
        if (!hasCalendarPermissions()) return task to false

        val calInfo = findPrimaryGoogleCalendar() ?: return task to false
        val dueTime = task.dueTime ?: LocalTime.of(9, 0)
        val zone = ZoneId.systemDefault()
        val startZdt = LocalDateTime.of(dueDate, dueTime).atZone(zone)
        val durationMins = task.getTagValue("dur")?.removeSuffix("m")?.toLongOrNull() ?: 30L
        val endZdt = startZdt.plusMinutes(durationMins)

        val startMillis = startZdt.toInstant().toEpochMilli()
        val endMillis = endZdt.toInstant().toEpochMilli()

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calInfo.id)
            put(
                CalendarContract.Events.TITLE,
                if (task.isCompleted) "[x] ${task.description}" else task.description
            )
            put(
                CalendarContract.Events.DESCRIPTION,
                buildString {
                    append("tick.txt task\n")
                    if (task.projects.isNotEmpty()) append("Projects: ${task.projects.joinToString(" ") { "+$it" }}\n")
                    if (task.contexts.isNotEmpty()) append("Contexts: ${task.contexts.joinToString(" ") { "@$it" }}\n")
                    append("Raw line: ${TodoParser.serializeLine(task)}")
                }
            )
            if (task.contexts.isNotEmpty()) {
                put(CalendarContract.Events.EVENT_LOCATION, task.contexts.first())
            }
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        val existingEventId = task.getTagValue("gcal")?.toLongOrNull()
        return try {
            if (existingEventId != null) {
                val updateUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existingEventId)
                val rows = context.contentResolver.update(values, updateUri)
                if (rows > 0) {
                    return task to true
                }
            }

            val insertedUri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val newId = insertedUri?.lastPathSegment?.toLongOrNull() ?: return task to false

            // Add a 10-minute calendar reminder on the event
            val remValues = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, newId)
                put(CalendarContract.Reminders.MINUTES, 10)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            runCatching { context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, remValues) }

            val updatedTask = TodoParser.withUpdatedTag(task, "gcal", newId.toString())
            updatedTask to true
        } catch (_: Exception) {
            task to false
        }
    }

    /**
     * Syncs all tasks that have a `due:` date to Google Calendar.
     */
    fun syncAllDueTasks(tasks: List<TodoTask>): Pair<List<TodoTask>, Int> {
        if (!hasCalendarPermissions()) return tasks to 0
        var syncedCount = 0
        val updatedList = tasks.map { task ->
            if (!task.isBlankOrComment && task.dueDate != null) {
                val (syncedTask, ok) = syncTaskToGoogleCalendar(task)
                if (ok) syncedCount++
                syncedTask
            } else {
                task
            }
        }
        return updatedList to syncedCount
    }

    /**
     * Fallback intent that opens the official Google Calendar app pre-populated with this task's details.
     */
    fun openInGoogleCalendarApp(task: TodoTask) {
        val dueDate = task.dueDate ?: java.time.LocalDate.now()
        val dueTime = task.dueTime ?: LocalTime.of(9, 0)
        val zone = ZoneId.systemDefault()
        val startMillis = LocalDateTime.of(dueDate, dueTime).atZone(zone).toInstant().toEpochMilli()
        val endMillis = startMillis + 30 * 60_000L

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, task.description)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            if (task.contexts.isNotEmpty()) {
                putExtra(CalendarContract.Events.EVENT_LOCATION, task.contexts.first())
            }
            putExtra(
                CalendarContract.Events.DESCRIPTION,
                "Synced from tick.txt: ${TodoParser.serializeLine(task)}"
            )
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { context.startActivity(intent) }
    }

    private fun android.content.ContentResolver.update(values: ContentValues, uri: android.net.Uri): Int {
        return this.update(uri, values, null, null)
    }
}
