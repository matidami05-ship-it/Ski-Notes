package com.ski.notes.data

import android.content.Context
import com.ski.notes.engine.NoteDoc
import com.ski.notes.engine.Stroke
import com.ski.notes.ui.InkView

/** Remembered pen setup and S Pen settings. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ski_notes", Context.MODE_PRIVATE)

    var fingerDraws: Boolean
        get() = sp.getBoolean("finger", false)
        set(v) = sp.edit().putBoolean("finger", v).apply()

    var sideButton: InkView.SideAction
        get() = runCatching { InkView.SideAction.valueOf(sp.getString("side", "ERASER")!!) }.getOrDefault(InkView.SideAction.ERASER)
        set(v) = sp.edit().putString("side", v.name).apply()

    var holdToStraighten: Boolean
        get() = sp.getBoolean("hold", true)
        set(v) = sp.edit().putBoolean("hold", v).apply()

    var eraserWhole: Boolean
        get() = sp.getBoolean("eraserWhole", true)
        set(v) = sp.edit().putBoolean("eraserWhole", v).apply()

    var eraserSize: Float
        get() = sp.getFloat("eraserSize", 14f)
        set(v) = sp.edit().putFloat("eraserSize", v).apply()

    var penTool: Int
        get() = sp.getInt("tool", Stroke.FOUNTAIN)
        set(v) = sp.edit().putInt("tool", v).apply()

    fun color(tool: Int, def: Int): Int = sp.getInt("color$tool", def)
    fun setColor(tool: Int, c: Int) = sp.edit().putInt("color$tool", c).apply()
    fun width(tool: Int, def: Float): Float = sp.getFloat("width$tool", def)
    fun setWidth(tool: Int, w: Float) = sp.edit().putFloat("width$tool", w).apply()

    var defaultPaper: Int
        get() = sp.getInt("paper", NoteDoc.PAPER_LINED)
        set(v) = sp.edit().putInt("paper", v).apply()

    var defaultTone: Int
        get() = sp.getInt("tone", NoteDoc.TONE_WHITE)
        set(v) = sp.edit().putInt("tone", v).apply()

    /** Custom colours the user added to the palette. */
    var customColors: List<Int>
        get() = sp.getString("custom", "")!!.split(',').mapNotNull { it.toLongOrNull()?.toInt() }
        set(v) = sp.edit().putString("custom", v.joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() }).apply()
}
