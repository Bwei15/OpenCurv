#!/usr/bin/env bash
# ===========================================================================
# run_scorer.sh -- die EINZIGE Stelle, an der die Pipeline den Kurven-Scorer
# aufruft.
#
# Der Scorer ist ein austauschbarer Baustein hinter einer festen Schnittstelle:
#
#     Eingabe : eine OSM-Datei (.osm oder .osm.pbf)
#     Ausgabe : dieselben Daten, an jedem bewerteten Way zusaetzlich der Tag
#               `opencurv:curve` mit einem Wert 0..(levels-1)
#
# Die Reihenfolge, in der ein Scorer gesucht wird:
#   1. $OPENCURV_SCORER_CMD          -- ausdrueckliche Ueberschreibung
#   2. tools/curvescore/            -- der echte Scorer
#   3. tools/pipeline/bin/placeholder_scorer.py  -- Rueckfallebene
#
# Zum echten Scorer (Stand 10.09.2026): sein Unterbefehl `tag` schreibt
# ausdruecklich NUR OSM-XML zurueck ("PBF-Ausgabe uebernimmt die
# rd5-Pipeline"). Ein XML-Umweg ist fuer ein Bundesland ausgeschlossen --
# Bayern als XML sind zweistellige Gigabyte, ein freier Runner hat rund 14 GB.
# Deshalb der zweistufige Weg:
#     curvescore score --in <pbf> --json <scores.json>
#     apply_scores.py  <pbf> <scores.json> <out.pbf>
#
# Aufruf: run_scorer.sh <in.osm.pbf> <out.osm.pbf> [levels]
# ===========================================================================
set -euo pipefail

IN="${1:?Eingabedatei fehlt}"
OUT="${2:?Ausgabedatei fehlt}"
LEVELS="${3:-16}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
PY="${OPENCURV_PYTHON:-python3}"

[ -f "$IN" ] || { echo "FEHLER: Eingabedatei nicht gefunden: $IN" >&2; exit 1; }

pick_scorer() {
  if [ -n "${OPENCURV_SCORER_CMD:-}" ]; then
    echo "override"; return
  fi
  if [ -x "$REPO/tools/curvescore/run.sh" ]; then
    echo "curvescore-sh"; return
  fi
  if [ -f "$REPO/tools/curvescore/build.gradle.kts" ]; then
    echo "curvescore-gradle"; return
  fi
  echo "placeholder"
}

KIND="$(pick_scorer)"
echo "== Kurven-Scorer: $KIND"

case "$KIND" in
  override)
    # $OPENCURV_SCORER_CMD bekommt <in> <out> <levels> angehaengt.
    # shellcheck disable=SC2086
    $OPENCURV_SCORER_CMD "$IN" "$OUT" "$LEVELS"
    ;;
  curvescore-sh)
    "$REPO/tools/curvescore/run.sh" "$IN" "$OUT" "$LEVELS"
    ;;
  curvescore-gradle)
    CS="$REPO/tools/curvescore"
    BIN="$CS/build/install/opencurv-curvescore/bin/opencurv-curvescore"
    if [ ! -x "$BIN" ]; then
      echo "   curvescore bauen (installDist)"
      ( cd "$REPO" && ./gradlew -p tools/curvescore installDist --console=plain -q ) || {
        echo "FEHLER: tools/curvescore/ liess sich nicht uebersetzen." >&2
        echo "        Entweder das Modul reparieren, oder fuer diesen Lauf" >&2
        echo "        OPENCURV_SCORER_CMD auf den Platzhalter setzen:" >&2
        echo "          OPENCURV_SCORER_CMD=\"$PY $HERE/placeholder_scorer.py\"" >&2
        exit 1
      }
    fi
    SCORES="${OUT%.osm.pbf}.scores.json"
    "$BIN" score --in "$IN" --json "$SCORES" --levels "$LEVELS"
    [ -s "$SCORES" ] || { echo "FEHLER: curvescore hat keine Score-Datei geschrieben: $SCORES" >&2; exit 1; }
    "$PY" "$HERE/apply_scores.py" "$IN" "$SCORES" "$OUT"
    # PLATTENPLATZ: die Score-Liste kann fuer ein grosses Bundesland selbst
    # in die Gigabyte gehen und wird ab hier nicht mehr gebraucht.
    rm -f "$SCORES"
    ;;
  placeholder)
    echo "   HINWEIS: tools/curvescore/ existiert noch nicht -- es laeuft der"
    echo "   PLATZHALTER. Die erzeugten Kacheln sind technisch vollwertig, der"
    echo "   Score darin ist aber nur eine grobe Heuristik."
    "$PY" "$HERE/placeholder_scorer.py" "$IN" "$OUT" --levels "$LEVELS"
    ;;
esac

# --- Abnahme der Stufe ----------------------------------------------------
# Der Scorer ist fremder Code hinter einer Schnittstelle. Die Pipeline glaubt
# ihm nichts, sondern prueft: Datei da, nicht leer, plausibel gross.
if [ ! -s "$OUT" ]; then
  echo "FEHLER: Der Scorer hat keine (oder eine leere) Ausgabedatei erzeugt: $OUT" >&2
  echo "        Vertrag: <in.osm.pbf> <out.osm.pbf> <levels>, Ausgabe mit Tag opencurv:curve." >&2
  exit 1
fi
IN_SZ=$(wc -c < "$IN")
OUT_SZ=$(wc -c < "$OUT")
if [ "$OUT_SZ" -lt $(( IN_SZ / 2 )) ]; then
  echo "FEHLER: Die Scorer-Ausgabe ist weniger als halb so gross wie die Eingabe" >&2
  echo "        ($OUT_SZ B gegen $IN_SZ B). Das deutet auf einen Abbruch mitten" >&2
  echo "        im Schreiben hin -- die Kette wird hier gestoppt." >&2
  exit 1
fi
echo "   $IN ($(( IN_SZ / 1048576 )) MB) -> $OUT ($(( OUT_SZ / 1048576 )) MB)"
