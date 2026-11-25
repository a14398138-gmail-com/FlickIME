package dev.flickime.engine

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/** Accuracy on held-out sentences (only runs where the corpus build output exists). */
class EvalTest {
    private val assets = File("src/main/assets")
    private fun map(f: File) = RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
    private val evalDir = File(System.getenv("EVAL_DIR") ?: "/home/claude/corpus/out")

    private fun converter(withLm: Boolean) = Converter(
        Dictionary(map(File(assets, "dict.bin")), map(File(assets, "conn.bin"))), emptyMap(), emptyMap(),
        lm = if (withLm) LanguageModel(map(File(assets, "lm.bin"))) else null,
    )

    private fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    @Test fun hashesMatchPython() {
        val f = File(System.getenv("SELFTEST") ?: "/home/claude/corpus/lm_t15.bin.selftest.tsv")
        assumeTrue(f.exists())
        for (line in f.readLines()) {
            val p = line.split('\t')
            assertEquals(p[2], java.lang.Long.toUnsignedString(LanguageModel.hash(p[0])))
            assertEquals(p[4].toInt(), LanguageModel.fold(LanguageModel.hash(p[0])))
            assertEquals(p[5].toInt(), LanguageModel.mix(LanguageModel.hash(p[0]), LanguageModel.hash(p[1])))
            if (p.size > 6) assertEquals(p[6].toInt(),
                LanguageModel.mix(LanguageModel.mix64(LanguageModel.hash(p[0]), LanguageModel.hash(p[1])), LanguageModel.hash("。")))
        }
        val lm = LanguageModel(map(File(assets, "lm.bin")))
        for ((a, b) in listOf("を" to "言っ", "を" to "行っ", "検索" to "し", "検索" to "市内", "調子" to "どう", "<s>" to "今日"))
            println("lm $a $b ${lm.cost(a, b)}")
    }

    @Test fun accuracy() {
        assumeTrue(File(evalDir, "eval_tatoeba.tsv").exists())
        val weights = (System.getenv("LM_WEIGHTS") ?: "0,0.5,1,1.5,2").split(',').map { it.toFloat() }
        val base = converter(false)
        val lmFile = System.getenv("LM_FILE")
        val lmConv = if (lmFile != null) Converter(
            Dictionary(map(File(assets, "dict.bin")), map(File(assets, "conn.bin"))), emptyMap(), emptyMap(),
            lm = LanguageModel(map(File(lmFile))),
        ) else converter(true)
        for (set in listOf("tatoeba", "wiki")) {
            val rows = File(evalDir, "eval_$set.tsv").readLines().map { it.split('\t') }.filter { it.size == 2 }.take(1500)
            for (w in weights) {
                val conv = if (w == 0f) base else lmConv.also { it.lmWeight = w }
                var exact = 0; var errs = 0; var chars = 0; var oracle = 0
                val t0 = System.nanoTime()
                for ((reading, target) in rows) {
                    val got = conv.bestPath(reading).joinToString("") { it.second }
                    if (w > 0f && conv.sentences(reading).any { it.surface == target }) oracle++
                    if (got == target) exact++
                    errs += editDistance(got, target); chars += target.length
                }
                val ms = (System.nanoTime() - t0) / 1e6 / rows.size
                println("EVAL $set w=$w exact=${"%.1f".format(100.0 * exact / rows.size)}% charAcc=${"%.2f".format(100.0 * (1 - errs.toDouble() / chars))}% oracle=${"%.1f".format(100.0 * oracle / rows.size)}% (${"%.1f".format(ms)} ms/sentence, n=${rows.size})")
            }
        }
    }
}
