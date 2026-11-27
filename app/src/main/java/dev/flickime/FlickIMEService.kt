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

    /** A zenz model the user imported (e.g. one fine-tuned on their own posts); used instead of the bundled one. */
    fun customZenz(c: Context) = java.io.File(c.filesDir, "zenz-custom.gguf")

    /** Swaps the loaded model after an import / reset (slow; call off the main thread). */
    @Synchronized fun reloadZenz(c: Context) {
        val old = converter?.zenz
        converter?.zenz = null
        old?.close()
        zenzTried = false
        loadZenz(c)
    }

    /** Loads zenz into the converter (slow; call off the main thread). */
    @Synchronized fun loadZenz(c: Context) {
        if (zenzTried) return
        zenzTried = true
        runCatching {
            System.loadLibrary("zenzjni")
            val custom = customZenz(c)
            if (custom.length() > 0) {
                dev.flickime.engine.Zenz.v2Prompt = false // imported models are v3 fine-tunes
                get(c).zenzGenerate = true
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                dev.flickime.engine.Zenz.load(custom.path, threads)?.let { z -> get(c).zenz = z; return }
                android.util.Log.e("FlickIME", "imported zenz did not load; using the bundled one")
            }
            // llama.cpp needs a real file: copy the model out of the APK once.
            // zenz-medium.gguf (zenz-v2.5-medium, v2 prompt) when the build has it, else zenz.gguf (v3.1-small)
            val medium = runCatching { c.assets.openFd("zenz-medium.gguf").use { true } }.getOrDefault(false)
            val asset = if (medium) "zenz-medium.gguf" else "zenz.gguf"
            val f = java.io.File(c.filesDir, if (medium) "zenz-v2.5-medium.gguf" else "zenz-small.gguf")
            // drop copies of other / older models
            for (old in listOf("zenz-v3.1-small.gguf", if (medium) "zenz-small.gguf" else "zenz-v2.5-medium.gguf")) java.io.File(c.filesDir, old).delete()
            val size = c.assets.openFd(asset).use { it.length }
            if (!f.exists() || f.length() != size) {
                val tmp = java.io.File(c.filesDir, "zenz.tmp")
                c.assets.open(asset).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                tmp.renameTo(f)
            }
            dev.flickime.engine.Zenz.v2Prompt = medium
            // zenz's own greedy conversion joins the candidates (AJIMEE-Bench: 80.5% -> 87.5% with small v3.2)
            get(c).zenzGenerate = true
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            dev.flickime.engine.Zenz.load(f.path, threads)?.let { z -> get(c).zenz = z }
        }.onFailure { android.util.Log.e("FlickIME", "zenz unavailable", it) }
    }
    @Volatile private var glide: GlideDecoder? = null

    fun glide(c: Context): GlideDecoder = glide ?: synchronized(this) {
        glide ?: GlideDecoder(c.assets.open("en_words.txt").bufferedReader().readLines().filter { it.isNotBlank() }).also { g ->
            UserWordsStore.load(c).forEach { g.addUserWord(it) }
            g.load(GlideStore.load(c))
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
        // typed words count too (the word before it, from the text in front of what was typed)
        val beforeAll = currentInputConnection?.getTextBeforeCursor(w.length + 41, 0)?.toString().orEmpty()
        val cut = beforeAll.lastIndexOf(w)
        learnGlide(w, previousWord(if (cut >= 0) beforeAll.substring(0, cut) else ""), null, null)
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
            suggestTyping()
        } else {
            val prev = typedWord.toString()
            finishTypedWord()
            if (text == " " && prev.isNotEmpty()) suggestNext(prev) else clearSuggestions()
        }
    }

    /** Candidates in the bar are completions of the word being typed (English), not conversions. */
    private var typeSuggest = false
    private var suggestSeq = 0
    /** Cursor jumps until then come from accepting a suggestion, not from the user moving the cursor. */
    private var suggestEditUntil = 0L

    /** Completions of the typed word (posted back in order; stale results are dropped). */
    private fun suggestTyping() {
        val w = typedWord.toString()
        if (!typedWordValid || w.isEmpty() || privateField) { clearSuggestions(); return }
        val prev = previousWord(currentInputConnection?.getTextBeforeCursor(w.length + 40, 0)?.toString().orEmpty().dropLast(w.length))
        val my = ++suggestSeq
        val caps = w.first().isUpperCase()
        val ctx = applicationContext
        worker.execute {
            val list = runCatching { Engine.glide(ctx).complete(w, prev) }.getOrDefault(emptyList())
                .map { if (caps) it.replaceFirstChar { c -> c.uppercase() } else it }
            main.post {
                if (my != suggestSeq || glideMode || mode != Mode.ALPHA || typedWord.toString() != w) return@post
                showSuggestions(list)
            }
        }
    }

    /** After a word and a space: what this user usually writes next. */
    private fun suggestNext(prev: String) {
        if (privateField) return
        val my = ++suggestSeq
        val ctx = applicationContext
        worker.execute {
            val list = runCatching { Engine.glide(ctx).nextWords(prev) }.getOrDefault(emptyList())
            main.post { if (my == suggestSeq && !glideMode && mode == Mode.ALPHA && typedWord.isEmpty()) showSuggestions(list) }
        }
    }

    private fun showSuggestions(list: List<String>) {
        if (list.isEmpty()) { clearSuggestions(); return }
        typeSuggest = true
        candidates = list.map { Candidate(it, 0, Candidate.Kind.ENGLISH) }
        selected = -1
        renderCandidates()
    }

    private fun clearSuggestions() {
        suggestSeq++
        if (!typeSuggest) return
        typeSuggest = false
        if (reading.isEmpty() && !glideMode) { candidates = emptyList(); selected = -1; renderCandidates() }
    }

    /** Tapped a suggestion: it replaces the word being typed, followed by a space (like Gboard). */
    private fun acceptSuggestion(i: Int) {
        val ic = currentInputConnection ?: return
        val w = candidates.getOrNull(i)?.surface ?: return
        val typed = typedWord.toString()
        val prevForLearn = previousWord(ic.getTextBeforeCursor(typed.length + 40, 0)?.toString().orEmpty().dropLast(typed.length))
        suggestEditUntil = android.os.SystemClock.uptimeMillis() + 400
        ic.beginBatchEdit()
        if (typed.isNotEmpty() && ic.getTextBeforeCursor(typed.length, 0)?.toString() == typed) ic.deleteSurroundingText(typed.length, 0)
        ic.commitText("$w ", 1)
        ic.endBatchEdit()
        typedWord.setLength(0); typedWordValid = false
        typeSuggest = false
        learnGlide(w, prevForLearn, null, null)
        suggestNext(w)
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

    /**
     * 👎 while converting: no candidate is right. The keyboard then records what the user ends up writing in
     * its place, however they get there (a lower candidate, re-splitting, single kanji one by one, kana,
     * pasting…), until they press ✓. That text is logged as the answer and added to the user dictionary
     * under the reading, so it is offered next time.
     */
    private class BadReport(val reading: String, val context: String, val shown: List<String>, val engine: String, val start: Int) {
        val committed = StringBuilder()   // fallback when the editor cannot show its text
    }
    private var badReport: BadReport? = null

    /** Absolute position of the start of the composition (or of the cursor when idle). */
    private fun compositionStart(): Int {
        if (composeStart >= 0) return composeStart
        val et = currentInputConnection?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) ?: return -1
        if (et.selectionStart < 0) return -1
        return et.startOffset + et.selectionStart - (if (reading.isNotEmpty()) shownCaret else 0)
    }

    private fun onBadButton() {
        if (badReport != null) { finishBadReport(confirmed = true); return }
        if (reading.isEmpty() || candidates.isEmpty()) return
        if (!Prefs.loggingEnabled(this)) {
            notice("設定でログをオンにすると記録できます"); return
        }
        badReport = BadReport(reading.toString(), compositionContext, candidates.take(8).map { it.surface }, candidatesEngine, compositionStart())
        updateBarButtons()
        notice("正しい形で入力して、最後に✓を押してください")
    }

    /** ✓ (or leaving the field): log what is now written where the composition was. */
    private fun finishBadReport(confirmed: Boolean) {
        val br = badReport ?: return
        badReport = null
        updateBarButtons()
        if (privateField) return
        var answer: String? = null
        if (confirmed) {
            if (reading.isNotEmpty()) { if (selected >= 0) commitCandidate(selected) else commitKana() }
            val ic = currentInputConnection
            val et = ic?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            if (et != null && br.start >= 0 && et.selectionStart >= 0) {
                val end = et.startOffset + et.selectionStart
                val text = et.text?.toString()
                val a = br.start - et.startOffset; val b = end - et.startOffset
                if (text != null && a in 0..b && b <= text.length && b - a in 1..64) answer = text.substring(a, b)
            }
            if (answer == null && br.committed.isNotEmpty()) answer = br.committed.toString()
        }
        val ev = JSONObject()
            .put("type", "bad")
            .put("ts", System.currentTimeMillis())
            .put("version", BuildConfig.VERSION_NAME)
            .put("engine", br.engine)
            .put("reading", br.reading)
            .put("context_before", br.context)
            .put("shown", org.json.JSONArray(br.shown))
            .put("answer", answer ?: JSONObject.NULL)
            .put("app", currentInputEditorInfo?.packageName ?: "")
        io.execute { log.append(ev) }
        val conv = Engine.converter
        val a = answer?.trim()
        if (conv != null && a != null && a.isNotEmpty() && a != br.reading && br.reading.length <= 16 && a.length <= 32 && '\n' !in a) {
            if (conv.userDict.add(br.reading, a)) {
                val text = conv.userDict.serialize()
                io.execute { UserDictStore.save(applicationContext, text) }
            }
            notice("「${br.reading}」→「$a」を記録して辞書に登録しました")
        } else if (confirmed) notice(if (a.isNullOrEmpty()) "入力が見つからなかったので、候補だけ記録しました" else "記録しました")
    }
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
        // long copies are kept too (up to ~100k characters; the panel shows only the start)
        if (text.isEmpty() || text.length > 100_000) return
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
        // 👎 only while converting, and only with the log on (it is a report for later tuning)
        if (::keyboard.isInitialized) {
            // the ⚙ key: 👎 while converting (log on), ✓ while a report is open
            keyboard.reportKey = Prefs.loggingEnabled(this)
            keyboard.reporting = badReport != null
        }
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
                text = if (item.text.length > 300) item.text.take(300) + "…（${item.text.length}文字）" else item.text
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
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
        // decode the background picture before the first show needs it
        worker.execute { runCatching { if (Prefs.glassTheme(applicationContext)) BackgroundImage.veiled(applicationContext, night, Prefs.glassParams(applicationContext).veil) } }
        if (Prefs.zenzEnabled(this)) zenzWorker.execute { Engine.loadZenz(applicationContext) }
        clipboard.addPrimaryClipChangedListener(clipListener)
        io.execute { val loaded = ClipStore.load(applicationContext); main.post { clips.addAll(0, loaded.filter { l -> clips.none { it.text == l.text } }) } }
        log = CommitLog(this)
        worker.execute { runCatching { Engine.get(applicationContext); dev.flickime.engine.JaNumbers.small.size; Engine.glide(applicationContext) } }
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
        inputRoot = root
        val bgImage = if (theme.glass) BackgroundImage.veiled(this, night, theme.params.veil) else null
        if (bgImage != null) {
            root.background = opaque(GlassBackdrop(night, translucent = false, image = bgImage, params = theme.params, preVeiled = true))
        } else if (theme.glass) {
            // opaque backdrop: the app behind the keyboard must not show through
            root.background = opaque(GlassBackdrop(night, translucent = false, params = theme.params))
        } else {
            root.setBackgroundColor(theme.background)
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        candRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        candScroll = SwipeHorizontalScrollView(this).apply {
            suppress = { dragging != null }
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
                if (t.isNullOrEmpty()) notice("設定画面の「定型文」で登録してください")
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
        // messages and confirmations shown in place of the candidates (a toast from the keyboard may not show at all)
        noticeText = TextView(this).apply {
            gravity = Gravity.CENTER_VERTICAL; isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(fgColor())
            setPadding((12 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        }
        noticeAction = TextView(this).apply {
            gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(fgColor())
            setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
            setBackgroundColor(theme.selectedBg)
        }
        val noticeClose = TextView(this).apply {
            text = "×"; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTextColor(fgColor())
            setOnClickListener { notice(null) }
        }
        noticeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            addView(noticeText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(noticeAction, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                setMargins(0, (6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt())
            })
            addView(noticeClose, LinearLayout.LayoutParams((40 * dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT))
        }
        bar.addView(noticeRow, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
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
            suppress = { dragging != null }
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
        bodyFrame = body
        // Gboard-style removal: long-press a candidate, drag it onto the bin
        trashView = TextView(this).apply {
            text = "🗑\nここで離すと削除"; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(fgColor())
            visibility = View.GONE; elevation = 8 * dp
        }
        body.addView(trashView, FrameLayout.LayoutParams((150 * dp).toInt(), (80 * dp).toInt(), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = (14 * dp).toInt() })
        dragChip = TextView(this).apply {
            gravity = Gravity.CENTER; isSingleLine = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); setTextColor(fgColor())
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 10 * dp; setColor(theme.selectedBg) }
            visibility = View.GONE; elevation = 12 * dp
        }
        body.addView(dragChip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        // Android 15 draws the IME edge-to-edge: keep keys above the navigation bar
        // (the strip with the system ∨ / 🌐 buttons), the way Gboard does.
        root.setOnApplyWindowInsetsListener { _, insets -> applyNavInset(insets); insets }
        // the first frame already sits above the navigation bar: use the height seen last time instead of
        // waiting for the insets (they come a few frames after the window shows)
        if (!floating) {
            val known = navInset.takeIf { it > 0 } ?: Prefs.navInset(this, landscape).takeIf { it >= 0 } ?: systemNavBarHeight()
            if (known > 0) { navInset = known; root.setPadding(0, 0, 0, known) }
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
        // bar views are reused between keystrokes: look the candidate up by position at press time
        setOnLongClickListener { v -> candidates.getOrNull(i)?.let { startCandidateDrag(it, v) }; true }
        setOnTouchListener { _, e -> dragging != null && onCandidateDrag(e) }
    }

    // ---- drag a candidate to the bin ----
    private var bodyFrame: FrameLayout? = null
    private lateinit var trashView: TextView
    private lateinit var dragChip: TextView
    private var dragging: Candidate? = null
    private var overTrash = false

    private fun trashBg(hot: Boolean) = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = 16 * dp
        setColor(if (hot) Color.argb(230, 220, 60, 60) else Color.argb(200, Color.red(theme.selectedBg), Color.green(theme.selectedBg), Color.blue(theme.selectedBg)))
        setStroke((2 * dp).toInt(), if (hot) Color.WHITE else Color.argb(120, Color.red(theme.text), Color.green(theme.text), Color.blue(theme.text)))
    }

    private fun startCandidateDrag(c: Candidate, v: View) {
        if (glideMode || bodyFrame == null) return
        val conv = Engine.converter
        val r = c.fullReading ?: reading.substring(0, c.consumed.coerceAtMost(reading.length))
        val removable = typeSuggest || c.kind == Candidate.Kind.PREDICTION ||
            conv != null && (conv.history.count(r, c.surface) > 0 || conv.userDict.lookup(r).any { it.surface == c.surface })
        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        if (!removable) { notice("「${c.surface}」は普通の変換候補なので消せません（消せるのは予測と学習した語）"); return }
        v.parent?.requestDisallowInterceptTouchEvent(true)
        dragging = c; overTrash = false
        dragChip.text = c.surface; dragChip.visibility = View.VISIBLE
        trashView.background = trashBg(false); trashView.visibility = View.VISIBLE
        // the chip starts where the candidate is
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        moveChip(loc[0] + v.width / 2f, loc[1] + v.height / 2f)
    }

    private fun moveChip(rawX: Float, rawY: Float) {
        val body = bodyFrame ?: return
        val bl = IntArray(2); body.getLocationOnScreen(bl)
        dragChip.translationX = rawX - bl[0] - dragChip.width / 2f
        dragChip.translationY = rawY - bl[1] - dragChip.height / 2f - 24 * dp // above the finger
        val tl = IntArray(2); trashView.getLocationOnScreen(tl)
        val m = 16 * dp
        val hot = rawX >= tl[0] - m && rawX <= tl[0] + trashView.width + m && rawY >= tl[1] - m && rawY <= tl[1] + trashView.height + m
        if (hot != overTrash) {
            overTrash = hot
            trashView.background = trashBg(hot)
            trashView.animate().scaleX(if (hot) 1.12f else 1f).scaleY(if (hot) 1.12f else 1f).setDuration(90).start()
            if (hot) trashView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun onCandidateDrag(e: MotionEvent): Boolean {
        val c = dragging ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> moveChip(e.rawX, e.rawY)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val drop = e.actionMasked == MotionEvent.ACTION_UP && overTrash
                endCandidateDrag()
                if (drop) removeCandidate(c)
            }
        }
        return true
    }

    private fun endCandidateDrag() {
        dragging = null; overTrash = false
        if (::dragChip.isInitialized) dragChip.visibility = View.GONE
        if (::trashView.isInitialized) { trashView.visibility = View.GONE; trashView.scaleX = 1f; trashView.scaleY = 1f }
    }

    private lateinit var noticeRow: LinearLayout
    private lateinit var noticeText: TextView
    private lateinit var noticeAction: TextView
    private val hideNotice = Runnable { notice(null) }

    /** A message in the candidate bar (null hides it); with [action], a button that runs [onAction]. */
    private fun notice(text: String?, action: String? = null, onAction: (() -> Unit)? = null) {
        if (!::noticeRow.isInitialized) return
        main.removeCallbacks(hideNotice)
        if (text == null) {
            if (noticeRow.visibility != View.VISIBLE) return
            noticeRow.visibility = View.GONE; candScroll.visibility = View.VISIBLE
            updateBarButtons()
            return
        }
        noticeText.text = text
        noticeAction.visibility = if (action != null) View.VISIBLE else View.GONE
        noticeAction.text = action
        noticeAction.setOnClickListener { notice(null); onAction?.invoke() }
        noticeRow.visibility = View.VISIBLE; candScroll.visibility = View.GONE; clipChip.visibility = View.GONE
        main.postDelayed(hideNotice, if (action != null) 6000 else 2500)
    }

    private fun removeCandidate(c: Candidate) {
        if (glideMode) return
        if (typeSuggest) {
            // an English suggestion while typing: not suggested again
            val ctx = applicationContext
            worker.execute {
                val g = runCatching { Engine.glide(ctx) }.getOrNull() ?: return@execute
                g.block(c.surface)
                val text = g.serialize()
                io.execute { GlideStore.save(ctx, text) }
            }
            notice("「${c.surface}」を候補に出さないようにしました")
            showSuggestions(candidates.filter { it !== c }.map { it.surface })
            return
        }
        val conv = Engine.converter ?: return
        val r = c.fullReading ?: reading.substring(0, c.consumed.coerceAtMost(reading.length))
        if (c.kind == Candidate.Kind.PREDICTION) {
            conv.history.forget(r, c.surface)
            conv.history.block(r, c.surface)
            historyDirty = true
            saveHistory()
            notice("「${c.surface}」を予測に出さないようにしました")
            refresh()
            return
        }
        val fromHistory = conv.history.forget(r, c.surface)
        val fromDict = conv.userDict.remove(r, c.surface)
        if (!fromHistory && !fromDict) {
            notice("学習した候補ではありません")
            return
        }
        historyDirty = true
        saveHistory()
        if (fromDict) { val text = conv.userDict.serialize(); io.execute { UserDictStore.save(applicationContext, text) } }
        notice("「${c.surface}」を候補から削除しました")
        refresh()
    }

    private fun renderPanel() {
        flow.removeAllViews()
        candidates.forEachIndexed { i, c -> flow.addView(candidateView(i, c, i == selected)) }
    }

    private var inputRoot: View? = null
    /** Last non-zero navigation bar height: some show-ups report 0 insets for a moment (the keyboard then sat a row too low). */
    private var navInset = 0

    private fun applyNavInset(insets: android.view.WindowInsets?) {
        val v = inputRoot ?: return
        val bottom = when {
            insets == null -> 0
            Build.VERSION.SDK_INT >= 30 -> maxOf(
                insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom,
                insets.getInsets(android.view.WindowInsets.Type.tappableElement()).bottom,
            )
            else -> @Suppress("DEPRECATION") insets.systemWindowInsetBottom
        }
        if (bottom > 0 && bottom != navInset) { navInset = bottom; Prefs.setNavInset(this, landscape, bottom) }
        val pad = if (floating) 0 else if (bottom > 0) bottom else navInset
        if (v.paddingBottom != pad) v.setPadding(0, 0, 0, pad)
    }

    /** The system's navigation bar height, for the very first show before any insets were seen. */
    private fun systemNavBarHeight(): Int {
        @Suppress("DiscouragedApi")
        val id = resources.getIdentifier(if (landscape) "navigation_bar_height_landscape" else "navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // insets can arrive late or not at all for a re-shown window: apply what the window has now, right away
        inputRoot?.let { r -> applyNavInset(r.rootWindowInsets); r.requestApplyInsets() }
        redrawAll()
    }

    /**
     * A keyboard shown again after a long pause could keep showing its old frame (the picture without its
     * veil and the glass keys) until a key press redrew it: redraw everything now and once more on the
     * next frame, when the window has its final size.
     */
    private fun redrawAll() {
        val r = inputRoot ?: return
        fun once() {
            r.background?.invalidateSelf()
            r.invalidate()
            if (::keyboard.isInitialized) keyboard.invalidate()
            if (::candRow.isInitialized) candRow.invalidate()
        }
        once()
        r.requestLayout()
        r.postOnAnimation { once() }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // rebuild the view when the theme was switched in settings
        if (::theme.isInitialized && themeKey != Prefs.themeKey(this)) setInputView(onCreateInputView())
        inputRoot?.let { r -> r.rootWindowInsets?.let { applyNavInset(it) } }
        redrawAll()
        if (!restarting) finishBadReport(confirmed = false)
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
        notice(null); endCandidateDrag()
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
        if (mode != Mode.ALPHA) clearSuggestions()
    }

    /** The last word before the cursor (for the glide decoder's word pairs). */
    private fun previousWord(before: String): String =
        Regex("([A-Za-z']+)[^A-Za-z']*$").find(before.takeLast(40))?.groupValues?.get(1).orEmpty()

    private var glidePath: List<GlideDecoder.P>? = null
    private var glideGeom: Triple<Map<Char, GlideDecoder.P>, Float, Float>? = null
    private var glidePrev = ""

    /** Teach the glide decoder the word the user ended up with (and save, off the main thread). */
    private fun learnGlide(word: String, previous: String, path: List<GlideDecoder.P>?, geom: Triple<Map<Char, GlideDecoder.P>, Float, Float>?) {
        if (privateField || word.length < 2) return
        val ctx = applicationContext
        worker.execute {
            val g = runCatching { Engine.glide(ctx) }.getOrNull() ?: return@execute
            g.learn(word, previous, path, geom?.first, geom?.second ?: 1f, geom?.third ?: 1f)
            val text = g.serialize()
            io.execute { GlideStore.save(ctx, text) }
        }
    }

    private fun endGlide(learn: Boolean = true) {
        if (!glideMode) return
        // the glided word (whichever candidate is in place now) stays: learn it
        if (learn) {
            val word = glideCommitted.removePrefix(glidePrefix)
            val before = currentInputConnection?.getTextBeforeCursor(glideCommitted.length + 2, 0)?.toString().orEmpty()
            if (word.isNotEmpty() && before.trimEnd().endsWith(word)) learnGlide(word, glidePrev, glidePath, glideGeom)
        }
        glidePath = null; glideGeom = null
        glideMode = false; glideCommitted = ""; glidePrefix = ""
        candidates = emptyList(); selected = -1; renderCandidates()
    }

    override fun onGlide(path: List<GlideDecoder.P>, centers: Map<Char, GlideDecoder.P>, keyWidth: Float, keyHeight: Float) {
        typeSuggest = false; suggestSeq++
        finishTypedWord()
        val caps = keyboard.glideCaps
        val prev = previousWord(currentInputConnection?.getTextBeforeCursor(40, 0)?.toString().orEmpty())
        worker.execute {
            val words = runCatching { Engine.glide(applicationContext).decode(path, centers, keyWidth, keyHeight, previous = prev) }.getOrDefault(emptyList())
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
                glidePath = path; glideGeom = Triple(centers, keyWidth, keyHeight); glidePrev = prev
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
        notice(null)
        endGlide()
        finishCorrection()
        if (cursorLeftComposition()) dropComposition()
        if (mode == Mode.ALPHA) trackTyped(text)
        if (mode != Mode.KANA) {
            currentInputConnection?.commitText(text, 1)
            return
        }
        if (selected >= 0) commitCandidate(selected)
        if (compReading != null) resetCompound()
        if (reading.isEmpty()) captureContext()
        // typed where the cursor is inside the composition (the user tapped into it)
        val at = caret
        reading.insert(at, text)
        cur = if (cur >= 0 && at + text.length < reading.length) at + text.length else -1
        refresh()
    }

    override fun onAction(action: Action) {
        if (action != Action.Report) notice(null)
        val ic = currentInputConnection ?: return
        if (glideMode && action == Action.Backspace) {
            // like Gboard: backspace right after a glide removes the whole word,
            // but only if the cursor is still right after it
            val word = glideCommitted.removePrefix(glidePrefix)
            val before = ic.getTextBeforeCursor(glideCommitted.length, 0)?.toString().orEmpty()
            endGlide(learn = false)   // deleted right away: not what the user wanted
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
        // the user moved the cursor away and the editor dropped our composing region without telling us
        if (cursorLeftComposition()) dropComposition()
        if (action == Action.Backspace && typedWord.isNotEmpty()) { typedWord.deleteCharAt(typedWord.length - 1); if (mode == Mode.ALPHA) main.post { suggestTyping() } }
        else if (action == Action.Left || action == Action.Right) { typedWord.setLength(0); typedWordValid = false; clearSuggestions() }
        else if (action != Action.Backspace && action != Action.Shift) { finishTypedWord(); clearSuggestions() }
        else if (action == Action.Backspace && typeSuggest) clearSuggestions()
        when (action) {
            Action.Backspace -> when {
                selected >= 0 -> { selected = -1; showComposing(); renderCandidates() }
                reading.isNotEmpty() -> {
                    val at = caret
                    if (at == 0) {
                        // cursor at the start of the composition: delete what is in front of it, keep the rest unconverted
                        currentInputConnection?.let { ic ->
                            val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
                            if (before.isNotEmpty()) {
                                val w = if (before.length == 2 && Character.isSurrogatePair(before[0], before[1])) 2 else 1
                                ic.deleteSurroundingText(w, 0)
                                if (composeStart >= w) composeStart -= w   // the composition moved left with it
                            }
                        }
                    } else {
                        reading.deleteCharAt(at - 1)
                        if (cur >= 0) cur = at - 1
                        if (reading.isEmpty()) cur = -1
                        resetCompound(); refresh()
                    }
                }
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
            // like Gboard: ◀▶ set where the first segment ends (the cursor inside the composition moves by tapping)
            Action.Left -> if (reading.isEmpty()) sendKey(KeyEvent.KEYCODE_DPAD_LEFT) else moveBoundary(-1)
            Action.Right -> if (reading.isEmpty()) sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) else moveBoundary(+1)
            Action.Mode -> {
                if (reading.isNotEmpty()) { if (selected >= 0) commitCandidate(selected) else commitKana() }
                mode = if (mode == Mode.KANA) Mode.ALPHA else Mode.KANA
                applyMode()
            }
            Action.Dakuten -> if (reading.isNotEmpty()) {
                if (selected >= 0) { selected = -1 }
                val at = caret
                if (at > 0) Layouts.cycleDakuten(reading[at - 1])?.let { reading.setCharAt(at - 1, it); resetCompound(); refresh() }
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
            Action.Report -> onBadButton()
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
        // Some editors (Chrome, …) drop the composing region when the user taps elsewhere and then
        // report candidatesStart < 0, the same as while our own edits are in flight: tell the two
        // apart by checking that the text before the cursor still ends with what we are composing.
        val movedAway = movedOutside || reading.isNotEmpty() && candidatesStart < 0 &&
            (newSelStart != oldSelStart || newSelEnd != oldSelEnd) && cursorLeftComposition()
        if (reading.isNotEmpty() && movedAway) dropComposition()
        else if (reading.isNotEmpty() && candidatesStart >= 0) {
            composeStart = candidatesStart
            // the user tapped inside the unconverted composition: keep composing, move our cursor there
            // Only a real tap: the cursor actually moved, and not as the echo of our own edit. Some editors
            // report the new composing text before the new cursor, which looked like a tap inside it.
            val ownEcho = android.os.SystemClock.uptimeMillis() - lastComposeAt < 250
            if (!ownEcho && newSelStart != oldSelStart && newSelStart == newSelEnd && selected < 0 &&
                candidatesEnd - candidatesStart == reading.length) {
                val p = newSelStart - candidatesStart
                val next = if (p >= reading.length) -1 else p
                if (next != cur) { cur = next; shownCaret = caret; refresh() }
            }
        }
        if (reading.isEmpty() && (newSelStart != oldSelStart || newSelEnd != oldSelEnd) && candidatesStart < 0) {
            // a jump that is not explained by our own typing breaks word tracking
            val typedJump = newSelStart == newSelEnd && newSelStart - oldSelEnd in 0..(if (glideMode) glideCommitted.length else 1)
            val ownEdit = android.os.SystemClock.uptimeMillis() < suggestEditUntil
            if (!typedJump && !ownEdit) { typedWord.setLength(0); typedWordValid = false; endGlide(); clearSuggestions() }
        }
    }

    /** What [showComposing] last put in the editor, and where the cursor is inside it. */
    private var shownComposing = ""
    private var shownCaret = 0
    private var lastComposeAt = 0L

    /** Cursor inside the (unconverted) composition, or -1 = at its end. */
    private var cur = -1
    private val caret get() = if (cur in 0 until reading.length) cur else reading.length
    /** Where the composing region starts in the editor (from onUpdateSelection). */
    private var composeStart = -1
    /**
     * The cursor inside the composition is also where the first segment ends (like Gboard):
     * everything before it is converted, the rest stays as it is.
     */
    private val segLen get() = if (cur in 1 until reading.length) cur else 0

    private fun cursorLeftComposition(): Boolean {
        if (reading.isEmpty() || shownComposing.isEmpty()) return false
        val ic = currentInputConnection ?: return false
        if (!ic.getSelectedText(0).isNullOrEmpty()) return true
        val c = shownCaret.coerceIn(0, shownComposing.length)
        val before = ic.getTextBeforeCursor(c, 0)?.toString() ?: return false
        if (before != shownComposing.substring(0, c)) return true
        if (c == shownComposing.length) return false
        val after = ic.getTextAfterCursor(shownComposing.length - c, 0)?.toString() ?: return false
        return after != shownComposing.substring(c)
    }

    /** Leaves the kana where it is and forgets the composition. */
    private fun dropComposition() {
        currentInputConnection?.finishComposingText()
        seq++
        reading.setLength(0); candidates = emptyList(); selected = -1
        shownComposing = ""; cur = -1; composeStart = -1
        resetCompound(); compWords.clear()
        if (::keyboard.isInitialized) keyboard.composing = false
        if (::candRow.isInitialized) renderCandidates()
    }

    private fun sendKey(code: Int) {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // ---- composition ----

    private fun resetComposition() {
        reading.setLength(0); candidates = emptyList(); selected = -1; seq++
        cur = -1; composeStart = -1
        resetCompound()
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
        shownComposing = text.toString()
        lastComposeAt = android.os.SystemClock.uptimeMillis()
        val at = if (selected >= 0) text.length else caret
        if (at < text.length && composeStart < 0) {
            // not reported by the editor yet: work it out from the cursor after placing the text at the end
            ic.setComposingText(text, 1)
            ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.let {
                if (it.selectionStart >= 0) composeStart = it.startOffset + it.selectionStart - text.length
            }
        }
        if (at < text.length && composeStart >= 0) {
            shownCaret = at
            ic.beginBatchEdit()
            ic.setComposingText(text, 1)
            ic.setSelection(composeStart + at, composeStart + at)
            ic.endBatchEdit()
        } else {
            shownCaret = shownComposing.length
            ic.setComposingText(text, 1)
        }
    }

    /** Shrink / extend the first segment, like Gboard's ◀▶ during conversion. */
    private fun moveBoundary(delta: Int) {
        val n = reading.length
        // like Gboard: before converting, the whole reading is one segment and ◀ shrinks it from the end;
        // while converting (space pressed), start from where the converter put the first boundary
        val base = when {
            cur in 0 until n -> cur
            selected >= 0 -> runCatching { Engine.get(applicationContext).firstSegmentLength(reading.toString()) }.getOrDefault(n)
            else -> n
        }
        // the cursor can also go to the very start of the composition (0): ⌫ there deletes the text in
        // front of it (院進学|して → 院進|して) and the reading stays unconverted
        val next = (base + delta).coerceIn(0, n)
        if (next == caret && selected < 0) return
        cur = if (next >= n) -1 else next
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
                if (sl in 1..r.length) conv.segmentCandidates(r, sl, context = ctx) else conv.candidates(r, ctx)
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
        if (typeSuggest) { acceptSuggestion(i); return }
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

        badReport?.committed?.append(c.surface)
        reading.delete(0, c.consumed)
        cur = -1; composeStart = -1
        selected = -1
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
