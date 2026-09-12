# OpenCurv — Umbauplan und Agenten-Orchestrierung

Stand: 10.09.2026. Dieses Dokument ist das Steuerpult für den großen Umbau von
OpenCurv. Es hält fest, **wer was baut, in welcher Reihenfolge und warum** —
damit spätere Sitzungen nicht neu anfangen müssen.

## Das Ziel in einem Satz

Eine kostenlose, vollständig offline arbeitende Motorradnavigation, die
**kurvenreiche Strecken** findet — und zwar so gut, dass sie eine echte
Alternative zu Calimoto ist.

## Die vier Grundentscheidungen (mit dem Auftraggeber abgestimmt)

| Frage | Entscheidung |
| --- | --- |
| Routing-Engine auf dem Handy | **Entschieden: BRouter bleibt.** Begründung siehe Abschnitt "Die Routing-Entscheidung" unten. |
| GitHub-Releases | `gh` ist lokal installiert; der Auftraggeber meldet sich einmalig mit `gh auth login` an. |
| Regionen der Pipeline | Deutschland, nach Bundesländern. Die Pipeline wird regionsagnostisch gebaut und ist später erweiterbar. |
| Erste Priorität | Der Kurven-Algorithmus und die Testarena. Erst der Beweis, dann die Verpackung. |

## Die Architektur, auf die wir umbauen

```
GitHub Actions (die Rechenzentrale, kostet nichts)
  │
  ├─ OSM-Rohdaten (Geofabrik) + Höhenmodell (Copernicus DEM)
  │
  ├─ Artefakt 1: Routing-Daten mit VORBERECHNETEM Kurven-Score je Kante
  ├─ Artefakt 2: Vektorkacheln für die Kartendarstellung
  └─ Artefakt 3: H3-Hexagonraster mit regionalen Kurven-Hotspots
        │
        ▼  als GitHub-Release veröffentlicht, max. 2 GB je Datei
     Handy
        ├─ lädt genau diese Artefakte, sonst nichts
        ├─ rendert die Karte auf der GPU (MapLibre Native, 3D, Tag/Nacht)
        └─ rechnet NUR noch die Route auf den fertig vorbereiteten Daten
```

Der Bruch zum Bestand: heute rendert Mapsforge auf der CPU und die App lädt
fremde Karten von mapsforge.org. Beides fällt weg.

## Die Routing-Entscheidung (10.09.2026)

**BRouter bleibt die Routing-Engine auf dem Handy.**

GraphHopper wäre inhaltlich der naheliegende Kandidat gewesen: Es hat einen
`curvature`-Wert und Custom-Models bereits eingebaut, und Kurviger — unser
direkter Wettbewerber — baut nachweislich darauf auf. Es scheitert aber an
etwas Banalerem: GraphHopper hat den offiziellen Android-Support mit Version
2.0 eingestellt, und in der Praxis bricht `graphhopper-core` auf Android an
`java.awt`-Importen, fehlschlagender Log4j-URL-Auflösung im APK und
ART-Klasseninitialisierungsfehlern. Das ist im offiziellen Issue-Tracker
belegt, nicht vermutet. Valhalla hat mit `Rallista/valhalla-mobile` aktive
Mobile-Bindings, ist aber Pre-1.0 und ein Drittanbieter-Fork — als
Beobachtungskandidat vormerken, nicht jetzt migrieren.

BRouter dagegen wird aktiv gepflegt, liegt bereits einvendort im Repo, prägt
SRTM-Höhen schon selbst auf und ist MIT-lizenziert.

### Wie der Kurven-Score in die Routing-Daten kommt

Die Recherche schlug vor, BRouters "Pseudo Tags" aus dem Produktions-Build-
Skript zu nutzen. **Das ist verworfen.** Dieser Mechanismus ist in der Doku nur
als Randnotiz erwähnt und nirgends vorgeführt — der Recherche-Agent hat ihn
selbst als größtes Projektrisiko markiert. Auf einen undokumentierten Pfad
setzen wir die tragende Säule der Architektur nicht.

Stattdessen der regulär spezifizierte Weg:

1. Der Kurven-Score wird **vor** BRouter berechnet, in unserer eigenen
   Verarbeitungsstufe.
2. Er wird als **echter OSM-Tag** (z.B. `opencurv:curve=0..15`) an den Way
   zurückgeschrieben.
3. `lookups.dat` wird um diesen Tag erweitert — Anhängen am Ende ist laut
   Profile Developers Guide ausdrücklich erlaubt und versionsverträglich.
4. BRouters normale Kachel-Erzeugung läuft unverändert darüber.
5. Das `.brf`-Profil liest den Tag und macht ihn zum Kostenfaktor.

Nur dokumentiertes Verhalten, kein Sonderweg. Die Pseudo-Tags bleiben als
mögliche spätere Optimierung im Hinterkopf, nicht als Voraussetzung.

### Der Beweis ist erbracht (10.09.2026)

Der Spike ist gelaufen und die Annahme **hält**. Unabhängig nachgeprüft: aus
einem leeren Verzeichnis komplett neu erzeugt, mit demselben Ergebnis.

- Der frei erfundene Tag `opencurv:curve` überlebt BRouters reguläre
  Kachelerzeugung und wird aus der `.rd5` dekodiert — auch vom **einvendorten**
  Modul, also von dem Code, der tatsächlich auf dem Handy läuft.
- Die Routenwahl ändert sich messbar allein wegen des Tags: Das Profil, das
  niedrige Scores belohnt, nimmt die 4 m längere Südroute.
- **Der Score ist feinstufig numerisch nutzbar**, nicht nur als grobe Klasse —
  Voraussetzung ist die Deklaration als Wildcard `opencurv:curve;0 *`, dann
  liefert `v:opencurv:curve` die echte Zahl (gemessen: `costfactor=0.25` für
  `1.0 − 0.05·15`).
- Kosten: log2(Stufenzahl) Bit je Link, also 4 Bit bei 16 Stufen.
- Der undokumentierte Pseudo-Tag-Weg wurde nicht gebraucht.

Reproduzierbar über `tools/rd5build/run_spike.sh [enum|num]`. Der einvendorte
`brouter/`-Ordner enthält **keinen** `btools.mapcreator` — die Kachelerzeugung
braucht den Upstream-BRouter, den das Skript sich selbst klont und baut.

#### Zwei Fallen, die die Pipeline und die Profile beachten MÜSSEN

1. **NaN-Falle:** `v:opencurv:curve` liefert für einen Weg **ohne** den Tag NaN,
   und NaN in der Kostenformel macht das gesamte Netz unbefahrbar (gemessen:
   `from-position not mapped in existing datafile`). Jedes Profil braucht eine
   Existenzprüfung `switch not opencurv:curve= …`. "Nicht gesetzt" ist nicht
   dasselbe wie der Wert "0".
2. **Das einvendorte BRouter ist hinter dem Upstream zurück:**
   `BExpressionContext.getLookupValue` fehlt der `val < 900`-Zweig. Deshalb
   rechnet die **diskrete Werteliste** numerisch falsch (−9,83 statt 15), die
   **Wildcard-Variante** dagegen richtig. Entweder Wildcard benutzen oder das
   einvendorte Modul auf Upstream-Stand bringen.

Offen und ausdrücklich **nicht** gemessen: Der Beweis lief auf selbst erzeugten
Kleinstdaten. Ob die Kette auf einem echten Geofabrik-Extrakt durchläuft —
Laufzeit, Speicher, Grenzknoten, Turn-Restrictions, Relationen — und wie stark
die realen `.rd5` wachsen, ist gerechnet, nicht gemessen. Das ist die erste
Aufgabe des Pipeline-Agenten.

Bericht: `1.Doku/RD5_Pipeline.md`.

### Wie das Risiko bewiesen wurde

Bevor darauf eine Cloud-Pipeline gebaut wird, läuft ein **Spike** (Opus): ein
Prüfstand mit zwei sonst völlig identischen Wegen, die sich einzig im neuen Tag
unterscheiden. Der Beweis gilt als erbracht, wenn der Router allein wegen
dieses Tags messbar den einen Weg dem anderen vorzieht. Erst danach wird die
Pipeline gebaut. Die praktisch wichtigste Nebenfrage: Kann ein Profil den Tag
**numerisch** in einer Kostenformel verwenden, oder nur auf Gleichheit prüfen?
Davon hängt ab, ob wir einen feinstufigen Score bekommen oder nur grobe Klassen.

Bericht: `1.Doku/RD5_Pipeline.md`. Recherchegrundlage:
`1.Doku/Research_Tech_Options.md`.

### Die weiteren Technologie-Entscheidungen aus der Recherche

| Thema | Entscheidung |
| --- | --- |
| Vektorkacheln erzeugen | Planetiler (Apache-2.0) |
| Kacheln auf dem Handy | PMTiles über `pmtiles://file://`, nativ ab MapLibre Android 11.7.0 — eine Datei, kein eigener Server |
| Höhendaten | Copernicus DEM GLO-30 über AWS S3, aufgeprägt mit BRouters eigenem SRTM-Importer |
| Kurvigkeits-Metrik | `adamfranco/curvature` als Ideengeber; Eigenimplementierung, da GPL-3.0 nicht zu uns passt |
| H3-Hotspots | `h3-android` (Apache-2.0), optional |
| MapLibre Navigation SDK | Nicht einbinden — passt nicht zum reinen Offline-On-Device-Modell |

Offene Warnungen aus der Recherche, die die Pipeline berücksichtigen muss:
GitHub-Runner haben nur ~14 GB freien Plattenplatz (der eigentliche Engpass,
nicht der Arbeitsspeicher), und MapLibres PMTiles-Unterstützung ist mit Anfang
2025 noch jung.

## Agenten-Aufstellung

Jeder Agent bekommt genau einen Auftrag, ein eigenes Verzeichnis und darf
nichts außerhalb davon anfassen. Kein Agent führt verändernde git-Kommandos
aus — das Zusammenführen macht die Orchestrierung.

### Welle 0 — Grundlagen (läuft)

| Agent | Modell | Auftrag | Verzeichnis |
| --- | --- | --- | --- |
| Repo-Kartograph | Haiku | Bestandsaufnahme des Workspaces für spätere KI-Sitzungen | `1.Doku/AI_Workspace_Overview.md` |
| Tech-Scout | Sonnet | Entscheidungsvorlage: welche Open-Source-Bausteine für Routing, Kacheln, Rendering, Höhen, H3, Audio | `1.Doku/Research_Tech_Options.md` |

*Warum diese Modelle:* Die Bestandsaufnahme ist mechanisches Lesen und
Zusammenschreiben — dafür reicht das schnellste Modell. Die Recherche braucht
Urteilsvermögen über Reifegrad und Risiko, aber keine Spitzenleistung.

### Welle 1 — Der Kern (läuft)

| Agent | Modell | Auftrag | Verzeichnis |
| --- | --- | --- | --- |
| Testarena-Bauer | Sonnet | Synthetische Karte mit bekannter Geometrie + Mess-Harness, um Kurven-Algorithmen überhaupt prüfbar zu machen | `tools/testarena/`, `1.Doku/Testarena.md` |
| Design-Spezialist | Opus | Einheitliches Design-System (hell/dunkel), Compose-Tokens, neues App-Icon, Bildbelege | `1.Doku/Design_System.md`, `ui/theme/`, Icon-Ressourcen |

*Warum diese Modelle:* Die Arena ist gut spezifizierte Fleißarbeit mit klaren
Prüfkriterien. Das Design verlangt echte Abwägung — "modern und verspielt"
gegen "mit Handschuhen bei 120 km/h lesbar" ist ein Zielkonflikt, den nur ein
starkes Modell sauber auflöst statt ihn zu verwaschen.

### Welle 2 — geplant, startet nach Recherche und Arena

| Agent | Modell | Auftrag |
| --- | --- | --- |
| Kurven-Algorithmiker | Opus | Der Fahrspaß-Score je Straßenabschnitt: Winkeländerung, S-Kurven-Wechsel, Radienverlauf, Steigung, Umgebung; Abwertung von Ortsgittern, 90°-Abbiegern, schlechtem Belag. Wird gegen die Testarena gemessen. **Hier wird nicht gespart.** |
| Cloud-Pipeline-Ingenieur | Opus | GitHub-Actions-Workflows: Rohdaten holen, Höhen aufprägen, Score rechnen, Routing-Artefakt + Vektorkacheln + H3-Raster bauen, als Release veröffentlichen. |

### Welle 3 — geplant

| Agent | Modell | Auftrag |
| --- | --- | --- |
| Karten-Renderer-Ingenieur | Opus | Mapsforge raus, MapLibre Native rein: Vektorkacheln offline, Style aus `Map_Design.md`, Tag/Nacht, stufenlose 3D-Perspektive. |
| Datenschicht-Ingenieur | Sonnet | Regionenauswahl und Download ausschließlich aus den GitHub-Release-Artefakten; alte Download-Quellen entfernen. |
| Sprachausgabe-Ingenieur | Sonnet | Bluetooth-Preroll gegen die Aufwachlatenz der Helm-Headsets, Ducking statt Stopp, zeitbasierte statt distanzbasierte Trigger, Sprechverbot in Schräglage, kommandobasierte Formulierungen. |

### Welle 4 — geplant

| Agent | Modell | Auftrag |
| --- | --- | --- |
| QA- und Emulator-Tester | Sonnet | Bauen, auf dem Emulator starten, Bildschirmfotos, Regressionen melden. |
| Repo-Hygiene und Release | Sonnet | README mit Inhaltsverzeichnis und Demobildern, Aufräumen, Release-Kandidat v0.1.5 auf GitHub. |

## Warum diese Reihenfolge

1. **Erst messbar machen, dann bauen.** Ohne die Testarena ist jede Aussage
   über den Kurven-Algorithmus Geschmackssache. Die Arena kommt zuerst.
2. **Erst entscheiden, dann investieren.** Die Wahl der Routing-Engine hängt an
   Fakten, die der Tech-Scout liefert. Solange die fehlen, wird kein Code
   geschrieben, der von der Entscheidung abhängt.
3. **Design läuft nebenher.** Es hängt an keiner technischen Entscheidung und
   blockiert nichts — deshalb schon in Welle 1.
4. **README und Release ganz zum Schluss**, weil sie die neue Architektur
   beschreiben sollen und nicht die alte.

## Werkzeugkette auf diesem Rechner

- Java 17 (Homebrew), Gradle über `./gradlew`
- Android SDK: `~/Library/Android/sdk` — `adb` und `emulator` sind **nicht** im
  PATH, volle Pfade nötig
- AVD `Medium_Phone`, ein Emulator läuft bereits
- `gh` ist installiert, Anmeldung durch den Auftraggeber
- Repository: `git@github.com:Bwei15/OpenCurv.git`

## Befunde vom Emulator-Lauf (10.09.2026)

Debug-APK gebaut, auf dem Emulator installiert und gestartet: **kein Absturz**,
neues Icon und neue Farbtokens sind wirksam. Dabei fielen Mängel auf, die in
Dateien liegen, die der Design-Agent ausdrücklich nicht anfassen durfte. Sie
gehören zum Auftrag des Agenten, der in Welle 3 die Oberfläche auf das
Design-System umstellt:

1. **Zwei überlappende Tag/Nacht-Knöpfe** am rechten Rand, einer davon halb
   hinter dem Bottom-Sheet. Ein Layoutfehler, kein Designfehler.
2. **Das Bottom-Sheet verdeckt über die Hälfte des Bildschirms**, obwohl kein
   Ziel gesetzt ist. Im Ruhezustand gehört der Karte der Platz.
3. **Das Sheet trägt die HUD-dunkle Gestaltung**, obwohl es eine Ruhe-Ansicht
   ist. Das widerspricht dem Design-System direkt: verspielt und hell im Stand,
   nüchtern und dunkel nur in Fahrt. Ursache ist, dass nur die Tokens ersetzt
   wurden, nicht die Aufrufstellen.
4. **Die Suchleiste liegt unter der Statusleiste** — der obere Sicherheitsrand
   wird nicht beachtet.
5. Dazu die drei Befunde des Design-Agenten aus dessen Abschnitt 12:
   `MetricReadout` mit 30 sp liegt unter dem lesbaren Mindestwert,
   `ic_maneuver_roundabout` ist beschnitten, und die 18 Aktionssymbole
   verwenden sechs verschiedene Strichstärken.

---

## Fortschritt (10.09.2026, Stand nach Welle 3)

### Abgeschlossen und nachgeprüft

| Ergebnis | Beleg |
| --- | --- |
| Bestandsaufnahme des Workspaces | `AI_Workspace_Overview.md` |
| Technologie-Entscheidungsvorlage | `Research_Tech_Options.md` |
| Design-System, Compose-Tokens, neues App-Icon | `Design_System.md`, Build grün, auf dem Emulator sichtbar |
| Testarena mit Mess-Harness | `Testarena.md`, 13 Tests grün |
| Beweis, dass der Kurven-Tag durch BRouter kommt | `RD5_Pipeline.md`, unabhängig aus leerem Verzeichnis nachvollzogen |
| **Der Kurven-Score** | `Kurven_Score.md`, 50 Tests grün, `tools/curvescore/report/arena.svg` |
| **Cloud-Pipeline & GitHub Actions** | `Cloud_Pipeline.md` — **Erfolgreich auf GitHub Actions gelaufen** (Run 34520134312, 100% grün, Bremen Artefakte erzeugt) |
| **Sprachausgabe & Audio-Timing** | `Sprachausgabe.md`, Commit `c5c349f`, 145 Tests grün |
| **Karten-Rendering (MapLibre Native)** | `Karte_MapLibre.md`, GPU-Vektorrendering, PMTiles offline, Tag/Nacht-Styles, MapLibreStyleTest grün, 4 Layoutmängel behoben, Emulator-Screenshots in `1.Doku/design/screens/` |

### Welle 3 — Abgeschlossen

| Agent | Modell | Ergebnis |
| --- | --- | --- |
| Pipeline-Integrator & Debugger | Sonnet | Echter Scorer durch Pipeline, PrimitiveNodeStore-Speicherreduktion, Runner-Cleanup-Fix für JDKs und absolute Pfade in `build_rd5.sh`. GitHub Actions Workflow `opencurv-data.yml` für `de-hb` erfolgreich durchgelaufen. |
| Sprachausgabe | Sonnet | Ursache der Mehrfachansagen behoben, zeitbasierte Trigger in Sekunden, Headset-Aufwach-Chime (340 ms), Schräglagen-Sprechverbot. Commit `c5c349f`. |
| Karten-Renderer & UI-Polisher | Sonnet | Mapsforge-Rendering durch MapLibre Native SDK (11.11.0) ersetzt. Vektorkacheln via PMTiles offline geladen. Tag/Nacht-Styles mit lokalen Offline-Glyphen. Alle 4 UI-Befunde (Zahnrad-Icon, Statusbar-Insets, kompakter Sheet-Peek, HUD-Schriftgrößen 34 sp) behoben. `MapLibreStyleTest` grün. |

### Welle 4 — Abgeschlossen
 
| Agent | Modell | Ergebnis |
| --- | --- | --- |
| Ortssuche- & Daten-Ingenieur | Sonnet | Ortssuche und Regionen-Download entkoppelt: GitHub-Release-Downloads (`github.com`, `objects.githubusercontent.com`), PMTiles + RD5 Download in `DownloadRepository`, abwärtskompatibler `MapCatalog` für `catalog.json` & `regions.json`. Schneller autarker TSV-Starter-Ortskatalog (`places_de.tsv` mit 129 Zielen), sodass Fahrer sofort offline suchen und planen können. Commit `88257f5`. |
| Release & Dokumentation | Sonnet | README aktualisiert (MapLibre Native, PMTiles, OSM-Kurventagging, Screenshots, lokale APK-Builds), APK-Assemble-Job aus GitHub Actions entfernt (Commit `bd91125`), Release-Build verifiziert. 158/158 Android-Unit-Tests grün, JVM-Verifier 100% grün. |

### Welle 5 — Abgeschlossen: Echtzeitverkehr & Baustellen-Vermeidung (Mobilithek / BMDV)

| Agent | Modell | Ergebnis |
| --- | --- | --- |
| Verkehrsdaten-Architekt & Routing | Sonnet | Datenmodell `TrafficIncident`, `NoGoArea` und `NoGoPolygon` erstellt. GeoJSON-Parser `MobilithekTrafficParser` für BMDV- / Mobilithek- / DATEX-II-Meldungen implementiert (Punkt- und Streckensperrungen, Pässe, Baustellen). `TrafficRepository` mit Offline-First-Cache hinzugefügt. |
| Dynamisches Routing (No-Go) | Sonnet | `BRouterEngine` und `RouteRequest` um `noGos` und `noGoPolygons` erweitert; Übergabe an BRouters `RoutingContext.nogopoints` mit automatischer Vermeidung bei Routenberechnung und Alternativen. Verdrahtung in `NavigationController` und `AppContainer`. |
| Verifikation | Sonnet | Tests hinzugefügt: `TrafficIncidentTest`, `MobilithekTrafficParserTest`, `BRouterNoGoTest`. 167/167 Tests im JVM-Verifier grün, alle Android-Unit-Tests grün, APK auf Emulator installiert und verifiziert. |

### Noch offen

- **H3-Kurven-Hotspots** (optional für spätere automatische Rundtouren-Generierung).
- **GitHub Release Tag v0.1.5** mit den erzeugten Kacheln und lokaler Release-APK.





---

## Welle 6 bis 8 — Auftrag vom 11.09.2026 (Orchestrierung: Opus)

### Befunde vor dem Start

1. **Welle 4 war nicht abgeschlossen**: Der Download aus GitHub-Releases scheitert
   mit "release-assets.githubusercontent.com" — GitHub leitet Release-Assets
   inzwischen auf diesen Host um, der in `DownloadTarget.ALLOWED_HOSTS` fehlt.
   Zweiter Fehler: `releases/latest/download/catalog.json` löst auf das
   APK-Release `v1.0.0` auf, das keinen Katalog enthält (404). Die Daten liegen
   unter dem Tag `data-20260910`.
2. **Welle 5 ist ein Gerüst ohne Datenquelle**: Parser, Modell und Cache
   existieren, aber nichts lädt je Verkehrsdaten und nichts zeigt sie an.
   Als frei nutzbare, schlüssellose Quelle geprüft: die Autobahn-API des BMDV
   (`https://verkehr.autobahn.de/o/autobahn/`, JSON, Sperrungen/Baustellen/
   Warnungen). Landesstraßen-Meldungen (Mobilithek, DATEX II) brauchen eine
   Registrierung — als Erweiterungspunkt vorsehen, nicht jetzt.
3. **Ortssuche hängt noch an Mapsforge-`.map`-Dateien**, die es seit dem
   MapLibre-Umbau nicht mehr gibt. Effektiv sucht die App nur in 129
   handgepflegten Orten; Straßen und Hausnummern fehlen ganz. Dafür muss die
   Pipeline einen Adress-Index je Region liefern.
4. Lokale Testdaten für Niedersachsen liegen unter `~/Downloads/region-de-ni/`
   (`de-ni.pmtiles`, `E5_N50.rd5`, `E10_N50.rd5`) und auf dem Emulator.

### Arbeitsweise

Jeder Agent arbeitet in einem **eigenen git-Worktree** (Branch), damit
parallele Gradle-Builds und Dateiänderungen sich nicht in die Quere kommen.
Kein Agent committet auf `main`; die Orchestrierung führt die Branches
nacheinander zusammen, löst Konflikte und fährt die Abnahme (Tests, Build,
Emulator). Dateibesitz ist je Agent festgelegt; fremde Dateien nur additiv.

### Welle 6 — Daten (parallel)

| Nr. | Agent | Modell | Auftrag | Eigene Dateien |
| --- | --- | --- | --- | --- |
| 6.1 | Download-Reparatur | Sonnet | `release-assets.githubusercontent.com` freischalten, neuesten `data-*`-Release statt `latest` ermitteln, SHA-256 aus dem Katalog nach dem Download prüfen, auf dem Emulator echten Download nachweisen. Schließt Welle 4 ab. | `data/download/*`, `res/xml/network_security_config.xml`, zugehörige Tests |
| 6.2 | Verkehrsdaten-Live | Sonnet | Fetcher für die Autobahn-API, Verbindungsprüfung (online → laden, offline → Cache), Aktualisierungs-Takt, Ablauf alter Meldungen, Verdrahtung in `AppContainer`. | `data/traffic/*`, neue `TrafficFeed`-Dateien, Tests |
| 6.3 | Blitzer | Sonnet | Stationäre Blitzer aus OSM (`highway=speed_camera`): Pipeline-Schritt je Region + Starter-Datei für Niedersachsen, `SpeedCameraRepository`, Warnlogik (≤ 1 km, Fahrtrichtung auf den Blitzer zu), Sprachansage, eigenständiges Warn-Composable (rot, Kamera-Icon, "Blitzer"). Einbau ins HUD erst in 7.3. | `data/cameras/*`, `domain/cameras/*`, `ui/navigation/SpeedCameraAlert.kt`, `tools/pipeline/bin/build_cameras.py`, Workflow-Schritt |
| 6.4 | Adress-Index | Opus | Pipeline erzeugt je Region eine SQLite-Datei mit Orten, Straßen und Hausnummern; Katalog-Art `places`; App lädt sie mit der Region und sucht darin (inkl. "Straße 12, Ort"). Startet nach 6.1, weil beide den Download-Code anfassen. | `tools/pipeline/bin/build_places.py`, `data/search/*`, Download-Anbindung |

*Warum diese Modelle:* 6.1 bis 6.3 sind klar spezifizierte, gut prüfbare
Aufgaben. 6.4 verlangt Entwurfsentscheidungen (Datenformat, Größe, Ranking
von Hausnummern gegen Orte) — dafür das große Modell.

### Welle 7 — Oberfläche (parallel, nach Welle 6)

| Nr. | Agent | Modell | Auftrag | Eigene Dateien |
| --- | --- | --- | --- | --- |
| 7.1 | Karten-Overlays | Opus | Positionsmarke als reines, großes Dreieck; POI-Icons (Tankstelle, Restaurant) statt Farbpunkte, größer, antippbar → als Ziel/Zwischenziel wählbar; Barriere-Icon für Sperrungen; Kamera-Icon für Blitzer; Karte in der Navigation gekippt wie im Demo-Modus. | `ui/map/MapController.kt`, `ui/map/MapScreen.kt`, `assets/maplibre/*.json`, Drawables |
| 7.2 | Planung & Touren | Opus | Sheet von überall ziehbar mit Einrasten; eingeklappt nur Play-Knopf + Kernzahlen; Mehrpunkt-Touren und Rundtour; letzte Ziele und letzte Routen; automatische Neuberechnung bei Profil-/Kurvenhunger-Wechsel; Nachweis, dass Profile und Kurvenhunger die Route messbar ändern. | `ui/components/DraggableSheet.kt`, `ui/plan/*`, `ui/search/*`, `ui/map/MapViewModel.kt`, neue `data/history/*` |
| 7.3 | Navigations-HUD | Opus | Kurvigkeits-Anzeige raus; Ankunft/Restkilometer klein in die Ecke; Verkehrszeichen + gefahrene Geschwindigkeit als ein Element rechts; Lautstärke/Neuladen/Beenden zu einem aufklappbaren Menü; Zentrieren bleibt eigenständig; Blitzer-Warnung aus 6.3 einbauen. | `ui/navigation/*` |

### Welle 8 — Abnahme (Orchestrierung)

Tests (`testDebugUnitTest`, Verifier), Debug-APK, Emulator-Lauf mit
Bildschirmfotos, Download-Nachweis, Projektplan und Workspace-Overview
nachziehen.

---

## Fortschritt Welle 6–8 (Stand 12.09.2026)

Arbeitsweise nach zwei Limit-Abbrüchen umgestellt: **ein Agent nach dem
anderen**, Sonnet als Standard, kleine Aufträge, Worktrees von Hand
(`git worktree add … main`), Abnahme durch die Orchestrierung. Details und
Befunde je Schritt: `Welle6_7_Status.md`.

### Abgeschlossen und in `main`

| Schritt | Ergebnis | Beleg |
| --- | --- | --- |
| 6.1 Download-Reparatur | `release-assets.githubusercontent.com` + `api.github.com` erlaubt, Katalog aus neuestem `data-*`-Release, SHA-256/Größe geprüft (auch Resume). Niedersachsen (427 MB) im Emulator vollständig geladen — Welle 4 damit abgeschlossen. | `cde9996`, `design/screens/welle6_download_*` |
| 6.2 Verkehrsdaten live | Autobahn-API (BMDV, ohne Schlüssel): Sperrungen, Baustellen, Warnungen; alle 30 min bei Netz, offline aus dem Cache; nur Vollsperrungen werden NoGos. | `5396877`, `Verkehrsdaten.md` |
| 6.3 Blitzer | OSM `highway=speed_camera` je Region (Pipeline-Schritt + 1 275 Starter-Blitzer Niedersachsen), Warnung ≤ 1 km in Fahrtrichtung mit Ansage und rotem Vollbild, Opt-in (§23 StVO). | `159e925`, `Blitzer.md` |
| 6.4 Adress-Index | Pipeline `build_places.py` → `<region>.places.sqlite` (Orte, Straßen, Hausnummern; Niedersachsen 122 MB, 2,45 Mio. Adressen, 5 min); App: Download-Art `places`, `QueryParser` („Georgstr. 10 Hannover“), `SqlitePlaceIndex`, Ranking; Mapsforge-Reste entfernt. Suche in 60–120 ms. | `762e4c7`, `c710a58`, `Ortssuche.md`, `design/screens/welle8_suche_adresse.png` |
| 7.1 Karten-Overlays | Dreieck-Puck, Sperrungen rot mit Barriere-Icon, Blitzer-Icons, POI-Tippkarte („Als Ziel“/„Zwischenziel“), Karte in Fahrt gekippt (≥ 45°), Kipp-Bug nach Demo behoben. | `20530c7`, `64f1cf1` |
| 7.2a Sheet | Von überall ziehbar, Einrasten nach Weg + Fling, Peek mit Fahrzeit + rundem Play-Knopf, Auto-Neuberechnung bei Profil/Kurvenhunger (400 ms Debounce, Cancel). | `b05085a` |
| 7.2b Touren & Verlauf | Stoppliste (umordnen/löschen), Stopp per Suche/Langdruck, Rundtour-Schalter + Vorschlag nach Wunschlänge, letzte Ziele in der Suche, letzte Touren im Sheet. | `e2705aa` |
| 7.2c/d Routing | **Kacheln wurden nie benutzt:** Katalogname `de-ni_E5_N50.rd5` ≠ BRouter-Name `E5_N50.rd5` → App routete auf altem Tile ohne Kurven-Tag; jetzt kanonische Namen. Alternativen 3→1, `pass1coefficient` 1.5, Profile nachgeschärft (curvy(2) kurvigste Option, +30–50 % Länge). NoGo-Korridorfilter (2 281 → 4 Kreise; Auslöser war ein Wegpunkt bei (0,0) ohne GPS). | `68f2d22`, `80da8b0`, `Kurven_Score.md` §13 |
| 7.3 HUD | Kurvigkeit raus, Ankunft/Restkilometer klein unten links, Tempolimit-Schild + Tacho als ein Element rechts, Lautstärke/Neuladen/Beenden als Klappmenü, Zentrieren eigenständig, Blitzer-Warnung im HUD + Banner im Ruhebildschirm. | `bef52d6` |

Abnahme: App-Unit-Tests 300/300, JVM-Verifier 267/267, Debug- und
Release-APK bauen.

### Offen nach Welle 8

1. **Routing-Tempo auf dem Emulator**: JVM 0,1 s (2 km) / 0,9 s (69 km),
   Emulator 5–12 s (2 km), 40 km laufen in den 60-s-Timeout. Thread-Dump zeigt
   reine Java-Dekodierung der Kacheln (`DirectWeaver`) — kein Warten. Der
   Emulator rendert die Karte per Software-GPU bei 320 % CPU; ob ein echtes
   Gerät betroffen ist, ist **nicht gemessen** (Release-Build-Vergleich lief
   beim Schreiben noch). Falls ja: Kachel-Dekodierung cachen
   (`RoutingEngine`/`NodesCache` zwischen Anfragen halten).
2. **POI-Icons (Tankstelle/Restaurant)** rendern trotz Laufzeit-Layer nicht;
   Debug-Ansatz in `Welle6_7_Status.md`.
3. Kleinere UI-Nits: Anstiegs-Zeile lugt im Peek hervor; Peek-Knopf im
   ausgezogenen Sheet oben abgeschnitten; Zielname aus der Suche erscheint
   nicht im Sheet („Destination on the map“ statt „Hameln“).
4. `de-ni.places.sqlite` (122 MB) und `de-ni.cameras.tsv` müssen ins Release
   `data-20260910` hochgeladen und `catalog.json` ergänzt werden — oder die
   Pipeline (`opencurv-data.yml`) läuft neu. **Entscheidung des Auftraggebers.**
5. Landes-/Bundesstraßen-Sperrungen (Mobilithek, DATEX II) brauchen eine
   Registrierung; nur Erweiterungspunkt vorhanden.
