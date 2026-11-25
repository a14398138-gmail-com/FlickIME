#!/usr/bin/env python3
"""Extra vocabulary in Mozc dictionary format (reading lid rid cost surface).

- a filtered subset of mecab-ipadic-NEologd (English-spelled names, common nouns,
  short product/organisation names), with a cost penalty so it rarely overrides
  ordinary conversions but shows up as candidates;
- a hand-made list of post-2020 / tech words (tools/modern_words.tsv).
"""
import csv, re, sys, glob, os
neologd, modern, mozc_dir, out = sys.argv[1:5]

def hira(s): return ''.join(chr(ord(ch) - 0x60) if 'ァ' <= ch <= 'ヶ' else ch for ch in s)
NOUN, SAHEN, PROPER, ORG = 1851, 1841, 1920, 1929

existing = set()
kanji_readings = set()  # readings Mozc already writes with kanji: ordinary words
for p in glob.glob(os.path.join(mozc_dir, 'dictionary0[0-9].txt')):
    for line in open(p, encoding='utf-8'):
        f = line.rstrip('\n').split('\t')
        if len(f) >= 5:
            existing.add((f[0], f[4]))
            if re.search(r'[\u4e00-\u9fff]', f[4]): kanji_readings.add(f[0])

rows = {}
def add(r, s, pid, cost):
    k = (r, s)
    if k in existing: return
    if k not in rows or rows[k][1] > cost: rows[k] = (pid, cost)

for line in open(modern, encoding='utf-8'):
    if line.startswith('#') or not line.strip(): continue
    r, s = line.rstrip('\n').split('\t')[:2]
    add(r, s, PROPER, 4500)

ascii_by_reading = {}
modern_by_reading = {}
for (r, s) in rows: modern_by_reading.setdefault(r, []).append(s)
for row in csv.reader(open(neologd, encoding='utf-8')):
    if len(row) < 12: continue
    s, pos, r = row[0], row[4:7], hira(row[11])
    if not re.fullmatch(r'[ぁ-ゖー]+', r) or len(r) > 32: continue
    if re.search(r'[A-Za-z]', s):
        if 2 <= len(r) <= 12 and len(s) <= 16 and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9 &'+\-!.]*", s) and s.count('.') <= 1:
            ascii_by_reading.setdefault(r, []).append((int(row[3]), s, ORG if pos[2] == '組織' else PROPER))
    elif pos[0] == '名詞' and pos[1] == '一般':
        add(r, s, NOUN, 6000)
    elif pos[1] == 'サ変接続':
        add(r, s, SAHEN, 6000)
    elif pos[1] == '固有名詞' and pos[2] in ('一般', '組織') and len(r) <= 8 and len(s) <= 8:
        add(r, s, ORG if pos[2] == '組織' else PROPER, 7500)

# English spellings: drop case/punctuation variants, keep the best three per reading
for r, lst in ascii_by_reading.items():
    seen = {re.sub(r'[^a-z0-9]', '', s.lower()) for s in modern_by_reading.get(r, [])}
    kept = 0
    for cost, s, pid in sorted(lst, key=lambda x: (x[0], x[1].islower(), x[1])):
        key = re.sub(r'[^a-z0-9]', '', s.lower())
        if key in seen: continue
        seen.add(key); add(r, s, pid, 8000 if r in kanji_readings else 6500); kept += 1
        if kept >= 3: break

with open(out, 'w', encoding='utf-8') as f:
    for (r, s), (pid, cost) in sorted(rows.items()):
        f.write(f'{r}\t{pid}\t{pid}\t{cost}\t{s}\n')
print('extra entries', len(rows))
