package com.ski.notes.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.text.Layout
import androidx.core.content.FileProvider
import com.ski.notes.engine.Docx
import com.ski.notes.engine.NoteDoc
import com.ski.notes.engine.Outline
import com.ski.notes.engine.Stroke
import com.ski.notes.engine.TextBox
import com.ski.notes.ui.DocLayout
import com.ski.notes.ui.PageRenderer
import com.ski.notes.ui.TextStyler
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayOutputStream
import java.io.File

/** PDF / Word / image export and the share sheet. */
object Exporter {
    private fun outDir(ctx: Context) = File(ctx.cacheDir, "exports").apply {
        mkdirs()
        listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete() }
    }

    private fun safeName(doc: NoteDoc) =
        doc.title.ifBlank { "Note" }.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "Note" }.take(60)

    // ------------------------------------------------------------------ PDF

    /**
     * Every page as a PDF. For a note made from a PDF, the original pages are kept exactly as they were
     * (sharp, searchable) and your ink and typed text are added on top.
     */
    fun pdf(ctx: Context, doc: NoteDoc, sourcePdf: File?): File {
        val f = File(outDir(ctx), safeName(doc) + ".pdf")
        if (doc.hasPdf && sourcePdf != null && sourcePdf.exists()) annotatedPdf(ctx, doc, sourcePdf, f)
        else plainPdf(doc, f)
        return f
    }

    private fun plainPdf(doc: NoteDoc, f: File) {
        val pdf = PdfDocument()
        val pw = 595
        val s = pw / NoteDoc.PAGE_W
        for (i in doc.pages.indices) {
            val pg = doc.pages[i]
            val ph = (pg.height * s).toInt().coerceAtLeast(1)
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
            page.canvas.scale(s, s)
            PageRenderer.drawPage(page.canvas, doc, pg)
            pdf.finishPage(page)
        }
        f.outputStream().use { pdf.writeTo(it) }
        pdf.close()
    }

    private fun annotatedPdf(ctx: Context, doc: NoteDoc, source: File, out: File) {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        PDDocument.load(source).use { src ->
            src.isAllSecurityToBeRemoved = true
            PDDocument().use { dst ->
                val fonts = Fonts(dst)
                for (pg in doc.pages) {
                    val page: PDPage
                    val rotation: Int
                    val box: PDRectangle
                    if (pg.pdfPage in 0 until src.numberOfPages) {
                        page = dst.importPage(src.getPage(pg.pdfPage))
                        rotation = ((page.rotation % 360) + 360) % 360
                        box = page.cropBox
                    } else {
                        page = PDPage(PDRectangle(595f, 595f * pg.height / NoteDoc.PAGE_W))
                        dst.addPage(page)
                        rotation = 0
                        box = page.mediaBox
                    }
                    if (pg.strokes.isEmpty() && pg.texts.isEmpty()) continue
                    val shownW = if (rotation == 90 || rotation == 270) box.height else box.width
                    val s = shownW / NoteDoc.PAGE_W
                    PDPageContentStream(dst, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        // Map "shown page" coordinates (origin top-left, y down) to PDF user space.
                        val x0 = box.lowerLeftX
                        val y0 = box.lowerLeftY
                        val w = box.width
                        val h = box.height
                        val m = when (rotation) {
                            90 -> Matrix(0f, 1f, 1f, 0f, x0, y0)
                            180 -> Matrix(-1f, 0f, 0f, 1f, x0 + w, y0)
                            270 -> Matrix(0f, -1f, -1f, 0f, x0 + w, y0 + h)
                            else -> Matrix(1f, 0f, 0f, -1f, x0, y0 + h)
                        }
                        cs.transform(m)
                        cs.transform(Matrix(s, 0f, 0f, s, 0f, 0f))
                        for (t in pg.texts) drawText(cs, t, fonts)
                        for (st in pg.strokes) drawStroke(dst, cs, st)
                    }
                }
                dst.save(out)
            }
        }
    }

    private fun setColor(cs: PDPageContentStream, argb: Int, stroke: Boolean) {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        if (stroke) cs.setStrokingColor(r, g, b) else cs.setNonStrokingColor(r, g, b)
    }

    private fun drawStroke(doc: PDDocument, cs: PDPageContentStream, s: Stroke) {
        if (s.n == 0) return
        cs.saveGraphicsState()
        val alpha = PageRenderer.toolAlpha(s.tool)
        if (alpha < 1f || s.tool == Stroke.HIGHLIGHTER) {
            val gs = PDExtendedGraphicsState()
            gs.nonStrokingAlphaConstant = alpha
            gs.strokingAlphaConstant = alpha
            if (s.tool == Stroke.HIGHLIGHTER) gs.blendMode = BlendMode.MULTIPLY
            cs.setGraphicsStateParameters(gs)
        }
        if (s.tool == Stroke.HIGHLIGHTER) {
            setColor(cs, s.color, true)
            cs.setLineWidth(s.width)
            cs.setLineCapStyle(1)
            cs.setLineJoinStyle(1)
            cs.moveTo(s.x[0], s.y[0])
            if (s.n == 1) cs.lineTo(s.x[0] + 0.01f, s.y[0])
            for (i in 1 until s.n) cs.lineTo(s.x[i], s.y[i])
            cs.stroke()
        } else {
            setColor(cs, s.color, false)
            Outline.build(s) { pts, n ->
                cs.moveTo(pts[0], pts[1])
                for (k in 1 until n) cs.lineTo(pts[2 * k], pts[2 * k + 1])
                cs.closePath()
            }
            cs.fill() // non-zero winding: overlapping pieces merge into one shape
        }
        cs.restoreGraphicsState()
    }

    /** Fonts embedded for typed text; falls back to Helvetica if the phone's fonts can't be read. */
    private class Fonts(private val doc: PDDocument) {
        private val cache = HashMap<Int, PDFont>()
        private val files = mapOf(
            0 to listOf("Roboto-Regular.ttf", "RobotoStatic-Regular.ttf", "NotoSans-Regular.ttf", "DroidSans.ttf"),
            TextBox.BOLD to listOf("Roboto-Bold.ttf", "RobotoStatic-Bold.ttf", "NotoSans-Bold.ttf", "DroidSans-Bold.ttf"),
            TextBox.ITALIC to listOf("Roboto-Italic.ttf", "RobotoStatic-Italic.ttf", "NotoSans-Italic.ttf"),
            (TextBox.BOLD or TextBox.ITALIC) to listOf("Roboto-BoldItalic.ttf", "RobotoStatic-BoldItalic.ttf", "NotoSans-BoldItalic.ttf"),
        )

        fun get(flags: Int): PDFont {
            val key = flags and (TextBox.BOLD or TextBox.ITALIC)
            return cache.getOrPut(key) {
                for (name in files[key].orEmpty() + files[key and TextBox.BOLD].orEmpty() + files[0].orEmpty()) {
                    val f = File("/system/fonts/$name")
                    if (!f.exists()) continue
                    try {
                        return@getOrPut PDType0Font.load(doc, f)
                    } catch (_: Throwable) {
                    }
                }
                when (key) {
                    TextBox.BOLD -> PDType1Font.HELVETICA_BOLD
                    TextBox.ITALIC -> PDType1Font.HELVETICA_OBLIQUE
                    TextBox.BOLD or TextBox.ITALIC -> PDType1Font.HELVETICA_BOLD_OBLIQUE
                    else -> PDType1Font.HELVETICA
                }
            }
        }
    }

    /** Characters the font can draw (others are replaced so one odd emoji can't fail the export). */
    private fun drawable(font: PDFont, s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val ch = String(Character.toChars(cp))
            val ok = try {
                font.encode(ch); true
            } catch (_: Throwable) {
                false
            }
            sb.append(if (ok) ch else if (cp == '\t'.code) " " else "?")
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun drawText(cs: PDPageContentStream, t: TextBox, fonts: Fonts) {
        val tone = NoteDoc.TONE_WHITE
        val l: Layout = TextStyler.layout(t, tone)
        val text = t.text
        for (line in 0 until l.lineCount) {
            val ls = l.getLineStart(line)
            var le = l.getLineEnd(line)
            while (le > ls && (text[le - 1] == '\n')) le--
            if (le <= ls) continue
            val baseline = t.y + l.getLineBaseline(line)
            // Split the line where formatting changes.
            val cuts = sortedSetOf(ls, le)
            for (r in t.runs) {
                if (r.start in ls..le) cuts.add(r.start)
                if (r.end in ls..le) cuts.add(r.end)
            }
            val c = cuts.toList()
            for (k in 0 until c.size - 1) {
                val a = c[k]
                val b = c[k + 1]
                if (a >= b) continue
                var flags = 0
                var color = t.color
                var scale = 1f
                for (r in t.runs) if (r.start <= a && r.end >= b) {
                    flags = flags or r.flags
                    if (r.color != 0) color = r.color
                    if (r.scale != 1f) scale = r.scale
                }
                val piece = text.substring(a, b).trimEnd('\n', '\r')
                if (piece.isBlank()) continue
                val font = fonts.get(flags)
                val size = t.size * scale
                val x = t.x + l.getPrimaryHorizontal(a)
                val safe = drawable(font, piece)
                setColor(cs, color, false)
                cs.beginText()
                cs.setFont(font, size)
                // The page space is y-down; flip glyphs back upright.
                cs.setTextMatrix(Matrix(1f, 0f, 0f, -1f, x, baseline))
                cs.showText(safe)
                cs.endText()
                if ((flags and (TextBox.UNDERLINE or TextBox.STRIKE)) != 0) {
                    val w = try {
                        font.getStringWidth(safe) / 1000f * size
                    } catch (_: Throwable) {
                        l.getPrimaryHorizontal(b) - l.getPrimaryHorizontal(a)
                    }
                    setColor(cs, color, true)
                    cs.setLineWidth(size / 16f)
                    if ((flags and TextBox.UNDERLINE) != 0) {
                        cs.moveTo(x, baseline + size * 0.12f); cs.lineTo(x + w, baseline + size * 0.12f); cs.stroke()
                    }
                    if ((flags and TextBox.STRIKE) != 0) {
                        cs.moveTo(x, baseline - size * 0.3f); cs.lineTo(x + w, baseline - size * 0.3f); cs.stroke()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ Word

    /** Typed text as editable Word text, with each page's handwriting included as a picture. */
    fun docx(ctx: Context, doc: NoteDoc): File {
        val pngW = 1400
        val sizes = HashMap<Int, Pair<Int, Int>>()
        val paras = DocLayout.toParagraphs(doc, inkImage = { i ->
            val bmp = PageRenderer.renderInk(doc, i, pngW) ?: return@toParagraphs null
            // Picture width in points, matching where it sat on the page (page width = 475pt column).
            val k = 475f / pngW
            sizes[i] = (bmp.width * k).toInt() to (bmp.height * k).toInt()
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bmp.recycle()
            bos.toByteArray()
        }, inkSizePt = { i -> sizes[i] })
        val f = File(outDir(ctx), safeName(doc) + ".docx")
        f.outputStream().use { Docx.write(paras, doc.title, it) }
        return f
    }

    // ------------------------------------------------------------------ images

    fun pagePng(ctx: Context, doc: NoteDoc, index: Int, pdfBg: Bitmap?): File {
        val bmp = PageRenderer.renderPage(doc, index, 1654, pdfBg)
        val f = File(outDir(ctx), safeName(doc) + " p${index + 1}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f
    }

    fun share(ctx: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens the file in another app (e.g. Word, a PDF reader). */
    fun openWith(ctx: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
