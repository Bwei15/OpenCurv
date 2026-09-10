#!/usr/bin/env bash
# ===========================================================================
# run_limits.sh -- Grenzen ausloten, nachdem run_spike.sh den Beweis erbracht
# hat. Baut ein groesseres Gitternetz und misst, wie stark die .rd5 durch den
# Zusatz-Tag waechst -- getrennt nach
#   (a) diskrete Werte (lookups.dat: opencurv:curve;0 0 .. 15)
#   (b) Wildcard/numerisch (lookups.dat: opencurv:curve;0 *)
# und in Abhaengigkeit von der Anzahl der erlaubten Werte.
#
# Ausserdem: Versionsvertraeglichkeit zwischen der lookups.dat der
# Kachelerzeugung und der des Profils.
#
# Aufruf: tools/rd5build/run_limits.sh
# ===========================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
WORK="${RD5_SPIKE_WORK:-${TMPDIR:-/tmp}/opencurv-rd5-spike}"
UPSTREAM="${RD5_SPIKE_UPSTREAM:-$WORK/brouter-upstream}"
LIM="$WORK/limits"

log() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

if [ ! -d "$WORK/profiles" ] || [ ! -d "$WORK/classes-upstream" ]; then
  echo "Bitte zuerst tools/rd5build/run_spike.sh laufen lassen (gleiches RD5_SPIKE_WORK)." >&2
  exit 1
fi

JAR="$(ls "$UPSTREAM"/brouter-server/build/libs/brouter-*-all.jar | head -1)"
mkdir -p "$LIM"

GRID="${GRID:-40}"   # 40x40 Knoten -> 3120 Wege

segments_for() { # $1 = pbf, $2 = lookups, $3 = out-name
  local PBF="$1" LOOK="$2" OUT="$LIM/$3"
  rm -rf "$LIM/tmp" "$OUT"
  mkdir -p "$LIM/tmp"/{nodetiles,waytiles,waytiles55,nodes55,unodes55,segments}
  ( cd "$LIM/tmp" && \
    java -Xmx2g -cp "$JAR" -DuseDenseMaps=false -DavoidMapPolling=true \
      btools.mapcreator.OsmFastCutter "$LOOK" nodetiles waytiles nodes55 waytiles55 \
      bordernids.dat relations.dat restrictions.dat \
      "$WORK/profiles/all.brf" "$WORK/profiles/trekking.brf" "$WORK/profiles/softaccess.brf" \
      "$PBF" > /dev/null )
  ( cd "$LIM/tmp" && java -Xmx2g -cp "$JAR" btools.mapcreator.PosUnifier \
      nodes55 unodes55 bordernids.dat bordernodes.dat none > /dev/null )
  ( cd "$LIM/tmp" && java -Xmx2g -cp "$JAR" -DuseDenseMaps=false \
      btools.mapcreator.WayLinker unodes55 waytiles55 bordernodes.dat restrictions.dat \
      "$LOOK" "$WORK/profiles/all.brf" segments rd5 > /dev/null )
  mv "$LIM/tmp/segments" "$OUT"
  wc -c < "$OUT"/E10_N50.rd5 | tr -d ' '
}

log "lookups-Varianten erzeugen"
SRC="$REPO/app/src/main/assets/profiles/lookups.dat"
python3 "$HERE/patch_lookups.py" "$SRC" "$LIM/lookups_enum16.dat"  --mode enum --max 15
python3 "$HERE/patch_lookups.py" "$SRC" "$LIM/lookups_enum64.dat"  --mode enum --max 63
python3 "$HERE/patch_lookups.py" "$SRC" "$LIM/lookups_enum256.dat" --mode enum --max 255
python3 "$HERE/patch_lookups.py" "$SRC" "$LIM/lookups_num.dat"     --mode num
cp "$SRC" "$LIM/lookups_orig.dat"

log "Gitternetz erzeugen (GRID=$GRID)"
python3 "$HERE/make_testbench_pbf.py" "$LIM/grid_notag.pbf"  --grid "$GRID" --no-tag
python3 "$HERE/make_testbench_pbf.py" "$LIM/grid_v16.pbf"    --grid "$GRID" --values 16
python3 "$HERE/make_testbench_pbf.py" "$LIM/grid_v64.pbf"    --grid "$GRID" --values 64
python3 "$HERE/make_testbench_pbf.py" "$LIM/grid_v256.pbf"   --grid "$GRID" --values 256
python3 "$HERE/make_testbench_pbf.py" "$LIM/grid_num16.pbf"  --grid "$GRID" --values 16 --scale 1
python3 "$HERE/make_testbench_pbf.py" "$LIM/grid_num001.pbf" --grid "$GRID" --values 16 --scale 0.01

NWAYS=$(python3 - "$GRID" <<'EOF'
import sys
n = int(sys.argv[1]); print(2 * n * (n - 1))
EOF
)
echo "Wege im Gitter: $NWAYS"

log "rd5-Groessen messen"
BASE=$(segments_for "$LIM/grid_notag.pbf"  "$LIM/lookups_enum16.dat"  s_notag)
E16=$(segments_for  "$LIM/grid_v16.pbf"    "$LIM/lookups_enum16.dat"  s_e16)
E64=$(segments_for  "$LIM/grid_v64.pbf"    "$LIM/lookups_enum64.dat"  s_e64)
E256=$(segments_for "$LIM/grid_v256.pbf"   "$LIM/lookups_enum256.dat" s_e256)
N16=$(segments_for  "$LIM/grid_num16.pbf"  "$LIM/lookups_num.dat"     s_n16)
N001=$(segments_for "$LIM/grid_num001.pbf" "$LIM/lookups_num.dat"     s_n001)

printf '\n%-34s %10s %10s %10s\n' "Variante" "rd5 [B]" "delta [B]" "B/Weg"
row() { printf '%-34s %10s %10s %10.3f\n' "$1" "$2" "$(( $2 - BASE ))" "$(python3 -c "print(($2-$BASE)/$NWAYS)")"; }
row "ohne Tag (Referenz)"            "$BASE"
row "enum, 16 Werte (0..15)"         "$E16"
row "enum, 64 Werte (0..63)"         "$E64"
row "enum, 256 Werte (0..255)"       "$E256"
row "wildcard '*', Werte 0..15"      "$N16"
row "wildcard '*', Werte 0.00..0.15" "$N001"

# ---------------------------------------------------------------------------
log "Versionsvertraeglichkeit"

probe() { # $1 = segments, $2 = lookups fuer das Profil, $3 = profil, $4 = label
  local PDIR="$LIM/pdir"; rm -rf "$PDIR"; mkdir -p "$PDIR"
  cp "$2" "$PDIR/lookups.dat"
  cp "$WORK/profiles/$3.brf" "$PDIR/" 2>/dev/null || true
  echo "--- $4"
  java -cp "$JAR:$WORK/classes-upstream" RouteProbe "$1" "$PDIR" "$3" \
    12.0000 52.0000 12.0200 52.0000 2>&1 | head -4 || true
}

# Kacheln MIT Tag (minor 3) gegen Profil-lookups OHNE Tag (minor 2):
probe "$WORK/segments" "$LIM/lookups_orig.dat" spike_neutral \
  "rd5 minor=3 (mit Tag)  <->  Profil-lookups minor=2 (ohne Tag), Profil nutzt Tag NICHT"

# Kacheln OHNE Tag gegen Profil-lookups MIT Tag, Profil liest den Tag:
probe "$WORK/segments_notag" "$LIM/lookups_enum16.dat" spike_prefer_low \
  "rd5 ohne Tag  <->  Profil-lookups mit Tag, Profil liest den Tag"

# Major-Version verbiegen -> muss knallen
sed 's/^---lookupversion:11/---lookupversion:12/' "$LIM/lookups_enum16.dat" > "$LIM/lookups_major12.dat"
probe "$WORK/segments" "$LIM/lookups_major12.dat" spike_prefer_low \
  "Major-Version 12 im Profil vs. 11 in der rd5 -- MUSS fehlschlagen"

log "DONE. Artefakte unter $LIM"
