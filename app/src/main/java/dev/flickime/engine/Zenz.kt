package dev.flickime.engine

/**
 * zenz (azooKey's neural kana-kanji model, GPT-2 small, CC BY-SA 4.0) running through llama.cpp.
 * Used to re-rank the converter's whole-sentence candidates.
 */
class Zenz private constructor(private var handle: Long) {
    /** Negative log-likelihood (nats) of each candidate; null if the model is gone. */
    @Synchronized fun score(reading: String, context: String, candidates: List<String>): FloatArray? {
        if (handle == 0L || candidates.isEmpty()) return null
        val r = nativeScore(handle, prompt(reading, context).toByteArray(), candidates.map { it.replace(' ', '　').toByteArray() }.toTypedArray())
        return if (r.any { it.isNaN() }) null else r
    }

    @Synchronized fun close() { if (handle != 0L) nativeFree(handle); handle = 0 }

    companion object {
        @JvmStatic external fun nativeLoad(path: String, threads: Int): Long
        @JvmStatic external fun nativeScore(handle: Long, prompt: ByteArray, candidates: Array<ByteArray>): FloatArray
        @JvmStatic external fun nativeFree(handle: Long)

        fun load(path: String, threads: Int): Zenz? = nativeLoad(path, threads).takeIf { it != 0L }?.let { Zenz(it) }

        /** zenz v3 prompt: [ left context]  katakana reading  */
        fun prompt(reading: String, context: String): String {
            val ctx = context.takeLast(40).replace(' ', '　')
            return (if (ctx.isNotEmpty()) "$ctx" else "") + "" + Kana.toKatakana(reading) + ""
        }
    }
}
