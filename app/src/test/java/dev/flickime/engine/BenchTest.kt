package dev.flickime.engine

import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/** Simulates typing: converts every prefix of each sentence, like one conversion per keystroke. */
class BenchTest {
    private val assets = File("src/main/assets")
    private fun map(name: String) = RandomAccessFile(File(assets, name), "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }

    @Test fun keystrokes() {
        val conv = Converter(Dictionary(map("dict.bin"), map("conn.bin")), Kana.parseTable(File(assets, "en.tsv").readText()),
            Kana.parseTable(File(assets, "symbols.tsv").readText()), lm = LanguageModel(map("lm.bin")))
        val sentences = listOf(
            "わたしはだいがくいんでしそうしをけんきゅうしています", "きょうはとてもいいてんきなのでさんぽにいきたい",
            "あしたのごごさんじにえきまえのかふぇでまちあわせしよう", "しゅうしろんぶんのてーまがまだきまっていない",
            "こうこうのときにならったことをおもいだした", "にほんごにゅうりょくのしくみについてしらべている",
        )
        repeat(3) { round ->
            var worst = 0L; var total = 0L; var count = 0
            for (s in sentences) for (i in 1..s.length) {
                val t0 = System.nanoTime()
                conv.candidates(s.substring(0, i))
                val us = (System.nanoTime() - t0) / 1000
                worst = maxOf(worst, us); total += us; count++
            }
            println("round $round: avg ${total / count} us, worst ${worst / 1000} ms over $count keystrokes")
        }
    }
}
