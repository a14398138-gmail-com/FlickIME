#!/usr/bin/env python3
"""AJIMEE-Bench (github.com/azooKey/AJIMEE-Bench, CC BY-SA 3.0) -> TSV for AjimeeTest.
usage: git clone https://github.com/azooKey/AJIMEE-Bench && python3 tools/ajimee_tsv.py AJIMEE-Bench/JWTD_v2/v1/evaluation_items.json out.tsv
rows: index \t hiragana reading \t left context \t accepted answers joined by U+0001
"""
import json, sys
d = json.load(open(sys.argv[1], encoding='utf-8'))
def hira(s): return ''.join(chr(ord(c) - 0x60) if 'ァ' <= c <= 'ヶ' else c for c in s)
def esc(x): return x.replace('\\', '\\\\').replace('\t', '\\t').replace('\n', '\\n')
with open(sys.argv[2], 'w', encoding='utf-8') as f:
    for x in d:
        f.write('\t'.join([x['index'], esc(hira(x['input'])), esc(x['context_text']), esc('\u0001'.join(x['expected_output']))]) + '\n')
