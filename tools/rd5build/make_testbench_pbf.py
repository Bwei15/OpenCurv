#!/usr/bin/env python3
"""
make_testbench_pbf.py -- erzeugt den Pruefstand fuer den RD5-Tag-Spike.

Schreibt eine winzige OSM-PBF-Datei (und zur Lesbarkeit dasselbe als .osm-XML)
ohne jede externe Abhaengigkeit -- kein osmium, kein osmosis, kein protoc.
Die Protobuf-Kodierung ist von Hand geschrieben; BRouters BPbfBlobDecoder
akzeptiert unkomprimierte ("raw") Blobs, deshalb brauchen wir auch kein zlib.

Geometrie: zwei spiegelsymmetrische Verbindungen zwischen denselben zwei
Knoten A und B. Gleiche Laenge, gleiche highway-Klasse, gleiches surface --
der EINZIGE Unterschied ist der Wert des neuen Tags.

        N1 --------- N2          (Nordroute, opencurv:curve = CURVE_NORTH)
       /               \
      A                 B
       \               /
        S1 --------- S2          (Suedroute, opencurv:curve = CURVE_SOUTH)

Aufruf:  python3 make_testbench_pbf.py <out.osm.pbf> [<out.osm>]
"""

import struct
import sys

# ---------------------------------------------------------------- protobuf ---


def varint(v):
    out = bytearray()
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def zigzag(v):
    return (v << 1) ^ (v >> 63) if v < 0 else (v << 1)


def key(field, wiretype):
    return varint((field << 3) | wiretype)


def f_bytes(field, data):
    return key(field, 2) + varint(len(data)) + data


def f_str(field, s):
    return f_bytes(field, s.encode("utf-8"))


def f_int(field, v):
    return key(field, 0) + varint(v)


def f_packed_uint(field, values):
    body = b"".join(varint(v) for v in values)
    return f_bytes(field, body)


def f_packed_sint(field, values):
    body = b"".join(varint(zigzag(v)) for v in values)
    return f_bytes(field, body)


# ------------------------------------------------------------------- blobs ---

GRANULARITY = 100  # 100 nanodegrees == 1e-7 degrees


def blob(blob_type, payload):
    """BlobHeader{type=1,datasize=3} + Blob{raw=1,raw_size=2}, laengenpraefigiert."""
    body = f_bytes(1, payload) + f_int(2, len(payload))
    header = f_str(1, blob_type) + f_int(3, len(body))
    return struct.pack(">i", len(header)) + header + body


def header_block():
    # HeaderBlock{required_features=4}
    return f_str(4, "OsmSchema-V0.6") + f_str(16, "opencurv-rd5-spike")


class Block:
    """Ein PrimitiveBlock mit eigener Stringtable."""

    def __init__(self):
        self.strings = [""]
        self.index = {"": 0}
        self.nodes = []
        self.ways = []

    def s(self, text):
        if text not in self.index:
            self.index[text] = len(self.strings)
            self.strings.append(text)
        return self.index[text]

    def add_node(self, nid, lat, lon, tags=None):
        tags = tags or {}
        keys = [self.s(k) for k in tags]
        vals = [self.s(v) for v in tags.values()]
        # ACHTUNG: Node.id ist in osmformat.proto ein sint64 (zigzag), im
        # Gegensatz zu Way.id (int64). Ohne Zigzag kommen im Mapcreator
        # verdrehte Knoten-IDs an und das Wegenetz zerfaellt.
        body = key(1, 0) + varint(zigzag(nid))
        if keys:
            body += f_packed_uint(2, keys) + f_packed_uint(3, vals)
        body += key(8, 0) + varint(zigzag(int(round(lat * 1e7))))
        body += key(9, 0) + varint(zigzag(int(round(lon * 1e7))))
        self.nodes.append(body)

    def add_way(self, wid, refs, tags):
        keys = [self.s(k) for k in tags]
        vals = [self.s(v) for v in tags.values()]
        body = f_int(1, wid)
        body += f_packed_uint(2, keys) + f_packed_uint(3, vals)
        deltas, prev = [], 0
        for r in refs:
            deltas.append(r - prev)
            prev = r
        body += f_packed_sint(8, deltas)
        self.ways.append(body)

    def serialize(self):
        stringtable = b"".join(f_bytes(1, x.encode("utf-8")) for x in self.strings)
        group = b""
        for n in self.nodes:
            group += f_bytes(1, n)
        for w in self.ways:
            group += f_bytes(3, w)
        out = f_bytes(1, stringtable)
        out += f_bytes(2, group)
        out += f_int(17, GRANULARITY)
        out += f_int(18, 1000)
        return out


# --------------------------------------------------------------- Pruefstand ---

TAG_NAME = "opencurv:curve"
CURVE_NORTH = "15"
CURVE_SOUTH = "0"

BASE_LAT = 52.0   # gut im Inneren der 5x5-Kachel E10_N50 (lon 10..15, lat 50..55)
BASE_LON = 12.0
DLAT = 0.0020   # seitlicher Versatz der beiden Aeste
DLON = 0.0050   # Laengsversatz

COMMON_TAGS = {
    "highway": "secondary",
    "surface": "asphalt",
    "maxspeed": "80",
}

# id: (lat, lon)
NODES = {
    1: (BASE_LAT, BASE_LON + 0 * DLON),            # A  (West-Ende)
    2: (BASE_LAT, BASE_LON + 4 * DLON),            # B  (Ost-Ende)
    11: (BASE_LAT + DLAT, BASE_LON + 1 * DLON),    # N1
    12: (BASE_LAT + DLAT, BASE_LON + 3 * DLON),    # N2
    21: (BASE_LAT - DLAT, BASE_LON + 1 * DLON),    # S1
    22: (BASE_LAT - DLAT, BASE_LON + 3 * DLON),    # S2
}

WAYS = [
    (101, [1, 11, 12, 2], CURVE_NORTH),  # Nordroute
    (102, [1, 21, 22, 2], CURVE_SOUTH),  # Suedroute
]


def build(with_tag=True):
    b = Block()
    for nid, (lat, lon) in sorted(NODES.items()):
        b.add_node(nid, lat, lon)
    for wid, refs, curve in WAYS:
        tags = dict(COMMON_TAGS)
        tags["name"] = "north" if wid == 101 else "south"
        if with_tag:
            tags[TAG_NAME] = curve
        b.add_way(wid, refs, tags)
    return b


def build_grid(n, with_tag, nvalues, scale):
    """Gitternetz aus n*n Knoten -> 2*n*(n-1) Wege. Fuer die Groessenmessung.

    `nvalues` = Anzahl verschiedener Tag-Werte, `scale` = Faktor, mit dem der
    Wert vor dem Schreiben multipliziert wird (fuer den Wildcard-Modus, in dem
    der Wert als Float ankommt).
    """
    b = Block()
    d = 0.0008
    nid = 1
    ids = {}
    for i in range(n):
        for j in range(n):
            ids[(i, j)] = nid
            b.add_node(nid, BASE_LAT + i * d, BASE_LON + j * d)
            nid += 1
    wid = 1
    for i in range(n):
        for j in range(n):
            for di, dj in ((0, 1), (1, 0)):
                if i + di >= n or j + dj >= n:
                    continue
                tags = dict(COMMON_TAGS)
                if with_tag:
                    v = (i * 7 + j * 13 + wid) % nvalues
                    tags[TAG_NAME] = ("%g" % (v * scale)) if scale != 1 else str(v)
                b.add_way(wid, [ids[(i, j)], ids[(i + di, j + dj)]], tags)
                wid += 1
    return b, nid - 1, wid - 1


def write_pbf(path, with_tag=True, grid=0, nvalues=16, scale=1):
    if grid:
        b, nn, nw = build_grid(grid, with_tag, nvalues, scale)
    else:
        b, nn, nw = build(with_tag), len(NODES), len(WAYS)
    with open(path, "wb") as fh:
        fh.write(blob("OSMHeader", header_block()))
        fh.write(blob("OSMData", b.serialize()))
    return nn, nw


def write_xml(path, with_tag=True):
    lines = ['<?xml version="1.0" encoding="UTF-8"?>', '<osm version="0.6" generator="opencurv-rd5-spike">']
    for nid, (lat, lon) in sorted(NODES.items()):
        lines.append('  <node id="%d" lat="%.7f" lon="%.7f" version="1"/>' % (nid, lat, lon))
    for wid, refs, curve in WAYS:
        lines.append('  <way id="%d" version="1">' % wid)
        for r in refs:
            lines.append('    <nd ref="%d"/>' % r)
        for k, v in COMMON_TAGS.items():
            lines.append('    <tag k="%s" v="%s"/>' % (k, v))
        lines.append('    <tag k="name" v="%s"/>' % ("north" if wid == 101 else "south"))
        if with_tag:
            lines.append('    <tag k="%s" v="%s"/>' % (TAG_NAME, curve))
        lines.append("  </way>")
    lines.append("</osm>")
    with open(path, "w") as fh:
        fh.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    argv = sys.argv[1:]
    if not argv:
        print(__doc__)
        sys.exit(1)

    def opt(name, default):
        return type(default)(argv[argv.index(name) + 1]) if name in argv else default

    with_tag = "--no-tag" not in argv
    grid = opt("--grid", 0)
    nvalues = opt("--values", 16)
    scale = opt("--scale", 1.0)
    skip = set()
    for name in ("--grid", "--values", "--scale"):
        if name in argv:
            skip.add(argv.index(name) + 1)
    args = [a for i, a in enumerate(argv) if not a.startswith("--") and i not in skip]

    nn, nw = write_pbf(args[0], with_tag, grid, nvalues, scale)
    if len(args) > 1 and not grid:
        write_xml(args[1], with_tag)
    print("wrote %s (with_tag=%s, grid=%d, values=%d, %d nodes, %d ways)"
          % (args[0], with_tag, grid, nvalues, nn, nw))
