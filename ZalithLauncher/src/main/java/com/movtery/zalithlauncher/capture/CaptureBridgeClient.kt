package com.movtery.zalithlauncher.capture

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Client end of the StreamCraft capture IPC. The mod (inside the embedded JVM) is the server and binds
 * an AF_UNIX filesystem socket; this connects to it to receive control messages (START/STOP) and to
 * stream captured camera/screen frames back. Wire format mirrors the mod's {@code AndroidCaptureBridge}:
 * {@code [u32 len][u8 type][payload]}, big-endian.
 */
class CaptureBridgeClient(
    private val socketPath: String,
    private val onStart: (source: Int, w: Int, h: Int, fps: Int) -> Unit,
    private val onStop: (source: Int) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var socket: LocalSocket? = null
    private val writeLock = Any()
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "TBS-CaptureBridgeClient").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    fun isConnected(): Boolean = socket != null

    private fun loop() {
        while (running) {
            try {
                val s = LocalSocket()
                s.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
                socket = s
                Log.i(TAG, "connected to mod capture socket: $socketPath")
                readControl(s)
            } catch (e: Exception) {
                // mod hasn't bound the socket yet, or the connection dropped — retry below.
                Log.d(TAG, "connect/read ended: ${e.message}")
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
            }
            if (running) try { Thread.sleep(500) } catch (_: InterruptedException) { return }
        }
    }

    private fun readControl(s: LocalSocket) {
        val input = DataInputStream(s.inputStream)
        while (running) {
            val len = input.readInt() // big-endian
            if (len <= 0 || len > MAX_MSG) throw IOException("bad message length $len")
            val buf = ByteArray(len)
            input.readFully(buf)
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)
            when (bb.get().toInt() and 0xFF) {
                MSG_START -> {
                    val src = bb.get().toInt() and 0xFF
                    val w = bb.int; val h = bb.int; val fps = bb.int
                    Log.i(TAG, "START src=$src ${w}x$h@$fps")
                    onStart(src, w, h, fps)
                }
                MSG_STOP -> {
                    val src = bb.get().toInt() and 0xFF
                    Log.i(TAG, "STOP src=$src")
                    onStop(src)
                }
                else -> { /* ignore */ }
            }
        }
    }

    fun sendFrame(source: Int, w: Int, h: Int, bgra: ByteArray) {
        val payloadLen = 1 + 1 + 4 + 4 + 1 + bgra.size // type, src, w, h, fmt, pixels
        val framed = ByteBuffer.allocate(4 + payloadLen).order(ByteOrder.BIG_ENDIAN)
        framed.putInt(payloadLen)
        framed.put(MSG_FRAME.toByte()).put(source.toByte()).putInt(w).putInt(h)
            .put(FORMAT_BGRA.toByte()).put(bgra)
        write(framed.array())
    }

    fun sendStatus(source: Int, state: Int, reason: String) {
        val r = reason.toByteArray(Charsets.UTF_8)
        val payloadLen = 1 + 1 + 1 + 2 + r.size
        val framed = ByteBuffer.allocate(4 + payloadLen).order(ByteOrder.BIG_ENDIAN)
        framed.putInt(payloadLen)
        framed.put(MSG_STATUS.toByte()).put(source.toByte()).put(state.toByte())
            .putShort(r.size.toShort()).put(r)
        write(framed.array())
    }

    private fun write(bytes: ByteArray) {
        val s = socket ?: return
        synchronized(writeLock) {
            try {
                s.outputStream.write(bytes)
                s.outputStream.flush()
            } catch (e: Exception) {
                Log.d(TAG, "write failed: ${e.message}")
            }
        }
    }

    companion object {
        const val SOURCE_CAMERA = 0
        const val SOURCE_SCREEN = 1
        const val FORMAT_BGRA = 1
        const val STATE_PENDING = 0
        const val STATE_ACTIVE = 1
        const val STATE_DENIED = 2
        const val STATE_ERROR = 3
        private const val MSG_START = 0x02
        private const val MSG_STOP = 0x03
        private const val MSG_FRAME = 0x11
        private const val MSG_STATUS = 0x12
        private const val MAX_MSG = 32 * 1024 * 1024
        private const val TAG = "CaptureBridgeClient"
    }
}
