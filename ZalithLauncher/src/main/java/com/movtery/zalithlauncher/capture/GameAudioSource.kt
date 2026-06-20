package com.movtery.zalithlauncher.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Game/playback audio capture via Android's `AudioPlaybackCapture` (API 29+), reusing the SAME
 * `MediaProjection` token as screen-share. Minecraft runs in the launcher's `:game` process — same UID —
 * so `addMatchingUid(Process.myUid())` captures the game's own audio mix regardless of any opt-in policy.
 * Emits interleaved s16le PCM to the bridge as the screen-share's "desktop audio" track.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class GameAudioSource(
    private val mediaProjection: MediaProjection,
    private val sampleRate: Int,
    private val channels: Int,
    private val onFrame: (pcm: ByteArray, len: Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    fun start() {
        if (running) return
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUid(Process.myUid())
            .build()
        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf * 2, sampleRate * channels)) // ~0.5 s headroom
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) { onError("game-audio init: ${e.message}"); return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { onError("game-audio not initialized"); rec.release(); return }
        record = rec
        running = true
        rec.startRecording()
        thread = Thread({ loop(rec) }, "tbs-gameaudio").apply { isDaemon = true; start() }
        Log.i(TAG, "game-audio capture started ${sampleRate}Hz x$channels")
    }

    private fun loop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frameBytes = (sampleRate / 50) * channels * 2 // ~20 ms
        val buf = ByteArray(frameBytes)
        while (running) {
            val n = rec.read(buf, 0, buf.size)
            if (n > 0) onFrame(buf, n)
            else if (n < 0) { onError("game-audio read=$n"); break }
        }
    }

    fun stop() {
        running = false
        try { thread?.join(200) } catch (_: Exception) {}
        thread = null
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
    }

    private companion object { const val TAG = "GameAudioSource" }
}
