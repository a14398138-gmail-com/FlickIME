#!/usr/bin/env python3
"""Words from SudachiDict (Apache-2.0, raw lexicon CSVs) that Mozc OSS lacks, in Mozc dictionary format.

usage: build_sudachi_dict.py SUDACHI_DIR MOZC_DIR OUT.txt
  SUDACHI_DIR: small_lex.csv core_lex.csv notcore_lex.csv
    (https://d2ej7fkh96fzlu.cloudfront.net/sudachidict-raw/<version>/*.zip)
  MOZC_DIR: dictionary0*.txt (to skip what is already there) and id.def (Sudachi POS -> Mozc ids)
Taken: common nouns, adverbs, na-adjective stems, interjections, verbs (godan / ichidan, all usual
forms) and i-adjectives from all three files; general proper nouns from small+core; person names from small.
Address-like place names and the long tail of notcore proper nouns are left out to keep the size sane.
"""
import csv, glob, os, re, sys
SD, MOZC, OUT = sys.argv[1:4]
def hira(s): return ''.join(chr(ord(c) - 0x60) if 'ァ' <= c <= 'ヶ' else c for c in s)

ids = {}
for line in open(os.path.join(MOZC, 'id.def'), encoding='utf-8'):
    i, pos = line.rstrip('\n').split(' ', 1)
    ids[pos] = int(i)
existing = set()
for p in glob.glob(os.path.join(MOZC, 'dictionary0*.txt')):
    if p.endswith('_sudachi.txt'): continue
    for line in open(p, encoding='utf-8'):
        f = line.rstrip('\n').split('\t')
        if len(f) >= 5: existing.add((f[0], f[4]))

NOUN = {('普通名詞', '一般'): '名詞,一般', ('普通名詞', 'サ変可能'): '名詞,サ変接続', ('普通名詞', 'サ変形状詞可能'): '名詞,サ変接続',
        ('普通名詞', '形状詞可能'): '名詞,形容動詞語幹', ('普通名詞', '副詞可能'): '名詞,副詞可能', ('普通名詞', '助数詞可能'): '名詞,一般'}
GODAN = {'カ行': '五段・カ行イ音便', 'ガ行': '五段・ガ行', 'サ行': '五段・サ行', 'タ行': '五段・タ行', 'ナ行': '五段・ナ行',
         'バ行': '五段・バ行', 'マ行': '五段・マ行', 'ラ行': '五段・ラ行', 'ワア行': '五段・ワ行促音便'}
VFORM = {'終止形-一般': '基本形', '連体形-一般': '基本形', '連用形-一般': '連用形', '連用形-促音便': '連用タ接続',
         '連用形-イ音便': '連用タ接続', '連用形-撥音便': '連用タ接続', '未然形-一般': '未然形', '意志推量形': '未然ウ接続',
         '仮定形-一般': '仮定形', '仮定形-融合': '仮定縮約１', '命令形': '命令'}
AFORM = {'終止形-一般': '基本形', '連体形-一般': '基本形', '連用形-一般': '連用テ接続', '連用形-促音便': '連用タ接続',
         '仮定形-一般': '仮定形', '語幹-一般': 'ガル接続', '意志推量形': '未然ウ接続'}

def mozc_pos(pos, base):
    p0, p1, p2, p3, ctype, cform = pos
    if p0 == '名詞' and (p1, p2) in NOUN: return NOUN[(p1, p2)] + ',*,*,*,*,*'
    if p0 == '名詞' and p1 == '固有名詞':
        if p2 == '一般': return '名詞,固有名詞,一般,*,*,*,*'
        if p2 == '人名': return {'姓': '名詞,固有名詞,人名,姓,*,*,*', '名': '名詞,固有名詞,人名,名,*,*,*'}.get(p3, '名詞,固有名詞,人名,一般,*,*,*')
        if p2 == '地名': return {'国': '名詞,固有名詞,地域,国,*,*,*'}.get(p3, '名詞,固有名詞,地域,一般,*,*,*')
    if p0 == '形状詞' and p1 == '一般': return '名詞,形容動詞語幹,*,*,*,*,*'
    if p0 == '副詞': return '副詞,一般,*,*,*,*,*'
    if p0 == '感動詞': return '感動詞,*,*,*,*,*,*'
    if p0 == '動詞' and p1 == '一般':
        if ctype.startswith('五段-'): t = '五段動詞'   # Mozc keeps one id set for all ordinary godan verbs
        elif ctype.startswith(('上一段', '下一段')): t = '一段'
        else: return None
        f = VFORM.get(cform)
        if not t or not f: return None
        if f == '命令': f = '命令ｅ' if t != '一段' else '命令ｒｏ'
        return f'動詞,自立,*,*,{t},{f},*'
    if p0 == '形容詞' and p1 == '一般' and ctype.startswith('形容詞'):
        f = AFORM.get(cform)
        if not f: return None
        stem = hira(base[:-1]) if base.endswith('い') else ''
        t = '形容詞・イ段' if stem and stem[-1] in 'いきぎしじちぢにひびぴみりゐ' else '形容詞・アウオ段'
        return f'形容詞,自立,*,*,{t},{f},*'
    return None

# per file: what to take, and the base cost (higher = offered later)
PLAN = [('small_lex.csv', 7500, {'普', '固一', '人', '他'}), ('core_lex.csv', 8000, {'普', '固一', '他'}), ('notcore_lex.csv', 8500, {'普', '他'})]
rows = {}; skipped = 0
for fn, base_cost, take in PLAN:
    for row in csv.reader(open(os.path.join(SD, fn), encoding='utf-8')):
        if len(row) < 13: continue
        s, r = (row[4] or row[0]), hira(row[11])
        pos = tuple(row[5:11])
        kind = '普' if pos[1] == '普通名詞' else '固一' if pos[1:3] == ('固有名詞', '一般') else '人' if pos[2] == '人名' else \
            '地' if pos[2] == '地名' else '他'
        if kind not in take: continue
        if not re.fullmatch(r'[ぁ-ゖー]{1,32}', r) or not s or len(s) > 20: continue
        if re.search(r'[Ａ-Ｚａ-ｚ０-９]', s) or (r, s) in existing or s == r: continue   # kana-only spellings add nothing
        mp = mozc_pos(pos, row[12])
        pid = ids.get(mp) if mp else None
        if pid is None: skipped += 1; continue
        cost = base_cost + (800 if kind in ('固一', '人') else 0)
        k = (r, s, pid)
        if k not in rows or rows[k] > cost: rows[k] = cost
with open(OUT, 'w', encoding='utf-8') as f:
    for (r, s, pid), cost in sorted(rows.items()):
        f.write(f'{r}\t{pid}\t{pid}\t{cost}\t{s}\n')
print('entries', len(rows), 'unmapped', skipped)
