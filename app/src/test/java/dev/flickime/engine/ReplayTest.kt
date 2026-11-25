package dev.flickime.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/** Prints conversions of readings listed in REPLAY_FILE (developer aid). */
class ReplayTest {
    private val assets = File("src/main/assets")
    private fun map(f: File) = RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }

    @Test fun replay() {
        val f = File(System.getenv("REPLAY_FILE") ?: "/tmp/replay.txt")
        assumeTrue(f.exists())
        val lmFile = File(System.getenv("LM_FILE") ?: File(assets, "lm.bin").path)
        val conv = Converter(Dictionary(map(File(assets, "dict.bin")), map(File(assets, "conn.bin"))), emptyMap(), emptyMap(),
            lm = LanguageModel(map(lmFile)))
        conv.lmWeight = (System.getenv("LM_WEIGHT") ?: "0.45").toFloat()
        for (r in f.readLines().filter { it.isNotBlank() }) {
            val base = conv.viterbiPath(r).joinToString("") { it.second }
            val s = conv.sentences(r).take(3).joinToString(" / ") { it.surface }
            println("REPLAY $r | dict-only: $base | lm: $s")
        }
    }
}
