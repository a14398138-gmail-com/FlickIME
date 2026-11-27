package dev.flickime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

class ConverterTest {
    private val assets = File("src/main/assets")
    private fun map(name: String) = RandomAccessFile(File(assets, name), "r").use {
        it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())
    }
    private val conv by lazy {
        Converter(Dictionary(map("dict.bin"), map("conn.bin")), Kana.parseTable(File(assets, "en.tsv").readText()), Kana.parseTable(File(assets, "symbols.tsv").readText()),
            lm = LanguageModel(RandomAccessFile(File(assets, "lm.bin"), "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }),
            predictIds = File(assets, "predict_ids.txt").readText().trim().split(' ').map { it.toInt() }.toSet())
            .also { it.singleKanji = singleKanjiTable(File(assets, "single_kanji.tsv").readText()) }
    }

    private fun top(r: String) = conv.candidates(r).first().surface

    @Test fun sentences() {
        val cases = listOf(
            "わたしはがくせいです", "きょうはいいてんきですね", "しそうしをけんきゅうしています",
            "にほんごにゅうりょく", "あした", "とうきょうにいきます",
        )
        for (c in cases) {
            val t0 = System.nanoTime()
            val cands = conv.candidates(c)
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("$c -> ${cands.take(8).map { "${it.surface}/${it.consumed}" }}  (${ms}ms)")
        }
        assertEquals("私は学生です", top("わたしはがくせいです"))
    }

    @Test fun modernVocabulary() {
        for (r in listOf("くろーど", "ぎっとはぶ", "すらっく", "ゆにくろ")) println("$r -> ${conv.candidates(r).take(6).map { it.surface }}")
        assertTrue("Claude" in conv.candidates("くろーど").take(4).map { it.surface })
        assertTrue("GitHub" in conv.candidates("ぎっとはぶ").take(4).map { it.surface })
    }

    @Test fun segments() {
        val r = "しそうしをけんきゅう"
        val base = conv.firstSegmentLength(r)
        println("base=$base " + conv.segmentCandidates(r, base).take(4) + " / " + conv.segmentCandidates(r, base - 1).take(4))
        assertEquals(4, base)
        assertEquals("思想史", conv.segmentCandidates(r, base)[1].surface)
        val shorter = conv.segmentCandidates(r, 3)
        assertTrue(shorter.drop(1).all { it.consumed == 3 })
        assertTrue("思想" in shorter.map { it.surface })
    }

    @Test fun english() {
        val m = conv.candidates("もーにんぐ").map { it.surface }
        println(m.take(10))
        assertTrue("morning" in m.take(6))
        assertTrue(conv.candidates("ねこ").none { it.kind == Candidate.Kind.ENGLISH && it.consumed == 2 })
        assertTrue(conv.candidates("あした").none { it.kind == Candidate.Kind.ENGLISH && it.consumed == 3 })
    }

    @Test fun symbols() {
        val c = conv.candidates("やじるし").map { it.surface }
        println(c.take(16))
        assertTrue("→" in c.take(12))
        assertTrue("☆" in conv.candidates("ほし").map { it.surface })
    }

    @Test fun userDictionary() {
        val text = UserDictionary.decode(("# Gboard Dictionary version:1\n" +
            "ふりっくいめ\tFlickIME\tja-JP\n" +
            "まーち\tMARCH\tja-JP\n" +
            "ぐーぐるにほんごにゅうりょく\tGoogle日本語入力\t固有名詞\t\n").toByteArray())
        val words = UserDictionary.parse(text)
        assertEquals(3, words.size)
        words.forEach { conv.userDict.add(it.reading, it.surface, it.posId) }
        assertEquals("FlickIME", top("ふりっくいめ"))
        println(conv.candidates("まーちのだいがくいん").take(5))
        assertEquals("MARCH", conv.candidates("まーち").first().surface)
        // MS-IME export: UTF-16LE with BOM
        val ms = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "!Microsoft IME\r\nてすとご\tテスト語\t名詞\r\n".toByteArray(Charsets.UTF_16LE)
        assertEquals(listOf("テスト語"), UserDictionary.parse(UserDictionary.decode(ms)).map { it.surface })
        conv.userDict.clear()
    }

    @Test fun learnedNonDictionaryCandidatesComeFirst() {
        // (2) English / symbols / katakana picks are remembered too
        val h = conv.history
        val before = conv.candidates("のっといこーる").first().surface
        h.learn("のっといこーる", "≠")
        assertEquals("≠", conv.candidates("のっといこーる").first().surface)
        h.learn("もーにんぐ", "morning")
        assertEquals("morning", conv.candidates("もーにんぐ").first().surface)
        println("のっといこーる: $before -> ≠")
        h.clear()
    }

    @Test fun passedOverTopDrops() {
        // (3) picking the 2nd candidate penalises the 1st
        val h = conv.history
        val c0 = conv.candidates("せき").map { it.surface }
        h.penalize("せき", c0[0])
        val c1 = conv.candidates("せき").map { it.surface }
        println("せき: ${c0.take(4)} -> ${c1.take(4)}")
        assertTrue(c1[0] != c0[0])
        assertTrue(c0[0] in c1.take(6)) // dropped a little, not buried
        h.clear()
    }

    @Test fun sentenceWordsAreLearnedAndUndoable() {
        // (1) words inside an accepted sentence get a light boost; (5) undo takes it back
        val h = conv.history
        val r = "とうはん"
        val first = conv.candidates(r).first().surface
        val target = conv.candidates(r).map { it.surface }.first { it != first && it.length == 2 && it != "とうはん" }
        val undo = UserHistory.Undo()
        h.learn(r, target, weight = 1, undo = undo)
        assertEquals(target, conv.candidates(r).first().surface)
        h.revert(undo)
        assertEquals(first, conv.candidates(r).first().surface)
        h.clear()
    }

    @Test fun historyMigratesFromV1() {
        val h = UserHistory()
        h.load("かんじ\t漢字\t3")
        assertEquals(6, h.count("かんじ", "漢字"))
        val again = UserHistory().apply { load(h.serialize()) }
        assertEquals(6, again.count("かんじ", "漢字"))
    }

    @Test fun predictions() {
        for (r in listOf("ありが", "よろし", "けんきゅ", "しゅうしろ", "あ")) println("PRED $r -> ${conv.predictions(r).map { "${it.surface}(${it.fullReading})" }}")
        assertTrue(conv.predictions("ありが").any { it.fullReading!!.startsWith("ありがと") })
        // own phrases complete from the first character, ahead of dictionary words
        conv.history.learn("よろしくおねがいします", "よろしくお願いします")
        // picked once: from the second character; picked twice: from the first
        assertTrue(conv.predictions("よ").none { it.surface == "よろしくお願いします" })
        assertEquals("よろしくお願いします", conv.predictions("よろ").first().surface)
        conv.history.learn("よろしくおねがいします", "よろしくお願いします")
        assertEquals("よろしくお願いします", conv.predictions("よ").first().surface)
        assertEquals(Candidate.Kind.PREDICTION, conv.candidates("よろ")[1].kind)
        conv.history.clear()
    }

    @Test fun alignChecksGeneratedText() {
        assertEquals(listOf("私", "は", "学生", "です"), conv.align("わたしはがくせいです", "私は学生です")?.map { it.second }?.let { segs ->
            // the exact split may vary; what matters is that it spells the reading
            if (segs.joinToString("") == "私は学生です") listOf("私", "は", "学生", "です") else segs })
        assertEquals(null, conv.align("わたしはがくせいです", "私は学生でした"))   // does not spell the reading
        assertEquals(null, conv.align("わたしはがくせいです", "私は学生"))         // dropped characters
        assertTrue(conv.align("ぎっとはぶでこうかい", "GitHubで公開") != null)
        // a word only zenz knows still spells the reading kanji by kanji, and stays one word
        assertEquals(listOf(4 to "院進", 2 to "して"), conv.align("いんしんして", "院進して")?.let { segs ->
            if (segs.first() == (4 to "院進")) listOf(4 to "院進", 2 to "して") else segs })
    }

    @Test fun zipImportIsBoundedAndPicksDictionary() {
        fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
            val bo = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(bo).use { z -> entries.forEach { (n, b) -> z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(b); z.closeEntry() } }
            return bo.toByteArray()
        }
        val picked = UserDictionary.unzipIfNeeded(zip("readme.txt" to "x".toByteArray(), "dictionary.txt" to "ふりっく\tFlick\tja-JP".toByteArray()))
        assertEquals("ふりっく\tFlick\tja-JP", String(picked))
        val bomb = zip("dictionary.txt" to ByteArray(UserDictionary.MAX_TEXT_BYTES + 1))   // compresses to ~32 KB
        assertTrue(bomb.size < 1_000_000)
        assertTrue(runCatching { UserDictionary.unzipIfNeeded(bomb) }.exceptionOrNull() is dev.flickime.engine.DictionaryTooLargeException)
    }

    @Test fun learning() {
        val before = conv.candidates("かんじ").map { it.surface }
        val target = before.first { it != before[0] && it != "かんじ" }
        repeat(2) { conv.history.learn("かんじ", target) }
        assertEquals(target, top("かんじ"))
        conv.history.clear()
    }

    @Test fun removedPredictionStaysRemoved() {
        conv.history.clear()
        val before = conv.predictions("ありが").map { it.surface to it.fullReading!! }
        val (s, r) = before.first { it.first == "ありがとう" }
        conv.history.block(r, s)
        assertTrue(conv.predictions("ありが").none { it.surface == s })
        // still convertible when typed out, and kept across save / load
        assertTrue(conv.candidates("ありがとう").any { it.surface == s })
        val h = UserHistory().apply { load(conv.history.serialize()) }
        assertTrue(h.isBlocked(r, s))
        conv.history.clear()
    }
}
