#!/usr/bin/env python3
"""Tokenise Tatoeba (conversational) + Japanese Wikipedia with MeCab/IPAdic.

Outputs in OUT_DIR:
  vocab.txt              one surface per line; id = line number (0=<s>, 1=</s>)
  tatoeba.npy, wiki.npy  int32 token-id streams, each sentence as <s> w1 .. wn </s>
  eval_tatoeba.tsv, eval_wiki.tsv   held-out sentences: hiragana reading \t surface
"""
import bz2, csv, re, sys, os, multiprocessing as mp
from array import array
import numpy as np

OUT = sys.argv[1]
TATOEBA = sys.argv[2]
WIKI = sys.argv[3:]
WIKI_CHAR_BUDGET = int(os.environ.get('WIKI_CHARS', '450000000'))
os.makedirs(OUT, exist_ok=True)

_tagger = None
def tagger():
    global _tagger
    if _tagger is None:
        import fugashi, ipadic
        _tagger = fugashi.GenericTagger(ipadic.MECAB_ARGS)
    return _tagger

def hira(s):
    return ''.join(chr(ord(c) - 0x60) if 'ァ' <= c <= 'ヶ' else c for c in s)

PUNCT = set('、。？！…・「」『』（）ー〜')

def analyse(batch):
    """-> list of (surfaces, reading_or_None)"""
    t = tagger()
    out = []
    for s in batch:
        toks = t(s)
        surf = [w.surface for w in toks]
        # words are keyed by surface + reading, so 行っ(いっ) and 行っ(おこなっ) are different words
        keyed = []
        for w in toks:
            f = w.feature
            r = hira(f[7]) if len(f) >= 8 and f[7] not in ('*', None) else w.surface
            keyed.append(w.surface + '\x01' + r)
        reading = []
        ok = True
        for w in toks:
            f = w.feature
            if w.surface in PUNCT or (f[0] == '記号' and len(w.surface) == 1 and w.surface in '、。？！'):
                reading.append(w.surface); continue
            if len(f) < 8 or f[7] in ('*', None):
                ok = False; break
            r = hira(f[7])
            if not re.fullmatch(r'[ぁ-ゖー]+', r):
                ok = False; break
            reading.append(r)
        out.append((surf, keyed, ''.join(reading) if ok else None))
    return out

def sentences_tatoeba():
    with bz2.open(TATOEBA, 'rt', encoding='utf-8') as f:
        for line in f:
            p = line.rstrip('\n').split('\t')
            if len(p) >= 3 and 2 <= len(p[2]) <= 120:
                yield p[2].replace(' ', '')

JA = re.compile(r'[ぁ-ゖァ-ヶ一-鿿]')
def sentences_wiki():
    import pyarrow.parquet as pq
    budget = WIKI_CHAR_BUDGET
    for path in WIKI:
        pf = pq.ParquetFile(path)
        for rg in range(pf.num_row_groups):
            for doc in pf.read_row_group(rg, columns=['text']).column('text').to_pylist():
                for line in doc.split('\n'):
                    for s in re.split(r'(?<=[。！？])', line.strip()):
                        s = s.strip()
                        if not (6 <= len(s) <= 120): continue
                        if len(JA.findall(s)) < len(s) * 0.5: continue
                        yield s
                        budget -= len(s)
                        if budget <= 0: return

vocab = {'<s>': 0, '</s>': 1}
def ids(words):
    out = [0]
    for w in words:
        i = vocab.get(w)
        if i is None:
            i = vocab[w] = len(vocab)
        out.append(i)
    out.append(1)
    return out

def run(name, gen, hold_every, hold_max, maxlen_eval):
    stream = array('i')
    evals = []
    n = 0
    def batches():
        b = []
        for s in gen:
            b.append(s)
            if len(b) == 2000:
                yield b; b = []
        if b: yield b
    with mp.Pool(2) as pool:
        for res in pool.imap(analyse, batches(), chunksize=1):
            for surf, keyed, reading in res:
                n += 1
                if hold_every and n % hold_every == 0 and len(evals) < hold_max and reading and len(''.join(surf)) <= maxlen_eval:
                    evals.append((reading, ''.join(surf)))
                    continue
                stream.extend(ids(keyed))
            if n % 200000 < 2000:
                print(name, n, 'sentences', len(stream), 'tokens', len(vocab), 'vocab', flush=True)
    np.save(os.path.join(OUT, name + '.npy'), np.frombuffer(stream, dtype=np.int32))
    with open(os.path.join(OUT, 'eval_' + name + '.tsv'), 'w', encoding='utf-8') as f:
        for r, s in evals:
            f.write(r + '\t' + s + '\n')
    print(name, 'done', n, len(stream), len(evals), flush=True)

run('tatoeba', sentences_tatoeba(), 40, 3000, 40)
run('wiki', sentences_wiki(), 3000, 3000, 40)
with open(os.path.join(OUT, 'vocab.txt'), 'w', encoding='utf-8') as f:
    for w, i in sorted(vocab.items(), key=lambda x: x[1]):
        f.write(w.replace('\n', ' ') + '\n')
print('vocab', len(vocab))
