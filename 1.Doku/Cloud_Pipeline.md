# Cloud-Pipeline — OSM zu fertigen Artefakten in GitHub Actions

**ERGEBNIS VORWEG: Die Kette laeuft auf echten Daten, und Bayern passt mit
grossem Abstand auf einen kostenlosen GitHub-Runner.**

Gemessen, nicht gerechnet: Ein vollstaendiger Bayern-Durchlauf (851 MB PBF von
Geofabrik, Copernicus-Hoehen aufgepraegt, Kurven-Score eingebacken) braucht
**123 Sekunden Rechenzeit, 2,9 GB Arbeitsspeicher und 673 MB Plattenplatz**
und liefert 87,0 MB `.rd5`. Der Engpass, den die Recherche beim Plattenplatz
vermutet hat, ist keiner: von den rund 14 GB eines freien Runners werden in
der Spitze unter 3 GB gebraucht — und das nur, weil die Rohdaten kurzzeitig
mit herumliegen.

Danach wurde in den erzeugten Kacheln eine **150 km lange echte Route**
gefahren. Der mittlere Kurven-Score entlang der Strecke springt allein durch
den Profilwechsel von 1,47 auf 5,51 — **Faktor 3,75**. Der Score wirkt auf
echten Daten.

Datum: 10.09.2026 · Messrechner: Apple Silicon, Java 17 (Homebrew openjdk@17) ·
BRouter Upstream 1.7.11-beta · Geofabrik-Stand 10.09.2026

---

## 1. Die Kette, Stufe fuer Stufe

```
   Geofabrik <region>-latest.osm.pbf          Copernicus DEM GLO-30 (AWS S3)
            │                                            │
            │                                    gdal_translate -of SRTMHGT
            │                                            │  (1 Grad, .hgt)
            │                                    ElevationRasterTileConverter
            │                                            │  (5x5 Grad, .bef)
            ▼                                            │
   [1] Kurven-Score anbringen                            │
       tools/curvescore/  (oder Platzhalter)             │
       -> opencurv:curve=0..15 an jedem Way              │
            │                                            │
            ▼                                            ▼
   [2] OsmFastCutter ──────► [3] PosUnifier ◄────────────┘
            │                     │  (Hoehen + eindeutige Positionen)
            │                     ▼
            └──────────────► [4] WayLinker  ──► .rd5-Kacheln (5x5 Grad)
                                  │
   [5] Planetiler (parallel) ─────┼──► <region>.pmtiles
                                  ▼
   [6] Abnahme: echte Route fahren, Score und Hoehen nachweisen
                                  │
                                  ▼
   [7] catalog.json + Pruefsummen ──► GitHub-Release
```

Alles ausser Stufe 1 und 5 ist der Weg, den `tools/rd5build/run_spike.sh`
bereits bewiesen hat. Neu ist nur die Betriebsfaehigkeit: Messung, Abnahme,
Aufraeumen, sprechende Fehler.

### Die Skripte

| Datei | Aufgabe |
| --- | --- |
| `tools/pipeline/config/regions.json` | **Die** Konfiguration. Nur hier stehen Regionen. |
| `tools/pipeline/bin/regions.py` | liest sie; erzeugt die Actions-Matrix, validiert, bestimmt Hoehenkacheln |
| `tools/pipeline/bin/fetch_dem.py` | Copernicus GLO-30 holen und in `.bef`-Raster giessen |
| `tools/pipeline/bin/run_scorer.sh` | **die einzige Stelle**, an der der Scorer aufgerufen wird |
| `tools/pipeline/bin/placeholder_scorer.py` | Rueckfall-Scorer, erfuellt denselben Vertrag |
| `tools/pipeline/bin/apply_scores.py` | traegt `curvescore score --json` in die PBF ein |
| `tools/pipeline/bin/build_rd5.sh` | Cutter → PosUnifier → WayLinker, mit Messung und Aufraeumen |
| `tools/pipeline/bin/build_pmtiles.sh` | Planetiler |
| `tools/pipeline/bin/RouteCheck.java` | faehrt eine Route und liest den Score **aus der `.rd5`** |
| `tools/pipeline/bin/verify_region.sh` | die Abnahme, Stufe 6 |
| `tools/pipeline/bin/make_catalog.py` | `catalog.json` samt Pruefsummen und Aufteilung |
| `tools/pipeline/bin/catalog_summary.py` | Markdown-Tabelle fuer die Job-Zusammenfassung |
| `tools/pipeline/profiles/opencurv_check.brf` | Pruefprofil mit der Existenzpruefung gegen die NaN-Falle |

---

## 2. Die Messwerte

Alle Zahlen dieses Abschnitts sind **gemessen**. Wo etwas hochgerechnet ist,
steht es ausdruecklich dabei.

### 2.1 Der Gesamtdurchlauf je Region

Eingabe ist der Geofabrik-Extrakt, Ausgabe die fertigen `.rd5`.

| Region | PBF | Score anbringen | rd5-Kette | Spitzen-RAM | Spitzen-Platte | `.rd5` gesamt | Kacheln |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Bremen | 21 MB | 7,6 s | 5,2 s | 705 MB | 24 MB | 1,56 MB | 1 |
| Saarland | 55 MB | 20,8 s | 11,5 s | 543 MB | 59 MB | 3,89 MB | 1 |
| Rheinland-Pfalz | 269 MB | 119,9 s | 65,7 s | 1 736 MB | 353 MB | 24,67 MB | 2 |
| **Bayern** | **851 MB** | **379,0 s** | **123,0 s** | **2 897 MB** | **673 MB** | **87,01 MB** | **4** |

Bayern im Einzelnen (`de-by_*`): `E10_N45.rd5` 72,90 MB (die Alpenkachel),
`E10_N50.rd5` 8,01 MB, `E5_N45.rd5` 4,05 MB, `E5_N50.rd5` 2,06 MB.

Die Laufzeit „Score anbringen" ist die des **Platzhalters** (Python/pyosmium)
— der echte Scorer aus `tools/curvescore/` wird andere Zahlen haben, siehe
§6.2.

Die rd5-Kette im Einzelnen fuer Bayern (`useDenseMaps=true`, `-Xmx5500M`,
`-Ddeletetmpfiles=true`):

| Stufe | Sekunden | Arbeitsverzeichnis danach |
| --- | ---: | ---: |
| `OsmFastCutter` | 64 | 311 MB |
| `PosUnifier` | 20 | 348 MB |
| `WayLinker` | 39 | 442 MB |

Der Spitzenwert von 673 MB stammt aus einer Beobachtung im 5-Sekunden-Takt
waehrend des Laufs und liegt hoeher als die Werte an den Stufengrenzen — er
faellt mitten im `WayLinker` an, wenn Ein- und Ausgabe gleichzeitig existieren.

### 2.2 Was der Kurven-Tag kostet

Die zentrale offene Frage des Spikes: Wie stark wachsen die `.rd5` durch
`opencurv:curve`? Jede Region wurde dafuer **zweimal** gebaut, einmal aus dem
unveraenderten Geofabrik-Extrakt, einmal aus der bewerteten Fassung.

| Region | `.rd5` ohne Tag | `.rd5` mit Tag | Zuwachs |
| --- | ---: | ---: | ---: |
| Bremen (ohne Hoehen) | 1 288 725 B | 1 438 871 B | **+11,65 %** |
| Bremen (mit Hoehen) | 1 410 433 B | 1 560 577 B | **+10,65 %** |
| Saarland | 3 396 184 B | 3 888 768 B | **+14,51 %** |
| Rheinland-Pfalz | 21 487 459 B | 24 665 594 B | **+14,79 %** |
| **Bayern** (4 Kacheln, mit Hoehen) | **77 230 916 B** | **87 012 542 B** | **+12,67 %** |

Bayern kostet der Score also **9,78 MB**. Der absolute Zuwachs ist bei Bremen
in beiden Faellen exakt **150 144 Byte** —
der Tag kostet unabhaengig davon, ob Hoehen aufgepraegt sind. Das ist
plausibel: Hoehen stecken in den Knotendaten, der Score in den Wegdaten.

**Die Hochrechnung des Spikes war zu optimistisch.** Der Spike sagte „+4 Bit
pro Link im schlimmsten Fall, in der Praxis weniger" und schaetzte auf
Deutschland „einen einstelligen Prozentsatz". Gemessen sind es 11–15 %, ueber alle vier Regionen hinweg. Die
Codec-Statistik des `WayLinker` erklaert, warum (Bremen, 16 Stufen):

| Posten | ohne Tag | mit Tag | Differenz |
| --- | ---: | ---: | ---: |
| `wayDescIdx` (173 135 Links) | 1 311 493 Bit | 1 413 390 Bit | +101 897 Bit = **+0,59 Bit/Link** |
| `wayTagDictionary` (149 Microcaches) | 1 295 084 Bit | 2 394 284 Bit | +1 099 200 Bit = **+137 400 Byte** |

Der Index pro Link kostet nur 0,59 statt der befuerchteten 4 Bit — die
Vorhersage des Spikes, dass der Score mit `highway` und `maxspeed` korreliert
und der Woerterbuch-Coder das ausnutzt, **stimmt**. Aber **91 % des Zuwachses
stecken im Woerterbuch**, nicht im Index: jede bisher vorkommende
Tag-Kombination bekommt Varianten mit Score, und dieses Woerterbuch wird je
Microcache erneut gespeichert. Diesen Posten hatte der Spike nicht auf dem
Schirm, weil sein Pruefgitter nur eine einzige Wegbeschreibung kannte.

**Und daraus folgt die praktisch wichtigste Erkenntnis:** Die Stufenzahl zu
senken hilft kaum, weil der Loewenanteil ein Fixkostenblock ist. Gemessen an
Bremen:

| Stufen | `.rd5` | Zuwachs gegenueber „ohne Tag" |
| ---: | ---: | ---: |
| 0 (kein Tag) | 1 288 725 B | — |
| 4 | 1 402 195 B | +8,81 % |
| 8 | 1 421 674 B | +10,32 % |
| **16** | **1 438 871 B** | **+11,65 %** |

Von 16 auf 4 Stufen zu gehen spart **2,8 Prozentpunkte** und wirft dafuer zwei
Drittel der Aufloesung weg. **Empfehlung: bei 16 Stufen bleiben.** Die
Empfehlung des Spikes bleibt also gueltig, aber aus einem anderen Grund, als
er annahm.

### 2.3 Hoehendaten

Copernicus DEM GLO-30 liegt im oeffentlichen S3-Bucket `copernicus-dem-30m`
und ist **ohne Anmeldung und ohne `aws`-CLI** ueber schlichtes HTTPS
erreichbar — nachgeprueft. Es sind Cloud-Optimized-GeoTIFFs mit
**breitenabhaengiger Spaltenzahl** (bei 50–60 Grad Nord nur 2400 statt 3600
Spalten), muessen also auf das starre 3601x3601-Gitter des `.hgt`-Formats
umgerechnet werden. Das erledigt `gdal_translate -of SRTMHGT -r bilinear`.

Gemessen fuer Bayern (bbox 8,98–13,85 / 47,27–50,57):

| Posten | Wert |
| --- | ---: |
| Copernicus-Kacheln (1x1 Grad) | 24 |
| GeoTIFF geladen | 900 MB |
| Ladedauer + Umwandlung nach `.hgt` | 289 s |
| `.hgt`-Zwischenstufe | 622 MB (24 x 25,9 MB) |
| `.bef`-Kacheln (5x5 Grad) | 4 |
| `.bef` gesamt | **145 MB** |
| Umwandlung nach `.bef` | 28 s |
| Spitzen-RAM des Rasterwandlers | **1,41 GB** |

Die 1,41 GB sind der Grund fuer die Aufteilung in Jobs: Ein 5x5-Grad-Raster in
1-Bogensekunde ist 18001 x 18001 `short` = 648 MB allein als Array.

Zum Vergleich Nordrhein-Westfalen (bbox 5,86–9,47 / 50,32–52,53): 15
Copernicus-Kacheln, 452 MB GeoTIFF, 131 s, daraus **eine** `.bef` von 64 MB.

**Die Groesse einer `.bef` haengt an der Abdeckung, nicht an der Kachelflaeche.**
Dieselbe Kachel `srtm_38_02` wiegt 2,0 MB, wenn nur Bremens vier Grad-Felder
darin liegen, und 67,6 MB, wenn NRW sie fast ausfuellt. Wer den Cache-Bedarf
abschaetzt, muss das beruecksichtigen.

Ganz Deutschland braucht nur **neun** verschiedene `.bef`-Kacheln
(`srtm_38_01` bis `srtm_40_03`). Deshalb sind sie **gecacht**, nicht bei jedem
Lauf neu gebaut — dazu `opencurv-dem-cache.yml`. Der Cache-Schluessel ist die
sortierte Kachelliste einer Region, Regionen mit gleichem Bedarf teilen sich
also einen Eintrag. **Achtung auf das 10-GB-Limit des Actions-Cache:** die
16 Bundeslaender ergeben rund neun verschiedene Kachellisten; zusammen mit
Planetilers 1,4 GB Zusatzdaten bleibt das nach Hochrechnung unter 4 GB, aber
das ist gerechnet, nicht gemessen.

Nachweis, dass die Hoehen ankommen (Bremen, `WayLinker`-Codec-Statistik):
`nodeele` waechst von 137 248 auf 678 978 Bit, `transele` von 123 220 auf
554 699 Bit. In der Probefahrt durch Bayern liest der Router Hoehen von
**358 bis 601 m** aus den Kacheln.

### 2.4 Was im Spike gar nicht vorkam

Der Spike lief auf sechs selbst erzeugten Knoten. Drei Dinge waren dort leer
und sind hier ausdruecklich geprueft:

| | Bremen | Saarland | Rheinland-Pfalz | Bayern |
| --- | ---: | ---: | ---: | ---: |
| **Turn-Restrictions** gelesen | 1 152 | — | 12 317 | 20 327 |
| davon verworfen (`badtrs.txt`) | 1 | — | 101 | — |
| **Relationen** (`relations.dat`) | 1,26 MB | — | 6,22 MB | — |
| **Grenzknoten** (`bordernids.dat`) | 0 B | 0 B | **9 445 B** | **21 920 B** |
| erzeugte `.rd5`-Kacheln | 1 | 1 | 2 | 4 |

Bremen und Saarland liegen komplett in je einer 5x5-Grad-Kachel — dort gibt es
schlicht keine Grenzknoten. Rheinland-Pfalz (ueberschreitet 50 Grad Nord) und
Bayern (ueberschreitet 10 Grad Ost **und** 50 Grad Nord) haben welche, und die
Kette verarbeitet sie ohne Fehler. Bei Rheinland-Pfalz verteilen sich die
12 317 Turn-Restrictions sauber auf 7 904 + 4 411 ueber die beiden Kacheln.

**`-DuseDenseMaps=true`** — im Spike als Absturzursache bei Kleinstdaten
notiert — laeuft auf echten Extrakten problemlos. Bremen wurde zur Gegenprobe
mit beiden Einstellungen gebaut: die `.rd5` ist **byteweise identisch**
(1 560 577 B), `true` ist schneller (Cutter 1,9 s statt 2,8 s) und kostet mehr
Speicher (492 MB statt 333 MB). `build_rd5.sh` schaltet deshalb automatisch:
ab 100 MB Eingabe `true`, darunter `false`.

### 2.5 Der Beweis, dass der Score wirkt — auf echten Daten

`verify_region.sh` faehrt drei Profile ueber dieselben Kacheln. Alle drei
pruefen vor `v:opencurv:curve` die Existenz des Tags (siehe §5.1).

**Bayern**, 10,681/48,421 nach 11,900/49,246 (rund 150 km):

| Profil | Strecke | Kosten | mittlerer Score | Hoehen |
| --- | ---: | ---: | ---: | --- |
| neutral (liest den Tag nicht) | 149 736 m | 149 736 | — | 358–591 m |
| **kurvensuchend** | 171 911 m | 121 698 | **5,51** | 360–600 m |
| **kurvenmeidend** | 158 263 m | 181 611 | **1,47** | 360–601 m |

Das kurvensuchende Profil faehrt **22 km Umweg (+14,8 %)** und holt dafuer
einen 3,75-mal hoeheren mittleren Kurven-Score. Genau das ist das Produkt.

**Bremen** zur Gegenprobe, 8,807/53,075 nach 8,620/53,170:

| Profil | Strecke | mittlerer Score |
| --- | ---: | ---: |
| neutral | 19 092 m | — |
| kurvensuchend | 20 309 m | 5,57 |
| kurvenmeidend | 19 806 m | 1,66 |

Bei beiden tragen **100 % der gefahrenen Meter** den Tag. Die Werte stammen
aus `OsmTrack.aggregateMessages()`, also aus dem, was BRouter beim Routen
**aus der Binaerkachel dekodiert** hat — nicht aus der OSM-Eingabe.

### 2.6 Vektorkacheln (Planetiler)

Gemessen an Bremen:

| Posten | Wert |
| --- | ---: |
| Eingabe | 21 MB PBF |
| Ausgabe `de-hb.pmtiles` | **12,3 MB** |
| Gesamtlaufzeit | 660 s (davon der weit ueberwiegende Teil Download) |
| Spitzen-RAM | 1,97 GB |
| **Zusatzdaten einmalig** | **1 377 MB** |

Die Zusatzdaten des OpenMapTiles-Schemas sind der eigentliche Kostenpunkt und
regionsunabhaengig:

| Datei | Groesse |
| --- | ---: |
| `water-polygons-split-3857.zip` | 886 MB |
| `natural_earth_vector.sqlite.zip` | 414 MB |
| `lake_centerline.shp.zip` | 77 MB |

Ohne Cache zahlt **jede** der 16 Regionen diese 1,4 GB erneut. Deshalb liegen
sie in `--download-dir` und werden im Workflow gecacht.

**Zwei harte Funde:**

1. **Planetiler braucht Java 21.** Unter Java 17 bricht es sofort ab:
   `UnsupportedClassVersionError ... class file version 65.0, this version ...
   recognizes ... up to 61.0`. BRouters Mapcreator laeuft dagegen auf 17.
   Der Workflow richtet deshalb **beide** JDKs ein und schaltet nur fuer den
   Planetiler-Schritt auf `JAVA_HOME_21_X64` um.
2. Die Schalter heissen `--download-dir` und `--tmpdir`, **nicht** `--data-dir`.
   Mit falschem Namen laedt Planetiler stillschweigend nach `./data/sources`
   und der Cache greift ins Leere. Alle Schalter in `build_pmtiles.sh` sind
   gegen `planetiler --help` geprueft.

Fuer den Notfall kennt `build_pmtiles.sh` `OPENCURV_PLANETILER_FREE_SOURCES=1`:
das setzt `--free-water-polygons-after-read` und Geschwister und loescht die
Quelldaten direkt nach dem Lesen. Das rettet Plattenplatz und macht den Cache
kaputt — deshalb nur auf Ansage.

### 2.7 Packen lohnt sich nicht

`.rd5` ist bereits bitgepackt. Gemessen an `E5_N45.rd5` (Rheinland-Pfalz,
14 389 703 B): `gzip -6` bringt 13 860 833 B, also **96,3 %** — 3,7 % Ersparnis.

**Konsequenz: `.rd5` wird unkomprimiert ausgeliefert.** Das Handy kann die
Datei direkt ins Segmentverzeichnis legen, ohne Entpackschritt und ohne
zeitweise doppelten Speicherbedarf. Das ist auf einem Telefon mehr wert als
3,7 %.

---

## 3. Passt das auf einen kostenlosen Runner?

**Ja, mit grossem Abstand.** Ein `ubuntu-latest`-Runner hat 4 Kerne, 16 GB RAM
und rund 14 GB freien Plattenplatz.

Plattenplatz-Bilanz eines Bayern-Jobs, Posten fuer Posten:

| Zeitpunkt | belegt |
| --- | ---: |
| Rohdaten geladen | 851 MB |
| nach dem Score (beide Fassungen kurz gleichzeitig) | 1 706 MB |
| Rohdaten geloescht | 855 MB |
| `.bef`-Hoehen (aus dem Cache) | +145 MB |
| Spitze waehrend der rd5-Kette | +673 MB |
| **Spitze gesamt (ohne Vektorkacheln)** | **≈ 1,7 GB** |
| Planetiler-Zusatzdaten (gecacht) | +1 377 MB |
| Planetilers Zwischenablage | Hochrechnung: 1–2 GB |
| **Spitze gesamt (mit Vektorkacheln)** | **Hochrechnung: ≈ 4–5 GB** |

Selbst die pessimistische Variante bleibt unter der Haelfte des Verfuegbaren.
Der Job raeumt zusaetzlich vorinstallierte Pakete weg (`/usr/share/dotnet`,
`/usr/local/lib/android`), was auf `ubuntu-latest` erfahrungsgemaess
zweistellige GB freimacht.

**Arbeitsspeicher:** Spitze 2,90 GB (rd5-Kette Bayern), 1,97 GB (Planetiler
Bremen), 1,41 GB (Rasterwandler). Alles weit unter 16 GB. `-Xmx5500M` ist
bewusst grosszuegig gesetzt.

**Laufzeit** eines Bayern-Jobs, aus den Einzelmessungen zusammengesetzt:

| Schritt | gemessen |
| --- | ---: |
| Rohdaten laden | ~90 s (netzabhaengig) |
| Kurven-Score (Platzhalter) | 379 s |
| rd5-Kette | 123 s |
| Abnahme | ~15 s |
| Hoehen (Cache-Treffer) | ~5 s |
| Hoehen (Cache-Fehltreffer) | 317 s |
| **Summe ohne Vektorkacheln** | **~10 min** |

Das Zeitlimit fuer einen Actions-Job liegt bei 6 Stunden. Selbst mit
Vektorkacheln und kaltem Cache ist das nicht in Reichweite.

**Warum die Recherche daneben lag:** Die Warnung „~14 GB sind der eigentliche
Engpass" ist richtig — sie zielte aber auf ein Vorgehen, bei dem Rohdaten,
Zwischenstaende und Ergebnis gleichzeitig herumliegen. `-Ddeletetmpfiles=true`
allein senkt das Arbeitsverzeichnis bei Bremen von 23 MB auf 9 MB, und die
Rohdaten fliegen raus, sobald der Cutter durch ist.

### Wenn es doch einmal nicht passt

Der Workflow ist so gebaut, dass die Ausweichwege bereits eingebaut sind:

1. **Matrix ueber Bundeslaender** mit `fail-fast: false` — eine gescheiterte
   Region reisst die anderen nicht mit, und der Release-Job veroeffentlicht,
   was fertig geworden ist.
2. **Rohdaten frueh loeschen** — passiert schon, direkt nach dem Cutter.
3. **Vorinstalliertes raeumen** — erster Schritt jedes Regionsjobs.
4. **`runs-on` je Region** — `regions.json` kennt ein Feld `runner`. Eine
   einzelne zu grosse Region bekommt dort einen groesseren Runner, ohne dass
   alle anderen teurer werden.
5. **Region feiner schneiden** — `regions.json` ist regionsagnostisch. Statt
   `nordrhein-westfalen` koennten dort `duesseldorf`, `koeln`, `arnsberg` …
   stehen; Geofabrik bietet die Regierungsbezirke einzeln an.
6. **Vektorkacheln abschalten** — `artifacts: ["rd5"]` in `regions.json` lasst
   den teuersten Schritt weg. Der Planetiler-Schritt ist ausserdem
   fehlertolerant: scheitert er, wird eine Warnung gesetzt und die
   Routing-Kacheln werden trotzdem veroeffentlicht.

---

## 4. Die Konfiguration

### 4.1 Eine Region hinzufuegen

**Nur `tools/pipeline/config/regions.json` anfassen.** Kein Workflow und kein
Skript kennt eine Region beim Namen.

```json
{ "id": "at-tirol", "name": "Tirol",
  "source": "europe/austria/tirol",
  "bbox": [10.0964, 46.6514, 12.9773, 47.7395] }
```

| Feld | Pflicht | Bedeutung |
| --- | --- | --- |
| `id` | ja | Kurzkennung. Taucht im Katalog und in jedem Dateinamen auf. |
| `name` | ja | Klartext fuer Menschen und die App |
| `source` | ja | Pfad bei Geofabrik **ohne** `-latest.osm.pbf` |
| `bbox` | nein | `[minlon, minlat, maxlon, maxlat]`. Fehlt sie, liest die Pipeline die Bounding-Box aus dem PBF-Kopf — **aber der DEM-Job kann dann die Hoehenkacheln nicht vorab bestimmen**. Fuer Regionen mit Hoehen also angeben. |
| `runner` | nein | `runs-on` fuer genau diese Region |
| `curveLevels` | nein | Stufenzahl, Standard 16 |
| `demArcsec` | nein | 1 oder 3, Standard 1 |
| `artifacts` | nein | `["rd5"]`, `["rd5","pmtiles"]` … |
| `enabled` | nein | `false` nimmt die Region aus dem planmaessigen Lauf, laesst sie aber per Hand baubar |

Der Block `defaults` gilt fuer alles, was ein Eintrag nicht selbst setzt.
Danach:

```bash
python3 tools/pipeline/bin/regions.py check     # validiert, Exit 1 bei Fehler
python3 tools/pipeline/bin/regions.py matrix --only at-tirol
```

Die bbox-Werte der 16 Bundeslaender stammen aus
`https://download.geofabrik.de/index-v1.json`, abgerufen am 10.09.2026 — sie
sind nicht geschaetzt.

### 4.2 Die Workflows

| Datei | Ausloeser | Zweck |
| --- | --- | --- |
| `.github/workflows/opencurv-data.yml` | `workflow_dispatch` (Region waehlbar, `dry_run` standardmaessig **an**) + monatlich am 1. um 03:17 UTC | die eigentliche Pipeline |
| `.github/workflows/opencurv-dem-cache.yml` | `workflow_dispatch` | Hoehenkacheln vorwaermen, veroeffentlicht nichts |

`android.yml` wurde **nicht** angefasst.

`opencurv-data.yml` hat fuenf Jobs:

| Job | was er tut |
| --- | --- |
| `vorbereiten` | `regions.json` validieren, Matrix und Release-Tag bestimmen |
| `brouter` | Upstream-BRouter klonen und das Fat-Jar bauen (das einvendorte `brouter/`-Modul enthaelt **keinen** `btools.mapcreator`) |
| `region` | Matrix ueber die Regionen: laden → Score → Hoehen → rd5 → Abnahme → Vektorkacheln |
| `arena` | Testarena erzeugen und als Demo-Paket schnueren |
| `release` | einsammeln, `catalog.json` bauen, Pruefsummen, veroeffentlichen |

**Der erste Lauf sollte sein:** `opencurv-dem-cache.yml` einmal von Hand
(waermt die Hoehen), danach `opencurv-data.yml` mit `region: de-hb` und
`dry_run: true` (kleinste Region, veroeffentlicht nichts). Erst dann `all`.

`dry_run` ist mit Absicht auf `true` vorbelegt: ein versehentlicher Klick
veroeffentlicht nichts.

---

## 5. Die zwei Fallen aus dem Spike — wie die Pipeline sie behandelt

### 5.1 Die NaN-Falle

`v:opencurv:curve` liefert `Float.NaN`, sobald ein Way den Tag **nicht**
traegt. NaN pflanzt sich durch die Kostenformel fort, und BRouter haelt
daraufhin das gesamte Netz fuer unbefahrbar.

**Auf echten Daten reproduziert** (Bremen, Kacheln ohne Tag, Profil ohne
Existenzpruefung):

```
PROFILE=chk_unguarded ERROR=from-position not mapped in existing datafile
```

Dasselbe Profil **mit** Pruefung auf denselben Kacheln faehrt anstandslos.

Die Pipeline sichert das **doppelt** ab:

1. **Jeder routbare Way bekommt den Tag**, auch wenn der Scorer ihn nicht
   bewertet hat — dann mit Wert 0. `apply_scores.py --default 0` (Standard)
   und `placeholder_scorer.py` tun das beide. Gemessen: in der Probefahrt
   tragen 100 % der Meter den Tag.
2. **Jedes Profil prueft die Existenz.** `opencurv_check.brf` enthaelt die
   Pruefung mit ausfuehrlichem Kommentar und ist die Vorlage fuer die
   App-Profile:

```
assign curvecost = switch not opencurv:curve=
                     max 0.2 sub 1.0 multiply 0.053 v:opencurv:curve
                     1.0
```

`verify_region.sh` bricht die Abnahme ab, wenn weniger als 90 % der gefahrenen
Meter den Tag tragen. Eine Region mit Loechern kommt gar nicht erst ins
Release.

### 5.2 Das einvendorte BRouter rechnet die diskrete Werteliste falsch

Deshalb benutzt die Pipeline durchgaengig die **Wildcard-Deklaration**
(`patch_lookups.py --mode num`, ergibt `opencurv:curve;0000000001 *`). Das ist
Weg A aus `RD5_Pipeline.md` §6: feinstufig **und** ohne Patch am einvendorten
Modul.

Der Preis: **es gibt keine Wertebereichspruefung mehr.** Ein kaputter Scorer
koennte `opencurv:curve=999` schreiben, und niemand merkt es. Deshalb prueft
`apply_scores.py` jeden Wert und verwirft Ausreisser mit einer Warnung, bevor
sie in die PBF gelangen.

Der Zusatzvorteil, den `RD5_Pipeline.md` nennt, gilt: Die Stufenzahl steht
nicht mehr in `lookups.dat`. `curveLevels` in `regions.json` laesst sich
aendern, ohne die Lookup-Tabelle anzufassen.

---

## 6. Die Schnittstelle zum Kurven-Score

### 6.1 Der Vertrag

> Eingabe: eine OSM-Datei (`.osm` oder `.osm.pbf`).
> Ausgabe: dieselben Daten, an jedem bewerteten Way zusaetzlich der Tag
> `opencurv:curve` mit einem Wert 0–15.

`run_scorer.sh` ist die **einzige** Stelle, an der der Scorer aufgerufen wird.
Sie sucht in dieser Reihenfolge:

1. `$OPENCURV_SCORER_CMD` — ausdrueckliche Ueberschreibung
2. `tools/curvescore/run.sh`
3. `tools/curvescore/build.gradle.kts` → Gradle-Modul
4. `tools/pipeline/bin/placeholder_scorer.py` — Rueckfallebene

### 6.2 Stand von `tools/curvescore/` bei Abgabe

Das Modul **existiert** und hat genau die passende Schnittstelle. Zwei Punkte,
die die Anbindung praegen:

**(a) `tag` schreibt nur XML.** Im Quelltext steht woertlich:

> `"tag schreibt nur OSM-XML zurueck; PBF-Ausgabe uebernimmt die rd5-Pipeline"`

Ein XML-Umweg kommt fuer ein Bundesland nicht in Frage — Bayern als
unkomprimiertes `.osm`-XML sind zweistellige Gigabyte. Die Anbindung laeuft
deshalb ueber den `score`-Unterbefehl:

```
curvescore score --in region.osm.pbf --json scores.json --levels 16
apply_scores.py  region.osm.pbf scores.json region-scored.osm.pbf
```

`apply_scores.py` liest das JSON **stromweise** (`JSONDecoder.raw_decode` auf
einem mitwachsenden Puffer), weil die Score-Liste fuer ein grosses Bundesland
selbst in die Gigabyte gehen kann.

**(b) Das Modul liess sich am 10.09.2026 nicht uebersetzen** —
`:compileKotlin` schlaegt fehl. Der Kurven-Algorithmiker arbeitet offensichtlich
noch daran. Ich habe `tools/curvescore/` auftragsgemaess **nicht angefasst**.

**Alle Messungen dieses Berichts sind deshalb mit dem Platzhalter entstanden.**
Fuer die Frage „passt das auf einen Runner" ist das unkritisch: der Scorer ist
eine austauschbare Stufe, und die rd5-Kette sieht nur die getaggte PBF. Fuer
die Laufzeit ist es sehr wohl relevant — siehe §8.

`run_scorer.sh` faellt **nicht** stillschweigend auf den Platzhalter zurueck,
wenn der echte Scorer da ist, sich aber nicht bauen laesst. Es bricht mit
einer sprechenden Meldung ab und nennt die Umgebungsvariable, mit der man den
Platzhalter fuer einen einzelnen Lauf erzwingt. Ein Release mit heimlich
falschem Score waere das schlechteste aller Ergebnisse.

---

## 7. Die Katalogdatei

`catalog.json` ist der **einzige Einstiegspunkt fuer die App**. Sie listet
Verzeichnisse nicht auf und raet keine Namen.

### 7.1 Aufbau

```jsonc
{
  "schemaVersion": 1,
  "generated": "2026-09-10T17:42:11Z",       // UTC, ISO-8601
  "release": {
    "tag":     "data-20260910",
    "repo":    "Bwei15/OpenCurv",
    "baseUrl": "https://github.com/Bwei15/OpenCurv/releases/download/data-20260910"
  },
  "producer": {
    "pipeline":        "opencurv/tools/pipeline",
    "curveTag":        "opencurv:curve",
    "curveEncoding":   "wildcard",   // "wildcard" | "enum"
    "curveLevels":     16,           // gueltige Werte 0..curveLevels-1
    "scorer":          "<git sha>",
    "brouterUpstream": "<kurz-sha>",
    "lookupsVersion":  "11.3",       // major.minor -- NUR major muss passen
    "demSource":       "copernicus-glo30"
  },
  "notes": { "rd5": "...", "lookups": "...", "nan": "..." },

  "shared": [                        // regionsunabhaengig, einmal laden
    { "name": "lookups.dat", "kind": "lookups",
      "bytes": 31696, "sha256": "…",
      "url": "https://github.com/…/download/data-20260910/lookups.dat" },
    { "name": "testarena-demo.zip", "kind": "bundle", "bytes": …, "sha256": "…", "url": "…" }
  ],

  "regions": [
    {
      "id":   "de-by",
      "name": "Bayern",
      "bbox": [8.9752, 47.2654, 13.8495, 50.5662],
      "source": { "provider": "geofabrik", "path": "europe/germany/bayern" },
      "totalBytes": 87012542,
      "files": [
        { "name": "de-by_E10_N45.rd5", "kind": "routing",
          "bytes": 72897030, "sha256": "…", "url": "…/de-by_E10_N45.rd5" },
        { "name": "de-by_E10_N50.rd5", "kind": "routing", "bytes": 8010115,  "sha256": "…", "url": "…" },
        { "name": "de-by_E5_N45.rd5",  "kind": "routing", "bytes": 4050393,  "sha256": "…", "url": "…" },
        { "name": "de-by_E5_N50.rd5",  "kind": "routing", "bytes": 2055004,  "sha256": "…", "url": "…" },
        { "name": "de-by.pmtiles",     "kind": "maptiles","bytes": …,        "sha256": "…", "url": "…" }
      ]
    }
  ],
  "totalBytes": 1234567890
}
```

### 7.2 Was die App damit tut

| `kind` | Bedeutung | was die App damit macht |
| --- | --- | --- |
| `routing` | eine `.rd5`-Kachel, **unkomprimiert** | direkt ins Segmentverzeichnis legen. Kein Entpacken. |
| `maptiles` | `.pmtiles` | ueber `pmtiles://file://…` an MapLibre geben |
| `lookups` | `lookups.dat` | neben die `.brf`-Profile legen. **Gehoert zwingend zu ihnen.** |
| `profile` | `.brf` | Routing-Profil |
| `bundle` | `.zip` | z. B. das Testarena-Demopaket |
| `metadata` | `.json` | Beiwerk |
| `other` | alles uebrige | ignorieren, wenn unbekannt |

**Regeln, auf die sich die App-Seite verlassen darf:**

1. `bytes` und `sha256` **jeder** Datei sind da. Nach dem Laden pruefen —
   ein halb geladenes `.rd5` faellt sonst erst beim Routen auf.
2. Dateinamen tragen die `id` der Region als Praefix. Zwei Bundeslaender
   koennen dieselbe 5x5-Grad-Kachel enthalten (`E5_N45` gehoert zu Saarland
   **und** Rheinland-Pfalz), aber mit unterschiedlichem Inhalt.
3. `shared` wird einmal geladen und fuer alle Regionen benutzt.
4. Der Router braucht **alle** `routing`-Dateien einer Region. Eine
   Teilinstallation ergibt Loecher am Kachelrand.
5. Unbekannte Felder und unbekannte `kind`-Werte **ignorieren**, nicht als
   Fehler behandeln. Neue Artefakttypen (H3-Hotspots) kommen so dazu, ohne
   dass alte App-Versionen brechen.
6. `producer.lookupsVersion` ist `major.minor`. **Nur `major` muss passen**;
   das prueft BRouter beim Oeffnen der `.rd5` selbst. Ein Minor-Unterschied ist
   in beide Richtungen unkritisch (im Spike gemessen).

### 7.3 Das 2-GB-Limit

GitHub laesst je Release-Asset maximal 2 GiB zu. Weil **jede `.rd5`-Kachel ein
eigenes Asset** ist, ist das praktisch unerreichbar: die groesste gemessene
Kachel ist `de-by_E10_N45.rd5` mit **72,9 MB** — Faktor 29 Sicherheitsabstand.
Auch eine `.pmtiles` fuer ein ganzes Bundesland bleibt weit darunter
(Hochrechnung aus Bremen: einige hundert MB).

Falls doch: `make_catalog.py --split` zerlegt die Datei in `.partNN` und traegt
im Katalog ein:

```jsonc
{ "name": "de-nw.pmtiles", "kind": "maptiles",
  "bytes": 2400000000, "sha256": "<der GESAMTEN Datei>",
  "split": {
    "parts": [
      { "name": "de-nw.pmtiles.part01", "bytes": 2147483648, "sha256": "…", "url": "…" },
      { "name": "de-nw.pmtiles.part02", "bytes":  252516352, "sha256": "…", "url": "…" }
    ],
    "note": "Teile in dieser Reihenfolge aneinanderhaengen, dann gegen sha256 der Gesamtdatei pruefen."
  }
}
```

Ein Eintrag mit `split` hat **kein** `url`-Feld auf oberster Ebene. Die App
muss also `split` pruefen, bevor sie `url` benutzt. Ohne `--split` bricht die
Katalogerzeugung mit einer sprechenden Meldung ab, statt ein kaputtes Release
zu bauen.

---

## 8. Was ich NICHT pruefen konnte

Ehrliche Liste.

1. **Kein einziger GitHub-Actions-Lauf.** `gh` ist installiert, aber nicht
   angemeldet — auftragsgemaess habe ich keinen Release erzeugt und keinen
   Workflow gestartet. Geprueft ist: YAML parst sauber (PyYAML), jedes
   aufgerufene Skript laeuft lokal auf echten Daten durch, jeder
   Planetiler-Schalter ist gegen `--help` verifiziert, `regions.py check`
   validiert die Konfiguration. **Nicht** geprueft ist alles, was nur der
   Runner kann: ob `fromJSON` die Matrix so annimmt, ob `actions/cache` mit
   diesen Schluesseln greift, ob `setup-java` beide JDKs so ablegt
   (`JAVA_HOME_17_X64`/`JAVA_HOME_21_X64` sind dokumentiert, aber ungetestet),
   ob `gh release upload --clobber` mit dieser Dateimenge zurechtkommt, und ob
   die 14 GB in der Praxis wirklich reichen. `act` ist auf diesem Rechner
   nicht vorhanden.
2. **Die Zahlen stammen von Apple Silicon, nicht von einem Runner.** RAM,
   Plattenplatz und Dateigroessen uebertragen sich unveraendert. **Laufzeiten
   nicht** — ein Runner-Kern ist langsamer, und der Speicher ist geteilt.
   Fuer die Frage „passt es" ist das ohne Belang, fuer „wie lange dauert es"
   sehr wohl. Rechnen Sie mit dem Zwei- bis Dreifachen.
3. **Der echte Kurven-Scorer wurde nie ausgefuehrt** (§6.2). Damit ist offen:
   seine Laufzeit und sein Speicherbedarf auf einem Bundesland, die Groesse
   der Score-JSON, und ob `OsmReader.read()` ein 851-MB-PBF ueberhaupt in den
   Speicher eines Runners bekommt. Das ist das **groesste verbliebene Risiko**
   der Pipeline. `apply_scores.py` ist gegen die dokumentierte Schnittstelle
   geschrieben und syntaktisch geprueft, aber **nie gegen echte Ausgaben des
   echten Scorers gelaufen**.
4. **Vektorkacheln nur fuer Bremen gemessen.** Bayerns `.pmtiles`-Groesse und
   Planetilers Zwischenablage sind Hochrechnung, nicht Messung. Der Schritt
   ist deshalb fehlertolerant gebaut: scheitert er, gehen die Routing-Kacheln
   trotzdem ins Release.
5. **Nordrhein-Westfalen ist die groesste Region, nicht Bayern** (870 MB gegen
   812 MB). Fuer NRW liegen keine eigenen Messwerte vor; der Unterschied zu
   Bayern ist mit 7 % aber so klein, dass die Bayern-Zahlen tragen.
6. **Nichts davon lief auf einem Android-Geraet.** Ob die App die
   `.rd5`-Kacheln mit erweiterter `lookups.dat` und die `.pmtiles` wirklich
   verarbeitet, ist Sache der Welle-3-Agenten. Die Produktionsprofile unter
   `app/src/main/assets/profiles/` habe ich **nicht** angefasst — sie tragen
   die Existenzpruefung aus §5.1 noch **nicht** und wuerden mit
   `v:opencurv:curve` heute in die NaN-Falle laufen.
7. **Die H3-Hotspots gibt es nicht.** Sie waren ausdruecklich optional („nur
   wenn Zeit bleibt"). Die Zeit ging in Stufe 1 und in die Anbindung des
   echten Scorers. Der Katalog ist darauf vorbereitet: ein neuer `kind`-Wert
   genuegt, und Regel 5 in §7.2 sorgt dafuer, dass aeltere App-Versionen
   nicht darueber stolpern.
8. **Der planmaessige Lauf ist nie ausgeloest worden.** Ob der Cron-Ausdruck
   `17 3 1 * *` das tut, was gedacht ist, zeigt erst der 1. Oktober.
9. **`gh release upload` mit rund 70 Dateien** ist ungetestet. Bei einem
   Teilfehler bleibt ein halb gefuelltes Release stehen. `--clobber` erlaubt
   einen Wiederholungslauf, der einzelne Dateien ersetzt.

---

## 9. Nachinstalliert

| Werkzeug | wofuer | wie |
| --- | --- | --- |
| **GDAL 3.13.3** | Copernicus-COG nach `.hgt` (Spaltenzahl ist breitenabhaengig) | `brew install gdal` — im Workflow `apt-get install -y gdal-bin` |
| **pyosmium 4.3.1** | PBF lesen und schreiben im Platzhalter-Scorer und in `apply_scores.py` | `pip install osmium` in einem venv unter dem Scratchpad — im Workflow `pip install osmium` |
| PyYAML | nur zur lokalen Pruefung der Workflow-Dateien | venv, wird von der Pipeline nicht gebraucht |

`osmium-tool`, `osmosis` und `osmconvert` werden **nicht** gebraucht.

Im Repository ist ausser den beschriebenen Dateien nichts entstanden. Alle
grossen Zwischenstaende liegen im Scratchpad. `.gitignore` wurde um die
Ausgaben dieser Pipeline ergaenzt (`*.osm.pbf`, `*.pmtiles`, `*.bef`, `*.hgt`,
`catalog.json`, `SHA256SUMS`, `tools/pipeline/work/`, `tools/pipeline/out/`).

---

## 10. Die zwei groessten Risiken

**1. Der echte Kurven-Scorer ist die einzige nie ausgefuehrte Stufe.** Er hat
die passende Schnittstelle, aber er liess sich am 10.09.2026 nicht
uebersetzen, und `OsmReader.read()` laedt eine ganze Datei in den Speicher.
Ob ein 851-MB-Bayern-PBF so in einen Runner passt, ist offen. Faellt das um,
faellt der Kern des Produkts um — die rd5-Kette selbst ist dann zwar in
Ordnung, aber sie transportiert nur Platzhalterwerte.
*Was hilft:* `run_scorer.sh` bricht sichtbar ab statt heimlich zurueckzufallen;
erster echter Lauf mit `region: de-hb`, nicht mit `all`.

**2. Kein einziger Actions-Lauf.** Die Pipeline ist lokal Stufe fuer Stufe
gemessen, aber als Ganzes nie in der Umgebung gelaufen, fuer die sie gebaut
ist. Erfahrungsgemaess bricht sich so etwas an Kleinigkeiten die Beine:
Matrix-Serialisierung, Cache-Schluessel, Rechte des `GITHUB_TOKEN`, die
Ablageorte der beiden JDKs.
*Was hilft:* `dry_run` ist standardmaessig an; die erste Ausfuehrung mit der
kleinsten Region kostet Minuten und faengt genau diese Klasse Fehler.
