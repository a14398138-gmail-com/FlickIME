#!/usr/bin/env python3
"""Pick colloquial lines from zenz-v2.5-dataset's llm-jp-corpus-v3 (web) part for the language model.

usage: colloq_filter.py JSONL(.part ok) OUT.txt
Keeps outputs that look like spoken / casual Japanese (sentence-final particles, casual forms, ！？, 笑),
dropping list-like, URL, ad and very short or long lines.
"""
import sys, json, re
JA = re.compile(r'[ぁ-ゖァ-ヶ一-鿿]')
CASUAL = re.compile(r'(けど|けれど|よね|じゃん|じゃない|でしょ|だよ|だね|だな|かな|かも|よな|わよ|のよ|ちゃう|ちゃった|ちゃって|っちゃ|っす|やばい|めっちゃ|すっごく|なぁ|ねぇ|なんか|とか|みたい|っぽい|んだ|んです|んじゃ|ですよ|ますよ|ましょう|ませんか|ね[。！？!?]|よ[。！？!?]|な[。！？!?]|[！？!?]|笑)')
BAD = re.compile(r'(https?:|www\.|＠|@|■|●|◆|★|【|】|\d{3,}-\d|円（税|送料|価格|会員登録|ログイン|クリック)')
src, out = sys.argv[1], sys.argv[2]
n = k = 0
with open(src, encoding='utf-8', errors='ignore') as f, open(out, 'w', encoding='utf-8') as o:
    for line in f:
        n += 1
        try: s = json.loads(line)['output']
        except Exception: continue
        s = s.strip().replace(' ', '').replace('　', '')
        if not (8 <= len(s) <= 100) or BAD.search(s): continue
        if len(JA.findall(s)) < len(s) * 0.7: continue
        if not CASUAL.search(s): continue
        o.write(s + '\n'); k += 1
print('lines', n, 'kept', k)
