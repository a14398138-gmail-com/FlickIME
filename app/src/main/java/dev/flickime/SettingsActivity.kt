package dev.flickime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.flickime.engine.UserDictionary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var logInfo: TextView
    private lateinit var dictInfo: TextView
    private lateinit var glassSwitch: Switch
    private val log by lazy { CommitLog(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        fun text(s: String, size: Float = 15f) = TextView(this).apply { text = s; textSize = size; setPadding(0, pad / 2, 0, pad / 2) }
        fun button(s: String, onClick: () -> Unit) = Button(this).apply { text = s; isAllCaps = false; setOnClickListener { onClick() } }

        col.addView(text("FlickIME", 24f))
        col.addView(text("Mozc辞書を使うフリック入力IMEです。確定した変換（読み→表記）を端末内に暗号化して記録します。パスワード欄とシークレット入力欄では記録も学習もしません。"))
        status = text("")
        col.addView(status)
        col.addView(button("1. キーボードを有効にする") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
        col.addView(button("2. キーボードを切り替える") { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() })
        col.addView(Switch(this).apply {
            text = "ガラス風デザイン（試験的）"
            isChecked = Prefs.glassTheme(this@SettingsActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setGlassTheme(this@SettingsActivity, v) }
        }.also { glassSwitch = it })
        col.addView(button("ガラスの背景に画像を設定") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
            }, REQ_IMAGE)
        })
        col.addView(button("背景画像を外す") { BackgroundImage.clear(this); toast("背景画像を外しました"); recreate() })

        col.addView(text("ガラスの調整", 20f))
        col.addView(text("100% が初期値です。変更はキーボードを開き直すと反映されます。", 13f))
        val preview = GlassPreview(this).apply { params = Prefs.glassParams(this@SettingsActivity) }
        col.addView(preview)
        val bars = ArrayList<Pair<String, android.widget.SeekBar>>()
        for ((key, name, max) in GlassParams.fields) {
            val caption = text("")
            fun setCaption(v: Int) { caption.text = "$name: $v%" }
            val bar = android.widget.SeekBar(this).apply {
                this.max = max
                progress = preview.params.get(key)
                setCaption(progress)
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar, v: Int, fromUser: Boolean) {
                        setCaption(v)
                        preview.params = preview.params.with(key, v)
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar) { Prefs.setGlassParams(this@SettingsActivity, preview.params) }
                })
            }
            bars.add(key to bar)
            col.addView(caption); col.addView(bar)
        }
        col.addView(button("ガラスの調整を初期値に戻す") {
            Prefs.setGlassParams(this, GlassParams())
            preview.params = GlassParams()
            bars.forEach { (k, b) -> b.progress = GlassParams().get(k) }
        })
        // flick sensitivity: the slider runs from "low" (40dp) to "high" (6dp)
        val flickCaption = text("")
        fun flickLabel(dpv: Int) {
            val level = when { dpv <= 12 -> "高い"; dpv <= 18 -> "やや高い"; dpv <= 26 -> "標準"; dpv <= 32 -> "やや低い"; else -> "低い" }
            flickCaption.text = "フリック感度: $level（${dpv}dp 動かすとフリック）"
        }
        flickLabel(Prefs.flickThresholdDp(this))
        col.addView(flickCaption)
        col.addView(android.widget.SeekBar(this).apply {
            max = 34
            progress = 40 - Prefs.flickThresholdDp(this@SettingsActivity)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, v: Int, fromUser: Boolean) {
                    flickLabel(40 - v)
                    Prefs.setFlickThresholdDp(this@SettingsActivity, 40 - v)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
            })
        })
        col.addView(EditText(this).apply { hint = "ここで試し打ち" })

        col.addView(text("変換", 20f))
        col.addView(Switch(this).apply {
            text = "zenz（ニューラル変換）で候補を並べ替える"
            isChecked = Prefs.zenzEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setZenzEnabled(this@SettingsActivity, v) }
        })
        col.addView(text("入力が少し止まったときに、文の候補をzenzで並べ替えます。オフにすると電池とメモリを節約できます（キーボードの再起動後に反映）。", 13f))

        col.addView(text("ユーザー辞書", 20f))
        col.addView(text("候補に出なかった語を区切って確定（例: 思想史＋学）すると、読み全体の単語として自動で登録されます。", 13f))
        col.addView(text("Gboardの書き出し（zip / dictionary.txt）、Google日本語入力・Mozcの書き出し、MS-IMEのtxt、「読み<TAB>単語」のテキストを読み込めます。", 13f))
        dictInfo = text("")
        col.addView(dictInfo)
        // manual registration
        val readingIn = EditText(this).apply { hint = "読み（ひらがな）"; isSingleLine = true }
        val wordIn = EditText(this).apply { hint = "単語"; isSingleLine = true }
        col.addView(readingIn); col.addView(wordIn)
        val found = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val searchIn = EditText(this).apply { hint = "登録語を検索（読み・単語）"; isSingleLine = true }
        fun showFound() {
            found.removeAllViews()
            for (w in userDict().search(searchIn.text.toString(), 50)) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
                row.addView(text("${w.surface}（${w.reading}）"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(button("削除") {
                    userDict().remove(w.reading, w.surface); saveDict(); showFound(); refresh()
                })
                found.addView(row)
            }
        }
        col.addView(button("辞書に登録") {
            val r = dev.flickime.engine.Kana.toHiragana(readingIn.text.toString().trim())
            val w = wordIn.text.toString().trim()
            when {
                r.isEmpty() || w.isEmpty() -> toast("読みと単語を入力してください")
                !r.all { it in 'ぁ'..'ゖ' || it == 'ー' || it.isLetterOrDigit() } -> toast("読みはひらがなで入力してください")
                userDict().add(r, w) -> { saveDict(); readingIn.setText(""); wordIn.setText(""); toast("「$w」を登録しました"); refresh(); showFound() }
                else -> toast("すでに登録されています")
            }
        })
        col.addView(searchIn)
        searchIn.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) = showFound()
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        })
        col.addView(found)
        showFound()

        col.addView(button("辞書をインポート") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }, REQ_IMPORT)
        })
        col.addView(button("ユーザー辞書を削除") {
            confirm("インポートした単語をすべて削除しますか？") {
                userDict().clear(); UserDictStore.clear(this); refresh()
            }
        })

        col.addView(text("英語モードで手打ちした、なぞり入力の単語リストにない英単語は自動で登録され、次からなぞり入力で出るようになります。", 13f))
        col.addView(button("登録した英単語を削除") {
            confirm("自動登録した英単語をすべて削除しますか？（再起動後に反映）") { UserWordsStore.clear(this); refresh() }
        })

        col.addView(text("定型文", 20f))
        col.addView(text("キーボードの候補バーの📝を押すと、ここで登録したテキストがそのまま入力されます。", 13f))
        val snippetIn = EditText(this).apply { hint = "登録するテキスト（改行も可）"; setText(SnippetStore.load(this@SettingsActivity).firstOrNull().orEmpty()) }
        col.addView(snippetIn)
        col.addView(button("定型文を保存") {
            val t = snippetIn.text.toString()
            SnippetStore.save(this, if (t.isEmpty()) emptyList() else listOf(t))
            toast(if (t.isEmpty()) "定型文を消しました" else "保存しました")
        })

        col.addView(text("クリップボード", 20f))
        col.addView(text("コピーしたテキストは、候補バー左の📋から呼び出せます（最大30件＋固定したもの、端末内に暗号化して保存）。パスワード管理アプリなどが「機密」としてコピーしたものは保存しません。", 13f))
        col.addView(button("クリップボード履歴を削除") {
            confirm("クリップボード履歴をすべて削除しますか？") { ClipStore.clear(this); toast("削除しました") }
        })

        col.addView(text("確定ログ", 20f))
        col.addView(Switch(this).apply {
            text = "確定ログを記録する"
            isChecked = Prefs.loggingEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setLoggingEnabled(this@SettingsActivity, v) }
        })
        logInfo = text("")
        col.addView(logInfo)
        col.addView(button("ログを書き出す（JSONL・復号済み）") {
            val name = "flickime-log-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".jsonl"
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream" /* any other type makes the picker append its own extension (.jsonl.json) */; putExtra(Intent.EXTRA_TITLE, name)
            }, REQ_EXPORT)
        })
        col.addView(button("ログを削除") {
            confirm("確定ログをすべて削除しますか？") { log.clear(); refresh() }
        })
        col.addView(button("学習履歴をリセット") {
            confirm("変換の学習履歴をリセットしますか？") {
                Engine.converter?.history?.clear(); HistoryStore.clear(this); toast("リセットしました")
            }
        })
        col.addView(text("書き出したファイルは暗号化されていません。扱いに注意してください。ログの鍵はこの端末のキーストアにあり、アプリを削除すると暗号化ログは読めなくなります。", 13f))
        col.addView(text("辞書: Mozc dictionary_oss（BSD / IPAdic）、英単語: JMdict（EDRDG, CC BY-SA 4.0）、wordfreq（CC BY-SA 4.0）、言語モデル: Wikipedia（CC BY-SA）・Tatoeba（CC BY 2.0 FR）、zenz-v3.1-small / zenz-v2.5-medium（三輪敬太, CC BY-SA 4.0）、llama.cpp（MIT）", 12f))
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith(packageName) == true
        status.text = "状態: " + (if (enabled) "有効" else "未有効") + " / " + (if (current) "使用中" else "未選択")
        dictInfo.text = "登録語数: ${userDict().size} 語 / 自動登録した英単語: ${UserWordsStore.load(this).size} 語"
        logInfo.text = "記録件数: ${log.count()} 件（${log.sizeBytes() / 1024} KB）"
    }

    /** The IME's live dictionary if the engine is loaded, otherwise a copy read from storage. */
    private fun userDict(): UserDictionary = Engine.converter?.userDict ?: (cachedDict ?: UserDictionary().also {
        it.load(UserDictStore.load(this)); cachedDict = it
    })
    private var cachedDict: UserDictionary? = null

    private fun importDictionary(uri: android.net.Uri) {
        Thread {
            val result = runCatching {
                val bytes = contentResolver.openInputStream(uri)!!.use { UserDictionary.readLimited(it, 64 * 1024 * 1024) }
                val words = UserDictionary.parse(UserDictionary.decode(UserDictionary.unzipIfNeeded(bytes)))
                val dict = userDict()
                var added = 0
                synchronized(dict) { words.forEach { if (dict.add(it.reading, it.surface, it.posId)) added++ } }
                UserDictStore.save(this, dict.serialize())
                words.size to added
            }
            runOnUiThread {
                result.onSuccess { (read, added) ->
                    toast(if (read == 0) "単語が見つかりませんでした" else "$read 語を読み込み、$added 語を追加しました")
                }.onFailure { e -> toast(if (e is dev.flickime.engine.DictionaryTooLargeException) "ファイルが大きすぎます" else "読み込みに失敗しました") }
                refresh()
            }
        }.start()
    }

    private fun saveDict() {
        val text = userDict().serialize()
        Thread { UserDictStore.save(this, text) }.start()
    }

    private fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK") { _, _ -> ok() }.setNegativeButton("キャンセル", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    @Deprecated("simple activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ_IMAGE && resultCode == RESULT_OK && uri != null) {
            Thread {
                val ok = runCatching { BackgroundImage.save(this, contentResolver.openInputStream(uri)!!.use { UserDictionary.readLimited(it, 50 * 1024 * 1024) }) }.getOrDefault(false)
                runOnUiThread {
                    if (ok) { Prefs.setGlassTheme(this, true); toast("背景画像を設定しました"); recreate() }
                    else toast("画像を読み込めませんでした")
                }
            }.start()
            return
        }
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && uri != null) { importDictionary(uri); return }
        if (requestCode == REQ_EXPORT && resultCode == RESULT_OK && uri != null) {
            Thread {
                val n = runCatching { contentResolver.openOutputStream(uri)!!.use { log.exportTo(it) } }.getOrElse { -1 }
                runOnUiThread { toast(if (n >= 0) "$n 件を書き出しました" else "書き出しに失敗しました") }
            }.start()
        }
    }

    companion object {
        private const val REQ_EXPORT = 1
        private const val REQ_IMPORT = 2
        private const val REQ_IMAGE = 3
    }
}
