package com.ski.notes.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.text.Layout
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout

/**
 * The writing surface plus the text field used for typing. The text field is laid out at page size and
 * scaled to match the zoom, so it wraps lines exactly like the finished text drawn on the page.
 */
@SuppressLint("ViewConstructor")
class NoteCanvasLayout(context: Context, val ink: InkView) : FrameLayout(context) {
    val editor: EditText = EditText(context).apply {
        background = null
        setPadding(0, 0, 0, 0)
        includeFontPadding = true
        setLineSpacing(0f, 1f)
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        isSingleLine = false
        setHorizontallyScrolling(false)
        hint = "Type here"
        setHintTextColor(0x66888888)
        highlightColor = 0x554F7CFF
        setTextColor(Color.BLACK)
        visibility = GONE
    }

    init {
        addView(ink, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(editor, LayoutParams(600, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        ink.textEditor = editor
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // The text field may be taller than the screen (it is scaled down to fit): never cap its height.
        if (editor.visibility != GONE) {
            editor.measure(
                MeasureSpec.makeMeasureSpec(editor.layoutParams.width.coerceAtLeast(1), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
        }
    }
}
