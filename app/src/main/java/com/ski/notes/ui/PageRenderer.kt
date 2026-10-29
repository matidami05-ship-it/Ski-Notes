package com.ski.notes.ui

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.ski.notes.engine.NoteDoc
import com.ski.notes.engine.Outline
import com.ski.notes.engine.Stroke
import com.ski.notes.engine.TextBox
import java.util.WeakHashMap

/** Draws paper and strokes. Shared by the editor, thumbnails and PDF export (synchronized: the save thread renders thumbnails). */
object PageRenderer {
    private val pathCache = WeakHashMap<Stroke, Path>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val paperPaint = Paint()
    private val patternPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun paperColor(tone: Int): Int = when (tone) {
        NoteDoc.TONE_CREAM -> 0xFFFBF6E9.toInt()
        NoteDoc.TONE_DARK -> 0xFF1E2126.toInt()
        else -> Color.WHITE
    }

    private fun patternColor(tone: Int): Int = when (tone) {
        NoteDoc.TONE_DARK -> 0xFF3A3F47.toInt()
        NoteDoc.TONE_CREAM -> 0xFFD9CFB8.toInt()
        else -> 0xFFD3DAE5.toInt()
    }

    /** On dark paper, near-black ink is shown light so it stays readable (and flips back on light paper). */
    fun displayColor(color: Int, tone: Int): Int {
        if (tone != NoteDoc.TONE_DARK) return color
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return if (lum < 0.18) Color.argb(Color.alpha(color), 236, 238, 242) else color
    }

    /** Undoes [displayColor] for a colour read back from the screen (typed text on dark paper). */
    fun storedColor(shown: Int, tone: Int): Int {
        if (tone != NoteDoc.TONE_DARK) return shown
        return if (Color.red(shown) == 236 && Color.green(shown) == 238 && Color.blue(shown) == 242) Color.argb(Color.alpha(shown), 0, 0, 0) else shown
    }

    @Synchronized
    fun drawPaper(c: Canvas, paper: Int, tone: Int, h: Float = NoteDoc.PAGE_H) {
        val w = NoteDoc.PAGE_W
        paperPaint.color = paperColor(tone)
        c.drawRect(0f, 0f, w, h, paperPaint)
        patternPaint.color = patternColor(tone)
        when (paper) {
            NoteDoc.PAPER_LINED -> {
                patternPaint.strokeWidth = 1.6f
                var y = 170f
                while (y < h - 40f) {
                    c.drawLine(0f, y, w, y, patternPaint)
                    y += 56f
                }
                patternPaint.color = if (tone == NoteDoc.TONE_DARK) 0xFF6B3A44.toInt() else 0xFFF2B8BE.toInt()
                c.drawLine(150f, 0f, 150f, h, patternPaint)
            }
            NoteDoc.PAPER_GRID -> {
                patternPaint.strokeWidth = 1.2f
                var x = 50f
                while (x < w) { c.drawLine(x, 0f, x, h, patternPaint); x += 50f }
                var y = 50f
                while (y < h) { c.drawLine(0f, y, w, y, patternPaint); y += 50f }
            }
            NoteDoc.PAPER_DOTS -> {
                var y = 50f
                while (y < h) {
                    var x = 50f
                    while (x < w) { c.drawCircle(x, y, 2.4f, patternPaint); x += 50f }
                    y += 50f
                }
            }
        }
    }

    /** Path for a finished stroke, cached. Outline polygons for ink; a centre line for the highlighter. */
    @Synchronized
    fun pathFor(s: Stroke): Path {
        pathCache[s]?.let { return it }
        val p = buildPath(s)
        pathCache[s] = p
        return p
    }

    @Synchronized
    fun forget(s: Stroke) {
        pathCache.remove(s)
    }

    fun buildPath(s: Stroke): Path {
        val path = Path()
        if (s.tool == Stroke.HIGHLIGHTER) {
            if (s.n > 0) {
                path.moveTo(s.x[0], s.y[0])
                if (s.n == 1) path.lineTo(s.x[0] + 0.01f, s.y[0])
                for (i in 1 until s.n) path.lineTo(s.x[i], s.y[i])
            }
        } else {
            path.fillType = Path.FillType.WINDING
            Outline.build(s, PathSink(path))
        }
        return path
    }

    /** Opacity a tool is drawn with (applied to the whole stroke, so overlaps never darken). */
    fun toolAlpha(tool: Int): Float = when (tool) {
        Stroke.PENCIL -> 0.82f
        Stroke.HIGHLIGHTER -> 0.38f
        else -> 1f
    }

    @Synchronized
    fun drawStroke(c: Canvas, s: Stroke, tone: Int, path: Path = pathFor(s), colorOverride: Int? = null) {
        val color = displayColor(colorOverride ?: s.color, tone)
        if (s.tool == Stroke.HIGHLIGHTER) {
            line.color = color
            line.alpha = (255 * toolAlpha(s.tool)).toInt()
            line.strokeWidth = s.width
            line.blendMode = if (tone == NoteDoc.TONE_DARK) null else BlendMode.MULTIPLY
            c.drawPath(path, line)
            line.blendMode = null
        } else {
            fill.color = color
            fill.alpha = (Color.alpha(color) * toolAlpha(s.tool)).toInt()
            c.drawPath(path, fill)
        }
    }

    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pdfWhite = Paint().apply { color = Color.WHITE }
    private val dst = RectF()

    /**
     * Draws one page in page coordinates: paper (or the PDF page underneath), typed text, then ink on top.
     * [pdfBg] is the rendered PDF page for pages that have one (null = not ready yet, drawn white).
     */
    @Synchronized
    fun drawPage(
        c: Canvas,
        doc: NoteDoc,
        page: NoteDoc.Page,
        strokes: List<Stroke> = page.strokes,
        skip: Set<Stroke>? = null,
        skipText: TextBox? = null,
        pdfBg: Bitmap? = null,
    ) {
        val h = page.height
        if (page.pdfPage >= 0) {
            c.drawRect(0f, 0f, NoteDoc.PAGE_W, h, pdfWhite)
            if (pdfBg != null) {
                dst.set(0f, 0f, NoteDoc.PAGE_W, h)
                c.drawBitmap(pdfBg, null, dst, bgPaint)
            }
        } else drawPaper(c, doc.paper, doc.tone, h)
        c.save()
        c.clipRect(0f, 0f, NoteDoc.PAGE_W, h)
        val tone = if (page.pdfPage >= 0) NoteDoc.TONE_WHITE else doc.tone
        for (t in page.texts) {
            if (t === skipText) continue
            drawText(c, t, tone)
        }
        for (s in strokes) {
            if (skip != null && s in skip) continue
            val sh = s.maxHalfWidth() + 2f
            if (c.quickReject(s.minX - sh, s.minY - sh, s.maxX + sh, s.maxY + sh)) continue
            drawStroke(c, s, tone)
        }
        c.restore()
    }

    fun drawText(c: Canvas, t: TextBox, tone: Int) {
        val l = TextStyler.layout(t, tone)
        if (c.quickReject(t.x, t.y, t.x + t.w, t.y + l.height)) return
        c.save()
        c.translate(t.x, t.y)
        l.draw(c)
        c.restore()
    }

    /** Ink colours shown on a page: PDF pages are always light, so ink is never flipped there. */
    fun pageTone(doc: NoteDoc, page: NoteDoc.Page): Int = if (page.pdfPage >= 0) NoteDoc.TONE_WHITE else doc.tone

    /** Renders a page to a bitmap of the given width (thumbnails, sharing). */
    @Synchronized
    fun renderPage(doc: NoteDoc, index: Int, width: Int, pdfBg: Bitmap? = null): Bitmap {
        val scale = width / NoteDoc.PAGE_W
        val page = doc.pages[index]
        val bmp = Bitmap.createBitmap(width, (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        drawPage(c, doc, page, pdfBg = pdfBg)
        return bmp
    }

    /** Only the handwriting of a page, on white (for Word export). Returns null if the page has no ink. */
    @Synchronized
    fun renderInk(doc: NoteDoc, index: Int, width: Int): Bitmap? {
        val page = doc.pages[index]
        if (page.strokes.isEmpty()) return null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (s in page.strokes) {
            val hw = s.maxHalfWidth()
            minX = minOf(minX, s.minX - hw); minY = minOf(minY, s.minY - hw)
            maxX = maxOf(maxX, s.maxX + hw); maxY = maxOf(maxY, s.maxY + hw)
        }
        minX = minX.coerceAtLeast(0f) ; minY = minY.coerceAtLeast(0f)
        maxX = maxX.coerceAtMost(NoteDoc.PAGE_W); maxY = maxY.coerceAtMost(page.height)
        if (maxX - minX < 2 || maxY - minY < 2) return null
        val scale = width / NoteDoc.PAGE_W
        val bmp = Bitmap.createBitmap(((maxX - minX) * scale).toInt().coerceAtLeast(1), ((maxY - minY) * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        c.translate(-minX, -minY)
        for (s in page.strokes) drawStroke(c, s, NoteDoc.TONE_WHITE)
        return bmp
    }

    class PathSink(private val path: Path) : Outline.Sink {
        override fun polygon(pts: FloatArray, n: Int) {
            path.moveTo(pts[0], pts[1])
            for (k in 1 until n) path.lineTo(pts[2 * k], pts[2 * k + 1])
            path.close()
        }
    }
}
