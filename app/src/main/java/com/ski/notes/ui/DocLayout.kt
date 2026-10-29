package com.ski.notes.ui

import com.ski.notes.engine.NoteDoc
import com.ski.notes.engine.RichPara
import com.ski.notes.engine.TextBox

/** Flows formatted paragraphs (from a Word file) onto note pages as editable text boxes. */
object DocLayout {
    const val MARGIN_X = 90f
    const val MARGIN_TOP = 110f
    const val MARGIN_BOTTOM = 110f
    /** Body text size in page units (about 12pt on the page). */
    const val BODY = 26f

    private class Builder {
        val text = StringBuilder()
        val runs = ArrayList<TextBox.Run>()
        fun isEmpty() = text.isEmpty()
        fun box(width: Float) = TextBox(MARGIN_X, MARGIN_TOP, width, BODY, 0xFF000000.toInt(), text.toString(), runs.toTypedArray())
        fun appendPara(p: RichPara) {
            if (text.isNotEmpty()) {
                // Small gap between paragraphs: a short blank line.
                text.append('\n')
                val s = text.length
                text.append('\n')
                runs.add(TextBox.Run(s - 1, s, 0, 0, 0.45f))
            }
            val off = text.length + if (p.bullet) 2 else 0
            if (p.bullet) text.append("• ")
            text.append(p.text.toString().replace(' ', '\n'))
            for (r in p.runs) runs.add(TextBox.Run(r.start + off, r.end + off, r.flags, r.color, r.scale))
        }
        fun clear() { text.setLength(0); runs.clear() }
    }

    fun paginate(paras: List<RichPara>): List<NoteDoc.Page> {
        val width = NoteDoc.PAGE_W - 2 * MARGIN_X
        val maxH = NoteDoc.PAGE_H - MARGIN_TOP - MARGIN_BOTTOM
        val pages = ArrayList<NoteDoc.Page>()
        val cur = Builder()

        fun flush() {
            if (cur.isEmpty()) return
            val page = NoteDoc.Page()
            page.texts.add(cur.box(width))
            pages.add(page)
            cur.clear()
        }

        for (p in paras) {
            if (p.image != null) continue
            if (p.pageBreakBefore) flush()
            val beforeText = cur.text.toString()
            val beforeRuns = ArrayList(cur.runs)
            cur.appendPara(p)
            var box = cur.box(width)
            var l = TextStyler.layout(box, NoteDoc.TONE_WHITE)
            if (l.height <= maxH) continue
            // Too tall: if the page already had text, move this paragraph to the next page first.
            if (beforeText.isNotEmpty()) {
                cur.clear()
                cur.text.append(beforeText)
                cur.runs.addAll(beforeRuns)
                flush()
                cur.appendPara(p)
                box = cur.box(width)
                l = TextStyler.layout(box, NoteDoc.TONE_WHITE)
            }
            // A single paragraph longer than a page: cut it at line boundaries.
            while (l.height > maxH) {
                val lastLine = (l.getLineForVertical(maxH.toInt()) - 1).coerceAtLeast(0)
                val cut = l.getLineEnd(lastLine).coerceIn(1, cur.text.length)
                val all = cur.text.toString()
                val allRuns = ArrayList(cur.runs)
                cur.clear()
                cur.text.append(all, 0, cut)
                for (r in allRuns) if (r.start < cut) cur.runs.add(TextBox.Run(r.start, minOf(r.end, cut), r.flags, r.color, r.scale))
                flush()
                val rest = all.substring(cut)
                cur.text.append(rest)
                for (r in allRuns) if (r.end > cut) cur.runs.add(TextBox.Run(maxOf(r.start, cut) - cut, r.end - cut, r.flags, r.color, r.scale))
                if (cur.isEmpty()) break
                box = cur.box(width)
                l = TextStyler.layout(box, NoteDoc.TONE_WHITE)
            }
        }
        flush()
        return pages
    }

    /** Base text size that Word's 11pt body text corresponds to, for export. */
    const val WORD_BODY = 26f

    /** The note's typed text and handwriting as Word paragraphs (handwriting goes in as pictures). */
    fun toParagraphs(doc: NoteDoc, inkImage: (Int) -> ByteArray?, inkSizePt: (Int) -> Pair<Int, Int>?): List<RichPara> {
        val out = ArrayList<RichPara>()
        for ((pi, page) in doc.pages.withIndex()) {
            val first = out.size
            for (t in page.texts.sortedWith(compareBy({ it.y }, { it.x }))) {
                val k = t.size / WORD_BODY
                for (p in RichPara.fromTextBox(t)) {
                    val q = RichPara()
                    q.bullet = p.bullet
                    q.text.append(p.text)
                    // Bake the box's size and colour into every character so Word shows it the same.
                    val segs = ArrayList<TextBox.Run>()
                    var pos = 0
                    val sorted = p.runs.sortedBy { it.start }
                    for (r in sorted) {
                        if (r.start > pos) segs.add(TextBox.Run(pos, r.start, 0, 0, 1f))
                        segs.add(r)
                        pos = maxOf(pos, r.end)
                    }
                    if (pos < q.text.length) segs.add(TextBox.Run(pos, q.text.length, 0, 0, 1f))
                    for (r in segs) {
                        val color = if (r.color != 0) r.color else if (t.color != 0xFF000000.toInt()) t.color else 0
                        q.runs.add(TextBox.Run(r.start, r.end, r.flags, color, (r.scale * k * 100).toInt() / 100f))
                    }
                    out.add(q)
                }
            }
            inkImage(pi)?.let { png ->
                val p = RichPara()
                p.image = png
                inkSizePt(pi)?.let { (w, h) -> p.imageW = w; p.imageH = h }
                out.add(p)
            }
            if (pi > 0 && out.size > first) out[first].pageBreakBefore = true
        }
        return out
    }
}
