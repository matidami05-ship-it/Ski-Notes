package com.ski.notes.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ski.notes.data.Exporter
import com.ski.notes.data.NoteRepository
import com.ski.notes.data.Prefs
import com.ski.notes.engine.NoteDoc
import com.ski.notes.engine.Stroke
import com.ski.notes.ui.theme.Accent
import com.ski.notes.ui.theme.Line
import com.ski.notes.ui.theme.Panel
import com.ski.notes.ui.theme.PanelHigh
import com.ski.notes.ui.theme.TextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val toolNames = listOf("Fountain pen", "Ballpoint", "Pencil", "Calligraphy", "Highlighter")
private val sizeRanges = listOf(1f..30f, 1f..20f, 1f..20f, 2f..40f, 8f..80f)
private val defaultWidths = floatArrayOf(6f, 4f, 4f, 10f, 28f)

@Composable
fun EditorScreen(id: String, isNew: Boolean, repo: NoteRepository, prefs: Prefs, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    var doc by remember { mutableStateOf<NoteDoc?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        val d = withContext(Dispatchers.IO) { repo.load(id) }
        if (d == null) loadFailed = true
        else {
            if (d.lastPageUsed()) d.pages.add(NoteDoc.Page())
            doc = d
        }
    }

    // ---- UI state mirrored into the InkView
    var mode by remember { mutableStateOf(InkView.Mode.PEN) }
    var penTool by remember { mutableIntStateOf(prefs.penTool.coerceIn(0, Stroke.TOOL_COUNT - 1)) }
    val colors = remember {
        mutableStateListOf<Int>().apply {
            for (t in 0 until Stroke.TOOL_COUNT) add(prefs.color(t, if (t == Stroke.HIGHLIGHTER) HighlighterColors[0] else android.graphics.Color.BLACK))
        }
    }
    val widths = remember { mutableStateListOf<Float>().apply { for (t in 0 until Stroke.TOOL_COUNT) add(prefs.width(t, defaultWidths[t])) } }
    var eraserWhole by remember { mutableStateOf(prefs.eraserWhole) }
    var eraserSize by remember { mutableFloatStateOf(prefs.eraserSize) }
    var fingerDraws by remember { mutableStateOf(prefs.fingerDraws) }
    var ruler by remember { mutableStateOf(false) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var selCount by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var pages by remember { mutableIntStateOf(1) }
    var title by remember { mutableStateOf("") }
    var showOptions by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showPaper by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var pickColorFor by remember { mutableStateOf<Int?>(null) } // tool index, or -1 for selection
    var customColors by remember { mutableStateOf(prefs.customColors) }
    var confirmDeletePage by remember { mutableStateOf(false) }
    var penSeen by remember { mutableStateOf(false) }
    var textEditing by remember { mutableStateOf(false) }
    var textColor by remember { mutableIntStateOf(prefs.textColor) }
    var textSize by remember { mutableFloatStateOf(prefs.textSize) }
    var busy by remember { mutableStateOf<String?>(null) }

    var saveJob by remember { mutableStateOf<Job?>(null) }

    val view = remember {
        InkView(ctx).apply {
            this.textColor = prefs.textColor
            this.textSize = prefs.textSize
            this.penTool = penTool
            for (t in 0 until Stroke.TOOL_COUNT) { this.colors[t] = colors[t]; this.widths[t] = widths[t] }
            eraserWholeStroke = eraserWhole
            eraserSizeDp = eraserSize
            this.fingerDraws = fingerDraws
            sideButton = prefs.sideButton
            holdToStraighten = prefs.holdToStraighten
        }
    }
    val canvasLayout = remember { NoteCanvasLayout(ctx, view) }

    // The attached PDF (for notes made from a PDF), rendered under the ink.
    DisposableEffect(doc) {
        val d = doc
        var pdf: com.ski.notes.data.PdfPages? = null
        if (d != null && d.hasPdf) {
            pdf = runCatching { com.ski.notes.data.PdfPages(repo.pdfFile(d.id)) }.getOrNull()
            view.pdf = pdf
        }
        onDispose {
            view.pdf = null
            pdf?.close()
        }
    }

    // Back while typing just finishes typing.
    androidx.activity.compose.BackHandler(enabled = textEditing) { view.commitText() }

    fun saveSoon() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1500)
            doc?.let { repo.saveAsync(it) }
        }
    }

    fun close() {
        view.commitText()
        saveJob?.cancel()
        doc?.let { repo.saveAsync(it, deleteIfEmpty = isNew) }
        onClose()
    }

    LaunchedEffect(doc) {
        val d = doc ?: return@LaunchedEffect
        title = d.title
        view.listener = object : InkView.Listener {
            override fun onContentChanged() = saveSoon()
            override fun onHistoryChanged(u: Boolean, r: Boolean) {
                canUndo = u
                canRedo = r
            }

            override fun onSelectionChanged(count: Int) { selCount = count }
            override fun onPageChanged(current: Int, total: Int) { page = current; pages = total }
            override fun onStylusSeen() { penSeen = true }
            override fun onTextEditing(active: Boolean) { textEditing = active }
        }
        view.setDocument(d)
    }

    // Save when the app goes to the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) { view.commitText(); saveJob?.cancel(); doc?.let { repo.saveAsync(it) } }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    fun selectTool(t: Int) {
        if (mode == InkView.Mode.PEN && penTool == t) { showOptions = !showOptions; return }
        mode = InkView.Mode.PEN
        penTool = t
        view.mode = InkView.Mode.PEN
        view.penTool = t
        prefs.penTool = t
        showOptions = false
    }

    fun setTextColor(c: Int) {
        textColor = c
        prefs.textColor = c
        view.setTextColorForEdit(c)
    }

    fun setTextSizeTo(sz: Float) {
        textSize = sz
        prefs.textSize = sz
        view.setTextSizeForEdit(sz)
    }

    fun export(label: String, mime: String, open: Boolean = false, make: (NoteDoc) -> java.io.File) {
        view.commitText()
        val d = doc ?: return
        val snap = repo.snapshot(d)
        busy = label
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { make(snap) } }
            busy = null
            val f = r.getOrNull()
            if (f == null) {
                Toast.makeText(ctx, "Couldn't create the file: ${r.exceptionOrNull()?.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
                return@launch
            }
            try {
                if (open) Exporter.openWith(ctx, f, mime) else Exporter.share(ctx, f, mime)
            } catch (_: Throwable) {
                Toast.makeText(ctx, "No app found to open it", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun setColor(c: Int) {
        val t = penTool
        colors[t] = c
        view.colors[t] = c
        prefs.setColor(t, c)
        if (mode != InkView.Mode.PEN) { mode = InkView.Mode.PEN; view.mode = InkView.Mode.PEN }
    }

    androidx.compose.material3.Scaffold(containerColor = Color(0xFF2A2D33)) { _ ->
        Column(Modifier.fillMaxSize()) {
            // ---------------- top bar
            Surface(color = Panel) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { close() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    BasicTextField(
                        value = title,
                        onValueChange = {
                            title = it
                            doc?.let { d -> d.title = it; d.modified = System.currentTimeMillis(); saveSoon() }
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                        cursorBrush = SolidColor(Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); view.requestFocus() }),
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        decorationBox = { inner ->
                            if (title.isEmpty()) Text("Untitled", color = TextDim, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            inner()
                        },
                    )
                    Text("${page + 1} / $pages", color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 6.dp))
                    IconButton(onClick = { view.undo() }, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(onClick = { view.redo() }, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    Box {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(text = { Text("Paper style") }, onClick = { showMenu = false; showPaper = true })
                            DropdownMenuItem(text = { Text("Add page") }, onClick = { showMenu = false; view.addPage() })
                            DropdownMenuItem(text = { Text("Clear this page") }, onClick = { showMenu = false; view.clearCurrentPage() })
                            DropdownMenuItem(text = { Text("Delete this page") }, onClick = { showMenu = false; confirmDeletePage = true })
                            DropdownMenuItem(text = { Text("Fit page to screen") }, onClick = { showMenu = false; view.resetZoom() })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Share as PDF") }, onClick = {
                                showMenu = false
                                export("Making PDF…", "application/pdf") { Exporter.pdf(ctx, it, repo.pdfFile(it.id)) }
                            })
                            DropdownMenuItem(text = { Text("Share as Word (.docx)") }, onClick = {
                                showMenu = false
                                export("Making Word file…", "application/vnd.openxmlformats-officedocument.wordprocessingml.document") { Exporter.docx(ctx, it) }
                            })
                            DropdownMenuItem(text = { Text("Open in Word / other app") }, onClick = {
                                showMenu = false
                                export("Making Word file…", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", open = true) { Exporter.docx(ctx, it) }
                            })
                            DropdownMenuItem(text = { Text("Share this page as image") }, onClick = {
                                showMenu = false
                                val p = view.currentPage()
                                export("Making image…", "image/png") { snap ->
                                    val idx = p.coerceAtMost(snap.pages.size - 1)
                                    val pg = snap.pages[idx]
                                    val bg = if (pg.pdfPage >= 0 && snap.hasPdf) {
                                        val pp = com.ski.notes.data.PdfPages(repo.pdfFile(snap.id))
                                        try { pp.renderBlocking(pg.pdfPage, 1654) } finally { pp.close() }
                                    } else null
                                    Exporter.pagePng(ctx, snap, idx, bg)
                                }
                            })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("S Pen settings") }, onClick = { showMenu = false; showSettings = true })
                        }
                    }
                }
            }

            // ---------------- canvas
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (doc == null) {
                    if (loadFailed) Text("This note couldn't be opened.", color = TextDim, modifier = Modifier.align(Alignment.Center))
                    else CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    AndroidView(factory = { canvasLayout }, modifier = Modifier.fillMaxSize())
                }

                // selection actions
                androidx.compose.animation.AnimatedVisibility(selCount > 0, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)) {
                    Surface(color = PanelHigh, shape = RoundedCornerShape(24.dp), shadowElevation = 6.dp) {
                        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("$selCount selected", fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp))
                            IconButton(onClick = { pickColorFor = -1 }) { Icon(Icons.Default.Palette, "Recolour") }
                            IconButton(onClick = { view.duplicateSelection() }) { Icon(Icons.Default.ContentCopy, "Duplicate") }
                            IconButton(onClick = { view.deleteSelection() }) { Icon(Icons.Default.Delete, "Delete") }
                            TextButton(onClick = { view.clearSelection() }) { Text("Done") }
                        }
                    }
                }

                // "S Pen only" hint the first time a finger is the only input
                busy?.let { msg ->
                    Surface(color = PanelHigh, shape = RoundedCornerShape(14.dp), shadowElevation = 6.dp, modifier = Modifier.align(Alignment.Center)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(msg)
                        }
                    }
                }

                if (!penSeen && !fingerDraws && doc != null && canUndo.not() && mode != InkView.Mode.TEXT) {
                    Surface(
                        color = PanelHigh, shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 420.dp),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Write with your S Pen. No pen? Turn on finger drawing →", fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                fingerDraws = true; view.fingerDraws = true; prefs.fingerDraws = true
                            }) { Text("Turn on") }
                        }
                    }
                }

                // tool options popover
                androidx.compose.animation.AnimatedVisibility(showOptions, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)) {
                    Surface(color = PanelHigh, shape = RoundedCornerShape(18.dp), shadowElevation = 8.dp, modifier = Modifier.widthIn(max = 460.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            when (mode) {
                                InkView.Mode.ERASER -> {
                                    Text("Eraser", fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(eraserWhole, { eraserWhole = true; view.eraserWholeStroke = true; prefs.eraserWhole = true }, label = { Text("Whole strokes") })
                                        FilterChip(!eraserWhole, { eraserWhole = false; view.eraserWholeStroke = false; prefs.eraserWhole = false }, label = { Text("Area") })
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text("Size", color = TextDim, fontSize = 12.sp)
                                    Slider(eraserSize, { eraserSize = it; view.eraserSizeDp = it; prefs.eraserSize = it }, valueRange = 4f..60f)
                                }
                                InkView.Mode.TEXT -> {
                                    Text("Text", fontWeight = FontWeight.SemiBold)
                                    Text("Tap anywhere on the page to type. Tap text to edit it.", color = TextDim, fontSize = 13.sp)
                                    Spacer(Modifier.height(8.dp))
                                    TextSizeChips(textSize) { setTextSizeTo(it) }
                                }
                                InkView.Mode.LASSO -> {
                                    Text("Lasso", fontWeight = FontWeight.SemiBold)
                                    Text("Circle strokes to select them. Drag the selection to move it, drag the corner dot to resize.", color = TextDim, fontSize = 13.sp)
                                }
                                InkView.Mode.PEN -> {
                                    val t = penTool
                                    Text(toolNames[t], fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(6.dp))
                                    StrokePreview(t, Color(colors[t]), widths[t])
                                    Text("Thickness", color = TextDim, fontSize = 12.sp)
                                    Slider(widths[t], {
                                        widths[t] = it; view.widths[t] = it; prefs.setWidth(t, it)
                                    }, valueRange = sizeRanges[t])
                                }
                            }
                        }
                    }
                }
            }

            // ---------------- bottom toolbar
            Surface(color = Panel) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
                    if (textEditing) {
                        TextFormatBar(
                            size = textSize,
                            onFlag = { view.toggleTextFlag(it) },
                            onBullets = { view.toggleBullets() },
                            onSize = { setTextSizeTo(it) },
                            onDelete = { view.deleteEditingText() },
                            onDone = { view.commitText() },
                        )
                    } else Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        for (t in 0 until Stroke.TOOL_COUNT) {
                            Box(Modifier.clickable { selectTool(t) }.padding(horizontal = 6.dp, vertical = 2.dp)) {
                                PenGlyph(t, Color(colors[t]), selected = mode == InkView.Mode.PEN && penTool == t)
                            }
                        }
                        Box(Modifier.clickable {
                            if (mode == InkView.Mode.ERASER) showOptions = !showOptions
                            else { mode = InkView.Mode.ERASER; view.mode = InkView.Mode.ERASER; showOptions = false }
                        }.padding(horizontal = 6.dp, vertical = 2.dp)) {
                            EraserGlyph(selected = mode == InkView.Mode.ERASER)
                        }
                        Spacer(Modifier.width(6.dp))
                        ToolIcon(selected = mode == InkView.Mode.TEXT, onClick = {
                            if (mode == InkView.Mode.TEXT) showOptions = !showOptions
                            else { mode = InkView.Mode.TEXT; view.mode = InkView.Mode.TEXT; showOptions = false }
                        }) { Icon(Icons.Default.TextFields, "Type text") }
                        ToolIcon(selected = mode == InkView.Mode.LASSO, onClick = {
                            if (mode == InkView.Mode.LASSO) showOptions = !showOptions
                            else { mode = InkView.Mode.LASSO; view.mode = InkView.Mode.LASSO; showOptions = false }
                        }) { Icon(Icons.Outlined.Gesture, "Lasso") }
                        ToolIcon(selected = ruler, onClick = { ruler = !ruler; view.rulerVisible = ruler }) {
                            Icon(Icons.Default.Straighten, "Ruler")
                        }
                        ToolIcon(selected = fingerDraws, onClick = {
                            fingerDraws = !fingerDraws; view.fingerDraws = fingerDraws; prefs.fingerDraws = fingerDraws
                            Toast.makeText(ctx, if (fingerDraws) "Finger drawing on" else "S Pen only — fingers scroll and zoom", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Default.TouchApp, "Draw with finger") }
                    }
                    HorizontalDivider(color = Line)
                    // colours for the current pen
                    val forText = mode == InkView.Mode.TEXT || textEditing
                    val palette = if (!forText && penTool == Stroke.HIGHLIGHTER) HighlighterColors else PresetColors
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val current = if (forText) textColor else colors[penTool]
                        for (c in palette + customColors) {
                            Swatch(Color(c), selected = (forText || mode == InkView.Mode.PEN) && current == c) {
                                if (forText) setTextColor(c) else setColor(c)
                            }
                        }
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(PanelHigh).clickable { pickColorFor = if (forText) -2 else penTool },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.Add, "Custom colour", modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }

    pickColorFor?.let { target ->
        ColorPickerDialog(
            initial = if (target >= 0) colors[target] else if (target == -2) textColor else android.graphics.Color.BLACK,
            onDismiss = { pickColorFor = null },
            onPick = { c ->
                pickColorFor = null
                if (target == -1) view.recolorSelection(c)
                else if (target == -2) {
                    setTextColor(c)
                    if (c !in PresetColors && c !in customColors) {
                        customColors = (listOf(c) + customColors).take(8)
                        prefs.customColors = customColors
                    }
                } else {
                    setColor(c)
                    if (c !in PresetColors && c !in HighlighterColors && c !in customColors) {
                        customColors = (listOf(c) + customColors).take(8)
                        prefs.customColors = customColors
                    }
                }
            },
        )
    }

    if (showPaper) {
        val d = doc
        if (d != null) {
            var p by remember { mutableIntStateOf(d.paper) }
            var tn by remember { mutableIntStateOf(d.tone) }
            AlertDialog(
                onDismissRequest = { showPaper = false },
                title = { Text("Paper") },
                text = {
                    PaperPicker(p, tn, onPaper = { p = it; d.paper = it; view.paperChanged() }, onTone = { tn = it; d.tone = it; view.paperChanged() })
                },
                confirmButton = { TextButton(onClick = { showPaper = false }) { Text("Done") } },
            )
        }
    }

    if (showSettings) {
        SPenSettingsDialog(prefs = prefs, onDismiss = { showSettings = false }, onChanged = {
            view.sideButton = prefs.sideButton
            view.holdToStraighten = prefs.holdToStraighten
            fingerDraws = prefs.fingerDraws
            view.fingerDraws = fingerDraws
        })
    }

    if (confirmDeletePage) {
        AlertDialog(
            onDismissRequest = { confirmDeletePage = false },
            title = { Text("Delete page ${page + 1}?") },
            text = { Text("Undo history for this note will be cleared.") },
            confirmButton = {
                TextButton(onClick = { confirmDeletePage = false; view.deleteCurrentPage() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDeletePage = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ToolIcon(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 3.dp, vertical = 6.dp)
            .size(42.dp)
            .clip(CircleShape)
            .background(if (selected) Accent.copy(alpha = 0.25f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides if (selected) Accent else MaterialTheme.colorScheme.onSurface,
        ) { content() }
    }
}

/** A sample squiggle drawn with the real pen engine at the chosen size. */
@Composable
private fun StrokePreview(tool: Int, color: Color, width: Float) {
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(10.dp)).background(Color.White),
    ) {
        val s = Stroke(tool, android.graphics.Color.argb(255, (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()), width)
        val w = size.width
        val h = size.height
        val n = 80
        for (i in 0..n) {
            val t = i / n.toFloat()
            val x = w * 0.08f + t * w * 0.84f
            val y = h / 2f + kotlin.math.sin(t * 6.28f * 1.5f) * h * 0.22f
            val p = 0.25f + 0.75f * kotlin.math.sin(t * 3.14f)
            s.add(x, y, p)
        }
        PageRenderer.drawStroke(drawContext.canvas.nativeCanvas, s, NoteDoc.TONE_WHITE, PageRenderer.buildPath(s))
    }
}

private val textSizes = listOf("Small" to 26f, "Normal" to 36f, "Large" to 52f, "Heading" to 72f)

@Composable
private fun TextSizeChips(current: Float, onPick: (Float) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((name, sz) in textSizes) FilterChip(current == sz, { onPick(sz) }, label = { Text(name) })
    }
}

/** Shown while typing: formatting for the selection (or the word at the cursor). */
@Composable
private fun TextFormatBar(
    size: Float,
    onFlag: (Int) -> Unit,
    onBullets: () -> Unit,
    onSize: (Float) -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    var sizeMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FormatButton("B", FontWeight.Bold) { onFlag(com.ski.notes.engine.TextBox.BOLD) }
        FormatButton("I", italic = true) { onFlag(com.ski.notes.engine.TextBox.ITALIC) }
        FormatButton("U", underline = true) { onFlag(com.ski.notes.engine.TextBox.UNDERLINE) }
        FormatButton("S", strike = true) { onFlag(com.ski.notes.engine.TextBox.STRIKE) }
        IconButton(onClick = onBullets) { Icon(Icons.AutoMirrored.Filled.FormatListBulleted, "Bullets") }
        Box {
            TextButton(onClick = { sizeMenu = true }) {
                Text(textSizes.firstOrNull { it.second == size }?.first ?: "${size.toInt()}")
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = sizeMenu, onDismissRequest = { sizeMenu = false }) {
                for ((name, sz) in textSizes) {
                    DropdownMenuItem(text = { Text(name, fontSize = (12 + sz / 6).sp) }, onClick = { sizeMenu = false; onSize(sz) })
                }
            }
        }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete text box") }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onDone) { Text("Done") }
    }
}

@Composable
private fun FormatButton(
    label: String,
    weight: FontWeight = FontWeight.Normal,
    italic: Boolean = false,
    underline: Boolean = false,
    strike: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 18.sp,
            fontWeight = if (label == "B") FontWeight.Bold else weight,
            fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
            textDecoration = when {
                underline -> androidx.compose.ui.text.style.TextDecoration.Underline
                strike -> androidx.compose.ui.text.style.TextDecoration.LineThrough
                else -> null
            },
        )
    }
}
