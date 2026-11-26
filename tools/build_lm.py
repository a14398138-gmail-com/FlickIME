#!/usr/bin/env python3
"""Build lm.bin (pruned interpolated Kneser-Ney word bigram + trigram model) from lm_corpus.py output.

usage: build_lm.py CORPUS_DIR OUT_FILE [tatoeba_weight] [min_count] [max_bigrams] [max_trigrams]
  EXTRA="name:weight[:trigram_min],..." adds more streams from lm_add_stream.py (e.g. EXTRA=colloq:3,o2ch:1:2)

Format (little endian), read by engine/LanguageModel.kt:
  int magic 'FLM1', nUni, nBi; float step; int unkCost; int reserved
  int   uniKey[nUni]   sorted, fold(fnv64(surface))
  byte  uniLogP[nUni]  -log10 P_cont(w) / step
  byte  uniBack[nUni]  -log10 backoff(v) / step
  int   biKey[nBi]     sorted, mix(fnv64(v), fnv64(w))
  byte  biLogP[nBi]    -log10 P(w|v) / step
  int   ctxKey[nCtx]   sorted, mix(fnv64(u), fnv64(v))   (FLM2 only)
  byte  ctxBack[nCtx]  -log10 backoff(u,v) / step
  int   triKey[nTri]   sorted, mix(mix64(fnv64(u), fnv64(v)), fnv64(w))
  byte  triLogP[nTri]  -log10 P(w|u,v) / step
"""
import sys, os, struct
import numpy as np

src, out = sys.argv[1], sys.argv[2]
TW = float(sys.argv[3]) if len(sys.argv) > 3 else 15.0
MIN_COUNT = float(sys.argv[4]) if len(sys.argv) > 4 else 3.0
MAX_BI = int(sys.argv[5]) if len(sys.argv) > 5 else 3_000_000
MAX_TRI = int(sys.argv[6]) if len(sys.argv) > 6 else 3_000_000
D3 = 0.8
STEP = 8.0 / 255.0
D = 0.75
M64 = (1 << 64) - 1

vocab = [l.rstrip('\n') for l in open(os.path.join(src, 'vocab.txt'), encoding='utf-8')]
V = len(vocab)
print('vocab', V, flush=True)

def bigrams(path, chunk=40_000_000):
    a = np.load(path, mmap_mode='r')
    keys_all, cnt_all = [], []
    for s in range(0, len(a) - 1, chunk):
        x = np.asarray(a[s:min(len(a), s + chunk + 1)], dtype=np.int64)
        l, r = x[:-1], x[1:]
        m = l != 1  # </s> -> <s> is not a bigram
        k, c = np.unique(l[m] * V + r[m], return_counts=True)
        keys_all.append(k); cnt_all.append(c)
    k = np.concatenate(keys_all); c = np.concatenate(cnt_all)
    u, inv = np.unique(k, return_inverse=True)
    return u, np.bincount(inv, weights=c)

# name:weight[:trigram_min_count]
EXTRA = [(x.split(':')[0], float(x.split(':')[1]), int(x.split(':')[2]) if x.count(':') > 1 else 1)
         for x in os.environ.get('EXTRA', '').split(',') if x]
wk, wc = bigrams(os.path.join(src, 'wiki.npy'))
tk, tc = bigrams(os.path.join(src, 'tatoeba.npy'))
print('wiki bigram types', len(wk), 'tatoeba', len(tk), flush=True)
ks, cs = [wk, tk], [wc, tc * TW]
for name, w, _ in EXTRA:
    ek, ec = bigrams(os.path.join(src, name + '.npy')); ks.append(ek); cs.append(ec * w)
    print(name, 'bigram types', len(ek), 'weight', w, flush=True)
keys = np.concatenate(ks); cnts = np.concatenate(cs); del ks, cs
keys, inv = np.unique(keys, return_inverse=True)
cnts = np.bincount(inv, weights=cnts)
left = keys // V; right = keys % V
del inv, wk, wc, tk, tc

c_v = np.bincount(left, weights=cnts, minlength=V)          # c(v)
n1_v = np.bincount(left, minlength=V).astype(np.float64)     # N1+(v,.)
n1_w = np.bincount(right, minlength=V).astype(np.float64)    # N1+(.,w)
types = float(len(keys))
p_cont = n1_w / types
lam = np.where(c_v > 0, D * n1_v / np.maximum(c_v, 1e-9), 1.0)

# vocabulary: words seen often enough (as left or right context)
uni_count = np.bincount(right, weights=cnts, minlength=V)
keep_word = uni_count >= MIN_COUNT
keep_word[0] = keep_word[1] = True
min_pc = p_cont[keep_word & (p_cont > 0)].min()
p_cont = np.where(p_cont > 0, p_cont, min_pc)
unk_logp = np.log10(min_pc) - 0.5

p_kn = np.maximum(cnts - D, 0) / np.maximum(c_v[left], 1e-9) + lam[left] * p_cont[right]
p_back = lam[left] * p_cont[right]
gain = np.log10(p_kn) - np.log10(p_back)
# keep frequent pairs whose probability really differs from the back-off estimate
cand = (cnts >= MIN_COUNT) & keep_word[left] & keep_word[right] & (np.abs(gain) > 0.15)
idx = np.nonzero(cand)[0]
print('bigram candidates', len(idx), flush=True)
if len(idx) > MAX_BI:
    # keep the pairs carrying the most information: count × |log gain|
    imp = cnts[idx] * np.abs(gain[idx])
    idx = idx[np.argsort(-imp)[:MAX_BI]]
print('bigrams kept', len(idx), flush=True)

def fnv(s):
    h = 0xcbf29ce484222325
    b = s.encode('utf-16-le')
    for i in range(0, len(b), 2):
        h ^= b[i] | (b[i + 1] << 8)
        h = (h * 0x100000001b3) & M64
    return h

def s32(x):
    x &= 0xffffffff
    return x - (1 << 32) if x >= 1 << 31 else x

def fold(h):
    return s32(h ^ (h >> 32))

def mix64(h1, h2):
    x = (h1 * 0x9E3779B97F4A7C15 + h2) & M64
    x ^= x >> 31
    x = (x * 0xBF58476D1CE4E5B9) & M64
    x ^= x >> 29
    return x

def mix(h1, h2):
    return s32(mix64(h1, h2))

# ---------------- trigrams ----------------
def trigrams(path, keep_min, chunk=30_000_000):
    a = np.load(path, mmap_mode='r')
    ks, cs = [], []
    for st in range(0, len(a) - 2, chunk):
        x = np.asarray(a[st:min(len(a), st + chunk + 2)], dtype=np.int64)
        u, v, w = x[:-2], x[1:-1], x[2:]
        m = (u != 1) & (v != 1)
        k, c = np.unique((u[m] * V + v[m]) * V + w[m], return_counts=True)
        if keep_min > 1:
            sel = c >= keep_min; k, c = k[sel], c[sel]
        ks.append(k); cs.append(c)
        del x, u, v, w, m
    k = np.concatenate(ks); c = np.concatenate(cs)
    u_, inv = np.unique(k, return_inverse=True)
    return u_, np.bincount(inv, weights=c)

t3k, t3c = trigrams(os.path.join(src, 'wiki.npy'), 2)
u3k, u3c = trigrams(os.path.join(src, 'tatoeba.npy'), 1)
k3l, c3l = [t3k, u3k], [t3c, u3c * TW]
for name, w, tmin in EXTRA:
    ek, ec = trigrams(os.path.join(src, name + '.npy'), tmin); k3l.append(ek); c3l.append(ec * w)
k3 = np.concatenate(k3l); c3 = np.concatenate(c3l); del k3l, c3l
k3, inv = np.unique(k3, return_inverse=True); c3 = np.bincount(inv, weights=c3)
del inv, t3k, t3c, u3k, u3c
print('trigram types (filtered)', len(k3), flush=True)
ctx = k3 // V            # u*V+v
w3 = k3 % V
ctx_i = np.searchsorted(keys, ctx)             # bigram (u,v) index (always present)
vw_i = np.searchsorted(keys, (ctx % V) * V + w3)
c_uv = cnts[ctx_i]
p2 = p_kn[vw_i]
uctx, cinv = np.unique(ctx_i, return_inverse=True)
kept_sum = np.bincount(cinv, weights=c3)
kept_n = np.bincount(cinv).astype(np.float64)
singles = np.maximum(c_uv[np.unique(cinv, return_index=True)[1]] - kept_sum, 0)  # dropped singletons
lam3_ctx = np.minimum(D3 * (kept_n + singles) / np.maximum(cnts[uctx], 1e-9), 1.0)
lam3 = lam3_ctx[cinv]
p3 = np.maximum(c3 - D3, 0) / np.maximum(c_uv, 1e-9) + lam3 * p2
gain3 = np.log10(p3) - np.log10(lam3 * p2)
u_ = ctx // V; v_ = ctx % V
sel = (c3 >= MIN_COUNT) & keep_word[u_] & keep_word[v_] & keep_word[w3] & (np.abs(gain3) > 0.2)
tidx = np.nonzero(sel)[0]
print('trigram candidates', len(tidx), flush=True)
if len(tidx) > MAX_TRI:
    imp = c3[tidx] * np.abs(gain3[tidx])
    tidx = tidx[np.argsort(-imp)[:MAX_TRI]]
print('trigrams kept', len(tidx), flush=True)
# contexts needed by kept trigrams
cid = np.unique(cinv[tidx])

words = np.nonzero(keep_word)[0]
H = {}
for i in words:
    H[int(i)] = fnv(vocab[i])
print('hashed', len(H), flush=True)

def q(logp):
    return np.clip(np.round(-np.asarray(logp) / STEP), 0, 255).astype(np.uint8)

# unigrams (duplicates from hash collisions: keep the more frequent word)
uk = np.array([fold(H[int(i)]) for i in words], dtype=np.int64)
order = np.lexsort((-uni_count[words], uk))
uk, uw = uk[order], words[order]
first = np.concatenate([[True], uk[1:] != uk[:-1]])
uk, uw = uk[first], uw[first]
up = q(np.log10(p_cont[uw]))
ub = q(np.log10(lam[uw]))

bk = np.array([mix(H[int(l)], H[int(r)]) for l, r in zip(left[idx], right[idx])], dtype=np.int64)
bp = q(np.log10(p_kn[idx]))
order = np.argsort(bk, kind='stable')
bk, bp = bk[order], bp[order]
first = np.concatenate([[True], bk[1:] != bk[:-1]])
bk, bp = bk[first], bp[first]

def dedupe(k, v):
    o = np.argsort(k, kind='stable'); k, v = k[o], v[o]
    f = np.concatenate([[True], k[1:] != k[:-1]])
    return k[f], v[f]

cu = keys[uctx[cid]] // V; cv = keys[uctx[cid]] % V
ck = np.array([mix(H[int(a)], H[int(b)]) for a, b in zip(cu, cv)], dtype=np.int64)
ck, cb = dedupe(ck, q(np.log10(lam3_ctx[cid])))
tu = (ctx[tidx] // V); tv = ctx[tidx] % V; tw = w3[tidx]
tk = np.array([s32(mix64(mix64(H[int(a)], H[int(b)]), H[int(c)])) for a, b, c in zip(tu, tv, tw)], dtype=np.int64)
tk, tp = dedupe(tk, q(np.log10(p3[tidx])))

with open(out, 'wb') as f:
    f.write(struct.pack('<iiifii', 0x324D4C46, len(uk), len(bk), STEP, int(-unk_logp * 1000), 0))
    f.write(struct.pack('<ii', len(ck), len(tk)))
    f.write(uk.astype('<i4').tobytes()); f.write(up.tobytes()); f.write(ub.tobytes())
    f.write(bk.astype('<i4').tobytes()); f.write(bp.tobytes())
    f.write(ck.astype('<i4').tobytes()); f.write(cb.tobytes())
    f.write(tk.astype('<i4').tobytes()); f.write(tp.tobytes())
print('wrote', out, os.path.getsize(out), 'bytes; unigrams', len(uk), 'bigrams', len(bk), 'contexts', len(ck), 'trigrams', len(tk), flush=True)

# self-test values for the Kotlin side
with open(out + '.selftest.tsv', 'w', encoding='utf-8') as f:
    for a, b in [('<s>', '今日'), ('今日', 'は'), ('を', '言っ'), ('を', '行っ'), ('検索', 'し'), ('検索', '市内'), ('調子', 'どう'), ('に', 'ぬるぽ')]:
        ia = vocab.index(a) if a in vocab else -1
        ib = vocab.index(b) if b in vocab else -1
        f.write(f'{a}\t{b}\t{fnv(a)}\t{fnv(b)}\t{fold(fnv(a))}\t{mix(fnv(a), fnv(b))}\t{s32(mix64(mix64(fnv(a), fnv(b)), fnv("。")))}\n')
