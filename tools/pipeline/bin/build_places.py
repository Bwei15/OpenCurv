#!/usr/bin/env python3
"""
build_places.py -- extracts an offline street/address index from a region's
OSM extract into a SQLite file the app queries directly
(follow-up: com.motoroute.data.search, wave 6.4b).

Uses pyosmium (the `osmium` Python package), the same tool
`build_cameras.py` and `apply_scores.py` already use.

Output: `<out>.places.sqlite`, schema documented on the CREATE TABLE
statements below (also see 1.Doku/Ortssuche.md).

Two passes over the PBF, the same shape as build_cameras.py's two-pass
DeviceCollector/CameraCollector:

  Pass 1 (`RelationScan`, no locations): finds `place=city|town` and
  address-bearing RELATIONS and records one member way per relation (its
  best guess at an "outer" ring) whose node-centroid Pass 2 should compute.
  Cheap: only the relation() callback does anything, so nodes and ways
  stream past for free (mirrors DeviceCollector in build_cameras.py).

  Pass 2 (`MainCollector`, locations=True): a single combined
  node()/way()/relation() pass that reads node coordinates directly, and
  way-node coordinates via pyosmium's location index (populated as nodes
  stream by, resolved once the way's nodes are visited -- see
  apply_file(..., locations=True, idx=...)). Way centroids needed for Pass
  1's relations are stashed keyed by way id; because ways precede relations
  in PBF file order, by the time relation() runs in this same pass every
  way centroid it needs has already been computed.

Everything else (deduplicating streets, assigning a place to each street and
address, computing parent_id via grid search) is plain Python post-processing
over the rows collected above -- no further PBF reads.

Usage:
    build_places.py <in.osm.pbf> <out.places.sqlite> [--region-id ID]
"""

import math
import os
import re
import sqlite3
import sys
import time

import osmium

# ---------------------------------------------------------------------------
# Normalisation -- muss identisch bleiben mit PlaceQuery.normalise() in
# app/src/main/java/com/motoroute/data/search/Place.kt. Beide Seiten tragen
# denselben Kommentar und dieselben 5 Beispielwerte (siehe
# app/src/test/java/com/motoroute/PlaceNormaliseParityTest.kt und
# _PARITY_EXAMPLES unten), damit ein Drift zwischen Kotlin und Python bei den
# Tests auffaellt statt still falsche Treffer zu produzieren.
# ---------------------------------------------------------------------------

_FOLD = {
    "ä": "ae", "ö": "oe", "ü": "ue", "ß": "ss",
    "á": "a", "à": "a", "â": "a", "å": "a",
    "é": "e", "è": "e", "ê": "e",
    "í": "i", "ì": "i", "î": "i",
    "ó": "o", "ò": "o", "ô": "o", "ø": "o",
    "ú": "u", "ù": "u", "û": "u",
    "ç": "c",
    "ñ": "n",
}
_MULTI_SPACE = re.compile(r" +")


def normalise(text):
    """Lower case, umlauts folded, everything else reduced to single spaces.

    muss identisch bleiben mit PlaceQuery.normalise() (Place.kt) -- siehe
    Kommentar oben.
    """
    if not text:
        return ""
    out = []
    for raw in text.lower():
        folded = _FOLD.get(raw)
        if folded is not None:
            out.append(folded)
        elif raw.isalnum():
            out.append(raw)
        else:
            out.append(" ")
    return _MULTI_SPACE.sub(" ", "".join(out)).strip()


# 5 Beispielwerte, exakt gespiegelt in PlaceNormaliseParityTest.kt. Ein
# Drift zwischen den beiden Implementierungen laesst genau einen der beiden
# Tests fehlschlagen.
_PARITY_EXAMPLES = [
    ("Göttingen", "goettingen"),
    ("Münster", "muenster"),
    ("Straße", "strasse"),
    ("Bad Münder am Deister", "bad muender am deister"),
    ("Sankt-Florian-Weg 12a", "sankt florian weg 12a"),
]


def hn_norm(housenumber):
    """'12 A' -> '12a'. Kleingeschrieben, ohne Leerzeichen (siehe Auftrag)."""
    if not housenumber:
        return ""
    return housenumber.strip().lower().replace(" ", "")


# ---------------------------------------------------------------------------
# Kinds
# ---------------------------------------------------------------------------

PLACE_KINDS = ("city", "town", "village", "hamlet", "suburb", "neighbourhood", "locality")
# village/hamlet/suburb/neighbourhood/locality are nodes only; city/town also
# get way/relation centroids (see module docstring and the "5 km" rule below).
CITY_TOWN = ("city", "town")

# Highways a motorcyclist can actually ride; excludes anything foot/cycle/
# construction-only. Exact denylist from the Auftrag -- unusual-but-legal
# highway values (living_street, track, unclassified, service, ...) still
# pass, on purpose.
_EXCLUDED_HIGHWAY = {
    "footway", "path", "steps", "cycleway", "bridleway", "pedestrian",
    "corridor", "platform", "construction", "proposed",
}

CENTROID_SAME_NAME_RADIUS_M = 5_000  # "5 km" rule from the Auftrag
STREET_ADDR_MATCH_RADIUS_M = 5_000   # how far an addr:street may be from a
                                      # way segment and still count as "an
                                      # dieser Strasse" for place resolution
GRID_CELL_DEG = 0.1                  # ~11 km at German latitudes -- coarse
                                      # bucket for the "naechster Ort" search


def haversine_m(lat1, lon1, lat2, lon2):
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlmb = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlmb / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def grid_key(lat, lon):
    return (math.floor(lat / GRID_CELL_DEG), math.floor(lon / GRID_CELL_DEG))


class Grid:
    """Bucket points into ~11 km cells so 'nearest place' is a small local
    scan instead of an O(n) walk over every place in the region."""

    def __init__(self):
        self.cells = {}

    def add(self, idx, lat, lon):
        self.cells.setdefault(grid_key(lat, lon), []).append(idx)

    def nearby(self, lat, lon, rings=1):
        gy, gx = grid_key(lat, lon)
        out = []
        for dy in range(-rings, rings + 1):
            for dx in range(-rings, rings + 1):
                out.extend(self.cells.get((gy + dy, gx + dx), ()))
        return out


# ---------------------------------------------------------------------------
# Pass 1: relations that need a member way's centroid resolved in Pass 2.
# ---------------------------------------------------------------------------

class RelationScan(osmium.SimpleHandler):
    """Cheap pre-pass (relation() only, mirrors DeviceCollector in
    build_cameras.py): which relations are place=city/town or carry an
    addr:street+addr:housenumber, and which single member way should stand
    in for their geometry (first 'outer' role, else first way member)."""

    def __init__(self):
        super().__init__()
        # way_id -> list of (relation_id, kind) that need that way's centroid
        self.needed_way_ids = {}
        self.place_relations = {}    # relation_id -> (name, way_id)
        self.address_relations = {}  # relation_id -> (tags dict, way_id)

    def relation(self, r):
        tags = dict(r.tags)
        place = tags.get("place")
        is_place = place in CITY_TOWN and tags.get("name")
        is_address = tags.get("addr:street") and tags.get("addr:housenumber")
        if not is_place and not is_address:
            return
        way_id = None
        for m in r.members:
            if m.type != "w":
                continue
            if m.role == "outer":
                way_id = m.ref
                break
            if way_id is None:
                way_id = m.ref
        if way_id is None:
            return
        if is_place:
            self.place_relations[r.id] = (tags.get("name"), way_id)
            self.needed_way_ids.setdefault(way_id, []).append((r.id, "place"))
        if is_address:
            self.address_relations[r.id] = (tags, way_id)
            self.needed_way_ids.setdefault(way_id, []).append((r.id, "address"))


# ---------------------------------------------------------------------------
# Pass 2: the real extraction.
# ---------------------------------------------------------------------------

class MainCollector(osmium.SimpleHandler):
    def __init__(self, needed_way_ids, place_relations, address_relations):
        super().__init__()
        self.needed_way_ids = needed_way_ids
        self.place_relations = place_relations
        self.address_relations = address_relations
        self._way_centroid = {}  # way_id -> (lat, lon), only for needed_way_ids

        # place candidates: dict source -> row
        # row = (name, kind, lat, lon, population)
        self.node_places = []
        self.extra_places = []  # way/relation candidates for city/town

        # street raw segments: name -> list of (sum_lat, sum_lon, n, mid_lat, mid_lon)
        # kept as a flat list, grouped in post-processing.
        self.street_segments = []  # (name, sum_lat, sum_lon, node_count, mid_lat, mid_lon)

        # addresses: (street_name, addr_city_or_none, housenumber, lat, lon, postcode)
        self.addresses = []

        self.stats = {"nodes": 0, "ways": 0, "relations": 0}

    # -- nodes ---------------------------------------------------------
    def node(self, n):
        self.stats["nodes"] += 1
        tags = n.tags
        place = tags.get("place")
        if place in PLACE_KINDS and tags.get("name"):
            self.node_places.append((
                tags.get("name"), place, n.location.lat, n.location.lon,
                _parse_population(tags.get("population")),
            ))
            return  # a place node is not also an address point in practice
        street = tags.get("addr:street")
        hn = tags.get("addr:housenumber")
        if street and hn:
            self.addresses.append((
                street, tags.get("addr:city"), hn,
                n.location.lat, n.location.lon, tags.get("addr:postcode"),
            ))

    # -- ways ------------------------------------------------------------
    def way(self, w):
        self.stats["ways"] += 1
        locs = [(nd.location.lat, nd.location.lon) for nd in w.nodes if nd.location.valid()]
        if not locs:
            return  # geometry unresolved (node outside this extract) -- skip
        sum_lat = sum(p[0] for p in locs)
        sum_lon = sum(p[1] for p in locs)
        n = len(locs)
        mid_lat, mid_lon = sum_lat / n, sum_lon / n

        if w.id in self.needed_way_ids:
            self._way_centroid[w.id] = (mid_lat, mid_lon)

        tags = w.tags
        place = tags.get("place")
        if place in CITY_TOWN and tags.get("name"):
            self.extra_places.append((
                tags.get("name"), place, mid_lat, mid_lon,
                _parse_population(tags.get("population")),
            ))

        street = tags.get("addr:street")
        hn = tags.get("addr:housenumber")
        if street and hn:
            self.addresses.append((
                street, tags.get("addr:city"), hn, mid_lat, mid_lon,
                tags.get("addr:postcode"),
            ))

        highway = tags.get("highway")
        name = tags.get("name")
        if highway and name and highway not in _EXCLUDED_HIGHWAY:
            self.street_segments.append((name, sum_lat, sum_lon, n, mid_lat, mid_lon))

    # -- relations ---------------------------------------------------------
    def relation(self, r):
        self.stats["relations"] += 1
        place_entry = self.place_relations.get(r.id)
        if place_entry is not None:
            name, way_id = place_entry
            centroid = self._way_centroid.get(way_id)
            if centroid is not None:
                self.extra_places.append((
                    name, dict(r.tags).get("place"), centroid[0], centroid[1],
                    _parse_population(r.tags.get("population")),
                ))
        addr_entry = self.address_relations.get(r.id)
        if addr_entry is not None:
            tags, way_id = addr_entry
            centroid = self._way_centroid.get(way_id)
            if centroid is not None:
                self.addresses.append((
                    tags.get("addr:street"), tags.get("addr:city"),
                    tags.get("addr:housenumber"), centroid[0], centroid[1],
                    tags.get("addr:postcode"),
                ))


def _parse_population(raw):
    if not raw:
        return None
    digits = re.sub(r"[^\d]", "", raw)
    if not digits:
        return None
    try:
        return int(digits)
    except ValueError:
        return None


# ---------------------------------------------------------------------------
# Post-processing: candidates -> final rows
# ---------------------------------------------------------------------------

def build_places_table(node_places, extra_places):
    """node-based places win outright; a way/relation city/town candidate is
    only added when no same-named node place sits within 5 km (Auftrag)."""
    places = []  # (name, norm, kind, lat, lon, population)
    node_norms = []  # (norm, lat, lon) for the 5 km same-name check
    for name, kind, lat, lon, pop in node_places:
        places.append((name, normalise(name), kind, lat, lon, pop))
        node_norms.append((normalise(name), lat, lon))

    for name, kind, lat, lon, pop in extra_places:
        norm = normalise(name)
        duplicate = any(
            n == norm and haversine_m(lat, lon, la, lo) <= CENTROID_SAME_NAME_RADIUS_M
            for n, la, lo in node_norms
        )
        if duplicate:
            continue
        places.append((name, norm, kind, lat, lon, pop))
        node_norms.append((norm, lat, lon))  # so two extra-candidates for the
        # same relation/way pair (rare) also dedupe against each other

    return places


def assign_parents(places):
    """parent_id (1-based row index into `places`) = nearest city/town, via
    the coarse grid (Auftrag: 'pragmatisch ueber Gitter-Suche'). City/town
    rows get parent_id = NULL -- they are the anchor, not a child."""
    anchors = [(i, lat, lon) for i, (_, _, kind, lat, lon, _) in enumerate(places) if kind in CITY_TOWN]
    grid = Grid()
    for i, lat, lon in anchors:
        grid.add(i, lat, lon)

    parent_id = [None] * len(places)
    for i, (_, _, kind, lat, lon, _) in enumerate(places):
        if kind in CITY_TOWN:
            continue
        best, best_d = None, None
        rings = 1
        while best is None and rings <= 20:
            for j in grid.nearby(lat, lon, rings=rings):
                d = haversine_m(lat, lon, places[j][3], places[j][4])
                if best_d is None or d < best_d:
                    best, best_d = j, d
            rings *= 2
        parent_id[i] = (best + 1) if best is not None else None
    return parent_id


def build_streets_and_addresses(street_segments, addresses, places):
    """Group way segments into one row per (norm(name), place); resolve each
    address's street via (norm(addr:street), place) with addr:city preferred
    over the nearest-place fallback."""
    place_grid = Grid()
    for i, (_, _, _, lat, lon, _) in enumerate(places):
        place_grid.add(i, lat, lon)
    place_norm_index = {}
    for i, (_, norm, _, lat, lon, _) in enumerate(places):
        place_norm_index.setdefault(norm, []).append(i)

    def nearest_place(lat, lon):
        rings = 1
        while rings <= 20:
            candidates = place_grid.nearby(lat, lon, rings=rings)
            if candidates:
                return min(candidates, key=lambda i: haversine_m(lat, lon, places[i][3], places[i][4]))
            rings *= 2
        return None

    def resolve_place_by_name(name_text, near_lat, near_lon):
        norm = normalise(name_text)
        candidates = place_norm_index.get(norm)
        if not candidates:
            return None
        return min(candidates, key=lambda i: haversine_m(near_lat, near_lon, places[i][3], places[i][4]))

    # Index addresses by (norm(addr:street), grid cell) so a street segment
    # only ever scans addresses near IT, not every address sharing its name
    # anywhere in the region -- a common name like "Hauptstrasse" can have
    # thousands of addresses state-wide, and a flat per-norm list would make
    # every one of its (equally numerous) way segments scan all of them.
    # STREET_ADDR_MATCH_RADIUS_M (5 km) fits inside one ring of
    # GRID_CELL_DEG (~11 km) cells, so ring=1 cannot miss a real match.
    addr_index = {}  # (norm, grid_key) -> [(addr_city, lat, lon), ...]
    for street, addr_city, hn, lat, lon, postcode in addresses:
        norm = normalise(street)
        addr_index.setdefault((norm, grid_key(lat, lon)), []).append((addr_city, lat, lon))

    def nearby_addr_entries(norm, lat, lon):
        gy, gx = grid_key(lat, lon)
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                yield from addr_index.get((norm, (gy + dy, gx + dx)), ())

    # name-norm -> {place_id: [sum_lat, sum_lon, count]}
    groups = {}
    for name, sum_lat, sum_lon, n, mid_lat, mid_lon in street_segments:
        norm = normalise(name)
        place_id = None
        for addr_city, alat, alon in nearby_addr_entries(norm, mid_lat, mid_lon):
            if haversine_m(mid_lat, mid_lon, alat, alon) > STREET_ADDR_MATCH_RADIUS_M:
                continue
            if addr_city:
                place_id = resolve_place_by_name(addr_city, mid_lat, mid_lon)
                if place_id is not None:
                    break
        if place_id is None:
            place_id = nearest_place(mid_lat, mid_lon)
        key = (norm, place_id)
        bucket = groups.setdefault(key, [name, 0.0, 0.0, 0])
        bucket[1] += sum_lat
        bucket[2] += sum_lon
        bucket[3] += n

    streets = []  # (name, norm, place_id(1-based or None), lat, lon)
    street_lookup = {}  # (norm, place_id) -> street row index (1-based)
    # norm -> [street row index, ...] -- the fallback below ("any street with
    # this name") must not degrade to an O(len(street_lookup)) scan per miss;
    # at Niedersachsen scale (hundreds of thousands of streets) that turns a
    # few percent of unmatched addresses into a multi-hour run.
    street_ids_by_norm = {}
    for (norm, place_id), (name, sum_lat, sum_lon, n) in groups.items():
        lat, lon = sum_lat / n, sum_lon / n
        streets.append((name, norm, (place_id + 1) if place_id is not None else None, lat, lon))
        sid = len(streets)  # 1-based id
        street_lookup[(norm, place_id)] = sid
        street_ids_by_norm.setdefault(norm, []).append(sid)

    address_rows = []  # (street_id, housenumber, hn_norm, lat, lon, postcode)
    unmatched = 0
    for street, addr_city, hn, lat, lon, postcode in addresses:
        norm = normalise(street)
        place_id = None
        if addr_city:
            place_id = resolve_place_by_name(addr_city, lat, lon)
        if place_id is None:
            place_id = nearest_place(lat, lon)
        street_id = street_lookup.get((norm, place_id))
        if street_id is None:
            # fall back to any street with this name regardless of place --
            # better than dropping the address outright.
            candidates = street_ids_by_norm.get(norm)
            street_id = candidates[0] if candidates else None
        if street_id is None:
            unmatched += 1
            continue
        address_rows.append((street_id, hn, hn_norm(hn), lat, lon, postcode))

    return streets, address_rows, unmatched


# ---------------------------------------------------------------------------
# SQLite
# ---------------------------------------------------------------------------

def to_e6(value):
    return None if value is None else int(round(value * 1_000_000))


def write_sqlite(path, places, parent_ids, streets, address_rows, region_id):
    if os.path.exists(path):
        os.remove(path)
    con = sqlite3.connect(path)
    cur = con.cursor()
    cur.executescript("""
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous = OFF;

        -- Praefix-Suche: WHERE norm >= ? AND norm < ? (Bereichsscan ueber den
        -- norm-Index) statt LIKE 'x%' -- SQLite kann einen Bereichsscan mit
        -- dem Index bedienen, LIKE mit Platzhalter am Ende zwar theoretisch
        -- auch (Praefix-Optimierung), aber der explizite Bereich ist fuer die
        -- App-Seite (6.4b) eindeutig und unabhaengig von PRAGMA
        -- case_sensitive_like. Obergrenze: '?' + '\\uffff' an den Praefix
        -- angehaengt.
        CREATE TABLE places (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            norm TEXT NOT NULL,
            kind TEXT NOT NULL,
            lat INTEGER NOT NULL,   -- 1e-6 Grad
            lon INTEGER NOT NULL,   -- 1e-6 Grad
            population INTEGER,
            parent_id INTEGER REFERENCES places(id)
        );
        CREATE INDEX idx_places_norm ON places(norm);

        CREATE TABLE streets (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            norm TEXT NOT NULL,
            place_id INTEGER REFERENCES places(id),
            lat INTEGER NOT NULL,
            lon INTEGER NOT NULL
        );
        CREATE INDEX idx_streets_norm ON streets(norm);
        CREATE INDEX idx_streets_place ON streets(place_id);

        CREATE TABLE addresses (
            street_id INTEGER NOT NULL REFERENCES streets(id),
            housenumber TEXT NOT NULL,
            hn_norm TEXT NOT NULL,   -- housenumber, lowercased, no spaces
            lat INTEGER NOT NULL,
            lon INTEGER NOT NULL,
            postcode TEXT
        );
        CREATE INDEX idx_addresses_street_hn ON addresses(street_id, hn_norm);

        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
    """)

    cur.executemany(
        "INSERT INTO places(id, name, norm, kind, lat, lon, population, parent_id) "
        "VALUES (?,?,?,?,?,?,?,?)",
        [
            (i + 1, name, norm, kind, to_e6(lat), to_e6(lon), pop, parent_ids[i])
            for i, (name, norm, kind, lat, lon, pop) in enumerate(places)
        ],
    )
    cur.executemany(
        "INSERT INTO streets(id, name, norm, place_id, lat, lon) VALUES (?,?,?,?,?,?)",
        [
            (i + 1, name, norm, place_id, to_e6(lat), to_e6(lon))
            for i, (name, norm, place_id, lat, lon) in enumerate(streets)
        ],
    )
    cur.executemany(
        "INSERT INTO addresses(street_id, housenumber, hn_norm, lat, lon, postcode) "
        "VALUES (?,?,?,?,?,?)",
        [
            (street_id, hn, hnn, to_e6(lat), to_e6(lon), postcode)
            for street_id, hn, hnn, lat, lon, postcode in address_rows
        ],
    )
    cur.executemany(
        "INSERT INTO meta(key, value) VALUES (?,?)",
        [
            ("schema", "1"),
            ("region", region_id or ""),
            ("built", time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())),
            ("normalisation", "Place.normalise v1"),
        ],
    )
    con.commit()
    cur.execute("VACUUM")
    con.commit()
    con.close()


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 1
    src, dst = argv[1], argv[2]
    region_id = None
    if "--region-id" in argv:
        region_id = argv[argv.index("--region-id") + 1]
    elif len(argv) >= 4 and not argv[3].startswith("--"):
        region_id = argv[3]

    t0 = time.time()
    scan = RelationScan()
    scan.apply_file(src)
    t1 = time.time()
    print("build_places: Pass 1 (Relationen) %.1fs -- %d Orts-, %d Adress-Relationen, %d benoetigte Ways"
          % (t1 - t0, len(scan.place_relations), len(scan.address_relations), len(scan.needed_way_ids)))

    main_collector = MainCollector(scan.needed_way_ids, scan.place_relations, scan.address_relations)
    # idx='flex_mem' haelt alle Node-Koordinaten im RAM (schnell, reicht fuer
    # Bundeslaender bis ~Niedersachsen-Groesse auf einem 7-GB-Runner -- siehe
    # 1.Doku/Ortssuche.md fuer die gemessene Spitzenlast). Ein groesserer
    # Extrakt braeuchte 'sparse_file_array,<pfad>' (Platte statt RAM).
    main_collector.apply_file(src, locations=True, idx="flex_mem")
    t2 = time.time()
    print("build_places: Pass 2 (Haupt) %.1fs -- %d Nodes, %d Ways, %d Relationen; "
          "%d Orts-Nodes, %d Orts-Kandidaten (way/rel), %d Strassenabschnitte, %d Adressen"
          % (t2 - t1, main_collector.stats["nodes"], main_collector.stats["ways"],
             main_collector.stats["relations"], len(main_collector.node_places),
             len(main_collector.extra_places), len(main_collector.street_segments),
             len(main_collector.addresses)))

    places = build_places_table(main_collector.node_places, main_collector.extra_places)
    parent_ids = assign_parents(places)
    streets, address_rows, unmatched = build_streets_and_addresses(
        main_collector.street_segments, main_collector.addresses, places,
    )
    t3 = time.time()
    print("build_places: Nachbearbeitung %.1fs -- %d Orte, %d Strassen, %d Adressen (%d ohne Strassen-Treffer verworfen)"
          % (t3 - t2, len(places), len(streets), len(address_rows), unmatched))

    write_sqlite(dst, places, parent_ids, streets, address_rows, region_id)
    t4 = time.time()
    size_mb = os.path.getsize(dst) / 1_048_576.0
    print("build_places: %s -> %s (%.1f MB) in %.1fs gesamt"
          % (src, dst, size_mb, t4 - t0))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
