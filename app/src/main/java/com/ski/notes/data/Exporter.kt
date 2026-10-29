package com.ski.notes.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.ski.notes.engine.NoteDoc
import com.ski.notes.ui.PageRenderer
import java.io.File

/** PDF / image export and the share sheet. */
object Exporter {
    private fun outDir(ctx: Context) = File(ctx.cacheDir, "exports").apply { mkdirs() }

    private fun safeName(doc: NoteDoc) =
        doc.title.ifBlank { "Note" }.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "Note" }.take(60)

    /** Every page as vector ink on A4 pages. */
    fun pdf(ctx: Context, doc: NoteDoc): File {
        val pdf = PdfDocument()
        val pw = 595
        val ph = 842
        val s = pw / NoteDoc.PAGE_W
        for (i in doc.pages.indices) {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
            page.canvas.scale(s, s)
            PageRenderer.drawPage(page.canvas, doc, doc.pages[i].strokes)
            pdf.finishPage(page)
        }
        val f = File(outDir(ctx), safeName(doc) + ".pdf")
        f.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        return f
    }

    fun pagePng(ctx: Context, doc: NoteDoc, index: Int): File {
        val bmp = PageRenderer.renderPage(doc, index, 1654)
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
}
