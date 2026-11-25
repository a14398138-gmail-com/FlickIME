package dev.flickime.engine

import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/** Words the user imported. Readings are stored in hiragana. */
class UserDictionary {
    data class Word(val reading: String, val surface: String, val posId: Int)

    private val byReading = LinkedHashMap<String, MutableList<Word>>()

    /** Bumped on every change so cached conversions can be dropped. */
    @Volatile var version = 0
        private set

    val size: Int @Synchronized get() = byReading.values.sumOf { it.size }

    /** A copy: the keyboard thread may add words while conversion runs on another thread. */
    @Synchronized fun lookup(reading: String): List<Word> = byReading[reading]?.toList().orEmpty()
    @Synchronized fun isEmpty() = byReading.isEmpty()

    /** Words whose reading or surface contains [query] (for the settings screen). */
    @Synchronized fun search(query: String, limit: Int): List<Word> {
        val q = Kana.toHiragana(query.trim())
        return byReading.values.flatten().filter { q.isEmpty() || q in it.reading || query.trim() in it.surface }.take(limit)
    }

    /** Imported words whose reading starts with [prefix] (and is longer). */
    @Synchronized fun completions(prefix: String): List<Pair<String, String>> =
        byReading.entries.filter { it.key.length > prefix.length && it.key.startsWith(prefix) }
            .sortedBy { it.key.length }.flatMap { e -> e.value.map { e.key to it.surface } }
    @Synchronized fun clear() { byReading.clear(); version++ }

    @Synchronized fun remove(reading: String, surface: String): Boolean {
        val list = byReading[reading] ?: return false
        val removed = list.removeAll { it.surface == surface }
        if (list.isEmpty()) byReading.remove(reading)
        if (removed) version++
        return removed
    }

    /** Returns true when the word was new. */
    @Synchronized fun add(reading: String, surface: String, posId: Int = NOUN): Boolean {
        val r = Kana.toHiragana(reading.trim())
        val s = surface.trim()
        if (r.isEmpty() || s.isEmpty() || r.length > 32) return false
        val list = byReading.getOrPut(r) { ArrayList() }
        if (list.any { it.surface == s }) return false
        list.add(Word(r, s, posId))
        version++
        return true
    }

    @Synchronized fun serialize(): String = byReading.values.flatten().joinToString("\n") { "${it.reading}\t${it.surface}\t${it.posId}" }

    @Synchronized fun load(text: String) {
        clear()
        text.lineSequence().forEach { line ->
            val p = line.split('\t')
            if (p.size >= 2) add(p[0], p[1], p.getOrNull(2)?.toIntOrNull() ?: NOUN)
        }
    }

    companion object {
        const val NOUN = 1851          // 名詞,一般
        const val PROPER = 1920        // 名詞,固有名詞,一般
        const val PERSON = 1921        // 名詞,固有名詞,人名,一般
        const val PLACE = 1924         // 名詞,固有名詞,地域,一般
        const val ORG = 1929           // 名詞,固有名詞,組織

        private fun posOf(label: String?): Int {
            val l = label.orEmpty()
            return when {
                "人名" in l || "姓" in l || "名" == l -> PERSON
                "地名" in l || "地域" in l -> PLACE
                "組織" in l -> ORG
                "固有名詞" in l -> PROPER
                else -> NOUN
            }
        }

        /** Decode bytes: UTF-16 (MS-IME) if a BOM is present, else UTF-8, falling back to Shift_JIS. */
        fun decode(bytes: ByteArray): String {
            if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
            val utf8 = String(bytes, start, bytes.size - start, Charsets.UTF_8)
            if ('�' !in utf8) return utf8
            return runCatching { String(bytes, Charset.forName("Shift_JIS")) }.getOrDefault(utf8)
        }

        /** Largest dictionary text accepted after unzipping (a real word list is a few MB at most). */
        const val MAX_TEXT_BYTES = 32 * 1024 * 1024

        /** Reads at most [max] bytes; throws instead of growing without bound (zip bombs). */
        fun readLimited(input: java.io.InputStream, max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (out.size() + n > max) throw DictionaryTooLargeException()
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        /**
         * Unwraps a Gboard export (a zip with dictionary.txt). Prefers an entry named
         * dictionary.txt, else the first .txt. Other files are returned unchanged.
         */
        fun unzipIfNeeded(bytes: ByteArray): ByteArray {
            if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return bytes
            var firstTxt: ByteArray? = null
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val name = e.name.substringAfterLast('/').lowercase()
                    if (!e.isDirectory && name == "dictionary.txt") return readLimited(z, MAX_TEXT_BYTES)
                    if (!e.isDirectory && name.endsWith(".txt") && firstTxt == null) firstTxt = readLimited(z, MAX_TEXT_BYTES)
                    e = z.nextEntry
                }
            }
            return firstTxt ?: ByteArray(0)
        }

        /**
         * Parses Gboard (reading, word, locale), Google 日本語入力 / Mozc (reading, word, POS, comment),
         * MS-IME (reading, word, POS) and plain "reading<TAB>word" lines.
         */
        fun parse(text: String): List<Word> {
            val out = ArrayList<Word>()
            for (raw in text.lineSequence()) {
                val line = raw.trimEnd('\r')
                if (line.isBlank() || line.startsWith("#") || line.startsWith("!")) continue
                val p = if ('\t' in line) line.split('\t') else line.split(',')
                if (p.size < 2) continue
                val reading = Kana.toHiragana(p[0].trim())
                val surface = p[1].trim()
                if (reading.isEmpty() || surface.isEmpty()) continue
                if (!reading.all { it in 'ぁ'..'ゖ' || it == 'ー' || it.isLetterOrDigit() }) continue
                val third = p.getOrNull(2)?.trim()
                val pos = if (third != null && Regex("^[a-z]{2}(-[A-Z]{2})?$").matches(third)) NOUN else posOf(third)
                out.add(Word(reading, surface, pos))
            }
            return out
        }
    }
}

class DictionaryTooLargeException : java.io.IOException("dictionary too large")
