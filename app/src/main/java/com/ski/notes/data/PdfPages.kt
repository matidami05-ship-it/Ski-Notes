package com.ski.notes.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors

/**
 * Renders pages of a note's attached PDF in the background and caches the results. PdfRenderer only
 * allows one open page at a time, so every render runs on one thread.
 */
class PdfPages(file: File) {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashSet<String>()
    @Volatile private var closed = false

    val pageCount: Int get() = renderer.pageCount

    /** Holds roughly 120 MB of page images. */
    private val cache = object : LruCache<String, Bitmap>(120 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Width buckets so zooming doesn't cause endless re-renders. */
    fun bucket(px: Float): Int = when {
        px <= 800 -> 800
        px <= 1400 -> 1400
        px <= 2200 -> 2200
        else -> 3000
    }

    /**
     * Returns the page image at about [widthPx] wide if it is ready. Otherwise it returns the best image
     * already cached (or null) and calls [onReady] once the sharp one is done.
     */
    fun get(page: Int, widthPx: Float, onReady: () -> Unit): Bitmap? {
        if (page < 0 || page >= pageCount) return null
        val w = bucket(widthPx)
        val key = "$page@$w"
        cache.get(key)?.let { return it }
        synchronized(pending) {
            if (pending.add(key)) {
                exec.execute {
                    try {
                        if (!closed) {
                            val bmp = renderNow(page, w)
                            cache.put(key, bmp)
                        }
                    } catch (_: Throwable) {
                    } finally {
                        synchronized(pending) { pending.remove(key) }
                    }
                    if (!closed) main.post(onReady)
                }
            }
        }
        // Fall back to any other size of this page while the sharp one renders.
        for (b in intArrayOf(1400, 800, 2200, 3000)) cache.get("$page@$b")?.let { return it }
        return null
    }

    /** Renders a page synchronously (caller must not be on the render thread). */
    fun renderBlocking(page: Int, widthPx: Int): Bitmap = exec.submit<Bitmap> { renderNow(page, widthPx) }.get()

    private fun renderNow(page: Int, w: Int): Bitmap {
        renderer.openPage(page).use { p ->
            val h = (w.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }

    /** Page aspect ratios (height / width), read once on import. */
    fun aspects(): FloatArray = exec.submit<FloatArray> {
        FloatArray(renderer.pageCount) { i -> renderer.openPage(i).use { it.height.toFloat() / it.width.coerceAtLeast(1) } }
    }.get()

    fun close() {
        closed = true
        exec.execute {
            try {
                renderer.close()
                fd.close()
            } catch (_: Throwable) {
            }
        }
        exec.shutdown()
        cache.evictAll()
    }
}
