#!/usr/bin/env python3
"""Same as mine_compounds.py, but straight from zenz-v2.5-dataset JSONL on stdin (output field),
tokenised with MeCab+IPAdic on the fly. usage: curl ... | mine_stream.py OUT.tsv [min_count]"""
import sys, json, re, multiprocessing as mp
from collections import Counter
OUT = sys.argv[1]; MIN = int(sys.argv[2]) if len(sys.argv) > 2 else 10
KANJI = re.compile(r'^[一-鿿々]+$'); HIRA = re.compile(r'^[ぁ-ゖー]+$')
_t = None
def hira(s): return ''.join(chr(ord(c) - 0x60) if 'ァ' <= c <= 'ヶ' else c for c in s)
def work(lines):
    global _t
    if _t is None:
        import fugashi, ipadic
        _t = fugashi.GenericTagger(ipadic.MECAB_ARGS)
    c = Counter()
    for line in lines:
        try: s = json.loads(line)['output']
        except Exception: continue
        run = []
        for w in list(_t(s)) + [None]:
            if w is not None:
                f = w.feature
                r = hira(f[7]) if len(f) >= 8 and f[7] not in ('*', None) else ''
                if KANJI.match(w.surface) and HIRA.match(r):
                    run.append((w.surface, r)); continue
            if 2 <= len(run) <= 4:
                sf = ''.join(x[0] for x in run)
                if 2 <= len(sf) <= 6: c[(''.join(x[1] for x in run), sf)] += 1
            run = []
    return c
def batches():
    b = []
    for line in sys.stdin:
        b.append(line)
        if len(b) == 5000: yield b; b = []
    if b: yield b
total = Counter(); n = 0
with mp.Pool(2) as pool:
    for c in pool.imap_unordered(work, batches()):
        total.update(c); n += 1
        if n % 200 == 0: print('batches', n, 'pairs', len(total), flush=True)
with open(OUT, 'w', encoding='utf-8') as f:
    for (r, s), k in total.most_common():
        if k < MIN: break
        f.write(f'{r}\t{s}\t{k}\n')
print('done', n, 'batches')
