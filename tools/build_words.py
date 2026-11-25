#!/usr/bin/env python3
"""wordfreq large_en -> en_words.txt (most frequent first, a-z and apostrophes)."""
import gzip, msgpack, re, sys
src, out, n = sys.argv[1], sys.argv[2], int(sys.argv[3])
data = msgpack.load(gzip.open(src), raw=False)
words = []
seen = set()
for bucket in data[1:]:
    for w in bucket:
        if re.fullmatch(r"[a-z][a-z']*[a-z]|[ai]", w) and w not in seen:
            seen.add(w); words.append(w)
            if len(words) >= n: break
    if len(words) >= n: break
open(out, 'w').write('\n'.join(words) + '\n')
print(len(words), words[:15])
