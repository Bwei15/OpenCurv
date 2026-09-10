#!/usr/bin/env python3
"""
regions.py -- liest tools/pipeline/config/regions.json und beantwortet alles,
was Workflows und Skripte ueber Regionen wissen muessen.

Kein Workflow und kein anderes Skript kennt eine Region beim Namen. Wer eine
Region hinzufuegen will, aendert NUR die JSON-Datei.

Unterkommandos:
    matrix [--only <id|all>]   GitHub-Actions-Matrix als JSON (eine Zeile)
    list                       alle IDs, eine je Zeile
    get <id> <feld>            einen aufgeloesten Feldwert ausgeben
    json <id>                  den vollstaendig aufgeloesten Regionseintrag
    dem-tiles <id>             die benoetigten .bef-Kacheln, je Zeile eine
    release <feld>             Feld aus dem "release"-Block
    check                      Konfiguration validieren (Exit 1 bei Fehler)

Beispiele:
    regions.py matrix --only all
    regions.py get de-by source
    regions.py dem-tiles de-by
"""

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG = os.environ.get(
    "OPENCURV_REGIONS_CONFIG",
    os.path.join(HERE, "..", "config", "regions.json"),
)

REQUIRED = ("id", "name", "source")


def load():
    with open(CONFIG) as fh:
        cfg = json.load(fh)
    defaults = cfg.get("defaults", {})
    out = []
    for r in cfg.get("regions", []):
        merged = dict(defaults)
        merged.update(r)
        out.append(merged)
    cfg["_resolved"] = out
    return cfg


def by_id(cfg, rid):
    for r in cfg["_resolved"]:
        if r["id"] == rid:
            return r
    raise SystemExit("FEHLER: unbekannte Region '%s'. Bekannt: %s"
                     % (rid, ", ".join(x["id"] for x in cfg["_resolved"])))


def bef_tiles(bbox):
    minlon, minlat, maxlon, maxlat = bbox
    out = []
    lon = (int(minlon) // 5) * 5
    while lon <= maxlon:
        lat = (int(minlat) // 5) * 5
        while lat <= maxlat:
            lon_idx = ((lon + 180) // 5) + 1
            lat_idx = (60 - lat) // 5
            n = "srtm_%02d_%02d" % (lon_idx, lat_idx)
            if n not in out:
                out.append(n)
            lat += 5
        lon += 5
    return sorted(out)


def cmd_check(cfg):
    errs = []
    seen = set()
    for r in cfg["_resolved"]:
        for k in REQUIRED:
            if not r.get(k):
                errs.append("Region ohne Pflichtfeld '%s': %r" % (k, r))
        rid = r.get("id", "?")
        if rid in seen:
            errs.append("doppelte Region-ID: %s" % rid)
        seen.add(rid)
        bb = r.get("bbox")
        if bb is not None:
            if len(bb) != 4:
                errs.append("%s: bbox braucht 4 Werte" % rid)
            elif not (bb[0] < bb[2] and bb[1] < bb[3]):
                errs.append("%s: bbox ist verdreht (min >= max)" % rid)
        lv = int(r.get("curveLevels", 16))
        if lv < 2 or lv > 256:
            errs.append("%s: curveLevels muss zwischen 2 und 256 liegen" % rid)
    rel = cfg.get("release", {})
    if int(rel.get("maxAssetBytes", 0)) > 2 * 1024 ** 3:
        errs.append("release.maxAssetBytes ueber dem GitHub-Limit von 2 GiB")
    if errs:
        for e in errs:
            print("KONFIGURATIONSFEHLER: " + e, file=sys.stderr)
        return 1
    print("regions.json ok: %d Regionen, %d davon aktiv"
          % (len(cfg["_resolved"]),
             sum(1 for r in cfg["_resolved"] if r.get("enabled", True))))
    return 0


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 1
    cfg = load()
    cmd = argv[1]

    if cmd == "check":
        return cmd_check(cfg)

    if cmd == "list":
        for r in cfg["_resolved"]:
            print(r["id"])
        return 0

    if cmd == "matrix":
        only = "all"
        if "--only" in argv:
            only = argv[argv.index("--only") + 1]
        if only in ("", "all", "*"):
            rs = [r for r in cfg["_resolved"] if r.get("enabled", True)]
        else:
            rs = [by_id(cfg, only)]
        if not rs:
            raise SystemExit("FEHLER: die Matrix waere leer -- ist jede Region "
                             "auf enabled:false gesetzt?")
        entries = [{
            "id": r["id"],
            "name": r["name"],
            "source": r["source"],
            "runner": r.get("runner", "ubuntu-latest"),
            "curveLevels": r.get("curveLevels", 16),
            "demArcsec": r.get("demArcsec", 1),
            "artifacts": r.get("artifacts", ["rd5"]),
        } for r in rs]
        print(json.dumps({"include": entries}, separators=(",", ":")))
        return 0

    if cmd == "release":
        print(cfg.get("release", {}).get(argv[2], ""))
        return 0

    if cmd in ("get", "json", "dem-tiles"):
        r = by_id(cfg, argv[2])
        if cmd == "json":
            print(json.dumps(r, indent=2, ensure_ascii=False))
        elif cmd == "dem-tiles":
            bb = r.get("bbox")
            if not bb:
                raise SystemExit("FEHLER: %s hat keine bbox in regions.json; "
                                 "ohne bbox kann der DEM-Job die Hoehenkacheln "
                                 "nicht vorab bestimmen." % r["id"])
            for t in bef_tiles(bb):
                print(t)
        else:
            v = r.get(argv[3], "")
            if isinstance(v, (list, dict)):
                v = json.dumps(v, separators=(",", ":"))
            print(v)
        return 0

    print(__doc__)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
