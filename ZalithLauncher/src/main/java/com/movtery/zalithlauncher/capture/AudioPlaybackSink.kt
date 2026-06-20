package com.movtery.zalithlauncher.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Host-side speaker for one mod→host audio sink (proximity voice or remote screen-share audio). The mod
 * pulls remote PCM from the LiveKit FFI / voice relay and sends it here; this plays it through an
 * `AudioTrack`. Remote voice uses `USAGE_VOICE_COMMUNICATION` so the platform AEC references it (cancelling
 * it from the mic). One AudioTrack per sink; Android mixes multiple sinks at the OS level.
 */
class AudioPlaybackSink(
    private val sampleRate: Int,
    private val channels: Int,
) {
    @Volatile private var open = false
    private var track: AudioTrack? = null

    fun start() {
        if (open) return
        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val t = try {
            AudioTrack(attrs, format, maxOf(minBuf * 2, sampleRate * channels),
                AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        } catch (e: Exception) { Log.e(TAG, "AudioTrack init failed: ${e.message}"); return }
        if (t.state != AudioTrack.STATE_INITIALIZED) { Log.e(TAG, "AudioTrack not initialized"); t.release(); return }
        track = t
        t.play()
        open = true
        Log.i(TAG, "playback sink open ${sampleRate}Hz x$channels")
    }

    /** Write interleaved s16le PCM; blocking so it paces to real time. */
    fun write(pcm: ByteArray) {
        if (!open) return
        try { track?.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) } catch (_: Exception) {}
    }

    fun stop() {
        open = false
        try { track?.pause(); track?.flush(); track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
    }

    private companion object { const val TAG = "AudioPlaybackSink" }
}
