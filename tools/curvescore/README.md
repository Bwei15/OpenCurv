# tools/curvescore

Der Kurven-Score von OpenCurv: aus OSM-Geometrie wird je Way eine Zahl
`0..15` (0 = meiden, 15 = Traumstrecke), die die Cloud-Pipeline als OSM-Tag
`opencurv:curve` in die Daten zurückschreibt und BRouter später als
Kostenfaktor liest.

Eigenständiger Gradle-Build wie `tools/testarena` und `tools/verifier` —
**kein Teil des Android-Builds**. Er läuft in GitHub Actions, einmal pro
Region, nie auf dem Telefon.

Die vollständige fachliche Herleitung (Formel, jede Konstante mit Begründung,
Arena-Rangliste, Laufzeit, Fehlerfälle) steht in **`1.Doku/Kurven_Score.md`**.

## Benutzung

```bash
# Testarena bewerten, E1-E9 prüfen, report/arena.svg + arena_scores.json schreiben
./gradlew -p tools/curvescore arenaReport

# alle Tests (50)
./gradlew -p tools/curvescore test

# Durchsatz messen
./gradlew -p tools/curvescore benchmark
```

Direkt als CLI:

```bash
./gradlew -p tools/curvescore run --args="score --in region.osm.pbf --json scores.json"
./gradlew -p tools/curvescore run --args="tag --in region.osm --out region.tagged.osm"
./gradlew -p tools/curvescore run --args="tag --in a.osm --out b.osm --tag-name brouter:curviness --levels 8"
./gradlew -p tools/curvescore run --args="bench-io --ways 400000"
```

Optionen: `--tag-name`, `--raw-tag`, `--conf-tag`, `--levels`, `--window`,
`--enduro` (Schotter nicht abwerten).

## Aufbau

| Datei | Zweck |
|---|---|
| `geom/Geo.kt` | Projektion, Douglas-Peucker, Mindestschrittweite |
| `geom/TurnAnalysis.kt` | Ablenkung je Stützpunkt, Radius, **Kurve vs. Ecke** |
| `score/ScoreConfig.kt` | jede Konstante mit ihrer Begründung |
| `score/Terms.kt` | die Einzelterme, jeder einzeln testbar |
| `score/RoadTags.kt` | Straßenklasse, Belag, fehlende Tags, Konfidenz |
| `score/Environment.kt` | Landuse-/Gewässer-Index (Szenerie, Ortslage) |
| `score/Corridor.kt` | Way + geradlinige Fortsetzung als Bezugsstrecke |
| `score/CurveScorer.kt` | gleitendes Fenster, Gewichtung, Quantisierung |
| `io/` | `.osm`-XML und `.osm.pbf` lesen, Tag zurückschreiben |
| `report/` | Arena-Auswertung, Erwartungsprüfung, SVG |

## Abhängigkeiten

`gson` (Maven Central) und `de.topobyte:osm4j-*` für PBF — dieselbe
Bibliothek und dieselben beiden Zusatz-Repositories, die `tools/testarena`
schon benutzt. Fallen die aus, bleibt der XML-Pfad (Lesen *und*
Tag-Zurückschreiben) unberührt; nur `OsmPbfReader`/`OsmPbfWriter` und der
`bench-io`-Modus müssten entfallen.

## Regeln

- `tools/testarena/` ist für dieses Modul **nur lesbar**. Es ist der Maßstab;
  ein Maßstab, den man biegen darf, ist keiner.
- `adamfranco/curvature` (GPL-3.0) war nur Ideengeber. Es wurde kein Code
  übernommen; die Umkreisradius-Methode wird hier bewusst *nicht* verwendet
  (Begründung in `1.Doku/Kurven_Score.md`, Abschnitt „Recherche").
