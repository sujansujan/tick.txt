package com.ticktxt.app.core.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ticktxt.app.core.nlp.TickRulesConfig
import com.ticktxt.app.core.notes.NoteFile
import com.ticktxt.app.core.notes.NotesRepository
import com.ticktxt.app.core.todotxt.TodoParser
import com.ticktxt.app.core.todotxt.TodoTask
import com.ticktxt.app.ui.theme.FontMode
import com.ticktxt.app.ui.theme.ThemeMode
import java.io.File

data class PersistedSettings(
    val themeMode: ThemeMode = ThemeMode.DRACULA_DARK,
    val fontMode: FontMode = FontMode.MONOSPACE,
    val fontSizeSp: Int = 16,
    val autoSyncGoogleCalendar: Boolean = true,
    val defaultTimedReminder: String = "0m/n",
    val defaultDateOnlyReminder: String = "09:00/n",
    val customSafUri: String? = null
)

/**
 * Single source-of-truth disk manager for `todo.txt`, `done.txt`, `tick.rules.txt`,
 * `notes/`, and `SharedPreferences` settings.
 * Ensures every edit is immediately written to disk and survives app close / reboot.
 */
class TickStorageRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val baseDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "tick").apply { mkdirs() }

    val todoFile: File get() = File(baseDir, "todo.txt")
    val doneFile: File get() = File(baseDir, "done.txt")
    val rulesFile: File get() = File(baseDir, "tick.rules.txt")
    val notesDir: File get() = File(baseDir, "notes").apply { mkdirs() }
    private val notesRepo: NotesRepository get() = NotesRepository(notesDir)

    fun ensureInitialized() {
        if (!todoFile.exists()) {
            val initialTodo = listOf(
                "2026-11-02 check chocotat +chocotat @factory due:2026-11-02 duetime:09:00 rem:0m/n",
                "(A) 2026-11-02 ship quarterly invoice +invoice @office due:2026-11-02 duetime:17:00 rem:0m/a/svr",
                "2026-11-02 review architecture notes +architecture @desk note:architecture.md"
            ).joinToString("\n") + "\n"
            AtomicFileWriter.writeAtomically(todoFile, initialTodo)
        }
        if (!doneFile.exists()) {
            AtomicFileWriter.writeAtomically(
                doneFile,
                "x 2026-11-01 2026-10-30 set up plain text folder on android +ops @phone\n"
            )
        }
        if (!rulesFile.exists()) {
            AtomicFileWriter.writeAtomically(rulesFile, TickRulesConfig.DEFAULT_RULES_FILE_CONTENT)
        }
        if (notesDir.listFiles().isNullOrEmpty()) {
            notesRepo.saveNote(
                "architecture.md",
                "# tick.txt storage notes\n\n- todo.txt and done.txt follow the official spec.\n- tick.rules.txt holds smart-add rules.\n- .tick/alarm-state.json stores active snoozes.",
                listOf("2026-11-04@09:00/n")
            )
            notesRepo.saveNote(
                "packing-list.txt",
                "passport\nusb-c cable\nnotebook and pen",
                listOf("2026-11-05@07:30/a/svr")
            )
        }
    }

    fun loadTasks(): List<TodoTask> {
        ensureInitialized()
        val safContent = readFromSafFile("todo.txt")
        val raw = safContent ?: runCatching { todoFile.readText(Charsets.UTF_8) }.getOrDefault("")
        if (safContent != null) {
            runCatching { AtomicFileWriter.writeAtomically(todoFile, safContent) }
        }
        return TodoParser.parseFile(raw)
    }

    fun saveTasks(tasks: List<TodoTask>) {
        val numbered = tasks.mapIndexed { index, task -> task.copy(lineNumber = index + 1) }
        val serialized = TodoParser.serializeFile(numbered)
        AtomicFileWriter.writeAtomically(todoFile, serialized)
        writeToSafFile("todo.txt", "text/plain", serialized)
    }

    fun loadDoneLines(): List<String> {
        ensureInitialized()
        val safContent = readFromSafFile("done.txt")
        val raw = safContent ?: runCatching { doneFile.readText(Charsets.UTF_8) }.getOrDefault("")
        return raw.lines().filter { it.isNotBlank() }
    }

    fun saveDoneLines(lines: List<String>) {
        val content = if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
        AtomicFileWriter.writeAtomically(doneFile, content)
        writeToSafFile("done.txt", "text/plain", content)
    }

    fun loadRulesText(): String {
        ensureInitialized()
        val safContent = readFromSafFile("tick.rules.txt")
        return safContent ?: runCatching { rulesFile.readText(Charsets.UTF_8) }
            .getOrDefault(TickRulesConfig.DEFAULT_RULES_FILE_CONTENT)
    }

    fun saveRulesText(content: String) {
        AtomicFileWriter.writeAtomically(rulesFile, content)
        writeToSafFile("tick.rules.txt", "text/plain", content)
    }

    fun loadNotes(): List<NoteFile> {
        ensureInitialized()
        return notesRepo.listNotes()
    }

    fun saveNote(filename: String, rawContent: String): List<NoteFile> {
        val safeName = NotesRepository.toHumanReadableFilename(filename)
        val target = File(notesDir, safeName)
        val normalized = rawContent.trimEnd() + "\n"
        AtomicFileWriter.writeAtomically(target, normalized)
        return notesRepo.listNotes()
    }

    fun deleteNote(filename: String): List<NoteFile> {
        val target = File(notesDir, filename)
        if (target.exists()) target.delete()
        return notesRepo.listNotes()
    }

    fun loadSettings(): PersistedSettings {
        val themeStr = prefs.getString(KEY_THEME, ThemeMode.DRACULA_DARK.name) ?: ThemeMode.DRACULA_DARK.name
        val fontStr = prefs.getString(KEY_FONT_MODE, FontMode.MONOSPACE.name) ?: FontMode.MONOSPACE.name
        val sizeSp = prefs.getInt(KEY_FONT_SIZE, 16)
        val autoSyncCal = prefs.getBoolean(KEY_AUTO_GCAL, true)
        val timedRem = prefs.getString(KEY_TIMED_REM, "0m/n") ?: "0m/n"
        val dateOnlyRem = prefs.getString(KEY_DATE_REM, "09:00/n") ?: "09:00/n"
        val safUri = prefs.getString(KEY_SAF_URI, null)

        return PersistedSettings(
            themeMode = runCatching { ThemeMode.valueOf(themeStr) }.getOrDefault(ThemeMode.DRACULA_DARK),
            fontMode = runCatching { FontMode.valueOf(fontStr) }.getOrDefault(FontMode.MONOSPACE),
            fontSizeSp = sizeSp,
            autoSyncGoogleCalendar = autoSyncCal,
            defaultTimedReminder = timedRem,
            defaultDateOnlyReminder = dateOnlyRem,
            customSafUri = safUri
        )
    }

    fun saveSettings(settings: PersistedSettings) {
        prefs.edit()
            .putString(KEY_THEME, settings.themeMode.name)
            .putString(KEY_FONT_MODE, settings.fontMode.name)
            .putInt(KEY_FONT_SIZE, settings.fontSizeSp)
            .putBoolean(KEY_AUTO_GCAL, settings.autoSyncGoogleCalendar)
            .putString(KEY_TIMED_REM, settings.defaultTimedReminder)
            .putString(KEY_DATE_REM, settings.defaultDateOnlyReminder)
            .putString(KEY_SAF_URI, settings.customSafUri)
            .apply()
    }

    private fun readFromSafFile(filename: String): String? {
        val uriStr = prefs.getString(KEY_SAF_URI, null) ?: return null
        return runCatching {
            val treeUri = Uri.parse(uriStr)
            val dir = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            val doc = dir.findFile(filename) ?: return null
            context.contentResolver.openInputStream(doc.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrNull()
    }

    private fun writeToSafFile(filename: String, mimeType: String, content: String) {
        val uriStr = prefs.getString(KEY_SAF_URI, null) ?: return
        runCatching {
            val treeUri = Uri.parse(uriStr)
            val dir = DocumentFile.fromTreeUri(context, treeUri) ?: return
            val doc = dir.findFile(filename) ?: dir.createFile(mimeType, filename) ?: return
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
                os.flush()
            }
        }
    }

    companion object {
        const val PREFS_NAME = "tick_txt_preferences"
        private const val KEY_THEME = "pref_theme_mode"
        private const val KEY_FONT_MODE = "pref_font_mode"
        private const val KEY_FONT_SIZE = "pref_font_size_sp"
        private const val KEY_TIMED_REM = "pref_timed_rem"
        private const val KEY_DATE_REM = "pref_date_rem"
        private const val KEY_SAF_URI = "pref_saf_uri"
        private const val KEY_AUTO_GCAL = "pref_auto_gcal"
    }
}
