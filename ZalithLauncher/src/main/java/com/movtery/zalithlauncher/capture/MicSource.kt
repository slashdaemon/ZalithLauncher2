package com.movtery.zalithlauncher.capture

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log

/**
 * Microphone capture for StreamCraft voice. Uses the `VOICE_COMMUNICATION` audio source, which engages
 * the platform's comms-tuned AEC/NS/AGC — the Android replacement for the desktop webrtc-java APM (absent
 * on Android). Emits interleaved s16le PCM in ~20 ms frames to the bridge.
 */
class MicSource(
    private val sampleRate: Int,
    private val channels: Int,
    private val onFrame: (pcm: ByteArray, len: Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private val effects = ArrayList<AudioEffect>()

    fun start() {
        if (running) return
        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { onError("mic getMinBufferSize=$minBuf"); return }
        val rec = try {
            @Suppress("MissingPermission") // RECORD_AUDIO checked before this source is created
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2,
            )
        } catch (e: Exception) { onError("mic AudioRecord init: ${e.message}"); return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { onError("mic AudioRecord not initialized"); rec.release(); return }
        record = rec

        // Attach platform DSP to the record session (best-effort; the comms source may already apply them).
        val session = rec.audioSessionId
        try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session)?.also { it.enabled = true; effects += it } } catch (_: Exception) {}
        try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session)?.also { it.enabled = true; effects += it } } catch (_: Exception) {}
        try { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session)?.also { it.enabled = true; effects += it } } catch (_: Exception) {}

        running = true
        rec.startRecording()
        thread = Thread({ loop(rec) }, "tbs-mic").apply { isDaemon = true; start() }
        Log.i(TAG, "mic capture started ${sampleRate}Hz x$channels")
    }

    private fun loop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frameBytes = (sampleRate / 50) * channels * 2 // ~20 ms
        val buf = ByteArray(frameBytes)
        while (running) {
            val n = rec.read(buf, 0, buf.size)
            if (n > 0) onFrame(buf, n)
            else if (n < 0) { onError("mic read=$n"); break }
        }
    }

    fun stop() {
        running = false
        try { thread?.join(200) } catch (_: Exception) {}
        thread = null
        effects.forEach { try { it.release() } catch (_: Exception) {} }
        effects.clear()
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
    }

    private companion object { const val TAG = "MicSource" }
}
