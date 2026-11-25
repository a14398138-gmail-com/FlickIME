package dev.flickime.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * AJIMEE-Bench (azooKey, CC BY-SA 3.0; human-checked readings, several accepted answers, 100 items with left context).
 * Input file: index \t hiragana reading \t left context \t accepted answers joined by U+0001 (tools/ajimee_tsv.py).
 *   AJIMEE=path to that file; optional ZENZ_LIB + ZENZ_MODEL (+ ZENZ_V2=1 for the v2.5 prompt) to rerank with zenz.
 * Prints acc@1 and MinCER (as in AJIMEE-Bench's utils.py), split into with / without context.
 */
class AjimeeTest {
    private fun lev(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) { val t = dp[j]; dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1); prev = t }
        }
        return dp[b.length]
    }

    @Test fun run() {
        val path = System.getenv("AJIMEE")
        assumeTrue(path != null && File(path).exists())
        val f = ConverterTest::class.java.getDeclaredMethod("getConv").apply { isAccessible = true }
        val conv = f.invoke(ConverterTest()) as Converter
        conv.history.clear()
        val lib = System.getenv("ZENZ_LIB"); val model = System.getenv("ZENZ_MODEL")
        if (lib != null && model != null) {
            System.load(lib)
            if (System.getenv("ZENZ_V2") == "1") Zenz.v2Prompt = true
            conv.zenz = Zenz.load(model, 2)
        }
        fun unesc(s: String) = s.replace("\\n", "\n").replace("\\t", "\t")
        // [with context, without] x [n, acc, cerSum]
        val n = IntArray(2); val acc = IntArray(2); val cer = DoubleArray(2); var oracle = 0
        val useCtx = System.getenv("AJIMEE_NOCTX") != "1"
        var ms = 0L
        val misses = StringBuilder()
        for (line in File(path!!).readLines()) {
            val p = line.split('\t')
            val reading = unesc(p[1]); val ctx = if (useCtx) unesc(p[2]) else ""; val answers = unesc(p[3]).split('\u0001')
            val k = if (unesc(p[2]).isNotEmpty()) 0 else 1
            val t0 = System.nanoTime()
            if (conv.zenz != null) conv.zenzRerank(reading, ctx)
            val got = conv.sentences(reading, ctx).first().surface
            ms += (System.nanoTime() - t0) / 1_000_000
            n[k]++
            if (conv.sentences(reading, ctx).any { it.surface in answers }) oracle++
            if (got in answers) acc[k]++ else misses.append("${p[0]}\t$got\t${answers.first()}\n")
            cer[k] += answers.minOf { lev(it, got).toDouble() / it.length }
        }
        System.getenv("AJIMEE_OUT")?.let { File(it).writeText(misses.toString()) }
        fun pct(a: Int, b: Int) = "%.1f%%".format(100.0 * a / b)
        val tot = n.sum()
        println("AJIMEE acc@1=${pct(acc.sum(), tot)} (ctx ${pct(acc[0], n[0])}, no-ctx ${pct(acc[1], n[1])}) " +
            "MinCER=${"%.3f".format(cer.sum() / tot)} (ctx ${"%.3f".format(cer[0] / n[0])}, no-ctx ${"%.3f".format(cer[1] / n[1])}) " +
            "N-best oracle=${pct(oracle, tot)} ${ms / tot} ms/item n=$tot zenz=${conv.zenz != null}${if (useCtx) "" else " (context withheld)"}")
    }
}
