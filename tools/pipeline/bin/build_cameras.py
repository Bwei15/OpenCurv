#!/usr/bin/env python3
"""
build_cameras.py -- extracts stationary speed cameras from a region's OSM
extract into the TSV format the app reads
(app/src/main/java/com/motoroute/data/cameras/SpeedCameraRepository.kt).

Two OSM sources, both flattened to the same row (see 1.Doku/Blitzer.md for
what each field means and why the app trusts it the way it does):

  - plain `highway=speed_camera` nodes - by far the common case.
  - `enforcement=maxspeed` relations, whose `role=device` member node is the
    physical camera; the relation itself often carries the `maxspeed` the
    plain node tag does not.

Uses pyosmium (the `osmium` Python package), the same tool
`tools/pipeline/bin/apply_scores.py` already uses and the same one the
"Werkzeuge" step in `.github/workflows/opencurv-data.yml` already installs -
no extra dependency, and no shelling out to the osmium-tool CLI.

The file is read twice: once to collect `enforcement=maxspeed` relations
(rare, cheap), once for nodes. Both source kinds need a *node's* own
coordinate, and pyosmium hands that over for free on every node it sees - no
separate location index is needed the way a way's geometry would need one.

Output: `<out>.cameras.tsv`, UTF-8, one line per camera, latitude first:

    lat<TAB>lon<TAB>direction<TAB>maxspeed<TAB>name

`direction` is OSM's `direction` tag in degrees, 0 = north (empty when
absent - most cameras do not have one; see 1.Doku/Blitzer.md for what that
means for the on-device warning rule). `maxspeed` is numeric km/h (mph values
are converted; anything else unparseable is left empty). `name` is whatever
`name` tag the node or its relation carries, tab/newline-stripped - empty if
none.

Usage:
    build_cameras.py <in.osm.pbf> <out.cameras.tsv>
"""

import re
import sys

import osmium

_MPH_RE = re.compile(r"^(\d+)\s*mph$")
_KMH_RE = re.compile(r"^(\d+)(\s*km/?h)?$")

_COMPASS_DEGREES = {
    "N": 0, "NNE": 22, "NE": 45, "ENE": 67,
    "E": 90, "ESE": 112, "SE": 135, "SSE": 157,
    "S": 180, "SSW": 202, "SW": 225, "WSW": 247,
    "W": 270, "WNW": 292, "NW": 315, "NNW": 337,
}


def parse_maxspeed(raw):
    """'50', '50 km/h', '30 mph' -> km/h int. Anything else (e.g. 'none', 'walk') -> None."""
    if not raw:
        return None
    raw = raw.strip().lower()
    m = _MPH_RE.match(raw)
    if m:
        return round(int(m.group(1)) * 1.60934)
    m = _KMH_RE.match(raw)
    if m:
        return int(m.group(1))
    return None


def parse_direction(raw):
    """A bearing in degrees, or the 8/16-point compass OSM also allows for `direction`."""
    if not raw:
        return None
    raw = raw.strip()
    if raw.upper() in _COMPASS_DEGREES:
        return _COMPASS_DEGREES[raw.upper()]
    try:
        return int(round(float(raw) % 360))
    except ValueError:
        return None


def clean_name(raw):
    if not raw:
        return ""
    return raw.replace("\t", " ").replace("\n", " ").replace("\r", " ").strip()


class DeviceCollector(osmium.SimpleHandler):
    """Pass 1: node id -> the tags of the enforcement=maxspeed relation it is the `device` member of."""

    def __init__(self):
        super().__init__()
        self.device_tags = {}

    def relation(self, r):
        if r.tags.get("enforcement") != "maxspeed":
            return
        device_ids = [m.ref for m in r.members if m.type == "n" and m.role == "device"]
        if not device_ids:
            return
        rel_tags = dict(r.tags)
        for nid in device_ids:
            self.device_tags[nid] = rel_tags


class CameraCollector(osmium.SimpleHandler):
    """Pass 2: one row per camera node - plain highway=speed_camera, or a relation's device node."""

    def __init__(self, device_tags):
        super().__init__()
        self.device_tags = device_tags
        self.rows = []
        self._seen = set()

    def node(self, n):
        tags = dict(n.tags)
        relation_tags = self.device_tags.get(n.id)
        is_plain_camera = tags.get("highway") == "speed_camera"
        if not is_plain_camera and relation_tags is None:
            return
        if n.id in self._seen:
            return
        self._seen.add(n.id)

        # The node's own tags win over the relation's - a node that is both a
        # plain speed_camera AND a relation's device may disagree, and the
        # node is the more specific source for its own attributes.
        merged = dict(relation_tags or {})
        merged.update(tags)

        self.rows.append((
            n.location.lat,
            n.location.lon,
            parse_direction(merged.get("direction")),
            parse_maxspeed(merged.get("maxspeed")),
            clean_name(merged.get("name")),
        ))


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 1
    src, dst = argv[1], argv[2]

    devices = DeviceCollector()
    devices.apply_file(src)

    cameras = CameraCollector(devices.device_tags)
    cameras.apply_file(src)

    rows = sorted(cameras.rows, key=lambda row: (row[0], row[1]))

    with open(dst, "w", encoding="utf-8") as fh:
        fh.write(
            "# source: OpenStreetMap contributors via Geofabrik, "
            "generated by tools/pipeline/bin/build_cameras.py, license ODbL\n",
        )
        for lat, lon, direction, maxspeed, name in rows:
            fh.write("%.7f\t%.7f\t%s\t%s\t%s\n" % (
                lat, lon,
                "" if direction is None else direction,
                "" if maxspeed is None else maxspeed,
                name,
            ))

    with_direction = sum(1 for r in rows if r[2] is not None)
    with_maxspeed = sum(1 for r in rows if r[3] is not None)
    print(
        "build_cameras: %s -> %s  (%d cameras, %d with direction, %d with maxspeed)"
        % (src, dst, len(rows), with_direction, with_maxspeed),
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
