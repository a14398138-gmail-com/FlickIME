# 改善ループ: 「読みを最後まで打ったら、正解が候補バーにスクロールなしで出る」（FlickIME）

あなたはこのリポジトリ（Android 用の自作日本語フリック IME「FlickIME」）で、変換候補の並びを改善する。
使うのは作者 1 人だけ（POCO F7）。作者の希望は **「まだ打っていない部分を補完してもらうより、最後まで打ったときに正解が候補バーの見えている範囲にあって、スクロールしなくて済む」** こと。
この文書の手順どおりに、仮説 → 変更 → 測定 → 採用/戻す を繰り返す。**作業はすべてローカル。push はしない。**

## 0. 準備（最初に 1 回）

```
git clone https://github.com/a14398138-gmail-com/FlickIME.git   # まだなら
cd FlickIME
git checkout -b loop/$(date +%Y%m%d)
tools/loop/setup.sh
```

- 必要なもの: JDK 17 以上、python3、git、curl、unzip。zenz（スマホと同じ AI 変換）を使うには cmake と C++ コンパイラも要る（setup.sh が自動でビルドする。無ければ n-gram だけで測る。**途中で条件を変えないこと**）。
- 作者の入力ログ `eval/log.jsonl` があれば setup.sh が `eval/events.tsv` を作る。無ければ log セットは使えない。その場合は作者にログを置くよう頼むメッセージを出して止まる。
- `eval/`、`out/`、`build/` は `.gitignore` 済み。**`eval/` とログ明細（`out/*.log_*.txt` など）の中身は作者の私的な文章。コミット、コード、コメント、最終出力に書き写さない。**
- `git rev-parse HEAD` を BASE として控える。

## 1. 物差し

```
tools/loop/eval_all.sh dev base      # 全部（zenz ありで数分）
FAST=1 tools/loop/eval_all.sh dev x  # 主指標だけ（試行中の素早い確認用）
```

出力される行:

- `CONVEVAL set=log split=dev ...` — **主指標**。作者のログを時系列に再生する（各確定の後に学習するので、学習込みで測る）。読みを全部打った時点で、確定まで何アクションかかるかを見る。
  - `one`（**最大化する**）: 全確定のうち、打ち終えてから1アクションで確定できた割合。答えが読みそのもの（ひらがなのまま）なら Enter の1回で済むので、候補の並びに関係なく1アクションと数える。それ以外は、正解が候補バーにスクロールなしで見えていれば1アクション。バーの幅は 380dp と仮定している。各候補の幅は、左右の余白 24dp に、全角 1 文字 17dp・半角 9.5dp を足したもの。先頭の候補は常に見えている扱い。2 番目以降は、48dp 以上が画面内にあれば見えている扱い。
  - `taps`（**最小化する**）: 確定までのアクション数の平均（全確定）。Enter・見えている候補のタップは 1、スクロールが必要なら 2、候補に無ければ 3＋文字数。
  - `| conv` 以降は、答えが読みと違うもの（変換）だけで測った `vis`（バーに見えている割合）/ `top1` / `mrr` / `miss`（候補に無い割合）/ `barN`（バーに見えている候補数）。ひらがなのままの答えは候補の順位に関係なく Enter で済むので、ひらがなを何位に置いても主指標は変わらない。
- `CONVEVAL set=ajimee` — 公開ベンチ（AJIMEE-Bench、200 文）。**回帰チェック用**。
- `CONVEVAL set=casual` — 口語 50 文（`tools/eval/casual.tsv`）。**回帰チェック用**。
- `PREDEVAL split=dev ... ksr=...` — 打ちかけの予測変換の指標（キー入力の節約率）。今回の主目的ではない。**大きく壊さないことだけを確認する**。

明細は `out/<tag>.log_dev.txt` などに出る。各行の形式は `id 読み 正解 rank=順位 bar=見えている数 候補上位8個`（ひらがなのままの答えは `enter`）。`rank` が `-`（候補に無い）か、`bar` より大きい（スクロールが必要）の行が改善の対象。

ベースライン（v0.86、zenz あり、作者環境の 2 コア PC・古いログで測定。数値はログで変わるので、あなたの環境で測り直して、その値を基準にすること）:

```
CONVEVAL set=log split=dev all=383 one=97.4% taps=1.16 | conv n=243 vis=95.9% top1=88.1% miss=2.5%
CONVEVAL set=ajimee all=200 one=89.0% | conv top1=87.9% miss=7.1%
CONVEVAL set=casual all=50 one=98.0% | conv top1=93.5% miss=2.2%
PREDEVAL split=dev ksr=7.2%
```

ベースラインの明細で既に分かっている傾向（ヒント。鵜呑みにせず自分で確かめる）:

- 読みを打ち終えているのに、その先を補う**予測候補**（「〜？と」「〜？って」「〜の」「〜は」のように読みより長いもの）が、完全一致の変換候補より前に並んで、正解をバーの外へ押し出している。
- 1〜2 文字の読み（か、さい、いる など）で、予測候補（「感じ」「書い」「カーソル」など）がバーを埋めて、同じ読みの単漢字・単語が後ろに回っている。
- 何にも変換されない読み（ありが、よろし など＝打ちかけの語）では、予測の上位 2 つを読みのすぐ後ろに出すのが仕様（0.77）。これを崩すと作者が「予測が出ない」と感じる。
- ひらがなのままの答えは Enter で確定できるので、候補の先頭は本物の変換に譲っている（0.86）。

## 2. 変更してよい場所

- `app/src/main/java/dev/flickime/engine/Converter.kt` — `candidates()`（並べ方、予測をどこに何個入れるか、`predictionsFirst`、`addAltSentences` / `addDictPredictions` の条件）、`UserHistory`（学習の重み、上限）、`segmentCandidates`、zenz 関係の重み（`zenzWeight` / `zenzGenPenalty`）など。
- 必要なら他の `engine/*.kt` も。
- アイデアの例（採用するかは測って決める）:
  - 読みと完全に一致する変換（consumed == 読みの長さ、かつ読みより長く補わないもの）を予測より前に置く。予測は、読みを打ち終えた時点では後ろに回すか、数を減らす。
  - 予測候補を入れるのは読みが短い・途中のときだけにする（ksr への影響を必ず見る）。
  - 同じ表記の重複や、ほぼ同じ候補（〜？と / 〜？って）を後ろに回す。
  - 学習済みの語の順位の上げ方を見直す。
  - バーの幅を食う長い候補の扱い。
- **変更しない**: 評価器（`tools/predeval/*`、`tools/loop/*`、`tools/build_predict_eval.py`、`tools/ajimee_tsv.py`、`tools/eval/casual.tsv`）、`app/src/main/assets/` の中身（辞書・言語モデル・zenz モデル）、Android の UI コード（`FlickIMEService.kt` など）。

## 3. ループ（これを繰り返す）

1. 明細（最初は `out/base.log_dev.txt`）から、スクロールが必要な行と候補に無い行を集めて、原因ごとに分ける。
2. 仮説を 1 つ選んで、最小の変更を書く。
3. `FAST=1 tools/loop/eval_all.sh dev <短いタグ>` で主指標を見る。
4. 良さそうなら `tools/loop/eval_all.sh dev <タグ>` で全部測る。
5. 採用条件（すべて満たすこと）:
   - log `one` が上がる（または同じで `taps` が下がる）。
   - ajimee と casual の `top1` が、どちらも 1.0 ポイント以上は下がらない。
   - `PREDEVAL ksr` が 1.0 ポイント以上は下がらない。`conv1` も 1.0 ポイント以上は下がらない。
   - `ms/item` が大きく増えない（目安は 1.5 倍以内）。
6. 採用なら `git commit -am "<英語で短く: 何を変えたか>"`。不採用なら `git checkout -- app/` で戻す。
7. `out/NOTES.md` に 1 行追記する: `仮説 | 変更 | log one/taps/conv top1/miss | ajimee top1 | casual top1 | ksr | 採用/不採用`。
8. 1 に戻る。最低 10 回は試行する。採用が 3 回続けて出なくなったら、別の種類の仮説に切り替える。

## 4. 守ること

- **test split で調整しない。** `tools/loop/eval_all.sh test final` は最後に 1 回だけ実行する。
- **評価データの単語や文をハードコードしない。** 辞書・リスト・特例・テストへの追加も含めて禁止。dev は約 240 件と小さいので、1〜2 件だけ直す変更より、効く理由を説明できる一般的な変更を選ぶ。
- 既存のユニットテストを壊さない。Android SDK があれば `./gradlew -q testDebugUnitTest` で確認する。無ければ、`tools/predeval/build.sh` が通ることを最低ラインにして、Gradle のテストを回せなかったことを最後に書く。
- push しない。`eval/` と `out/` の中身はコミットに入れない（`git status` で確認する）。
- 打鍵ごとの処理を重くしない（zenz の呼び出しを増やさない）。

## 5. 終わり方

1. `tools/loop/eval_all.sh test final` を 1 回だけ実行する。
2. `git format-patch BASE..HEAD --stdout > out/loop.patch` を実行する。`grep '^diff --git' out/loop.patch` で、`eval/` と `out/` のファイルが含まれていないことを確認する。
3. 次を出力して終える:
   0. `out/loop.patch` のパスと、変更ファイルの一覧
   1. `out/NOTES.md` の表（試したことを全部）
   2. 採用した変更の要約とコミットハッシュ
   3. ベースラインと最終の dev の行（`CONVEVAL` / `PREDEVAL` をそのまま貼る）と、test の行
   4. 次にやるとよいことの候補
