package dev.flickime.engine

/**
 * Numbers read out in kana → Arabic digits (ごげん → 5限, じゅうはちのなつ → 18の夏, いっかい → 1回).
 * Mozc does this with a rewriter outside the dictionary, so the lattice gets these as extra nodes.
 * Covers 0..9999 with the sound changes of counting (さんびゃく, ろっぴゃく, はっせん), the doubled-
 * consonant forms before a counter (いっ, ろっ, はっ, じゅっ / じっ, ひゃっ), よ for 4 (よじ), the native
 * counts (ひとつ → 1つ … ここのつ → 9つ) and N まん M for larger numbers.
 */
object JaNumbers {
    /** reading -> digits, for 1..9999 and 0, including the variants */
    val small: Map<String, String>
    /** reading of N まん (N in 1..9999) -> N */
    val man: Map<String, Int>
    /** whole words (native counts) -> surface */
    val words = mapOf(
        "ひとつ" to "1つ", "ふたつ" to "2つ", "みっつ" to "3つ", "よっつ" to "4つ", "いつつ" to "5つ",
        "むっつ" to "6つ", "ななつ" to "7つ", "やっつ" to "8つ", "ここのつ" to "9つ",
    )
    val maxLen: Int

    private val ones = arrayOf(listOf(""), listOf("いち"), listOf("に"), listOf("さん"), listOf("よん"), listOf("ご"),
        listOf("ろく"), listOf("なな", "しち"), listOf("はち"), listOf("きゅう"))
    private val tens = arrayOf(listOf(""), listOf("じゅう"), listOf("にじゅう"), listOf("さんじゅう"), listOf("よんじゅう"), listOf("ごじゅう"),
        listOf("ろくじゅう"), listOf("ななじゅう", "しちじゅう"), listOf("はちじゅう"), listOf("きゅうじゅう"))
    private val hundreds = arrayOf(listOf(""), listOf("ひゃく"), listOf("にひゃく"), listOf("さんびゃく"), listOf("よんひゃく"), listOf("ごひゃく"),
        listOf("ろっぴゃく"), listOf("ななひゃく"), listOf("はっぴゃく"), listOf("きゅうひゃく"))
    private val thousands = arrayOf(listOf(""), listOf("せん", "いっせん"), listOf("にせん"), listOf("さんぜん"), listOf("よんせん"), listOf("ごせん"),
        listOf("ろくせん"), listOf("ななせん"), listOf("はっせん"), listOf("きゅうせん"))

    /** Doubled-consonant / short forms of the last word, used before a counter (いっかい, じゅっぷん, よじ). */
    private fun ends(w: String): List<String> = when (w) {
        "いち" -> listOf("いっ"); "ろく" -> listOf("ろっ"); "はち" -> listOf("はっ"); "よん" -> listOf("よ")
        "じゅう" -> listOf("じゅっ", "じっ"); "ひゃく" -> listOf("ひゃっ"); "びゃく" -> listOf("びゃっ"); "ぴゃく" -> listOf("ぴゃっ")
        else -> emptyList()
    }

    init {
        val m = HashMap<String, String>()
        m["ぜろ"] = "0"; m["れい"] = "0"
        for (n in 1..9999) {
            val parts = listOf(thousands[n / 1000], hundreds[n / 100 % 10], tens[n / 10 % 10], ones[n % 10]).filter { it != listOf("") }
            var acc = listOf("")
            for ((pi, p) in parts.withIndex()) {
                val opts = if (pi == parts.size - 1) p + p.flatMap { w ->
                    // the last word may take its short form (only its tail changes: にひゃく → にひゃっ)
                    val tail = listOf("じゅう", "ぴゃく", "びゃく", "ひゃく").firstOrNull { w.endsWith(it) && w != it }
                    if (tail != null) ends(tail).map { w.dropLast(tail.length) + it } else ends(w)
                } else p
                acc = acc.flatMap { a -> opts.map { a + it } }
            }
            for (r in acc) m.putIfAbsent(r, n.toString())
        }
        small = m
        val mm = HashMap<String, Int>()
        for ((r, v) in m) if (v != "0" && !r.endsWith("っ") && !r.endsWith("よ")) mm.putIfAbsent(r + "まん", v.toInt())
        mm["まん"] = 1; mm["いちまん"] = 1
        man = mm
        maxLen = (m.keys + mm.keys).maxOf { it.length } + 12
    }

    /** Every number spelled by [reading] from [i]: (length in kana, digits). */
    fun at(reading: String, i: Int): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        val maxL = minOf(reading.length - i, 24)
        for (len in 1..maxL) {
            val r = reading.substring(i, i + len)
            small[r]?.let { out.add(len to it) }
            words[r]?.let { out.add(len to it) }
            man[r]?.let { hi ->
                out.add(len to (hi * 10000).toString())
                // N まん M
                for (l2 in 1..minOf(reading.length - i - len, 16)) small[reading.substring(i + len, i + len + l2)]?.let { lo ->
                    if (lo != "0") out.add((len + l2) to (hi * 10000L + lo.toLong()).toString())
                }
            }
        }
        return out
    }
}
