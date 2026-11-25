package dev.flickime.engine

import dev.flickime.Layouts
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class GlideDecoderTest {
    private val words = File("src/main/assets/en_words.txt").readLines().filter { it.isNotBlank() }
    private val decoder = GlideDecoder(words)
    private val width = 1080f
    private val rowH = 140f
    private val centers: Map<Char, GlideDecoder.P> = Layouts.qwerty.keys.mapNotNull { k ->
        k.letter?.let { it to GlideDecoder.P((k.x + k.w / 2) * width, (k.row + 0.5f) * rowH) }
    }.toMap()
    private val keyW = width / 13f

    /** Simulated finger path through the word's keys with jitter. */
    private fun path(word: String, rnd: Random): List<GlideDecoder.P> {
        val out = ArrayList<GlideDecoder.P>()
        val pts = word.map { centers.getValue(it) }
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            for (s in 0 until 8) {
                val t = s / 8f
                out.add(GlideDecoder.P(a.x + (b.x - a.x) * t + rnd.nextFloat() * 20 - 10, a.y + (b.y - a.y) * t + rnd.nextFloat() * 20 - 10))
            }
        }
        out.add(pts.last())
        return out
    }

    /** Tall keys like on a phone; the finger lifts off half a row low. */
    @Test fun tallKeysAndSloppyEnd() {
        val kw = width / 13f; val kh = kw * 1.6f
        val tall = Layouts.qwerty.keys.mapNotNull { k -> k.letter?.let { it to GlideDecoder.P((k.x + k.w / 2) * width, (k.row + 0.5f) * kh) } }.toMap()
        var ok = 0
        val test = listOf("hello", "world", "thanks", "the", "good", "night", "question", "as", "it")
        for (w in test) {
            val pts = w.map { tall.getValue(it) }
            val path = ArrayList<GlideDecoder.P>()
            for (i in 0 until pts.size - 1) for (s in 0 until 8) {
                val t = s / 8f
                path.add(GlideDecoder.P(pts[i].x + (pts[i + 1].x - pts[i].x) * t, pts[i].y + (pts[i + 1].y - pts[i].y) * t))
            }
            path.add(GlideDecoder.P(pts.last().x + kw * 0.3f, pts.last().y + kh * 0.55f))
            val res = decoder.decode(path, tall, kw, kh)
            println("tall $w -> ${res.take(4)}")
            if (w in res.take(3)) ok++
        }
        assertTrue("$ok/${test.size}", ok >= test.size - 1)
    }

    @Test fun learnsUserWord() {
        val w = "flickime"
        assertTrue(w !in decoder.decode(path(w, Random(2)), centers, keyW).take(3).map { it.lowercase() })
        decoder.addUserWord("FlickIME")
        val res = decoder.decode(path(w, Random(3)), centers, keyW)
        println("after learning -> ${res.take(5)}")
        assertTrue(res.first() == "FlickIME")
    }

    @Test fun decodesCommonWords() {
        val rnd = Random(1)
        val test = listOf("hello", "world", "keyboard", "morning", "thanks", "history", "philosophy", "the", "you", "language", "japanese", "thesis")
        var top1 = 0; var top3 = 0
        for (w in test) {
            val t0 = System.nanoTime()
            val res = decoder.decode(path(w, rnd), centers, keyW)
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("$w -> ${res.take(5)} (${ms}ms)")
            if (res.firstOrNull() == w) top1++
            if (w in res.take(3)) top3++
        }
        println("top1 $top1/${test.size}, top3 $top3/${test.size}")
        assertTrue(top3 >= test.size - 2)
    }
}
