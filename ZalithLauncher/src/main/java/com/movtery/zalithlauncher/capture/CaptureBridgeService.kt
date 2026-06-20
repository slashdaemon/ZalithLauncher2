package com.movtery.zalithlauncher.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
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
        if (source == CaptureBridgeClient.SOURCE_CAMERA) main.post { startCamera(w, h) }
        // SOURCE_SCREEN: Phase 5 (MediaProjection)
    }

    private fun onStopSource(source: Int) {
        if (source == CaptureBridgeClient.SOURCE_CAMERA) main.post { camera?.stop(); camera = null }
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

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        camera?.stop()
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
