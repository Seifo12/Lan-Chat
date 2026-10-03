package com.example.data.network

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.example.data.security.EncryptionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlaybackState(
    val currentFilePath: String? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Int = 0,
    val totalDurationMs: Int = 0,
    val progress: Float = 0f,
    val errorMessage: String? = null
) {
    val filePath: String? get() = currentFilePath
    val progressFraction: Float get() = progress
}

class AudioPlayerHelper(private val context: Context) {

    companion object {
        private const val TAG = "AudioPlayerHelper"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var currentPlayingTempFile: File? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    fun playOrPause(filePath: String) {
        val current = _playbackState.value
        if (current.currentFilePath == filePath && current.isPlaying) {
            pause()
            return
        }
        if (current.currentFilePath == filePath && mediaPlayer != null) {
            resume()
            return
        }
        // فك التشفير + prepare بيعملوا I/O تقيل، فبنشيلهم من الـ UI thread
        scope.launch(Dispatchers.IO) {
            startPlaying(filePath)
        }
    }

    private fun startPlaying(filePath: String) {
        stop()

        val originalFile = File(filePath)
        if (!originalFile.exists()) {
            Log.e(TAG, "Audio file not found: $filePath")
            _playbackState.value = PlaybackState(
                currentFilePath = filePath,
                errorMessage = "الملف الصوتي غير موجود"
            )
            return
        }

        try {
            val playableFile = EncryptionManager.getPlayableAudioFile(context, filePath)
            if (playableFile == null) {
                _playbackState.value = PlaybackState(
                    currentFilePath = filePath,
                    errorMessage = "مش قادر أفك تشفير هذا التسجيل على جهازك"
                )
                return
            }
            if (playableFile != originalFile) {
                currentPlayingTempFile = playableFile
            }

            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(playableFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    _playbackState.value = _playbackState.value.copy(
                        isPlaying = false,
                        currentPositionMs = 0,
                        progress = 0f
                    )
                    progressJob?.cancel()
                    cleanTempPlaybackFile()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    _playbackState.value = _playbackState.value.copy(
                        isPlaying = false,
                        errorMessage = "تعذّر تشغيل هذا التسجيل"
                    )
                    cleanTempPlaybackFile()
                    true
                }
                start()
            }
            mediaPlayer = player

            val duration = player.duration.coerceAtLeast(1)
            _playbackState.value = PlaybackState(
                currentFilePath = filePath,
                isPlaying = true,
                currentPositionMs = 0,
                totalDurationMs = duration,
                progress = 0f
            )
            startProgressTracker()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio file: ${e.message}", e)
            stop()
            _playbackState.value = PlaybackState(
                currentFilePath = filePath,
                errorMessage = "تعذّر تشغيل هذا التسجيل"
            )
        }
    }

    private fun resume() {
        mediaPlayer?.let { player ->
            if (!player.isPlaying) {
                player.start()
                _playbackState.value = _playbackState.value.copy(isPlaying = true)
                startProgressTracker()
            }
        }
    }

    fun pause() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                _playbackState.value = _playbackState.value.copy(isPlaying = false)
                progressJob?.cancel()
            }
        }
    }

    fun seekTo(progressFraction: Float) {
        mediaPlayer?.let { player ->
            val duration = player.duration
            val targetMs = (duration * progressFraction.coerceIn(0f, 1f)).toInt()
            player.seekTo(targetMs)
            _playbackState.value = _playbackState.value.copy(
                currentPositionMs = targetMs,
                progress = progressFraction
            )
        }
    }

    fun clearError() {
        if (_playbackState.value.errorMessage != null) {
            _playbackState.value = _playbackState.value.copy(errorMessage = null)
        }
    }

    fun stop() {
        progressJob?.cancel()
        progressJob = null
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaPlayer: ${e.message}")
        } finally {
            mediaPlayer = null
        }
        cleanTempPlaybackFile()
        _playbackState.value = PlaybackState()
    }

    private fun cleanTempPlaybackFile() {
        try {
            // 1. حذف الملف المؤقت الفعلي المرتبط بالمشغل
            currentPlayingTempFile?.let { tempFile ->
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
            currentPlayingTempFile = null

            // 2. تنظيف شامل لمجلد الكاش للتأكد من عدم وجود أي تسجيلات صوتية مفكوكة معلقة
            val tempDir = File(context.cacheDir, "decrypted_audio")
            if (tempDir.exists()) {
                tempDir.listFiles()?.forEach { file ->
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning temp file: ${e.message}")
        }
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && _playbackState.value.isPlaying) {
                delay(100)
                mediaPlayer?.let { player ->
                    try {
                        if (player.isPlaying) {
                            val pos = player.currentPosition
                            val dur = player.duration.coerceAtLeast(1)
                            val fraction = pos.toFloat() / dur.toFloat()
                            _playbackState.value = _playbackState.value.copy(
                                currentPositionMs = pos,
                                totalDurationMs = dur,
                                progress = fraction
                            )
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }
}