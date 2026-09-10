#!/usr/bin/env python3
"""
apply_scores.py -- traegt die Ergebnisse von `curvescore score` in eine
.osm.pbf ein.

Warum es dieses Zwischenstueck gibt
-----------------------------------
Der Kurven-Scorer (tools/curvescore/) kann OSM-XML zurueckschreiben, PBF aber
ausdruecklich nicht -- im Quelltext steht dazu woertlich, die PBF-Ausgabe
uebernehme die rd5-Pipeline. Genau das ist diese Datei.

Ein XML-Umweg kaeme nicht in Frage: Bayern als .osm-XML sind zweistellige
Gigabyte, und auf einem freien Runner sind nur rund 14 GB frei.

Der Weg ist deshalb:

    curvescore score --in region.osm.pbf --json scores.json
    apply_scores.py region.osm.pbf scores.json region-scored.osm.pbf

Das JSON ist eine Liste von WayScore-Objekten; gebraucht werden daraus nur
`wayId` und `level`. Gelesen wird STROMWEISE (json.JSONDecoder.raw_decode auf
einem mitwachsenden Puffer), weil die Datei fuer ein grosses Bundesland selbst
in die Gigabyte gehen kann und ein json.load() dafuer den Arbeitsspeicher
sprengen wuerde.

Aufruf:
    apply_scores.py <in.osm.pbf> <scores.json> <out.osm.pbf>
                    [--tag-name opencurv:curve] [--default 0] [--no-default]

    --default N     Ways, die der Scorer nicht bewertet hat, aber routbar
                    sind, bekommen diesen Wert. Standard 0.
                    HINTERGRUND: Ein Way OHNE den Tag laesst v:opencurv:curve
                    NaN liefern, und NaN macht in BRouter das gesamte Netz
                    unbefahrbar. Der Default ist die zweite Absicherung neben
                    der Existenzpruefung im Profil.
    --no-default    nur bewertete Ways taggen (nur fuer Messzwecke).
"""

import json
import sys
import time

import osmium

# Deckungsgleich mit placeholder_scorer.py: das ist die Menge der Wege, die
# BRouters all.brf ueberhaupt in die Kacheln laesst.
ROUTABLE = {
    "motorway", "motorway_link", "trunk", "trunk_link",
    "primary", "primary_link", "secondary", "secondary_link",
    "tertiary", "tertiary_link", "unclassified", "residential",
    "living_street", "road", "service", "track",
}


def stream_scores(path):
    """Liefert (wayId, level) aus einer moeglicherweise sehr grossen JSON-Datei.

    Vertraegt sowohl ein Wurzel-Array (so schreibt `curvescore score --json`)
    als auch ein Objekt mit den Schluesseln 'ranking' oder 'scores'.
    """
    dec = json.JSONDecoder()
    with open(path) as fh:
        buf = fh.read(1 << 20)
        # Fuehrende Struktur ueberspringen, bis das oeffnende '[' des Arrays
        # gefunden ist.
        i = 0
        while True:
            j = buf.find("[", i)
            if j >= 0:
                buf = buf[j + 1:]
                break
            more = fh.read(1 << 20)
            if not more:
                return
            buf = buf[-64:] + more
            i = 0

        while True:
            buf = buf.lstrip()
            if buf.startswith(","):
                buf = buf[1:].lstrip()
            if buf.startswith("]") or buf == "":
                if buf.startswith("]"):
                    return
                more = fh.read(1 << 20)
                if not more:
                    return
                buf += more
                continue
            while True:
                try:
                    obj, end = dec.raw_decode(buf)
                    break
                except ValueError:
                    more = fh.read(1 << 20)
                    if not more:
                        return
                    buf += more
            buf = buf[end:]
            wid = obj.get("wayId")
            lvl = obj.get("level")
            if wid is not None and lvl is not None:
                yield int(wid), int(lvl)


class Applier(osmium.SimpleHandler):
    def __init__(self, writer, scores, tag_name, default):
        super().__init__()
        self.writer = writer
        self.scores = scores
        self.tag_name = tag_name
        self.default = default
        self.n_scored = 0
        self.n_default = 0

    def node(self, n):
        self.writer.add_node(n)

    def relation(self, r):
        self.writer.add_relation(r)

    def way(self, w):
        lvl = self.scores.get(w.id)
        if lvl is None:
            if self.default is None or w.tags.get("highway") not in ROUTABLE:
                self.writer.add_way(w)
                return
            lvl = self.default
            self.n_default += 1
        else:
            self.n_scored += 1
        tags = dict(w.tags)
        tags[self.tag_name] = str(lvl)
        self.writer.add_way(w.replace(tags=tags))


def main(argv):
    if len(argv) < 4:
        print(__doc__)
        return 1
    src, scores_path, dst = argv[1], argv[2], argv[3]
    tag_name = argv[argv.index("--tag-name") + 1] if "--tag-name" in argv else "opencurv:curve"
    default = None if "--no-default" in argv else (
        int(argv[argv.index("--default") + 1]) if "--default" in argv else 0)

    t0 = time.time()
    scores = {}
    bad = 0
    for wid, lvl in stream_scores(scores_path):
        # Plausibilitaetspruefung: mit der Wildcard-Deklaration in lookups.dat
        # gibt es KEINE Wertebereichspruefung mehr in BRouter (siehe
        # 1.Doku/RD5_Pipeline.md, 6). Ein kaputter Scorer wuerde sonst
        # unbemerkt Unsinn in die Kacheln schreiben.
        if lvl < 0 or lvl > 255:
            bad += 1
            continue
        scores[wid] = lvl
    if bad:
        print("WARNUNG: %d Score-Werte lagen ausserhalb 0..255 und wurden "
              "verworfen." % bad, file=sys.stderr)
    if not scores:
        print("FEHLER: %s enthaelt keinen einzigen verwertbaren Eintrag "
              "(erwartet: Objekte mit 'wayId' und 'level')." % scores_path,
              file=sys.stderr)
        return 1
    print("Scores gelesen: %d Ways in %.1f s" % (len(scores), time.time() - t0))

    t1 = time.time()
    writer = osmium.SimpleWriter(dst, overwrite=True)
    h = Applier(writer, scores, tag_name, default)
    h.apply_file(src)
    writer.close()

    print("apply_scores: %s + %s -> %s" % (src, scores_path, dst))
    print("  vom Scorer bewertet : %d" % h.n_scored)
    print("  auf Standardwert    : %d" % h.n_default)
    print("  laufzeit            : %.1f s" % (time.time() - t1))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
