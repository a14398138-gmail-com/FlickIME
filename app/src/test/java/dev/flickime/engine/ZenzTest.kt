package dev.flickime.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/** Runs the real JNI bridge (host build) on the eval sets. Needs ZENZ_LIB and ZENZ_MODEL. */
class ZenzTest {
    private val assets = File("src/main/assets")
    private fun map(f: File) = RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }

    @Test fun rerank() {
        val lib = System.getenv("ZENZ_LIB"); val model = System.getenv("ZENZ_MODEL")
        assumeTrue(lib != null && model != null)
        System.load(lib)
        if (System.getenv("ZENZ_V2") == "1") Zenz.v2Prompt = true
        val z = Zenz.load(model!!, 2)!!
        println("ZENZ nll 私は学生です=${z.score("わたしはがくせいです", "", listOf("私は学生です", "渡しは学生です")).contentToString()}")
        val conv = Converter(Dictionary(map(File(assets, "dict.bin")), map(File(assets, "conv.bin".replace("conv", "conn")))), emptyMap(), emptyMap(),
            lm = LanguageModel(map(File(assets, "lm.bin"))))
        val n = (System.getenv("ZENZ_N") ?: "200").toInt()
        for (set in listOf("tatoeba", "wiki")) {
            val rows = File("/home/claude/corpus/out2/eval_$set.tsv").readLines().map { it.split('\t') }.filter { it.size == 2 }.take(n)
            var base = 0; var withZ = 0; var ms = 0L
            for ((r, gold) in rows) {
                conv.zenz = null
                if (conv.bestPath(r).joinToString("") { it.second } == gold) base++
                conv.zenz = z
                val t0 = System.nanoTime(); conv.zenzRerank(r, ""); ms += (System.nanoTime() - t0) / 1_000_000
                if (conv.bestPath(r).joinToString("") { it.second } == gold) withZ++
            }
            println("ZENZ $set n=${rows.size} ngram=${100.0 * base / rows.size}% zenz=${100.0 * withZ / rows.size}% (${ms / rows.size} ms/sentence)")
        }
        for (r in listOf("うちゅうせんかんのぷらもでる", "なにをいっているの？", "ちょうしどう？")) {
            conv.zenzRerank(r, ""); println("ZENZ $r -> ${conv.bestPath(r).joinToString("") { it.second }}")
        }
        z.close()
    }
}
