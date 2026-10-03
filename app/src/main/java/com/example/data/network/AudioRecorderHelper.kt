package com.example.data.network

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
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

data class RecordingState(
    val isRecording: Boolean = false,
    val durationSeconds: Int = 0,
    val amplitude: Int = 0,
    val outputFile: File? = null,
    val isCancelled: Boolean = false
) {
    val durationMs: Long get() = durationSeconds * 1000L
}

class AudioRecorderHelper(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorderHelper"
        private const val MAX_RECORDING_DURATION_SECONDS = 600
    }

    private var mediaRecorder: MediaRecorder? = null
    private var rawTempOutputFile: File? = null
    private var recordTimerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _recordingState = MutableStateFlow(RecordingState())
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    fun startRecording(): Boolean {
        return try {
            stopRecording(discard = true)

            val dir = File(context.cacheDir, "raw_voice_temp").apply { mkdirs() }
            val file = File(dir, "raw_temp_${System.currentTimeMillis()}.m4a")
            rawTempOutputFile = file

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder

            _recordingState.value = RecordingState(
                isRecording = true,
                durationSeconds = 0,
                amplitude = 0,
                outputFile = file
            )

            startTimer()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            stopRecording(discard = true)
            false
        }
    }

    fun stopRecording(discard: Boolean = false): File? {
        recordTimerJob?.cancel()
        recordTimerJob = null

        val rawFile = rawTempOutputFile

        try {
            mediaRecorder?.apply {
                try {
                    stop()
                } catch (e: Exception) {
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaRecorder: ${e.message}")
        } finally {
            mediaRecorder = null
        }

        _recordingState.value = RecordingState(isRecording = false)

        if (discard || rawFile == null || !rawFile.exists() || rawFile.length() <= 0) {
            rawFile?.delete()
            rawTempOutputFile = null
            return null
        }

        // بنرجّع الملف الخام بدون تشفير.
        // القناة نفسها مشفّرة أصلاً (PENC/AES عبر pairwise)، فلو شفّرنا قبل الإرسال
        // كان المفتاح خاص بالمُرسِل والطرف التاني مش يقدر يفكّه، فالرسالة
        // كانت بتيجي مشغّرة على جهاز المستقبِل وMediaPlayer بيفشل بصمت.
        // التشفير على القرص بيتم عند الاستقبال بمفتاح الجهاز المستقبِل نفسه.
        return try {
            if (rawFile.exists() && rawFile.length() > 0) {
                rawFile
            } else {
                rawFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finalising voice note: ${e.message}", e)
            rawFile.delete()
            null
        }
    }

    private fun startTimer() {
        recordTimerJob?.cancel()
        recordTimerJob = scope.launch {
            var ticks = 0
            while (isActive && _recordingState.value.isRecording) {
                delay(100)
                val amp = try {
                    mediaRecorder?.maxAmplitude ?: 0
                } catch (e: Exception) {
                    0
                }
                ticks++
                val currentSeconds = ticks / 10

                _recordingState.value = _recordingState.value.copy(
                    durationSeconds = currentSeconds,
                    amplitude = amp
                )

                if (currentSeconds >= MAX_RECORDING_DURATION_SECONDS) {
                    Log.d(TAG, "Max recording duration reached, auto-stopping")
                    stopRecording(discard = false)
                    break
                }
            }
        }
    }
}
