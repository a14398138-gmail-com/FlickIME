#!/usr/bin/env python3
"""Mozc symbol.tsv -> symbols.tsv (hiragana reading -> symbols, in Mozc order)."""
import sys, re
src, out = sys.argv[1], sys.argv[2]
m = {}
with open(src, encoding='utf-8') as f:
    next(f)
    for line in f:
        p = line.rstrip('\n').split('\t')
        if len(p) < 3 or not p[1]:
            continue
        sym = p[1]
        for r in p[2].split(' '):
            if re.fullmatch(r'[ぁ-ゖー]+', r):
                lst = m.setdefault(r, [])
                if sym not in lst:
                    lst.append(sym)
with open(out, 'w', encoding='utf-8') as f:
    for r in sorted(m):
        f.write(r + '\t' + '\t'.join(m[r]) + '\n')
print(len(m))
