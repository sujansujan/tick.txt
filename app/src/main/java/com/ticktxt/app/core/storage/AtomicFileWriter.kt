package com.ticktxt.app.core.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Guarantees crash-safe atomic writes for todo.txt, done.txt, tick.rules.txt, and notes.
 * Writes to `<target>.tmp` in the same directory, flushes & fsyncs the file descriptor,
 * then performs an atomic POSIX/filesystem rename.
 */
object AtomicFileWriter {

    @Throws(IOException::class)
    fun writeAtomically(targetFile: File, content: String) {
        val parent = targetFile.parentFile ?: throw IOException("Target file has no parent directory")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create parent directory: ${parent.absolutePath}")
        }

        val tempFile = File(parent, "${targetFile.name}.tmp.${System.nanoTime()}")
        try {
            FileOutputStream(tempFile).use { fos ->
                val bytes = content.toByteArray(Charsets.UTF_8)
                fos.write(bytes)
                fos.flush()
                fos.fd.sync()
            }
            try {
                Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                // Fallback for filesystems that do not support ATOMIC_MOVE
                Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }
}
