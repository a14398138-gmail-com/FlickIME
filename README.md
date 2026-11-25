# FlickIME

自分向けに育てる日本語フリック入力IME（Android）。

## 主な機能

- フリック入力（1キー1文字表示、フリック中に文字が引き出される表示）、英語QWERTY（なぞり入力、長押し記号）、半角数字
- かな漢字変換：Mozc OSS辞書 + NEologd/新語の追加語彙、ラティスのN-bestを単語3-gram言語モデルで並べ替え
- 学習：選んだ候補（英語・記号含む）を上位に、飛ばした1位を少し下に、確定した文の単語や単語の並びも学習。確定直後に⌫で消すと学習を取り消し
- 確定ログ（端末内で暗号化、JSONLで書き出し）、ユーザー辞書のインポート（Gboard / Google日本語入力 / MS-IME / TSV）
- 変換の区切り変更（◀▶）、候補の一覧表示、クリップボード履歴、絵文字パネル、フローティング、ガラス風テーマ

## ビルド

```
./gradlew assembleDebug
```

`app/src/main/assets/` の辞書・言語モデルは `tools/` のスクリプトで生成したものです。

| ファイル | 生成 |
| --- | --- |
| dict.bin / conn.bin | `tools/build_extra_dict.py` → `tools/build_dict.py`（Mozc `src/data/dictionary_oss`） |
| en.tsv | `tools/build_english.py`（JMdict） |
| symbols.tsv / emoji_panel.tsv | `tools/build_symbols.py`, `tools/build_emoji.py` |
| en_words.txt | `tools/build_words.py`（wordfreq） |
| lm.bin | `tools/lm_corpus.py` → `tools/build_lm.py`（Wikipedia + Tatoeba、MeCab/IPAdicで分割） |

## データのライセンス

- Mozc 辞書: BSD-3-Clause（IPAdic 由来部分は IPAdic ライセンス）
- mecab-ipadic-NEologd: Apache-2.0
- JMdict: CC BY-SA 4.0（EDRDG）
- wordfreq: CC BY-SA 4.0
- 言語モデル: Wikipedia 日本語版（CC BY-SA 4.0）、Tatoeba（CC BY 2.0 FR）から作成
- Unicode emoji data: Unicode License
