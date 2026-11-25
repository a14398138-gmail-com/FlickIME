#!/usr/bin/env python3
"""FlickIME commit log (JSONL) -> evaluation set for prediction / conversion.

usage: build_predict_eval.py LOG.jsonl OUT_DIR

Writes OUT_DIR/events.jsonl: every usable commit in time order, one per line:
  {"i", "ts", "split": "warm"|"dev"|"test", "context", "reading", "surface", "kind", "target": bool}
- commits deleted right afterwards (a correction removing >= half of it) are dropped:
  they were wrong, so they are neither learned nor scored
- "target": scored for prediction (2..12 reading chars, surface is not just the kana / a symbol)
- split by time: first 60% "warm" (only learned), next 20% "dev" (tune on this), last 20% "test" (report only)
Also writes OUT_DIR/bad.tsv (👎 reports: reading, the text the user wrote instead before pressing ✓, context, shown candidates),
OUT_DIR/events.tsv (same rows, for the JVM test) and OUT_DIR/summary.json.
"""
import json, sys, os, re

log, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
rows = [json.loads(l) for l in open(log, encoding='utf-8') if l.strip()]
commits = sorted((r for r in rows if r.get('type', 'commit') == 'commit'), key=lambda r: r['ts'])
wrong = set()
for r in rows:
    if r.get('type') == 'correction' and len(r.get('deleted', '')) * 2 >= len(r.get('committed', '')) > 0:
        wrong.add(r['ref'])

KANA = re.compile(r'^[ぁ-ゖーァ-ヺ]+$')
events = []
for r in commits:
    if r['ts'] in wrong:
        continue
    reading, surface = r.get('predicted_reading') or r['reading'], r['surface']
    if not reading or not surface:
        continue
    # long unconverted commits are usually forced (cursor moved), not a choice
    if r['kind'] == 'KANA' and r.get('rank', 0) < 0 and len(reading) > 6:
        continue
    target = (2 <= len(reading) <= 12 and r['kind'] in ('SENTENCE', 'WORD', 'PREDICTION', 'ENGLISH')
              and not KANA.match(surface) and surface != reading)
    ctx = r.get('context_before', '')
    # context_before ends with the composing reading itself; keep only the text before it
    if ctx.endswith(r['full_reading']):
        ctx = ctx[: len(ctx) - len(r['full_reading'])]
    events.append({'ts': r['ts'], 'context': ctx[-40:], 'reading': reading, 'surface': surface,
                   'kind': r['kind'], 'target': target})

n = len(events)
for i, e in enumerate(events):
    e['i'] = i
    e['split'] = 'warm' if i < n * 0.6 else 'dev' if i < n * 0.8 else 'test'
with open(os.path.join(out, 'events.jsonl'), 'w', encoding='utf-8') as f:
    for e in events:
        f.write(json.dumps(e, ensure_ascii=False) + '\n')
# the same as TSV for the JVM test (Android's org.json is a stub there): i split target reading surface kind context
def esc(x): return x.replace('\\', '\\\\').replace('\t', '\\t').replace('\n', '\\n')
with open(os.path.join(out, 'events.tsv'), 'w', encoding='utf-8') as f:
    for e in events:
        f.write('\t'.join([str(e['i']), e['split'], '1' if e['target'] else '0', esc(e['reading']), esc(e['surface']), e['kind'], esc(e['context'])]) + '\n')
# 👎 reports: conversions where no candidate was right, with the answer committed afterwards
bad = [r for r in rows if r.get('type') == 'bad' and r.get('answer')]
with open(os.path.join(out, 'bad.tsv'), 'w', encoding='utf-8') as f:
    for r in bad:
        f.write('\t'.join([esc(r['reading']), esc(r['answer']), esc(r.get('context_before', '')), esc(' / '.join(r.get('shown', [])))]) + '\n')
summary = {s: {'events': sum(e['split'] == s for e in events), 'targets': sum(e['split'] == s and e['target'] for e in events)}
           for s in ('warm', 'dev', 'test')}
summary['dropped_corrected'] = len(wrong)
summary['bad_reports'] = len(bad)
json.dump(summary, open(os.path.join(out, 'summary.json'), 'w'), indent=1)
print(json.dumps(summary))
