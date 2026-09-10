#!/usr/bin/env bash
# ===========================================================================
# run_spike.sh -- Risiko-Spike: traegt ein frei erfundener OSM-Tag durch die
# BRouter-Kachelerzeugung bis in ein .brf-Profil?
#
# Voraussetzungen: bash, git, java 17 (JAVA_HOME oder java im PATH), python3.
# KEIN osmium/osmosis noetig -- die Test-PBF wird selbst geschrieben.
#
# Alles Grosse landet unter $WORK (Default: Scratchpad bzw. /tmp), nichts
# davon im Repository.
#
# Aufruf:  tools/rd5build/run_spike.sh [enum|num]
#   enum : opencurv:curve als 16 diskrete Werte 0..15   (Default)
#   num  : opencurv:curve als Wildcard '*' (numerische Kodierung)
# ===========================================================================
set -euo pipefail

MODE="${1:-enum}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"

WORK="${RD5_SPIKE_WORK:-${TMPDIR:-/tmp}/opencurv-rd5-spike}"
UPSTREAM="${RD5_SPIKE_UPSTREAM:-$WORK/brouter-upstream}"
BROUTER_REF="${RD5_SPIKE_BROUTER_REF:-master}"

log() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

mkdir -p "$WORK"

# --- 1) Upstream-BRouter besorgen (enthaelt btools.mapcreator; das im Repo
#        einvendorte brouter/-Modul enthaelt NUR den Routing-Teil) -----------
if [ ! -d "$UPSTREAM/.git" ]; then
  log "clone brouter upstream -> $UPSTREAM"
  git clone --depth 1 --branch "$BROUTER_REF" https://github.com/abrensch/brouter.git "$UPSTREAM"
fi

JAR="$(ls "$UPSTREAM"/brouter-server/build/libs/brouter-*-all.jar 2>/dev/null | head -1 || true)"
if [ -z "$JAR" ]; then
  log "build brouter fat jar (mapcreator + server)"
  ( cd "$UPSTREAM" && ./gradlew :brouter-server:fatJar --console=plain -q )
  JAR="$(ls "$UPSTREAM"/brouter-server/build/libs/brouter-*-all.jar | head -1)"
fi
echo "jar: $JAR"

# --- 2) Pruefstand erzeugen -------------------------------------------------
log "generate testbench PBF"
python3 "$HERE/make_testbench_pbf.py" "$WORK/testbench.osm.pbf" "$WORK/testbench.osm"
python3 "$HERE/make_testbench_pbf.py" "$WORK/testbench_notag.osm.pbf" --no-tag

# --- 3) lookups.dat erweitern ----------------------------------------------
log "patch lookups.dat (mode=$MODE)"
SRC_LOOKUPS="$REPO/app/src/main/assets/profiles/lookups.dat"
python3 "$HERE/patch_lookups.py" "$SRC_LOOKUPS" "$WORK/lookups.dat" --mode "$MODE"
echo "--- angehaengter Block ---"
grep -n -A 20 "OpenCurv:" "$WORK/lookups.dat" | head -25
echo "--- Versionen ---"
head -3 "$WORK/lookups.dat" | grep -- "---"

# --- 4) Profile bereitstellen ----------------------------------------------
log "prepare profiles"
PROF="$WORK/profiles"
rm -rf "$PROF"; mkdir -p "$PROF"
cp "$UPSTREAM"/misc/profiles2/all.brf "$PROF/"
cp "$UPSTREAM"/misc/profiles2/trekking.brf "$PROF/"
cp "$UPSTREAM"/misc/profiles2/softaccess.brf "$PROF/"
cp "$WORK/lookups.dat" "$PROF/lookups.dat"

mkprofile() { # name, curvecost-expression
  sed "s|@@CURVECOST@@|$2|" "$HERE/profiles/spike_base.brf" > "$PROF/$1.brf"
}

# neutral: Tag wird gar nicht gelesen -> Referenzlauf
mkprofile spike_neutral "1.0"

if [ "$MODE" = "enum" ]; then
  # A: hoher Score ist billig  -> sollte die Nordroute (curve=15) nehmen
  mkprofile spike_prefer_high "switch opencurv:curve=15  1.0  9.0"
  # B: niedriger Score ist billig -> sollte die Suedroute (curve=0) nehmen
  mkprofile spike_prefer_low  "switch opencurv:curve=0   1.0  9.0"
  # C: numerischer Zugriff auf einen DISKRETEN Wert (Grenztest)
  mkprofile spike_numeric     "max 0.2 sub 1.0 multiply 0.05 v:opencurv:curve"
else
  mkprofile spike_prefer_high "switch greater v:opencurv:curve 7.5  1.0  9.0"
  mkprofile spike_prefer_low  "switch lesser  v:opencurv:curve 7.5  1.0  9.0"
  mkprofile spike_numeric     "max 0.2 sub 1.0 multiply 0.05 v:opencurv:curve"
fi

# --- 5) Kachelerzeugung -----------------------------------------------------
build_segments() { # $1 = pbf, $2 = out-dir-name
  local PBF="$1" OUT="$WORK/$2"
  rm -rf "$WORK/tmp" "$OUT"
  mkdir -p "$WORK/tmp"/{nodetiles,waytiles,waytiles55,nodes55,unodes55,segments}
  ( cd "$WORK/tmp" && \
    java -Xmx1500M -cp "$JAR" -DuseDenseMaps=false -DavoidMapPolling=true btools.mapcreator.OsmFastCutter \
      "$WORK/lookups.dat" nodetiles waytiles nodes55 waytiles55 \
      bordernids.dat relations.dat restrictions.dat \
      "$PROF/all.brf" "$PROF/trekking.brf" "$PROF/softaccess.brf" "$PBF" )
  ( cd "$WORK/tmp" && \
    java -Xmx1500M -cp "$JAR" btools.mapcreator.PosUnifier \
      nodes55 unodes55 bordernids.dat bordernodes.dat none )
  ( cd "$WORK/tmp" && \
    java -Xmx1500M -cp "$JAR" -DuseDenseMaps=false btools.mapcreator.WayLinker \
      unodes55 waytiles55 bordernodes.dat restrictions.dat \
      "$WORK/lookups.dat" "$PROF/all.brf" segments rd5 )
  mv "$WORK/tmp/segments" "$OUT"
  ls -l "$OUT"
}

log "build segments WITH opencurv:curve"
build_segments "$WORK/testbench.osm.pbf" "segments"

log "build segments WITHOUT opencurv:curve (Groessenvergleich)"
build_segments "$WORK/testbench_notag.osm.pbf" "segments_notag"

log "rd5 size comparison"
for f in "$WORK"/segments/*.rd5; do
  b="$(basename "$f")"
  s1=$(wc -c < "$f")
  s2=$(wc -c < "$WORK/segments_notag/$b" 2>/dev/null || echo 0)
  echo "$b: mit Tag=$s1 B   ohne Tag=$s2 B   delta=$((s1-s2)) B"
done

# --- 6) Routen und messen ---------------------------------------------------
WP="12.0000 52.0000 12.0200 52.0000"

log "compile RouteProbe against upstream jar"
mkdir -p "$WORK/classes-upstream"
javac -nowarn -cp "$JAR" -d "$WORK/classes-upstream" "$HERE/RouteProbe.java"

log "routing with UPSTREAM brouter (Start 12.0000,52.0000  Ziel 12.0200,52.0000)"
for P in spike_neutral spike_prefer_high spike_prefer_low spike_numeric; do
  java -cp "$JAR:$WORK/classes-upstream" RouteProbe "$WORK/segments" "$PROF" "$P" $WP || true
done

# --- 7) Gegenprobe mit dem im Repo einvendorten brouter/-Modul --------------
log "build vendored OpenCurv brouter module + probe"
VJAR="$REPO/brouter/build/libs/$(ls "$REPO/brouter/build/libs" 2>/dev/null | head -1)"
if [ ! -f "$VJAR" ]; then
  ( cd "$REPO" && ./gradlew :brouter:jar --console=plain -q )
  VJAR="$REPO/brouter/build/libs/$(ls "$REPO/brouter/build/libs" | head -1)"
fi
echo "vendored jar: $VJAR"
mkdir -p "$WORK/classes-vendored"
javac -nowarn -cp "$VJAR" -d "$WORK/classes-vendored" "$HERE/RouteProbe.java"

log "routing with VENDORED OpenCurv brouter (das ist der Code auf dem Handy)"
for P in spike_neutral spike_prefer_high spike_prefer_low spike_numeric; do
  java -cp "$VJAR:$WORK/classes-vendored" RouteProbe "$WORK/segments" "$PROF" "$P" $WP || true
done

log "DONE. Artefakte unter $WORK"
