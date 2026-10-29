package com.sudokuoverlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager

object Screen {
    class Size(val w: Int, val h: Int, val dpi: Int)

    fun real(ctx: Context): Size {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val dpi = ctx.resources.configuration.densityDpi
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Size(b.width(), b.height(), dpi)
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            Size(m.widthPixels, m.heightPixels, dpi)
        }
    }
}

/**
 * One persistent virtual display (Android 14 allows only one per projection) that keeps the newest frame.
 * Frames are only produced when the screen changes, so we hold the latest one instead of waiting for a new one.
 * All work happens on [handler]'s thread.
 */
class ScreenGrabber(private val projection: MediaProjection, private val handler: Handler, size: Screen.Size) {
    private var width = size.w
    private var height = size.h
    private val dpi = size.dpi
    private var reader = newReader(width, height)
    private var held: Image? = null
    @Volatile private var frames = 0
    private val display: VirtualDisplay = projection.createVirtualDisplay(
        "sudoku-overlay", width, height, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler)

    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).also { r ->
            r.setOnImageAvailableListener({ src ->
                val img = try { src.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
                held?.close()
                held = img
                frames++
            }, handler)
        }

    /** Waits [delayMs] (so the caller's hidden views are really gone from the screen), then returns the newest frame. */
    fun capture(size: Screen.Size, delayMs: Long, timeoutMs: Long, cb: (Bitmap?) -> Unit) {
        val startFrames = frames
        handler.post {
            if (size.w != width || size.h != height) {
                val old = reader
                held?.close(); held = null
                reader = newReader(size.w, size.h)
                width = size.w; height = size.h
                display.resize(width, height, dpi)
                display.surface = reader.surface
                old.close()
            }
            val begin = SystemClock.uptimeMillis()
            val poll = object : Runnable {
                override fun run() {
                    val elapsed = SystemClock.uptimeMillis() - begin
                    val fresh = frames > startFrames || size.w != width
                    if ((elapsed >= delayMs && fresh) || elapsed >= timeoutMs) {
                        cb(held?.let { toBitmap(it) })
                    } else handler.postDelayed(this, 30)
                }
            }
            handler.postDelayed(poll, delayMs)
        }
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val rowPixels = plane.rowStride / plane.pixelStride
        val bmp = Bitmap.createBitmap(rowPixels, img.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        return if (rowPixels == img.width) bmp else Bitmap.createBitmap(bmp, 0, 0, img.width, img.height).also { bmp.recycle() }
    }

    fun release() {
        handler.post {
            try { held?.close() } catch (_: Exception) {}
            held = null
            try { display.release() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
        }
    }
}
