package com.movtery.zalithlauncher.capture

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Screen capture via MediaProjection. Mirrors the device display into a VirtualDisplay backed by an
 * ImageReader, converts each RGBA frame to BGRA, and hands it to {@code onFrame} (the bridge then
 * streams it to the mod). Runs on its own thread so the per-frame copy + socket write never touch the
 * UI thread.
 */
class ScreenSource(
    private val mediaProjection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val onFrame: (w: Int, h: Int, bgra: ByteArray) -> Unit,
    private val onStopped: () -> Unit,
) {
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val thread = HandlerThread("tbs-screen").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var bgraBuf: ByteArray? = null

    private val mpCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system/user")
            stop()
            onStopped()
        }
    }

    fun start() {
        mediaProjection.registerCallback(mpCallback, handler)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r -> drain(r) }, handler)
        imageReader = reader
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "tbs-screen", width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler
        )
        Log.i(TAG, "screen capture started ${width}x$height @${densityDpi}dpi")
    }

    private fun drain(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val src = plane.buffer
            val out = ensureBuf(width * height * 4)
            val row = ByteArray(rowStride)
            var o = 0
            for (y in 0 until height) {
                src.position(y * rowStride)
                src.get(row, 0, minOf(rowStride, src.remaining()))
                var i = 0
                var x = 0
                while (x < width) {
                    out[o] = row[i + 2]      // B
                    out[o + 1] = row[i + 1]  // G
                    out[o + 2] = row[i]      // R
                    out[o + 3] = row[i + 3]  // A
                    o += 4
                    i += pixelStride
                    x++
                }
            }
            onFrame(width, height, out)
        } catch (e: Exception) {
            Log.d(TAG, "screen frame failed: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun ensureBuf(size: Int): ByteArray {
        var b = bgraBuf
        if (b == null || b.size != size) { b = ByteArray(size); bgraBuf = b }
        return b
    }

    fun stop() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { mediaProjection.unregisterCallback(mpCallback) } catch (_: Exception) {}
        try { mediaProjection.stop() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "ScreenSource"
    }
}
