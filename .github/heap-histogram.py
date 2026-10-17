"""Temporary review helper: class histogram of an Android (or JVM) .hprof, app heap only, and which classes point at
the biggest ones. Usage: python3 heap-histogram.py dump.hprof"""
import struct
import sys
from collections import Counter, defaultdict

PRIM_SIZE = {4: 1, 5: 2, 6: 4, 7: 8, 8: 1, 9: 2, 10: 4, 11: 8}
PRIM_NAME = {4: 'boolean[]', 5: 'char[]', 6: 'float[]', 7: 'double[]', 8: 'byte[]', 9: 'short[]', 10: 'int[]', 11: 'long[]'}

data = memoryview(open(sys.argv[1], 'rb').read())
header_end = bytes(data[:64]).index(b'\0')
ID = struct.unpack_from('>I', data, header_end + 1)[0]
ID_FMT = '>I' if ID == 4 else '>Q'
SIZE = dict(PRIM_SIZE)
SIZE[2] = ID


def rid(pos):
    return struct.unpack_from(ID_FMT, data, pos)[0]


def u4(pos):
    return struct.unpack_from('>I', data, pos)[0]


def u2(pos):
    return struct.unpack_from('>H', data, pos)[0]


strings = {}
load_name = {}          # class id -> name string id
class_info = {}         # class id -> (super id, [instance field types])
skip_heaps = {'zygote', 'image'}


def sub_records(on_instance, on_obj_array, on_prim_array):
    """Walks every heap sub-record, skipping zygote and image heaps."""
    heap = 'app'
    pos = header_end + 1 + 4 + 8
    while pos < len(data):
        tag = data[pos]
        length = u4(pos + 5)
        pos += 9
        rec_end = pos + length
        if tag == 0x01:
            strings[rid(pos)] = bytes(data[pos + ID:rec_end]).decode('utf-8', 'replace')
        elif tag == 0x02:
            load_name[rid(pos + 4)] = rid(pos + 8 + ID)
        elif tag in (0x0C, 0x1C):
            p = pos
            while p < rec_end:
                sub = data[p]
                p += 1
                if sub in (0xFF, 0x05, 0x07, 0x89, 0x8A, 0x8B, 0x8C, 0x8D, 0x90):
                    p += ID
                elif sub == 0x01:
                    p += 2 * ID
                elif sub in (0x02, 0x03, 0x08, 0x8E):
                    p += ID + 8
                elif sub in (0x04, 0x06):
                    p += ID + 4
                elif sub == 0xFE:
                    heap = strings.get(rid(p + 4), '?')
                    p += 4 + ID
                elif sub == 0x20:
                    cid = rid(p)
                    super_id = rid(p + ID + 4)
                    p += 7 * ID + 8
                    count = u2(p)
                    p += 2
                    for _ in range(count):
                        p += 2
                        p += 1 + SIZE[data[p]]
                    count = u2(p)
                    p += 2
                    for _ in range(count):
                        p += ID
                        p += 1 + SIZE[data[p]]
                    count = u2(p)
                    p += 2
                    fields = []
                    for _ in range(count):
                        fields.append(data[p + ID])
                        p += ID + 1
                    class_info[cid] = (super_id, fields)
                elif sub == 0x21:
                    n = u4(p + 2 * ID + 4)
                    if heap not in skip_heaps:
                        on_instance(rid(p), rid(p + ID + 4), p + 2 * ID + 8, n)
                    p += 2 * ID + 8 + n
                elif sub == 0x22:
                    n = u4(p + ID + 4)
                    if heap not in skip_heaps:
                        on_obj_array(rid(p), rid(p + ID + 8), p + 2 * ID + 8, n)
                    p += 2 * ID + 8 + n * ID
                elif sub == 0x23:
                    n = u4(p + ID + 4)
                    kind = data[p + ID + 8]
                    if heap not in skip_heaps:
                        on_prim_array(rid(p), kind, n)
                    p += ID + 9 + n * PRIM_SIZE[kind]
                elif sub == 0xC3:
                    n = u4(p + ID + 4)
                    kind = data[p + ID + 8]
                    if heap not in skip_heaps:
                        on_prim_array(rid(p), kind, n)
                    p += ID + 9
                else:
                    raise SystemExit('unknown heap sub-record 0x%02x at %d' % (sub, p - 1))
        pos = rec_end


def name_of(class_id):
    return strings.get(load_name.get(class_id), '0x%x' % class_id).replace('/', '.')


obj_key = {}
count, size = Counter(), Counter()


def instance(oid, cid, at, n):
    key = name_of(cid)
    obj_key[oid] = key
    count[key] += 1
    size[key] += n + 8


def obj_array(oid, cid, at, n):
    key = name_of(cid)
    obj_key[oid] = key
    count[key] += 1
    size[key] += n * ID + 12


def prim_array(oid, kind, n):
    key = PRIM_NAME[kind]
    obj_key[oid] = key
    count[key] += 1
    size[key] += n * PRIM_SIZE[kind] + 12


sub_records(instance, obj_array, prim_array)
total = sum(size.values())
print('app heap: %.1f MB in %d objects' % (total / 1e6, sum(count.values())))
print('%10s %12s  class' % ('count', 'bytes'))
for key, b in size.most_common(40):
    print('%10d %12d  %s' % (count[key], b, key))
print()
for key in sorted(k for k in count if 'share_scan' in k):
    print('%10d %12d  %s' % (count[key], size[key], key))

# Who references the biggest classes (by count of references from each referrer class).
top = {key for key, _ in size.most_common(8)}
layout_cache = {}


def ref_offsets(cid):
    if cid in layout_cache:
        return layout_cache[cid]
    offsets, at, c = [], 0, cid
    while c and c in class_info:
        super_id, fields = class_info[c]
        for kind in fields:
            if kind == 2:
                offsets.append(at)
            at += SIZE[kind]
        c = super_id
    layout_cache[cid] = offsets
    return offsets


referrers = defaultdict(Counter)


def instance_refs(oid, cid, at, n):
    src = name_of(cid)
    for off in ref_offsets(cid):
        target = obj_key.get(rid(at + off))
        if target in top:
            referrers[target][src] += 1


def array_refs(oid, cid, at, n):
    src = name_of(cid)
    for i in range(n):
        target = obj_key.get(rid(at + i * ID))
        if target in top:
            referrers[target][src] += 1


sub_records(instance_refs, array_refs, lambda *a: None)
print()
for key, _ in size.most_common(8):
    print('referrers of %s: %s' % (key, ', '.join('%s=%d' % kv for kv in referrers[key].most_common(6))))
