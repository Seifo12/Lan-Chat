package com.example.data.transfer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OrphanTempFileSweepTest {

    private val directories = listOf("chat_photos", "chat_videos", "chat_files", "received_voices")

    private fun filesDir() =
        ApplicationProvider.getApplicationContext<Application>().filesDir

    @Test
    fun `only temp files go and the sweep names them`() {
        val stale = mutableListOf<File>()
        directories.forEach { name ->
            val dir = File(filesDir(), name).apply { mkdirs() }
            stale += File(dir, "temp_xfer_abc_photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            File(dir, "1700000000_photo.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        }
        val swept = OrphanTempFileSweeper.sweep(
            ApplicationProvider.getApplicationContext<Application>()
        )
        assertEquals(stale.size, swept.size)
        assertTrue(swept.all { it.contains("temp_") })
        directories.forEach { name ->
            val remaining = File(filesDir(), name).listFiles().orEmpty()
            assertEquals(
                "a finished transfer must survive the sweep in $name",
                1,
                remaining.size
            )
            assertTrue(remaining.single().name.endsWith("photo.jpg"))
            assertTrue(!remaining.single().name.startsWith("temp_"))
        }
    }

    @Test
    fun `a missing directory is not an error`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        File(context.filesDir, "chat_files").deleteRecursively()
        assertEquals(emptyList<String>(), OrphanTempFileSweeper.sweep(context))
    }

    @Test
    fun `a directory holding only temp files is left empty not deleted`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dir = File(context.filesDir, "chat_photos").apply { mkdirs() }
        File(dir, "temp_x_y.jpg").writeBytes(byteArrayOf(1))
        OrphanTempFileSweeper.sweep(context)
        assertTrue("the directory itself must survive", dir.isDirectory)
        assertEquals(0, dir.listFiles().orEmpty().size)
    }

    @Test
    fun `nested directories are not touched`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        // Unique name: Robolectric shares one filesDir across the methods in
        // this class, so a fixed name collides with a file an earlier test
        // left behind and mkdirs throws instead of asserting.
        val dir = File(context.filesDir, "chat_files").apply { mkdirs() }
        val nested = File(dir, "temp_nested_${System.nanoTime()}").apply { mkdirs() }
        OrphanTempFileSweeper.sweep(context)
        assertTrue("only regular files are orphans, never directories", nested.isDirectory)
    }
}