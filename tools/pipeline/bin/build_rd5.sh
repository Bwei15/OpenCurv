#!/usr/bin/env bash
# ===========================================================================
# build_rd5.sh -- BRouter-Kachelerzeugung fuer EINE Region, mit
# Plattenplatz-Disziplin und Abnahme nach jeder Stufe.
#
# Setzt direkt auf dem Weg auf, den tools/rd5build/run_spike.sh bewiesen hat:
#     lookups.dat erweitern -> OsmFastCutter -> PosUnifier -> WayLinker
# Neu gegenueber dem Spike ist nur die Betriebsfaehigkeit: Messung, Abnahme,
# Aufraeumen, sprechende Fehler.
#
# Aufruf:
#   build_rd5.sh <scored.osm.pbf> <bef-dir|none> <out-dir> [work-dir]
#
# Umgebungsvariablen:
#   OPENCURV_BROUTER_JAR   Pflicht. Upstream-Fat-Jar mit btools.mapcreator.
#   OPENCURV_XMX           JVM-Heap je Stufe (Default 5500M)
#   OPENCURV_DENSE_MAPS    true|false|auto (Default auto: ab 100 MB PBF true)
#   OPENCURV_KEEP_INPUT    1 = Eingabe-PBF nach dem Cutter NICHT loeschen
#   OPENCURV_STEP_SUMMARY  Datei, in die die Messwerte angehaengt werden
#                          (in Actions: $GITHUB_STEP_SUMMARY)
# ===========================================================================
set -euo pipefail

PBF="${1:?Eingabe-PBF fehlt}"
BEFDIR="${2:-none}"
OUTDIR="${3:?Ausgabeverzeichnis fehlt}"
WORK="${4:-$(dirname "$OUTDIR")/rd5work}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
PY="${OPENCURV_PYTHON:-python3}"
XMX="${OPENCURV_XMX:-5500M}"
SUMMARY="${OPENCURV_STEP_SUMMARY:-}"

JAR="${OPENCURV_BROUTER_JAR:?OPENCURV_BROUTER_JAR muss auf das BRouter-Fat-Jar zeigen}"
[ -f "$JAR" ] || { echo "FEHLER: BRouter-Jar nicht gefunden: $JAR" >&2; exit 1; }
[ -f "$PBF" ] || { echo "FEHLER: Eingabe-PBF nicht gefunden: $PBF" >&2; exit 1; }

# Absolute Pfade herstellen, damit 'cd "$WORK"' spaetere Stufen nicht bricht
PBF="$(cd "$(dirname "$PBF")" && pwd)/$(basename "$PBF")"
JAR="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"

mkdir -p "$OUTDIR" "$WORK"
OUTDIR="$(cd "$OUTDIR" && pwd)"
WORK="$(cd "$WORK" && pwd)"

if [ "$BEFDIR" != "none" ]; then
  if [ -d "$BEFDIR" ]; then
    BEFDIR="$(cd "$BEFDIR" && pwd)"
  elif [ -d "$(dirname "$BEFDIR")" ]; then
    BEFDIR="$(cd "$(dirname "$BEFDIR")" && pwd)/$(basename "$BEFDIR")"
  fi
fi

if [ -n "$SUMMARY" ] && [ -d "$(dirname "$SUMMARY")" ]; then
  SUMMARY="$(cd "$(dirname "$SUMMARY")" && pwd)/$(basename "$SUMMARY")"
fi

PBF_MB=$(( $(wc -c < "$PBF") / 1048576 ))

# --- Dichte Karten: bei kleinen Eingaben stuerzt DenseLongMap ab -----------
# (IndexOutOfBoundsException in DenseLongMap.put, siehe 1.Doku/RD5_Pipeline.md).
# Gemessen: ab echten Extrakten in Bremen-Groesse laeuft true problemlos und
# ist schneller, kostet aber mehr Arbeitsspeicher.
DENSE="${OPENCURV_DENSE_MAPS:-auto}"
if [ "$DENSE" = "auto" ]; then
  if [ "$PBF_MB" -ge 100 ]; then DENSE=true; else DENSE=false; fi
fi

log()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
die()  { echo "FEHLER: $*" >&2; exit 1; }

STATS="$WORK/stage-stats.tsv"

# Spitzenwert an Plattenplatz ueber den ganzen Lauf mitfuehren -- die Zahl,
# die ueber "passt auf einen GitHub-Runner" entscheidet.
PEAK_MB=0
note_stage() { # name, sekunden, workdir-MB
  local du_mb="$3"
  [ "$du_mb" -gt "$PEAK_MB" ] && PEAK_MB="$du_mb"
  printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "$STATS"
  printf '   %-12s %6s s   Arbeitsverzeichnis %6s MB\n' "$1" "$2" "$3"
}

stage() { # name, kommando...
  local name="$1"; shift
  local t0 t1
  t0=$(date +%s)
  "$@" || die "Stufe '$name' abgebrochen. Die vollstaendige Ausgabe steht oben."
  t1=$(date +%s)
  note_stage "$name" "$(( t1 - t0 ))" "$(( $(du -sk "$WORK" | awk '{print $1}') / 1024 ))"
}

rm -rf "$WORK"
mkdir -p "$WORK"/{nodetiles,waytiles,waytiles55,nodes55,unodes55,segments} "$OUTDIR"
: > "$STATS"

# --- lookups.dat erweitern -------------------------------------------------
# Anhaengen am Ende der way-Sektion + Minor-Version +1. Major-Version bleibt,
# damit alte Kacheln und neue App (und umgekehrt) zusammenpassen.
# Modus 'num' = Wildcard '*': der einzige Weg, der einen FEINSTUFIGEN Score
# liefert, OHNE das einvendorte brouter/-Modul patchen zu muessen.
log "lookups.dat erweitern (Wildcard-Modus)"
"$PY" "$REPO/tools/rd5build/patch_lookups.py" \
  "$REPO/app/src/main/assets/profiles/lookups.dat" \
  "$WORK/lookups.dat" --mode num
grep -q '^opencurv:curve;' "$WORK/lookups.dat" \
  || die "opencurv:curve steht nicht in der erzeugten lookups.dat -- hat sich das Dateiformat geaendert?"

# --- Filterprofile ---------------------------------------------------------
# OsmFastCutter braucht drei Profile, um zu entscheiden, was ueberhaupt in die
# Kacheln kommt. Die nehmen wir unveraendert vom Upstream.
PROF="$WORK/profiles"
mkdir -p "$PROF"
UP_PROFILES="${OPENCURV_UPSTREAM_PROFILES:-$(dirname "$(dirname "$(dirname "$JAR")")")/../misc/profiles2}"
[ -d "$UP_PROFILES" ] && UP_PROFILES="$(cd "$UP_PROFILES" && pwd)"
for p in all trekking softaccess; do
  if [ -f "$UP_PROFILES/$p.brf" ]; then
    cp "$UP_PROFILES/$p.brf" "$PROF/"
  else
    die "Filterprofil $p.brf nicht gefunden (gesucht in $UP_PROFILES). Setze OPENCURV_UPSTREAM_PROFILES."
  fi
done
cp "$WORK/lookups.dat" "$PROF/lookups.dat"

echo "Eingabe: $PBF ($PBF_MB MB) | useDenseMaps=$DENSE | Xmx=$XMX | Hoehen: $BEFDIR"

# --- Stufe 1: OsmFastCutter ------------------------------------------------
cd "$WORK"
stage cutter java -Xmx"$XMX" -cp "$JAR" \
  -DuseDenseMaps="$DENSE" -DavoidMapPolling=true -Ddeletetmpfiles=true \
  btools.mapcreator.OsmFastCutter \
  "$WORK/lookups.dat" nodetiles waytiles nodes55 waytiles55 \
  bordernids.dat relations.dat restrictions.dat \
  "$PROF/all.brf" "$PROF/trekking.brf" "$PROF/softaccess.brf" "$PBF"

[ -n "$(ls -A "$WORK/waytiles55" 2>/dev/null)" ] \
  || die "Der Cutter hat keine Wegkacheln erzeugt (waytiles55 ist leer). Enthaelt die Eingabe ueberhaupt Strassen?"
[ -n "$(ls -A "$WORK/nodes55" 2>/dev/null)" ] \
  || die "Der Cutter hat keine Knotenkacheln erzeugt (nodes55 ist leer)."

# PLATTENPLATZ: Ab hier wird die Eingabe nicht mehr gebraucht. Bei Bayern sind
# das ueber 800 MB, die sonst bis zum Ende belegt bleiben.
if [ "${OPENCURV_KEEP_INPUT:-0}" != "1" ]; then
  echo "   Eingabe-PBF wird freigegeben ($PBF_MB MB)"
  rm -f "$PBF"
fi

# --- Stufe 2: PosUnifier (Hoehen aufpraegen) -------------------------------
if [ "$BEFDIR" != "none" ] && [ ! -d "$BEFDIR" ]; then
  die "Hoehenverzeichnis $BEFDIR existiert nicht. Erst tools/pipeline/bin/fetch_dem.py laufen lassen oder 'none' uebergeben."
fi
stage posunifier java -Xmx"$XMX" -cp "$JAR" \
  -DuseDenseMaps="$DENSE" -Ddeletetmpfiles=true \
  btools.mapcreator.PosUnifier nodes55 unodes55 bordernids.dat bordernodes.dat "$BEFDIR"

[ -n "$(ls -A "$WORK/unodes55" 2>/dev/null)" ] \
  || die "PosUnifier hat keine Knotendateien geschrieben (unodes55 ist leer)."

# --- Stufe 3: WayLinker ----------------------------------------------------
stage waylinker java -Xmx"$XMX" -cp "$JAR" \
  -DuseDenseMaps="$DENSE" -DskipEncodingCheck=true \
  btools.mapcreator.WayLinker unodes55 waytiles55 bordernodes.dat restrictions.dat \
  "$WORK/lookups.dat" "$PROF/all.brf" segments rd5

N_RD5=$(ls -1 "$WORK/segments"/*.rd5 2>/dev/null | wc -l | tr -d ' ')
[ "$N_RD5" -gt 0 ] || die "WayLinker hat keine .rd5-Kachel erzeugt."

# --- Einsammeln ------------------------------------------------------------
mv "$WORK/segments"/*.rd5 "$OUTDIR/"
cp "$WORK/lookups.dat" "$OUTDIR/lookups.dat"

# Grenzknoten und Turn-Restrictions ausdruecklich ausweisen -- beides kam im
# Spike gar nicht vor und ist deshalb die Stelle, an der man hinsehen will.
BORDER_B=$(wc -c < "$WORK/bordernids.dat" 2>/dev/null || echo 0)

log "Ergebnis"
ls -l "$OUTDIR"
RD5_MB=$(( $(du -sk "$OUTDIR" | awk '{print $1}') / 1024 ))
echo "rd5-Kacheln: $N_RD5   Gesamtgroesse: $RD5_MB MB"
echo "bordernids.dat: $BORDER_B B  (0 = die Region liegt komplett in einer 5x5-Grad-Kachel)"
echo "Spitzen-Plattenplatz im Arbeitsverzeichnis: $PEAK_MB MB"

if [ -n "$SUMMARY" ]; then
  {
    echo "### rd5-Kachelerzeugung"
    echo ""
    echo "| Stufe | Sekunden | Arbeitsverzeichnis |"
    echo "|---|---:|---:|"
    while IFS=$'\t' read -r a b c; do echo "| $a | $b | $c MB |"; done < "$STATS"
    echo ""
    echo "- Eingabe: ${PBF_MB} MB PBF, \`useDenseMaps=$DENSE\`, \`-Xmx$XMX\`"
    echo "- Ausgabe: ${N_RD5} .rd5-Kacheln, ${RD5_MB} MB"
    echo "- **Spitzen-Plattenplatz: ${PEAK_MB} MB**"
    echo "- Grenzknotendatei: ${BORDER_B} B"
  } >> "$SUMMARY"
fi

# PLATTENPLATZ: Zwischenstaende weg, sobald die Kacheln in Sicherheit sind.
rm -rf "$WORK/nodetiles" "$WORK/waytiles" "$WORK/waytiles55" \
       "$WORK/nodes55" "$WORK/unodes55" "$WORK/restrictions" "$WORK/restrictions55"
echo "Zwischenstaende geloescht."
