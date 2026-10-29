package com.ski.notes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ski.notes.data.Prefs
import com.ski.notes.engine.NoteDoc
import com.ski.notes.engine.Stroke
import com.ski.notes.ui.theme.Accent
import com.ski.notes.ui.theme.TextDim

/** Pen drawn as a little instrument with a coloured tip, like a pen tray. */
@Composable
fun PenGlyph(tool: Int, color: Color, selected: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 30.dp, height = 46.dp)) {
        val w = size.width
        val h = size.height
        val lift = if (selected) 0f else h * 0.14f
        val bodyW = w * (if (tool == Stroke.HIGHLIGHTER) 0.62f else 0.46f)
        val left = (w - bodyW) / 2f
        val bodyTop = h * 0.36f + lift
        val body = when (tool) {
            Stroke.PENCIL -> Color(0xFFE8B04A)
            Stroke.HIGHLIGHTER -> Color(0xFFE7EAF0)
            Stroke.CALLIGRAPHY -> Color(0xFF2C2F36)
            Stroke.BALLPOINT -> Color(0xFFDDE1E8)
            else -> Color(0xFF3A3F49)
        }
        // body
        drawRoundRect(body, Offset(left, bodyTop), Size(bodyW, h - bodyTop + 6f), CornerRadius(4f, 4f))
        // tip
        val tipTop = h * 0.08f + lift
        val p = Path()
        when (tool) {
            Stroke.HIGHLIGHTER -> {
                p.moveTo(left + bodyW * 0.12f, bodyTop)
                p.lineTo(left + bodyW * 0.12f, tipTop + h * 0.1f)
                p.lineTo(left + bodyW * 0.88f, tipTop)
                p.lineTo(left + bodyW * 0.88f, bodyTop)
                p.close()
                drawPath(p, color)
            }
            Stroke.PENCIL -> {
                p.moveTo(left, bodyTop)
                p.lineTo(w / 2f, tipTop)
                p.lineTo(left + bodyW, bodyTop)
                p.close()
                drawPath(p, Color(0xFFF3D9A8))
                val lead = Path()
                lead.moveTo(w / 2f - bodyW * 0.16f, tipTop + (bodyTop - tipTop) * 0.34f)
                lead.lineTo(w / 2f, tipTop)
                lead.lineTo(w / 2f + bodyW * 0.16f, tipTop + (bodyTop - tipTop) * 0.34f)
                lead.close()
                drawPath(lead, color)
            }
            Stroke.CALLIGRAPHY -> {
                p.moveTo(left, bodyTop)
                p.lineTo(left + bodyW * 0.1f, tipTop + h * 0.06f)
                p.lineTo(left + bodyW, tipTop)
                p.lineTo(left + bodyW, bodyTop)
                p.close()
                drawPath(p, color)
            }
            Stroke.FOUNTAIN -> {
                p.moveTo(left, bodyTop)
                p.quadraticTo(left, tipTop + h * 0.12f, w / 2f, tipTop)
                p.quadraticTo(left + bodyW, tipTop + h * 0.12f, left + bodyW, bodyTop)
                p.close()
                drawPath(p, color)
                drawLine(Color(0xAA000000), Offset(w / 2f, tipTop + 3f), Offset(w / 2f, tipTop + (bodyTop - tipTop) * 0.6f), 1.5f)
            }
            else -> {
                p.moveTo(left + bodyW * 0.15f, bodyTop)
                p.lineTo(w / 2f, tipTop)
                p.lineTo(left + bodyW * 0.85f, bodyTop)
                p.close()
                drawPath(p, Color(0xFFB9BFC9))
                drawCircle(color, bodyW * 0.14f, Offset(w / 2f, tipTop + 3f))
            }
        }
        // colour band on the body
        drawRect(color, Offset(left, bodyTop + h * 0.08f), Size(bodyW, h * 0.07f))
    }
}

@Composable
fun EraserGlyph(selected: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 30.dp, height = 46.dp)) {
        val lift = if (selected) 0f else size.height * 0.14f
        rotate(-18f) {
            drawRoundRect(Color(0xFFF2A3B3), Offset(size.width * 0.2f, size.height * 0.12f + lift), Size(size.width * 0.6f, size.height * 0.34f), CornerRadius(6f, 6f))
            drawRoundRect(Color(0xFF5B8DEF), Offset(size.width * 0.2f, size.height * 0.42f + lift), Size(size.width * 0.6f, size.height * 0.7f), CornerRadius(4f, 4f))
        }
    }
}

/** A colour dot; a ring shows the selected one. */
@Composable
fun Swatch(color: Color, selected: Boolean, size: Dp = 30.dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else Color(0x44FFFFFF), CircleShape)
            .padding(if (selected) 5.dp else 2.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
    )
}

val PresetColors = listOf(
    0xFF000000, 0xFF5B6270, 0xFFFFFFFF, 0xFF1F5BD6, 0xFF2BA3E0, 0xFF1E9E5A,
    0xFF8BC34A, 0xFFFFD43B, 0xFFFF9F1C, 0xFFE5383B, 0xFFE0558F, 0xFF7B4FD6,
).map { it.toInt() }

val HighlighterColors = listOf(0xFFFFE04A, 0xFF9BF06A, 0xFF6FE3F2, 0xFFFF9CC6, 0xFFFFB566, 0xFFC7A6FF).map { it.toInt() }

/** Hue / saturation / brightness picker for custom colours. */
@Composable
fun ColorPickerDialog(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var s by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    val c = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom colour") },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).background(c))
                Spacer(Modifier.height(16.dp))
                Text("Hue", color = TextDim, fontSize = 12.sp)
                Box(
                    Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(
                        Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }),
                    ),
                )
                Slider(h, { h = it }, valueRange = 0f..360f)
                Text("Saturation", color = TextDim, fontSize = 12.sp)
                Slider(s, { s = it })
                Text("Brightness", color = TextDim, fontSize = 12.sp)
                Slider(v, { v = it })
            }
        },
        confirmButton = { TextButton(onClick = { onPick(c.toArgb()) }) { Text("Use colour") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(sub, color = TextDim, fontSize = 12.sp)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun <T> RadioRow(label: String, value: T, current: T, onPick: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = value == current, onClick = { onPick(value) }).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == current, onClick = { onPick(value) })
        Text(label)
    }
}

/** S Pen behaviour. Changes apply immediately and are remembered. */
@Composable
fun SPenSettingsDialog(prefs: Prefs, onDismiss: () -> Unit, onChanged: () -> Unit = {}) {
    var side by remember { mutableStateOf(prefs.sideButton) }
    var finger by remember { mutableStateOf(prefs.fingerDraws) }
    var hold by remember { mutableStateOf(prefs.holdToStraighten) }
    var paper by remember { mutableStateOf(prefs.defaultPaper) }
    var tone by remember { mutableStateOf(prefs.defaultTone) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("S Pen settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionTitle("While holding the S Pen button")
                RadioRow("Erase", InkView.SideAction.ERASER, side) { side = it; prefs.sideButton = it; onChanged() }
                RadioRow("Lasso select", InkView.SideAction.LASSO, side) { side = it; prefs.sideButton = it; onChanged() }
                RadioRow("Highlight", InkView.SideAction.HIGHLIGHTER, side) { side = it; prefs.sideButton = it; onChanged() }
                RadioRow("Nothing", InkView.SideAction.NONE, side) { side = it; prefs.sideButton = it; onChanged() }
                SectionTitle("Writing")
                SwitchRow("Draw with finger", "Off = only the S Pen writes; fingers scroll and zoom (palm rejection)", finger) {
                    finger = it; prefs.fingerDraws = it; onChanged()
                }
                SwitchRow("Hold to draw straight line", "Pause at the end of a stroke to snap it straight", hold) {
                    hold = it; prefs.holdToStraighten = it; onChanged()
                }
                SectionTitle("New notes")
                PaperPicker(paper, tone, onPaper = { paper = it; prefs.defaultPaper = it }, onTone = { tone = it; prefs.defaultTone = it })
                SectionTitle("Gestures")
                Text("• Two-finger tap: undo   • Three-finger tap: redo\n• Pinch to zoom, drag with a finger to scroll\n• Ruler: drag with one finger, twist with two", color = TextDim, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private val paperNames = listOf("Blank", "Lined", "Grid", "Dots")
private val toneNames = listOf("White", "Cream", "Dark")

@Composable
fun PaperPicker(paper: Int, tone: Int, onPaper: (Int) -> Unit, onTone: (Int) -> Unit) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0 until NoteDoc.PAPER_COUNT) {
                PaperTile(i, tone, paper == i, paperNames[i]) { onPaper(i) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            for (t in 0 until NoteDoc.TONE_COUNT) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Swatch(Color(PageRenderer.paperColor(t)), tone == t, 34.dp) { onTone(t) }
                    Text(toneNames[t], fontSize = 11.sp, color = TextDim)
                }
            }
        }
    }
}

@Composable
private fun PaperTile(paper: Int, tone: Int, selected: Boolean, name: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(width = 48.dp, height = 64.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(if (selected) 2.5.dp else 1.dp, if (selected) Accent else Color(0x44FFFFFF), RoundedCornerShape(6.dp))
                .clickable(onClick = onClick),
        ) {
            val sc = size.width / NoteDoc.PAGE_W
            drawContext.canvas.nativeCanvas.apply {
                save()
                scale(sc, sc)
                PageRenderer.drawPaper(this, paper, tone)
                restore()
            }
        }
        Text(name, fontSize = 11.sp, color = if (selected) MaterialTheme.colorScheme.onSurface else TextDim)
    }
}
