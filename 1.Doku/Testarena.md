# Testarena: eine messbare Kurven-Wahrheit

Stand: September 2026. Modul: `tools/testarena/` (eigenständiger Gradle-Build, kein Teil des
Android-Builds - siehe unten).

## Wozu

Bisher ließ sich "ist unser Kurven-Algorithmus gut?" nur so prüfen: eine Route auf einer echten
Karte berechnen und schauen, ob sie "irgendwie kurvig aussieht". Das ist kein Test, weil niemand
weiß, was die *richtige* Antwort gewesen wäre - eine echte Karte hat keine bekannte, exakte
Ground Truth.

Die Testarena löst das, indem sie eine **synthetische** OSM-Karte erzeugt, auf der die Geometrie
jeder Straße exakt bekannt ist (weil sie aus Kreisbögen und Geraden mit fest kommandiertem Radius
und Winkel zusammengesetzt wurde, nicht aus echten Vermessungsdaten). Zwischen zwei Knoten ALPHA
und OMEGA liegen mehrere alternative Wege, die sich jeweils in **genau einer** Eigenschaft
unterscheiden (Kurvenradius, Belag, Straßenklasse, Ortsdurchfahrt, Steigung, Landuse). Damit lässt
sich exakt messen, welchen Weg ein Routing-Algorithmus wählt - und ob das der ist, den ein
Motorradfahrer auch gewählt hätte.

Drei Teile:

1. **Arena-Generator** - erzeugt `data/arena.osm` (+ `data/arena.osm.pbf`) und `arena_truth.json`
   deterministisch.
2. **Ground Truth** (`arena_truth.json`) - die exakten, analytisch bekannten Kennwerte jedes
   Elements, plus eine Liste maschinenlesbarer *Erwartungen* ("Route X muss Route Y vorgezogen
   werden - Begründung").
3. **Mess-Harness** - nimmt eine fertig berechnete Route (GPX oder JSON-Punktliste) entgegen und
   prüft sie gegen die Arena, ganz ohne zu wissen, welche Routing-Engine sie erzeugt hat.

## Warum dieser Ort?

Die Arena liegt im offenen Atlantik vor Westafrika, rund um **0,30° N / -1,00° E** - "rund um
0/0" wie gefordert, aber bewusst *nicht exakt* auf 0°N/0°E ("Null Island"). Null Island ist ein
bekannter Sammelpunkt für fehlerhafte GPS-Nullfixes und wird von manchen Werkzeugen/Datensätzen
speziell behandelt; ein paar Zehnerkilometer Abstand vermeiden das, ohne die Anforderung zu
verletzen. Der Ort ist offenes Meer - es gibt dort keine echten Straßen, mit denen die Arena
kollidieren könnte.

## Recherche: gab es schon etwas Fertiges?

Vor dem Eigenbau wurde gezielt nach vorhandenen Werkzeugen gesucht:

- **Synthetische OSM-Testdaten-Generatoren**: nichts Passendes gefunden. `osmium`/`pyosmium`
  sind Werkzeuge zum *Verarbeiten* echter OSM-Daten (Filtern, Transformieren, Format-Konvertierung),
  keine Generatoren für synthetische Testkarten mit bekannter Geometrie.
- **Routen-Vergleich gegen Ground Truth**: keine dedizierte Bibliothek gefunden. GraphHopper/
  Valhalla haben interne Testfixtures, aber keine wiederverwendbare "vergleiche Route X gegen
  bekannte Referenzgeometrie"-Komponente.
- **OSM-XML schreiben**: das Format ist trivial genug (flaches `<node>`/`<way>`/`<tag>`-XML), dafür
  lohnt sich keine Bibliothek - eigener, deterministischer Writer (`OsmXmlWriter.kt`).
- **OSM-PBF schreiben**: hier lohnt sich eine Bibliothek, weil das Format (Protobuf + zlib,
  Delta-kodierte Dense-Nodes) von Hand fehleranfällig wäre. Gefunden: **`osm4j-pbf`**
  (`de.topobyte:osm4j-pbf` + `osm4j-core`, LGPL-3.0, Teil des `topobyte/osm4j`-Projekts). Es liegt
  nicht auf Maven Central, sondern auf `https://mvn.topobyte.de` (+ `https://mvn.slimjars.com` für
  eine transitive trove4j-Abhängigkeit) - beide sind in `build.gradle.kts` als zusätzliche Maven-
  Repositories eingetragen. **Offener Punkt**: sollten diese beiden Hosts irgendwann verschwinden,
  bricht der *Build* (nicht nur die PBF-Ausgabe) - das Modul dann notfalls ohne PBF-Unterstützung
  weiterbauen (Dependency + Repo-Zeilen entfernen, `OsmPbfWriter`/`GenerateArena`s PBF-Aufruf
  rausnehmen; `arena.osm` bleibt davon unberührt).

Ergebnis: Generator und Harness sind Eigenbauten (ausdrücklich erlaubt, wenn nichts Fertiges
existiert), nur das PBF-Schreiben nutzt eine fertige Bibliothek.

## Wie erzeugt man die Arena?

Das Modul baut sich unabhängig vom Android-Build, genau wie `tools/verifier/`:

```
./gradlew -p tools/testarena generateArena
```

Das schreibt (immer, deterministisch):

- `tools/testarena/data/arena.osm` (OSM-XML)
- `tools/testarena/data/arena.osm.pbf` (Binärformat, best effort - siehe oben)
- `tools/testarena/arena_truth.json` (Ground Truth + Erwartungen)

Alle drei Dateien sind im Repo eingecheckt (nicht `.gitignore`t); `DeterminismTest` prüft, dass ein
erneuter Lauf exakt dieselben Bytes produziert. Nach jeder Änderung an `ArenaDefinition.kt` muss
`generateArena` neu laufen und die geänderten Dateien müssen mit committet werden.

Alternativ direkt mit Gradle im Modul (kein Android-SDK nötig):

```
cd tools/testarena
gradle generateArena     # oder: ../../gradlew -p tools/testarena generateArena
```

## Die Elemente (ASCII-Skizze)

ALPHA liegt am Ursprung, OMEGA 4 km nördlich davon. Neun Alternativrouten fächern bei ALPHA mit
unterschiedlichem Anfangs-Heading auf (damit sie sich nicht überlagern) und laufen über eine
gewöhnliche gerade Auffahrt wieder exakt auf denselben OMEGA-Knoten zusammen - so, wie mehrere
echte Straßen an derselben Kreuzung ankommen können. Draufsicht, Norden ist oben:

```
                                     OMEGA
                                       o
              .-------.-------.-------|-------.-------.-------.
             /        |       |       |       |       |        \
           R8        R4      R3      R1      R7      R6        R2   R9
      (90°-Ecken) (S-Kurven) (weite (gerade)(sanfte (Schotter)(14   (lange
                              Schwünge)      Kurven)          echte Gerade,
                                                                Kehren) dann 1
                                                                        scharfe
                                                                        Kurve)
             \        |       |       |       |       |        /    /
              '-------'-------'-------|-------'-------'-------'----'
                                       |
                                     ALPHA
                                       |
                                       '---> R5 (Ortsnetz):
                                             ALPHA -> [Zickzack durch ein
                                             5x5-Wohnblock-Gitter,
                                             landuse=residential] -> OMEGA

  Getrennt davon, mit eigenen Knotenpaaren (gleicher Grundriss, ein Parameter anders):

  HILL_START o====== identische S-Kurve ======o HILL_END
             (einmal eben, einmal mit 350 Höhenmetern - Steigungsprofil vs. flach)

  GREEN_START o====== identische S-Kurve ======o GREEN_END
             (einmal durch landuse=forest, einmal durch landuse=industrial)
```

| Element (routeId) | Was | highway | Radius / Charakter |
|---|---|---|---|
| `R1_HIGHWAY` | Schnellstraße, kerzengerade, kurz | primary | keine Kurve, 4000 m |
| `R7_MOTORWAY` | Autobahn, kreuzungsfrei | motorway | 2 Kurven, R=900 m |
| `R2_SERPENTINE` | Serpentine mit echten Kehren | tertiary | 14 Kurven, R=24 m |
| `R3_FLOWING` | Fließende Landstraße | secondary | 4 Kurven, R=180-320 m |
| `R4_S_CURVES` | Dichte S-Kurven-Kombination | tertiary | 30 Kurven, R=45 m |
| `R6_GRAVEL` | Schotterpiste | track, surface=gravel | 3 Kurven, R=90-110 m |
| `R8_JOG90` | Stumpfe 90°-Kreuzungsfolge | tertiary | 0 echte Kurven, 6 rechtwinklige Ecken |
| `R9_DOGLEG` | Hundskurve (Gerade + 1 Überraschungskurve) | tertiary | 1 Kurve, R=20 m, nach 3,5 km Gerade |
| `R5_GRID` | Zickzack-Ortsnetz | residential, landuse=residential | 0 echte Kurven, 8 rechtwinklige Ecken (Radius 0) |
| `R_HILL_FLAT` / `R_HILL_CLIMB` | Steigungsprofil vs. flach | tertiary | identische Kurvengeometrie, 0 vs. 350 Höhenmeter |
| `R_FOREST` / `R_INDUSTRIAL` | Wald vs. Industriegebiet | tertiary | identische Kurvengeometrie, unterschiedliches Landuse |

Exakte Zahlen (Länge, Radien, Gesamt-Richtungsänderung, Höhenmeter, Way-IDs) stehen für jedes
Element in `arena_truth.json` → `elements[]`. Jede Zahl dort ist analytisch aus den
Kreisbogen-/Geraden-Kommandos berechnet, mit denen der Generator die Straße gebaut hat - nicht aus
der abgetasteten Geometrie geschätzt (siehe `GeometryTruthTest`, das genau das gegeneinander
prüft).

### Die wichtigste Falle: `R5_GRID`

`R5_GRID` hat von allen Elementen die größte Gesamt-Richtungsänderung (`totalTurnDeg` ≈ 717°,
mehr als die Serpentine!) - aber `curveCount = 0` und `minRadiusM = null`, weil jede "Kurve" dort
in Wahrheit eine stumpfe 90°-Ecke mit Radius 0 ist, mitten durch ein Tempo-50-Wohngebiet. Ein
Algorithmus, der Kurvigkeit naiv über "wie oft ändert sich die Richtung" misst, würde `R5_GRID` für
die kurvenreichste Route halten. Genau das ist der Fehler, den die Erwartungen `E3` und `E7`
abfangen (siehe unten) - dafür gibt es `sharpCornerCount` als eigenes Feld, getrennt von
`curveCount`.

## Erwartungen (`arena_truth.json` → `expectations[]`)

Jede Erwartung hat die Form *"Für eine Anfrage von `from` nach `to` muss ein guter Algorithmus eine
Route bevorzugen, die überwiegend `preferRouteIds` nutzt, gegenüber einer, die überwiegend
`overRouteIds` nutzt"*, mit `severity`:

- **`hard`**: muss gelten, sonst tut OpenCurv nicht das, wofür es gebaut ist.
- **`soft`**: erwünscht, aber profilabhängig (z. B. Asphalt vor Schotter - bei einem
  Enduro-Profil bewusst umgekehrt).
- **`info`**: nicht automatisch aus einer einzelnen Route auswertbar (z. B. Neutralität
  gegenüber Höhenmetern), dient als Hinweis für manuelle Prüfung.

| ID | Bevorzugt | Statt | Severity | Kernaussage |
|---|---|---|---|---|
| E1_CURVES_OVER_HIGHWAY | R2/R3/R4 | R1_HIGHWAY | hard | Nicht die kürzeste Straße wählen, nur weil sie kürzer ist |
| E2_CURVES_OVER_MOTORWAY | R2/R3/R4 | R7_MOTORWAY | hard | Nicht die schnellste Straße wählen, nur weil sie schnell ist |
| E3_CURVES_OVER_GRID | R2/R3/R4 | R5_GRID | hard | Ortsdurchfahrt mit vielen 90°-Ecken ist keine gute Kurve |
| E4_CURVES_OVER_DOGLEG | R2/R3/R4 | R9_DOGLEG | hard | Eine Überraschungskurve nach langer Gerade ist kein Kurvenerlebnis |
| E5_CURVES_OVER_JOG90 | R2/R3/R4 | R8_JOG90 | hard | Stumpfe Ecken sind keine fahrbaren Kurven |
| E6_CURVES_OVER_GRAVEL | R2/R3/R4 | R6_GRAVEL | soft | Asphalt vor Schotter (profilabhängig) |
| E7_HIGHWAY_OVER_GRID | R1_HIGHWAY | R5_GRID | hard | Selbst die langweilige Schnellstraße schlägt die Ortsdurchfahrt |
| E8_ELEVATION_NEUTRALITY | – | – | info | Kurvenbewertung darf nicht auf `ele` reagieren |
| E9_SCENIC_FOREST_OVER_INDUSTRIAL | R_FOREST | R_INDUSTRIAL | soft | Zukunfts-Hook für eine Szenerie-Bewertung |

## Wie bewertet man eine Route?

Das Harness kennt **nur Koordinaten** - keine Routing-Engine, kein Profil, kein internes Scoring.
Es nimmt eine fertig berechnete Route entgegen (GPX-`<trkpt>`/`<rtept>` oder eine einfache
JSON-Punktliste `[{"lat":.., "lon":..}, ...]`), matcht jeden Streckenabschnitt per Abstand
(Standard-Toleranz 30 m) auf das nächstgelegene Arena-Way-Segment, und leitet daraus ab, welche
Arena-Elemente die Route tatsächlich befahren hat.

```
./gradlew -p tools/testarena evaluateRoute -ProuteFile=/pfad/zu/route.gpx [-Pout=report.json]
```

Ausgabe: ein lesbarer Bericht auf der Konsole (Gesamtlänge, Kurvigkeit in °/km, Ortsdurchfahrt-,
Schotter- und Autobahnanteil, welche Arena-Elemente benutzt wurden, welche Erwartungen erfüllt/
verletzt sind) sowie dieselben Daten als `report.json`. Der Prozess beendet sich mit Exit-Code 1,
wenn mindestens eine `hard`-Erwartung verletzt wurde (nützlich für CI).

Beispielausschnitt (Route = die Autobahn selbst):

```
Kurvigkeit:         23,1 °/km
Autobahnanteil:     100,0 %

  [FAIL] (hard) E2_CURVES_OVER_MOTORWAY: ALPHA -> OMEGA
         bevorzugt [R2_SERPENTINE, R3_FLOWING, R4_S_CURVES] (0,0%) vs. [R7_MOTORWAY] (100,0%)
```

## Determinismus und Toleranzen

- **Determinismus**: Der Generator verwendet keine Zufallszahlen, keine Systemzeit, keine
  HashMap-Iterationsreihenfolge - Knoten/Ways werden vor dem Schreiben nach ID sortiert, Tags nach
  Schlüssel. `DeterminismTest` erzeugt die Arena zweimal und vergleicht Bytes (XML und PBF) sowie
  die serialisierte `arena_truth.json`.
- **Radius-Toleranz: 1 %.** Jeder Kurvenpunkt liegt exakt (bis auf Gleitkomma-Rauschen) auf dem
  kommandierten Kreisbogen - der Umkreisradius dreier abgetasteter Punkte ist für einen echten
  Kreis exakt, unabhängig vom Punktabstand.
- **Längen-/Winkel-Toleranz: 3 %.** Länge und Gesamt-Richtungsänderung werden aus der
  Sehnen-Summe der abgetasteten Punktfolge gemessen; bei engen Radien (15-30 m) weicht eine Sehne
  vom wahren Bogen um bis zu ~(Punktabstand/Radius)²/24 ab.
- **Stützpunktabstand**: 8-30 m (an engen Kurven automatisch enger, siehe `PathBuilder.arc()` -
  ein Bogen bekommt nie einen Abstand größer als die Hälfte seines Radius, sonst könnte eine sehr
  enge/kurze Kurve als eine einzige gerade Sehne abgetastet werden).

## Ein neues Element hinzufügen

1. In `ArenaDefinition.kt` eine neue private `build...()`-Funktion nach dem Muster der
   bestehenden schreiben: einen `PathBuilder` an einem Startpunkt/-heading aufmachen,
   `straight()`/`arc()` (und bei Bedarf `setElevationProfile()`) aufrufen, mit `connectTo(OMEGA)`
   (oder dem passenden Zielknoten) abschließen, und über `finish(...)` (oder analog zu
   `buildHillPair`/`buildLandusePair` für eigene Knotenpaare) in ein `ElementTruth` verwandeln.
2. Die neue Route in `build()` in die `elements`-Liste aufnehmen.
3. Falls die neue Route etwas widerlegen/belegen soll: einen Eintrag in `buildExpectations()`
   ergänzen (`preferRouteIds`/`overRouteIds`/`severity`/`reason`).
4. `./gradlew -p tools/testarena generateArena` laufen lassen und die geänderten Dateien unter
   `tools/testarena/data/` und `tools/testarena/arena_truth.json` committen.
5. `./gradlew -p tools/testarena test` - `GeometryTruthTest` prüft automatisch jeden `arc()`-Aufruf
   im ganzen Modul, ohne dass für ein neues Element ein eigener Test geschrieben werden muss;
   bei besonderen Eigenschaften (wie der Grid-Falle) lohnt sich trotzdem ein gezielter Test.

## Tests ausführen

```
./gradlew -p tools/testarena test
```

Die Suite (13 Tests) deckt ab: Determinismus (XML, PBF, `arena_truth.json`, stabile IDs, Abgleich
mit den eingecheckten Dateien), dass die abgetastete Geometrie jedes Bogens Radius/Länge aus der
Ground Truth reproduziert, dass die HILL*/GREEN*-Paare wirklich identische Kurvengeometrie haben,
und dass das Harness eine Route auf der Autobahn bzw. durch das Ortsnetz korrekt als Verletzung
der jeweiligen Erwartungen erkennt (während eine Route auf `R3_FLOWING` alle harten Erwartungen
erfüllt).
