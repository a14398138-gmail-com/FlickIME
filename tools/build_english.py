#!/usr/bin/env python3
"""Build kana-reading -> English loanword candidates (en.tsv) from JMdict.

Only katakana loanwords whose English gloss *sounds like* the katakana are kept
(モーニング -> morning), not translations (ねこ -> cat).
"""
import sqlite3, sys, re
from difflib import SequenceMatcher
db, out = sys.argv[1], sys.argv[2]
c = sqlite3.connect(db)

def hira(s):
    return ''.join(chr(ord(ch) - 0x60) if 'ァ' <= ch <= 'ヶ' else ch for ch in s)

# rough Hepburn for katakana, enough for a consonant skeleton
ROW = {
 'カ':'k','キ':'k','ク':'k','ケ':'k','コ':'k','ガ':'g','ギ':'g','グ':'g','ゲ':'g','ゴ':'g',
 'サ':'s','シ':'s','ス':'s','セ':'s','ソ':'s','ザ':'z','ジ':'j','ズ':'z','ゼ':'z','ゾ':'z',
 'タ':'t','チ':'ch','ツ':'ts','テ':'t','ト':'t','ダ':'d','ヂ':'j','ヅ':'z','デ':'d','ド':'d',
 'ナ':'n','ニ':'n','ヌ':'n','ネ':'n','ノ':'n','ハ':'h','ヒ':'h','フ':'f','ヘ':'h','ホ':'h',
 'バ':'b','ビ':'b','ブ':'b','ベ':'b','ボ':'b','パ':'p','ピ':'p','プ':'p','ペ':'p','ポ':'p',
 'マ':'m','ミ':'m','ム':'m','メ':'m','モ':'m','ヤ':'y','ユ':'y','ヨ':'y',
 'ラ':'r','リ':'r','ル':'r','レ':'r','ロ':'r','ワ':'w','ヲ':'','ン':'n','ヴ':'b',
}
def kata_skel(k):
    return ''.join(ROW.get(ch, '') for ch in k)

def en_skel(w):
    w = w.lower()
    for a, b in [('ph','f'),('th','s'),('ck','k'),('qu','kw'),('x','ks'),('wh','w'),('sh','s'),('tch','ch')]:
        w = w.replace(a, b)
    w = re.sub(r'c(?=[eiy])', 's', w).replace('c', 'k').replace('q', 'k').replace('v', 'b').replace('l', 'r')
    w = re.sub(r'[aeiou\s\-\']', '', w)
    w = re.sub(r'(.)\1+', r'\1', w)       # double consonants
    return w

def norm(s):
    s = re.sub(r'(.)\1+', r'\1', s)
    return s.replace('r', '').replace('w', '').replace('y', '').replace('h', '')

def sounds_like(kata, word):
    a, b = norm(kata_skel(kata)), norm(en_skel(word))
    if not a or not b:
        return False
    return SequenceMatcher(None, a, b).ratio() >= 0.75

non_eng = set(r[0] for r in c.execute(
    "select s.idseq from Sense s join SenseSource ss on ss.sid=s.ID where ss.lang not in ('eng','') and ss.lang is not null"))
src_words = {}
for idseq, text in c.execute("select s.idseq, ss.text from Sense s join SenseSource ss on ss.sid=s.ID where (ss.lang='eng' or ss.lang is null or ss.lang='') and ss.text is not null and ss.text!=''"):
    src_words.setdefault(idseq, []).append(text)
common = set(r[0] for r in c.execute("select k.idseq from Kana k join KNP p on p.kid=k.ID"))
glosses = {}
for idseq, text in c.execute("select s.idseq, g.text from SenseGloss g join Sense s on s.ID=g.sid where g.lang is null or g.lang='eng' order by g.rowid"):
    glosses.setdefault(idseq, []).append(text)

ok = re.compile(r"^[A-Za-z][A-Za-z'\-]*( [A-Za-z][A-Za-z'\-]*){0,2}$")
out_map = {}
for idseq, text in c.execute("select idseq, text from Kana"):
    if idseq in non_eng or not re.fullmatch(r'[ァ-ヶー・]+', text):
        continue
    kata = text.replace('・', '')
    cands = src_words.get(idseq, []) + glosses.get(idseq, [])
    for g in cands:
        g = re.sub(r'\s*\(.*?\)\s*', ' ', g).strip()
        if not ok.match(g):
            continue
        if not sounds_like(kata, g):
            continue
        g = g if any(ch.isupper() for ch in g[1:]) or g.isupper() else g.lower() if g[0].isupper() and idseq not in src_words else g
        lst = out_map.setdefault(hira(kata), {})
        lst.setdefault(g, 0 if idseq in common else 1)
with open(out, 'w', encoding='utf-8') as f:
    for r in sorted(out_map):
        ws = sorted(out_map[r].items(), key=lambda x: x[1])[:3]
        f.write(r + '\t' + '\t'.join(w for w, _ in ws) + '\n')
print(len(out_map))
