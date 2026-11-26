#!/usr/bin/env python3
"""Add one more tokenised stream to an lm_corpus.py output directory (vocab.txt is extended in place).

usage: lm_add_stream.py CORPUS_DIR NAME TEXT_FILE
  TEXT_FILE: one sentence per line. Writes CORPUS_DIR/NAME.npy (<s> w1 .. wn </s> per line, words keyed
  surface \\x01 reading exactly like lm_corpus.py) for build_lm.py's extra streams.
  BLOCK=tools/lm_blocklist.txt drops lines containing a listed word: whole MeCab tokens (surface or
  base form) for short entries, substrings for entries of 3+ characters.
"""
import sys, os, re, multiprocessing as mp
from array import array
import numpy as np

OUT, NAME, TEXT = sys.argv[1], sys.argv[2], sys.argv[3]
_tagger = None
def tagger():
    global _tagger
    if _tagger is None:
        import fugashi, ipadic
        _tagger = fugashi.GenericTagger(ipadic.MECAB_ARGS)
    return _tagger
def hira(s): return ''.join(chr(ord(c) - 0x60) if 'ァ' <= c <= 'ヶ' else c for c in s)

BLOCK_TOK, BLOCK_SUB = set(), []
if os.environ.get('BLOCK'):
    for l in open(os.environ['BLOCK'], encoding='utf-8'):
        w = l.strip()
        if not w or w.startswith('#'): continue
        (BLOCK_SUB if len(w) >= 3 else []).append(w.lower())
        BLOCK_TOK.add(w.lower())

def analyse(batch):
    out = []
    t = tagger()
    for s in batch:
        if BLOCK_SUB and any(b in s.lower() for b in BLOCK_SUB):
            out.append(None); continue
        keyed = []
        words = list(t(s))
        if BLOCK_TOK and any(w.surface.lower() in BLOCK_TOK or (len(w.feature) > 6 and str(w.feature[6]).lower() in BLOCK_TOK) for w in words):
            out.append(None); continue
        for w in words:
            f = w.feature
            r = hira(f[7]) if len(f) >= 8 and f[7] not in ('*', None) else w.surface
            keyed.append(w.surface + '\x01' + r)
        out.append(keyed)
    return out

vocab_path = os.path.join(OUT, 'vocab.txt')
words = [l.rstrip('\n') for l in open(vocab_path, encoding='utf-8')]
vocab = {w: i for i, w in enumerate(words)}
n_old = len(words)
stream = array('i')
def batches():
    b = []
    for line in open(TEXT, encoding='utf-8'):
        s = line.strip()
        if s: b.append(s)
        if len(b) == 2000: yield b; b = []
    if b: yield b
n = 0; blocked = 0
with mp.Pool(2) as pool:
    for res in pool.imap(analyse, batches(), chunksize=1):
        for keyed in res:
            if keyed is None:
                blocked += 1; continue
            n += 1
            stream.append(0)
            for w in keyed:
                i = vocab.get(w)
                if i is None:
                    i = vocab[w] = len(words); words.append(w)
                stream.append(i)
            stream.append(1)
        if n % 200000 < 2000: print(NAME, n, 'sentences', len(stream), 'tokens', len(words), 'vocab', flush=True)
np.save(os.path.join(OUT, NAME + '.npy'), np.frombuffer(stream, dtype=np.int32))
with open(vocab_path, 'a', encoding='utf-8') as f:
    for w in words[n_old:]: f.write(w.replace('\n', ' ') + '\n')
print(NAME, 'done', n, 'sentences', blocked, 'blocked', len(stream), 'tokens; vocab', n_old, '->', len(words))
