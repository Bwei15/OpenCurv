#!/usr/bin/env python3
"""
placeholder_scorer.py -- PLATZHALTER fuer tools/curvescore/.

Erfuellt exakt den vereinbarten Vertrag:

    Eingabe : eine OSM-Datei (.osm oder .osm.pbf)
    Ausgabe : dieselben Daten, an jedem bewerteten Way zusaetzlich der Tag
              `opencurv:curve` mit einem ganzzahligen Wert 0..15.

Dieses Modul ist AUSTAUSCHBAR. Sobald `tools/curvescore/` existiert, ruft
`run_scorer.sh` stattdessen den echten Scorer auf -- diese Datei bleibt nur
als Rueckfallebene und als Referenzimplementierung des Vertrags bestehen.

Der hier gerechnete Score ist bewusst simpel (Summe der Richtungsaenderungen
je Kilometer, in 16 Stufen gebinnt). Er ist KEIN Ersatz fuer den echten
Algorithmus -- er existiert, damit die Pipeline messbar und lauffaehig ist,
bevor der Kurven-Algorithmiker fertig ist.

Aufruf:
    placeholder_scorer.py <in.osm.pbf> <out.osm.pbf> [--all-highways]

Optionen:
    --all-highways   auch Wege ohne Geometriewert bekommen den Tag (Wert 0).
                     Standard: an; siehe NaN-Falle in 1.Doku/RD5_Pipeline.md.
    --no-all-highways  nur Wege mit berechnetem Score bekommen den Tag.
    --levels N       Anzahl der Stufen (Default 16, also Werte 0..15).
"""

import math
import sys
import time

import osmium

MAX_SCORE = 15

# Welche highway-Werte ueberhaupt bewertet werden. Deckungsgleich mit dem,
# was BRouters all.brf als befahrbar betrachtet -- absichtlich grosszuegig,
# damit in der .rd5 kein Way ohne Tag landet.
SCORED_HIGHWAYS = {
    "motorway", "motorway_link", "trunk", "trunk_link",
    "primary", "primary_link", "secondary", "secondary_link",
    "tertiary", "tertiary_link", "unclassified", "residential",
    "living_street", "road", "service", "track",
}

R_EARTH = 6371000.0


def _hav_len(p1, p2):
    lon1, lat1 = p1
    lon2, lat2 = p2
    dx = math.radians(lon2 - lon1) * math.cos(math.radians((lat1 + lat2) * 0.5))
    dy = math.radians(lat2 - lat1)
    return math.hypot(dx, dy) * R_EARTH


def _bearing(p1, p2):
    lon1, lat1 = p1
    lon2, lat2 = p2
    dx = math.radians(lon2 - lon1) * math.cos(math.radians((lat1 + lat2) * 0.5))
    dy = math.radians(lat2 - lat1)
    return math.atan2(dx, dy)


def curve_score(coords, max_score=MAX_SCORE):
    """Richtungsaenderung je Kilometer -> 0..max_score. Platzhalterheuristik."""
    if len(coords) < 3:
        return 0
    total_len = 0.0
    total_turn = 0.0
    prev_b = None
    for i in range(len(coords) - 1):
        seg = _hav_len(coords[i], coords[i + 1])
        if seg <= 0.0:
            continue
        total_len += seg
        b = _bearing(coords[i], coords[i + 1])
        if prev_b is not None:
            d = abs(b - prev_b)
            if d > math.pi:
                d = 2 * math.pi - d
            # 90-Grad-Abbieger sind keine Kurven -> abwerten (grob).
            if d < math.radians(80.0):
                total_turn += d
        prev_b = b
    if total_len < 30.0:
        return 0
    deg_per_km = math.degrees(total_turn) / (total_len / 1000.0)
    # 0 deg/km -> 0 ; >= 600 deg/km -> max_score
    return max(0, min(max_score, int(round(deg_per_km / (600.0 / max_score)))))


class Scorer(osmium.SimpleHandler):
    def __init__(self, writer, tag_all, max_score=MAX_SCORE):
        super().__init__()
        self.writer = writer
        self.tag_all = tag_all
        self.max_score = max_score
        self.ways_total = 0
        self.ways_tagged = 0
        self.hist = [0] * (max_score + 1)

    def node(self, n):
        self.writer.add_node(n)

    def relation(self, r):
        self.writer.add_relation(r)

    def way(self, w):
        self.ways_total += 1
        hw = w.tags.get("highway")
        if hw is None or hw not in SCORED_HIGHWAYS:
            self.writer.add_way(w)
            return
        try:
            coords = [(nd.lon, nd.lat) for nd in w.nodes if nd.location.valid()]
        except Exception:
            coords = []
        score = curve_score(coords, self.max_score) if len(coords) >= 3 else 0
        if score == 0 and not self.tag_all:
            self.writer.add_way(w)
            return
        tags = dict(w.tags)
        tags["opencurv:curve"] = str(score)
        self.writer.add_way(w.replace(tags=tags))
        self.ways_tagged += 1
        self.hist[score] += 1


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 1
    src, dst = argv[1], argv[2]
    tag_all = "--no-all-highways" not in argv
    max_score = MAX_SCORE
    if "--levels" in argv:
        max_score = int(argv[argv.index("--levels") + 1]) - 1

    t0 = time.time()
    writer = osmium.SimpleWriter(dst, overwrite=True)
    h = Scorer(writer, tag_all, max_score)
    # locations=True laesst libosmium die Knotenkoordinaten an die Wege
    # anheften -- ohne das ist w.nodes[i].location ungueltig.
    h.apply_file(src, locations=True, idx="flex_mem")
    writer.close()
    dt = time.time() - t0

    print("placeholder_scorer: %s -> %s" % (src, dst))
    print("  ways gesamt      : %d" % h.ways_total)
    print("  ways getaggt     : %d" % h.ways_tagged)
    print("  histogramm 0..%d : %s" % (max_score, " ".join(str(x) for x in h.hist)))
    print("  laufzeit         : %.1f s" % dt)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
