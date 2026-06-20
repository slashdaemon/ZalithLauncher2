package com.movtery.zalithlauncher.capture

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import android.util.Size
import java.util.concurrent.Executors

/**
 * CameraX webcam capture for the host app. Delivers BGRA frames (matching what StreamCraft's
 * VideoPublisher hands the LiveKit FFI) to {@code onFrame}. Runs the analyzer on a single background
 * thread, so the reusable BGRA buffer is written and shipped before the next frame is processed.
 */
class CameraSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFrame: (w: Int, h: Int, bgra: ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var provider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var bgraBuf: ByteArray? = null

    fun start(targetW: Int, targetH: Int) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cp = future.get()
                provider = cp
                val resolution = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(targetW, targetH),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(resolution)
                    .build()
                analysis.setAnalyzer(analysisExecutor, ::analyze)
                cp.unbindAll()
                cp.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                Log.i(TAG, "camera bound (target ${targetW}x$targetH)")
            } catch (e: Exception) {
                Log.e(TAG, "camera start failed", e)
                onError(e.message ?: "camera start failed")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyze(image: ImageProxy) {
        try {
            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride // 4 for RGBA_8888
            val src = plane.buffer
            val out = ensureBuf(w * h * 4)
            val row = ByteArray(rowStride)
            var o = 0
            for (y in 0 until h) {
                src.position(y * rowStride)
                src.get(row, 0, minOf(rowStride, src.remaining()))
                var i = 0
                var x = 0
                while (x < w) {
                    // RGBA -> BGRA
                    out[o] = row[i + 2]
                    out[o + 1] = row[i + 1]
                    out[o + 2] = row[i]
                    out[o + 3] = row[i + 3]
                    o += 4
                    i += pixelStride
                    x++
                }
            }
            onFrame(w, h, out)
        } catch (e: Exception) {
            Log.d(TAG, "frame convert failed: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun ensureBuf(size: Int): ByteArray {
        var b = bgraBuf
        if (b == null || b.size != size) {
            b = ByteArray(size)
            bgraBuf = b
        }
        return b
    }

    fun stop() {
        try { provider?.unbindAll() } catch (_: Exception) {}
        provider = null
    }

    companion object {
        private const val TAG = "CameraSource"
    }
}
