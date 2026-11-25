package dev.flickime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/** Emoji drawn straight onto a canvas (hundreds of TextViews would be slow). */
@SuppressLint("ViewConstructor")
class EmojiGridView(context: Context, private val onPick: (String) -> Unit) : View(context) {
    private val dp = resources.displayMetrics.density
    private val cell = 46 * dp
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 28 * dp }
    private val clip = Rect()
    /** All sections one after another, like Gboard (each starts on a new row). */
    var sections: List<List<String>> = emptyList()
        set(v) { field = v; layoutCells(width); requestLayout(); invalidate() }
    /** Flat cells with "" padding so every section starts a row; rebuilt when the width changes. */
    private var items: List<String> = emptyList()
    private var sectionRows = IntArray(0)
    private var laidOutFor = -1
    private val cols get() = maxOf(1, (width / cell).toInt())

    private fun layoutCells(w: Int) {
        if (w <= 0) return
        val c = maxOf(1, (w / cell).toInt())
        val out = ArrayList<String>(); val rows = IntArray(sections.size)
        sections.forEachIndexed { i, sec ->
            rows[i] = out.size / c
            out.addAll(sec)
            while (out.size % c != 0) out.add("")
        }
        items = out; sectionRows = rows; laidOutFor = w
    }

    /** Top pixel of section [i]. */
    fun sectionTop(i: Int): Int = ((sectionRows.getOrNull(i) ?: 0) * cell).toInt()
    /** Section shown at scroll offset [y]. */
    fun sectionAt(y: Int): Int {
        val row = (y + cell / 2) / cell
        var s = 0
        for (i in sectionRows.indices) if (sections[i].isNotEmpty() && sectionRows[i] <= row) s = i
        return s
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        if (width != laidOutFor) layoutCells(width)
        val c = maxOf(1, (width / cell).toInt())
        setMeasuredDimension(width, (((items.size + c - 1) / c) * cell).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.getClipBounds(clip)
        val cw = width / cols.toFloat()
        val first = maxOf(0, (clip.top / cell).toInt()) * cols
        val last = minOf(items.size, ((clip.bottom / cell).toInt() + 1) * cols)
        for (i in first until last) {
            val x = (i % cols) * cw + cw / 2
            val y = (i / cols) * cell + cell / 2 + paint.textSize / 3
            if (items[i].isNotEmpty()) canvas.drawText(items[i], x, y, paint)
        }
    }

    private var dx = 0f; private var dy = 0f
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dx = e.x; dy = e.y }
            MotionEvent.ACTION_UP -> if (abs(e.x - dx) < 12 * dp && abs(e.y - dy) < 12 * dp) {
                val i = (e.y / cell).toInt() * cols + (e.x / (width / cols.toFloat())).toInt()
                items.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); onPick(it) }
            }
        }
        return true
    }
}

/** Gboard-like emoji panel: category tabs, a scrolling grid, and back / space / ⌫ at the bottom. */
@SuppressLint("ViewConstructor")
class EmojiPanel(
    context: Context,
    private val theme: Theme,
    private val categories: List<Pair<String, List<String>>>,
    private val onPick: (String) -> Unit,
    private val onBack: () -> Unit,
    private val onSpace: () -> Unit,
    private val onDelete: () -> Unit,
) : LinearLayout(context) {
    private val dp = resources.displayMetrics.density
    private val grid = EmojiGridView(context) { e -> Recents.add(context, e); onPick(e) }
    private val scroll = ScrollView(context).apply {
        addView(grid)
        // the tab follows the scroll position
        setOnScrollChangeListener { _, _, y, _, _ -> highlight(grid.sectionAt(y)) }
    }
    private val tabs = LinearLayout(context).apply { orientation = HORIZONTAL }
    private var current = 0

    init {
        orientation = VERTICAL
        addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(tabs) },
            LayoutParams(LayoutParams.MATCH_PARENT, (40 * dp).toInt()))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val bottom = LinearLayout(context).apply { orientation = HORIZONTAL }
        fun key(label: String, weight: Float, onClick: () -> Unit) = TextView(context).apply {
            text = label; gravity = Gravity.CENTER; setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setOnClickListener { onClick() }
        }.also { bottom.addView(it, LayoutParams(0, LayoutParams.MATCH_PARENT, weight)) }
        key("あ/abc", 1f, onBack)
        key("␣", 2f, onSpace)
        key("⌫", 1f, onDelete)
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, (44 * dp).toInt()))
        renderTabs()
    }

    private fun all() = listOf("🕘 最近" to Recents.get(context)) + categories

    fun show() {
        grid.sections = all().map { it.second }
        current = if (Recents.get(context).isEmpty()) 1 else 0
        renderTabs()
        scroll.post { scroll.scrollTo(0, grid.sectionTop(current)); highlight(current) }
    }

    private fun renderTabs() {
        tabs.removeAllViews()
        all().forEachIndexed { i, (name, _) ->
            tabs.addView(TextView(context).apply {
                text = name.substringBefore(' ')
                contentDescription = name.substringAfter(' ')
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); gravity = Gravity.CENTER
                setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
                if (i == current) setBackgroundColor(theme.selectedBg)
                setOnClickListener { select(i) }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
    }

    /** Tab tapped: jump to that section of the one long list. */
    private fun select(i: Int) {
        scroll.scrollTo(0, grid.sectionTop(i))
        highlight(i)
    }

    private fun highlight(i: Int) {
        if (i == current && tabs.getChildAt(i)?.background != null) return
        current = i
        for (k in 0 until tabs.childCount) tabs.getChildAt(k).setBackgroundColor(if (k == i) theme.selectedBg else Color.TRANSPARENT)
    }

    /** Recently used emoji (not sensitive, kept in plain preferences). */
    object Recents {
        private const val KEY = "recent_emoji"
        private fun p(c: Context) = c.getSharedPreferences("flickime", Context.MODE_PRIVATE)
        fun get(c: Context): List<String> = p(c).getString(KEY, "")!!.split('\u0001').filter { it.isNotEmpty() }
        fun add(c: Context, e: String) {
            val list = (listOf(e) + get(c).filter { it != e }).take(40)
            p(c).edit().putString(KEY, list.joinToString("\u0001")).apply()
        }
    }
}
