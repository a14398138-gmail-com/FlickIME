package dev.flickime.engine

import kotlin.math.hypot
import kotlin.math.ln

/**
 * Shape-matching glide decoder (SHARK2-style, simplified).
 * [words] is ordered most frequent first.
 */
class GlideDecoder(words: List<String>) {
    class P(val x: Float, val y: Float)

    private val rank = HashMap<String, Int>()
    private val byEnds = HashMap<String, MutableList<String>>()
    /** lowercase key -> how the user wrote it (e.g. "flickime" -> "FlickIME") */
    private val display = HashMap<String, String>()

    init {
        words.forEachIndexed { i, w -> put(w, i) }
    }

    private fun put(w: String, r: Int) {
        if (w.length < 2) return
        if (rank.containsKey(w)) { rank[w] = minOf(rank[w]!!, r); return }
        rank[w] = r
        byEnds.getOrPut("${w.first()}${w.last()}") { ArrayList() }.add(w)
    }

    fun contains(word: String) = rank.containsKey(word.lowercase())

    /** Adds a word the user typed by hand; it ranks like a fairly common word. */
    fun addUserWord(word: String) {
        val key = word.lowercase()
        if (key.length < 2 || !key.all { it in 'a'..'z' || it == '\'' }) return
        if (word != key) display[key] = word
        put(key, USER_RANK)
    }

    private fun resample(pts: List<P>, n: Int): List<P> {
        if (pts.size == 1) return List(n) { pts[0] }
        val seg = FloatArray(pts.size)
        for (i in 1 until pts.size) seg[i] = seg[i - 1] + hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        val total = seg.last()
        if (total == 0f) return List(n) { pts[0] }
        val out = ArrayList<P>(n)
        var j = 1
        for (k in 0 until n) {
            val d = total * k / (n - 1)
            while (j < pts.size - 1 && seg[j] < d) j++
            val span = seg[j] - seg[j - 1]
            val t = if (span == 0f) 0f else (d - seg[j - 1]) / span
            out.add(P(pts[j - 1].x + (pts[j].x - pts[j - 1].x) * t, pts[j - 1].y + (pts[j].y - pts[j - 1].y) * t))
        }
        return out
    }

    private fun length(pts: List<P>): Float {
        var s = 0f
        for (i in 1 until pts.size) s += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        return s
    }

    /** The nearest few letters (always at least the closest two), within [radius]. */
    private fun near(p: P, centers: Map<Char, P>, radius: Float): List<Char> {
        val sorted = centers.entries.map { it.key to hypot(it.value.x - p.x, it.value.y - p.y) }.sortedBy { it.second }
        return sorted.filterIndexed { i, e -> i < 2 || e.second <= radius }.take(4).map { it.first }
    }

    /**
     * Returns up to [limit] words, best first. Keys are usually taller than wide, so the
     * y axis is rescaled by [keyWidth]/[keyHeight] to make every key square before matching.
     */
    fun decode(path: List<P>, centers: Map<Char, P>, keyWidth: Float, keyHeight: Float = keyWidth, limit: Int = 8): List<String> {
        if (path.size < 2) return emptyList()
        val sy = keyWidth / keyHeight
        val sp = path.map { P(it.x, it.y * sy) }
        val sc = centers.mapValues { P(it.value.x, it.value.y * sy) }
        return decodeScaled(sp, sc, keyWidth, limit, strict = true).ifEmpty { decodeScaled(sp, sc, keyWidth, limit, strict = false) }
    }

    private fun decodeScaled(path: List<P>, centers: Map<Char, P>, keyWidth: Float, limit: Int, strict: Boolean): List<String> {
        val n = 48
        val user = resample(path, n)
        val userLen = length(path)
        val radius = keyWidth * (if (strict) 1.3f else 2.2f)
        val starts = near(path.first(), centers, radius)
        val ends = near(path.last(), centers, radius)
        val scored = ArrayList<Pair<String, Float>>()
        for (s in starts) for (e in ends) {
            val list = byEnds["$s$e"] ?: continue
            for (w in list) {
                val ideal = ArrayList<P>(w.length)
                var ok = true
                for (ch in w) {
                    if (ch == '\'') continue
                    val c = centers[ch]
                    if (c == null) { ok = false; break }
                    if (ideal.isEmpty() || ideal.last() !== c) ideal.add(c)
                }
                if (!ok || ideal.size < 2) continue
                val idealLen = length(ideal)
                if (strict && (idealLen > userLen * 1.8f + keyWidth || idealLen < userLen * 0.45f - keyWidth)) continue
                val r = resample(ideal, n)
                var d = 0f
                for (k in 0 until n) d += hypot(r[k].x - user[k].x, r[k].y - user[k].y)
                d /= n * keyWidth
                // every letter's key should be visited somewhere along the path
                var miss = 0f
                for (c in ideal) {
                    var best = Float.MAX_VALUE
                    for (p in user) best = minOf(best, hypot(c.x - p.x, c.y - p.y))
                    if (best > keyWidth * 0.7f) miss += best / keyWidth
                }
                val freq = 0.06f * ln((rank[w] ?: 40000) + 10f)
                scored.add(w to d + 0.5f * miss + freq)
            }
        }
        return scored.sortedBy { it.second }.take(limit).map { display[it.first] ?: it.first }
    }

    companion object { const val USER_RANK = 300 }
}
