package com.familylink.p2p.media

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.util.UUID

data class RecordedVoiceNote(
    val file: File,
    val durationMs: Long
)

class VoiceNoteRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startTimeMs: Long = 0L

    fun startRecording(): Boolean {
        return try {
            val dir = File(context.filesDir, "media").apply { mkdirs() }
            val outFile = File(dir, "voice_${UUID.randomUUID().toString().take(8)}.m4a")
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(24_000)
            r.setOutputFile(outFile.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            currentFile = outFile
            startTimeMs = System.currentTimeMillis()
            true
        } catch (_: Exception) {
            cleanup()
            false
        }
    }

    fun stopRecording(): RecordedVoiceNote? {
        val r = recorder ?: return null
        val f = currentFile ?: return null
        val duration = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(500L)
        return try {
            r.stop()
            r.release()
            recorder = null
            currentFile = null
            if (f.exists() && f.length() > 0L) RecordedVoiceNote(f, duration) else null
        } catch (_: Exception) {
            cleanup()
            null
        }
    }

    fun cancel() {
        val f = currentFile
        cleanup()
        f?.delete()
    }

    private fun cleanup() {
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        currentFile = null
    }
}

class VoiceNotePlayer {
    private var player: MediaPlayer? = null
    private var activePath: String? = null

    fun togglePlay(filePath: String, onFinished: () -> Unit): Boolean {
        if (activePath == filePath && player?.isPlaying == true) {
            stop()
            return false
        }
        stop()
        return try {
            val mp = MediaPlayer().apply {
                setDataSource(filePath)
                setOnCompletionListener {
                    stop()
                    onFinished()
                }
                prepare()
                start()
            }
            player = mp
            activePath = filePath
            true
        } catch (_: Exception) {
            stop()
            false
        }
    }

    fun stop() {
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        activePath = null
    }
}
