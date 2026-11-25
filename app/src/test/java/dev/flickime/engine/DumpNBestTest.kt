package dev.flickime.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/** Writes the converter's whole-sentence candidates for the eval sets (for zenz experiments). */
class DumpNBestTest {
    private val assets = File("src/main/assets")
    private fun map(f: File) = RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }

    @Test fun dump() {
        val evalDir = File(System.getenv("EVAL_DIR") ?: "/home/claude/corpus/out2")
        val out = File(System.getenv("DUMP_DIR") ?: "/home/claude/zenz")
        assumeTrue(File(evalDir, "eval_tatoeba.tsv").exists() && out.isDirectory)
        val conv = Converter(Dictionary(map(File(assets, "dict.bin")), map(File(assets, "conn.bin"))), emptyMap(), emptyMap(),
            lm = LanguageModel(map(File(assets, "lm.bin"))))
        for (set in listOf("tatoeba", "wiki")) {
            File(out, "nbest_$set.tsv").printWriter().use { w ->
                File(evalDir, "eval_$set.tsv").readLines().map { it.split('\t') }.filter { it.size == 2 }.take(1500).forEach { (r, gold) ->
                    val s = conv.sentences(r)
                    w.println((listOf(r, gold) + s.flatMap { listOf(it.surface, it.score.toString()) }).joinToString("\t"))
                }
            }
        }
    }
}
