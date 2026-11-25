package dev.flickime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** A few glass keys on the backdrop, redrawn live while the sliders move. */
class GlassPreview(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val image = BackgroundImage.load(context)
    var params = GlassParams()
        set(v) { field = v; invalidate() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 22 * dp }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (2 * 62 * dp).toInt())

    override fun onDraw(c: Canvas) {
        val theme = Theme(true, night, dp, params)
        GlassBackdrop(night, false, image, params).apply { setBounds(0, 0, width, height) }.draw(c)
        val labels = listOf("あ", "か", "さ", "⌫", "た", "な", "は", "⏎")
        val cols = 4; val cw = width / cols.toFloat(); val rh = height / 2f; val m = 4 * dp
        labels.forEachIndexed { i, s ->
            val r = RectF((i % cols) * cw + m, (i / cols) * rh + m, (i % cols + 1) * cw - m, (i / cols + 1) * rh - m)
            val kind = when (s) { "⏎" -> Theme.Kind.PRIMARY; "⌫" -> Theme.Kind.ACCENT; else -> Theme.Kind.NORMAL }
            theme.drawKey(c, r, kind)
            label.color = if (kind == Theme.Kind.PRIMARY) android.graphics.Color.WHITE else theme.text
            c.drawText(s, r.centerX(), r.centerY() + label.textSize / 3, label)
        }
    }
}
