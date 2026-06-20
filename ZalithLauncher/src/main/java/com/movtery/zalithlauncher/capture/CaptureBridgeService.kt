package com.movtery.zalithlauncher.capture

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
 * the mod's AF_UNIX socket, and on a {@code START} control message captures the requested source
 * (camera in Phase 4; screen via MediaProjection in Phase 5) and streams BGRA frames back to the mod.
 *
 * <p>Plain (non-foreground) service for Phase 4 — the visible game keeps {@code :game} foreground, so
 * camera access is permitted. Phase 5 promotes this to a {@code mediaProjection} foreground service.
 */
class CaptureBridgeService : LifecycleService() {

    private var client: CaptureBridgeClient? = null
    private var camera: CameraSource? = null
    private var screen: ScreenSource? = null
    private var foregrounded = false
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        val socketPath = File(File(filesDir, "ipc"), "capture.sock").absolutePath
        val c = CaptureBridgeClient(
            socketPath = socketPath,
            onStart = { source, w, h, _ -> onStartSource(source, w, h) },
            onStop = { source -> onStopSource(source) },
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
        }
    }

    private fun onStopSource(source: Int) {
        when (source) {
            CaptureBridgeClient.SOURCE_CAMERA -> main.post { camera?.stop(); camera = null }
            CaptureBridgeClient.SOURCE_SCREEN -> main.post {
                screen?.stop(); screen = null; maybeStopForeground()
            }
        }
    }

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

    /** Called by {@link CapturePermissionActivity} after the CAMERA permission dialog resolves. */
    fun onCameraPermissionResult(granted: Boolean) {
        main.post {
            if (granted) startCamera(pendingCameraW, pendingCameraH)
            else client?.sendStatus(CaptureBridgeClient.SOURCE_CAMERA, CaptureBridgeClient.STATE_DENIED,
                "camera permission denied")
        }
    }

    // ---- screen-share (MediaProjection) ----

    private fun startScreen() {
        if (screen != null) return
        client?.sendStatus(CaptureBridgeClient.SOURCE_SCREEN, CaptureBridgeClient.STATE_PENDING,
            "requesting screen-share consent")
        MediaProjectionRequestActivity.request(this)
    }

    /** Called by {@link MediaProjectionRequestActivity} after the screen-capture consent dialog resolves. */
    fun onScreenConsentResult(resultCode: Int, data: Intent?) {
        main.post {
            if (resultCode != Activity.RESULT_OK || data == null) {
                client?.sendStatus(CaptureBridgeClient.SOURCE_SCREEN, CaptureBridgeClient.STATE_DENIED,
                    "screen-share consent denied")
                return@post
            }
            if (screen != null) return@post
            try {
                // Android 14: a mediaProjection foreground service must be running BEFORE getMediaProjection().
                startCaptureForeground()
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val mp = mpm.getMediaProjection(resultCode, data)
                    ?: throw IllegalStateException("getMediaProjection returned null")
                val (w, h, dpi) = screenSize()
                val src = ScreenSource(
                    mediaProjection = mp,
                    width = w, height = h, densityDpi = dpi,
                    onFrame = { fw, fh, bgra -> client?.sendFrame(CaptureBridgeClient.SOURCE_SCREEN, fw, fh, bgra) },
                    onStopped = { main.post { screen = null; maybeStopForeground() } },
                )
                screen = src
                src.start()
                client?.sendStatus(CaptureBridgeClient.SOURCE_SCREEN, CaptureBridgeClient.STATE_ACTIVE, "")
            } catch (e: Exception) {
                Log.e(TAG, "screen capture start failed", e)
                screen = null
                maybeStopForeground()
                client?.sendStatus(CaptureBridgeClient.SOURCE_SCREEN, CaptureBridgeClient.STATE_ERROR,
                    e.message ?: "screen capture failed")
            }
        }
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

    private fun startCaptureForeground() {
        if (foregrounded) return
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
            startForeground(NOTIFICATION_ID_CAPTURE_SERVICE, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID_CAPTURE_SERVICE, notification)
        }
        foregrounded = true
    }

    private fun maybeStopForeground() {
        if (foregrounded && screen == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregrounded = false
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        camera?.stop()
        screen?.stop()
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
