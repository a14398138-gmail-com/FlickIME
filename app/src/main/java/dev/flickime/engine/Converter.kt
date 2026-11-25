package dev.flickime.engine

import java.util.PriorityQueue

/** A conversion candidate covering the first [consumed] chars of the reading. */
data class Candidate(
    val surface: String,
    val consumed: Int,
    val kind: Kind,
    /** For SENTENCE candidates: the (readingLength, surface) words it is made of. */
    val segments: List<Pair<Int, String>>? = null,
    /** For PREDICTION candidates: the full reading of the predicted word (what gets learned). */
    val fullReading: String? = null,
) {
    enum class Kind { SENTENCE, WORD, PREDICTION, ENGLISH, SYMBOL, KANA }
}

/**
 * What the user taught the converter:
 *  - a weight for (reading, surface) pairs they chose: an explicit pick counts 2,
 *    a word inside a sentence they accepted counts 1;
 *  - a penalty for the top candidate they passed over to pick something else;
 *  - word pairs from what they committed (used when re-ranking whole sentences).
 * Every change can be recorded in an [Undo] so an immediate correction can take it back.
 */
class UserHistory {
    private val pos = HashMap<String, Int>()
    private val neg = HashMap<String, Int>()
    private val pairs = HashMap<String, Int>()
    private val readings = HashSet<String>()
    /** reading -> surfaces with a positive weight, sorted by reading for prefix lookups */
    private val byReading = java.util.TreeMap<String, MutableSet<String>>()

    @Volatile var version = 0
        private set

    class Undo {
        internal val steps = ArrayList<Triple<HashMap<String, Int>, String, Int?>>()
        val isEmpty get() = steps.isEmpty()
    }

    private fun key(r: String, s: String) = "$r\t$s"
    private fun ok(vararg s: String) = s.all { it.isNotEmpty() && '\t' !in it && '\n' !in it }

    private fun set(m: HashMap<String, Int>, k: String, v: Int, undo: Undo?) {
        undo?.steps?.add(Triple(m, k, m[k]))
        if (v <= 0) m.remove(k) else m[k] = v
        version++
    }

    @Synchronized fun has(reading: String) = reading in readings
    @Synchronized fun isEmpty() = pos.isEmpty() && neg.isEmpty()
    @Synchronized fun count(reading: String, surface: String) = pos[key(reading, surface)] ?: 0

    /** Cost bonus in Mozc cost units: positive = preferred, negative = passed over. */
    @Synchronized fun score(reading: String, surface: String): Int {
        if (reading !in readings) return 0
        val k = key(reading, surface)
        return minOf(pos[k] ?: 0, 12) * 450 - minOf(neg[k] ?: 0, 4) * 600
    }

    @Synchronized fun pair(w1: String, w2: String) = pairs["$w1\t$w2"] ?: 0

    @Synchronized fun learn(reading: String, surface: String, weight: Int = 2, undo: Undo? = null) {
        if (!ok(reading, surface)) return
        val k = key(reading, surface)
        readings.add(reading)
        byReading.getOrPut(reading) { LinkedHashSet() }.add(surface)
        set(pos, k, (pos[k] ?: 0) + weight, undo)
        // choosing it on purpose cancels an earlier "passed over"
        if (weight >= 2 && neg.containsKey(k)) set(neg, k, 0, undo)
    }

    @Synchronized fun penalize(reading: String, surface: String, undo: Undo? = null) {
        if (!ok(reading, surface)) return
        val k = key(reading, surface)
        readings.add(reading)
        set(neg, k, (neg[k] ?: 0) + 1, undo)
    }

    @Synchronized fun learnPair(w1: String, w2: String, undo: Undo? = null) {
        if (!ok(w1, w2)) return
        val k = "$w1\t$w2"
        set(pairs, k, (pairs[k] ?: 0) + 1, undo)
    }

    @Synchronized fun revert(undo: Undo) {
        for ((m, k, old) in undo.steps.asReversed()) if (old == null) m.remove(k) else m[k] = old
        undo.steps.clear()
        version++
    }

    @Synchronized fun clear() { pos.clear(); neg.clear(); pairs.clear(); readings.clear(); byReading.clear(); version++ }

    /** Drops everything learned for this word (long-press → delete on a candidate). */
    @Synchronized fun forget(reading: String, surface: String): Boolean {
        val k = key(reading, surface)
        val had = pos.remove(k) != null
        byReading[reading]?.let { it.remove(surface); if (it.isEmpty()) byReading.remove(reading) }
        pairs.keys.removeAll { it.startsWith("$surface\t") || it.endsWith("\t$surface") }
        version++
        return had
    }

    /**
     * Things the user committed whose reading starts with [prefix] (and is longer):
     * (reading, surface, score), most used first. Only deliberate picks count (weight >= 2).
     */
    @Synchronized fun completions(prefix: String, limit: Int): List<Triple<String, String, Int>> {
        if (prefix.isEmpty()) return emptyList()
        val out = ArrayList<Triple<String, String, Int>>()
        for ((r, surfaces) in byReading.subMap(prefix, true, prefix + '\uffff', false)) {
            if (r.length <= prefix.length) continue
            for (sf in surfaces) {
                // a bare kana commit (ぎんがが) is not a word worth suggesting
                if (sf == r) continue
                // from a single character, only things picked more than once
                if ((pos[key(r, sf)] ?: 0) < (if (prefix.length == 1) 4 else 2)) continue
                val sc = score(r, sf)
                if (sc > 0) out.add(Triple(r, sf, sc))
            }
        }
        return out.sortedByDescending { it.third }.take(limit)
    }

    @Synchronized fun serialize(): String = buildString {
        append("#v2\n")
        // keep the file bounded: drop single sentence-word sightings first
        val keepPos = if (pos.size > 60000) pos.filterValues { it > 1 } else pos
        keepPos.forEach { (k, v) -> append("p\t").append(k).append('\t').append(v).append('\n') }
        neg.forEach { (k, v) -> append("n\t").append(k).append('\t').append(v).append('\n') }
        val keepPairs = if (pairs.size > 60000) pairs.filterValues { it > 1 } else pairs
        keepPairs.forEach { (k, v) -> append("b\t").append(k).append('\t').append(v).append('\n') }
    }

    @Synchronized fun load(text: String) {
        clear()
        val lines = text.lineSequence().toList()
        if (lines.firstOrNull() == "#v2") {
            for (line in lines.drop(1)) {
                val p = line.split('\t')
                if (p.size != 4) continue
                val v = p[3].toIntOrNull() ?: continue
                when (p[0]) {
                    "p" -> { pos[key(p[1], p[2])] = v; readings.add(p[1]); byReading.getOrPut(p[1]) { LinkedHashSet() }.add(p[2]) }
                    "n" -> { neg[key(p[1], p[2])] = v; readings.add(p[1]) }
                    "b" -> pairs["${p[1]}\t${p[2]}"] = v
                }
            }
        } else {
            // v1: "reading surface count", one count per pick (= weight 2 now)
            for (line in lines) {
                val p = line.split('\t')
                if (p.size == 3) p[2].toIntOrNull()?.let {
                    pos[key(p[0], p[1])] = it * 2; readings.add(p[0]); byReading.getOrPut(p[0]) { LinkedHashSet() }.add(p[1])
                }
            }
        }
        version++
    }
}

class Converter(
    private val dict: Dictionary,
    private val english: Map<String, List<String>>,
    private val symbols: Map<String, List<String>> = emptyMap(),
    val history: UserHistory = UserHistory(),
    val userDict: UserDictionary = UserDictionary(),
    private val lm: LanguageModel? = null,
    /** POS ids allowed as dictionary predictions (assets/predict_ids.txt); null = all. */
    private val predictIds: Set<Int>? = null,
) {
    /** reading -> single kanji in Mozc's frequency order (assets/single_kanji.tsv), shown after the words. */
    @Volatile var singleKanji: Map<String, String> = emptyMap()

    private fun kanjiOf(reading: String): List<String> {
        val s = singleKanji[reading] ?: return emptyList()
        val out = ArrayList<String>(s.length); var i = 0
        while (i < s.length) { val n = Character.charCount(s.codePointAt(i)); out.add(s.substring(i, i + n)); i += n }
        return out
    }

    /** How much the language model counts against the dictionary costs when ranking sentences. */
    var lmWeight = DEFAULT_LM_WEIGHT
        set(v) { field = v; synchronized(this) { cache.clear(); zenzCache.clear() } }

    /** [entry] >= 0 points into the system dictionary; otherwise [text] holds the surface. */
    private class Node(
        val start: Int, val end: Int, val entry: Int, val text: String?,
        val lid: Int, val rid: Int, val cost: Int,
    ) {
        var total = Int.MAX_VALUE
        var prev: Node? = null
        var surfaceCache: String? = null
        var hashCache = 0L
        var hashed = false
    }

    private fun surfaceOf(n: Node): String = n.surfaceCache ?: (n.text ?: dict.surface(n.entry)).also { n.surfaceCache = it }
    /** The language model knows words by surface + reading (行っ/いっ ≠ 行っ/おこなっ). */
    private fun hashOf(n: Node, reading: String): Long {
        if (!n.hashed) {
            n.hashCache = LanguageModel.hash(surfaceOf(n) + '\u0001' + reading.substring(n.start, n.end))
            n.hashed = true
        }
        return n.hashCache
    }

    private class Lattice(val n: Int, val ends: Array<ArrayList<Node>>, val bos: Node)

    /** A whole-reading conversion: its words and its score (lower is better). */
    class Sentence(val segments: List<Pair<Int, String>>, val score: Int) {
        val surface: String get() = segments.joinToString("") { it.second }
    }

    private fun boost(reading: String, surface: String) = history.score(reading, surface)
    private fun boosted(w: UserDictionary.Word): Int = USER_COST - boost(w.reading, w.surface)

    private fun punctuation(c: Char): Pair<Int, Int>? = when (c) {
        '？', '?' -> 2648 to 0
        '！', '!' -> 2646 to 0
        '…', '‥' -> 2651 to 0
        '「', '『', '（' -> 2656 to 0
        '」', '』', '）' -> 2655 to 0
        '・' -> 2644 to 500
        '〜', '～' -> 2654 to 500
        else -> null
    }

    private fun build(reading: String): Lattice {
        val n = reading.length
        val ends = Array(n + 1) { ArrayList<Node>() }
        val bos = Node(0, 0, -1, "", 0, 0, 0).also { it.total = 0; it.surfaceCache = "" }
        ends[0].add(bos)
        val userEmpty = userDict.isEmpty()
        val historyEmpty = history.isEmpty()
        for (i in 0 until n) {
            val prevs = ends[i]
            if (prevs.isEmpty()) continue
            val bestByLid = HashMap<Int, Pair<Int, Node>>()
            fun bestFor(lid: Int): Pair<Int, Node> = bestByLid.getOrPut(lid) {
                var bc = Int.MAX_VALUE; var bn = prevs[0]
                for (p in prevs) {
                    val v = p.total + dict.connection(p.rid, lid)
                    if (v < bc) { bc = v; bn = p }
                }
                bc to bn
            }
            fun place(node: Node) {
                val (bc, bn) = bestFor(node.lid)
                node.total = bc + node.cost; node.prev = bn
                ends[node.end].add(node)
            }
            punctuation(reading[i])?.let { (id, cost) ->
                place(Node(i, i + 1, -1, reading[i].toString(), id, id, cost))
            }
            val maxLen = minOf(dict.maxReadingLen, n - i)
            for (len in 1..maxLen) {
                val range = dict.range(reading, i, len)
                val needR = !userEmpty || len == 1 || (range != null && !historyEmpty)
                val r = if (needR) reading.substring(i, i + len) else null
                val user = if (r == null || userEmpty) emptyList() else userDict.lookup(r)
                if (range == null && user.isEmpty() && len > 1) continue
                for (w in user) place(Node(i, i + len, -1, w.surface, w.posId, w.posId, boosted(w)))
                if (range != null) {
                    val learned = r != null && history.has(r)
                    for (e in range) {
                        val cost = if (learned) dict.cost(e) - boost(r!!, dict.surface(e)) else dict.cost(e)
                        place(Node(i, i + len, e, null, dict.lid(e), dict.rid(e), cost))
                    }
                }
                if (len == 1) place(Node(i, i + 1, -1, r, UNK_ID, UNK_ID, 12000)) // keeps the lattice connected
            }
        }
        return Lattice(n, ends, bos)
    }

    private fun toSegments(nodes: List<Node>) = nodes.map { (it.end - it.start) to surfaceOf(it) }

    private fun viterbi(lat: Lattice): List<Node> {
        var best: Node? = null; var bc = Int.MAX_VALUE
        for (p in lat.ends[lat.n]) {
            val v = p.total + dict.connection(p.rid, 0)
            if (v < bc) { bc = v; best = p }
        }
        val out = ArrayList<Node>()
        var cur = best
        while (cur != null && cur !== lat.bos) { out.add(cur); cur = cur.prev }
        out.reverse()
        return out
    }

    private class State(val node: Node, val g: Int, val next: State?) {
        val f get() = node.total + g
    }

    /** Distinct-surface k-best paths by backward A* over the forward Viterbi costs. */
    private fun nBest(lat: Lattice, k: Int): List<Pair<List<Node>, Int>> {
        val pq = PriorityQueue<State>(64, compareBy { it.f })
        var best = Int.MAX_VALUE
        for (e in lat.ends[lat.n]) {
            if (e.total == Int.MAX_VALUE) continue
            val s = State(e, dict.connection(e.rid, 0), null)
            best = minOf(best, s.f)
            pq.add(s)
        }
        val limit = if (best == Int.MAX_VALUE) Int.MAX_VALUE else best + NBEST_MARGIN
        val out = ArrayList<Pair<List<Node>, Int>>()
        val seen = HashSet<String>()
        var pops = 0
        while (pq.isNotEmpty() && out.size < k && pops < MAX_POPS) {
            val s = pq.poll()
            pops++
            if (s.node === lat.bos) {
                val nodes = ArrayList<Node>()
                var t = s.next
                while (t != null) { nodes.add(t.node); t = t.next }
                if (seen.add(nodes.joinToString("") { surfaceOf(it) })) out.add(nodes to s.f)
                continue
            }
            val g = s.g + s.node.cost
            for (p in lat.ends[s.node.start]) {
                if (p.total == Int.MAX_VALUE) continue
                val ng = g + dict.connection(p.rid, s.node.lid)
                if (p.total + ng > limit) continue
                pq.add(State(p, ng, s))
            }
        }
        return out
    }

    private fun lmScore(nodes: List<Node>, reading: String): Int {
        val m = lm ?: return 0
        var c = 0
        var prev2 = 0L
        var first = true
        var prev = LanguageModel.BOS
        var prevSurface: String? = null
        for (n in nodes) {
            val h = hashOf(n, reading)
            c += if (first) m.cost(prev, h) else m.cost(prev2, prev, h)
            first = false
            prev2 = prev; prev = h
        }
        c += if (first) m.cost(prev, LanguageModel.EOS) else m.cost(prev2, prev, LanguageModel.EOS)
        var bonus = 0
        for (n in nodes) {
            val s = surfaceOf(n)
            if (prevSurface != null) bonus += minOf(history.pair(prevSurface, s), 5) * PAIR_BONUS
            prevSurface = s
        }
        return (c * lmWeight).toInt() - bonus
    }

    private val cache = object : LinkedHashMap<String, List<Sentence>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Sentence>>?) = size > 24
    }
    private var cacheVersion = -1L

    /** Neural re-ranker (optional). */
    @Volatile var zenz: Zenz? = null
    /** Weight of the n-gram score (per 1000) against zenz's log-likelihood (nats). */
    var zenzWeight = 0.3f
    private val zenzCache = object : LinkedHashMap<String, List<Sentence>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Sentence>>?) = size > 24
    }

    private fun checkVersion() {
        val v = history.version.toLong() * 1_000_003L + userDict.version
        if (v != cacheVersion) { cache.clear(); zenzCache.clear(); cacheVersion = v }
    }

    /** zenz results depend on the text before the composition, so it is part of the key. */
    private fun zkey(reading: String, context: String) = context.takeLast(40) + "\u0000" + reading

    /** Whole-reading conversions, best first (zenz order when it has run for this reading in this context). */
    @Synchronized fun sentences(reading: String, context: String = ""): List<Sentence> {
        if (reading.isEmpty()) return emptyList()
        checkVersion()
        zenzCache[zkey(reading, context)]?.let { return it }
        return ngramSentences(reading)
    }

    /** Has zenz already ranked this reading in this context? */
    @Synchronized fun zenzRanked(reading: String, context: String): Boolean { checkVersion(); return zenzCache.containsKey(zkey(reading, context)) }

    /**
     * Re-ranks the sentence candidates of [reading] with zenz (slow: call off the typing thread).
     * Returns true if the best sentence changed.
     */
    fun zenzRerank(reading: String, context: String): Boolean {
        val z = zenz ?: return false
        val base = synchronized(this) { checkVersion(); ngramSentences(reading) }
        if (base.size < 2) return false
        val version = cacheVersion
        val nll = z.score(reading, context, base.map { it.surface }) ?: return false
        val ranked = base.indices.sortedBy { base[it].score * zenzWeight / 1000f + nll[it] }.map { base[it] }
        synchronized(this) {
            if (version != cacheVersion) return false
            zenzCache[zkey(reading, context)] = ranked
        }
        return ranked.first() !== base.first()
    }

    private fun ngramSentences(reading: String): List<Sentence> {
        cache[reading]?.let { return it }
        val lat = build(reading)
        val result = if (lm == null) {
            listOf(Sentence(toSegments(viterbi(lat)), 0))
        } else {
            nBest(lat, NBEST)
                .map { (nodes, cost) -> Sentence(toSegments(nodes), cost + lmScore(nodes, reading)) }
                .sortedBy { it.score }
        }.ifEmpty { listOf(Sentence(listOf(reading.length to reading), 0)) }
        cache[reading] = result
        return result
    }

    /** Best segmentation. Returns list of (readingLen, surface). */
    fun bestPath(reading: String): List<Pair<Int, String>> = sentences(reading).firstOrNull()?.segments ?: emptyList()

    /** Dictionary-only Viterbi (no language model); for comparisons. */
    fun viterbiPath(reading: String): List<Pair<Int, String>> =
        if (reading.isEmpty()) emptyList() else synchronized(this) { toSegments(viterbi(build(reading))) }

    /** Imported words first, then the system dictionary; both reordered by the user's history. */
    private fun words(reading: String, limit: Int = Int.MAX_VALUE): List<String> {
        val user = userDict.lookup(reading).sortedBy { boosted(it) }.map { it.surface }
        val range = dict.range(reading, 0, reading.length)
        val learnedReading = history.has(reading)
        val sys: List<String> = if (range == null) emptyList() else if (learnedReading) {
            range.map { dict.surface(it) to dict.cost(it) - boost(reading, dict.surface(it)) }.sortedBy { it.second }.map { it.first }
        } else {
            // entries are stored cost-sorted per reading, so only decode what is needed
            val out = ArrayList<String>(); val seen = HashSet<String>()
            for (e in range) { val s = dict.surface(e); if (seen.add(s)) out.add(s); if (out.size >= limit) break }
            out
        }
        val learned = if (learnedReading) (user + sys).filter { boost(reading, it) > 0 }.sortedByDescending { boost(reading, it) } else emptyList()
        return (learned + user + sys).distinct().take(limit)
    }

    /**
     * Within each group of candidates covering the same reading length, things the user picked
     * before come first (any kind: words, English, symbols, katakana) and things they passed over
     * move a few places down; the groups themselves keep their order.
     */
    private fun reorder(list: List<Candidate>, reading: String): List<Candidate> {
        if (history.isEmpty()) return list
        val groups = LinkedHashMap<Int, MutableList<Candidate>>()
        for (c in list) groups.getOrPut(c.consumed) { ArrayList() }.add(c)
        val out = ArrayList<Candidate>(list.size)
        for ((len, g) in groups) {
            val r = reading.substring(0, len)
            if (!history.has(r)) { out.addAll(g); continue }
            // picked before: to the front (most picked first); passed over: a few places down per penalty
            out.addAll(g.withIndex().sortedBy {
                val sc = boost(r, it.value.surface)
                if (sc > 0) -1_000_000.0 - sc else it.index + (if (sc < 0) 3.5 * (-sc) / 600.0 else 0.0)
            }.map { it.value })
        }
        return out
    }

    fun candidates(reading: String, context: String = "", limit: Int = 200): List<Candidate> {
        val n = reading.length
        if (n == 0) return emptyList()
        val out = LinkedHashMap<Pair<String, Int>, Candidate>()
        fun add(c: Candidate) { if (out.size < limit && !c.surface.any { it == '\u3099' || it == '\u309a' || it == '゜' && c.surface.length > 1 }) out.putIfAbsent(c.surface to c.consumed, c) }

        val ranked = sentences(reading, context)
        val path = ranked.first().segments
        val full = words(reading, limit)
        add(Candidate(ranked.first().surface, n, Candidate.Kind.SENTENCE, path))
        // a couple of other whole-sentence readings when the reading spans several words
        if (path.size >= 2) ranked.drop(1).take(2).forEach { add(Candidate(it.surface, n, Candidate.Kind.SENTENCE, it.segments)) }

        val preds = predictions(reading)
        val own = preds.filter { history.count(it.fullReading!!, it.surface) > 0 || userDict.lookup(it.fullReading).any { w -> w.surface == it.surface } }
        own.forEach { add(it) }
        fun addDictPredictions() = preds.forEach { add(it) }
        if (full.size < 2) addDictPredictions()

        val en = english[reading].orEmpty()
        val sym = symbols[reading].orEmpty()
        full.forEachIndexed { i, s ->
            add(Candidate(s, n, Candidate.Kind.WORD))
            if (i == 1) addDictPredictions()
            if (i == 1) en.forEach { add(Candidate(it, n, Candidate.Kind.ENGLISH)) }
            if (i == 3) sym.take(6).forEach { add(Candidate(it, n, Candidate.Kind.SYMBOL)) }
        }
        en.forEach { add(Candidate(it, n, Candidate.Kind.ENGLISH)) }
        sym.forEach { add(Candidate(it, n, Candidate.Kind.SYMBOL)) }
        kanjiOf(reading).forEach { add(Candidate(it, n, Candidate.Kind.WORD)) }

        // first segment of the best path, then other prefixes (longest first)
        val firstLen = path.firstOrNull()?.first ?: n
        val prefixLens = buildList {
            if (firstLen < n) add(firstLen)
            for (l in minOf(n - 1, dict.maxReadingLen) downTo 1) if (l != firstLen) add(l)
        }
        add(Candidate(reading, n, Candidate.Kind.KANA))
        add(Candidate(Kana.toKatakana(reading), n, Candidate.Kind.KANA))
        if (firstLen < n) path.firstOrNull()?.let { add(Candidate(it.second, firstLen, Candidate.Kind.WORD)) }
        for ((idx, l) in prefixLens.withIndex()) {
            val r = reading.substring(0, l)
            val ws = words(r, if (idx == 0) 12 else 4)
            ws.forEach { add(Candidate(it, l, Candidate.Kind.WORD)) }
            english[r]?.forEach { add(Candidate(it, l, Candidate.Kind.ENGLISH)) }
            if (idx == 0) symbols[r]?.take(8)?.forEach { add(Candidate(it, l, Candidate.Kind.SYMBOL)) }
        }
        return reorder(out.values.toList(), reading)
    }

    /**
     * Completions of the reading typed so far (Gboard-style prediction):
     * the user's own words / phrases and imported words from the first character,
     * frequent dictionary words from the second.
     */
    fun predictions(reading: String, limit: Int = 4): List<Candidate> {
        if (reading.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, Candidate>()
        fun add(r: String, s: String) {
            if (out.size < limit && s !in out) out[s] = Candidate(s, reading.length, Candidate.Kind.PREDICTION, fullReading = r)
        }
        history.completions(reading, limit).forEach { (r, s, _) -> add(r, s) }
        userDict.completions(reading).take(limit).forEach { (r, s) -> add(r, s) }
        dictPredictions(reading).forEach { (r, s, _) -> add(r, s) }
        return out.values.toList()
    }

    // from one character the whole range of readings is scanned once and kept
    private val firstCharPredictions = HashMap<String, List<Triple<String, String, Int>>>()

    private fun dictPredictions(reading: String): List<Triple<String, String, Int>> {
        val one = reading.length == 1
        if (one) synchronized(firstCharPredictions) { firstCharPredictions[reading]?.let { return it } }
        // short completions first: each extra character costs a bit
        val res = dict.completions(reading, maxExtra = if (one) 4 else 6, limit = 24, pos = { predictIds == null || it in predictIds },
            scan = if (one) Int.MAX_VALUE else 4000)
            // rare additions (web names, cost >= 6000) only if the language model has seen them;
            // the user's own rare words come from their history instead
            .map { (r, sf, cost) -> Triple(r, sf, if (lm == null || lm.knows(sf + '\u0001' + r)) cost else cost + 1500) }
            .filter { it.third < (if (one) 6000 else 7500) }
            .filter { (r, s, _) -> !s.all { it in 'ぁ'..'ゖ' } || r.length - reading.length >= 2 }
            .sortedBy { (r, _, cost) -> cost + 350 * (r.length - reading.length) }
            .take(8)
        if (one) synchronized(firstCharPredictions) { firstCharPredictions[reading] = res }
        return res
    }

    /** Reading length of the first segment the converter would pick on its own. */
    fun firstSegmentLength(reading: String): Int = bestPath(reading).firstOrNull()?.first ?: reading.length

    /**
     * Candidates when the user has set the first segment to [len] chars (Gboard-style ◀▶).
     * The first entry converts the whole reading with that boundary; the rest cover only the segment.
     */
    fun segmentCandidates(reading: String, len: Int, limit: Int = 120): List<Candidate> {
        val n = reading.length
        val l = len.coerceIn(1, n)
        val seg = reading.substring(0, l)
        val out = LinkedHashMap<Pair<String, Int>, Candidate>()
        fun add(c: Candidate) { if (out.size < limit && !c.surface.any { it == '\u3099' || it == '\u309a' || it == '゜' && c.surface.length > 1 }) out.putIfAbsent(c.surface to c.consumed, c) }
        val path = bestPath(reading)
        val sameAsAuto = path.firstOrNull()?.first == l
        var ws = words(seg, limit).ifEmpty { listOf(bestPath(seg).joinToString("") { it.second }) }
        // keep the converter's own choice first when the boundary is where it put it
        if (sameAsAuto) ws = (listOf(path.first().second) + ws).distinct()
        if (l < n) {
            val segments = if (sameAsAuto) path else listOf(l to ws.first()) + bestPath(reading.substring(l)).map { (a, b) -> a to b }
            add(Candidate(segments.joinToString("") { it.second }, n, Candidate.Kind.SENTENCE, segments))
        }
        ws.forEachIndexed { i, w ->
            add(Candidate(w, l, Candidate.Kind.WORD))
            if (i == 1) english[seg]?.forEach { add(Candidate(it, l, Candidate.Kind.ENGLISH)) }
        }
        english[seg]?.forEach { add(Candidate(it, l, Candidate.Kind.ENGLISH)) }
        symbols[seg]?.forEach { add(Candidate(it, l, Candidate.Kind.SYMBOL)) }
        kanjiOf(seg).forEach { add(Candidate(it, l, Candidate.Kind.WORD)) }
        add(Candidate(seg, l, Candidate.Kind.KANA))
        add(Candidate(Kana.toKatakana(seg), l, Candidate.Kind.KANA))
        return reorder(out.values.toList(), reading)
    }

    companion object {
        const val UNK_ID = 1851 // 名詞,一般
        const val USER_COST = 3000
        const val DEFAULT_LM_WEIGHT = 0.35f
        const val NBEST = 8
        const val NBEST_MARGIN = 5000
        const val MAX_POPS = 4000
        const val PAIR_BONUS = 800
    }
}

/** single_kanji.tsv: reading \t kanji kanji ... (one string) */
fun singleKanjiTable(text: String): Map<String, String> = HashMap<String, String>().apply {
    text.lineSequence().forEach { l -> val t = l.indexOf('\t'); if (t > 0 && t < l.length - 1) put(l.substring(0, t), l.substring(t + 1)) }
}

object Kana {
    fun toKatakana(s: String) = buildString {
        for (ch in s) append(if (ch in 'ぁ'..'ゖ') ch + 0x60 else ch)
    }
    fun toHiragana(s: String) = buildString {
        for (ch in s) append(if (ch in 'ァ'..'ヶ') ch - 0x60 else ch)
    }

    fun parseTable(text: String): Map<String, List<String>> {
        val m = HashMap<String, List<String>>()
        text.lineSequence().forEach { line ->
            val p = line.split('\t')
            if (p.size >= 2) m[p[0]] = p.drop(1)
        }
        return m
    }
}
