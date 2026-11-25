package dev.flickime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** Colours and key rendering for one keyboard look. */
/** User-tunable glass look; every value is a percentage of the default (100 = default). */
data class GlassParams(
    val body: Int = 100,      // key opacity
    val sheen: Int = 100,     // specular highlight
    val rim: Int = 100,       // rim light
    val radius: Int = 100,    // corner roundness
    val shadow: Int = 100,    // drop shadow
    val color: Int = 100,     // backdrop colour blobs
    val veil: Int = 100,      // veil over a background image
) {
    companion object {
        /** key, label, max */
        val fields = listOf(
            Triple("body", "キーの不透明度", 250), Triple("sheen", "ハイライト", 250), Triple("rim", "ふちの光", 250),
            Triple("radius", "角の丸み", 250), Triple("shadow", "影", 300), Triple("color", "背景の色の強さ", 200),
            Triple("veil", "背景画像に重ねる膜（白/黒）", 300),
        )
    }
    fun get(key: String) = when (key) { "body" -> body; "sheen" -> sheen; "rim" -> rim; "radius" -> radius; "shadow" -> shadow; "color" -> color; else -> veil }
    fun with(key: String, v: Int) = when (key) {
        "body" -> copy(body = v); "sheen" -> copy(sheen = v); "rim" -> copy(rim = v); "radius" -> copy(radius = v)
        "shadow" -> copy(shadow = v); "color" -> copy(color = v); else -> copy(veil = v)
    }
}

private fun pct(a: Int, p: Int) = (a * p / 100).coerceIn(0, 255)

class Theme(val glass: Boolean, val night: Boolean, private val dp: Float, val params: GlassParams = GlassParams()) {
    val text = if (glass) (if (night) Color.WHITE else Color.rgb(20, 24, 40)) else if (night) Color.WHITE else Color.rgb(30, 30, 30)
    val englishText = if (glass && night) Color.rgb(140, 190, 255) else Color.rgb(50, 110, 220)
    val primary = Color.rgb(90, 140, 240)
    val selectedBg = if (glass) Color.argb(if (night) 70 else 110, 255, 255, 255)
        else if (night) Color.rgb(70, 90, 130) else Color.rgb(190, 210, 245)

    val popupFill = if (glass) Color.argb(235, 90, 140, 240) else Color.rgb(90, 140, 240)
    val popupText = Color.WHITE
    val popupGuide = if (glass) Color.argb(if (night) 150 else 200, if (night) 60 else 255, if (night) 66 else 255, if (night) 90 else 255)
        else if (night) Color.argb(230, 80, 80, 86) else Color.argb(235, 255, 255, 255)

    /** Solid background used by the standard theme (glass draws [GlassBackdrop] instead). */
    val background = if (night) Color.rgb(28, 28, 30) else Color.rgb(226, 228, 232)
    private val keyColor = if (night) Color.rgb(66, 66, 70) else Color.WHITE
    private val accentColor = if (night) Color.rgb(48, 48, 52) else Color.rgb(196, 200, 207)
    private val pressedColor = if (night) Color.rgb(96, 120, 170) else Color.rgb(170, 196, 245)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * dp }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)

    enum class Kind { NORMAL, ACCENT, PRIMARY, PRESSED }

    fun drawKey(c: Canvas, r: RectF, kind: Kind) {
        val radius = (if (glass) 14f * params.radius / 100f else 6f) * dp
        if (!glass) {
            fill.shader = null
            fill.color = when (kind) { Kind.PRESSED -> pressedColor; Kind.PRIMARY -> primary; Kind.ACCENT -> accentColor; Kind.NORMAL -> keyColor }
            c.drawRoundRect(r, radius, radius, fill)
            return
        }
        // soft drop shadow under the glass
        shadow.shader = null
        shadow.color = Color.argb(pct(if (night) 60 else 28, params.shadow), 0, 0, 20)
        c.drawRoundRect(RectF(r.left, r.top + 2 * dp, r.right, r.bottom + 2 * dp), radius, radius, shadow)

        // translucent body: brighter at the top, like light passing through a lens
        val (topA, botA, tint) = when (kind) {
            Kind.PRESSED -> Triple(150, 90, Color.WHITE)
            Kind.PRIMARY -> Triple(210, 160, primary)
            Kind.ACCENT -> Triple(if (night) 40 else 70, if (night) 18 else 35, Color.WHITE)
            Kind.NORMAL -> Triple(if (night) 70 else 150, if (night) 30 else 80, Color.WHITE)
        }
        fill.shader = LinearGradient(0f, r.top, 0f, r.bottom,
            withAlpha(tint, pct(topA, params.body)), withAlpha(tint, pct(botA, params.body)), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, fill)

        // specular highlight across the upper half
        val hl = RectF(r.left + 2 * dp, r.top + 1.5f * dp, r.right - 2 * dp, r.top + r.height() * 0.48f)
        sheen.shader = LinearGradient(0f, hl.top, 0f, hl.bottom,
            Color.argb(pct(if (kind == Kind.PRIMARY) 90 else 70, params.sheen), 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRoundRect(hl, radius * 0.8f, radius * 0.8f, sheen)

        // rim light: bright on the top edge, fading towards the bottom
        rim.shader = LinearGradient(0f, r.top, 0f, r.bottom,
            Color.argb(pct(200, params.rim), 255, 255, 255), Color.argb(pct(if (night) 30 else 60, params.rim), 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, rim)
    }

    private fun withAlpha(color: Int, a: Int) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        fun of(c: Context): Theme {
            val night = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return Theme(Prefs.glassTheme(c), night, c.resources.displayMetrics.density, Prefs.glassParams(c))
        }
    }
}

/** Colourful, softly lit backdrop the glass keys sit on. Translucent when the window behind is blurred. */
class GlassBackdrop(
    private val night: Boolean,
    private val translucent: Boolean,
    private val image: Bitmap? = null,
    private val params: GlassParams = GlassParams(),
) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    override fun draw(c: Canvas) {
        val b = bounds
        val w = b.width().toFloat(); val h = b.height().toFloat()
        if (image != null) {
            // centre-crop the user's picture, then a light veil so labels stay readable
            val scale = maxOf(w / image.width, h / image.height)
            val sw = w / scale; val sh = h / scale
            val sx = (image.width - sw) / 2; val sy = (image.height - sh) / 2
            p.shader = null
            c.drawBitmap(image, Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt()), b, p)
            p.color = if (night) Color.argb(pct(80, params.veil), 0, 0, 0) else Color.argb(pct(70, params.veil), 255, 255, 255)
            c.drawRect(b, p)
            return
        }
        val baseA = if (translucent) 150 else 255
        p.shader = LinearGradient(0f, 0f, 0f, h,
            if (night) Color.argb(baseA, 18, 22, 38) else Color.argb(baseA, 232, 238, 250),
            if (night) Color.argb(baseA, 10, 12, 22) else Color.argb(baseA, 214, 222, 240), Shader.TileMode.CLAMP)
        c.drawRect(b, p)
        fun blob(cx: Float, cy: Float, r: Float, color: Int) {
            p.shader = RadialGradient(cx, cy, r, color, Color.argb(0, Color.red(color), Color.green(color), Color.blue(color)), Shader.TileMode.CLAMP)
            c.drawRect(b, p)
        }
        val a = pct(if (night) 120 else 110, params.color)
        if (a < 30) return
        blob(w * 0.12f, h * 0.20f, w * 0.55f, Color.argb(a, 80, 120, 255))
        blob(w * 0.85f, h * 0.30f, w * 0.50f, Color.argb(a, 190, 90, 240))
        blob(w * 0.55f, h * 0.95f, w * 0.60f, Color.argb(a, 40, 200, 200))
        blob(w * 0.95f, h * 0.90f, w * 0.35f, Color.argb(a - 30, 255, 140, 120))
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = if (translucent) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE
}

/** The user's chosen keyboard background, stored downscaled in app storage. */
object BackgroundImage {
    private fun file(c: Context) = java.io.File(c.filesDir, "background.jpg")

    fun load(c: Context): Bitmap? = file(c).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    fun clear(c: Context) { file(c).delete(); Prefs.touchBackground(c) }

    /** Decodes [bytes] (any size), scales to at most the screen width, saves as JPEG. */
    fun save(c: Context, bytes: ByteArray): Boolean {
        val maxW = c.resources.displayMetrics.widthPixels.coerceAtLeast(720)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return false
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val scaled = if (bmp.width > maxW) Bitmap.createScaledBitmap(bmp, maxW, bmp.height * maxW / bmp.width, true) else bmp
        val buf = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, buf)
        atomicWrite(file(c), buf.toByteArray())
        Prefs.touchBackground(c)
        return true
    }
}
