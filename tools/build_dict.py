#!/usr/bin/env python3
"""Convert Mozc dictionary_oss text files into compact binaries for the IME.

dict.bin (little endian):
  int magic 'MZD1', nReadings, nEntries, readingCharLen, surfaceCharLen, maxReadingLen
  int readingOff[nReadings+1]   (char offsets into readingChars)
  int entryStart[nReadings+1]
  u16 readingChars[readingCharLen]
  entries[nEntries]: int surfOff, u16 surfLen, i16 lid, i16 rid, i16 cost  (12 bytes)
  u16 surfaceChars[surfaceCharLen]

conn.bin: int size, then i16[size*size], index = leftRid*size + rightLid
"""
import struct, sys, glob, os, array

src, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
MAX_LEN = 32

by_reading = {}
for path in sorted(glob.glob(os.path.join(src, 'dictionary0*.txt'))):
    with open(path, encoding='utf-8') as f:
        for line in f:
            p = line.rstrip('\n').split('\t')
            if len(p) < 5:
                continue
            r, lid, rid, cost, s = p[0], int(p[1]), int(p[2]), int(p[3]), p[4]
            if len(r) > MAX_LEN or not r:
                continue
            by_reading.setdefault(r, []).append((cost, lid, rid, s))

def u16(s):
    return s.encode('utf-16-le')

# sort by UTF-16 code units so Kotlin char comparison matches
readings = sorted(by_reading.keys(), key=lambda x: list(struct.unpack('<%dH' % (len(u16(x)) // 2), u16(x))))

r_off, e_start = [], []
rchars = bytearray()
surf_index = {}
schars = bytearray()
entries = bytearray()
n_entries = 0
max_len = 0
for r in readings:
    r_off.append(len(rchars) // 2)
    e_start.append(n_entries)
    rchars += u16(r)
    max_len = max(max_len, len(u16(r)) // 2)
    for cost, lid, rid, s in sorted(by_reading[r]):
        if s not in surf_index:
            surf_index[s] = len(schars) // 2
            schars += u16(s)
        cost = min(cost, 32767)
        entries += struct.pack('<iHhhh', surf_index[s], len(u16(s)) // 2, lid, rid, cost)
        n_entries += 1
r_off.append(len(rchars) // 2)
e_start.append(n_entries)

with open(os.path.join(out, 'dict.bin'), 'wb') as f:
    f.write(struct.pack('<6i', 0x3144_5A4D, len(readings), n_entries, len(rchars) // 2, len(schars) // 2, max_len))
    f.write(array.array('i', r_off).tobytes())
    f.write(array.array('i', e_start).tobytes())
    f.write(rchars)
    f.write(entries)
    f.write(schars)

with open(os.path.join(src, 'connection_single_column.txt')) as f:
    size = int(f.readline())
    vals = array.array('h', (max(-32768, min(32767, int(l))) for l in f))
assert len(vals) == size * size, (len(vals), size)
with open(os.path.join(out, 'conn.bin'), 'wb') as f:
    f.write(struct.pack('<i', size))
    f.write(vals.tobytes())

print('readings', len(readings), 'entries', n_entries, 'maxlen', max_len, 'conn', size)
