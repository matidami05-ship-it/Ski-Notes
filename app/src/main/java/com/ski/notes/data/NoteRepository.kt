package com.ski.notes.data

import android.content.Context
import android.graphics.Bitmap
import com.ski.notes.engine.NoteCodec
import com.ski.notes.engine.NoteDoc
import com.ski.notes.ui.PageRenderer
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Notes live as .skn files in app storage, each with a small PNG thumbnail of its first page. */
class NoteRepository(context: Context) {
    private val dir = File(context.filesDir, "notes").apply { mkdirs() }

    data class Item(
        val id: String,
        val title: String,
        val modified: Long,
        val pages: Int,
        val thumb: File?,
    )

    private val io = Executors.newSingleThreadExecutor()

    /**
     * Copy of a note that the save thread can write while the editor keeps changing the original. Finished
     * strokes are never modified in place, so sharing them is safe; only the lists are copied. Trailing
     * blank pages (the spare page kept ready below your writing) are left out.
     */
    fun snapshot(doc: NoteDoc): NoteDoc {
        val c = NoteDoc(doc.id)
        c.title = doc.title
        c.created = doc.created
        c.modified = doc.modified
        c.paper = doc.paper
        c.tone = doc.tone
        var last = doc.pages.size - 1
        while (last > 0 && doc.pages[last].strokes.isEmpty()) last--
        for (i in 0..last) {
            val pg = NoteDoc.Page()
            pg.strokes.addAll(doc.pages[i].strokes)
            c.pages.add(pg)
        }
        return c
    }

    /** Saves in the background, in order. A brand-new note left empty is deleted instead. */
    fun saveAsync(doc: NoteDoc, deleteIfEmpty: Boolean = false) {
        val snap = snapshot(doc)
        io.execute {
            try {
                if (deleteIfEmpty && snap.strokeCount() == 0 && snap.title.isBlank()) delete(snap.id) else save(snap)
            } catch (_: Throwable) {
            }
        }
    }

    private fun noteFile(id: String) = File(dir, "$id.skn")
    private fun thumbFile(id: String) = File(dir, "$id.png")

    /** Lists notes (call off the main thread). Waits for pending saves so the list is current. */
    fun list(): List<Item> = run { io.submit {}.get() }.let { listNow() }

    private fun listNow(): List<Item> = dir.listFiles { f -> f.name.endsWith(".skn") }.orEmpty().mapNotNull { f ->
        try {
            val m = f.inputStream().use { NoteCodec.readMeta(it) }
            val t = thumbFile(m.id)
            Item(m.id, m.title, m.modified, m.pageCount, t.takeIf { it.exists() })
        } catch (_: Throwable) {
            null
        }
    }.sortedByDescending { it.modified }

    fun create(): NoteDoc = NoteDoc.blank(UUID.randomUUID().toString())

    fun load(id: String): NoteDoc? = try {
        noteFile(id).inputStream().use { NoteCodec.read(it) }
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
            val bmp = PageRenderer.renderPage(doc, 0, 360)
            thumbFile(doc.id).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            bmp.recycle()
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
    }

    fun duplicate(id: String) {
        val d = load(id) ?: return
        val copy = NoteDoc(UUID.randomUUID().toString())
        copy.title = (d.title.ifBlank { "Untitled" }) + " copy"
        copy.paper = d.paper
        copy.tone = d.tone
        copy.pages.addAll(d.pages)
        save(copy)
    }
}
