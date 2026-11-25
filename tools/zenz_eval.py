import sys, numpy as np, time
from llama_cpp import Llama
model, nmax = sys.argv[1], int(sys.argv[2])
llm = Llama(model_path=model, n_ctx=256, logits_all=True, verbose=False, n_threads=2, kv_overrides={"tokenizer.ggml.pre": "gpt-2"})
def kata(s): return ''.join(chr(ord(c)+0x60) if 'ぁ'<=c<='ゖ' else c for c in s)
def tok(s, bos): return llm.tokenize(s.replace(' ', '　').encode('utf-8'), add_bos=bos, special=True)
EOS = 3  # '</s>' in the vocab; the model ends outputs with it
def nll(reading, cand):
    p = tok('' + kata(reading) + '', True)
    c = tok(cand, False) + [EOS]
    llm.reset(); llm.eval(p + c)
    logits = np.array(llm.scores[len(p)-1:len(p)-1+len(c)])
    m = logits.max(1, keepdims=True); lse = (m[:,0] + np.log(np.exp(logits-m).sum(1)))
    return float(sum(lse[i] - logits[i, t] for i, t in enumerate(c)))
for set_ in ['tatoeba', 'wiki']:
    rows = [l.rstrip('\n').split('\t') for l in open(f'/home/claude/zenz/nbest_{set_}.tsv', encoding='utf-8')][:nmax]
    data = []; t0 = time.time(); ntok = 0
    for r in rows:
        reading, gold = r[0], r[1]
        cands = [(r[i], int(r[i+1])) for i in range(2, len(r), 2)]
        data.append((gold, [(s, sc, nll(reading, s)) for s, sc in cands]))
    dt = time.time() - t0
    n = len(data)
    def acc(f): return 100.0 * sum(1 for g, cs in data if min(cs, key=f)[0] == g) / n
    print(f'{set_} n={n} ngram={acc(lambda c: c[1]):.1f}% zenz-only={acc(lambda c: c[2]):.1f}%', end=' ')
    for w in [0.1, 0.2, 0.3, 0.5]:
        print(f'mix{w}={acc(lambda c: c[1]*w/1000 + c[2]):.1f}%', end=' ')
    print(f'oracle={100.0*sum(1 for g,cs in data if any(c[0]==g for c in cs))/n:.1f}% ({dt/n*1000:.0f} ms/sentence on this CPU)', flush=True)
