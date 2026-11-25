#!/usr/bin/env python3
"""POS ids (Mozc id.def) of words that make sense as a completion: nouns, adverbs, etc.,
and verbs/adjectives only in their dictionary (基本形) form — not stems like ありがたかっ."""
import sys
ok = []
for line in open(sys.argv[1], encoding='utf-8'):
    i, pos = line.split(' ', 1)
    f = pos.strip().split(',')
    if f[0] == '名詞' and f[1] not in ('接尾', '非自立', '数'):
        ok.append(i)
    elif f[0] in ('副詞', '連体詞', '接続詞', '感動詞'):
        ok.append(i)
    elif f[0] in ('動詞', '形容詞') and f[1] == '自立' and len(f) > 5 and f[5] == '基本形':
        ok.append(i)
open(sys.argv[2], 'w').write(' '.join(ok) + '\n')
print(len(ok))
