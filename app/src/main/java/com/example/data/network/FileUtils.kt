package com.example.data.network

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.DecimalFormat

object FileUtils {

    const val BUFFER_SIZE = 64 * 1024
    const val MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024 * 1024

    data class FileMeta(
        val fileName: String,
        val fileSize: Long,
        val mimeType: String?
    )

    fun getFileMeta(context: Context, uri: Uri): FileMeta {
        var name = "file_${System.currentTimeMillis()}"
        var size = 0L
        var mime = context.contentResolver.getType(uri)
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex != -1) name = it.getString(nameIndex) ?: name
                    if (sizeIndex != -1) size = it.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (size <= 0L) {
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                    val descriptorSize = it.length
                    if (descriptorSize > 0) size = descriptorSize
                }
            } catch (_: Exception) {}
        }
        if (mime == null) {
            val extension = MimeTypeMap.getFileExtensionFromUrl(name)
            if (extension.isNotBlank()) {
                mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
            }
        }
        return FileMeta(name, size, mime)
    }

    fun copyUriToLocalFile(
        context: Context,
        uri: Uri,
        subFolder: String = "chat_media",
        maxSizeBytes: Long = MAX_FILE_SIZE_BYTES,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)? = null
    ): File? {
        return try {
            val meta = getFileMeta(context, uri)
            if (meta.fileSize > maxSizeBytes) return null

            val dir = File(context.filesDir, subFolder).apply {
                if (!exists()) mkdirs()
            }
            val sanitizedName = sanitizeFileName(meta.fileName)
            val targetFile = File(dir, "${System.currentTimeMillis()}_$sanitizedName")

            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            if (inputStream != null) {
                FileOutputStream(targetFile).use { outputStream ->
                    val copied = streamCopy(
                        inputStream = inputStream,
                        outputStream = outputStream,
                        totalSize = meta.fileSize,
                        maxBytes = maxSizeBytes,
                        onProgress = onProgress
                    )
                    if (copied < 0) {
                        targetFile.delete()
                        return null
                    }
                }
                inputStream.close()
                targetFile
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun streamCopy(
        inputStream: InputStream,
        outputStream: OutputStream,
        totalSize: Long = 0L,
        bufferSize: Int = BUFFER_SIZE,
        maxBytes: Long = MAX_FILE_SIZE_BYTES,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ): Long {
        val buffer = ByteArray(bufferSize)
        var bytesCopied = 0L
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            bytesCopied += read
            if (bytesCopied > maxBytes) return -1L
            outputStream.write(buffer, 0, read)
            if (onProgress != null && totalSize > 0L) {
                onProgress(bytesCopied, totalSize)
            }
        }
        return bytesCopied
    }

    fun sanitizeFileName(fileName: String): String {
        return fileName.replace(Regex("[^a-zA-Z0-9._\\-\u0600-\u06FF]"), "_")
    }

    fun getVideoThumbnail(filePath: String): Bitmap? {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(filePath)
            retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
        } catch (e: Exception) {
            null
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val formattedNumber = DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, digitGroups.toDouble()))
        return "$formattedNumber ${units[digitGroups]}"
    }

    fun openFileWithSystemApp(context: Context, filePath: String, mimeType: String? = null) {
        try {
            val file = File(filePath)
            if (!file.exists()) return
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val resolvedMime = mimeType ?: getMimeType(file.name) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolvedMime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "فتح بواسطة")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getMimeType(fileName: String): String? {
        val extension = fileName.substringAfterLast('.', "")
        return if (extension.isNotBlank()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
        } else null
    }

    fun isVideoMime(mimeType: String?, fileName: String): Boolean {
        if (mimeType?.startsWith("video/") == true) return true
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in listOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "flv", "wmv")
    }
}