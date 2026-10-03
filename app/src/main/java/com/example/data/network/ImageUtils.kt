package com.example.data.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

object ImageUtils {

    private const val MAX_BASE64_IMAGE_LENGTH = 15_000_000
    private const val MAX_DECODED_IMAGE_BYTES = 12_000_000
    private const val MAX_BITMAP_DIMENSION = 4096

    fun uriToCompressedBase64(context: Context, uri: Uri, maxDimension: Int = 1024, quality: Int = 75): String? {
        var inputStream: InputStream? = null
        return try {
            inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()

            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            val totalPixels = options.outWidth.toLong() * options.outHeight.toLong()
            if (totalPixels > MAX_BITMAP_DIMENSION.toLong() * MAX_BITMAP_DIMENSION.toLong()) return null

            inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            inputStream = null

            if (bitmap != null) bitmapToBase64(bitmap, maxDimension, quality) else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
        }
    }

    fun bitmapToBase64(bitmap: Bitmap, maxDimension: Int = 1024, quality: Int = 75): String {
        val scaledBitmap = scaleBitmapDown(bitmap, maxDimension)
        val byteArrayOutputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    fun base64ToImageFile(context: Context, base64Str: String, prefix: String = "photo_"): File? {
        if (base64Str.length > MAX_BASE64_IMAGE_LENGTH) return null
        return try {
            val decodedBytes = Base64.decode(base64Str, Base64.DEFAULT)
            if (decodedBytes.size > MAX_DECODED_IMAGE_BYTES || decodedBytes.isEmpty()) return null
            if (!isValidImageHeader(decodedBytes)) return null

            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            if (options.outWidth > MAX_BITMAP_DIMENSION || options.outHeight > MAX_BITMAP_DIMENSION) return null

            val photosDir = File(context.filesDir, "chat_photos").apply {
                if (!exists()) mkdirs()
            }
            val fileName = "${prefix}${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg"
            val file = File(photosDir, fileName)
            FileOutputStream(file).use { fos ->
                fos.write(decodedBytes)
                fos.flush()
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun saveBitmapToFile(context: Context, bitmap: Bitmap, prefix: String = "avatar_"): File? {
        return try {
            val dir = File(context.filesDir, "avatars").apply {
                if (!exists()) mkdirs()
            }
            val file = File(dir, "${prefix}${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
                fos.flush()
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun scaleBitmapDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        var resizedWidth = maxDimension
        var resizedHeight = maxDimension
        if (originalHeight > originalWidth) {
            resizedHeight = maxDimension
            resizedWidth = (resizedHeight * originalWidth.toFloat() / originalHeight.toFloat()).toInt()
        } else if (originalWidth > originalHeight) {
            resizedWidth = maxDimension
            resizedHeight = (resizedWidth * originalHeight.toFloat() / originalWidth.toFloat()).toInt()
        }
        return if (originalWidth > maxDimension || originalHeight > maxDimension) {
            Bitmap.createScaledBitmap(bitmap, resizedWidth.coerceAtLeast(1), resizedHeight.coerceAtLeast(1), true)
        } else {
            bitmap
        }
    }

    fun filePathToBase64(filePath: String, maxDimension: Int = 1024, quality: Int = 75): String? {
        return try {
            val file = File(filePath)
            if (!file.exists()) return null
            if (file.length() > MAX_DECODED_IMAGE_BYTES) return null
            val bitmap = BitmapFactory.decodeFile(filePath) ?: return null
            bitmapToBase64(bitmap, maxDimension, quality)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun isValidImageHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val isJpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        val isPng = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        val isWebp = bytes.size >= 12 &&
                bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
                bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
                bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        return isJpeg || isPng || isWebp
    }
}