#!/usr/bin/env python3
"""Minimal androidx.datastore preferences_pb reader/writer.

PreferenceMap: field 1 repeated map entry { 1: key(string), 2: Value }
Value oneof:   1 bool(varint) 2 float(fixed32) 3 int(varint) 4 long(varint)
               5 string 6 stringset(msg) 7 double(fixed64) 8 bytes
"""
import struct, sys

def read_varint(b, i):
    v = 0; s = 0
    while True:
        x = b[i]; i += 1
        v |= (x & 0x7F) << s
        if not x & 0x80: return v, i
        s += 7

def write_varint(v):
    out = bytearray()
    while True:
        x = v & 0x7F; v >>= 7
        if v: out.append(x | 0x80)
        else: out.append(x); return bytes(out)

def parse_fields(b):
    i = 0; fields = []
    while i < len(b):
        tag, i = read_varint(b, i)
        fn, wt = tag >> 3, tag & 7
        if wt == 0:
            v, i = read_varint(b, i); fields.append((fn, wt, v))
        elif wt == 1:
            fields.append((fn, wt, b[i:i+8])); i += 8
        elif wt == 2:
            ln, i = read_varint(b, i); fields.append((fn, wt, b[i:i+ln])); i += ln
        elif wt == 5:
            fields.append((fn, wt, b[i:i+4])); i += 4
        else:
            raise ValueError(f"wire type {wt}")
    return fields

def encode_field(fn, wt, payload):
    tag = write_varint(fn << 3 | wt)
    if wt == 0: return tag + write_varint(payload)
    if wt in (1, 5): return tag + payload
    if wt == 2: return tag + write_varint(len(payload)) + payload
    raise ValueError

def load(path):
    data = open(path, "rb").read()
    entries = []
    for fn, wt, payload in parse_fields(data):
        assert fn == 1 and wt == 2
        key = None; val = None
        for efn, ewt, ep in parse_fields(payload):
            if efn == 1: key = ep.decode()
            elif efn == 2: val = ep
        entries.append([key, val])
    return entries

def dump(entries):
    for key, val in entries:
        for vfn, vwt, vp in parse_fields(val):
            if vfn == 2: print(f"{key} = float {struct.unpack('<f', vp)[0]}")
            elif vfn == 3: print(f"{key} = int {vp}")
            elif vfn == 1: print(f"{key} = bool {vp}")
            elif vfn == 5: print(f"{key} = str {vp.decode()!r}")
            else: print(f"{key} = field{vfn} {vp!r}")

def save(path, entries):
    out = bytearray()
    for key, val in entries:
        entry = encode_field(1, 2, key.encode()) + encode_field(2, 2, val)
        out += encode_field(1, 2, entry)
    open(path, "wb").write(bytes(out))

if __name__ == "__main__":
    path = sys.argv[1]
    entries = load(path)
    if len(sys.argv) == 2:
        dump(entries); sys.exit()
    # sets: key=float:VAL | key=int:VAL | key=str:VAL (VAL may contain \n)
    for spec in sys.argv[2:]:
        key, tv = spec.split("=", 1)
        typ, raw = tv.split(":", 1)
        if typ == "float": new = encode_field(2, 5, struct.pack("<f", float(raw)))
        elif typ == "int": new = encode_field(3, 0, int(raw))
        elif typ == "str": new = encode_field(5, 2, raw.replace("\\n", "\n").encode())
        else: raise ValueError(typ)
        for e in entries:
            if e[0] == key: e[1] = new; break
        else:
            entries.append([key, new])
    save(path, entries)
    dump(entries)
