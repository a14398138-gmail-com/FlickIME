package dev.flickime.engine

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * "Typed the whole reading — is the right conversion on screen without scrolling the candidate bar?"
 * Pure JVM (tools/predeval/conv.sh). zenz is used like on the phone when ZENZ_LIB (host build of
 * libzenzjni, tools/loop/build_zenz_host.sh) is set; without it the numbers are n-gram only.
 *
 * Sets:
 *   log    = eval/events.tsv (the author's commit log, tools/build_predict_eval.py), replayed in time order
 *            with learning after each commit like the phone; only the given split is scored. Every pick from the
 *            candidate list counts, also when the pick was the hiragana itself (about a quarter of the picks)
 *   ajimee = AJIMEE-Bench TSV (tools/ajimee_tsv.py), fresh history, accepted answers
 *   casual = tools/eval/casual.tsv (same format), fresh history
 *
 * Output (grep CONVEVAL):
 *   vis   = MAIN METRIC: the right candidate is on screen in the bar without scrolling
 *           (bar width model: BAR_DP dp, default 380 ≈ POCO F7 minus the ▼ button; each candidate is
 *           12+12 dp padding + 17 dp per full-width character, 9.5 dp per half-width one; a candidate
 *           counts when it is the first or at least 48 dp of it is on screen)
 *   top1  = it is the first candidate
 *   mrr   = mean reciprocal rank (0 when missing)
 *   miss  = not among the candidates at all (has to be fixed by hand)
 *   taps  = mean taps to pick it: 1 if visible, 2 if further (▼ then tap), 3 + length if missing
 *   barN  = mean number of candidates visible in the bar
 */
object ConvEval {
    private fun map(f: File) = RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }

    val barDp = System.getenv("BAR_DP")?.toFloat() ?: 380f

    fun width(s: String): Float = 24f + s.sumOf { c -> (if (c.code < 0x2E80 || c in '｡'..'ﾟ') 9.5 else 17.0) }.toFloat()

    /**
     * How many leading candidates can be seen and tapped without scrolling: the first always, the others
     * when at least 48 dp of them is on screen (a long sentence can run past the edge and still be read).
     */
    fun visibleCount(cands: List<Candidate>): Int {
        var x = 0f; var n = 0
        for (c in cands) { if (n > 0 && x + 48f > barDp) break; x += width(c.surface); n++ }
        return n
    }

    fun converter(assets: File): Converter = Converter(
        Dictionary(map(File(assets, "dict.bin")), map(File(assets, "conn.bin"))),
        Kana.parseTable(File(assets, "en.tsv").readText()), Kana.parseTable(File(assets, "symbols.tsv").readText()),
        lm = LanguageModel(map(File(assets, "lm.bin"))),
        predictIds = File(assets, "predict_ids.txt").readText().trim().split(' ').map { it.toInt() }.toSet(),
    ).also { c ->
        c.singleKanji = singleKanjiTable(File(assets, "single_kanji.tsv").readText())
        val lib = System.getenv("ZENZ_LIB")
        if (!lib.isNullOrEmpty()) {
            System.load(File(lib).absolutePath)
            val model = System.getenv("ZENZ_MODEL") ?: File(assets, "zenz.gguf").path
            c.zenz = Zenz.load(model, System.getenv("ZENZ_THREADS")?.toInt() ?: minOf(4, Runtime.getRuntime().availableProcessors())) ?: error("zenz model did not load: $model")
            c.zenzGenerate = true // as in the app (Engine)
        }
    }

    class Score(val set: String, val split: String) {
        var n = 0; var top1 = 0; var vis = 0; var miss = 0; var rr = 0.0; var taps = 0; var barN = 0; var ms = 0L
        val detail = StringBuilder()
        fun line() = "CONVEVAL set=$set split=$split n=$n vis=${p(vis)} top1=${p(top1)} mrr=${"%.3f".format(rr / maxOf(n, 1))} " +
            "miss=${p(miss)} taps=${"%.2f".format(taps.toDouble() / maxOf(n, 1))} barN=${"%.1f".format(barN.toDouble() / maxOf(n, 1))} " +
            "zenz=${if (System.getenv("ZENZ_LIB").isNullOrEmpty()) "off" else "on"} ms/item=${ms / maxOf(n, 1)}"
        private fun p(a: Int) = "%.1f%%".format(100.0 * a / maxOf(n, 1))
    }

    /** What the bar shows after the whole reading is typed (the app's refresh(): zenz first, then candidates). */
    fun shown(conv: Converter, reading: String, ctx: String): List<Candidate> {
        if (conv.zenz != null && reading.length >= 2 && !conv.zenzRanked(reading, ctx)) conv.zenzRerank(reading, ctx)
        return conv.candidates(reading, ctx)
    }

    fun score(s: Score, conv: Converter, id: String, reading: String, ctx: String, answers: Collection<String>) {
        val t0 = System.nanoTime()
        val cands = shown(conv, reading, ctx)
        s.ms += (System.nanoTime() - t0) / 1_000_000
        val idx = cands.indexOfFirst { it.consumed == reading.length && it.surface in answers }
        val bar = visibleCount(cands)
        s.n++; s.barN += bar
        when {
            idx < 0 -> { s.miss++; s.taps += 3 + answers.first().length }
            idx < bar -> { s.vis++; s.taps += 1 }
            else -> s.taps += 2
        }
        if (idx == 0) s.top1++
        if (idx >= 0) s.rr += 1.0 / (idx + 1)
        s.detail.append("$id\t$reading\t${answers.first()}\trank=${if (idx < 0) "-" else (idx + 1).toString()}\tbar=$bar\t" +
            cands.take(8).joinToString(" | ") { it.surface } + "\n")
    }

    private fun unesc(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] }); i += 2 } else { append(c); i++ }
        }
    }

    /** The author's log: replay + learn (same learning as PredEval / the app), score [split]. */
    fun runLog(conv: Converter, events: File, split: String): Score {
        val s = Score("log", split)
        events.forEachLine { line ->
            val f = line.split('\t')
            val id = f[0]; val reading = unesc(f[3]); val surface = unesc(f[4]); val ctx = unesc(f.getOrElse(6) { "" })
            // scored: every pick from the candidate list, the kana candidates included. Kind KANA is both the
            // bar's hiragana / katakana candidates and the reading committed with Enter (rank -1, not a pick):
            // with the rank column (8th) only rank >= 0 counts; without it a KANA whose surface differs from
            // the reading (katakana) is a pick, and KANA == reading is left out (cannot tell Enter from a tap)
            val rank = f.getOrNull(7)?.toIntOrNull()
            val picked = f[5] != "KANA" || (if (rank != null) rank >= 0 else surface != reading)
            if (f[1] == split && picked && reading.isNotEmpty() && reading.length <= 40) score(s, conv, id, reading, ctx, listOf(surface))
            val segs = conv.sentences(reading, ctx).firstOrNull { it.surface == surface }?.segments
            if (segs != null && segs.size >= 2) {
                var off = 0
                for ((len, w) in segs) { if (off + len > reading.length) break; conv.history.learn(reading.substring(off, off + len), w, weight = 1); off += len }
                if (reading.length <= 10) conv.history.learn(reading, surface)
            } else conv.history.learn(reading, surface)
        }
        return s
    }

    /** index \t reading \t context \t answers joined by U+0001 (AJIMEE / casual). No learning. */
    fun runTsv(conv: Converter, name: String, file: File): Score {
        conv.history.clear()
        val s = Score(name, "all")
        for (line in file.readLines()) {
            if (line.isBlank()) continue
            val p = line.split('\t')
            score(s, conv, p[0], unesc(p[1]), unesc(p.getOrElse(2) { "" }), unesc(p[3]).split('\u0001'))
        }
        return s
    }

    /** args: ASSETS SET(log|ajimee|casual) PATH [split] [detail-out] */
    @JvmStatic fun main(args: Array<String>) {
        if (args.size < 3) { System.err.println("usage: ConvEval ASSETS log|ajimee|casual PATH [dev|test] [detail-out]"); return }
        val conv = converter(File(args[0]))
        val s = when (args[1]) {
            "log" -> runLog(conv, File(args[2], "events.tsv").takeIf { it.exists() } ?: File(args[2]), args.getOrElse(3) { "dev" })
            else -> runTsv(conv, args[1], File(args[2]))
        }
        args.getOrNull(4)?.let { File(it).writeText(s.detail.toString()) }
        println(s.line())
    }
}
