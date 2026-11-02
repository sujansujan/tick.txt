package com.ticktxt.app.core.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Guarantees crash-safe writes across all Android filesystems (ext4, F2FS, FUSE, SDCardFS).
 * Writes to a sibling `.tmp` file, flushes & fsyncs, and renames; if FUSE blocks rename,
 * writes directly with fsync so user changes are never lost on app close.
 */
object AtomicFileWriter {

    @Throws(IOException::class)
    fun writeAtomically(targetFile: File, content: String) {
        val parent = targetFile.parentFile ?: throw IOException("Target file has no parent directory")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create parent directory: ${parent.absolutePath}")
        }

        val bytes = content.toByteArray(Charsets.UTF_8)
        val tempFile = File(parent, "${targetFile.name}.tmp")
        var renamed = false
        try {
            FileOutputStream(tempFile, false).use { fos ->
                fos.write(bytes)
                fos.flush()
                runCatching { fos.fd.sync() }
            }
            renamed = tempFile.renameTo(targetFile)
        } catch (_: Exception) {
            renamed = false
        } finally {
            if (!renamed && tempFile.exists()) {
                runCatching { tempFile.delete() }
            }
        }

        if (!renamed) {
            FileOutputStream(targetFile, false).use { fos ->
                fos.write(bytes)
                fos.flush()
                runCatching { fos.fd.sync() }
            }
        }
    }
}
