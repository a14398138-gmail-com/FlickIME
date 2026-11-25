package dev.flickime

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM with a non-exportable key in the Android Keystore. */
object Crypto {
    private const val ALIAS = "flickime_log_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return g.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    fun encryptLine(s: String): String = Base64.encodeToString(encrypt(s.toByteArray()), Base64.NO_WRAP)
    fun decryptLine(s: String): String = String(decrypt(Base64.decode(s, Base64.NO_WRAP)))
}

/** Append-only, line-by-line encrypted log of committed conversions. */
class CommitLog(private val context: Context) {
    private val file get() = File(context.filesDir, "commits.log")

    fun append(event: JSONObject) {
        runCatching { file.appendText(Crypto.encryptLine(event.toString()) + "\n") }
    }

    fun count(): Int = if (file.exists()) file.useLines { it.count { l -> l.isNotBlank() } } else 0
    fun sizeBytes(): Long = if (file.exists()) file.length() else 0

    /** Writes decrypted JSONL; returns number of lines written. */
    fun exportTo(out: OutputStream): Int {
        if (!file.exists()) return 0
        var n = 0
        out.bufferedWriter().use { w ->
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                runCatching { Crypto.decryptLine(line) }.onSuccess { w.write(it); w.write("\n"); n++ }
            }
        }
        return n
    }

    fun clear() { file.delete() }
}

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("flickime", Context.MODE_PRIVATE)
    fun loggingEnabled(c: Context) = p(c).getBoolean("logging", true)
    fun setLoggingEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("logging", v).apply()
    // floating is remembered per orientation: landscape floats by default, portrait docks
    private fun land(c: Context) =
        c.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private fun sfx(c: Context) = if (land(c)) "_land" else ""
    fun floating(c: Context) = p(c).getBoolean("floating" + sfx(c), land(c))
    fun setFloating(c: Context, v: Boolean) = p(c).edit().putBoolean("floating" + sfx(c), v).apply()
    fun floatPos(c: Context) = p(c).getFloat("float_x" + sfx(c), -1f) to p(c).getFloat("float_y" + sfx(c), -1f)
    fun setFloatPos(c: Context, x: Float, y: Float) =
        p(c).edit().putFloat("float_x" + sfx(c), x).putFloat("float_y" + sfx(c), y).apply()
    const val DEFAULT_FLICK_DP = 22
    fun flickThresholdDp(c: Context) = p(c).getInt("flick_dp", DEFAULT_FLICK_DP)
    fun setFlickThresholdDp(c: Context, v: Int) = p(c).edit().putInt("flick_dp", v).apply()
    fun glassTheme(c: Context) = p(c).getBoolean("glass", false)
    fun setGlassTheme(c: Context, v: Boolean) = p(c).edit().putBoolean("glass", v).apply()
    /** Changes whenever the look changes, so the keyboard knows to rebuild. */
    fun themeKey(c: Context) = "${glassTheme(c)}:${p(c).getLong("bg", 0)}:${glassParams(c)}"
    fun glassParams(c: Context): GlassParams =
        GlassParams.fields.fold(GlassParams()) { acc, f -> acc.with(f.first, p(c).getInt("glass_${f.first}", 100)) }
    fun setGlassParams(c: Context, g: GlassParams) = p(c).edit().apply {
        GlassParams.fields.forEach { putInt("glass_${it.first}", g.get(it.first)) }
    }.apply()
    fun touchBackground(c: Context) = p(c).edit().putLong("bg", System.currentTimeMillis()).apply()
}

/** Encrypted storage for the learned (reading → surface) history. */
object HistoryStore {
    private fun f(c: Context) = File(c.filesDir, "history.bin")
    fun load(c: Context): String = runCatching { String(Crypto.decrypt(f(c).readBytes())) }.getOrDefault("")
    fun save(c: Context, text: String) { runCatching { f(c).writeBytes(Crypto.encrypt(text.toByteArray())) } }
    fun clear(c: Context) { f(c).delete() }
}

/** Encrypted storage for imported user-dictionary words. */
object UserDictStore {
    private fun f(c: Context) = File(c.filesDir, "userdict.bin")
    fun load(c: Context): String = runCatching { String(Crypto.decrypt(f(c).readBytes())) }.getOrDefault("")
    fun save(c: Context, text: String) { runCatching { f(c).writeBytes(Crypto.encrypt(text.toByteArray())) } }
    fun clear(c: Context) { f(c).delete() }
}

/** Encrypted list of English words the user typed by hand (one per line). */
object UserWordsStore {
    private fun f(c: Context) = File(c.filesDir, "userwords.bin")
    fun load(c: Context): List<String> =
        runCatching { String(Crypto.decrypt(f(c).readBytes())).lines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
    @Synchronized fun add(c: Context, word: String) {
        val all = (load(c) + word).distinct()
        runCatching { f(c).writeBytes(Crypto.encrypt(all.joinToString("\n").toByteArray())) }
    }
    fun clear(c: Context) { f(c).delete() }
}

/** Clipboard history kept by the keyboard (encrypted, like everything else it stores). */
data class ClipItem(val text: String, val pinned: Boolean, val time: Long)

object ClipStore {
    private const val MAX_UNPINNED = 30
    private fun f(c: Context) = File(c.filesDir, "clips.bin")

    fun load(c: Context): MutableList<ClipItem> = runCatching {
        val arr = org.json.JSONArray(String(Crypto.decrypt(f(c).readBytes())))
        MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            ClipItem(o.getString("t"), o.optBoolean("p"), o.optLong("ts"))
        }
    }.getOrElse { ArrayList() }

    fun save(c: Context, items: List<ClipItem>) {
        val arr = org.json.JSONArray()
        items.forEach { arr.put(JSONObject().put("t", it.text).put("p", it.pinned).put("ts", it.time)) }
        runCatching { f(c).writeBytes(Crypto.encrypt(arr.toString().toByteArray())) }
    }

    /** Newest first; pinned items are never dropped. */
    fun add(items: MutableList<ClipItem>, text: String) {
        val old = items.firstOrNull { it.text == text }
        items.removeAll { it.text == text }
        items.add(0, ClipItem(text, old?.pinned ?: false, System.currentTimeMillis()))
        var unpinned = 0
        items.removeAll { !it.pinned && ++unpinned > MAX_UNPINNED }
    }

    /** Bumped on clear so a running keyboard drops its in-memory copy too. */
    @Volatile var generation = 0

    fun clear(c: Context) { f(c).delete(); generation++ }
}
