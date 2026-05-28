package com.secondream.novagram.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAt: Long = 0L

    fun start(): File {
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.ogg")
        output = file
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setOutputFormat(MediaRecorder.OutputFormat.OGG)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
        rec.setAudioSamplingRate(48000)
        rec.setAudioEncodingBitRate(32000)
        rec.setAudioChannels(1)
        rec.setOutputFile(file.absolutePath)
        rec.prepare()
        rec.start()
        startedAt = SystemClock.elapsedRealtime()
        recorder = rec
        return file
    }

    fun stop(): RecordingResult? {
        val r = recorder ?: return null
        val durationMs = SystemClock.elapsedRealtime() - startedAt
        return try {
            r.stop()
            r.release()
            recorder = null
            val file = output ?: return null
            output = null
            if (file.exists() && file.length() > 200) {
                RecordingResult(file, (durationMs / 1000L).toInt().coerceAtLeast(1))
            } else null
        } catch (t: Throwable) {
            try { r.release() } catch (_: Throwable) { }
            recorder = null
            output?.delete()
            output = null
            null
        }
    }

    fun cancel() {
        try { recorder?.stop() } catch (_: Throwable) { }
        try { recorder?.release() } catch (_: Throwable) { }
        recorder = null
        output?.delete()
        output = null
    }

    data class RecordingResult(val file: File, val durationSeconds: Int)
}
