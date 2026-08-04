package com.prism.launcher.virtualization

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Surface
import java.io.DataInputStream
import java.net.Socket

/**
 * Minimal RFB (VNC) client that reads framebuffer updates from a QEMU VNC server
 * and blits each frame onto an Android [Surface].
 *
 * This is a simplified implementation covering the FramebufferUpdate path.
 * A production build would replace this with a full RFB 3.8 client or an
 * NDK-based renderer (e.g., libvncserver/libvncclient).
 */
internal class VncSurfaceRenderer(
    private val socket: Socket,
    private val surface: Surface,
) : Thread("VncSurfaceRenderer") {

    @Volatile private var running = true

    override fun run() {
        try {
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            // RFB handshake: server sends "RFB 003.008\n"
            val greeting = ByteArray(12)
            input.readFully(greeting)
            output.write("RFB 003.008\n".toByteArray())

            // Security: read offered types, choose None (1)
            val numTypes = input.read()
            val types = ByteArray(numTypes)
            input.readFully(types)
            output.write(byteArrayOf(1)) // SecurityType.None

            // SecurityResult
            input.readInt()

            // ClientInit: shared flag
            output.write(byteArrayOf(1))

            // ServerInit: read framebuffer dimensions and pixel format (24 bytes)
            val width  = input.readShort().toInt() and 0xFFFF
            val height = input.readShort().toInt() and 0xFFFF
            input.skipBytes(16) // pixel format
            val nameLen = input.readInt()
            input.skipBytes(nameLen)

            // Request continuous full-screen updates
            sendFramebufferUpdateRequest(output, 0, 0, width, height, incremental = false)

            val paint = Paint()
            var bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            while (running) {
                val msgType = input.read()
                if (msgType == 0) { // FramebufferUpdate
                    input.read() // padding
                    val numRects = input.readShort().toInt() and 0xFFFF
                    repeat(numRects) {
                        val x = input.readShort().toInt() and 0xFFFF
                        val y = input.readShort().toInt() and 0xFFFF
                        val w = input.readShort().toInt() and 0xFFFF
                        val h = input.readShort().toInt() and 0xFFFF
                        val encoding = input.readInt()
                        if (encoding == 0) { // Raw encoding
                            val pixels = IntArray(w * h)
                            for (i in pixels.indices) {
                                val r = input.read()
                                val g = input.read()
                                val b = input.read()
                                input.read() // padding byte
                                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                            if (x + w <= bitmap.width && y + h <= bitmap.height) {
                                bitmap.setPixels(pixels, 0, w, x, y, w, h)
                            }
                        }
                    }
                    blitToSurface(bitmap, paint, width, height)
                    sendFramebufferUpdateRequest(output, 0, 0, width, height, incremental = true)
                }
            }
            bitmap.recycle()
        } catch (_: Exception) {
            // Connection closed or VM stopped
        } finally {
            runCatching { socket.close() }
        }
    }

    fun stopRenderer() {
        running = false
        runCatching { socket.close() }
    }

    private fun blitToSurface(bitmap: Bitmap, paint: Paint, w: Int, h: Int) {
        if (!surface.isValid) return
        val canvas: Canvas = surface.lockCanvas(null) ?: return
        try {
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun sendFramebufferUpdateRequest(
        out: java.io.OutputStream,
        x: Int, y: Int, w: Int, h: Int,
        incremental: Boolean,
    ) {
        out.write(byteArrayOf(
            3,                              // FramebufferUpdateRequest
            if (incremental) 1 else 0,
            (x shr 8).toByte(), x.toByte(),
            (y shr 8).toByte(), y.toByte(),
            (w shr 8).toByte(), w.toByte(),
            (h shr 8).toByte(), h.toByte(),
        ))
    }
}
