package dev.flickime.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pruned word-trigram model (interpolated Kneser-Ney) built by tools/build_lm.py from
 * Japanese Wikipedia + Tatoeba, tokenised with MeCab/IPAdic (the same word units as the
 * Mozc dictionary). Words and word pairs are stored as 32-bit hashes of their surfaces.
 *
 * Costs are -log10 P × 1000.
 */
class LanguageModel(buf: ByteBuffer) {
    private val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    private val nUni: Int
    private val nBi: Int
    private val step: Float
    val unkCost: Int
    private val uniKeys: Int
    private val uniP: Int
    private val uniB: Int
    private val biKeys: Int
    private val biP: Int
    private val nCtx: Int
    private val nTri: Int
    private val ctxKeys: Int
    private val ctxB: Int
    private val triKeys: Int
    private val triP: Int

    init {
        val magic = b.getInt(0)
        require(magic == MAGIC || magic == MAGIC2) { "bad lm magic" }
        nUni = b.getInt(4)
        nBi = b.getInt(8)
        step = b.getFloat(12)
        unkCost = b.getInt(16)
        val v2 = magic == MAGIC2
        nCtx = if (v2) b.getInt(24) else 0
        nTri = if (v2) b.getInt(28) else 0
        uniKeys = if (v2) 32 else 24
        uniP = uniKeys + 4 * nUni
        uniB = uniP + nUni
        biKeys = uniB + nUni
        biP = biKeys + 4 * nBi
        ctxKeys = biP + nBi
        ctxB = ctxKeys + 4 * nCtx
        triKeys = ctxB + nCtx
        triP = triKeys + 4 * nTri
    }

    val hasTrigrams get() = nTri > 0

    /** Cost of [hw] after [hu] [hv] (trigram, backing off to the bigram). */
    fun cost(hu: Long, hv: Long, hw: Long): Int {
        if (nTri == 0) return cost(hv, hw)
        val ctx = mix64(hu, hv)
        val ti = find(triKeys, nTri, mix64(ctx, hw).toInt())
        if (ti >= 0) return deq(b.get(triP + ti))
        val ci = find(ctxKeys, nCtx, ctx.toInt())
        return (if (ci >= 0) deq(b.get(ctxB + ci)) else 0) + cost(hv, hw)
    }

    private fun find(base: Int, n: Int, key: Int): Int {
        var lo = 0
        var hi = n - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = b.getInt(base + 4 * mid)
            when {
                v < key -> lo = mid + 1
                v > key -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun deq(x: Byte): Int = ((x.toInt() and 0xFF) * step * 1000f).toInt()

    fun cost(prev: String, word: String): Int = cost(hash(prev), hash(word))

    /** Cost of [hw] following [hp] (both from [hash]). */
    fun cost(hp: Long, hw: Long): Int {
        val pi = find(uniKeys, nUni, fold(hp))
        val backoff = if (pi >= 0) deq(b.get(uniB + pi)) else 0
        val wi = find(uniKeys, nUni, fold(hw))
        if (wi < 0) return backoff + unkCost
        if (pi >= 0) {
            val bi = find(biKeys, nBi, mix(hp, hw))
            if (bi >= 0) return deq(b.get(biP + bi))
        }
        return backoff + deq(b.get(uniP + wi))
    }

    fun knows(word: String) = find(uniKeys, nUni, fold(hash(word))) >= 0

    companion object {
        const val MAGIC = 0x314D4C46 // "FLM1": bigrams
        const val MAGIC2 = 0x324D4C46 // "FLM2": bigrams + trigrams
        val BOS = hash("<s>")
        val EOS = hash("</s>")

        /** FNV-1a 64 over UTF-16 code units (must match tools/build_lm.py). */
        fun hash(s: String): Long {
            var h = -3750763034362895579L // 0xcbf29ce484222325
            for (c in s) {
                h = h xor c.code.toLong()
                h *= 1099511628211L
            }
            return h
        }

        fun fold(h: Long): Int = (h xor (h ushr 32)).toInt()

        fun mix64(h1: Long, h2: Long): Long {
            var x = h1 * -7046029254386353131L + h2 // 0x9E3779B97F4A7C15
            x = x xor (x ushr 31)
            x *= -4658895280553007687L // 0xBF58476D1CE4E5B9
            x = x xor (x ushr 29)
            return x
        }

        fun mix(h1: Long, h2: Long): Int = mix64(h1, h2).toInt()
    }
}
