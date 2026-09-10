#!/usr/bin/env bash
# ===========================================================================
# verify_region.sh -- Abnahme einer fertig gebauten Region.
#
# Eine Kachel gilt erst dann als gut, wenn in ihr wirklich gefahren werden
# kann UND der Kurven-Score dabei sichtbar wirkt. Genau das prueft dieses
# Skript, bevor irgendetwas veroeffentlicht wird:
#
#   1. Sind ueberhaupt .rd5-Dateien da und plausibel gross?
#   2. Steht opencurv:curve in der lookups.dat?
#   3. Faehrt eine echte Route durch die Kacheln? (neutrales Profil)
#   4. Traegt die Route den Score, aus der .rd5 DEKODIERT?
#   5. Aendert sich der mittlere Score messbar, wenn das Profil ihn
#      belohnt statt bestraft? -> der Score wirkt.
#   6. Wurden Hoehen aufgepraegt? (nur wenn mit DEM gebaut)
#
# Der Startpunkt der Probefahrt wird aus der bbox der Region abgeleitet;
# alternativ ausdruecklich per --waypoints setzen.
#
# Aufruf:
#   verify_region.sh <rd5-dir> <region-id> [--waypoints lon1 lat1 lon2 lat2]
#                    [--expect-elevation]
# ===========================================================================
set -euo pipefail

RD5DIR="${1:?rd5-Verzeichnis fehlt}"
REGION="${2:?Region-ID fehlt}"
shift 2

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
PY="${OPENCURV_PYTHON:-python3}"
JAR="${OPENCURV_BROUTER_JAR:?OPENCURV_BROUTER_JAR muss gesetzt sein}"
WORK="${OPENCURV_VERIFY_WORK:-${TMPDIR:-/tmp}/opencurv-verify-$REGION}"
EXPECT_ELE=0
WP=""

while [ $# -gt 0 ]; do
  case "$1" in
    --waypoints) WP="$2 $3 $4 $5"; shift 5 ;;
    --expect-elevation) EXPECT_ELE=1; shift ;;
    *) echo "unbekannte Option: $1" >&2; exit 1 ;;
  esac
done

fail() { echo "ABNAHME FEHLGESCHLAGEN ($REGION): $*" >&2; exit 1; }

# --- 1) Dateien vorhanden? -------------------------------------------------
N=$(ls -1 "$RD5DIR"/*.rd5 2>/dev/null | wc -l | tr -d ' ')
[ "$N" -gt 0 ] || fail "keine .rd5-Datei in $RD5DIR"
for f in "$RD5DIR"/*.rd5; do
  s=$(wc -c < "$f")
  [ "$s" -gt 10000 ] || fail "$(basename "$f") ist nur $s Bytes gross -- das ist keine brauchbare Kachel"
done
echo "ok: $N .rd5-Kachel(n)"

# --- 2) lookups.dat ---------------------------------------------------------
LOOK="$RD5DIR/lookups.dat"
[ -f "$LOOK" ] || fail "lookups.dat fehlt neben den Kacheln -- ohne sie kann kein Profil den Score lesen"
grep -q '^opencurv:curve;' "$LOOK" || fail "opencurv:curve steht nicht in $LOOK"
echo "ok: lookups.dat enthaelt opencurv:curve ($(grep -c '^---' "$LOOK") Steuerzeilen)"

# --- Wegpunkte bestimmen ----------------------------------------------------
if [ -z "$WP" ]; then
  WP=$(OPENCURV_PIPELINE_BIN="${OPENCURV_PIPELINE_BIN:-$HERE}" "$PY" - "$REGION" <<'PYEOF'
import sys, os
sys.path.insert(0, os.environ["OPENCURV_PIPELINE_BIN"])
import regions as R
cfg = R.load(); r = R.by_id(cfg, sys.argv[1])
bb = r.get("bbox")
if not bb:
    raise SystemExit("FEHLER: keine bbox fuer %s -- --waypoints angeben" % sys.argv[1])
# zwei Punkte im inneren Drittel, diagonal -- weit genug fuer eine echte
# Route, weit genug vom Rand fuer garantierte Datenabdeckung.
lon0 = bb[0] + (bb[2]-bb[0])*0.35; lat0 = bb[1] + (bb[3]-bb[1])*0.35
lon1 = bb[0] + (bb[2]-bb[0])*0.60; lat1 = bb[1] + (bb[3]-bb[1])*0.60
print("%.5f %.5f %.5f %.5f" % (lon0, lat0, lon1, lat1))
PYEOF
)
fi
echo "Probefahrt: $WP"

# --- Profile bauen ----------------------------------------------------------
mkdir -p "$WORK/profiles" "$WORK/classes"
cp "$LOOK" "$WORK/profiles/lookups.dat"
mk() { sed "s|@@CURVECOST@@|$2|" "$REPO/tools/pipeline/profiles/opencurv_check.brf" > "$WORK/profiles/$1.brf"; }
mk vfy_neutral "1.0"
# ACHTUNG: beide Varianten pruefen erst die Existenz des Tags. Ohne diese
# Pruefung liefert v:opencurv:curve NaN und das ganze Netz gilt als
# unbefahrbar -- siehe 1.Doku/RD5_Pipeline.md, 5.4(b).
mk vfy_seek  "switch not opencurv:curve=  max 0.2 sub 1.0 multiply 0.053 v:opencurv:curve  1.0"
mk vfy_avoid "switch not opencurv:curve=  add 1.0 multiply 0.10 v:opencurv:curve  1.0"

javac -nowarn -cp "$JAR" -d "$WORK/classes" "$REPO/tools/pipeline/bin/RouteCheck.java" \
  || fail "RouteCheck liess sich nicht uebersetzen"

run() { java -cp "$JAR:$WORK/classes" RouteCheck "$RD5DIR" "$WORK/profiles" "$1" $WP; }

# --- 3) faehrt ueberhaupt eine Route? ---------------------------------------
OUT_N=$(run vfy_neutral) || fail "das neutrale Profil findet keine Route: $OUT_N"
echo "$OUT_N"

# --- 4/5) wirkt der Score? --------------------------------------------------
OUT_S=$(run vfy_seek)  || fail "das kurvensuchende Profil findet keine Route: $OUT_S"
OUT_A=$(run vfy_avoid) || fail "das kurvenmeidende Profil findet keine Route: $OUT_A"
echo "$OUT_S"
echo "$OUT_A"

getval() { echo "$1" | tr ',' '.' | sed -n "s/.*$2=\([-0-9.]*\).*/\1/p"; }

COV=$(getval "$OUT_S" SCORED)
SEEK=$(getval "$OUT_S" MEANSCORE)
AVOID=$(getval "$OUT_A" MEANSCORE)

"$PY" - "$COV" "$SEEK" "$AVOID" <<'PYEOF'
import sys
cov, seek, avoid = (float(x) for x in sys.argv[1:4])
if cov < 90.0:
    raise SystemExit("ABNAHME FEHLGESCHLAGEN: nur %.1f %% der Route tragen "
                     "opencurv:curve. Erwartet sind >= 90 %%; sonst laufen "
                     "Profile spaeter in die NaN-Falle." % cov)
if not seek > avoid:
    raise SystemExit("ABNAHME FEHLGESCHLAGEN: der Score wirkt nicht. "
                     "kurvensuchend=%.2f, kurvenmeidend=%.2f -- die beiden "
                     "muessen sich unterscheiden, sonst steht der Tag zwar in "
                     "der Kachel, steuert aber nichts." % (seek, avoid))
print("ok: Score wirkt. Mittlerer Score kurvensuchend=%.2f gegen "
      "kurvenmeidend=%.2f (Faktor %.2f), Abdeckung %.1f %%"
      % (seek, avoid, seek / avoid if avoid else float("inf"), cov))
PYEOF

# --- 6) Hoehen --------------------------------------------------------------
if [ "$EXPECT_ELE" = "1" ]; then
  ELE=$(echo "$OUT_N" | sed -n 's/.*ELE=\[\([^]]*\)\].*/\1/p')
  # RouteCheck schreibt "-..-", wenn kein einziger Knoten der Route eine
  # Hoehe traegt.
  if [ "$ELE" = "-..-" ] || [ -z "$ELE" ]; then
    fail "keine Hoehenwerte in der Route -- lief PosUnifier wirklich gegen ein .bef-Verzeichnis?"
  fi
  echo "ok: Hoehen aufgepraegt, Spanne der Probefahrt ${ELE} m"
fi

echo "ABNAHME BESTANDEN: $REGION"
