package com.ski.notes.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import com.ski.notes.engine.Eraser
import com.ski.notes.engine.History
import com.ski.notes.engine.Lasso
import com.ski.notes.engine.LineAssist
import com.ski.notes.engine.NoteDoc
import com.ski.notes.engine.Outline
import com.ski.notes.engine.Ruler
import com.ski.notes.engine.Stroke
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The writing surface. Built around the S Pen:
 *  - pressure-sensitive ink, every historical sample used, unbuffered input dispatch for low latency,
 *    incremental rendering and a short predicted tail so ink stays glued to the nib;
 *  - the S Pen side button (or the eraser end of a stylus) switches to a configurable tool while held;
 *  - hover shows a brush-size preview and highlights ruler edges the pen will snap to;
 *  - palm rejection: with "draw with finger" off, only the pen inks and fingers pan / zoom;
 *  - a ruler for perfectly straight lines, and "hold at the end of a stroke" to straighten it.
 */
@SuppressLint("ViewConstructor")
class InkView(context: Context) : View(context) {

    interface Listener {
        fun onContentChanged()
        fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean)
        fun onSelectionChanged(count: Int)
        fun onPageChanged(current: Int, total: Int)
        fun onStylusSeen()
    }

    enum class Mode { PEN, ERASER, LASSO }
    enum class SideAction { ERASER, LASSO, HIGHLIGHTER, NONE }

    var listener: Listener? = null

    // ---------------------------------------------------------------- document

    var doc: NoteDoc = NoteDoc.blank("empty")
        private set
    var history = History(doc, 300)
        private set

    fun setDocument(d: NoteDoc) {
        doc = d
        if (doc.pages.isEmpty()) doc.pages.add(NoteDoc.Page())
        history = History(d, 300)
        selection = null
        fitted = false
        if (width > 0) fitToWidth()
        sceneDirty()
        notifyHistory()
        notifyPage()
    }

    // ---------------------------------------------------------------- tool settings (set by the UI)

    var mode = Mode.PEN
        set(v) { field = v; if (v != Mode.LASSO) clearSelection(); invalidate() }
    var penTool = Stroke.FOUNTAIN
    val colors = IntArray(Stroke.TOOL_COUNT) { Color.BLACK }.also { it[Stroke.HIGHLIGHTER] = 0xFFFFE04A.toInt() }
    /** Base widths in page units. */
    val widths = floatArrayOf(6f, 4f, 4f, 10f, 28f)
    var eraserWholeStroke = true
    /** Eraser radius in screen dp. */
    var eraserSizeDp = 14f
    var fingerDraws = false
    var sideButton = SideAction.ERASER
    var holdToStraighten = true

    var rulerVisible = false
        set(v) {
            field = v
            if (v) placeRulerIfNeeded()
            invalidate()
        }

    // ---------------------------------------------------------------- view transform

    private val density = resources.displayMetrics.density
    private val gap = 48f
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var fitScale = 1f
    private var fitted = false
    private val viewMatrix = Matrix()

    private fun pageTop(i: Int) = i * (NoteDoc.PAGE_H + gap)
    private fun docHeight() = doc.pages.size * (NoteDoc.PAGE_H + gap) - gap
    private fun toDocX(sx: Float) = (sx - tx) / scale
    private fun toDocY(sy: Float) = (sy - ty) / scale
    private fun toScreenX(dx: Float) = dx * scale + tx
    private fun toScreenY(dy: Float) = dy * scale + ty
    private fun pageAt(docY: Float): Int =
        floor(docY / (NoteDoc.PAGE_H + gap)).toInt().coerceIn(0, doc.pages.size - 1)

    private fun updateMatrix() {
        viewMatrix.reset()
        viewMatrix.postScale(scale, scale)
        viewMatrix.postTranslate(tx, ty)
    }

    private fun fitToWidth() {
        val pad = 12 * density
        fitScale = (width - 2 * pad) / NoteDoc.PAGE_W
        // On a wide tablet don't blow the page up past roughly 1.4× its real size.
        fitScale = min(fitScale, height * 1.35f / NoteDoc.PAGE_H * 1.6f).coerceAtLeast(0.05f)
        scale = fitScale
        tx = (width - NoteDoc.PAGE_W * scale) / 2f
        ty = pad
        fitted = true
        clampPan()
    }

    private fun clampPan() {
        val pad = 12 * density
        val cw = NoteDoc.PAGE_W * scale
        tx = if (cw <= width - 2 * pad) (width - cw) / 2f else tx.coerceIn(width - cw - pad, pad)
        val ch = docHeight() * scale
        val bottomRoom = height * 0.4f
        ty = if (ch <= height - 2 * pad) pad else ty.coerceIn(height - ch - bottomRoom, pad)
        updateMatrix()
    }

    // ---------------------------------------------------------------- caches

    private var scene: Bitmap? = null
    private var sceneCanvas: Canvas? = null
    private val sceneMatrix = Matrix()
    private val sceneInverse = Matrix()
    private val drawMatrix = Matrix()
    private var sceneValid = false
    private var live: Bitmap? = null
    private var liveCanvas: Canvas? = null

    private val desk = 0xFF2A2D33.toInt()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        scene?.recycle(); live?.recycle()
        scene = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { sceneCanvas = Canvas(it) }
        live = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { liveCanvas = Canvas(it) }
        if (!fitted) fitToWidth() else clampPan()
        ruler?.let { it.length = 2.4f * max(w, h) }
        sceneDirty()
    }

    private fun sceneDirty() {
        sceneValid = false
        invalidate()
    }

    /** Hidden while being moved (drawn live on top instead). */
    private var hidden: Set<Stroke> = emptySet()
    /** While erasing, the page being erased shows this working list. */
    private var eraseOverride: EraseSession? = null

    private fun renderScene(dirty: RectF? = null) {
        val c = sceneCanvas ?: return
        c.save()
        if (dirty != null) c.clipRect(dirty)
        c.drawColor(desk, BlendMode.SRC)
        c.concat(viewMatrix)
        val top = toDocY(dirty?.top ?: 0f)
        val bottom = toDocY(dirty?.bottom ?: height.toFloat())
        for (i in doc.pages.indices) {
            val pt = pageTop(i)
            if (pt > bottom || pt + NoteDoc.PAGE_H < top) continue
            c.save()
            c.translate(0f, pt)
            shadowPaint.color = 0x40000000
            c.drawRect(-3f / scale, 3f / scale, NoteDoc.PAGE_W + 3f / scale, NoteDoc.PAGE_H + 6f / scale, shadowPaint)
            val strokes = eraseOverride?.takeIf { it.page == i }?.working ?: doc.pages[i].strokes
            PageRenderer.drawPage(c, doc, strokes, hidden.ifEmpty { null })
            c.restore()
        }
        c.restore()
        sceneMatrix.set(viewMatrix)
        sceneMatrix.invert(sceneInverse)
        sceneValid = true
    }

    /** Re-renders only the screen area covering a page-space box (fast path for erasing). */
    private fun renderPageArea(page: Int, minX: Float, minY: Float, maxX: Float, maxY: Float) {
        if (!sceneValid || !sameAsScene()) { sceneDirty(); return }
        val pt = pageTop(page)
        val r = RectF(toScreenX(minX) - 4, toScreenY(minY + pt) - 4, toScreenX(maxX) + 4, toScreenY(maxY + pt) + 4)
        renderScene(r)
        invalidate()
    }

    private fun sameAsScene(): Boolean = sceneMatrix == viewMatrix

    private val shadowPaint = Paint()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val livePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val rerender = Runnable { sceneDirty() }

    /** Called after every viewport change: draw the stale cache scaled now, re-render shortly after. */
    private fun viewportChanged() {
        updateMatrix()
        removeCallbacks(rerender)
        postDelayed(rerender, 90)
        notifyPage()
        invalidate()
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        if (!sceneValid) renderScene()
        canvas.drawColor(desk)
        scene?.let { bmp ->
            if (sameAsScene()) canvas.drawBitmap(bmp, 0f, 0f, null)
            else {
                drawMatrix.set(sceneInverse)
                drawMatrix.postConcat(viewMatrix)
                canvas.drawBitmap(bmp, drawMatrix, bmpPaint)
            }
        }
        drawLiveInk(canvas)
        drawSelection(canvas)
        drawLasso(canvas)
        drawRuler(canvas)
        drawCursor(canvas)
    }

    // ---------------------------------------------------------------- input: classification

    private enum class Act { NONE, INK, ERASE, LASSO, MOVE_SEL, SCALE_SEL, GESTURE, RULER }

    private var act = Act.NONE
    private var activeId = -1
    private var stylusSeen = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                beginPointer(e, 0)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val isPen = isPenTool(e.getToolType(i))
                when (act) {
                    Act.INK, Act.ERASE, Act.LASSO, Act.MOVE_SEL, Act.SCALE_SEL -> {
                        // A finger arriving while a finger was inking means "this is a gesture": drop the stroke.
                        val activeIsPen = e.findPointerIndex(activeId).let { it >= 0 && isPenTool(e.getToolType(it)) }
                        if (!activeIsPen && !isPen) {
                            cancelCurrent()
                            startGesture(e)
                        }
                        // otherwise: palm / extra finger while the pen writes -> ignore
                    }
                    Act.GESTURE -> if (isPen) { endGesture(null); beginPointer(e, i) } else restartGesture(e)
                    Act.RULER -> if (!isPen) restartRuler(e)
                    Act.NONE -> beginPointer(e, i)
                }
            }
            MotionEvent.ACTION_MOVE -> when (act) {
                Act.INK -> inkMove(e)
                Act.ERASE -> eraseMove(e)
                Act.LASSO -> lassoMove(e)
                Act.MOVE_SEL, Act.SCALE_SEL -> selMove(e)
                Act.GESTURE -> gestureMove(e)
                Act.RULER -> rulerMove(e)
                Act.NONE -> {}
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val canceled = (e.flags and MotionEvent.FLAG_CANCELED) != 0
                when (act) {
                    Act.GESTURE -> restartGesture(e, excluding = i)
                    Act.RULER -> restartRuler(e, excluding = i)
                    else -> if (id == activeId) { if (canceled) cancelCurrent() else finishCurrent(e, i) }
                }
            }
            MotionEvent.ACTION_UP -> {
                val canceled = (e.flags and MotionEvent.FLAG_CANCELED) != 0
                when (act) {
                    Act.GESTURE -> endGesture(e)
                    Act.RULER -> endRuler()
                    else -> if (canceled) cancelCurrent() else finishCurrent(e, 0)
                }
                act = Act.NONE
                activeId = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                if (act == Act.GESTURE) endGesture(null) else if (act == Act.RULER) endRuler() else cancelCurrent()
                act = Act.NONE
                activeId = -1
            }
        }
        return true
    }

    private fun isPenTool(t: Int) = t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER

    private fun beginPointer(e: MotionEvent, i: Int) {
        val tool = e.getToolType(i)
        val x = e.getX(i)
        val y = e.getY(i)
        activeId = e.getPointerId(i)
        if (isPenTool(tool)) {
            lastPenTime = SystemClock.uptimeMillis()
            if (!stylusSeen) { stylusSeen = true; listener?.onStylusSeen() }
            requestUnbufferedDispatch(e)
            val sideHeld = tool == MotionEvent.TOOL_TYPE_ERASER ||
                (e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0
            startTool(e, i, x, y, sideHeld, pressure = e.getPressure(i), eraserEnd = tool == MotionEvent.TOOL_TYPE_ERASER)
            return
        }
        // Finger. While the S Pen is hovering or just lifted, a touch is almost certainly the palm: ignore it.
        if (!fingerDraws && SystemClock.uptimeMillis() - lastPenTime < 350) { act = Act.NONE; activeId = -1; return }
        if (rulerVisible && ruler?.contains(x, y, 8 * density) == true) {
            startRuler(e)
            return
        }
        if (fingerDraws && e.pointerCount == 1) {
            requestUnbufferedDispatch(e)
            startTool(e, i, x, y, false, pressure = 0.5f, eraserEnd = false)
        } else {
            startGesture(e)
        }
    }

    private fun startTool(e: MotionEvent, i: Int, x: Float, y: Float, sideHeld: Boolean, pressure: Float, eraserEnd: Boolean) {
        // Pen on the current selection: move it (or scale it from the corner handle).
        selection?.let { sel ->
            val b = selScreenBounds()
            val handle = 28 * density
            if (hypot(x - b.right, y - b.bottom) < handle) { startSelDrag(x, y, scaling = true); return }
            if (b.contains(x, y)) { startSelDrag(x, y, scaling = false); return }
            if (!sideHeld) clearSelection()
        }
        var m = mode
        var tool = penTool
        if (eraserEnd) m = Mode.ERASER
        else if (sideHeld) when (sideButton) {
            SideAction.ERASER -> m = Mode.ERASER
            SideAction.LASSO -> m = Mode.LASSO
            SideAction.HIGHLIGHTER -> { m = Mode.PEN; tool = Stroke.HIGHLIGHTER }
            SideAction.NONE -> {}
        }
        when (m) {
            Mode.PEN -> startInk(x, y, pressure, tool, e.eventTime)
            Mode.ERASER -> startErase(x, y)
            Mode.LASSO -> startLasso(x, y)
        }
    }

    private fun finishCurrent(e: MotionEvent, i: Int) {
        when (act) {
            Act.INK -> inkUp(e)
            Act.ERASE -> eraseUp()
            Act.LASSO -> lassoUp()
            Act.MOVE_SEL, Act.SCALE_SEL -> selUp()
            else -> {}
        }
        act = Act.NONE
    }

    private fun cancelCurrent() {
        when (act) {
            Act.INK -> { inking = null; removeCallbacks(holdCheck); invalidate() }
            Act.ERASE -> { eraseOverride = null; sceneDirty() }
            Act.LASSO -> { lassoN = 0; invalidate() }
            Act.MOVE_SEL, Act.SCALE_SEL -> { selDx = 0f; selDy = 0f; selScale = 1f; invalidate() }
            else -> {}
        }
        act = Act.NONE
    }

    // ---------------------------------------------------------------- ink

    private var inking: Stroke? = null
    private var inkPage = 0
    private var outline: Outline.Incremental? = null
    private var rulerEdge = 0
    private var straightened = false
    private val hold = LineAssist.HoldDetector(6f, 450L)
    private var startX = 0f
    private var startY = 0f
    private var lastSX = 0f
    private var lastSY = 0f
    private var lastT = 0L
    private var velX = 0f
    private var velY = 0f
    private val snapBuf = FloatArray(2)
    private val livePath = Path()
    private val liveFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val liveSink = Outline.Sink { pts, n ->
        livePath.moveTo(pts[0], pts[1])
        for (k in 1 until n) livePath.lineTo(pts[2 * k], pts[2 * k + 1])
        livePath.close()
    }

    private fun startInk(sx: Float, sy: Float, pressure: Float, tool: Int, t: Long) {
        clampPan()
        if (!sceneValid || !sameAsScene()) renderScene()
        inkPage = pageAt(toDocY(sy))
        val s = Stroke(tool, colors[tool], widths[tool])
        inking = s
        outline = Outline.Incremental(s, liveSink)
        straightened = false
        rulerEdge = if (rulerVisible) ruler?.edgeFor(sx, sy, 22 * density) ?: 0 else 0
        hold.reset()
        live?.eraseColor(Color.TRANSPARENT)
        liveCanvas?.let { c ->
            c.setMatrix(viewMatrix)
            c.translate(0f, pageTop(inkPage))
        }
        velX = 0f; velY = 0f
        lastT = t
        act = Act.INK
        addInkPoint(sx, sy, pressure, t)
        startX = s.x[0]; startY = s.y[0]
        if (holdToStraighten && rulerEdge == 0) postDelayed(holdCheck, 60)
    }

    private fun inkMove(e: MotionEvent) {
        val i = e.findPointerIndex(activeId)
        if (i < 0) return
        val finger = !isPenTool(e.getToolType(i))
        for (h in 0 until e.historySize) {
            addInkPoint(e.getHistoricalX(i, h), e.getHistoricalY(i, h),
                if (finger) 0.5f else e.getHistoricalPressure(i, h), e.getHistoricalEventTime(h))
        }
        addInkPoint(e.getX(i), e.getY(i), if (finger) 0.5f else e.getPressure(i), e.eventTime)
        flushLive()
        invalidate()
    }

    private fun addInkPoint(sx0: Float, sy0: Float, pressure: Float, t: Long) {
        val s = inking ?: return
        var sx = sx0
        var sy = sy0
        val dt = (t - lastT).coerceAtLeast(1L)
        if (s.n > 0) {
            velX = velX * 0.5f + (sx0 - lastSX) / dt * 0.5f
            velY = velY * 0.5f + (sy0 - lastSY) / dt * 0.5f
        }
        lastSX = sx0; lastSY = sy0; lastT = t
        val r = ruler
        if (rulerEdge != 0 && r != null) {
            // Lock to the ruler edge, sitting the ink just outside it.
            val half = Outline.halfWidth(s.tool, s.width, 0.6f, 0.0) * scale
            r.snap(sx, sy, rulerEdge, half, snapBuf)
            sx = snapBuf[0]; sy = snapBuf[1]
        }
        val px = toDocX(sx)
        val py = toDocY(sy) - pageTop(inkPage)
        if (straightened) {
            LineAssist.snapEnd(startX, startY, px, py, 4f, snapBuf)
            s.setLine(startX, startY, snapBuf[0], snapBuf[1], straightPressure)
            rebuildLive()
            return
        }
        hold.feed(sx0, sy0, t)
        s.add(px, py, pressure)
    }

    private var straightPressure = 0.6f

    private val holdCheck = object : Runnable {
        override fun run() {
            val s = inking ?: return
            if (act != Act.INK || straightened) return
            if (hold.held(SystemClock.uptimeMillis()) && LineAssist.worthStraightening(s, 30f / scale)) {
                straightened = true
                straightPressure = s.meanPressure()
                val ex = s.x[s.n - 1]; val ey = s.y[s.n - 1]
                LineAssist.snapEnd(startX, startY, ex, ey, 4f, snapBuf)
                s.setLine(startX, startY, snapBuf[0], snapBuf[1], straightPressure)
                rebuildLive()
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                invalidate()
                return
            }
            postDelayed(this, 50)
        }
    }

    private fun rebuildLive() {
        val s = inking ?: return
        live?.eraseColor(Color.TRANSPARENT)
        outline = Outline.Incremental(s, liveSink)
        flushLive()
    }

    /** Paints newly-final pieces of the live stroke into the live layer (opaque; alpha applied when shown). */
    private fun flushLive() {
        val s = inking ?: return
        val c = liveCanvas ?: return
        val color = PageRenderer.displayColor(s.color, doc.tone) or 0xFF000000.toInt()
        if (s.tool == Stroke.HIGHLIGHTER) {
            // The highlighter is a fat round line; redraw its centre line in full (cheap).
            live?.eraseColor(Color.TRANSPARENT)
            tailPaint.color = color
            tailPaint.strokeWidth = s.width
            tailPaint.alpha = 255
            c.drawPath(PageRenderer.buildPath(s), tailPaint)
            return
        }
        livePath.rewind()
        if (straightened) outline?.finish() else outline?.update()
        if (!livePath.isEmpty) {
            liveFill.color = color
            c.drawPath(livePath, liveFill)
        }
    }

    private fun drawLiveInk(canvas: Canvas) {
        val s = inking ?: return
        val bmp = live ?: return
        val color = PageRenderer.displayColor(s.color, doc.tone)
        livePaint.alpha = (255 * PageRenderer.toolAlpha(s.tool)).toInt()
        livePaint.blendMode = if (s.tool == Stroke.HIGHLIGHTER && doc.tone != NoteDoc.TONE_DARK) BlendMode.MULTIPLY else null
        canvas.drawBitmap(bmp, 0f, 0f, livePaint)
        if (s.tool == Stroke.HIGHLIGHTER || s.n == 0 || straightened) return
        // Tail: from the last finished point to the pen, plus a short prediction so ink keeps up with the nib.
        val o = outline ?: return
        val from = o.lastEmitted().coerceAtLeast(0)
        canvas.save()
        canvas.concat(viewMatrix)
        canvas.translate(0f, pageTop(inkPage))
        tailPaint.color = color
        tailPaint.alpha = (255 * PageRenderer.toolAlpha(s.tool)).toInt()
        tailPaint.strokeWidth = o.tailRadius() * 2f
        livePath.rewind()
        livePath.moveTo(s.x[from], s.y[from])
        for (k in from + 1 until s.n) livePath.lineTo(s.x[k], s.y[k])
        if (rulerEdge == 0 && act == Act.INK) {
            val ahead = 14f // ms
            var dx = velX * ahead
            var dy = velY * ahead
            val d = hypot(dx, dy)
            val cap = 28 * density
            if (d > cap) { dx *= cap / d; dy *= cap / d }
            livePath.lineTo(s.x[s.n - 1] + dx / scale, s.y[s.n - 1] + dy / scale)
        }
        canvas.drawPath(livePath, tailPaint)
        canvas.restore()
    }

    private fun inkUp(e: MotionEvent) {
        removeCallbacks(holdCheck)
        val s = inking ?: return
        inking = null
        if (s.n == 0) { invalidate(); return }
        s.computeBounds()
        history.add(inkPage, s)
        // Draw the finished stroke straight into the cached scene (no full re-render).
        val c = sceneCanvas
        if (c != null && sceneValid && sameAsScene()) {
            c.save()
            c.concat(viewMatrix)
            c.translate(0f, pageTop(inkPage))
            c.clipRect(0f, 0f, NoteDoc.PAGE_W, NoteDoc.PAGE_H)
            PageRenderer.drawStroke(c, s, doc.tone)
            c.restore()
        } else sceneDirty()
        if (inkPage == doc.pages.size - 1) {
            doc.pages.add(NoteDoc.Page()) // keep a fresh page ready below
            clampPan()
            sceneDirty()
            notifyPage()
        }
        changed()
        invalidate()
    }

    // ---------------------------------------------------------------- eraser

    private class EraseSession(val page: Int, val original: List<Stroke>) {
        val working = ArrayList(original)
    }

    private var eraseLastX = 0f
    private var eraseLastY = 0f
    private var cursorX = 0f
    private var cursorY = 0f
    private var showEraserCursor = false

    private fun startErase(sx: Float, sy: Float) {
        clampPan()
        if (!sceneValid || !sameAsScene()) renderScene()
        val page = pageAt(toDocY(sy))
        eraseOverride = EraseSession(page, ArrayList(doc.pages[page].strokes))
        act = Act.ERASE
        eraseLastX = sx; eraseLastY = sy
        showEraserCursor = true
        eraseAt(sx, sy, sx, sy)
    }

    private fun eraseMove(e: MotionEvent) {
        val i = e.findPointerIndex(activeId)
        if (i < 0) return
        for (h in 0 until e.historySize) eraseTo(e.getHistoricalX(i, h), e.getHistoricalY(i, h))
        eraseTo(e.getX(i), e.getY(i))
        invalidate()
    }

    private fun eraseTo(sx: Float, sy: Float) {
        eraseAt(eraseLastX, eraseLastY, sx, sy)
        eraseLastX = sx; eraseLastY = sy
    }

    private fun eraseAt(sx0: Float, sy0: Float, sx1: Float, sy1: Float) {
        val sess = eraseOverride ?: return
        cursorX = sx1; cursorY = sy1
        val pt = pageTop(sess.page)
        val ax = toDocX(sx0); val ay = toDocY(sy0) - pt
        val bx = toDocX(sx1); val by = toDocY(sy1) - pt
        val r = eraserSizeDp * density / scale
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        var touched = false
        val w = sess.working
        if (eraserWholeStroke) {
            val it = w.iterator()
            while (it.hasNext()) {
                val s = it.next()
                if (Eraser.hitsSwept(s, ax, ay, bx, by, r)) {
                    it.remove()
                    touched = true
                    val h = s.maxHalfWidth()
                    minX = min(minX, s.minX - h); minY = min(minY, s.minY - h)
                    maxX = max(maxX, s.maxX + h); maxY = max(maxY, s.maxY + h)
                }
            }
        } else {
            val len = hypot(bx - ax, by - ay)
            val steps = max(1, (len / (r * 0.5f)).toInt())
            for (k in 0..steps) {
                val t = k / steps.toFloat()
                val cx = ax + (bx - ax) * t
                val cy = ay + (by - ay) * t
                var j = 0
                while (j < w.size) {
                    val s = w[j]
                    val parts = Eraser.cut(s, cx, cy, r)
                    if (parts == null) { j++; continue }
                    touched = true
                    val h = s.maxHalfWidth()
                    minX = min(minX, s.minX - h); minY = min(minY, s.minY - h)
                    maxX = max(maxX, s.maxX + h); maxY = max(maxY, s.maxY + h)
                    w.removeAt(j)
                    w.addAll(j, parts)
                    j += parts.size
                }
            }
        }
        if (touched) renderPageArea(sess.page, minX, minY, maxX, maxY)
    }

    private fun eraseUp() {
        val sess = eraseOverride ?: return
        eraseOverride = null
        showEraserCursor = false
        val origSet = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Stroke, Boolean>()).apply { addAll(sess.original) }
        val workSet = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Stroke, Boolean>()).apply { addAll(sess.working) }
        val removed = sess.original.indices.filter { sess.original[it] !in workSet }
        val added = sess.working.filter { it !in origSet }
        if (removed.isNotEmpty() || added.isNotEmpty()) {
            history.apply(sess.page, removed, added)
            changed()
        }
        sceneDirty()
    }

    // ---------------------------------------------------------------- lasso & selection

    private var lassoPts = FloatArray(256)
    private var lassoN = 0
    private var lassoPage = 0
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = 0xFF4F7CFF.toInt()
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val lassoFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x184F7CFF }

    private class Selection(val page: Int, val strokes: List<Stroke>)

    private var selection: Selection? = null
        set(v) {
            field = v
            hidden = v?.strokes?.let { java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Stroke, Boolean>()).apply { addAll(it) } } ?: emptySet()
            listener?.onSelectionChanged(v?.strokes?.size ?: 0)
        }
    private var selDx = 0f
    private var selDy = 0f
    private var selScale = 1f
    private var dragX = 0f
    private var dragY = 0f

    private fun startLasso(sx: Float, sy: Float) {
        lassoPage = pageAt(toDocY(sy))
        lassoN = 0
        act = Act.LASSO
        addLasso(sx, sy)
    }

    private fun addLasso(sx: Float, sy: Float) {
        if (2 * lassoN + 2 > lassoPts.size) lassoPts = lassoPts.copyOf(lassoPts.size * 2)
        lassoPts[2 * lassoN] = toDocX(sx)
        lassoPts[2 * lassoN + 1] = toDocY(sy) - pageTop(lassoPage)
        lassoN++
    }

    private fun lassoMove(e: MotionEvent) {
        val i = e.findPointerIndex(activeId)
        if (i < 0) return
        for (h in 0 until e.historySize) addLasso(e.getHistoricalX(i, h), e.getHistoricalY(i, h))
        addLasso(e.getX(i), e.getY(i))
        invalidate()
    }

    private fun lassoUp() {
        val strokes = doc.pages[lassoPage].strokes
        val idx = Lasso.select(strokes, lassoPts, lassoN)
        lassoN = 0
        selection = if (idx.isEmpty()) null else Selection(lassoPage, idx.map { strokes[it] })
        sceneDirty()
    }

    private fun drawLasso(c: Canvas) {
        if (act != Act.LASSO || lassoN < 2) return
        val p = Path()
        val pt = pageTop(lassoPage)
        p.moveTo(toScreenX(lassoPts[0]), toScreenY(lassoPts[1] + pt))
        for (k in 1 until lassoN) p.lineTo(toScreenX(lassoPts[2 * k]), toScreenY(lassoPts[2 * k + 1] + pt))
        c.drawPath(p, lassoFill)
        c.drawPath(p, lassoPaint)
    }

    private fun selDocBounds(): FloatArray = Lasso.bounds(selection?.strokes ?: emptyList())

    private fun selScreenBounds(): RectF {
        val sel = selection ?: return RectF()
        val b = selDocBounds()
        val pt = pageTop(sel.page)
        val l = b[0]; val t = b[1]
        val r = l + (b[2] - l) * selScale
        val bt = t + (b[3] - t) * selScale
        return RectF(toScreenX(l + selDx), toScreenY(t + selDy + pt), toScreenX(r + selDx), toScreenY(bt + selDy + pt))
    }

    private fun startSelDrag(sx: Float, sy: Float, scaling: Boolean) {
        act = if (scaling) Act.SCALE_SEL else Act.MOVE_SEL
        dragX = sx; dragY = sy
        selDx = 0f; selDy = 0f; selScale = 1f
    }

    private fun selMove(e: MotionEvent) {
        val i = e.findPointerIndex(activeId)
        if (i < 0) return
        val x = e.getX(i); val y = e.getY(i)
        if (act == Act.MOVE_SEL) {
            selDx = (x - dragX) / scale
            selDy = (y - dragY) / scale
        } else {
            val b = selDocBounds()
            val w = (b[2] - b[0]).coerceAtLeast(1f)
            val h = (b[3] - b[1]).coerceAtLeast(1f)
            val nx = (toScreenX(b[2]) + (x - dragX)) // new right edge on screen
            val ny = (toScreenY(b[3] + pageTop(selection!!.page)) + (y - dragY))
            val sw = (toDocX(nx) - b[0]) / w
            val sh = (toDocY(ny) - pageTop(selection!!.page) - b[1]) / h
            selScale = max(sw, sh).coerceIn(0.1f, 10f)
        }
        invalidate()
    }

    private fun selUp() {
        val sel = selection ?: return
        if (selDx == 0f && selDy == 0f && selScale == 1f) return
        val b = selDocBounds()
        val copies = sel.strokes.map { s ->
            s.copy().also {
                if (selScale != 1f) it.scale(b[0], b[1], selScale)
                it.translate(selDx, selDy)
            }
        }
        replaceSelection(copies)
        selDx = 0f; selDy = 0f; selScale = 1f
    }

    /** Replace the selected strokes with new versions (one undo step) and keep them selected. */
    private fun replaceSelection(copies: List<Stroke>) {
        val sel = selection ?: return
        val list = doc.pages[sel.page].strokes
        val idx = sel.strokes.map { s -> list.indexOfFirst { it === s } }.filter { it >= 0 }
        history.apply(sel.page, idx, copies)
        selection = if (copies.isEmpty()) null else Selection(sel.page, copies)
        changed()
        sceneDirty()
    }

    private val selBox = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = 0xFF4F7CFF.toInt()
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4F7CFF.toInt() }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private fun drawSelection(c: Canvas) {
        val sel = selection ?: return
        val b = selDocBounds()
        c.save()
        c.concat(viewMatrix)
        c.translate(selDx, selDy + pageTop(sel.page))
        if (selScale != 1f) c.scale(selScale, selScale, b[0], b[1])
        for (s in sel.strokes) PageRenderer.drawStroke(c, s, doc.tone)
        c.restore()
        val r = selScreenBounds()
        c.drawRect(r, selBox)
        val hr = 9 * density
        c.drawCircle(r.right, r.bottom, hr + 2 * density, handleRing)
        c.drawCircle(r.right, r.bottom, hr, handlePaint)
    }

    fun clearSelection() {
        if (selection == null) return
        selection = null
        sceneDirty()
    }

    fun deleteSelection() = replaceSelection(emptyList())

    fun recolorSelection(color: Int) {
        val sel = selection ?: return
        replaceSelection(sel.strokes.map { it.copy().also { c -> c.color = color } })
    }

    fun duplicateSelection() {
        val sel = selection ?: return
        val off = 30f
        val copies = sel.strokes.map { it.copy().also { c -> c.translate(off, off) } }
        history.apply(sel.page, null, copies)
        selection = Selection(sel.page, copies)
        changed()
        sceneDirty()
    }

    // ---------------------------------------------------------------- pan / zoom with fingers

    private var gLastCx = 0f
    private var gLastCy = 0f
    private var gLastSpan = 0f
    private var gStartTime = 0L
    private var gMaxPointers = 0
    private var gTravel = 0f
    private var velocity: VelocityTracker? = null
    private val scroller = OverScroller(context)

    private fun fingerCentroid(e: MotionEvent, excluding: Int = -1): Triple<Float, Float, Float> {
        var sx = 0f; var sy = 0f; var n = 0
        for (k in 0 until e.pointerCount) {
            if (k == excluding || isPenTool(e.getToolType(k))) continue
            sx += e.getX(k); sy += e.getY(k); n++
        }
        if (n == 0) return Triple(0f, 0f, 0f)
        val cx = sx / n; val cy = sy / n
        var span = 0f
        for (k in 0 until e.pointerCount) {
            if (k == excluding || isPenTool(e.getToolType(k))) continue
            span += hypot(e.getX(k) - cx, e.getY(k) - cy)
        }
        return Triple(cx, cy, if (n > 1) span / n else 0f)
    }

    private fun startGesture(e: MotionEvent) {
        act = Act.GESTURE
        gStartTime = e.eventTime
        gMaxPointers = 0
        gTravel = 0f
        velocity?.recycle()
        velocity = VelocityTracker.obtain().also { it.addMovement(e) }
        restartGesture(e)
    }

    private fun restartGesture(e: MotionEvent, excluding: Int = -1) {
        val (cx, cy, span) = fingerCentroid(e, excluding)
        gLastCx = cx; gLastCy = cy; gLastSpan = span
        var fingers = 0
        for (k in 0 until e.pointerCount) if (k != excluding && !isPenTool(e.getToolType(k))) fingers++
        gMaxPointers = max(gMaxPointers, fingers)
    }

    private fun gestureMove(e: MotionEvent) {
        velocity?.addMovement(e)
        val (cx, cy, span) = fingerCentroid(e)
        gTravel += hypot(cx - gLastCx, cy - gLastCy)
        if (span > 0f && gLastSpan > 0f) {
            val f = span / gLastSpan
            val ns = (scale * f).coerceIn(fitScale * 0.35f, fitScale * 8f)
            val real = ns / scale
            // keep the point under the fingers fixed
            tx = gLastCx - (gLastCx - tx) * real
            ty = gLastCy - (gLastCy - ty) * real
            scale = ns
            gTravel += abs(span - gLastSpan)
        }
        tx += cx - gLastCx
        ty += cy - gLastCy
        gLastCx = cx; gLastCy = cy; gLastSpan = span
        clampPan()
        viewportChanged()
    }

    private fun endGesture(e: MotionEvent?) {
        val quick = e != null && e.eventTime - gStartTime < 260 && gTravel < 24 * density
        if (quick && gMaxPointers == 2) { undo(); return }
        if (quick && gMaxPointers == 3) { redo(); return }
        val v = velocity
        if (e != null && v != null && gMaxPointers == 1) {
            v.addMovement(e)
            v.computeCurrentVelocity(1000)
            val vx = v.xVelocity; val vy = v.yVelocity
            if (hypot(vx, vy) > 600) {
                scroller.fling(tx.toInt(), ty.toInt(), vx.toInt(), vy.toInt(),
                    Int.MIN_VALUE / 2, Int.MAX_VALUE / 2, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
                postInvalidateOnAnimation()
            }
        }
        v?.recycle()
        velocity = null
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            tx = scroller.currX.toFloat()
            ty = scroller.currY.toFloat()
            clampPan()
            viewportChanged()
            postInvalidateOnAnimation()
        }
    }

    // ---------------------------------------------------------------- ruler

    private var ruler: Ruler? = null
    private var rulerRawAngle = 0f
    private var rLastCx = 0f
    private var rLastCy = 0f
    private var rLastAngle = 0f
    private var rFingers = 0

    private fun placeRulerIfNeeded() {
        if (ruler == null && width > 0) {
            ruler = Ruler(width / 2f, height * 0.45f, 2.4f * max(width, height), 76 * density).also {
                it.angle = 0f
                rulerRawAngle = 0f
            }
        }
    }

    private fun startRuler(e: MotionEvent) {
        act = Act.RULER
        rulerRawAngle = ruler?.angle ?: 0f
        restartRuler(e)
    }

    private fun restartRuler(e: MotionEvent, excluding: Int = -1) {
        val pts = ArrayList<Pair<Float, Float>>()
        for (k in 0 until e.pointerCount) {
            if (k == excluding || isPenTool(e.getToolType(k))) continue
            pts.add(e.getX(k) to e.getY(k))
        }
        rFingers = pts.size
        if (pts.isEmpty()) return
        rLastCx = pts.map { it.first }.average().toFloat()
        rLastCy = pts.map { it.second }.average().toFloat()
        rLastAngle = if (pts.size >= 2) atan2(pts[1].second - pts[0].second, pts[1].first - pts[0].first) else 0f
    }

    private fun rulerMove(e: MotionEvent) {
        val r = ruler ?: return
        val pts = ArrayList<Pair<Float, Float>>()
        for (k in 0 until e.pointerCount) if (!isPenTool(e.getToolType(k))) pts.add(e.getX(k) to e.getY(k))
        if (pts.isEmpty()) return
        val cx = pts.map { it.first }.average().toFloat()
        val cy = pts.map { it.second }.average().toFloat()
        r.moveBy(cx - rLastCx, cy - rLastCy)
        if (pts.size >= 2 && rFingers >= 2) {
            val a = atan2(pts[1].second - pts[0].second, pts[1].first - pts[0].first)
            var d = a - rLastAngle
            if (d > Math.PI) d -= (2 * Math.PI).toFloat()
            if (d < -Math.PI) d += (2 * Math.PI).toFloat()
            rulerRawAngle += d
            // rotate about the finger midpoint, snapping to 0/15/30/45… when close
            val before = r.angle
            r.angle = rulerRawAngle
            val snapped = r.snapAngle(1.6f)
            val target = r.angle
            r.angle = before
            r.rotateAbout(cx, cy, target - before)
            if (snapped && abs(target - before) > 1e-4f) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            rLastAngle = a
        }
        rLastCx = cx; rLastCy = cy
        invalidate()
    }

    private fun endRuler() {}

    /** Puts the ruler back to horizontal in the middle of the screen. */
    fun resetRuler() {
        ruler = null
        placeRulerIfNeeded()
        invalidate()
    }

    private val rulerBody = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rulerEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rulerText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun drawRuler(c: Canvas) {
        if (!rulerVisible) return
        val r = ruler ?: return
        val dark = doc.tone == NoteDoc.TONE_DARK
        val half = r.thickness / 2f
        val len = r.length / 2f
        c.save()
        c.translate(r.cx, r.cy)
        c.rotate(Math.toDegrees(r.angle.toDouble()).toFloat())
        rulerBody.color = if (dark) 0xD0383D46.toInt() else 0xD6EEF1F6.toInt()
        c.drawRect(-len, -half, len, half, rulerBody)
        // Edge highlights: the edge the hovering pen would snap to glows.
        val hoverEdge = if (hovering) r.edgeFor(hoverX, hoverY, 22 * density) else 0
        rulerEdgePaint.strokeWidth = 1.5f * density
        for (edge in intArrayOf(-1, 1)) {
            rulerEdgePaint.color = if (edge == hoverEdge || (act == Act.INK && edge == rulerEdge)) 0xFF4F7CFF.toInt()
            else if (dark) 0xFF8A93A3.toInt() else 0xFF7D8696.toInt()
            rulerEdgePaint.strokeWidth = (if (edge == hoverEdge) 3f else 1.5f) * density
            c.drawLine(-len, edge * half, len, edge * half, rulerEdgePaint)
        }
        // Millimetre ticks at true physical size, numbered every centimetre from the ruler's centre.
        val pxPerMm = resources.displayMetrics.xdpi / 25.4f
        tickPaint.color = if (dark) 0xFFB8C0CC.toInt() else 0xFF4A5261.toInt()
        tickPaint.strokeWidth = 1f * density
        rulerText.color = tickPaint.color
        rulerText.textSize = 10f * density
        val mmCount = (len / pxPerMm).toInt()
        for (mm in -mmCount..mmCount) {
            val x = mm * pxPerMm
            val l = when { mm % 10 == 0 -> half * 0.42f; mm % 5 == 0 -> half * 0.28f; else -> half * 0.16f }
            c.drawLine(x, -half, x, -half + l, tickPaint)
            c.drawLine(x, half, x, half - l, tickPaint)
            if (mm % 10 == 0 && mm != 0) c.drawText("${abs(mm / 10)}", x, -half + l + rulerText.textSize * 1.05f, rulerText)
        }
        // Angle readout in the middle.
        val label = "${r.readoutDegrees()}°"
        rulerText.textSize = 15f * density
        val tw = rulerText.measureText(label) + 20 * density
        badgePaint.color = 0xFF4F7CFF.toInt()
        c.drawRoundRect(-tw / 2, -13 * density, tw / 2, 13 * density, 13 * density, 13 * density, badgePaint)
        rulerText.color = Color.WHITE
        c.drawText(label, 0f, 5.5f * density, rulerText)
        c.restore()
    }

    // ---------------------------------------------------------------- hover (S Pen in the air)

    private var lastPenTime = 0L
    private var hovering = false
    private var hoverX = 0f
    private var hoverY = 0f
    private var hoverButton = false
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (!isPenTool(e.getToolType(0))) return super.onHoverEvent(e)
        if (!stylusSeen) { stylusSeen = true; listener?.onStylusSeen() }
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                lastPenTime = SystemClock.uptimeMillis()
                hovering = true
                hoverX = e.x; hoverY = e.y
                hoverButton = e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
                    (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
            }
            MotionEvent.ACTION_HOVER_EXIT -> hovering = false
        }
        invalidate()
        return true
    }

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
        // Hide the system hover dot over the page; we draw our own brush preview.
        return if (isPenTool(event.getToolType(pointerIndex))) PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
        else super.onResolvePointerIcon(event, pointerIndex)
    }

    private fun drawCursor(c: Canvas) {
        val erasing = act == Act.ERASE && showEraserCursor
        if (!hovering && !erasing) return
        val x = if (erasing) cursorX else hoverX
        val y = if (erasing) cursorY else hoverY
        val eraserLook = erasing || (hovering && (mode == Mode.ERASER || (hoverButton && sideButton == SideAction.ERASER)))
        cursorPaint.strokeWidth = 1.5f * density
        if (eraserLook) {
            val r = eraserSizeDp * density
            cursorFill.color = 0x30FFFFFF
            c.drawCircle(x, y, r, cursorFill)
            cursorPaint.color = 0xFF7D8696.toInt()
            c.drawCircle(x, y, r, cursorPaint)
            return
        }
        if (act != Act.NONE) return
        if (mode == Mode.LASSO || (hoverButton && sideButton == SideAction.LASSO)) {
            cursorPaint.color = 0xFF4F7CFF.toInt()
            c.drawCircle(x, y, 5 * density, cursorPaint)
            return
        }
        val tool = if (hoverButton && sideButton == SideAction.HIGHLIGHTER) Stroke.HIGHLIGHTER else penTool
        val r = max(2f * density, (if (tool == Stroke.HIGHLIGHTER) widths[tool] / 2f else Outline.halfWidth(tool, widths[tool], 0.6f, 0.0)) * scale)
        cursorFill.color = PageRenderer.displayColor(colors[tool], doc.tone)
        cursorFill.alpha = if (tool == Stroke.HIGHLIGHTER) 110 else 200
        c.drawCircle(x, y, r, cursorFill)
        cursorPaint.color = 0x99FFFFFF.toInt()
        cursorPaint.strokeWidth = 1f * density
        c.drawCircle(x, y, r + 1.5f * density, cursorPaint)
    }

    // ---------------------------------------------------------------- keyboard (Book Cover keyboard etc.)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_Z -> { if (event.isShiftPressed) redo() else undo(); return true }
                KeyEvent.KEYCODE_Y -> { redo(); return true }
            }
        }
        if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
            if (selection != null) { deleteSelection(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- public actions

    fun undo() {
        clearSelection()
        if (history.undo() >= 0) { changed(); sceneDirty() }
    }

    fun redo() {
        clearSelection()
        if (history.redo() >= 0) { changed(); sceneDirty() }
    }

    fun addPage() {
        doc.pages.add(NoteDoc.Page())
        clampPan()
        scrollToPage(doc.pages.size - 1)
        changed()
    }

    /** Deletes the page in view. Page structure changes can't be undone, so history resets. */
    fun deleteCurrentPage() {
        if (doc.pages.size <= 1) {
            doc.pages[0].strokes.clear()
        } else {
            doc.pages.removeAt(currentPage())
        }
        history.clear()
        clearSelection()
        clampPan()
        sceneDirty()
        notifyHistory()
        notifyPage()
        changed()
    }

    fun clearCurrentPage() {
        val p = currentPage()
        val n = doc.pages[p].strokes.size
        if (n == 0) return
        history.apply(p, (0 until n).toList(), null)
        changed()
        sceneDirty()
    }

    fun currentPage(): Int = pageAt(toDocY(height / 2f))

    fun scrollToPage(i: Int) {
        ty = 12 * density - pageTop(i) * scale
        clampPan()
        viewportChanged()
    }

    fun resetZoom() {
        val keep = currentPage()
        fitToWidth()
        scrollToPage(keep)
    }

    fun paperChanged() {
        sceneDirty()
        changed()
    }

    private fun changed() {
        doc.modified = System.currentTimeMillis()
        notifyHistory()
        listener?.onContentChanged()
    }

    private fun notifyHistory() = listener?.onHistoryChanged(history.canUndo(), history.canRedo())

    private var lastPageNotified = -1
    private var lastTotalNotified = -1
    private fun notifyPage() {
        if (height == 0) return
        val c = currentPage()
        if (c != lastPageNotified || doc.pages.size != lastTotalNotified) {
            lastPageNotified = c
            lastTotalNotified = doc.pages.size
            listener?.onPageChanged(c, doc.pages.size)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(rerender)
        removeCallbacks(holdCheck)
    }
}
