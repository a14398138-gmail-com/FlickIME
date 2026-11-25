package dev.flickime.engine

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Prediction benchmark on the user's own commit log (tools/build_predict_eval.py → events.tsv).
 * Pure JVM: runs from the Gradle unit test (PredictEvalTest) or standalone (tools/predeval/run.sh, needs only a JDK).
 *
 * Replays the log in time order with a fresh history, like the phone would have lived through it:
 * every event is learned after it is scored, so personalization is part of what is measured.
 *
 * Output line (grep "PREDEVAL"):
 *   ksr   = keystroke saving rate of a simulated user (MAIN METRIC, higher is better):
 *           the user types the reading one character at a time and taps a prediction from the bar as soon as one
 *           is a prefix of what is left (reading and surface both; the longest such), then goes on with the rest;
 *           at the end of a piece the right conversion costs 1 tap if it is in the bar, 2 if further down,
 *           3 + its length if it is not offered at all. Cost is compared to typing everything + 1 tap.
 *   saved = keystrokes saved when only the exact committed surface counts as a prediction
 *   hit   = targets offered as a prediction before the reading was complete
 *   first1 = targets offered from the first character
 *   conv1 = full reading converted to the right surface as the first candidate (regression guard)
 */
object PredEval {
    private fun map(f: File) = RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }

    /** Returns the PREDEVAL line. [out]: optional per-target detail file. */
    fun run(assets: File, dir: File, split: String = "dev", bar: Int = 5, out: File? = null): String {
        val conv = Converter(
            Dictionary(map(File(assets, "dict.bin")), map(File(assets, "conn.bin"))),
            Kana.parseTable(File(assets, "en.tsv").readText()), Kana.parseTable(File(assets, "symbols.tsv").readText()),
            lm = LanguageModel(map(File(assets, "lm.bin"))),
            predictIds = File(assets, "predict_ids.txt").readText().trim().split(' ').map { it.toInt() }.toSet(),
        ).also { it.singleKanji = singleKanjiTable(File(assets, "single_kanji.tsv").readText()) }

        var simCost = 0; var simBase = 0
        /** Simulated taps for entering [surface] (reading [reading]) after [ctx0]. */
        fun simulate(reading: String, surface: String, ctx0: String): Int {
            var r = reading; var sf = surface; var ctx = ctx0; var cost = 0
            outer@ while (r.isNotEmpty()) {
                for (k in 1..r.length) {
                    val typed = r.substring(0, k)
                    cost++
                    if (k < r.length) {
                        val pick = conv.candidates(typed, ctx).take(bar).filter {
                            it.kind == Candidate.Kind.PREDICTION && it.fullReading != null && it.fullReading.length > k &&
                                r.startsWith(it.fullReading) && sf.startsWith(it.surface)
                        }.maxByOrNull { it.fullReading!!.length }
                        if (pick != null) {
                            cost++
                            r = r.substring(pick.fullReading!!.length); sf = sf.substring(pick.surface.length); ctx += pick.surface
                            continue@outer
                        }
                    } else {
                        val idx = conv.candidates(typed, ctx).indexOfFirst { it.consumed == typed.length && it.surface == sf }
                        cost += when { idx in 0 until bar -> 1; idx >= 0 -> 2; else -> 3 + sf.length }
                        break@outer
                    }
                }
            }
            return cost
        }

        var targets = 0; var keys = 0; var saved = 0; var hit = 0; var first1 = 0; var conv1 = 0
        val detail = StringBuilder()
        val t0 = System.nanoTime()
        fun unesc(s: String) = buildString {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) { append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] }); i += 2 } else { append(c); i++ }
            }
        }
        File(dir, "events.tsv").forEachLine { line ->
            val f = line.split('\t')
            val id = f[0]; val reading = unesc(f[3]); val surface = unesc(f[4]); val ctx = unesc(f.getOrElse(6) { "" })
            if (f[2] == "1" && f[1] == split) {
                targets++
                val n = reading.length
                keys += n
                var firstAt = -1
                for (k in 1 until n) {
                    val shown = conv.candidates(reading.substring(0, k), ctx).take(bar)
                    if (shown.any { it.kind == Candidate.Kind.PREDICTION && it.surface == surface }) { firstAt = k; break }
                }
                if (firstAt > 0) { hit++; saved += n - firstAt; if (firstAt == 1) first1++ }
                val sim = simulate(reading, surface, ctx)
                simCost += sim; simBase += n + 1
                val top = conv.candidates(reading, ctx).firstOrNull()?.surface
                if (top == surface) conv1++
                detail.append("$id\t$reading\t$surface\tfirst=$firstAt/$n\tsim=$sim/${n + 1}\tconv=${top == surface}\t$top\n")
            }
            // learn it the way the IME does for an explicit pick
            conv.history.learn(reading, surface)
        }
        out?.writeText(detail.toString())
        fun pct(a: Int, b: Int) = "%.1f%%".format(100.0 * a / maxOf(b, 1))
        return ("PREDEVAL split=$split targets=$targets ksr=${"%.1f%%".format(100.0 * (simBase - simCost) / simBase)} saved=${pct(saved, keys)} hit=${pct(hit, targets)} first1=${pct(first1, targets)} " +
            "conv1=${pct(conv1, targets)} time=${(System.nanoTime() - t0) / 1_000_000}ms")

    }

    @JvmStatic fun main(args: Array<String>) {
        if (args.size < 2) { System.err.println("usage: PredEval ASSETS_DIR EVAL_DIR [dev|test] [detail-out-file]"); return }
        println(run(File(args[0]), File(args[1]), args.getOrElse(2) { "dev" }, out = args.getOrNull(3)?.let(::File)))
    }
}
