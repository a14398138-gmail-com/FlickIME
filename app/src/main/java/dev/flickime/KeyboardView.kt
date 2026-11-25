package dev.flickime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import dev.flickime.engine.GlideDecoder
import kotlin.math.abs
import kotlin.math.hypot

/** Keyboard drawn on one canvas: flick 12-key layouts and a QWERTY layout with glide typing. */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener, private val theme: Theme) : View(context) {
    interface Listener {
        fun onText(text: String)
        fun onAction(action: Action)
        fun onGlide(path: List<GlideDecoder.P>, centers: Map<Char, GlideDecoder.P>, keyWidth: Float, keyHeight: Float)
    }

    private val dp = resources.displayMetrics.density
    private val textColor = theme.text
    private val primaryColor = theme.primary
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor; textAlign = Paint.Align.CENTER }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = primaryColor; alpha = 170; style = Paint.Style.STROKE; strokeWidth = 6 * dp
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    var layout: KeyLayout = Layouts.kana
        set(v) { field = v; shift = 0; requestLayout(); invalidate() }

    /** While kana is being composed, the globe key turns into the ゛゜ key (like Gboard). */
    var composing = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private fun effective(k: KeyDef): Action =
        if (k.action == Action.Globe && composing && layout === Layouts.kana) Action.Dakuten else k.action

    /** 0 = off, 1 = next letter only, 2 = caps lock */
    var shift = 0
        set(v) { field = v; invalidate() }
    private var lastShiftTap = 0L
    private var shiftLongPressed = false
    private val shiftLock = Runnable {
        shiftLongPressed = true
        shift = 2
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private var pressed: KeyDef? = null
    private var downX = 0f
    private var downY = 0f
    private var dir = 0 // 0 center, 1 left, 2 up, 3 right, 4 down
    private var gliding = false
    private val trail = ArrayList<GlideDecoder.P>()

    private val repeat = Handler(Looper.getMainLooper())
    /** Key that repeats while held: ⌫, and ◀▶ (cursor) */
    private var repeatAction: Action = Action.Backspace
    private val repeater = object : Runnable {
        override fun run() {
            listener.onAction(repeatAction)
            repeat.postDelayed(this, if (repeatAction == Action.Backspace) 60 else 70)
        }
    }

    private val landscape get() =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    /** Rows are lower in landscape so the app keeps most of the screen. */
    private val rowScale get() = if (landscape) (if (layout.glide) 0.78f else 0.7f) else 1f

    /** In landscape the 12-key pads are kept to a thumb-friendly width, centred. */
    private val contentWidth get() = if (landscape && !layout.glide) minOf(width.toFloat(), 460 * dp) else width.toFloat()
    private val contentLeft get() = (width - contentWidth) / 2

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (layout.rows * layout.rowHeightDp * rowScale * dp).toInt())
    }

    private fun rectOf(k: KeyDef): RectF {
        val rh = height / layout.rows.toFloat()
        val m = (if (layout.glide) 2.5f else 3f) * dp + (if (theme.glass) 1f * dp else 0f)
        val cw = contentWidth; val cl = contentLeft
        return RectF(cl + k.x * cw + m, k.row * rh + m, cl + (k.x + k.w) * cw - m, (k.row + k.rowSpan) * rh - m)
    }

    private fun out(k: KeyDef, s: String?): String? =
        if (s != null && shift > 0 && k.shifted != null && s == k.label) k.shifted else s

    override fun onDraw(canvas: Canvas) {
        if (!theme.glass) canvas.drawColor(theme.background)
        for (k in layout.keys) {
            val r = rectOf(k)
            if (k === pressed && !gliding && theme.glass) r.inset(-1.5f * dp, -1.5f * dp) // glass "lifts" when pressed
            theme.drawKey(canvas, r, when {
                k === pressed && !gliding -> Theme.Kind.PRESSED
                k.primary -> Theme.Kind.PRIMARY
                k.action == Action.Shift && (shift > 0 || shiftHoldId >= 0) -> Theme.Kind.PRESSED
                k.accent -> Theme.Kind.ACCENT
                else -> Theme.Kind.NORMAL
            })
            val showFlick = false // the flick popup is drawn on top of everything below
            val label = when {
                showFlick -> k.flicks.getOrNull(dir) ?: ""
                k.action == Action.Shift -> if (shift == 2) "⇪" else "⇧"
                effective(k) != k.action -> "゛゜"
                else -> out(k, k.label)!!
            }
            labelPaint.color = if (k.primary) Color.WHITE else textColor
            labelPaint.textSize = when {
                showFlick -> 30f
                layout.glide && label.length == 1 -> 21f
                label.length <= 2 -> 24f
                else -> 15f
            } * dp
            canvas.drawText(label, r.centerX(), r.centerY() + labelPaint.textSize / 3, labelPaint)
            if (k.action == Action.Shift && shift == 2) {
                val y = r.centerY() + labelPaint.textSize * 0.62f
                canvas.drawRect(r.centerX() - 7 * dp, y, r.centerX() + 7 * dp, y + 2 * dp, labelPaint)
            }
        }
        pressed?.let { k ->
            if (!gliding && !layout.glide && k.action == Action.Input && k.flicks.size > 1) drawFlickPopup(canvas, rectOf(k), k)
        }
        if (lpOpen) drawLongPress(canvas)
        if (gliding && trail.size > 1) {
            val p = Path().apply { moveTo(trail[0].x, trail[0].y); for (i in 1 until trail.size) lineTo(trail[i].x, trail[i].y) }
            canvas.drawPath(p, trailPaint)
        }
    }

    /** How far the finger must move before a touch counts as a flick (smaller = more sensitive). */
    var flickThresholdDp = Prefs.DEFAULT_FLICK_DP.toFloat()

    private var curX = 0f
    private var curY = 0f

    // space-bar trackpad: sliding along the space bar moves the cursor
    private var spaceDrag = false
    private var spaceAnchorX = 0f

    /** Space above the keyboard (candidate bar) that the popup may draw into. */
    var topHeadroom = 0f

    /** The popup can extend past our bounds, so the parents must repaint too. */
    private fun redraw() {
        invalidate()
        (parent as? View)?.invalidate()
        (parent?.parent as? View)?.invalidate()
    }

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /**
     * Gboard-like flick preview: only the chosen character is stretched out of the key
     * in the direction of the finger.
     */
    private fun drawFlickPopup(c: Canvas, r: RectF, k: KeyDef) {
        val cx = r.centerX(); val cy = r.centerY()
        val size = minOf(r.width(), r.height()) * 0.92f
        val rad = size / 2
        val gapX = r.width() * 0.5f + rad * 0.55f
        val gapY = r.height() * 0.5f + rad * 0.55f
        val minY = -topHeadroom + rad + 2 * dp
        val vec = arrayOf(0f to 0f, -1f to 0f, 0f to -1f, 1f to 0f, 0f to 1f)

        if (dir == 0) return // only the character being pulled is shown
        val ch = k.flicks.getOrNull(dir) ?: k.flicks[0] ?: return
        val (vx, vy) = vec[dir]
        // pull: the bubble follows the finger a little further than the guide position
        val pull = if (dir == 0) 0f else {
            val dist = kotlin.math.hypot(curX - downX, curY - downY)
            (dist / (r.width() * 1.2f)).coerceIn(0f, 1f)
        }
        val bx = cx + vx * (gapX + pull * rad * 0.35f)
        val by = maxOf(minY, cy + vy * (gapY + pull * rad * 0.35f))
        bubblePaint.color = theme.popupFill
        if (dir != 0) {
            // the stretched "neck" between the key and the bubble
            val neck = Path()
            val w0 = (if (vx != 0f) r.height() else r.width()) * 0.32f
            val w1 = rad * 0.8f
            if (vx != 0f) {
                val x0 = cx + vx * r.width() * 0.25f
                neck.moveTo(x0, cy - w0); neck.lineTo(bx, by - w1); neck.lineTo(bx, by + w1); neck.lineTo(x0, cy + w0)
            } else {
                val y0 = cy + vy * r.height() * 0.25f
                neck.moveTo(cx - w0, y0); neck.lineTo(bx - w1, by); neck.lineTo(bx + w1, by); neck.lineTo(cx + w0, y0)
            }
            neck.close()
            c.drawPath(neck, bubblePaint)
        }
        val big = if (dir == 0) rad * 1.05f else rad * (1f + 0.12f * pull)
        c.drawRoundRect(RectF(bx - big, by - big, bx + big, by + big), big * 0.45f, big * 0.45f, bubblePaint)
        bubbleText.color = theme.popupText; bubbleText.alpha = 255
        bubbleText.textSize = 30 * dp
        c.drawText(ch, bx, by + bubbleText.textSize / 3, bubbleText)
    }

    private fun tick() = performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

    private fun keyWidth() = width / 13f

    private fun centers(): Map<Char, GlideDecoder.P> = layout.keys.mapNotNull { k ->
        k.letter?.let { ch -> rectOf(k).let { r -> ch to GlideDecoder.P(r.centerX(), r.centerY()) } }
    }.toMap()

    // ---- long-press popup (English keyboard) ----
    private var lpOpen = false
    private var lpOptions: List<String> = emptyList()
    private var lpIndex = 0
    private var lpLeft = 0f
    private var lpTop = 0f
    private var lpCell = 0f
    private var lpCellH = 0f
    private var lpCols = 1
    private var lpRows = 1
    private var lpX = 0f
    private var lpMoved = false
    private val longPress = Runnable {
        val k = pressed ?: return@Runnable
        val opts = k.longPress ?: return@Runnable
        if (gliding || spaceDrag) return@Runnable
        lpOptions = opts.map { o -> if (shift > 0 && o.length == 1 && o[0].isLetter()) o[0].uppercaseChar().toString() else o }
        val r = rectOf(k)
        lpCell = maxOf(r.width(), 34 * dp)
        lpCellH = r.height() * 1.05f
        // wrap long lists (the period key has 16) into rows of up to 8, like LatinIME
        lpCols = minOf(lpOptions.size, 8, maxOf(1, (width / lpCell).toInt()))
        lpRows = (lpOptions.size + lpCols - 1) / lpCols
        val total = lpCell * lpCols
        lpLeft = (r.left - (lpCell - r.width()) / 2).coerceIn(0f, maxOf(0f, width - total))
        lpTop = maxOf(-topHeadroom, r.top - lpCellH * lpRows - 4 * dp)
        lpIndex = 0; lpMoved = false; lpX = curX
        lpOpen = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        redraw()
    }

    private val lpPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun drawLongPress(c: Canvas) {
        val radius = 10 * dp
        lpPaint.color = theme.popupGuide
        lpPaint.alpha = 250
        c.drawRoundRect(RectF(lpLeft, lpTop, lpLeft + lpCell * lpCols, lpTop + lpCellH * lpRows), radius, radius, lpPaint)
        lpOptions.forEachIndexed { i, o ->
            // first row sits right above the key, further rows stack upwards
            val col = i % lpCols; val row = lpRows - 1 - i / lpCols
            val cell = RectF(lpLeft + col * lpCell, lpTop + row * lpCellH, lpLeft + (col + 1) * lpCell, lpTop + (row + 1) * lpCellH)
            if (i == lpIndex) {
                lpPaint.color = theme.popupFill
                c.drawRoundRect(RectF(cell.left + 2 * dp, cell.top + 2 * dp, cell.right - 2 * dp, cell.bottom - 2 * dp), radius * 0.8f, radius * 0.8f, lpPaint)
            }
            labelPaint.color = if (i == lpIndex) theme.popupText else textColor
            labelPaint.textSize = 22 * dp
            c.drawText(o, cell.centerX(), cell.centerY() + labelPaint.textSize / 3, labelPaint)
        }
    }

    // multi-touch: the finger being tracked, and a shift key held down by another finger
    private var activeId = -1
    private var shiftHoldId = -1
    private var shiftHoldUsed = false
    private var shiftBeforeHold = 0

    private fun begin(x: Float, y: Float) {
        // touches in the gaps between keys go to the nearest key
        pressed = layout.keys.firstOrNull { rectOf(it).contains(x, y) }
            ?: layout.keys.minByOrNull { val r = rectOf(it); hypot(r.centerX() - x, r.centerY() - y) }
        downX = x; downY = y; curX = x; curY = y; dir = 0; gliding = false
        spaceDrag = false; spaceAnchorX = x
        trail.clear(); trail.add(GlideDecoder.P(x, y))
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val a = pressed?.action
        if (a == Action.Backspace || a == Action.Left || a == Action.Right) {
            repeatAction = a
            listener.onAction(a)
            repeat.postDelayed(repeater, 400)
        }
        if (pressed?.action == Action.Shift) { shiftLongPressed = false; repeat.postDelayed(shiftLock, 400) }
        lpOpen = false
        if (pressed?.longPress != null) repeat.postDelayed(longPress, 350)
        redraw()
    }

    /** The tracked finger lifted: type its key (or finish its glide / cursor slide). */
    private fun finish() {
        repeat.removeCallbacks(repeater)
        repeat.removeCallbacks(shiftLock)
        repeat.removeCallbacks(longPress)
        val k = pressed
        pressed = null
        if (lpOpen) {
            lpOpen = false
            lpOptions.getOrNull(lpIndex)?.let { listener.onText(it) }
            if (shift == 1) shift = 0
        } else if (gliding) {
            gliding = false
            listener.onGlide(ArrayList(trail), centers(), keyWidth(), height / layout.rows.toFloat())
            if (shift == 1) shift = 0
        } else if (spaceDrag) {
            spaceDrag = false // the slide moved the cursor; don't also type a space
        } else if (k != null) fire(k)
        if (shiftHoldId >= 0 && shift == 0) shift = 1 // still holding shift: keep capitals coming
        trail.clear()
        redraw()
    }

    private fun releaseShiftHold() {
        if (shiftHoldId < 0) return
        shiftHoldId = -1
        // used as a modifier (shift held + letters): go back to what it was before
        if (shiftHoldUsed) shift = if (shiftBeforeHold == 2) 2 else 0
        redraw()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeId = e.getPointerId(0); shiftHoldId = -1
                begin(e.x, e.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                if (pressed?.action == Action.Shift && shiftHoldId < 0 && !gliding) {
                    // shift is being held: it becomes a modifier for the new finger
                    repeat.removeCallbacks(shiftLock)
                    if (shiftLongPressed) { shiftLongPressed = false; shiftBeforeHold = 0 } else shiftBeforeHold = shift
                    shiftHoldId = activeId; shiftHoldUsed = true
                    if (shift == 0) shift = 1
                    pressed = null
                } else if (pressed != null && !gliding) {
                    finish() // two-thumb typing: the first key is typed as the second lands
                }
                activeId = e.getPointerId(i)
                begin(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = e.findPointerIndex(activeId)
                if (idx < 0 || pressed == null) return true
                val x = e.getX(idx); val y = e.getY(idx)
                curX = x; curY = y
                if (lpOpen) {
                    if (!lpMoved && (abs(x - lpX) > 8 * dp || abs(y - downY) > 12 * dp)) lpMoved = true
                    if (lpMoved) {
                        val col = ((x - lpLeft) / lpCell).toInt().coerceIn(0, lpCols - 1)
                        val row = (lpRows - 1 - ((y - lpTop) / lpCellH).toInt()).coerceIn(0, lpRows - 1)
                        val i = (row * lpCols + col).coerceIn(0, lpOptions.size - 1)
                        if (i != lpIndex) { lpIndex = i; tick(); redraw() }
                    }
                    return true
                }
                if (pressed?.action == Action.Space) {
                    val step = if (layout.glide) keyWidth() * 0.6f else 14 * dp
                    if (!spaceDrag && abs(x - downX) > step) { spaceDrag = true; spaceAnchorX = downX }
                    if (spaceDrag) {
                        while (x - spaceAnchorX >= step) { listener.onAction(Action.Right); spaceAnchorX += step; tick() }
                        while (spaceAnchorX - x >= step) { listener.onAction(Action.Left); spaceAnchorX -= step; tick() }
                    }
                    return true
                }
                val dx = x - downX; val dy = y - downY
                if (layout.glide) {
                    if (e.pointerCount == 1) for (h in 0 until e.historySize) trail.add(GlideDecoder.P(e.getHistoricalX(h), e.getHistoricalY(h)))
                    trail.add(GlideDecoder.P(x, y))
                    if (!gliding && pressed?.letter != null && hypot(dx, dy) > keyWidth() * 0.7f) { gliding = true; repeat.removeCallbacks(longPress) }
                    if (gliding) invalidate()
                } else {
                    val nd = if (hypot(dx, dy) < flickThresholdDp * dp) 0
                    else if (abs(dx) > abs(dy)) (if (dx < 0) 1 else 3) else (if (dy < 0) 2 else 4)
                    if (nd != dir) dir = nd
                    if (pressed?.flicks?.size ?: 0 > 1) redraw()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                when (id) {
                    activeId -> { finish(); activeId = -1 }
                    shiftHoldId -> releaseShiftHold()
                }
            }
            MotionEvent.ACTION_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == activeId) finish()
                releaseShiftHold()
                activeId = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                repeat.removeCallbacks(repeater); repeat.removeCallbacks(shiftLock); repeat.removeCallbacks(longPress)
                lpOpen = false; pressed = null; gliding = false; trail.clear()
                shiftHoldId = -1; activeId = -1; redraw()
            }
        }
        return true
    }


    private fun fire(k: KeyDef) {
        when (k.action) {
            Action.Input -> {
                val s = out(k, k.flicks.getOrNull(dir) ?: k.flicks[0]) ?: return
                listener.onText(s)
                if (shift == 1) shift = 0
            }
            // like Gboard: tap = next letter, double-tap or long-press = caps lock, tap again = off
            Action.Shift -> {
                if (shiftLongPressed) { shiftLongPressed = false; return }
                val now = SystemClock.uptimeMillis()
                shift = when {
                    shift == 0 -> 1
                    shift == 1 && now - lastShiftTap < 400 -> 2
                    else -> 0
                }
                lastShiftTap = now
            }
            Action.Backspace, Action.Left, Action.Right -> Unit // handled on down / repeat
            else -> listener.onAction(effective(k))
        }
    }

    /** Letters should be capitalised for the next glided word? */
    val glideCaps: Boolean get() = shift > 0
}
