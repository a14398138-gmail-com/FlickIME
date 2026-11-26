#!/usr/bin/env python3
"""おーぷん2ちゃんねる対話コーパス (p1atdev/open2ch, all-corpus-cleaned parquet; Apache-2.0) -> one line per utterance.
usage: open2ch_text.py OUT.txt PARQUET...
Drops URLs, reply anchors, ASCII art and non-Japanese lines; each distinct line once."""
import sys, re, hashlib
import pyarrow.parquet as pq
out, files = sys.argv[1], sys.argv[2:]
JA = re.compile(r'[ぁ-ゖァ-ヶ一-鿿]')
ANCHOR = re.compile(r'>>\d+(-\d+)?|＞＞\d+')
BAD = re.compile(r'(https?:|ttps?:|www\.|\.com|\.jp/|[─│┃━┏┓┗┛]{2,}|[\(（][^\)）]{0,6}[\)）][^\s]{0,3}[\(（])')
seen = set(); n = k = 0
with open(out, 'w', encoding='utf-8') as o:
    for f in files:
        t = pq.read_table(f, columns=['dialogue'])
        for dia in t.column('dialogue').to_pylist():
            for content in dia['content']:
                for line in (content or '').split('\n'):
                    n += 1
                    s = ANCHOR.sub('', line).strip().replace('　', ' ')
                    if not (2 <= len(s) <= 80) or BAD.search(s): continue
                    if len(JA.findall(s)) < len(s) * 0.5: continue
                    h = hashlib.blake2b(s.encode(), digest_size=8).digest()
                    if h in seen: continue
                    seen.add(h); o.write(s + '\n'); k += 1
        print(f, 'lines', n, 'kept', k, flush=True)
