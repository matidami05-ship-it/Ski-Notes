package com.ski.notes.ui

import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import com.ski.notes.engine.TextBox
import java.util.WeakHashMap

/** Converts between text boxes and Android styled text, and lays them out for drawing. */
object TextStyler {
    /** Typing at the end of a styled run continues the style, like a word processor. */
    private const val SPAN_FLAGS = Spanned.SPAN_EXCLUSIVE_INCLUSIVE

    fun spannable(box: TextBox, tone: Int): SpannableStringBuilder =
        spannable(box.text, box.runs, tone)

    fun spannable(text: String, runs: Array<TextBox.Run>, tone: Int): SpannableStringBuilder {
        val sb = SpannableStringBuilder(text)
        val len = text.length
        for (r in runs) {
            val a = r.start.coerceIn(0, len)
            val b = r.end.coerceIn(0, len)
            if (a >= b) continue
            val bold = (r.flags and TextBox.BOLD) != 0
            val italic = (r.flags and TextBox.ITALIC) != 0
            if (bold || italic) {
                val st = if (bold && italic) Typeface.BOLD_ITALIC else if (bold) Typeface.BOLD else Typeface.ITALIC
                sb.setSpan(StyleSpan(st), a, b, SPAN_FLAGS)
            }
            if ((r.flags and TextBox.UNDERLINE) != 0) sb.setSpan(UnderlineSpan(), a, b, SPAN_FLAGS)
            if ((r.flags and TextBox.STRIKE) != 0) sb.setSpan(StrikethroughSpan(), a, b, SPAN_FLAGS)
            if (r.color != 0) sb.setSpan(ForegroundColorSpan(PageRenderer.displayColor(r.color, tone)), a, b, SPAN_FLAGS)
            if (r.scale != 1f) sb.setSpan(RelativeSizeSpan(r.scale), a, b, SPAN_FLAGS)
        }
        return sb
    }

    /** Style of one character range, used while reading spans back. */
    data class Seg(val start: Int, val end: Int, val flags: Int, val color: Int, val scale: Float)

    /** Reads style spans back into runs. {@code tone} undoes the dark-paper colour swap. */
    fun runsOf(text: Spanned, tone: Int, baseColor: Int): Array<TextBox.Run> =
        segments(text, tone, baseColor).filter { it.flags != 0 || it.color != 0 || it.scale != 1f }
            .map { TextBox.Run(it.start, it.end, it.flags, it.color, it.scale) }.toTypedArray()

    fun segments(text: Spanned, tone: Int, baseColor: Int): List<Seg> {
        val len = text.length
        if (len == 0) return emptyList()
        val cuts = sortedSetOf(0, len)
        val spans = text.getSpans(0, len, Any::class.java).filter {
            it is StyleSpan || it is UnderlineSpan || it is StrikethroughSpan || it is ForegroundColorSpan || it is RelativeSizeSpan
        }
        for (s in spans) {
            cuts.add(text.getSpanStart(s).coerceIn(0, len))
            cuts.add(text.getSpanEnd(s).coerceIn(0, len))
        }
        val list = cuts.toList()
        val out = ArrayList<Seg>()
        val shownBase = PageRenderer.displayColor(baseColor, tone)
        for (i in 0 until list.size - 1) {
            val a = list[i]
            val b = list[i + 1]
            if (a >= b) continue
            var flags = 0
            var color = 0
            var scale = 1f
            for (s in spans) {
                val ss = text.getSpanStart(s)
                val se = text.getSpanEnd(s)
                if (ss > a || se < b) continue
                when (s) {
                    is StyleSpan -> flags = flags or when (s.style) {
                        Typeface.BOLD -> TextBox.BOLD
                        Typeface.ITALIC -> TextBox.ITALIC
                        Typeface.BOLD_ITALIC -> TextBox.BOLD or TextBox.ITALIC
                        else -> 0
                    }
                    is UnderlineSpan -> flags = flags or TextBox.UNDERLINE
                    is StrikethroughSpan -> flags = flags or TextBox.STRIKE
                    is ForegroundColorSpan -> color = s.foregroundColor
                    is RelativeSizeSpan -> scale = s.sizeChange
                }
            }
            // A colour equal to the (displayed) box colour is no override at all.
            if (color == shownBase) color = 0
            else if (color != 0) color = PageRenderer.storedColor(color, tone)
            val prev = out.lastOrNull()
            if (prev != null && prev.end == a && prev.flags == flags && prev.color == color && prev.scale == scale) {
                out[out.size - 1] = prev.copy(end = b)
            } else out.add(Seg(a, b, flags, color, scale))
        }
        return out
    }

    /**
     * Rewrites the styling of [a, b) with [change] and returns the new runs. Used by the formatting bar.
     */
    fun restyle(text: Spanned, tone: Int, baseColor: Int, a: Int, b: Int, change: (Seg) -> Seg): Array<TextBox.Run> {
        val segs = segments(text, tone, baseColor)
        val out = ArrayList<Seg>()
        for (s in segs) {
            // Split each segment at a and b, transforming the middle part.
            val pieces = listOf(s.start to minOf(s.end, a), maxOf(s.start, a) to minOf(s.end, b), maxOf(s.start, b) to s.end)
            for ((idx, p) in pieces.withIndex()) {
                if (p.first >= p.second) continue
                val piece = s.copy(start = p.first, end = p.second)
                out.add(if (idx == 1) change(piece) else piece)
            }
        }
        return out.filter { it.flags != 0 || it.color != 0 || it.scale != 1f }
            .map { TextBox.Run(it.start, it.end, it.flags, it.color, it.scale) }.toTypedArray()
    }

    /** True if every character in [a, b) has the flag. */
    fun allHave(text: Spanned, tone: Int, baseColor: Int, a: Int, b: Int, flag: Int): Boolean {
        if (a >= b) return false
        for (s in segments(text, tone, baseColor)) {
            if (s.end <= a || s.start >= b) continue
            if ((s.flags and flag) == 0) return false
        }
        return true
    }

    // ------------------------------------------------------------------ layout

    fun paint(box: TextBox, tone: Int): TextPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = box.size
        color = PageRenderer.displayColor(box.color, tone)
        typeface = Typeface.DEFAULT
    }

    private class Cached(val tone: Int, val layout: StaticLayout)

    private val cache = WeakHashMap<TextBox, Cached>()

    @Synchronized
    fun layout(box: TextBox, tone: Int): StaticLayout {
        cache[box]?.takeIf { it.tone == tone }?.let { return it.layout }
        val text = spannable(box, tone)
        val l = StaticLayout.Builder.obtain(text, 0, text.length, paint(box, tone), box.w.toInt().coerceAtLeast(10))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(true)
            .setLineSpacing(0f, 1f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
        cache[box] = Cached(tone, l)
        return l
    }

    fun height(box: TextBox, tone: Int): Float = layout(box, tone).height.toFloat()
}

