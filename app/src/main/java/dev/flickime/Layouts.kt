package dev.flickime

/** What a key does. [Input] keys emit one of up to five flick strings. */
sealed class Action {
    object Input : Action()
    object Backspace : Action()
    object Space : Action()
    object Enter : Action()
    object Left : Action()
    object Right : Action()
    object Mode : Action()     // あ ⇄ abc (123 → あ)
    object ToNumber : Action()
    object Dakuten : Action()
    object Shift : Action()
    object Globe : Action()
    object Emoji : Action()
    object Settings : Action()
}

/**
 * A key placed at [x, x+w) of the keyboard width (fractions) on [row, row+rowSpan).
 * flicks = [center, left, up, right, down]; only the center is drawn.
 * [shifted] is what the key types while shift is on (QWERTY).
 */
class KeyDef(
    val x: Float, val w: Float, val row: Int,
    val label: String,
    val action: Action = Action.Input,
    val flicks: List<String?> = listOf(label),
    val shifted: String? = null,
    val rowSpan: Int = 1,
    val accent: Boolean = false,
    val primary: Boolean = false,
    /** Long-press choices (Gboard style); the first one is drawn as a hint in the corner. */
    val longPress: List<String>? = null,
) {
    val letter: Char? get() = if (action == Action.Input && label.length == 1 && label[0] in 'a'..'z') label[0] else null
}

class KeyLayout(val keys: List<KeyDef>, val rows: Int, val rowHeightDp: Float, val glide: Boolean = false)

enum class Mode { KANA, ALPHA, NUMBER }

object Layouts {
    // 12-key geometry: side columns + three middle columns
    private const val SIDE = 0.16f
    private const val MID = (1f - 2 * SIDE) / 3f
    private fun cx(col: Int) = when (col) { 0 -> 0f; 4 -> 1f - SIDE; else -> SIDE + (col - 1) * MID }
    private fun cw(col: Int) = if (col == 0 || col == 4) SIDE else MID

    private fun k12(row: Int, col: Int, label: String, action: Action = Action.Input, flicks: List<String?> = listOf(label),
                    rowSpan: Int = 1, accent: Boolean = col == 0 || col == 4, primary: Boolean = false) =
        KeyDef(cx(col), cw(col), row, label, action, flicks, null, rowSpan, accent, primary)

    private fun flick(row: Int, col: Int, s: String) = k12(row, col, s.substring(0, 1), flicks = s.map { it.toString() })

    val kana = KeyLayout(listOf(
        k12(0, 0, "⚙", Action.Settings), flick(0, 1, "あいうえお"), flick(0, 2, "かきくけこ"), flick(0, 3, "さしすせそ"), k12(0, 4, "⌫", Action.Backspace),
        k12(1, 0, "◀", Action.Left), flick(1, 1, "たちつてと"), flick(1, 2, "なにぬねの"), flick(1, 3, "はひふへほ"), k12(1, 4, "▶", Action.Right),
        k12(2, 0, "123", Action.ToNumber), flick(2, 1, "まみむめも"), flick(2, 2, "や「ゆ」よ"), flick(2, 3, "らりるれろ"), k12(2, 4, "␣", Action.Space),
        k12(3, 0, "😊", Action.Emoji), k12(3, 1, "🌐", Action.Globe, accent = true), flick(3, 2, "わをんー"),
        k12(3, 3, "、。?!", flicks = listOf("、", "。", "？", "！", "…")), k12(3, 4, "⏎", Action.Enter, primary = true),
    ), rows = 4, rowHeightDp = 58f)

    /** Half-width digits with a few number-related symbols on flicks. */
    val number = KeyLayout(listOf(
        k12(0, 0, "◀", Action.Left), k12(0, 1, "1", flicks = listOf("1", "☆", "♪", "→")),
        k12(0, 2, "2", flicks = listOf("2", "¥", "$", "€")), k12(0, 3, "3", flicks = listOf("3", "%", "°", "#")), k12(0, 4, "⌫", Action.Backspace),
        k12(1, 0, "▶", Action.Right), k12(1, 1, "4", flicks = listOf("4", "○", "*", "・")),
        k12(1, 2, "5", flicks = listOf("5", "+", "×", "÷")), k12(1, 3, "6", flicks = listOf("6", "<", "=", ">")), k12(1, 4, "␣", Action.Space),
        k12(2, 0, "🌐", Action.Globe), k12(2, 1, "7", flicks = listOf("7", "「", "」", ":")),
        k12(2, 2, "8", flicks = listOf("8", "〒", "々", "〆")), k12(2, 3, "9", flicks = listOf("9", "^", "|", "\\")),
        k12(2, 4, "⏎", Action.Enter, rowSpan = 2, primary = true),
        k12(3, 0, "😊", Action.Emoji), k12(3, 1, "(", flicks = listOf("(", ")", "[", "]")),
        k12(3, 2, "0", flicks = listOf("0", "~", "…")), k12(3, 3, ".", flicks = listOf(".", ",", "-", "/")),
    ), rows = 4, rowHeightDp = 58f)

    /**
     * Long-press choices. First item = Gboard's corner symbol ("Touch & hold keys for symbols" with the
     * number row on); the rest = AOSP LatinIME's English more-keys (accents, digit fractions, period).
     */
    private val LONG_PRESS: Map<Char, List<String>> = mapOf(
        // top row: Gboard's symbols when the number row takes the digits
        'q' to listOf("%"), 'w' to listOf("\\"), 'e' to listOf("|", "é", "è", "ê", "ë", "ē"),
        'r' to listOf("="), 't' to listOf("["), 'y' to listOf("]"),
        'u' to listOf("<", "ú", "û", "ü", "ù", "ū"), 'i' to listOf(">", "í", "î", "ï", "ī", "ì"),
        'o' to listOf("{", "ó", "ô", "ö", "ò", "œ", "ø", "ō", "õ"), 'p' to listOf("}"),
        // home row: @ # $ _ & - + ( )
        'a' to listOf("@", "à", "á", "â", "ä", "æ", "ã", "å", "ā"), 's' to listOf("#", "ß"), 'd' to listOf("$"),
        'f' to listOf("_"), 'g' to listOf("&"), 'h' to listOf("-"), 'j' to listOf("+"), 'k' to listOf("("), 'l' to listOf(")"),
        // bottom row: * " ' : ; ! ?
        'z' to listOf("*"), 'x' to listOf("\""), 'c' to listOf("'", "ç"), 'v' to listOf(":"), 'b' to listOf(";"),
        'n' to listOf("!", "ñ"), 'm' to listOf("?"),
        // number row: superscript, subscript
        '1' to listOf("¹", "₁"), '2' to listOf("²", "₂"), '3' to listOf("³", "₃"), '4' to listOf("⁴", "₄"),
        '5' to listOf("⁵", "₅"), '6' to listOf("⁶", "₆"), '7' to listOf("⁷", "₇"), '8' to listOf("⁸", "₈"),
        '9' to listOf("⁹", "₉"), '0' to listOf("⁰", "₀"),
        // symbol keys (Gboard-style math / dash variants)
        '=' to listOf("≠", "≈", "∞", "±"), '-' to listOf("–", "—", "_", "·"), '/' to listOf("÷", "\\"),
        '[' to listOf("「", "【", "〈"), ']' to listOf("」", "】", "〉"),
        // period (LatinIME morekeys_punctuation)
        '.' to listOf(",", "?", "!", "#", ")", "(", "/", ";", "'", "@", ":", "-", "\"", "+", "%", "&"),
    )

    /** Full QWERTY like Gboard's English layout, with glide typing. */
    val qwerty: KeyLayout = run {
        val u = 1f / 13f
        val keys = ArrayList<KeyDef>()
        fun row(r: Int, start: Float, normal: String, shifted: String) {
            normal.forEachIndexed { i, ch ->
                keys.add(KeyDef(start + i * u, u, r, ch.toString(), shifted = shifted[i].toString(), longPress = LONG_PRESS[ch]))
            }
        }
        row(0, 0f, "`1234567890-=", "~!@#$%^&*()_+")
        row(1, 0f, "qwertyuiop[]\\", "QWERTYUIOP{}|")
        row(2, 0.5f * u, "asdfghjkl;'", "ASDFGHJKL:\"")
        keys.add(KeyDef(11.5f * u, 1.5f * u, 2, "⌫", Action.Backspace, accent = true))
        keys.add(KeyDef(0f, 1.5f * u, 3, "⇧", Action.Shift, accent = true))
        row(3, 1.5f * u, "zxcvbnm,./", "ZXCVBNM<>?")
        keys.add(KeyDef(11.5f * u, 1.5f * u, 3, "⇧", Action.Shift, accent = true))
        keys.add(KeyDef(0f, 1.5f * u, 4, "😊", Action.Emoji, accent = true))
        keys.add(KeyDef(1.5f * u, 1.5f * u, 4, "🌐", Action.Globe, accent = true))
        keys.add(KeyDef(3f * u, 7f * u, 4, "English", Action.Space))
        keys.add(KeyDef(10f * u, 3f * u, 4, "⏎", Action.Enter, primary = true))
        KeyLayout(keys, rows = 5, rowHeightDp = 48f, glide = true)
    }

    fun of(mode: Mode) = when (mode) { Mode.KANA -> kana; Mode.ALPHA -> qwerty; Mode.NUMBER -> number }

    private val dakutenCycles = listOf(
        "かが", "きぎ", "くぐ", "けげ", "こご", "さざ", "しじ", "すず", "せぜ", "そぞ",
        "ただ", "ちぢ", "つっづ", "てで", "とど", "はばぱ", "ひびぴ", "ふぶぷ", "へべぺ", "ほぼぽ",
        "あぁ", "いぃ", "うぅゔ", "えぇ", "おぉ", "やゃ", "ゆゅ", "よょ", "わゎ",
    )
    private val dakutenNext: Map<Char, Char> = buildMap {
        for (c in dakutenCycles) for (i in c.indices) put(c[i], c[(i + 1) % c.length])
    }
    fun cycleDakuten(ch: Char): Char? = dakutenNext[ch]
}
