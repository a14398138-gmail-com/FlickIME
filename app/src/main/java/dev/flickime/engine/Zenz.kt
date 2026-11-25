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

    /** zenz's own greedy conversion of [reading] (can fall outside the dictionary's N-best). */
    @Synchronized fun generate(reading: String, context: String): String? {
        if (handle == 0L) return null
        val b = nativeGenerate(handle, prompt(reading, context).toByteArray(), reading.length * 2 + 8)
        return String(b, Charsets.UTF_8).replace('　', ' ').ifEmpty { null }
    }

    @Synchronized fun close() { if (handle != 0L) nativeFree(handle); handle = 0 }

    companion object {
        @JvmStatic external fun nativeLoad(path: String, threads: Int): Long
        @JvmStatic external fun nativeScore(handle: Long, prompt: ByteArray, candidates: Array<ByteArray>): FloatArray
        @JvmStatic external fun nativeFree(handle: Long)
        @JvmStatic external fun nativeGenerate(handle: Long, prompt: ByteArray, maxTokens: Int): ByteArray

        fun load(path: String, threads: Int): Zenz? = nativeLoad(path, threads).takeIf { it != 0L }?.let { Zenz(it) }

        /**
         * Prompt layout. v3: [EE02 left context] EE00 katakana EE01.
         * v2 / v2.5 (the medium model): EE00 katakana [EE02 left context] EE01 (AzooKey's ZenzPromptBuilder).
         */
        @Volatile var v2Prompt = false

        fun prompt(reading: String, context: String): String {
            val ctx = context.takeLast(40).replace(' ', '\u3000')
            val input = "\uEE00" + Kana.toKatakana(reading)
            return if (v2Prompt) input + (if (ctx.isNotEmpty()) "\uEE02$ctx" else "") + "\uEE01"
            else (if (ctx.isNotEmpty()) "\uEE02$ctx" else "") + input + "\uEE01"
        }
    }
}
