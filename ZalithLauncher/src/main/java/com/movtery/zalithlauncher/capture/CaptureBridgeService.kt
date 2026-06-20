package com.movtery.zalithlauncher.capture

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.notification.NOTIFICATION_ID_CAPTURE_SERVICE
import com.movtery.zalithlauncher.notification.NotificationChannelData
import java.io.File

/**
 * Host-side capture bridge. Runs in the {@code :game} process alongside the embedded JVM, connects to
 * the mod's AF_UNIX socket, and on a {@code START} control message captures the requested source:
 * camera (Phase 4), screen via MediaProjection (Phase 5), and — Phase 3 audio — microphone
 * ({@link MicSource}), game audio via AudioPlaybackCapture on the same projection ({@link GameAudioSource}),
 * and remote-audio playback through {@link AudioPlaybackSink}. Video/audio frames stream back to the mod.
 */
class CaptureBridgeService : LifecycleService() {

    private var client: CaptureBridgeClient? = null
    private var camera: CameraSource? = null
    private var screen: ScreenSource? = null
    private var mic: MicSource? = null
    private var gameAudio: GameAudioSource? = null
    private val sinks = HashMap<Int, AudioPlaybackSink>()

    private var mediaProjection: MediaProjection? = null
    private var pendingScreen = false
    private var pendingGameAudio: Pair<Int, Int>? = null  // (sampleRate, channels)
    private var pendingMic: Pair<Int, Int>? = null

    private var foregrounded = false
    private var fgsTypes = 0
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        val socketPath = File(File(filesDir, "ipc"), "capture.sock").absolutePath
        val c = CaptureBridgeClient(
            socketPath = socketPath,
            onStart = { source, w, h, _ -> onStartSource(source, w, h) },
            onStop = { source -> onStopSource(source) },
            onPlayOpen = { sink, rate, ch -> main.post { openSink(sink, rate, ch) } },
            onPlayClose = { sink -> main.post { closeSink(sink) } },
            onPlayFrame = { sink, pcm -> sinks[sink]?.write(pcm) },
        )
        client = c
        c.start()
        Log.i(TAG, "capture bridge started; socket=$socketPath")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun onStartSource(source: Int, w: Int, h: Int) {
        when (source) {
            CaptureBridgeClient.SOURCE_CAMERA -> main.post { startCamera(w, h) }
            CaptureBridgeClient.SOURCE_SCREEN -> main.post { startScreen() }
            CaptureBridgeClient.SOURCE_MIC -> main.post { startMic(w, h) }       // w=sampleRate, h=channels
            CaptureBridgeClient.SOURCE_GAME_AUDIO -> main.post { startGameAudio(w, h) }
        }
    }

    private fun onStopSource(source: Int) {
        when (source) {
            CaptureBridgeClient.SOURCE_CAMERA -> main.post { camera?.stop(); camera = null }
            CaptureBridgeClient.SOURCE_SCREEN -> main.post {
                pendingScreen = false; screen?.stop(); screen = null; recomputeForeground()
            }
            CaptureBridgeClient.SOURCE_MIC -> main.post {
                pendingMic = null; mic?.stop(); mic = null; recomputeForeground()
            }
            CaptureBridgeClient.SOURCE_GAME_AUDIO -> main.post {
                pendingGameAudio = null; gameAudio?.stop(); gameAudio = null; recomputeForeground()
            }
        }
    }

    // ---- camera (Phase 4) ----

    private var pendingCameraW = 1280
    private var pendingCameraH = 720

    private fun startCamera(w: Int, h: Int) {
        pendingCameraW = w
        pendingCameraH = h
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            client?.sendStatus(CaptureBridgeClient.SOURCE_CAMERA, CaptureBridgeClient.STATE_PENDING,
                "requesting camera permission")
            CapturePermissionActivity.request(this, Manifest.permission.CAMERA)
            return
        }
        if (camera != null) return
        client?.sendStatus(CaptureBridgeClient.SOURCE_CAMERA, CaptureBridgeClient.STATE_ACTIVE, "")
        val cam = CameraSource(
            context = this,
            lifecycleOwner = this,
            onFrame = { fw, fh, bgra -> client?.sendFrame(CaptureBridgeClient.SOURCE_CAMERA, fw, fh, bgra) },
            onError = { msg -> client?.sendStatus(CaptureBridgeClient.SOURCE_CAMERA, CaptureBridgeClient.STATE_ERROR, msg) },
        )
        camera = cam
        cam.start(w, h)
    }

    /** Result router for the runtime-permission helper (camera + mic). */
    fun onPermissionResult(permission: String, granted: Boolean) {
        main.post {
            when (permission) {
                Manifest.permission.CAMERA ->
                    if (granted) startCamera(pendingCameraW, pendingCameraH)
                    else client?.sendStatus(CaptureBridgeClient.SOURCE_CAMERA, CaptureBridgeClient.STATE_DENIED,
                        "camera permission denied")
                Manifest.permission.RECORD_AUDIO -> {
                    val p = pendingMic
                    if (granted && p != null) startMic(p.first, p.second)
                    else { pendingMic = null; client?.sendStatus(CaptureBridgeClient.SOURCE_MIC,
                        CaptureBridgeClient.STATE_DENIED, "microphone permission denied") }
                }
            }
        }
    }

    // ---- microphone (Phase 3) ----

    private fun startMic(sampleRate: Int, channels: Int) {
        if (mic != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            pendingMic = sampleRate to channels
            client?.sendStatus(CaptureBridgeClient.SOURCE_MIC, CaptureBridgeClient.STATE_PENDING,
                "requesting microphone permission")
            CapturePermissionActivity.request(this, Manifest.permission.RECORD_AUDIO)
            return
        }
        pendingMic = null
        recomputeForeground(addMic = true) // FGS must include 'microphone' before recording
        val m = MicSource(
            sampleRate = sampleRate, channels = channels,
            onFrame = { pcm, len -> client?.sendAudioFrame(CaptureBridgeClient.SOURCE_MIC, sampleRate, channels, pcm, len) },
            onError = { msg -> client?.sendStatus(CaptureBridgeClient.SOURCE_MIC, CaptureBridgeClient.STATE_ERROR, msg) },
        )
        mic = m
        m.start()
        client?.sendStatus(CaptureBridgeClient.SOURCE_MIC, CaptureBridgeClient.STATE_ACTIVE, "")
    }

    // ---- game audio (Phase 3, reuses the screen MediaProjection) ----

    private fun startGameAudio(sampleRate: Int, channels: Int) {
        if (gameAudio != null) return
        val mp = mediaProjection
        if (mp == null) {
            // Need the projection. If screen-share isn't already obtaining it, request consent now.
            pendingGameAudio = sampleRate to channels
            client?.sendStatus(CaptureBridgeClient.SOURCE_GAME_AUDIO, CaptureBridgeClient.STATE_PENDING,
                "waiting for screen-share consent")
            if (!pendingScreen) MediaProjectionRequestActivity.request(this)
            return
        }
        startGameAudioWith(mp, sampleRate, channels)
    }

    private fun startGameAudioWith(mp: MediaProjection, sampleRate: Int, channels: Int) {
        if (gameAudio != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            client?.sendStatus(CaptureBridgeClient.SOURCE_GAME_AUDIO, CaptureBridgeClient.STATE_ERROR,
                "game-audio capture requires Android 10+")
            return
        }
        recomputeForeground(addProjection = true)
        val g = GameAudioSource(
            mediaProjection = mp, sampleRate = sampleRate, channels = channels,
            onFrame = { pcm, len -> client?.sendAudioFrame(CaptureBridgeClient.SOURCE_GAME_AUDIO, sampleRate, channels, pcm, len) },
            onError = { msg -> client?.sendStatus(CaptureBridgeClient.SOURCE_GAME_AUDIO, CaptureBridgeClient.STATE_ERROR, msg) },
        )
        gameAudio = g
        g.start()
        client?.sendStatus(CaptureBridgeClient.SOURCE_GAME_AUDIO, CaptureBridgeClient.STATE_ACTIVE, "")
    }

    // ---- remote-audio playback sinks (mod → host) ----

    private fun openSink(sink: Int, sampleRate: Int, channels: Int) {
        if (sinks.containsKey(sink)) return
        val s = AudioPlaybackSink(sampleRate, channels)
        sinks[sink] = s
        s.start()
    }

    private fun closeSink(sink: Int) {
        sinks.remove(sink)?.stop()
    }

    // ---- screen-share (MediaProjection) ----

    private fun startScreen() {
        if (screen != null) return
        pendingScreen = true
        val mp = mediaProjection
        if (mp != null) { startScreenWith(mp); return }
        client?.sendStatus(CaptureBridgeClient.SOURCE_SCREEN, CaptureBridgeClient.STATE_PENDING,
            "requesting screen-share consent")
        MediaProjectionRequestActivity.request(this)
    }

    /** Called by {@link MediaProjectionRequestActivity} after the screen-capture consent dialog resolves. */
    fun onScreenConsentResult(resultCode: Int, data: Intent?) {
        main.post {
            if (resultCode != Activity.RESULT_OK || data == null) {
                if (pendingScreen) client?.sendStatus(CaptureBridgeClient.SOURCE_SCREEN,
                    CaptureBridgeClient.STATE_DENIED, "screen-share consent denied")
                if (pendingGameAudio != null) client?.sendStatus(CaptureBridgeClient.SOURCE_GAME_AUDIO,
                    CaptureBridgeClient.STATE_DENIED, "screen-share consent denied")
                pendingScreen = false; pendingGameAudio = null
                return@post
            }
            try {
                // Android 14: a mediaProjection foreground service must be running BEFORE getMediaProjection().
                recomputeForeground(addProjection = true)
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val mp = mpm.getMediaProjection(resultCode, data)
                    ?: throw IllegalStateException("getMediaProjection returned null")
                mediaProjection = mp
                if (pendingScreen) startScreenWith(mp)
                pendingGameAudio?.let { (rate, ch) -> pendingGameAudio = null; startGameAudioWith(mp, rate, ch) }
            } catch (e: Exception) {
                Log.e(TAG, "media projection start failed", e)
                pendingScreen = false; pendingGameAudio = null
                recomputeForeground()
                client?.sendStatus(CaptureBridgeClient.SOURCE_SCREEN, CaptureBridgeClient.STATE_ERROR,
                    e.message ?: "screen capture failed")
            }
        }
    }

    private fun startScreenWith(mp: MediaProjection) {
        if (screen != null) { pendingScreen = false; return }
        pendingScreen = false
        val (w, h, dpi) = screenSize()
        val src = ScreenSource(
            mediaProjection = mp,
            width = w, height = h, densityDpi = dpi,
            onFrame = { fw, fh, bgra -> client?.sendFrame(CaptureBridgeClient.SOURCE_SCREEN, fw, fh, bgra) },
            onStopped = { main.post { screen = null; recomputeForeground() } },
        )
        screen = src
        src.start()
        client?.sendStatus(CaptureBridgeClient.SOURCE_SCREEN, CaptureBridgeClient.STATE_ACTIVE, "")
    }

    /** Device display size, capped to 1280 on the long edge (even dims) to bound IPC bandwidth. */
    private fun screenSize(): Triple<Int, Int, Int> {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
        var w = m.widthPixels
        var h = m.heightPixels
        val maxDim = maxOf(w, h)
        if (maxDim > 1280) {
            val s = 1280.0 / maxDim
            w = (w * s).toInt() and 1.inv()
            h = (h * s).toInt() and 1.inv()
        }
        return Triple(w, h, m.densityDpi)
    }

    // ---- foreground service (combined mediaProjection + microphone types) ----

    /**
     * Recompute the FGS type set from active sources and (re)start/stop the foreground service.
     * {@code addProjection}/{@code addMic} force a type ON ahead of the source actually being assigned
     * (needed because the mediaProjection FGS must be live before getMediaProjection, and the microphone
     * FGS before AudioRecord).
     */
    private fun recomputeForeground(addProjection: Boolean = false, addMic: Boolean = false) {
        var types = 0
        if (addProjection || screen != null || gameAudio != null) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        if (addMic || mic != null) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (types == 0) {
            if (foregrounded) { stopForeground(STOP_FOREGROUND_REMOVE); foregrounded = false; fgsTypes = 0 }
            return
        }
        if (foregrounded && types == fgsTypes) return
        fgsTypes = types
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannelData.GAME_SERVICE_CHANNEL
        if (nm.getNotificationChannel(ch.channelId) == null) {
            nm.createNotificationChannel(NotificationChannel(ch.channelId, ch.channelName(this), ch.level))
        }
        val notification = NotificationCompat.Builder(this, ch.channelId)
            .setContentTitle(getString(R.string.notification_jvm_running_name))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID_CAPTURE_SERVICE, notification, fgsTypes)
        } else {
            startForeground(NOTIFICATION_ID_CAPTURE_SERVICE, notification)
        }
        foregrounded = true
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        camera?.stop()
        screen?.stop()
        mic?.stop()
        gameAudio?.stop()
        sinks.values.forEach { it.stop() }
        sinks.clear()
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        client?.stop()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CaptureBridgeService"
        /** Process-local handle so {@link CapturePermissionActivity} can report its result back. */
        @Volatile var instance: CaptureBridgeService? = null
    }
}
