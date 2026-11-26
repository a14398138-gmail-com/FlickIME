#!/usr/bin/env python3
"""Find words the dictionary is missing: runs of 2+ all-kanji MeCab tokens that keep appearing together
in the LM corpus (out of lm_corpus.py / lm_add_stream.py), e.g. 院|進 -> 院進 (いんしん).

usage: mine_compounds.py CORPUS_DIR OUT.tsv stream[,stream...] [min_count]
OUT: reading \\t surface \\t count, most frequent first.
"""
import sys, os, re, numpy as np
from collections import Counter
src, out, streams = sys.argv[1], sys.argv[2], sys.argv[3].split(',')
MIN = int(sys.argv[4]) if len(sys.argv) > 4 else 20
KANJI = re.compile(r'^[一-鿿々]+$')
words = [l.rstrip('\n') for l in open(os.path.join(src, 'vocab.txt'), encoding='utf-8')]
V = len(words)
isk = np.zeros(V + 1, dtype=bool)
surf = [''] * V; read = [''] * V
for i, w in enumerate(words):
    if '\x01' not in w: continue
    s, r = w.split('\x01', 1)
    surf[i] = s; read[i] = r
    if KANJI.match(s) and re.fullmatch(r'[ぁ-ゖー]+', r or ''): isk[i] = True
cnt = Counter()
for name in streams:
    a = np.load(os.path.join(src, name + '.npy'), mmap_mode='r')
    for start in range(0, len(a), 20_000_000):
        chunk = np.asarray(a[start:start + 20_000_002])
        k = isk[chunk]
        # run starts/ends of consecutive kanji tokens
        d = np.diff(np.concatenate([[0], k.astype(np.int8), [0]]))
        starts = np.nonzero(d == 1)[0]; ends = np.nonzero(d == -1)[0]
        long = (ends - starts) >= 2
        for s0, e0 in zip(starts[long], ends[long]):
            if e0 - s0 > 4: continue
            ids = chunk[s0:e0]
            sf = ''.join(surf[i] for i in ids)
            if 2 <= len(sf) <= 6:
                cnt[(''.join(read[i] for i in ids), sf)] += 1
        print(name, start, len(cnt), flush=True)
with open(out, 'w', encoding='utf-8') as f:
    for (r, s), c in cnt.most_common():
        if c < MIN: break
        f.write(f'{r}\t{s}\t{c}\n')
print('written', sum(1 for _ in open(out)))
