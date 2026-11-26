package dev.flickime.engine

import dev.flickime.Layouts
import org.junit.Test
import java.io.File
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.random.Random

/**
 * Glide accuracy on simulated human gestures over the real QWERTY geometry (tall keys):
 * every letter is aimed at with Gaussian error, corners are rounded, speed varies, the finger jitters.
 * Words are drawn by frequency (Zipf over en_words.txt), like real use.
 *   GLIDE_N (default 600), GLIDE_SIGMA (aim error in key widths, default 0.3)
 * Also simulates personal use: a user with their own vocabulary keeps gliding; accuracy before/after learning.
 */
class GlideBenchTest {
    private val words = File("src/main/assets/en_words.txt").readLines().filter { it.isNotBlank() }
    private val width = 1080f
    private val kw = width / 13f
    private val kh = kw * 1.6f
    private val centers: Map<Char, GlideDecoder.P> = Layouts.qwerty.keys.mapNotNull { k ->
        k.letter?.let { it to GlideDecoder.P((k.x + k.w / 2) * width, (k.row + 0.5f) * kh) }
    }.toMap()

    private fun gauss(r: Random): Float { var s = 0.0; repeat(6) { s += r.nextDouble() }; return ((s - 3.0) / 0.7071).toFloat() }

    fun gesture(word: String, r: Random, sigma: Float, bias: GlideDecoder.P = GlideDecoder.P(0f, 0f)): List<GlideDecoder.P> {
        // aimed points (repeated letters collapse)
        val aim = ArrayList<GlideDecoder.P>()
        for (ch in word) {
            val c = centers[ch] ?: continue
            val p = GlideDecoder.P(c.x + gauss(r) * sigma * kw + bias.x * kw, c.y + gauss(r) * sigma * kw + bias.y * kw)
            if (aim.isEmpty() || hypot(aim.last().x - c.x, aim.last().y - c.y) > 1f) aim.add(p)
        }
        if (aim.size == 1) aim.add(GlideDecoder.P(aim[0].x + kw * 0.1f, aim[0].y))
        // rounded corners: quadratic curves between edge midpoints
        val pts = ArrayList<GlideDecoder.P>()
        fun lerp(a: GlideDecoder.P, b: GlideDecoder.P, t: Float) = GlideDecoder.P(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        val cut = 0.25f + r.nextFloat() * 0.2f
        val nodes = ArrayList<GlideDecoder.P>(); nodes.add(aim.first())
        for (i in 1 until aim.size - 1) { nodes.add(lerp(aim[i], aim[i - 1], cut)); nodes.add(aim[i]); nodes.add(lerp(aim[i], aim[i + 1], cut)) }
        nodes.add(aim.last())
        val step = kw / (4f + r.nextFloat() * 4f)
        fun emit(a: GlideDecoder.P, b: GlideDecoder.P) {
            val d = hypot(b.x - a.x, b.y - a.y); val k = maxOf(1, (d / step).toInt())
            for (s in 0 until k) { val p = lerp(a, b, s.toFloat() / k); pts.add(GlideDecoder.P(p.x + gauss(r) * 0.04f * kw, p.y + gauss(r) * 0.04f * kw)) }
        }
        var i = 0
        emit(nodes[0], if (nodes.size > 1) nodes[1] else nodes[0])
        i = 1
        while (i + 2 < nodes.size) {
            // quadratic bezier from nodes[i] via nodes[i+1] to nodes[i+2]
            val a = nodes[i]; val c = nodes[i + 1]; val b = nodes[i + 2]
            for (s in 0 until 6) { val t = s / 6f; pts.add(lerp(lerp(a, c, t), lerp(c, b, t), t)) }
            if (i + 3 < nodes.size) emit(b, nodes[i + 3])
            i += 3
        }
        pts.add(nodes.last())
        return pts
    }

    private fun zipf(r: Random, n: Int): Int {
        // rank ~ 1/r over the first n words
        val h = ln(n.toDouble())
        return minOf(n - 1, kotlin.math.exp(r.nextDouble() * h).toInt() - 1).coerceAtLeast(0)
    }

    @Test fun bench() {
        val n = (System.getenv("GLIDE_N") ?: "600").toInt()
        val sigma = (System.getenv("GLIDE_SIGMA") ?: "0.3").toFloat()
        System.getenv("G_LOC")?.let { GlideDecoder.LOC_W = it.toFloat() }
        System.getenv("G_SHAPE")?.let { GlideDecoder.SHAPE_W = it.toFloat() }
        System.getenv("G_MISS")?.let { GlideDecoder.MISS_W = it.toFloat() }
        System.getenv("G_FREQ")?.let { GlideDecoder.FREQ_W = it.toFloat() }
        val dec = GlideDecoder(words)
        val r = Random(42)
        var top1 = 0; var top3 = 0; var cnt = 0
        val t0 = System.nanoTime()
        val miss = StringBuilder()
        repeat(n) {
            val w = words[zipf(r, 20000)]
            if (w.length < 2 || !w.all { it in 'a'..'z' }) return@repeat
            val res = dec.decode(gesture(w, r, sigma), centers, kw, kh)
            cnt++
            if (res.firstOrNull() == w) top1++ else if (miss.length < 600) miss.append("$w→${res.take(3)} ")
            if (w in res.take(3)) top3++
        }
        println("GLIDE n=$cnt sigma=$sigma top1=${"%.1f".format(100.0 * top1 / cnt)}% top3=${"%.1f".format(100.0 * top3 / cnt)}% ${(System.nanoTime() - t0) / 1_000_000 / cnt}ms/word")
        println("GLIDEMISS $miss")
    }

    /** A user with a personal vocabulary (mid-frequency words + their own words), a habit of landing low-right. */
    @Test fun personal() {
        System.getenv("P_UNI")?.let { GlideDecoder.PERSONAL_UNI = it.toFloat() }
        System.getenv("P_PAIR")?.let { GlideDecoder.PERSONAL_PAIR = it.toFloat() }
        System.getenv("P_BIAS")?.let { GlideDecoder.BIAS_RATE = it.toFloat() }
        val r = Random(7)
        val vocab = (List(60) { words[300 + r.nextInt(8000)] } + listOf("flickime", "kotlin", "gboard", "zenz", "llama")).distinct()
        val bias = GlideDecoder.P(0.15f, 0.35f)
        val dec = GlideDecoder(words)
        listOf("flickime", "kotlin", "gboard", "zenz", "llama").forEach { dec.addUserWord(it) }
        var prev = ""
        fun round(learn: Boolean): Double {
            var ok = 0; var n = 0
            repeat(300) {
                val w = vocab[zipf(r, vocab.size)]
                val g = gesture(w, r, 0.3f, bias)
                val res = dec.decode(g, centers, kw, kh, previous = prev)
                n++; if (res.firstOrNull() == w) ok++
                if (learn) dec.learn(w, previous = prev, path = g, centers = centers, keyWidth = kw, keyHeight = kh)
                prev = w
            }
            return 100.0 * ok / n
        }
        fun general(): Double {
            val g = Random(99); var ok = 0; var n = 0
            repeat(300) { val w = words[zipf(g, 20000)]; if (w.length < 2 || !w.all { it in 'a'..'z' }) return@repeat
                n++; if (dec.decode(gesture(w, g, 0.3f, bias), centers, kw, kh).firstOrNull() == w) ok++ }
            return 100.0 * ok / n
        }
        val genBefore = general()
        val before = round(learn = false)
        round(learn = true)  // a while of use
        val after = round(learn = false)
        println("GLIDEGENERAL (same user, everyday words) before=${"%.1f".format(genBefore)}% after=${"%.1f".format(general())}%")
        println("GLIDEPERSONAL bias=${"%.2f".format(dec.biasX)},${"%.2f".format(dec.biasY)} before=${"%.1f".format(before)}% after=${"%.1f".format(after)}%")
    }
}
