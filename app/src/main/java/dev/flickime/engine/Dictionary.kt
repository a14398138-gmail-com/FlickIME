package dev.flickime.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Read-only view over dict.bin / conn.bin produced by tools/build_dict.py. */
class Dictionary(dictBuf: ByteBuffer, connBuf: ByteBuffer) {
    class Entry(val surface: String, val lid: Int, val rid: Int, val cost: Int)

    private val d = dictBuf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    private val nReadings: Int
    private val readingOffBase: Int
    private val entryStartBase: Int
    private val readingCharsBase: Int
    private val entriesBase: Int
    private val surfaceCharsBase: Int
    val maxReadingLen: Int

    private val c = connBuf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    private val connSize: Int

    init {
        require(d.getInt(0) == 0x31445A4D) { "bad dict magic" }
        nReadings = d.getInt(4)
        val nEntries = d.getInt(8)
        val readingCharLen = d.getInt(12)
        maxReadingLen = d.getInt(20)
        readingOffBase = 24
        entryStartBase = readingOffBase + 4 * (nReadings + 1)
        readingCharsBase = entryStartBase + 4 * (nReadings + 1)
        entriesBase = readingCharsBase + 2 * readingCharLen
        surfaceCharsBase = entriesBase + 12 * nEntries
        connSize = c.getInt(0)
    }

    fun connection(leftRid: Int, rightLid: Int): Int =
        c.getShort(4 + 2 * (leftRid * connSize + rightLid)).toInt()

    private fun compare(idx: Int, key: CharSequence, from: Int, len: Int): Int {
        val off = d.getInt(readingOffBase + 4 * idx)
        val end = d.getInt(readingOffBase + 4 * (idx + 1))
        val rlen = end - off
        val n = minOf(rlen, len)
        for (i in 0 until n) {
            val a = d.getChar(readingCharsBase + 2 * (off + i))
            val b = key[from + i]
            if (a != b) return a.compareTo(b)
        }
        return rlen - len
    }

    private fun findReading(key: CharSequence, from: Int, len: Int): Int {
        var lo = 0
        var hi = nReadings - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cmp = compare(mid, key, from, len)
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** All entries whose reading equals key[from, from+len). */
    fun lookup(key: CharSequence, from: Int, len: Int): List<Entry> {
        val idx = findReading(key, from, len)
        if (idx < 0) return emptyList()
        val s = d.getInt(entryStartBase + 4 * idx)
        val e = d.getInt(entryStartBase + 4 * (idx + 1))
        val out = ArrayList<Entry>(e - s)
        for (i in s until e) {
            val p = entriesBase + 12 * i
            val so = d.getInt(p)
            val sl = d.getShort(p + 4).toInt() and 0xFFFF
            val sb = StringBuilder(sl)
            for (k in 0 until sl) sb.append(d.getChar(surfaceCharsBase + 2 * (so + k)))
            out.add(Entry(sb.toString(), d.getShort(p + 6).toInt(), d.getShort(p + 8).toInt(), d.getShort(p + 10).toInt()))
        }
        return out
    }

    fun lookup(key: String): List<Entry> = lookup(key, 0, key.length)

    // ---- allocation-free access by entry index (used by the lattice) ----

    /** Entry index range [start, end) for key[from, from+len), or null. */
    fun range(key: CharSequence, from: Int, len: Int): IntRange? {
        val idx = findReading(key, from, len)
        if (idx < 0) return null
        return d.getInt(entryStartBase + 4 * idx) until d.getInt(entryStartBase + 4 * (idx + 1))
    }

    fun lid(i: Int) = d.getShort(entriesBase + 12 * i + 6).toInt()
    fun rid(i: Int) = d.getShort(entriesBase + 12 * i + 8).toInt()
    fun cost(i: Int) = d.getShort(entriesBase + 12 * i + 10).toInt()
    fun surface(i: Int): String {
        val p = entriesBase + 12 * i
        val so = d.getInt(p)
        val sl = d.getShort(p + 4).toInt() and 0xFFFF
        val ca = CharArray(sl)
        for (k in 0 until sl) ca[k] = d.getChar(surfaceCharsBase + 2 * (so + k))
        return String(ca)
    }
}
