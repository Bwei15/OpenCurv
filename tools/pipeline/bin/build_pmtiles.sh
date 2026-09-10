#!/usr/bin/env bash
# ===========================================================================
# build_pmtiles.sh -- Vektorkacheln fuer eine Region, als EINE .pmtiles-Datei.
#
# Warum PMTiles und nicht MBTiles: MapLibre Android kann seit 11.7.0
# `pmtiles://file://...` nativ oeffnen. Eine Datei, kein Kachelserver, kein
# Entpacken auf dem Handy -- genau das, was ein reines Offline-Modell braucht.
#
# Planetiler (Apache-2.0) erzeugt sie direkt aus der .osm.pbf. Wir fuettern
# ihm die BEWERTETE Datei: die Geometrie ist identisch zur Rohdatei, sie hat
# nur einen Tag mehr. Damit muss die Rohdatei nicht bis hierher aufgehoben
# werden -- das spart bei Bayern ueber 800 MB Plattenplatz.
#
# Aufruf: build_pmtiles.sh <in.osm.pbf> <out.pmtiles> [work-dir]
#
# Umgebungsvariablen:
#   PLANETILER_VERSION   Default 'latest'
#   PLANETILER_JAR       vorhandenes Jar benutzen statt herunterzuladen
#   OPENCURV_XMX_TILES   JVM-Heap (Default 4g)
# ===========================================================================
set -euo pipefail

IN="${1:?Eingabe-PBF fehlt}"
OUT="${2:?Ausgabedatei fehlt}"
WORK="${3:-$(dirname "$OUT")/../work/planetiler}"

XMX="${OPENCURV_XMX_TILES:-4g}"
VER="${PLANETILER_VERSION:-latest}"

[ -f "$IN" ] || { echo "FEHLER: Eingabe-PBF nicht gefunden: $IN" >&2; exit 1; }
mkdir -p "$WORK" "$(dirname "$OUT")"

JAR="${PLANETILER_JAR:-$WORK/planetiler.jar}"
if [ ! -f "$JAR" ]; then
  if [ "$VER" = "latest" ]; then
    URL="https://github.com/onthegomap/planetiler/releases/latest/download/planetiler.jar"
  else
    URL="https://github.com/onthegomap/planetiler/releases/download/$VER/planetiler.jar"
  fi
  echo "== Planetiler holen: $URL"
  curl -sSL -f --retry 3 -o "$JAR" "$URL" \
    || { echo "FEHLER: Planetiler-Jar liess sich nicht laden ($URL)" >&2; exit 1; }
fi

DL="${PLANETILER_DOWNLOAD_DIR:-$WORK/sources}"
mkdir -p "$DL" "$WORK/tmp"

echo "== Vektorkacheln bauen: $IN -> $OUT"
# Die Schalter sind gegen `planetiler --help` geprueft, nicht geraten.
#
# --download        holt die regionsunabhaengigen Zusatzdaten des
#                   OpenMapTiles-Schemas. GEMESSEN: allein
#                   water-polygons-split-3857.zip ist rund 950 MB, dazu
#                   Natural Earth und Lake-Centerlines. Deshalb liegen sie in
#                   --download-dir und werden im Workflow GECACHT -- sonst
#                   zahlt jede Region diesen Download erneut.
# --free-*-after-read  loescht die jeweilige Eingabe direkt nach dem Lesen.
#                   Das ist die wichtigste Stellschraube gegen den
#                   Plattenplatz-Engpass des freien Runners. ACHTUNG: es
#                   loescht aus dem Download-Verzeichnis, macht den Cache
#                   also kaputt -- deshalb nur, wenn ausdruecklich verlangt.
# --nodemap-type=sortedtable  haelt den Speicherbedarf klein; sparsearray
#                   waere schneller, braucht aber deutlich mehr RAM.
FREE=""
if [ "${OPENCURV_PLANETILER_FREE_SOURCES:-0}" = "1" ]; then
  FREE="--free-water-polygons-after-read --free-natural-earth-after-read --free-lake-centerlines-after-read"
  echo "   (Plattenplatz-Notmodus: Quelldaten werden nach dem Lesen geloescht)"
fi

# shellcheck disable=SC2086
java "-Xmx$XMX" -jar "$JAR" \
  --osm-path="$IN" \
  --output="$OUT" \
  --force \
  --download \
  --download-dir="$DL" \
  --tmpdir="$WORK/tmp" \
  --nodemap-type=sortedtable \
  --storage=mmap \
  $FREE

# --- Abnahme --------------------------------------------------------------
[ -s "$OUT" ] || { echo "FEHLER: Planetiler hat keine Ausgabedatei erzeugt: $OUT" >&2; exit 1; }
# PMTiles beginnt mit der Signatur "PMTiles" + Version.
head -c 7 "$OUT" | grep -q 'PMTiles' \
  || { echo "FEHLER: $OUT traegt keine PMTiles-Signatur -- die Datei ist unbrauchbar." >&2; exit 1; }
echo "   ok: $(du -h "$OUT" | awk '{print $1}')"

# PLATTENPLATZ: die Zwischenablage von Planetiler ist so gross wie die
# Eingabe oder groesser und wird nach dem Lauf nicht mehr gebraucht.
rm -rf "$WORK/tmp"
