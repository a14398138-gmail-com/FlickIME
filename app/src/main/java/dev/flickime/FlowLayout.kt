package dev.flickime

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Lays children out left-to-right, wrapping to new rows; each row is stretched to fill the width. */
class FlowLayout(context: Context) : ViewGroup(context) {
    var rowHeight = 0
    private val rows = ArrayList<IntRange>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val hSpec = MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY)
        rows.clear()
        var start = 0; var used = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), hSpec)
            if (used + c.measuredWidth > width && i > start) { rows.add(start until i); start = i; used = 0 }
            used += c.measuredWidth
        }
        if (start < childCount) rows.add(start until childCount)
        // stretch each row so cells fill the line, like Gboard's expanded candidates
        for (r in rows) {
            val total = r.sumOf { getChildAt(it).measuredWidth }
            val extra = (width - total) / (r.last - r.first + 1)
            for (i in r) {
                val c = getChildAt(i)
                c.measure(MeasureSpec.makeMeasureSpec(c.measuredWidth + extra, MeasureSpec.EXACTLY), hSpec)
            }
        }
        setMeasuredDimension(width, rows.size * rowHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        rows.forEachIndexed { ri, range ->
            var x = 0
            for (i in range) {
                val c: View = getChildAt(i)
                c.layout(x, ri * rowHeight, x + c.measuredWidth, (ri + 1) * rowHeight)
                x += c.measuredWidth
            }
        }
    }
}
