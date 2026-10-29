package com.ski.notes.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.ski.notes.engine.Docx
import com.ski.notes.engine.NoteCodec
import com.ski.notes.engine.NoteDoc
import com.ski.notes.ui.DocLayout
import com.ski.notes.ui.PageRenderer
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Notes live as .skn files in app storage, each with a small PNG thumbnail of its first page. A note made
 * from a PDF keeps a copy of that PDF next to it (id.pdf) to draw underneath the pages.
 */
class NoteRepository(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "notes").apply { mkdirs() }

    data class Item(
        val id: String,
        val title: String,
        val modified: Long,
        val pages: Int,
        val thumb: File?,
        val hasPdf: Boolean,
        val text: String,
    )

    private val io = Executors.newSingleThreadExecutor()

    /**
     * Copy of a note that the save thread can write while the editor keeps changing the original. Strokes
     * and text boxes are never changed in place, so sharing them is safe; only the lists are copied.
     * Trailing blank pages (the spare page kept ready below your writing) are left out.
     */
    fun snapshot(doc: NoteDoc): NoteDoc {
        val c = NoteDoc(doc.id)
        c.title = doc.title
        c.created = doc.created
        c.modified = doc.modified
        c.paper = doc.paper
        c.tone = doc.tone
        c.hasPdf = doc.hasPdf
        var last = doc.pages.size - 1
        while (last > 0 && doc.pages[last].isEmpty()) last--
        for (i in 0..last) c.pages.add(doc.pages[i].copy())
        return c
    }

    /** Saves in the background, in order. A brand-new note left empty is deleted instead. */
    fun saveAsync(doc: NoteDoc, deleteIfEmpty: Boolean = false) {
        val snap = snapshot(doc)
        io.execute {
            try {
                if (deleteIfEmpty && snap.isEmpty() && snap.title.isBlank()) delete(snap.id) else save(snap)
            } catch (_: Throwable) {
            }
        }
    }

    private fun noteFile(id: String) = File(dir, "$id.skn")
    private fun thumbFile(id: String) = File(dir, "$id.png")
    fun pdfFile(id: String) = File(dir, "$id.pdf")

    /** Lists notes (call off the main thread). Waits for pending saves so the list is current. */
    fun list(): List<Item> = run { io.submit {}.get() }.let { listNow() }

    private fun listNow(): List<Item> = dir.listFiles { f -> f.name.endsWith(".skn") }.orEmpty().mapNotNull { f ->
        try {
            val m = f.inputStream().use { NoteCodec.readMeta(it) }
            val t = thumbFile(m.id)
            Item(m.id, m.title, m.modified, m.pageCount, t.takeIf { it.exists() }, m.hasPdf, m.text)
        } catch (_: Throwable) {
            null
        }
    }.sortedByDescending { it.modified }

    fun create(): NoteDoc = NoteDoc.blank(UUID.randomUUID().toString())

    fun load(id: String): NoteDoc? = try {
        noteFile(id).inputStream().use { NoteCodec.read(it) }.also { d ->
            if (d.hasPdf && !pdfFile(id).exists()) {
                // The PDF went missing: keep the ink, show plain paper.
                d.hasPdf = false
                for (p in d.pages) p.pdfPage = -1
            }
        }
    } catch (_: Throwable) {
        null
    }

    /** Writes atomically (temp file + rename) so a crash mid-save can't corrupt a note. */
    @Synchronized
    fun save(doc: NoteDoc) {
        val tmp = File(dir, "${doc.id}.skn.tmp")
        tmp.outputStream().use { NoteCodec.write(doc, it) }
        if (!tmp.renameTo(noteFile(doc.id))) {
            noteFile(doc.id).delete()
            tmp.renameTo(noteFile(doc.id))
        }
        try {
            val first = doc.pages[0]
            var bg: Bitmap? = null
            if (first.pdfPage >= 0 && doc.hasPdf) {
                val pages = PdfPages(pdfFile(doc.id))
                try {
                    bg = pages.renderBlocking(first.pdfPage, 360)
                } finally {
                    pages.close()
                }
            }
            val bmp = PageRenderer.renderPage(doc, 0, 360, bg)
            thumbFile(doc.id).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            bmp.recycle()
            bg?.recycle()
        } catch (_: Throwable) {
        }
    }

    fun rename(id: String, title: String) {
        val d = load(id) ?: return
        d.title = title
        save(d)
    }

    fun delete(id: String) {
        noteFile(id).delete()
        thumbFile(id).delete()
        pdfFile(id).delete()
    }

    fun duplicate(id: String) {
        val d = load(id) ?: return
        val copy = NoteDoc(UUID.randomUUID().toString())
        copy.title = (d.title.ifBlank { "Untitled" }) + " copy"
        copy.paper = d.paper
        copy.tone = d.tone
        copy.hasPdf = d.hasPdf
        for (p in d.pages) copy.pages.add(p.copy())
        if (d.hasPdf) pdfFile(id).copyTo(pdfFile(copy.id), overwrite = true)
        save(copy)
    }

    // ------------------------------------------------------------------ importing

    enum class Kind { PDF, WORD, UNKNOWN }

    fun kindOf(uri: Uri, mime: String?): Kind {
        val name = displayName(uri)?.lowercase() ?: ""
        return when {
            mime == "application/pdf" || name.endsWith(".pdf") -> Kind.PDF
            mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || name.endsWith(".docx") -> Kind.WORD
            else -> Kind.UNKNOWN
        }
    }

    fun displayName(uri: Uri): String? = try {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment
    } catch (_: Throwable) {
        uri.lastPathSegment
    }

    private fun titleFrom(uri: Uri) = (displayName(uri) ?: "Imported").substringBeforeLast('.').take(80)

    data class Imported(val id: String, val warning: String? = null)

    /** Imports a PDF or Word file as a new note (call off the main thread). */
    fun import(uri: Uri, mime: String?): Imported = when (kindOf(uri, mime)) {
        Kind.PDF -> Imported(importPdf(uri))
        Kind.WORD -> importWord(uri)
        Kind.UNKNOWN -> throw IOException("Ski Notes can open PDF and Word (.docx) files")
    }

    /** Copies the PDF in and makes one note page per PDF page, ready to write on. */
    fun importPdf(uri: Uri): String {
        val d = create()
        d.pages.clear()
        val file = pdfFile(d.id)
        app.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: throw IOException("Couldn't open the file")
        val pages = try {
            PdfPages(file)
        } catch (e: Throwable) {
            file.delete()
            throw IOException("This PDF can't be opened (it may be damaged or password-protected)")
        }
        try {
            val aspects = pages.aspects()
            if (aspects.isEmpty()) throw IOException("This PDF has no pages")
            aspects.forEachIndexed { i, a ->
                d.pages.add(NoteDoc.Page(NoteDoc.PAGE_W * a.coerceIn(0.1f, 10f), i))
            }
        } catch (e: Throwable) {
            file.delete()
            throw e
        } finally {
            pages.close()
        }
        d.hasPdf = true
        d.title = titleFrom(uri)
        d.paper = NoteDoc.PAPER_BLANK
        save(d)
        return d.id
    }

    /** Turns a Word file into a note of typed text you can keep editing. */
    fun importWord(uri: Uri): Imported {
        val paras = app.contentResolver.openInputStream(uri)?.use { Docx.read(it) }
            ?: throw IOException("Couldn't open the file")
        val d = create()
        d.pages.clear()
        d.paper = NoteDoc.PAPER_BLANK
        d.title = titleFrom(uri)
        d.pages.addAll(DocLayout.paginate(paras))
        if (d.pages.isEmpty()) d.pages.add(NoteDoc.Page())
        save(d)
        val pictures = paras.count { it.image != null }
        return Imported(d.id, if (pictures > 0) "Text imported. $pictures picture${if (pictures == 1) "" else "s"} from the Word file weren't brought in." else null)
    }
}
