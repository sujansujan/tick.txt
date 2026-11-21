package com.ticktxt.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.widget.RemoteViews
import com.ticktxt.app.MainActivity
import com.ticktxt.app.R
import com.ticktxt.app.core.storage.TickStorageRepository
import com.ticktxt.app.ui.theme.ThemeMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Lightweight, zero-dependency pure-ASCII home-screen widget styled in the user's
 * active Dracula / AMOLED / Alucard palette, with 1-tap `[+ Add Task]` capture.
 */
class TickAsciiWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_WIDGET) {
            refreshAllWidgets(context)
        }
    }

    private data class WidgetColors(
        val bg: Int,
        val fg: Int,
        val muted: Int,
        val project: Int,
        val add: Int,
        val sync: Int
    )

    companion object {
        const val ACTION_REFRESH_WIDGET = "com.ticktxt.app.ACTION_REFRESH_WIDGET"

        fun refreshAllWidgets(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, TickAsciiWidgetProvider::class.java))
                for (id in ids) {
                    updateSingleWidget(context, manager, id)
                }
            }
        }

        private fun updateSingleWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val repo = TickStorageRepository(context)
            val settings = repo.loadSettings()
            val today = LocalDate.now()

            val isSysDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val effectiveTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> if (isSysDark) ThemeMode.DRACULA_DARK else ThemeMode.ALUCARD_LIGHT
                else -> settings.themeMode
            }

            val colors = when (effectiveTheme) {
                ThemeMode.AMOLED_BLACK -> WidgetColors(
                    bg = Color.parseColor("#000000"),
                    fg = Color.parseColor("#EBEBF0"),
                    muted = Color.parseColor("#6E7594"),
                    project = Color.parseColor("#BD93F9"),
                    add = Color.parseColor("#50FA7B"),
                    sync = Color.parseColor("#8BE9FD")
                )
                ThemeMode.ALUCARD_LIGHT -> WidgetColors(
                    bg = Color.parseColor("#FFFBEB"),
                    fg = Color.parseColor("#1F1F1F"),
                    muted = Color.parseColor("#595649"),
                    project = Color.parseColor("#644AC9"),
                    add = Color.parseColor("#14710A"),
                    sync = Color.parseColor("#036A96")
                )
                else -> WidgetColors(
                    bg = Color.parseColor("#282A36"),
                    fg = Color.parseColor("#F8F8F2"),
                    muted = Color.parseColor("#8F96BA"),
                    project = Color.parseColor("#BD93F9"),
                    add = Color.parseColor("#50FA7B"),
                    sync = Color.parseColor("#8BE9FD")
                )
            }
            val bgColor = colors.bg
            val fgColor = colors.fg
            val mutedColor = colors.muted
            val accentProject = colors.project
            val accentAdd = colors.add
            val accentSync = colors.sync

            val openTasks = runCatching { repo.loadTasks() }.getOrDefault(emptyList())
                .filter {
                    !it.isBlankOrComment && !it.isCompleted &&
                        (it.thresholdDate == null || !it.thresholdDate!!.isAfter(today))
                }
                .sortedWith(
                    compareBy<com.ticktxt.app.core.todotxt.TodoTask> { it.priority ?: '[' }
                        .thenBy { it.dueDate ?: LocalDate.MAX }
                        .thenBy { it.dueTime ?: LocalTime.MAX }
                        .thenBy { it.lineNumber }
                )

            val shortDateFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
            val bodyText = if (openTasks.isEmpty()) {
                "| ALL CLEAR -- plain text is clean."
            } else {
                openTasks.take(5).joinToString("\n") { task ->
                    val pri = task.priority?.let { "($it) " } ?: ""
                    val dueTag = when {
                        task.dueDate == null -> ""
                        task.dueDate == today && task.dueTime != null ->
                            " [${"%02d:%02d".format(task.dueTime!!.hour, task.dueTime!!.minute)}]"
                        task.dueDate == today -> " [today]"
                        task.dueDate!!.isBefore(today) -> " [!late]"
                        else -> " [${task.dueDate!!.format(shortDateFmt)}]"
                    }
                    "| [ ] $pri${task.description.take(28)}$dueTag"
                }
            }

            val views = RemoteViews(context.packageName, R.layout.widget_tick_ascii)
            views.setInt(R.id.widget_root, "setBackgroundColor", bgColor)
            views.setTextColor(R.id.widget_header_left, accentProject)
            views.setTextColor(R.id.widget_header_right, mutedColor)
            views.setTextColor(R.id.widget_tasks_body, fgColor)
            views.setTextColor(R.id.widget_btn_add, accentAdd)
            views.setTextColor(R.id.widget_btn_refresh, accentSync)

            views.setTextViewText(R.id.widget_header_right, "[${openTasks.size} open]--+")
            views.setTextViewText(R.id.widget_tasks_body, bodyText)

            // Tapping the body opens the main app
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openAppPi = PendingIntent.getActivity(
                context,
                widgetId * 10 + 1,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_tasks_body, openAppPi)
            views.setOnClickPendingIntent(R.id.widget_header_left, openAppPi)

            // Tapping [+ Add Task] jumps directly into Smart Add
            val quickAddIntent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_QUICK_ADD
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val quickAddPi = PendingIntent.getActivity(
                context,
                widgetId * 10 + 2,
                quickAddIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_add, quickAddPi)

            // Tapping [~ Sync] refreshes widget from todo.txt / SAF
            val refreshIntent = Intent(context, TickAsciiWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_WIDGET
            }
            val refreshPi = PendingIntent.getBroadcast(
                context,
                widgetId * 10 + 3,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_refresh, refreshPi)

            manager.updateAppWidget(widgetId, views)
        }
    }
}
