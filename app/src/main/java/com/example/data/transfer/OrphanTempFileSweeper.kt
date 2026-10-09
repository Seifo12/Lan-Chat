package com.example.data.transfer

import android.content.Context
import java.io.File

/**
 * Removes half-written transfers left behind by a crash or a cancel.
 *
 * The receive path writes `temp_${transferId}_$name` and renames only on
 * success, so every interrupted transfer leaves a file behind. Nothing sweeps
 * them today, which is also why this phase adds no pending_transfers table:
 * the state that survives a process death is a file, so deleting the file is
 * the whole recovery.
 */
object OrphanTempFileSweeper {

    /** Must match the subfolders `handleIncomingFileStream` writes into. */
    private val DIRECTORIES =
        listOf("chat_photos", "chat_videos", "chat_files", "received_voices")

    /** Returns what it removed, so a caller can log it. */
    fun sweep(context: Context): List<String> {
        val removed = mutableListOf<String>()
        for (name in DIRECTORIES) {
            val dir = File(context.filesDir, name)
            if (!dir.isDirectory) continue
            dir.listFiles().orEmpty()
                .filter { it.isFile && it.name.startsWith("temp_") }
                .forEach { if (it.delete()) removed += "${name}/${it.name}" }
        }
        return removed
    }
}