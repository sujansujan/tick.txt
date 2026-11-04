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
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fontMode: FontMode = FontMode.MONOSPACE,
    val fontSizeSp: Int = 16,
    val autoSyncGoogleCalendar: Boolean = true,
    val defaultTimedReminder: String = "0m/n",
    val defaultDateOnlyReminder: String = "09:00/n",
    val customSafUri: String? = null
)

data class SafFolderLoadResult(
    val tasks: List<TodoTask>,
    val doneLines: List<String>,
    val rulesText: String,
    val notes: List<NoteFile>,
    val foundExistingData: Boolean
)

/**
 * Single source-of-truth disk manager for `todo.txt`, `done.txt`, `tick.rules.txt`,
 * Markdown `.md` notes in `notes/`, rolling `.tick/backups/` snapshots, and `SharedPreferences` settings.
 */
class TickStorageRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val baseDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "tick").apply { mkdirs() }

    val todoFile: File get() = File(baseDir, "todo.txt")
    val doneFile: File get() = File(baseDir, "done.txt")
    val rulesFile: File get() = File(baseDir, "tick.rules.txt")
    val notesDir: File get() = File(baseDir, "notes").apply { mkdirs() }
    val backupsDir: File get() = File(File(baseDir, ".tick"), "backups").apply { mkdirs() }
    private val notesRepo: NotesRepository get() = NotesRepository(notesDir)

    fun ensureInitialized() {
        val alreadySeeded = prefs.getBoolean(KEY_INITIAL_SEED_DONE, false)
        if (!todoFile.exists()) {
            val initialTodo = if (!alreadySeeded) {
                listOf(
                    "2026-11-02 check chocotat +chocotat @factory due:2026-11-02 duetime:09:00 rem:0m/n",
                    "(A) 2026-11-02 ship quarterly invoice +invoice @office due:2026-11-02 duetime:17:00 rem:0m/n",
                    "2026-11-02 review architecture notes +architecture @desk note:architecture.md"
                ).joinToString("\n") + "\n"
            } else {
                ""
            }
            AtomicFileWriter.writeAtomically(todoFile, initialTodo)
        }
        if (!doneFile.exists()) {
            AtomicFileWriter.writeAtomically(
                doneFile,
                if (!alreadySeeded) "x 2026-11-01 2026-10-30 set up plain text folder on android +ops @phone\n" else ""
            )
        }
        if (!rulesFile.exists()) {
            AtomicFileWriter.writeAtomically(rulesFile, TickRulesConfig.DEFAULT_RULES_FILE_CONTENT)
        }
        if (!alreadySeeded && notesDir.listFiles().isNullOrEmpty()) {
            notesRepo.saveNote(
                "architecture.md",
                "# tick.txt storage\n\n- **todo.txt** & **done.txt** follow the spec\n- `tick.rules.txt` holds smart-add rules\n- [x] Offline NLP inference\n- [ ] Sync SAF folder",
                listOf("2026-11-04@09:00/n")
            )
            notesRepo.saveNote(
                "packing-list.md",
                "# Packing List\n\n- [x] Passport\n- [ ] USB-C cable\n- [ ] Notebook & pen\n> Ready for departure",
                listOf("2026-11-05@07:30/n")
            )
        }
        if (!alreadySeeded) {
            prefs.edit().putBoolean(KEY_INITIAL_SEED_DONE, true).apply()
        }
    }

    /**
     * Records a rolling plain-text backup in `.tick/backups/` before modifying `todo.txt`,
     * `done.txt`, or `tick.rules.txt` (retaining the latest 5 snapshots per file).
     */
    private fun recordRollingBackup(sourceFile: File, incomingContent: String) {
        runCatching {
            if (!sourceFile.exists()) return
            val existing = sourceFile.readText(Charsets.UTF_8)
            if (existing.isBlank() || existing.trim() == incomingContent.trim()) return

            val prefix = "${sourceFile.nameWithoutExtension}_"
            val existingBaks = backupsDir.listFiles { f ->
                f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".bak")
            }?.sortedByDescending { it.lastModified() } ?: emptyList()

            val oldLineCount = existing.lines().count { it.isNotBlank() }
            val newLineCount = incomingContent.lines().count { it.isNotBlank() }
            val isShrinkOrDelete = newLineCount < oldLineCount
            val nowMs = System.currentTimeMillis()
            val enoughTimeElapsed = existingBaks.isEmpty() || (nowMs - existingBaks.first().lastModified() >= 15_000L)

            if (isShrinkOrDelete || enoughTimeElapsed) {
                val bakFile = File(backupsDir, "${prefix}${nowMs}.bak")
                AtomicFileWriter.writeAtomically(bakFile, existing)
                val updatedBaks = backupsDir.listFiles { f ->
                    f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".bak")
                }?.sortedByDescending { it.lastModified() } ?: emptyList()
                updatedBaks.drop(5).forEach { oldBak -> runCatching { oldBak.delete() } }
            }
        }
    }

    fun getTodoBackupCount(): Int {
        return runCatching {
            backupsDir.listFiles { f ->
                f.isFile && f.name.startsWith("todo_") && f.name.endsWith(".bak")
            }?.size ?: 0
        }.getOrDefault(0)
    }

    fun restoreLatestTodoBackup(): List<TodoTask>? {
        return runCatching {
            val latestBak = backupsDir.listFiles { f ->
                f.isFile && f.name.startsWith("todo_") && f.name.endsWith(".bak")
            }?.maxByOrNull { it.lastModified() } ?: return null

            val restoredRaw = latestBak.readText(Charsets.UTF_8)
            latestBak.delete()
            AtomicFileWriter.writeAtomically(todoFile, restoredRaw)
            writeToSafFile("todo.txt", "text/plain", restoredRaw)
            TodoParser.parseFile(restoredRaw)
        }.getOrNull()
    }

    /**
     * Moves all completed (`x ...`) tasks from `todo.txt` into `done.txt` atomically.
     * Returns `(remainingTasks, updatedDoneLines, archivedCount)`.
     */
    fun archiveCompletedTasks(currentTasks: List<TodoTask>, currentDoneLines: List<String>): Triple<List<TodoTask>, List<String>, Int> {
        val completed = currentTasks.filter { it.isCompleted }
        if (completed.isEmpty()) {
            return Triple(currentTasks, currentDoneLines, 0)
        }
        val remaining = currentTasks.filterNot { it.isCompleted }
            .mapIndexed { idx, task -> task.copy(lineNumber = idx + 1) }
        val newlyArchivedLines = completed.map { TodoParser.serializeLine(it).trim() }.filter { it.isNotEmpty() }
        val mergedDone = (currentDoneLines + newlyArchivedLines).distinct()

        saveDoneLines(mergedDone)
        saveTasks(remaining)
        return Triple(remaining, mergedDone, completed.size)
    }

    /**
     * Called immediately when the user selects a folder via `[/ Folder]` (or taps `[↺ Reload]`).
     * Loads `todo.txt`, `done.txt`, `tick.rules.txt`, and Markdown `.md` notes from the SAF folder.
     */
    fun loadAllFromSafFolder(customUriStr: String? = null): SafFolderLoadResult {
        ensureInitialized()
        if (customUriStr != null) {
            prefs.edit().putString(KEY_SAF_URI, customUriStr).commit()
        }
        val uriStr = customUriStr ?: prefs.getString(KEY_SAF_URI, null)
        val safDir = resolveSafDirectory(uriStr)

        if (safDir == null) {
            return SafFolderLoadResult(
                tasks = TodoParser.parseFile(runCatching { todoFile.readText(Charsets.UTF_8) }.getOrDefault("")),
                doneLines = runCatching { doneFile.readText(Charsets.UTF_8) }.getOrDefault("").lines().filter { it.isNotBlank() },
                rulesText = runCatching { rulesFile.readText(Charsets.UTF_8) }.getOrDefault(TickRulesConfig.DEFAULT_RULES_FILE_CONTENT),
                notes = notesRepo.listNotes(),
                foundExistingData = false
            )
        }

        val safTodoDoc = findFileIgnoreCase(safDir, "todo.txt") ?: findFileIgnoreCase(safDir, "tasks.txt")
        val safDoneDoc = findFileIgnoreCase(safDir, "done.txt")
        val safRulesDoc = findFileIgnoreCase(safDir, "tick.rules.txt")
        val safNotes = readNotesFromSafDir(safDir)

        val hasExistingSaves = (safTodoDoc != null) || (safDoneDoc != null) || (safRulesDoc != null) || safNotes.isNotEmpty()

        val loadedTasks: List<TodoTask> = if (safTodoDoc != null) {
            val rawTodo = readDocumentText(safTodoDoc) ?: ""
            recordRollingBackup(todoFile, rawTodo)
            runCatching { AtomicFileWriter.writeAtomically(todoFile, rawTodo) }
            TodoParser.parseFile(rawTodo)
        } else if (!hasExistingSaves) {
            val localRaw = runCatching { todoFile.readText(Charsets.UTF_8) }.getOrDefault("")
            writeToSafFile("todo.txt", "text/plain", localRaw, safDir)
            TodoParser.parseFile(localRaw)
        } else {
            val rawTodo = readFromSafFile("todo.txt", safDir) ?: ""
            if (rawTodo.isNotEmpty()) {
                recordRollingBackup(todoFile, rawTodo)
                runCatching { AtomicFileWriter.writeAtomically(todoFile, rawTodo) }
            }
            TodoParser.parseFile(rawTodo.ifEmpty { runCatching { todoFile.readText(Charsets.UTF_8) }.getOrDefault("") })
        }

        val loadedDoneLines: List<String> = if (safDoneDoc != null) {
            val rawDone = readDocumentText(safDoneDoc) ?: ""
            recordRollingBackup(doneFile, rawDone)
            runCatching { AtomicFileWriter.writeAtomically(doneFile, rawDone) }
            rawDone.lines().filter { it.isNotBlank() }
        } else {
            val localDone = runCatching { doneFile.readText(Charsets.UTF_8) }.getOrDefault("")
            if (!hasExistingSaves && localDone.isNotBlank()) {
                writeToSafFile("done.txt", "text/plain", localDone, safDir)
            }
            localDone.lines().filter { it.isNotBlank() }
        }

        val loadedRulesText: String = if (safRulesDoc != null) {
            val rawRules = readDocumentText(safRulesDoc) ?: TickRulesConfig.DEFAULT_RULES_FILE_CONTENT
            recordRollingBackup(rulesFile, rawRules)
            runCatching { AtomicFileWriter.writeAtomically(rulesFile, rawRules) }
            rawRules
        } else {
            val localRules = runCatching { rulesFile.readText(Charsets.UTF_8) }
                .getOrDefault(TickRulesConfig.DEFAULT_RULES_FILE_CONTENT)
            if (!hasExistingSaves) {
                writeToSafFile("tick.rules.txt", "text/plain", localRules, safDir)
            }
            localRules
        }

        val loadedNotes: List<NoteFile> = if (safNotes.isNotEmpty()) {
            notesDir.listFiles()?.forEach { f ->
                if (f.isFile) runCatching { f.delete() }
            }
            for ((fname, content) in safNotes) {
                val safeName = NotesRepository.toHumanReadableFilename(fname, ".md")
                val target = File(notesDir, safeName)
                AtomicFileWriter.writeAtomically(target, content.trimEnd() + "\n")
            }
            notesRepo.listNotes()
        } else {
            val localNotes = notesRepo.listNotes()
            if (!hasExistingSaves) {
                for (n in localNotes) {
                    writeNoteToSaf(n.filename, n.rawContent, safDir)
                }
            }
            localNotes
        }

        return SafFolderLoadResult(
            tasks = loadedTasks,
            doneLines = loadedDoneLines,
            rulesText = loadedRulesText,
            notes = loadedNotes,
            foundExistingData = hasExistingSaves
        )
    }

    fun loadTasks(): List<TodoTask> {
        ensureInitialized()
        val safContent = readFromSafFile("todo.txt")
        val raw = safContent ?: runCatching { todoFile.readText(Charsets.UTF_8) }.getOrDefault("")
        if (safContent != null) {
            recordRollingBackup(todoFile, safContent)
            runCatching { AtomicFileWriter.writeAtomically(todoFile, safContent) }
        }
        return TodoParser.parseFile(raw)
    }

    fun saveTasks(tasks: List<TodoTask>) {
        val numbered = tasks.mapIndexed { index, task -> task.copy(lineNumber = index + 1) }
        val serialized = TodoParser.serializeFile(numbered)
        recordRollingBackup(todoFile, serialized)
        AtomicFileWriter.writeAtomically(todoFile, serialized)
        writeToSafFile("todo.txt", "text/plain", serialized)
    }

    fun loadDoneLines(): List<String> {
        ensureInitialized()
        val safContent = readFromSafFile("done.txt")
        val raw = safContent ?: runCatching { doneFile.readText(Charsets.UTF_8) }.getOrDefault("")
        if (safContent != null) {
            recordRollingBackup(doneFile, safContent)
            runCatching { AtomicFileWriter.writeAtomically(doneFile, safContent) }
        }
        return raw.lines().filter { it.isNotBlank() }
    }

    fun saveDoneLines(lines: List<String>) {
        val content = if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
        recordRollingBackup(doneFile, content)
        AtomicFileWriter.writeAtomically(doneFile, content)
        writeToSafFile("done.txt", "text/plain", content)
    }

    fun loadRulesText(): String {
        ensureInitialized()
        val safContent = readFromSafFile("tick.rules.txt")
        if (safContent != null) {
            recordRollingBackup(rulesFile, safContent)
            runCatching { AtomicFileWriter.writeAtomically(rulesFile, safContent) }
        }
        return safContent ?: runCatching { rulesFile.readText(Charsets.UTF_8) }
            .getOrDefault(TickRulesConfig.DEFAULT_RULES_FILE_CONTENT)
    }

    fun saveRulesText(content: String) {
        recordRollingBackup(rulesFile, content)
        AtomicFileWriter.writeAtomically(rulesFile, content)
        writeToSafFile("tick.rules.txt", "text/plain", content)
    }

    fun loadNotes(): List<NoteFile> {
        ensureInitialized()
        val safDir = resolveSafDirectory(prefs.getString(KEY_SAF_URI, null))
        if (safDir != null) {
            val safNotes = readNotesFromSafDir(safDir)
            if (safNotes.isNotEmpty()) {
                notesDir.listFiles()?.forEach { f ->
                    if (f.isFile) runCatching { f.delete() }
                }
                for ((fname, content) in safNotes) {
                    val safeName = NotesRepository.toHumanReadableFilename(fname, ".md")
                    val target = File(notesDir, safeName)
                    AtomicFileWriter.writeAtomically(target, content.trimEnd() + "\n")
                }
            }
        }
        return notesRepo.listNotes()
    }

    fun saveNote(filename: String, rawContent: String): List<NoteFile> {
        val safeName = NotesRepository.toHumanReadableFilename(filename, ".md")
        val target = File(notesDir, safeName)
        val normalized = rawContent.trimEnd() + "\n"
        AtomicFileWriter.writeAtomically(target, normalized)
        writeNoteToSaf(safeName, normalized)
        return notesRepo.listNotes()
    }

    fun deleteNote(filename: String): List<NoteFile> {
        val safeName = NotesRepository.toHumanReadableFilename(filename, ".md")
        val target = File(notesDir, safeName)
        if (target.exists()) target.delete()
        File(notesDir, filename).takeIf { it.exists() }?.delete()
        deleteNoteFromSaf(safeName)
        deleteNoteFromSaf(filename)
        return notesRepo.listNotes()
    }

    fun loadSettings(): PersistedSettings {
        val themeStr = prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        val fontStr = prefs.getString(KEY_FONT_MODE, FontMode.MONOSPACE.name) ?: FontMode.MONOSPACE.name
        val sizeSp = prefs.getInt(KEY_FONT_SIZE, 16)
        val autoSyncCal = prefs.getBoolean(KEY_AUTO_GCAL, true)
        val rawTimedRem = prefs.getString(KEY_TIMED_REM, "0m/n") ?: "0m/n"
        val timedRem = "${rawTimedRem.substringBefore("/")}/n"
        val rawDateRem = prefs.getString(KEY_DATE_REM, "09:00/n") ?: "09:00/n"
        val dateOnlyRem = "${rawDateRem.substringBefore("/")}/n"
        val safUri = prefs.getString(KEY_SAF_URI, null)

        return PersistedSettings(
            themeMode = runCatching { ThemeMode.valueOf(themeStr) }.getOrDefault(ThemeMode.SYSTEM),
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
            .commit()
    }

    private fun resolveSafDirectory(uriStr: String?): DocumentFile? {
        if (uriStr.isNullOrBlank()) return null
        return runCatching {
            val treeUri = Uri.parse(uriStr)
            val rootDir = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            if (findFileIgnoreCase(rootDir, "todo.txt") == null) {
                val childTick = rootDir.listFiles().firstOrNull {
                    it.isDirectory && (it.name.equals("tick", ignoreCase = true) || it.name.equals("tick-txt", ignoreCase = true))
                }
                if (childTick != null && findFileIgnoreCase(childTick, "todo.txt") != null) {
                    return childTick
                }
            }
            rootDir
        }.getOrNull()
    }

    private fun findFileIgnoreCase(dir: DocumentFile, filename: String): DocumentFile? {
        dir.findFile(filename)?.takeIf { it.isFile }?.let { return it }
        val all = dir.listFiles()
        all.firstOrNull { it.isFile && it.name.equals(filename, ignoreCase = true) }?.let { return it }
        val base = filename.substringBeforeLast('.')
        val ext = filename.substringAfterLast('.', "")
        return all.firstOrNull { f ->
            val n = f.name?.lowercase() ?: return@firstOrNull false
            f.isFile && (n == "$base.$ext.$ext" || (n.startsWith("$base ") && n.endsWith(".$ext")))
        }
    }

    private fun findSubdirIgnoreCase(dir: DocumentFile, subdirName: String): DocumentFile? {
        dir.findFile(subdirName)?.takeIf { it.isDirectory }?.let { return it }
        return dir.listFiles().firstOrNull { it.isDirectory && it.name.equals(subdirName, ignoreCase = true) }
    }

    private fun readDocumentText(doc: DocumentFile): String? {
        return runCatching {
            context.contentResolver.openInputStream(doc.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrNull()
    }

    private fun writeDocumentText(doc: DocumentFile, content: String) {
        runCatching {
            val os = context.contentResolver.openOutputStream(doc.uri, "rwt")
                ?: context.contentResolver.openOutputStream(doc.uri, "w")
                ?: context.contentResolver.openOutputStream(doc.uri)
            os?.use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.flush()
            }
        }
    }

    private fun readFromSafFile(filename: String, preResolvedDir: DocumentFile? = null): String? {
        val dir = preResolvedDir ?: resolveSafDirectory(prefs.getString(KEY_SAF_URI, null)) ?: return null
        val doc = findFileIgnoreCase(dir, filename) ?: return null
        return readDocumentText(doc)
    }

    private fun writeToSafFile(
        filename: String,
        mimeType: String,
        content: String,
        preResolvedDir: DocumentFile? = null
    ) {
        val dir = preResolvedDir ?: resolveSafDirectory(prefs.getString(KEY_SAF_URI, null)) ?: return
        runCatching {
            val doc = findFileIgnoreCase(dir, filename) ?: dir.createFile(mimeType, filename) ?: return
            writeDocumentText(doc, content)
        }
    }

    private fun readNotesFromSafDir(safDir: DocumentFile): List<Pair<String, String>> {
        val reservedNames = setOf("todo.txt", "tasks.txt", "done.txt", "tick.rules.txt", "alarm-state.json", "reminder-state.json")
        val collected = LinkedHashMap<String, String>()

        val notesSubdir = findSubdirIgnoreCase(safDir, "notes")
        if (notesSubdir != null) {
            for (doc in notesSubdir.listFiles()) {
                val name = doc.name ?: continue
                if (doc.isFile && (name.endsWith(".md", ignoreCase = true) || name.endsWith(".txt", ignoreCase = true))) {
                    if (name.lowercase() !in reservedNames) {
                        val text = readDocumentText(doc) ?: continue
                        val mdName = NotesRepository.toHumanReadableFilename(name, ".md")
                        collected[mdName] = text
                    }
                }
            }
        }

        for (doc in safDir.listFiles()) {
            val name = doc.name ?: continue
            if (doc.isFile && name.endsWith(".md", ignoreCase = true)) {
                if (name.lowercase() !in reservedNames) {
                    val mdName = NotesRepository.toHumanReadableFilename(name, ".md")
                    if (mdName !in collected) {
                        val text = readDocumentText(doc) ?: continue
                        collected[mdName] = text
                    }
                }
            }
        }

        return collected.entries.map { it.key to it.value }
    }

    private fun writeNoteToSaf(
        filename: String,
        content: String,
        preResolvedDir: DocumentFile? = null
    ) {
        val safeMdName = NotesRepository.toHumanReadableFilename(filename, ".md")
        val safDir = preResolvedDir ?: resolveSafDirectory(prefs.getString(KEY_SAF_URI, null)) ?: return
        runCatching {
            val rootExisting = findFileIgnoreCase(safDir, safeMdName)
            if (rootExisting != null) {
                writeDocumentText(rootExisting, content)
                return
            }
            val notesSubdir = findSubdirIgnoreCase(safDir, "notes") ?: safDir.createDirectory("notes") ?: safDir
            val doc = findFileIgnoreCase(notesSubdir, safeMdName) ?: notesSubdir.createFile("text/markdown", safeMdName) ?: return
            writeDocumentText(doc, content)
        }
    }

    private fun deleteNoteFromSaf(filename: String) {
        val safDir = resolveSafDirectory(prefs.getString(KEY_SAF_URI, null)) ?: return
        runCatching {
            findFileIgnoreCase(safDir, filename)?.delete()
            findSubdirIgnoreCase(safDir, "notes")?.let { sub ->
                findFileIgnoreCase(sub, filename)?.delete()
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
        private const val KEY_INITIAL_SEED_DONE = "pref_initial_seed_done"
    }
}
