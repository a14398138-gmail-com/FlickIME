package dev.flickime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.flickime.engine.Candidate
import dev.flickime.engine.Converter
import dev.flickime.engine.Dictionary
import dev.flickime.engine.GlideDecoder
import dev.flickime.engine.LanguageModel
import dev.flickime.engine.UserHistory
import dev.flickime.engine.Kana
import org.json.JSONObject
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

/** Loads the dictionary once per process. */
object Engine {
    @Volatile var converter: Converter? = null
    @Volatile private var zenzTried = false

    /** Loads zenz into the converter (slow; call off the main thread). */
    fun loadZenz(c: Context) {
        if (zenzTried) return
        zenzTried = true
        runCatching {
            System.loadLibrary("zenzjni")
            // llama.cpp needs a real file: copy the model out of the APK once
            val f = java.io.File(c.filesDir, "zenz-v3.1-small.gguf")
            val size = c.assets.openFd("zenz.gguf").use { it.length }
            if (!f.exists() || f.length() != size) {
                val tmp = java.io.File(c.filesDir, "zenz.tmp")
                c.assets.open("zenz.gguf").use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                tmp.renameTo(f)
            }
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            dev.flickime.engine.Zenz.load(f.path, threads)?.let { z -> get(c).zenz = z }
        }.onFailure { android.util.Log.e("FlickIME", "zenz unavailable", it) }
    }
    @Volatile private var glide: GlideDecoder? = null

    fun glide(c: Context): GlideDecoder = glide ?: synchronized(this) {
        glide ?: GlideDecoder(c.assets.open("en_words.txt").bufferedReader().readLines().filter { it.isNotBlank() }).also { g ->
            UserWordsStore.load(c).forEach { g.addUserWord(it) }
            glide = g
        }
    }
    private val lock = Any()

    fun get(c: Context): Converter = converter ?: synchronized(lock) {
        converter ?: run {
            fun map(name: String) = c.assets.openFd(name).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length)
            }
            fun table(name: String) = Kana.parseTable(c.assets.open(name).bufferedReader().readText())
            val conn = map("conn.bin").also { it.load() }
            val lm = runCatching { LanguageModel(map("lm.bin")) }.getOrNull()
            val predictIds = runCatching { c.assets.open("predict_ids.txt").bufferedReader().readText().trim().split(' ').map { it.toInt() }.toSet() }.getOrNull()
            Converter(Dictionary(map("dict.bin"), conn), table("en.tsv"), table("symbols.tsv"), lm = lm, predictIds = predictIds).also {
                it.singleKanji = runCatching { dev.flickime.engine.singleKanjiTable(c.assets.open("single_kanji.tsv").bufferedReader().readText()) }.getOrDefault(emptyMap())
                it.history.load(HistoryStore.load(c))
                it.userDict.load(UserDictStore.load(c))
                converter = it
            }
        }
    }
}

class FlickIMEService : InputMethodService(), KeyboardView.Listener {
    /** Conversion only (candidates, zenz). */
    private val worker = Executors.newSingleThreadExecutor()
    /** File I/O (log, history, dictionary, clipboard) so saving never delays the next candidates. */
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var keyboard: KeyboardView
    private lateinit var candRow: LinearLayout
    private lateinit var candScroll: SwipeHorizontalScrollView
    private lateinit var log: CommitLog
    private lateinit var expandButton: TextView
    private lateinit var panel: SwipeScrollView
    private lateinit var flow: FlowLayout
    private var expanded = false

    private var mode = Mode.KANA
    private val reading = StringBuilder()
    private var candidates: List<Candidate> = emptyList()
    private var selected = -1
    @Volatile private var seq = 0
    /** User-chosen length of the first segment (◀▶ while converting); 0 = automatic. */
    private var segLen = 0
    private var themeKey = ""
    private val barViews = ArrayList<TextView>()
    private var lastRendered: List<Candidate>? = null
    /** Which ranking produced the shown candidates (logged with each commit). */
    private var candidatesEngine = "ngram"
    private var privateField = false
    private var historyDirty = false

    // glide typing state: the last glided word can be swapped for another candidate
    private var glideMode = false
    private var glidePrefix = ""
    private var glideCommitted = ""

    // A word assembled from several partial commits of one reading (e.g. 思想史 + 学).
    // If the result was not among the candidates, it is added to the user dictionary.
    private var compReading: String? = null
    private val compSurface = StringBuilder()
    private var compSegments = 0
    private var compOffered: Set<String> = emptySet()
    private var compAllWords = true

    private fun resetCompound() {
        compReading = null; compSurface.setLength(0); compSegments = 0; compOffered = emptySet(); compAllWords = true
    }

    private fun trackCompound(full: String, c: Candidate) {
        if (compReading == null) {
            compReading = full
            compOffered = candidates.filter { it.consumed == full.length }.map { it.surface }.toSet()
        }
        compSurface.append(c.surface)
        compSegments++
        // plain kana or whole-sentence picks are not "words the user built"
        if (c.kind == Candidate.Kind.KANA || c.kind == Candidate.Kind.SENTENCE) compAllWords = false
    }

    private fun finishCompound(): Pair<String, String>? {
        val r = compReading; val s = compSurface.toString(); val n = compSegments; val words = compAllWords
        resetCompound()
        if (r == null || n < 2 || !words || privateField || s in compOffered) return null
        if (r.length > 12 || s.length > 16 || s == r) return null
        val conv = Engine.converter ?: return null
        if (conv.userDict.add(r, s)) {
            val text = conv.userDict.serialize()
            io.execute { UserDictStore.save(applicationContext, text) }
            return r to s
        }
        return null
    }

    // ---- learning from commits, and taking it back when the user corrects right away ----

    /** Words committed so far in the current composition; adjacent pairs are learned at its end. */
    private val compWords = ArrayList<String>()

    private class LastCommit(val ts: Long, val text: String, val undo: UserHistory.Undo, var dictWord: Pair<String, String>?)
    private var lastCommit: LastCommit? = null
    private var corrWithin = 0      // chars of the last commit deleted right after it
    private var corrBeyond = 0      // chars deleted further back
    private var corrUndone = false

    /** Applies what a commit teaches; returns the undo record. */
    private fun learnFrom(c: Candidate, consumedReading: String, rank: Int): UserHistory.Undo {
        val undo = UserHistory.Undo()
        val conv = Engine.converter ?: return undo
        val h = conv.history
        val segs = c.segments
        when {
            // (1) a whole sentence: learn every word in it, lightly
            c.kind == Candidate.Kind.SENTENCE && segs != null && segs.size >= 2 -> {
                var off = 0
                for ((len, surf) in segs) {
                    if (off + len > consumedReading.length) break
                    h.learn(consumedReading.substring(off, off + len), surf, weight = 1, undo = undo)
                    off += len
                }
                if (consumedReading.length <= 10) h.learn(consumedReading, c.surface, undo = undo)
            }
            // a prediction is learned under the word's own reading (ありが → ありがとう learns ありがとう)
            c.kind == Candidate.Kind.PREDICTION && c.fullReading != null -> h.learn(c.fullReading, c.surface, undo = undo)
            // plain kana committed with ⏎: only short ones mean "I want hiragana here"
            rank < 0 && consumedReading.length > 6 -> Unit
            else -> h.learn(consumedReading, c.surface, undo = undo)
        }
        // (3) a deliberate pick below the top: the top one gets a small penalty
        if (rank >= 1 && c.kind != Candidate.Kind.PREDICTION) {
            val top = candidates.firstOrNull { it.consumed == c.consumed }
            if (top != null && top.surface != c.surface) h.penalize(consumedReading, top.surface, undo)
        }
        compWords.addAll(if (segs != null && c.kind == Candidate.Kind.SENTENCE) segs.map { it.second } else listOf(c.surface))
        historyDirty = true
        return undo
    }

    private fun learnPairs(undo: UserHistory.Undo) {
        val h = Engine.converter?.history ?: return
        for (i in 1 until compWords.size) h.learnPair(compWords[i - 1], compWords[i], undo)
        compWords.clear()
    }

    /** (5) Called on ⌫ with nothing being composed: is this deleting what was just committed? */
    private fun trackCorrection() {
        val lc = lastCommit ?: return
        val ic = currentInputConnection ?: return
        if (corrWithin < lc.text.length) {
            val remaining = lc.text.substring(0, lc.text.length - corrWithin)
            if (ic.getTextBeforeCursor(remaining.length, 0)?.toString() != remaining) { finishCorrection(); return }
            val unit = if (remaining.length >= 2 && Character.isLowSurrogate(remaining.last())) 2 else 1
            corrWithin += unit
        } else corrBeyond++
        // most of it was wrong: forget what this commit taught
        if (!corrUndone && corrWithin * 2 >= lc.text.length) {
            corrUndone = true
            Engine.converter?.let { conv ->
                conv.history.revert(lc.undo)
                lc.dictWord?.let { (r, s) ->
                    if (conv.userDict.remove(r, s)) {
                        val text = conv.userDict.serialize()
                        io.execute { UserDictStore.save(applicationContext, text) }
                    }
                }
                historyDirty = true
            }
        }
    }

    /** Ends correction tracking for the last commit, logging what was deleted (if anything). */
    private fun finishCorrection() {
        val lc = lastCommit ?: return
        lastCommit = null
        if (corrWithin == 0 && corrBeyond == 0) return
        if (!privateField && Prefs.loggingEnabled(this)) {
            val ev = JSONObject()
                .put("type", "correction")
                .put("ts", System.currentTimeMillis())
                .put("ref", lc.ts)
                .put("committed", lc.text)
                .put("deleted", lc.text.takeLast(corrWithin))
                .put("deleted_before", corrBeyond)
                .put("unlearned", corrUndone)
                .put("app", currentInputEditorInfo?.packageName ?: "")
            io.execute { log.append(ev) }
        }
        corrWithin = 0; corrBeyond = 0; corrUndone = false
    }

    // English word being typed key by key; unknown words get learned for glide typing
    private val typedWord = StringBuilder()
    private var typedWordValid = false

    private fun isWordChar(ch: Char) = ch in 'a'..'z' || ch in 'A'..'Z' || ch == '\''

    private fun finishTypedWord() {
        val w = typedWord.toString().trim('\'')
        val valid = typedWordValid
        typedWord.setLength(0); typedWordValid = false
        if (!valid || privateField || w.length < 2 || !w.any { it.isLetter() }) return
        val ctx = applicationContext
        worker.execute {
            val g = runCatching { Engine.glide(ctx) }.getOrNull() ?: return@execute
            if (!g.contains(w)) { g.addUserWord(w); io.execute { UserWordsStore.add(ctx, w) } }
        }
    }

    private fun trackTyped(text: String) {
        if (text.length == 1 && isWordChar(text[0])) {
            if (typedWord.isEmpty()) {
                // only words that start at a word boundary
                val before = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString().orEmpty()
                typedWordValid = before.isEmpty() || !isWordChar(before[0]) && !before[0].isDigit()
            }
            typedWord.append(text)
        } else finishTypedWord()
    }

    private val dp get() = resources.displayMetrics.density
    private val night get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private lateinit var theme: Theme

    // ---- clipboard ----
    private val clipboard by lazy { getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager }
    private var clips: MutableList<ClipItem> = ArrayList()
    private var recentClipShownUntil = 0L
    private lateinit var clipButton: TextView
    private lateinit var floatButton: TextView
    private lateinit var snippetButton: TextView
    private lateinit var snippetPanel: ScrollView
    private lateinit var snippetList: LinearLayout
    private var snippetOpen = false
    private lateinit var clipChip: TextView
    private lateinit var clipPanel: ScrollView
    private lateinit var clipList: LinearLayout
    private var clipOpen = false
    private lateinit var emojiPanel: EmojiPanel
    private var emojiOpen = false
    private val emojiCategories by lazy {
        assets.open("emoji_panel.tsv").bufferedReader().readLines().filter { it.isNotBlank() }.map { line ->
            val p = line.split('\t'); p[0] to p.drop(1)
        }
    }

    private val landscape get() =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private fun barHeightDp() = if (landscape) 36f else 44f

    /** Keep the app visible in landscape instead of switching to the full-screen text box. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /** Height of the current key layout; overlay panels are sized to exactly this. */
    private fun keyboardHeightPx(): Int = keyboard.height.takeIf { it > 0 }
        ?: (keyboard.layout.rows * keyboard.layout.rowHeightDp * (if (landscape) 0.7f else 1f) * dp).toInt()

    private fun fitToKeyboard(v: View) {
        val h = keyboardHeightPx()
        if (v.layoutParams.height != h) { v.layoutParams = v.layoutParams.apply { height = h } }
    }

    private fun showEmoji(on: Boolean) {
        emojiOpen = on
        if (on) fitToKeyboard(emojiPanel)
        if (on) {
            expand(false); if (clipOpen) showClips(false)
            if (snippetOpen) { snippetOpen = false; snippetPanel.visibility = View.GONE; snippetButton.text = "📝" }
            emojiPanel.show()
        }
        emojiPanel.visibility = if (on) View.VISIBLE else View.GONE
        keyboard.visibility = if (on || expanded || clipOpen) View.INVISIBLE else View.VISIBLE
    }
    private var clipGeneration = ClipStore.generation

    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener { onClipChanged() }

    private fun onClipChanged() {
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return
        // passwords copied from password managers are flagged sensitive: never keep them
        if (Build.VERSION.SDK_INT >= 24 &&
            clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return
        val text = (0 until clip.itemCount).joinToString("\n") { clip.getItemAt(it).coerceToText(this).toString() }.trim()
        if (text.isEmpty() || text.length > 5000) return
        if (clipGeneration != ClipStore.generation) { clipGeneration = ClipStore.generation; clips.clear() }
        ClipStore.add(clips, text)
        recentClipShownUntil = System.currentTimeMillis() + 60_000
        val snapshot = clips.toList()
        io.execute { ClipStore.save(applicationContext, snapshot) }
        if (::clipChip.isInitialized) { updateBarButtons(); if (clipOpen) renderClips() }
    }

    private fun updateBarButtons() {
        val idle = candidates.isEmpty() && reading.isEmpty()
        clipButton.visibility = if (idle) View.VISIBLE else View.GONE
        floatButton.visibility = if (idle) View.VISIBLE else View.GONE
        snippetButton.visibility = if (idle) View.VISIBLE else View.GONE
        val recent = clips.firstOrNull()
        val showChip = idle && recent != null && System.currentTimeMillis() < recentClipShownUntil
        clipChip.visibility = if (showChip) View.VISIBLE else View.GONE
        if (showChip) clipChip.text = "📋 " + recent!!.text.replace('\n', ' ').take(30)
    }

    private fun pasteClip(text: String) {
        if (reading.isNotEmpty()) commitKana()
        currentInputConnection?.commitText(text, 1)
        recentClipShownUntil = 0
        showClips(false)
        updateBarButtons()
    }

    private fun showSnippets(on: Boolean) {
        snippetOpen = on
        if (on) {
            fitToKeyboard(snippetPanel)
            expand(false)
            if (clipOpen) showClips(false)
            if (::emojiPanel.isInitialized && emojiOpen) { emojiOpen = false; emojiPanel.visibility = View.GONE }
            renderSnippets(SnippetStore.load(this))
        }
        snippetPanel.visibility = if (on) View.VISIBLE else View.GONE
        keyboard.visibility = if (on || expanded || clipOpen) View.INVISIBLE else View.VISIBLE
        snippetButton.text = if (on) "⌨" else "📝"
    }

    private fun renderSnippets(items: List<String>) {
        snippetList.removeAllViews()
        val pad = (12 * dp).toInt()
        if (items.isEmpty()) {
            snippetList.addView(TextView(this).apply {
                text = "定型文はまだありません。設定画面の「定型文」から登録できます。"; setTextColor(fgColor()); setPadding(pad, pad, pad, pad)
            })
            return
        }
        for (item in items) {
            snippetList.addView(TextView(this).apply {
                text = item; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(fgColor())
                setPadding(pad, pad, pad, pad)
                setBackgroundColor(Color.argb(if (night) 40 else 120, 255, 255, 255))
                setOnClickListener {
                    if (reading.isNotEmpty()) commitKana()
                    currentInputConnection?.commitText(item, 1)
                    showSnippets(false)
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins((6 * dp).toInt(), (3 * dp).toInt(), (6 * dp).toInt(), (3 * dp).toInt())
            })
        }
    }

    private fun showClips(on: Boolean) {
        clipOpen = on
        if (on && ::snippetPanel.isInitialized && snippetOpen) { snippetOpen = false; snippetPanel.visibility = View.GONE; snippetButton.text = "📝" }
        if (on) fitToKeyboard(clipPanel)
        if (on) { expand(false); if (::emojiPanel.isInitialized && emojiOpen) { emojiOpen = false; emojiPanel.visibility = View.GONE } }
        clipPanel.visibility = if (on) View.VISIBLE else View.GONE
        keyboard.visibility = if (on || expanded) View.INVISIBLE else View.VISIBLE
        clipButton.text = if (on) "⌨" else "📋"
        if (on) renderClips()
    }

    private fun renderClips() {
        clipList.removeAllViews()
        val pad = (12 * dp).toInt()
        if (clips.isEmpty()) {
            clipList.addView(TextView(this).apply {
                text = "コピーしたテキストがここに表示されます"; setTextColor(fgColor()); setPadding(pad, pad, pad, pad)
            })
            return
        }
        val sorted = clips.sortedWith(compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.time })
        for (item in sorted) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.argb(if (night) 40 else 120, 255, 255, 255))
            }
            val tv = TextView(this).apply {
                text = item.text; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(fgColor())
                setPadding(pad, pad, pad, pad)
                setOnClickListener { pasteClip(item.text) }
            }
            fun icon(label: String, onClick: () -> Unit) = TextView(this).apply {
                text = label; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(fgColor())
                setOnClickListener { onClick() }
            }
            row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(icon(if (item.pinned) "📌" else "📍") {
                val i = clips.indexOfFirst { it.text == item.text }
                if (i >= 0) clips[i] = clips[i].copy(pinned = !clips[i].pinned)
                saveClips(); renderClips()
            }, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
            row.addView(icon("✕") {
                clips.removeAll { it.text == item.text }; saveClips(); renderClips(); updateBarButtons()
            }, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
            clipList.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins((6 * dp).toInt(), (3 * dp).toInt(), (6 * dp).toInt(), (3 * dp).toInt())
            })
        }
    }

    private fun saveClips() {
        val snapshot = clips.toList()
        io.execute { ClipStore.save(applicationContext, snapshot) }
    }

    // ---- zenz: re-rank after a short pause in typing ----
    private val zenzWorker = Executors.newSingleThreadExecutor()
    /** Text before the composition (zenz uses it as left context). */
    private var compositionContext = ""

    private fun captureContext() {
        compositionContext = currentInputConnection?.getTextBeforeCursor(40, 0)?.toString().orEmpty()
    }

    override fun onCreate() {
        super.onCreate()
        if (Prefs.zenzEnabled(this)) zenzWorker.execute { Engine.loadZenz(applicationContext) }
        clipboard.addPrimaryClipChangedListener(clipListener)
        io.execute { val loaded = ClipStore.load(applicationContext); main.post { clips.addAll(0, loaded.filter { l -> clips.none { it.text == l.text } }) } }
        log = CommitLog(this)
        worker.execute { runCatching { Engine.get(applicationContext); Engine.glide(applicationContext) } }
    }

    // ---- floating keyboard (Gboard-style) ----
    private var floating = false
    private var floatContainer: FrameLayout? = null
    private var floatCard: View? = null
    private var originalWindowBg: android.graphics.drawable.Drawable? = null
    private var savedOriginalBg = false

    override fun onCreateInputView(): View {
        floating = Prefs.floating(this)
        val kb = buildKeyboard()
        val w = window.window
        if (w != null && !savedOriginalBg) { originalWindowBg = w.decorView.background; savedOriginalBg = true }
        if (!floating) {
            floatContainer = null; floatCard = null
            if (savedOriginalBg) w?.setBackgroundDrawable(originalWindowBg)
            return kb
        }
        // The input view fills the screen but is transparent; only the card takes touches
        // (see onComputeInsets), so the app underneath keeps its full size and stays usable.
        w?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        val screenH = if (Build.VERSION.SDK_INT >= 30)
            (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).currentWindowMetrics.bounds.height()
        else resources.displayMetrics.heightPixels
        val screenW = resources.displayMetrics.widthPixels
        val container = object : FrameLayout(this) {
            override fun onMeasure(wSpec: Int, hSpec: Int) {
                super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(screenH, MeasureSpec.EXACTLY))
            }
        }
        val cardW = if (landscape) (screenW * 0.5f).toInt() else (screenW * 0.82f).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = 12 * dp
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, 18 * dp)
            }
            clipToOutline = true
            background = kb.background
        }
        // drag handle with a button to dock the keyboard again
        val handle = FrameLayout(this)
        val grip = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 3 * dp; setColor(Color.argb(140, Color.red(theme.text), Color.green(theme.text), Color.blue(theme.text)))
            }
        }
        handle.addView(grip, FrameLayout.LayoutParams((48 * dp).toInt(), (5 * dp).toInt(), Gravity.CENTER))
        handle.addView(TextView(this).apply {
            text = "▣"; gravity = Gravity.CENTER; setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            contentDescription = "固定に戻す"
            setOnClickListener { setFloating(false) }
        }, FrameLayout.LayoutParams((44 * dp).toInt(), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END))
        card.addView(handle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (22 * dp).toInt()))
        kb.background = null
        card.addView(kb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(card, FrameLayout.LayoutParams(cardW, FrameLayout.LayoutParams.WRAP_CONTENT))

        fun clampAndPlace(x: Float, y: Float) {
            val maxX = (container.width - card.width).coerceAtLeast(0).toFloat()
            val maxY = (container.height - card.height).coerceAtLeast(0).toFloat()
            card.translationX = x.coerceIn(0f, maxX); card.translationY = y.coerceIn(0f, maxY)
        }
        card.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val (px, py) = Prefs.floatPos(this)
            clampAndPlace(if (px < 0) (container.width - card.width) / 2f else px, if (py < 0) container.height - card.height - 80 * dp else py)
        }
        var lastX = 0f; var lastY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.rawX; lastY = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    clampAndPlace(card.translationX + e.rawX - lastX, card.translationY + e.rawY - lastY)
                    lastX = e.rawX; lastY = e.rawY
                    window.window?.decorView?.requestLayout() // refresh the touchable region
                }
                MotionEvent.ACTION_UP -> Prefs.setFloatPos(this, card.translationX, card.translationY)
            }
            true
        }
        floatContainer = container; floatCard = card
        return container
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val container = floatContainer ?: return
        val card = floatCard ?: return
        if (!floating || !container.isShown) return
        // the app is not resized; only the floating card receives touches
        val h = container.height
        outInsets.contentTopInsets = h
        outInsets.visibleTopInsets = h
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        val x = card.translationX.toInt(); val y = card.translationY.toInt()
        outInsets.touchableRegion.set(x, y, x + card.width, y + card.height)
    }

    private fun setFloating(on: Boolean) {
        if (reading.isNotEmpty()) { if (selected >= 0) commitCandidate(selected) else commitKana() }
        Prefs.setFloating(this, on)
        setInputView(onCreateInputView())
    }

    private fun buildKeyboard(): LinearLayout {
        theme = Theme.of(this)
        themeKey = Prefs.themeKey(this)
        barViews.clear()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bgImage = if (theme.glass) BackgroundImage.load(this) else null
        if (bgImage != null) {
            root.background = opaque(GlassBackdrop(night, translucent = false, image = bgImage, params = theme.params))
        } else if (theme.glass) {
            // opaque backdrop: the app behind the keyboard must not show through
            root.background = opaque(GlassBackdrop(night, translucent = false, params = theme.params))
        } else {
            root.setBackgroundColor(theme.background)
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        candRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        candScroll = SwipeHorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(candRow)
            onSwipeUp = { if (candidates.isNotEmpty()) { expand(true); true } else false }
            onSwipeDown = { if (expanded) { expand(false); true } else false }
        }
        clipButton = TextView(this).apply {
            text = "📋"; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setOnClickListener { showClips(!clipOpen) }
        }
        bar.addView(clipButton, LinearLayout.LayoutParams((48 * dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT))
        floatButton = TextView(this).apply {
            text = if (floating) "▣" else "⧉"; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTextColor(fgColor())
            contentDescription = if (floating) "固定に戻す" else "フローティング"
            setOnClickListener { setFloating(!floating) }
        }
        bar.addView(floatButton, LinearLayout.LayoutParams((44 * dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT))
        snippetButton = TextView(this).apply {
            text = "📝"; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            contentDescription = "定型文"
            // one registered text, typed straight away (set it in the settings screen)
            setOnClickListener {
                val t = SnippetStore.load(this@FlickIMEService).firstOrNull()
                if (t.isNullOrEmpty()) android.widget.Toast.makeText(this@FlickIMEService, "設定画面の「定型文」で登録してください", android.widget.Toast.LENGTH_SHORT).show()
                else { if (reading.isNotEmpty()) commitKana(); currentInputConnection?.commitText(t, 1) }
            }
        }
        bar.addView(snippetButton, LinearLayout.LayoutParams((44 * dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT))
        clipChip = TextView(this).apply {
            gravity = Gravity.CENTER_VERTICAL; isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(fgColor())
            setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
            setBackgroundColor(theme.selectedBg)
            visibility = View.GONE
            setOnClickListener { clips.firstOrNull()?.let { pasteClip(it.text) } }
        }
        bar.addView(clipChip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(0, (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
        })
        bar.addView(candScroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        expandButton = TextView(this).apply {
            text = "▾"; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(fgColor())
            visibility = View.INVISIBLE
            setOnClickListener { expand(!expanded) }
        }
        bar.addView(expandButton, LinearLayout.LayoutParams((48 * dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT))
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (barHeightDp() * dp).toInt()))

        val body = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        root.clipChildren = false; root.clipToPadding = false
        keyboard = KeyboardView(this, this, theme).apply {
            topHeadroom = barHeightDp() * dp
            flickThresholdDp = Prefs.flickThresholdDp(this@FlickIMEService).toFloat()
        }
        body.addView(keyboard)
        flow = FlowLayout(this).apply { rowHeight = (48 * dp).toInt() }
        if (theme.glass) flow.setBackgroundColor(Color.argb(if (night) 60 else 90, 255, 255, 255))
        panel = SwipeScrollView(this).apply {
            addView(flow)
            visibility = View.GONE
            // pulling down while already at the top closes the panel; otherwise it just scrolls
            onSwipeDown = { if (scrollY == 0) { expand(false); true } else false }
        }
        body.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (4 * 56 * dp).toInt()))
        clipList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt()) }
        clipPanel = ScrollView(this).apply { addView(clipList); visibility = View.GONE }
        body.addView(clipPanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (4 * 58 * dp).toInt()))
        clipOpen = false
        snippetList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt()) }
        snippetPanel = ScrollView(this).apply { addView(snippetList); visibility = View.GONE }
        body.addView(snippetPanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (4 * 58 * dp).toInt()))
        snippetOpen = false
        emojiPanel = EmojiPanel(this, theme, emojiCategories,
            onPick = { e -> currentInputConnection?.commitText(e, 1) },
            onBack = { showEmoji(false) },
            onSpace = { currentInputConnection?.commitText(" ", 1) },
            onDelete = { sendKey(KeyEvent.KEYCODE_DEL) },
        ).apply { visibility = View.GONE }
        body.addView(emojiPanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (4 * 58 * dp).toInt()))
        emojiOpen = false
        root.addView(body)
        // Android 15 draws the IME edge-to-edge: keep keys above the navigation bar
        // (the strip with the system ∨ / 🌐 buttons), the way Gboard does.
        if (!floating) root.setOnApplyWindowInsetsListener { v, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) {
                maxOf(
                    insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom,
                    insets.getInsets(android.view.WindowInsets.Type.tappableElement()).bottom,
                )
            } else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            v.setPadding(0, 0, 0, bottom)
            insets
        }
        root.requestApplyInsets()
        applyMode()
        return root
    }

    private fun fgColor() = theme.text

    /** Solid colour under the glass so nothing behind the keyboard window can ever show through. */
    private fun opaque(d: android.graphics.drawable.Drawable) = android.graphics.drawable.LayerDrawable(arrayOf(
        android.graphics.drawable.ColorDrawable(if (night) Color.rgb(14, 16, 28) else Color.rgb(222, 228, 242)), d,
    ))

    private fun expand(on: Boolean) {
        val want = on && candidates.isNotEmpty()
        if (want == expanded) return
        expanded = want
        panel.visibility = if (expanded) View.VISIBLE else View.GONE
        keyboard.visibility = if (expanded) View.INVISIBLE else View.VISIBLE
        expandButton.text = if (expanded) "▴" else "▾"
        if (expanded) { fitToKeyboard(panel); renderPanel(); panel.scrollTo(0, 0) } else flow.removeAllViews()
    }

    private fun candidateView(i: Int, c: Candidate, highlight: Boolean) = TextView(this).apply {
        text = c.surface
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(if (c.kind == Candidate.Kind.ENGLISH) theme.englishText else fgColor())
        setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        gravity = Gravity.CENTER
        isSingleLine = true
        if (highlight) setBackgroundColor(theme.selectedBg)
        setOnClickListener { expand(false); commitCandidate(i) }
        setOnLongClickListener { forgetCandidate(c); true }
    }

    /** Long-press: remove a learned word / prediction (Gboard's "候補から削除"). */
    private fun forgetCandidate(c: Candidate) {
        val conv = Engine.converter ?: return
        if (glideMode) return
        val r = c.fullReading ?: reading.substring(0, c.consumed.coerceAtMost(reading.length))
        val fromHistory = conv.history.forget(r, c.surface)
        val fromDict = conv.userDict.remove(r, c.surface)
        if (!fromHistory && !fromDict) {
            android.widget.Toast.makeText(this, "学習した候補ではありません", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        historyDirty = true
        saveHistory()
        if (fromDict) { val text = conv.userDict.serialize(); io.execute { UserDictStore.save(applicationContext, text) } }
        android.widget.Toast.makeText(this, "「${c.surface}」を候補から削除しました", android.widget.Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun renderPanel() {
        flow.removeAllViews()
        candidates.forEachIndexed { i, c -> flow.addView(candidateView(i, c, i == selected)) }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // rebuild the view when the theme was switched in settings
        if (::theme.isInitialized && themeKey != Prefs.themeKey(this)) setInputView(onCreateInputView())
        typedWord.setLength(0); typedWordValid = false
        resetComposition()
        if (::clipPanel.isInitialized && clipOpen) showClips(false)
        if (::emojiPanel.isInitialized && emojiOpen) showEmoji(false)
        if (::snippetPanel.isInitialized && snippetOpen) showSnippets(false)
        if (::keyboard.isInitialized) keyboard.flickThresholdDp = Prefs.flickThresholdDp(this).toFloat()
        if (clipGeneration != ClipStore.generation) { clipGeneration = ClipStore.generation; clips.clear(); recentClipShownUntil = 0 }
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val password = (cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        )) || (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val noLearning = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        privateField = password || noLearning
        mode = when {
            cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME -> Mode.NUMBER
            password || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_URI -> Mode.ALPHA
            else -> Mode.KANA
        }
        if (::keyboard.isInitialized) applyMode()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (reading.isNotEmpty()) commitKana()
        finishCorrection()
        compWords.clear()
        finishTypedWord()
        saveHistory()
    }

    override fun onDestroy() {
        runCatching { clipboard.removePrimaryClipChangedListener(clipListener) }
        saveHistory()
        worker.shutdown()
        io.shutdown()
        zenzWorker.shutdown()
        super.onDestroy()
    }

    private fun saveHistory() {
        if (!historyDirty) return
        historyDirty = false
        val text = Engine.converter?.history?.serialize() ?: return
        io.execute { HistoryStore.save(applicationContext, text) }
    }

    private fun applyMode() {
        keyboard.layout = Layouts.of(mode)
    }

    private fun endGlide() {
        if (!glideMode) return
        glideMode = false; glideCommitted = ""; glidePrefix = ""
        candidates = emptyList(); selected = -1; renderCandidates()
    }

    override fun onGlide(path: List<GlideDecoder.P>, centers: Map<Char, GlideDecoder.P>, keyWidth: Float, keyHeight: Float) {
        finishTypedWord()
        val caps = keyboard.glideCaps
        worker.execute {
            val words = runCatching { Engine.glide(applicationContext).decode(path, centers, keyWidth, keyHeight) }.getOrDefault(emptyList())
                .map { w -> if (caps) w.replaceFirstChar { it.uppercase() } else if (w == "i" || w.startsWith("i'")) w.replaceFirstChar { it.uppercase() } else w }
            main.post {
                // results are posted in order, so quick successive glides are all kept
                if (words.isEmpty() || mode != Mode.ALPHA) return@post
                val ic = currentInputConnection ?: return@post
                endGlide()
                val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
                glidePrefix = if (before.isNotEmpty() && !before[0].isWhitespace() && before[0] !in "([{\"'") " " else ""
                glideCommitted = glidePrefix + words[0]
                ic.commitText(glideCommitted, 1)
                glideMode = true
                candidates = words.map { Candidate(it, 0, Candidate.Kind.ENGLISH) }
                selected = 0
                renderCandidates()
            }
        }
    }

    private fun replaceGlide(i: Int) {
        val ic = currentInputConnection ?: return
        val w = candidates.getOrNull(i)?.surface ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(glideCommitted.length, 0)
        glideCommitted = glidePrefix + w
        ic.commitText(glideCommitted, 1)
        ic.endBatchEdit()
        selected = i
        renderCandidates()
    }

    // ---- KeyboardView.Listener ----

    override fun onText(text: String) {
        endGlide()
        finishCorrection()
        if (mode == Mode.ALPHA) trackTyped(text)
        if (mode != Mode.KANA) {
            currentInputConnection?.commitText(text, 1)
            return
        }
        if (selected >= 0) commitCandidate(selected)
        if (compReading != null) resetCompound()
        segLen = 0
        if (reading.isEmpty()) captureContext()
        reading.append(text)
        refresh()
    }

    override fun onAction(action: Action) {
        val ic = currentInputConnection ?: return
        if (glideMode && action == Action.Backspace) {
            // like Gboard: backspace right after a glide removes the whole word,
            // but only if the cursor is still right after it
            val word = glideCommitted.removePrefix(glidePrefix)
            val before = ic.getTextBeforeCursor(glideCommitted.length, 0)?.toString().orEmpty()
            endGlide()
            when {
                // the word plus the space we put in front of it
                before == glidePrefix + word -> ic.deleteSurroundingText(before.length, 0)
                // some apps trim or change that space: still remove the whole word
                word.isNotEmpty() && before.endsWith(word) -> ic.deleteSurroundingText(word.length, 0)
                else -> sendKey(KeyEvent.KEYCODE_DEL)
            }
            return
        }
        endGlide()
        if (action == Action.Backspace && typedWord.isNotEmpty()) typedWord.deleteCharAt(typedWord.length - 1)
        else if (action == Action.Left || action == Action.Right) { typedWord.setLength(0); typedWordValid = false }
        else if (action != Action.Backspace && action != Action.Shift) finishTypedWord()
        when (action) {
            Action.Backspace -> when {
                selected >= 0 -> { selected = -1; showComposing(); renderCandidates() }
                reading.isNotEmpty() -> { reading.deleteCharAt(reading.length - 1); resetCompound(); segLen = 0; refresh() }
                else -> { trackCorrection(); sendKey(KeyEvent.KEYCODE_DEL) }
            }
            Action.Space -> when {
                reading.isNotEmpty() && candidates.isNotEmpty() -> {
                    selected = (selected + 1) % candidates.size
                    showComposing(); renderCandidates()
                }
                reading.isNotEmpty() -> Unit
                else -> ic.commitText(" ", 1)
            }
            Action.Enter -> when {
                selected >= 0 -> commitCandidate(selected)
                reading.isNotEmpty() -> commitKana()
                else -> {
                    val ei = currentInputEditorInfo
                    val act = ei?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
                    val noEnter = (ei?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
                    if (!noEnter && act != EditorInfo.IME_ACTION_NONE && act != EditorInfo.IME_ACTION_UNSPECIFIED) ic.performEditorAction(act)
                    else sendKey(KeyEvent.KEYCODE_ENTER)
                }
            }
            Action.Left -> if (reading.isEmpty()) sendKey(KeyEvent.KEYCODE_DPAD_LEFT) else moveBoundary(-1)
            Action.Right -> if (reading.isEmpty()) sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) else moveBoundary(+1)
            Action.Mode -> {
                if (reading.isNotEmpty()) { if (selected >= 0) commitCandidate(selected) else commitKana() }
                mode = if (mode == Mode.KANA) Mode.ALPHA else Mode.KANA
                applyMode()
            }
            Action.Dakuten -> if (reading.isNotEmpty()) {
                if (selected >= 0) { selected = -1 }
                val last = reading.last()
                Layouts.cycleDakuten(last)?.let { reading.setCharAt(reading.length - 1, it); resetCompound(); segLen = 0; refresh() }
            }
            Action.ToNumber -> {
                if (reading.isNotEmpty()) { if (selected >= 0) commitCandidate(selected) else commitKana() }
                mode = Mode.NUMBER; applyMode()
            }
            Action.Shift -> Unit
            Action.Emoji -> {
                if (reading.isNotEmpty()) { if (selected >= 0) commitCandidate(selected) else commitKana() }
                showEmoji(true)
            }
            Action.Settings -> {
                if (reading.isNotEmpty()) { if (selected >= 0) commitCandidate(selected) else commitKana() }
                startActivity(android.content.Intent(this, SettingsActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            // the globe key swaps the Japanese and English keyboards (switching to another IME
            // is still available from the system's globe button in the navigation bar)
            Action.Globe -> {
                if (reading.isNotEmpty()) { if (selected >= 0) commitCandidate(selected) else commitKana() }
                mode = if (mode == Mode.KANA) Mode.ALPHA else Mode.KANA
                applyMode()
            }
            Action.Input -> Unit
        }
    }

    /**
     * The user moved the cursor (tapped in the text, dragged a handle...). If that happened
     * outside the text being composed, finish the composition where it is so later edits
     * (⌫ etc.) act at the new cursor instead of at the old composing text.
     */
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                                   candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // While our own commit/composing edits are in flight the editor briefly reports no
        // composing region (candidatesStart < 0); that is not the user moving the cursor.
        val movedOutside = candidatesStart >= 0 &&
            (newSelStart != newSelEnd || newSelStart < candidatesStart || newSelEnd > candidatesEnd)
        if (reading.isNotEmpty() && movedOutside) {
            currentInputConnection?.finishComposingText()
            seq++
            reading.setLength(0); candidates = emptyList(); selected = -1; segLen = 0
            resetCompound(); compWords.clear()
            if (::keyboard.isInitialized) keyboard.composing = false
            if (::candRow.isInitialized) renderCandidates()
        }
        if (reading.isEmpty() && (newSelStart != oldSelStart || newSelEnd != oldSelEnd) && candidatesStart < 0) {
            // a jump that is not explained by our own typing breaks word tracking
            val typedJump = newSelStart == newSelEnd && newSelStart - oldSelEnd in 0..(if (glideMode) glideCommitted.length else 1)
            if (!typedJump) { typedWord.setLength(0); typedWordValid = false; endGlide() }
        }
    }

    private fun sendKey(code: Int) {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // ---- composition ----

    private fun resetComposition() {
        reading.setLength(0); candidates = emptyList(); selected = -1; seq++
        resetCompound(); segLen = 0
        compWords.clear()
        if (::keyboard.isInitialized) keyboard.composing = false
        if (::candRow.isInitialized) renderCandidates()
    }

    private fun showComposing() {
        if (::keyboard.isInitialized) keyboard.composing = reading.isNotEmpty()
        val ic = currentInputConnection ?: return
        val text: CharSequence = if (selected >= 0) {
            val c = candidates[selected]
            c.surface + reading.substring(c.consumed)
        } else if (segLen in 1 until reading.length) {
            // highlight the segment being converted
            android.text.SpannableString(reading).apply {
                setSpan(android.text.style.BackgroundColorSpan(Color.argb(90, 90, 140, 240)), 0, segLen, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.UnderlineSpan(), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else reading.toString()
        ic.setComposingText(text, 1)
    }

    /** Shrink / extend the first segment, like Gboard's ◀▶ during conversion. */
    private fun moveBoundary(delta: Int) {
        val n = reading.length
        if (n < 2 && delta < 0) return
        val base = if (segLen > 0) segLen else
            runCatching { Engine.get(applicationContext).firstSegmentLength(reading.toString()) }.getOrDefault(n)
        val next = (base + delta).coerceIn(1, n)
        if (next == segLen) return
        segLen = next
        refresh()
    }

    private fun refresh() {
        selected = -1
        showComposing()
        val r = reading.toString()
        val my = ++seq
        if (r.isEmpty()) { candidates = emptyList(); renderCandidates(); return }
        // snapshot main-thread state now: the worker must not read fields that keep changing
        val sl = segLen
        val ctx = if (privateField) "" else compositionContext
        val useZenz = Prefs.zenzEnabled(this)
        worker.execute {
            if (my != seq) return@execute // a newer keystroke is already queued
            var engine = "ngram"
            val cands = runCatching {
                val conv = Engine.get(applicationContext)
                // zenz ranks the sentences before anything is shown (no n-gram first, no swap later)
                if (sl == 0 && r.length >= 2 && conv.zenz != null && useZenz && !conv.zenzRanked(r, ctx)) {
                    if (my != seq) return@execute
                    conv.zenzRerank(r, ctx)
                    if (my != seq) return@execute
                }
                if (sl == 0 && conv.zenzRanked(r, ctx)) engine = "zenz"
                if (sl in 1..r.length) conv.segmentCandidates(r, sl) else conv.candidates(r, ctx)
            }.getOrElse {
                engine = "fallback"
                listOf(Candidate(r, r.length, Candidate.Kind.KANA), Candidate(Kana.toKatakana(r), r.length, Candidate.Kind.KANA))
            }
            main.post {
                if (my == seq) { candidates = cands; candidatesEngine = engine; renderCandidates() }
            }
        }
    }

    private fun commitKana() {
        val r = reading.toString()
        commit(Candidate(r, r.length, Candidate.Kind.KANA), rank = -1)
    }

    private fun commitCandidate(i: Int) {
        if (glideMode) { replaceGlide(i); return }
        val c = candidates.getOrNull(i) ?: return
        commit(c, rank = i)
    }

    private fun commit(c: Candidate, rank: Int) {
        val ic = currentInputConnection ?: return
        val full = reading.toString()
        val consumedReading = full.substring(0, c.consumed)
        finishCorrection()
        val before = ic.getTextBeforeCursor(40, 0)?.toString().orEmpty()
        ic.commitText(c.surface, 1)
        trackCompound(full, c)
        val ts = System.currentTimeMillis()

        if (!privateField) {
            val undo = learnFrom(c, consumedReading, rank)
            lastCommit = LastCommit(ts, c.surface, undo, null)
            if (Prefs.loggingEnabled(this)) {
                val top = candidates.firstOrNull { it.consumed == c.consumed }?.surface
                val ev = JSONObject()
                    .put("type", "commit")
                    .put("ts", ts)
                    .put("version", BuildConfig.VERSION_NAME)
                    // which ranking produced the list this was picked from: zenz / ngram (3-gram only) / fallback
                    .put("engine", if (rank < 0) "none" else candidatesEngine)
                    .put("app", currentInputEditorInfo?.packageName ?: "")
                    .put("reading", consumedReading)
                    .put("surface", c.surface)
                    .put("kind", c.kind.name)
                    .put("rank", rank)
                    .put("top", top ?: JSONObject.NULL)
                    .put("full_reading", full)
                    .put("context_before", before)
                c.segments?.let { segs -> ev.put("segments", org.json.JSONArray(segs.map { it.second })) }
                c.fullReading?.let { ev.put("predicted_reading", it) }
                io.execute { log.append(ev) }
            }
        }

        reading.delete(0, c.consumed)
        selected = -1
        segLen = 0
        if (reading.isNotEmpty()) captureContext()
        keyboard.composing = reading.isNotEmpty()
        if (reading.isEmpty()) {
            val added = finishCompound()
            val lc = lastCommit
            if (lc != null && !privateField) { lc.dictWord = added; learnPairs(lc.undo) } else compWords.clear()
        }
        if (reading.isNotEmpty()) refresh() else { candidates = emptyList(); seq++; renderCandidates() }
    }

    // ---- candidate bar ----

    private fun renderCandidates() {
        // the bar only needs the first few; the pulled-up panel shows all of them.
        // TextViews are reused between keystrokes instead of being recreated.
        val shown = candidates.take(40)
        while (barViews.size < shown.size) {
            val tv = candidateView(barViews.size, Candidate("", 0, Candidate.Kind.WORD), false)
            barViews.add(tv)
            candRow.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        barViews.forEachIndexed { i, tv ->
            val c = shown.getOrNull(i)
            if (c == null) { tv.visibility = View.GONE; return@forEachIndexed }
            tv.visibility = View.VISIBLE
            tv.text = c.surface
            tv.setTextColor(if (c.kind == Candidate.Kind.ENGLISH) theme.englishText else fgColor())
            tv.setBackgroundColor(if (i == selected) theme.selectedBg else Color.TRANSPARENT)
        }
        expandButton.visibility = if (candidates.isEmpty()) View.INVISIBLE else View.VISIBLE
        updateBarButtons()
        if (clipOpen && candidates.isNotEmpty()) showClips(false)
        if (candidates.isEmpty()) expand(false) else if (expanded) renderPanel()
        if (selected >= 0) barViews.getOrNull(selected)?.let { v -> candScroll.post { candScroll.smoothScrollTo(maxOf(0, v.left - (40 * dp).toInt()), 0) } }
        else if (candidates !== lastRendered) candScroll.scrollTo(0, 0)
        lastRendered = candidates
    }
}
