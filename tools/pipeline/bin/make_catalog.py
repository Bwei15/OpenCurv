#!/usr/bin/env python3
"""
make_catalog.py -- erzeugt catalog.json, die eine Datei, die die App liest.

Der Katalog ist der EINZIGE Einstiegspunkt fuer das Handy. Er sagt, welche
Regionen es gibt, welche Dateien dazugehoeren, wie gross sie sind und welche
Pruefsumme sie haben. Die App muss danach keine Verzeichnisse mehr auflisten
und keine Namen raten.

Aufbau (schemaVersion 1) -- die vollstaendige Beschreibung steht in
1.Doku/Cloud_Pipeline.md, Abschnitt "Die Katalogdatei".

Aufruf:
    make_catalog.py --out catalog.json --release-tag data-20260910 \
                    --repo Bwei15/OpenCurv \
                    --region <id>:<dir> [--region ...] \
                    [--shared <dir>] [--max-bytes N] [--split]

    Jedes --region nennt eine Region-ID aus regions.json und ein Verzeichnis,
    dessen Inhalt zu genau dieser Region gehoert. Der Dateityp wird aus der
    Endung abgeleitet.
"""

import datetime
import hashlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import regions as regions_mod  # noqa: E402

# Endung -> Rolle im Katalog. Die App entscheidet daran, wohin die Datei
# gehoert und was sie damit tut.
KIND_BY_EXT = {
    ".rd5": "routing",       # BRouter-Kachel, direkt nutzbar, NICHT gepackt
    ".pmtiles": "maptiles",  # Vektorkacheln fuer MapLibre
    ".dat": "lookups",       # lookups.dat -- gehoert zu den Profilen
    ".brf": "profile",
    ".json": "metadata",
    ".zip": "bundle",
    ".tsv": "cameras",  # <region-id>.cameras.tsv - see kind_of() for the exact match
    ".sqlite": "places",  # <region-id>.places.sqlite - see kind_of() for the exact match
}

DEFAULT_MAX = 2 * 1024 ** 3  # GitHub-Limit je Release-Asset


def sha256_of(path, bufsize=1 << 20):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        while True:
            b = fh.read(bufsize)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def kind_of(name):
    if name == "lookups.dat":
        return "lookups"
    # Checked before the plain extension map: ".tsv" is only ever a speed
    # camera file today (build_cameras.py's <region-id>.cameras.tsv), but the
    # explicit suffix check keeps that true instead of just assuming it.
    if name.endswith(".cameras.tsv"):
        return "cameras"
    # Same reasoning for the address index (build_places.py's
    # <region-id>.places.sqlite) -- ".sqlite" happens to be unambiguous today
    # too, but an explicit check keeps that true on purpose.
    if name.endswith(".places.sqlite"):
        return "places"
    ext = os.path.splitext(name)[1].lower()
    return KIND_BY_EXT.get(ext, "other")


def split_file(path, max_bytes):
    """Zerlegt eine zu grosse Datei in .partNN-Stuecke und gibt sie zurueck.

    Nur Notnagel: greift erst, wenn eine EINZELNE Datei ueber dem Asset-Limit
    liegt. Die App haengt die Teile in der angegebenen Reihenfolge wieder
    aneinander und prueft die Gesamt-Pruefsumme.
    """
    parts = []
    idx = 1
    with open(path, "rb") as fh:
        while True:
            chunk = fh.read(max_bytes)
            if not chunk:
                break
            pp = "%s.part%02d" % (path, idx)
            with open(pp, "wb") as out:
                out.write(chunk)
            parts.append(pp)
            idx += 1
    return parts


def describe(path, base_url, max_bytes, allow_split):
    size = os.path.getsize(path)
    name = os.path.basename(path)
    entry = {
        "name": name,
        "kind": kind_of(name),
        "bytes": size,
        "sha256": sha256_of(path),
        "url": "%s/%s" % (base_url, name),
    }
    if size > max_bytes:
        if not allow_split:
            raise SystemExit(
                "FEHLER: %s ist %d Bytes gross und reisst das Asset-Limit von "
                "%d Bytes. Entweder --split setzen oder die Region in "
                "regions.json feiner schneiden." % (name, size, max_bytes))
        parts = split_file(path, max_bytes)
        entry["split"] = {
            "parts": [
                {"name": os.path.basename(p),
                 "bytes": os.path.getsize(p),
                 "sha256": sha256_of(p),
                 "url": "%s/%s" % (base_url, os.path.basename(p))}
                for p in parts
            ],
            "note": "Teile in dieser Reihenfolge aneinanderhaengen, dann gegen "
                    "sha256 der Gesamtdatei pruefen.",
        }
        entry.pop("url", None)
        os.remove(path)
    return entry


def main(argv):
    if "--out" not in argv:
        print(__doc__)
        return 1
    out = argv[argv.index("--out") + 1]
    tag = argv[argv.index("--release-tag") + 1] if "--release-tag" in argv else "unreleased"
    repo = argv[argv.index("--repo") + 1] if "--repo" in argv else os.environ.get("GITHUB_REPOSITORY", "")
    max_bytes = int(argv[argv.index("--max-bytes") + 1]) if "--max-bytes" in argv else DEFAULT_MAX
    allow_split = "--split" in argv

    base_url = "https://github.com/%s/releases/download/%s" % (repo, tag) if repo else tag

    pairs = [argv[i + 1] for i, a in enumerate(argv) if a == "--region"]
    shared_dirs = [argv[i + 1] for i, a in enumerate(argv) if a == "--shared"]

    cfg = regions_mod.load()

    catalog = {
        "schemaVersion": 1,
        "generated": datetime.datetime.now(datetime.timezone.utc)
                     .strftime("%Y-%m-%dT%H:%M:%SZ"),
        "release": {"tag": tag, "repo": repo, "baseUrl": base_url},
        "producer": {
            "pipeline": "opencurv/tools/pipeline",
            "curveTag": "opencurv:curve",
            "curveEncoding": "wildcard",
            "curveLevels": int(os.environ.get("OPENCURV_CURVE_LEVELS", "16")),
            "scorer": os.environ.get("OPENCURV_SCORER_ID", "unknown"),
            "brouterUpstream": os.environ.get("OPENCURV_BROUTER_REV", "unknown"),
            "lookupsVersion": os.environ.get("OPENCURV_LOOKUPS_VERSION", "11.3"),
            "demSource": os.environ.get("OPENCURV_DEM_SOURCE", "copernicus-glo30"),
        },
        "notes": {
            "rd5": "Unkomprimiert ausgeliefert. .rd5 ist bereits bitgepackt "
                   "(gemessen: gzip spart 3,7 %). Die App legt die Datei "
                   "direkt ins Segmentverzeichnis, kein Entpacken.",
            "lookups": "lookups.dat gehoert ZWINGEND zu den Profilen und muss "
                       "zusammen mit ihnen aktualisiert werden. Ein Profil, "
                       "das einen Tag nennt, den sein lookups.dat nicht kennt, "
                       "laesst sich nicht parsen.",
            "nan": "Jedes Profil, das v:opencurv:curve benutzt, MUSS vorher "
                   "'switch not opencurv:curve=' pruefen. Fehlt der Tag, "
                   "liefert v: NaN und das gesamte Netz gilt als unbefahrbar.",
        },
        "shared": [],
        "regions": [],
    }

    for d in shared_dirs:
        for name in sorted(os.listdir(d)):
            p = os.path.join(d, name)
            if os.path.isfile(p):
                catalog["shared"].append(describe(p, base_url, max_bytes, allow_split))

    for pair in pairs:
        rid, d = pair.split(":", 1)
        meta = regions_mod.by_id(cfg, rid)
        files = []
        for name in sorted(os.listdir(d)):
            p = os.path.join(d, name)
            if os.path.isfile(p) and not name.endswith(".part"):
                files.append(describe(p, base_url, max_bytes, allow_split))
        total = sum(f.get("bytes", 0) for f in files)
        entry = {
            "id": rid,
            "name": meta["name"],
            "source": {"provider": meta.get("sourceType", "geofabrik"),
                       "path": meta["source"]},
            "totalBytes": total,
            "files": files,
        }
        if meta.get("bbox"):
            entry["bbox"] = meta["bbox"]
        catalog["regions"].append(entry)

    catalog["totalBytes"] = (
        sum(r["totalBytes"] for r in catalog["regions"])
        + sum(f.get("bytes", 0) for f in catalog["shared"])
    )

    with open(out, "w") as fh:
        json.dump(catalog, fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    print("catalog: %s  (%d Regionen, %d gemeinsame Dateien, %.1f MB gesamt)"
          % (out, len(catalog["regions"]), len(catalog["shared"]),
             catalog["totalBytes"] / 1048576.0))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
