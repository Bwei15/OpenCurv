#!/usr/bin/env python3
"""
fetch_dem.py -- Copernicus DEM GLO-30 holen und in BRouters .bef-Raster giessen.

Warum ueberhaupt ein eigener Schritt?
------------------------------------
BRouters `PosUnifier` praegt Hoehen aus 5 Grad x 5 Grad grossen `.bef`-Rastern
auf. Erzeugt werden die aus 1 Grad x 1 Grad grossen `.hgt`-Kacheln durch
`btools.mapcreator.ElevationRasterTileConverter`. Copernicus DEM GLO-30 liegt
aber als Cloud-Optimized-GeoTIFF im oeffentlichen AWS-S3-Bucket
`copernicus-dem-30m` -- und zwar mit breitenabhaengiger Spaltenzahl (bei 50-60
Grad Nord nur 2400 statt 3600 Spalten). Es braucht also eine Umprojektion auf
das starre 3601 x 3601-Gitter des .hgt-Formats. Das macht `gdal_translate`.

Kette:
    S3 (COG, EPSG:4326)  --gdal_translate-->  N53E008.hgt (3601^2, int16, BE)
                         --ElevationRasterTileConverter-->  srtm_38_02.bef
                         --PosUnifier-->  Hoehen in den .u5d-Knotendateien

Der Bucket ist ohne Anmeldung ueber schlichtes HTTPS erreichbar; es wird KEIN
AWS-Konto und kein `aws`-CLI gebraucht.

Aufruf:
    fetch_dem.py --bbox <minlon> <minlat> <maxlon> <maxlat> \
                 --hgt-dir <dir> --bef-dir <dir> [--arcsec 1|3] [--keep-tif]

    fetch_dem.py --bef-tiles-for-bbox <minlon> <minlat> <maxlon> <maxlat>
        gibt nur die Namen der benoetigten .bef-Kacheln aus (fuer Caching).

Umgebungsvariablen:
    OPENCURV_BROUTER_JAR   Pfad zum Upstream-Fat-Jar (Pflicht ausser bei
                           --bef-tiles-for-bbox)
    OPENCURV_DEM_BUCKET    Default https://copernicus-dem-30m.s3.amazonaws.com
"""

import os
import shutil
import subprocess
import sys
import time

BUCKET = os.environ.get(
    "OPENCURV_DEM_BUCKET", "https://copernicus-dem-30m.s3.amazonaws.com"
)


def cop_url(lat, lon):
    la = "%s%02d" % ("N" if lat >= 0 else "S", abs(lat))
    lo = "%s%03d" % ("E" if lon >= 0 else "W", abs(lon))
    name = "Copernicus_DSM_COG_10_%s_00_%s_00_DEM" % (la, lo)
    return "%s/%s/%s.tif" % (BUCKET, name, name), "%s%s" % (la, lo)


def bef_name(ilon_base, ilat_base):
    """Gleiche Formel wie ElevationRasterTileConverter.genFilenameOld()."""
    lon_idx = ((ilon_base + 180) // 5) + 1
    lat_idx = (60 - ilat_base) // 5
    return "srtm_%02d_%02d" % (lon_idx, lat_idx)


def bef_tiles_for_bbox(minlon, minlat, maxlon, maxlat):
    out = []
    lon = (int(minlon) // 5) * 5
    while lon <= maxlon:
        lat = (int(minlat) // 5) * 5
        while lat <= maxlat:
            n = bef_name(lon, lat)
            if n not in out:
                out.append(n)
            lat += 5
        lon += 5
    return sorted(out)


def bef_origin(name):
    """srtm_38_02 -> (ilon_base, ilat_base) der 5x5-Kachel."""
    _, lon_idx, lat_idx = name.split("_")
    ilon_base = (int(lon_idx) - 1) * 5 - 180
    ilat_base = 150 - int(lat_idx) * 5 - 90
    return ilon_base, ilat_base


def run(cmd, **kw):
    r = subprocess.run(cmd, **kw)
    if r.returncode != 0:
        raise SystemExit("FEHLER: Kommando fehlgeschlagen: %s" % " ".join(cmd))


def download(url, dst):
    # -f: HTTP-Fehler als Exit-Code, damit eine fehlende Kachel nicht als
    #     0-Byte-Datei durchrutscht. --retry gegen S3-Schluckauf.
    r = subprocess.run(
        ["curl", "-sSL", "-f", "--retry", "3", "--retry-delay", "2", "-o", dst, url]
    )
    return r.returncode == 0


def main(argv):
    if "--bef-tiles-for-bbox" in argv:
        i = argv.index("--bef-tiles-for-bbox")
        bb = [float(x) for x in argv[i + 1:i + 5]]
        print("\n".join(bef_tiles_for_bbox(*bb)))
        return 0

    if "--bbox" not in argv:
        print(__doc__)
        return 1
    i = argv.index("--bbox")
    minlon, minlat, maxlon, maxlat = [float(x) for x in argv[i + 1:i + 5]]
    hgt_dir = argv[argv.index("--hgt-dir") + 1]
    bef_dir = argv[argv.index("--bef-dir") + 1]
    arcsec = argv[argv.index("--arcsec") + 1] if "--arcsec" in argv else "1"
    keep_tif = "--keep-tif" in argv

    jar = os.environ.get("OPENCURV_BROUTER_JAR")
    if not jar or not os.path.exists(jar):
        raise SystemExit("FEHLER: OPENCURV_BROUTER_JAR zeigt nicht auf das "
                         "BRouter-Fat-Jar (btools.mapcreator).")
    gdal = shutil.which("gdal_translate")
    if not gdal:
        raise SystemExit("FEHLER: gdal_translate nicht im PATH. "
                         "Ubuntu: apt-get install -y gdal-bin")

    os.makedirs(hgt_dir, exist_ok=True)
    os.makedirs(bef_dir, exist_ok=True)
    tifdir = os.path.join(hgt_dir, ".tif")
    os.makedirs(tifdir, exist_ok=True)

    tiles = bef_tiles_for_bbox(minlon, minlat, maxlon, maxlat)
    print("benoetigte .bef-Kacheln: %s" % ", ".join(tiles))

    # --- Schritt 1: 1x1-Grad-COGs holen und nach .hgt wandeln ---------------
    # Nur die Grad-Kacheln, die die bbox wirklich beruehrt. Der Rest der
    # 5x5-Kachel bleibt NODATA -- das ist zulaessig, PosUnifier liefert dann
    # fuer diese Knoten schlicht keine Hoehe.
    n_ok = n_miss = 0
    bytes_tif = 0
    t0 = time.time()
    for lat in range(int(minlat // 1), int(maxlat // 1) + 1):
        for lon in range(int(minlon // 1), int(maxlon // 1) + 1):
            url, tname = cop_url(lat, lon)
            hgt = os.path.join(hgt_dir, "%s.hgt" % tname)
            if os.path.exists(hgt) and os.path.getsize(hgt) == 3601 * 3601 * 2:
                n_ok += 1
                continue
            tif = os.path.join(tifdir, "%s.tif" % tname)
            if not os.path.exists(tif):
                if not download(url, tif):
                    # Ueber Wasser gibt es schlicht keine Kachel.
                    print("  (keine DEM-Kachel: %s)" % tname)
                    if os.path.exists(tif):
                        os.remove(tif)
                    n_miss += 1
                    continue
            bytes_tif += os.path.getsize(tif)
            run([gdal, "-q", "-of", "SRTMHGT",
                 "-outsize", "3601", "3601",
                 "-r", "bilinear",
                 "-ot", "Int16",
                 "-projwin", str(lon), str(lat + 1), str(lon + 1), str(lat),
                 tif, hgt])
            if not keep_tif:
                os.remove(tif)
            n_ok += 1
    print("hgt: %d Kacheln erzeugt, %d ohne Daten, %.0f MB GeoTIFF geladen, %.0f s"
          % (n_ok, n_miss, bytes_tif / 1048576.0, time.time() - t0))

    # --- Schritt 2: .hgt -> .bef (5x5 Grad) --------------------------------
    t0 = time.time()
    for t in tiles:
        out = os.path.join(bef_dir, t + ".bef")
        if os.path.exists(out) and os.path.getsize(out) > 0:
            print("  %s.bef bereits vorhanden" % t)
            continue
        run(["java", "-Xmx3500M", "-cp", jar,
             "btools.mapcreator.ElevationRasterTileConverter",
             t, hgt_dir, bef_dir, arcsec])
    total = sum(os.path.getsize(os.path.join(bef_dir, f))
                for f in os.listdir(bef_dir) if f.endswith(".bef"))
    print("bef: %d Kacheln, %.0f MB gesamt, %.0f s"
          % (len(tiles), total / 1048576.0, time.time() - t0))

    if not keep_tif and os.path.isdir(tifdir):
        shutil.rmtree(tifdir, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
