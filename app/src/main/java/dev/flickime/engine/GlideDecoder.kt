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
    /** All words, sorted, for prefix completion while typing */
    private val sortedWords = java.util.TreeSet<String>()
    /** suggestions the user removed (long-press) */
    private val blocked = HashSet<String>()

    @Synchronized fun block(word: String) { blocked.add(word.lowercase()) }
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
        sortedWords.add(w)
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

    /** Centred and scaled to unit size (longest side of the bounding box). */
    private fun normShape(pts: List<P>): List<P> {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var cx = 0f; var cy = 0f
        for (p in pts) { minX = minOf(minX, p.x); maxX = maxOf(maxX, p.x); minY = minOf(minY, p.y); maxY = maxOf(maxY, p.y); cx += p.x; cy += p.y }
        cx /= pts.size; cy /= pts.size
        val sc = maxOf(maxX - minX, maxY - minY, 1e-3f)
        return pts.map { P((it.x - cx) / sc, (it.y - cy) / sc) }
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
    fun decode(path: List<P>, centers: Map<Char, P>, keyWidth: Float, keyHeight: Float = keyWidth, limit: Int = 8,
               previous: String = ""): List<String> {
        if (path.size < 2) return emptyList()
        val sy = keyWidth / keyHeight
        // the user's habitual offset (learned), taken out before matching
        val bx = biasX * keyWidth; val by = biasY * keyHeight
        val sp = path.map { P(it.x - bx, (it.y - by) * sy) }
        prevWord = previous.lowercase()
        val sc = centers.mapValues { P(it.value.x, it.value.y * sy) }
        return decodeScaled(sp, sc, keyWidth, limit, strict = true).ifEmpty { decodeScaled(sp, sc, keyWidth, limit, strict = false) }
    }

    private fun decodeScaled(path: List<P>, centers: Map<Char, P>, keyWidth: Float, limit: Int, strict: Boolean): List<String> {
        val n = 48
        val user = resample(path, n)
        val userN = normShape(user)
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
                // shape channel (SHARK2): same gesture drawn smaller / bigger / shifted still matches
                val rn = normShape(r)
                var ds = 0f
                for (k in 0 until n) ds += hypot(rn[k].x - userN[k].x, rn[k].y - userN[k].y)
                ds /= n
                // every letter's key should be visited somewhere along the path
                var miss = 0f
                for (c in ideal) {
                    var best = Float.MAX_VALUE
                    for (p in user) best = minOf(best, hypot(c.x - p.x, c.y - p.y))
                    if (best > keyWidth * 0.7f) miss += best / keyWidth
                }
                val freq = FREQ_W * ln((rank[w] ?: 40000) + 10f)
                scored.add(w to LOC_W * d + SHAPE_W * ds + MISS_W * miss + freq - personal(w))
            }
        }
        return scored.sortedBy { it.second }.take(limit).map { display[it.first] ?: it.first }
    }

    /**
     * Completions of the word being typed, best first: the typed word itself when it is a word, then
     * longer words by frequency and this user's picks (more so after [previous]).
     */
    @Synchronized fun complete(prefix: String, previous: String = "", limit: Int = 6): List<String> {
        val p = prefix.lowercase()
        if (p.isEmpty()) return emptyList()
        prevWord = previous.lowercase()
        val sub = sortedWords.subSet(p, true, p + '\uffff', false)
        val scored = ArrayList<Pair<String, Float>>()
        for (w in sub) if (w !in blocked) scored.add(w to FREQ_W * ln((rank[w] ?: 40000) + 10f) - personal(w) + 0.02f * (w.length - p.length))
        val out = scored.sortedBy { it.second }.map { it.first }.take(limit).toMutableList()
        if (p in rank && p !in blocked) { out.remove(p); out.add(0, p) }
        return out.take(limit).map { display[it] ?: it }
    }

    /** Words this user typed right after [previous], most frequent first (next-word prediction). */
    @Synchronized fun nextWords(previous: String, limit: Int = 5): List<String> {
        val pv = previous.lowercase()
        if (pv.isEmpty()) return emptyList()
        return pairs.entries.filter { it.key.startsWith("$pv ") && it.value >= 2 && it.key.substringAfter(' ') !in blocked }
            .sortedByDescending { it.value }.take(limit).map { it.key.substringAfter(' ').let { w -> display[w] ?: w } }
    }

    // ---- personalisation: what this user glides, after which word, and where their finger lands ----
    private val counts = HashMap<String, Int>()
    private val pairs = HashMap<String, Int>()
    @Volatile private var prevWord = ""
    @Volatile var biasX = 0f; private set
    @Volatile var biasY = 0f; private set
    var version = 0; private set

    /** Bonus (subtracted from the distance) for words this user picks, more so right after the same word. */
    private fun personal(w: String): Float {
        val c = counts[w] ?: 0
        val p = if (prevWord.isEmpty()) 0 else pairs[prevWord + " " + w] ?: 0
        return PERSONAL_UNI * ln(1f + c) + PERSONAL_PAIR * ln(1f + p)
    }

    /**
     * The user ended up with [word] (kept the first candidate, picked another, or typed it): count it, and
     * when [path] is given, move the learned offset towards where this gesture started and ended
     * relative to the word's first and last keys.
     */
    @Synchronized fun learn(word: String, previous: String = "", path: List<P>? = null, centers: Map<Char, P>? = null,
                            keyWidth: Float = 1f, keyHeight: Float = keyWidth) {
        val w = word.lowercase()
        if (w.length < 2 || !w.all { it in 'a'..'z' || it == '\'' }) return
        counts[w] = minOf((counts[w] ?: 0) + 1, 1000)
        val pv = previous.lowercase()
        if (pv.isNotEmpty()) pairs["$pv $w"] = minOf((pairs["$pv $w"] ?: 0) + 1, 1000)
        if (!rank.containsKey(w)) put(w, USER_RANK)
        if (path != null && centers != null && path.size >= 2) {
            val a = centers[w.first()]; val b = centers[w.last { it != '\'' }]
            if (a != null && b != null) {
                val dx = ((path.first().x - a.x) + (path.last().x - b.x)) / 2 / keyWidth
                val dy = ((path.first().y - a.y) + (path.last().y - b.y)) / 2 / keyHeight
                biasX = (biasX * (1 - BIAS_RATE) + dx * BIAS_RATE).coerceIn(-0.6f, 0.6f)
                biasY = (biasY * (1 - BIAS_RATE) + dy * BIAS_RATE).coerceIn(-0.6f, 0.6f)
            }
        }
        version++
    }

    @Synchronized fun serialize(): String = buildString {
        append("B\t").append(biasX).append('\t').append(biasY).append('\n')
        counts.forEach { (w, c) -> append("U\t").append(w).append('\t').append(c).append('\n') }
        pairs.forEach { (k, c) -> append("P\t").append(k).append('\t').append(c).append('\n') }
        blocked.forEach { append("X\t").append(it).append('\n') }
    }

    @Synchronized fun load(text: String) {
        for (line in text.lineSequence()) {
            val f = line.split('\t')
            when (f.getOrNull(0)) {
                "B" -> if (f.size == 3) { biasX = f[1].toFloatOrNull() ?: 0f; biasY = f[2].toFloatOrNull() ?: 0f }
                "U" -> if (f.size == 3) { counts[f[1]] = f[2].toIntOrNull() ?: 0; if (!rank.containsKey(f[1])) put(f[1], USER_RANK) }
                "P" -> if (f.size == 3) pairs[f[1]] = f[2].toIntOrNull() ?: 0
                "X" -> if (f.size == 2) blocked.add(f[1])
            }
        }
    }

    companion object {
        const val USER_RANK = 300
        var LOC_W = 1f
        var SHAPE_W = 3f
        var MISS_W = 0.05f
        var FREQ_W = 0.15f
        var PERSONAL_UNI = 0.25f
        var PERSONAL_PAIR = 0.15f
        // learning a finger offset did not help in simulation (the shape channel already absorbs it): off
        var BIAS_RATE = 0f
    }
}
