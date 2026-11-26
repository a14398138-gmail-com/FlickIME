# Fine-tunes zenz (GPT-2, GGUF) on the author's own text, on CPU. Output: a GGUF (Q8_0) to load in
# Settings → 「zenzモデルを読み込む」. The data and the model are personal: never commit them.
# usage: python3 tools/zenz_ft/train.py TRAIN_TSV OUT.gguf [epochs=1] [lr=3e-5]
#   TRAIN_TSV lines: surface \t reading (hiragana or katakana) [\t left context]
#   ZENZ_BASE: model to start from (default app/src/main/assets/zenz.gguf)
# needs: pip install torch gguf tokenizers   (1 epoch of ~6800 posts ≈ 10 min on 2 cores)
# Measured (author's posts, 674 held out): top-1 79.8% -> 81.2%, AJIMEE 87.5% -> 86.0%.
import sys, random, time, math, torch, torch.nn.functional as F
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gpt2 import *; from tok import load_tok
M = os.environ.get('ZENZ_BASE', os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../app/src/main/assets/zenz.gguf'))
train, out = sys.argv[1], sys.argv[2]
epochs = float(sys.argv[3]) if len(sys.argv) > 3 else 1; lr = float(sys.argv[4]) if len(sys.argv) > 4 else 3e-5
torch.manual_seed(0); random.seed(0); torch.set_num_threads(2)
tok = load_tok(M); r, ts = load_tensors(M); m = GPT2(ts)
def kata(s): return ''.join(chr(ord(c) + 0x60) if 'ぁ' <= c <= 'ゖ' else c for c in s)
def unesc(s): return s.replace('\\t', '\t').replace('\\n', '\n').replace('\\\\', '\\')
ex = []
for l in open(train, encoding='utf-8'):
    p = l.rstrip('\n').split('\t')
    if len(p) < 2: continue
    s, rd = unesc(p[0]), unesc(p[1]); ctx = unesc(p[2]).replace(' ', '　')[-40:] if len(p) > 2 else ''
    if not s or not rd or len(rd) > 100: continue
    a = tok.encode(('' + ctx if ctx else '') + '' + kata(rd) + '').ids; b = tok.encode(s.replace(' ', '\u3000')).ids + [3]
    ex.append((a, b))
print('examples', len(ex), flush=True)
opt = torch.optim.AdamW(m.parameters(), lr=lr, weight_decay=0.0)
B = 16; steps = int(math.ceil(len(ex) / B) * epochs); step = 0; t0 = time.time()
m.train()
while step < steps:
    random.shuffle(ex)
    ex.sort(key=lambda e: len(e[0]) + len(e[1]) + random.random() * 6)  # similar lengths per batch
    batches = [ex[i:i + B] for i in range(0, len(ex), B)]; random.shuffle(batches)
    for bt in batches:
        if step >= steps: break
        T = max(len(a) + len(b) for a, b in bt)
        x = torch.full((len(bt), T), 1, dtype=torch.long); y = torch.full((len(bt), T), -100, dtype=torch.long)
        for i, (a, b) in enumerate(bt):
            seq = a + b; x[i, :len(seq)] = torch.tensor(seq)
            # predict b (incl. END) from the position before each of its tokens
            y[i, len(a) - 1:len(seq) - 1] = torch.tensor(b)
        cur = lr * min(1.0, (step + 1) / 30) * (0.5 * (1 + math.cos(math.pi * step / steps)))
        for g in opt.param_groups: g['lr'] = cur
        loss = F.cross_entropy(m(x).reshape(-1, m.head.out_features), y.reshape(-1), ignore_index=-100)
        opt.zero_grad(); loss.backward(); torch.nn.utils.clip_grad_norm_(m.parameters(), 1.0); opt.step()
        step += 1
        if step % 25 == 0: print(f'step {step}/{steps} loss {loss.item():.3f} {time.time() - t0:.0f}s', flush=True)
m.eval(); save_gguf(m, r, out); print('saved', out)
