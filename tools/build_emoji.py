#!/usr/bin/env python3
"""emoji_panel.tsv (category -> emoji, Unicode order, no skin-tone variants) and
emoji readings from Mozc appended to the symbol table (えがお -> 😊)."""
import re, sys
test, mozc, symbols_out, panel_out = sys.argv[1:5]
NAMES = {'Smileys & Emotion': '😀 顔', 'People & Body': '👋 人', 'Animals & Nature': '🐶 自然', 'Food & Drink': '🍙 食べ物',
         'Travel & Places': '🚃 旅行', 'Activities': '⚽ 活動', 'Objects': '💡 物', 'Symbols': '❤ 記号', 'Flags': '🏁 旗'}
groups, cur = [], None
for line in open(test, encoding='utf-8'):
    m = re.match(r'# group: (.+)', line)
    if m:
        cur = NAMES.get(m.group(1).strip())
        if cur: groups.append((cur, []))
        continue
    if not cur or line.startswith('#') or 'fully-qualified' not in line or '; fully' not in line: continue
    cps = line.split(';')[0].split()
    if any(c in ('1F3FB', '1F3FC', '1F3FD', '1F3FE', '1F3FF') for c in cps): continue
    groups[-1][1].append(''.join(chr(int(c, 16)) for c in cps))
with open(panel_out, 'w', encoding='utf-8') as f:
    for name, es in groups: f.write(name + '\t' + '\t'.join(es) + '\n')

table = {}
for line in open(symbols_out, encoding='utf-8'):
    p = line.rstrip('\n').split('\t')
    table[p[0]] = p[1:]
for line in open(mozc, encoding='utf-8'):
    if line.startswith('#'): continue
    p = line.rstrip('\n').split('\t')
    if len(p) < 3 or not p[1]: continue
    for r in p[2].split(' '):
        if re.fullmatch(r'[ぁ-ゖー]{2,}', r):
            lst = table.setdefault(r, [])
            if p[1] not in lst: lst.append(p[1])
with open(symbols_out, 'w', encoding='utf-8') as f:
    for r in sorted(table): f.write(r + '\t' + '\t'.join(table[r]) + '\n')
print('groups', [(n, len(e)) for n, e in groups], 'symbol readings', len(table))
