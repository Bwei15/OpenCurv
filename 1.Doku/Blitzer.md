# Blitzer (stationäre Geschwindigkeitsüberwachung)

Stand: September 2026. Betroffene Module: `tools/pipeline/bin/build_cameras.py`,
`app/src/main/assets/cameras/`, `app/src/main/java/com/motoroute/data/cameras/`,
`app/src/main/java/com/motoroute/domain/cameras/`,
`app/src/main/java/com/motoroute/ui/navigation/SpeedCameraAlert.kt`.

---

## 1. Rechtslage — deshalb der Opt-in

In Deutschland ist die Nutzung eines Geräts, das während der Fahrt vor
Blitzern warnt, nach **§23 Abs. 1c StVO** untersagt. Die Funktion ist deshalb
**standardmäßig aus** (`Settings.speedCameraWarnings = false`,
`data/settings/SettingsRepository.kt`) und lässt sich nur über einen expliziten
Schalter in Einstellungen → "Blitzerwarnung" einschalten
(`ui/settings/SettingsScreen.kt`), mit Warnhinweis-Text direkt am Schalter
(`strings.xml` / `strings-de.xml`: `settings_speed_camera_warnings_hint`). Der
Warner (`domain/cameras/SpeedCameraWarner.kt`) bekommt den Schalterzustand bei
jedem Fix erneut übergeben und löscht eine aktive Warnung sofort, wenn er auf
`false` steht (`onFix(..., enabled = false)`) — es reicht also, den Schalter
umzulegen, kein Neustart nötig.

---

## 2. Datenquelle und Format

**Quelle:** OpenStreetMap, zwei Tag-Schemata, beide auf dieselbe Zeile
abgebildet:

- `highway=speed_camera`-Nodes (die überwiegende Mehrheit).
- `enforcement=maxspeed`-Relationen: der `role=device`-Member-Node ist die
  physische Kamera; die Relation selbst trägt oft ein `maxspeed`, das der
  reine Node-Tag nicht hat.

**Format** (`<region-id>.cameras.tsv`, UTF-8, eine Kommentarzeile `#` am
Anfang mit Quelle/Datum/Lizenz, danach eine Zeile pro Kamera):

```
lat<TAB>lon<TAB>direction<TAB>maxspeed<TAB>name
```

- `lat`/`lon`: WGS84, 7 Nachkommastellen.
- `direction`: OSM-`direction`-Tag in Grad (0 = Nord), leer wenn nicht
  gesetzt. Kompassrichtungen (`N`, `NNE`, …) werden umgerechnet.
- `maxspeed`: numerisch km/h. `mph`-Werte werden umgerechnet; alles andere
  Unparsbare (`none`, `walk`, …) bleibt leer statt einen falschen Wert zu
  raten.
- `name`: Tab/Newline-bereinigt, leer wenn kein `name`-Tag vorhanden.

## 3. Pipeline-Schritt

`tools/pipeline/bin/build_cameras.py` liest den `.osm.pbf`-Extrakt einer
Region mit **pyosmium** (dasselbe `osmium`-Python-Paket, das
`apply_scores.py` bereits nutzt und das der Workflow-Schritt "Werkzeuge" schon
installiert — kein neues Werkzeug nötig) und schreibt die TSV-Datei. Die Datei
wird **zweimal** gelesen: einmal für `enforcement=maxspeed`-Relationen (selten,
billig), einmal für Nodes — beide Quellen brauchen die Koordinate eines Nodes,
und die liefert pyosmium für jeden gesehenen Node ohne separaten
Location-Index (anders als bei einer Way-Geometrie).

```
build_cameras.py <in.osm.pbf> <out.cameras.tsv>
```

**Workflow** (`.github/workflows/opencurv-data.yml`): neuer Schritt
"Blitzer extrahieren" nach "Rohdaten holen" und vor "Kurven-Score anbringen"
(nicht danach: der Score-Schritt löscht `raw/src.osm.pbf` anschließend, und
die Kamera-Extraktion braucht keine bewertete Datei). Ergebnis landet als
`out/$REGION_ID/$REGION_ID.cameras.tsv` — derselbe Ordner, den die
Routing-Kacheln benutzen, wird also automatisch mit ins Region-Artefakt und
später ins Release übernommen.

**Katalog:** `tools/pipeline/bin/make_catalog.py` erkennt `*.cameras.tsv` an
seinem Suffix (`kind_of()`, vor dem generischen Endungs-Mapping geprüft) und
trägt die Datei mit `"kind": "cameras"` in `catalog.json` ein — genau wie
`maptiles` und `routing` für ihre jeweiligen Dateien.

**Lokal getestet:** gegen den Bremen-Extrakt von Geofabrik
(`bremen-latest.osm.pbf`, ~20 MB) — **56 Kameras**, davon 21 mit `direction`
und 21 mit `maxspeed`. Lief mit `pyosmium` in einer lokalen virtualenv
(`osmium-tool` war für diesen Lauf nicht nötig, `pip install osmium` reicht,
wie im CI-Workflow).

## 4. Starter-Datei Niedersachsen

`app/src/main/assets/cameras/de-ni.cameras.tsv` — **1275 Kameras**, per
Overpass-API gegen die Bounding-Box von Niedersachsen (6.2953,51.2935,11.6073,54.2396)
geladen (nur `highway=speed_camera`, wie im Auftrag spezifiziert — keine
`enforcement=maxspeed`-Relationen, die kommen erst mit dem nächsten
Pipeline-Lauf über `build_cameras.py`). 584 Kameras tragen ein
`direction`-Tag, 931 ein `maxspeed`. Kommentarzeile: `# source: OSM/Overpass,
2026-09-11, license ODbL`. Diese Datei sorgt dafür, dass die Warnung
funktioniert, bevor die Cloud-Pipeline das erste Mal mit dem neuen Schritt
gelaufen ist; sobald ein `de-ni`-Release existiert, überschreibt/ergänzt der
Download in `filesDir/cameras/` sie nicht, sondern beide werden beim Laden
zusammengeführt (siehe unten).

## 5. App: Laden

- `data/cameras/SpeedCamera.kt` — Modell (`GeoPoint`, `directionDeg: Int?`,
  `maxSpeedKmh: Int?`, `name: String?`). `id` ist die auf 5 Nachkommastellen
  gerundete Koordinate (`SpeedCamera.idFor`) — zugleich der Dubletten-Schlüssel
  beim Laden und der stabile Cooldown-Schlüssel des Warners (eine OSM-Node-ID
  steht in der TSV-Datei absichtlich nicht, siehe Format oben).
- `domain/cameras/SpeedCameraGrid.kt` — Android-freier Bucket-Grid-Index
  (0,01°-Zellen, siehe `1.Doku/AI_Workspace_Overview.md`-Stil für "keine
  Allokation pro Fix"): `nearby(point, radiusMeters)` durchsucht nur die
  Zellen, die der Radius tatsächlich erreichen kann, nicht die ganze Liste.
  Läuft in `tools/verifier` und wird über `SpeedCameraWarnerTest` mitgeprüft
  (u. a. ein Fall, in dem Abfragepunkt und Kamera in verschiedenen Zellen
  liegen).
- `data/cameras/SpeedCameraRepository.kt` — lädt alle `*.cameras.tsv` aus
  `assets/cameras/` (Bundled-Starterdaten) und aus
  `OfflineDataRepository.camerasDir` (`filesDir/cameras/`, additiv in
  `data/map/OfflineDataRepository.kt` und `data/map/OfflineFile.kt` als
  `OfflineFileKind.CAMERAS` ergänzt — Extension `tsv`, Verzeichnis `cameras`).
  Dubletten (gleiche `SpeedCamera.id`, also Koordinate auf 5 Nachkommastellen)
  werden zusammengeführt (`mergeDuplicate`: fehlende Felder aus dem zweiten
  Fund auffüllen, vorhandene bleiben). Wegen der `Context`-Abhängigkeit aus
  `tools/verifier` ausgeschlossen (`tools/verifier/build.gradle.kts`,
  gleiche Begründung wie bei `ProfileManager`/`OfflineDataRepository`); die
  eigentliche Parse-Logik (`parseTsv`, `mergeDuplicate`) ist statisch und
  Android-frei und hat eine eigene Testklasse
  (`SpeedCameraRepositoryTest`, läuft nur im App-Modul).
  API: `nearby(point, radiusMeters)`, `all()`, `toGeoJson()` (Point-Features
  mit `properties.maxspeed`/`properties.direction`/`properties.id` — für den
  Icon-Layer aus Welle 7, siehe unten), `refresh()` (erneutes Einlesen, für
  nach einem Regions-Download).
- `di/AppContainer.kt` — verdrahtet `speedCameraRepository` und
  `speedCameraWarner` additiv; das erste Laden läuft in einer eigenen
  Coroutine auf `Dispatchers.IO` (wie `ProfileManager.ensureInstalled()`),
  damit ein paar tausend TSV-Zeilen den kalten Start nicht blockieren.

### Was der Download-Agent (6.1) noch ergänzen muss

Diese Aufgabe baut den Download-Code selbst **nicht** um. Sobald
`catalog.json` künftig `"kind": "cameras"`-Einträge liefert, muss der
Download-Pfad `kind == "cameras"` erkennen und die Datei nach
`OfflineDataRepository.camerasDir` legen (genau der Ort, den
`SpeedCameraRepository` schon liest) — analog dazu, wie `kind == "maptiles"`
heute nach `mapTilesDir` geht. Nach einem erfolgreichen Kamera-Download sollte
`AppContainer.speedCameraWarner.updateCameras(speedCameraRepository.refresh())`
aufgerufen werden, damit eine neu heruntergeladene Region ohne App-Neustart
wirkt (dieselbe Stelle, an der heute schon `viewModel.onDownloadedFilesChanged()`
nach einem abgeschlossenen Download läuft, siehe `ui/OpenCurvRoot.kt`).

## 6. Warnregel

Umgesetzt in `domain/cameras/SpeedCameraWarner.kt`, Android-frei, läuft in
`tools/verifier`. Eingabe je Fix: Position, Heading (Grad), Geschwindigkeit
(m/s) — dieselben Felder, die `data/location/KalmanFilter.kt` als
`FilteredFix` liefert.

**Fixe fließen auch ohne aktive Navigation:** `ui/map/MapViewModel.init`
ruft `container.navigation.startLocationUpdates()` unabhängig vom
Navigationszustand auf (das Kartenscreen startet den Standort-Stream beim
Öffnen), und `NavigationController.onFix()` verarbeitet jeden Fix auch ohne
gesetzte Route. Die Blitzerwarnung hängt deshalb direkt an
`NavigationController.onFix()` (additiv) statt nur an eine aktive Fahrt — ein
Blitzer 300 m von zuhause soll auch beim bloßen Kartenschauen warnen.

**Auslöser einer neuen Warnung** (alle Bedingungen müssen gelten):

| Bedingung | Schwelle |
| --- | --- |
| Abstand Fahrer → Blitzer | ≤ 1000 m (`WARN_RADIUS_M`) |
| Peilung Fahrer→Blitzer vs. Heading | ≤ 35° (`APPROACH_BEARING_DIFF_DEG`) |
| Geschwindigkeit | ≥ 3 m/s (`MIN_SPEED_MPS`) — darunter ist das GPS-Heading nicht belastbar (dieselbe Einschränkung wie in `1.Doku/Sprachausgabe.md` §4) |
| Blitzer hat `direction`-Tag | zusätzlich: Heading vs. Blitzerrichtung ≤ 60° (`DIRECTION_TOLERANCE_DEG`), sonst gilt der Blitzer der Gegenspur und wird ignoriert |

**Hysterese:** einmal aktiv, bleibt die Warnung stehen — sie wird *nicht*
erneut gegen die Auslöseschwellen geprüft — bis entweder der Abstand
1200 m übersteigt (`RELEASE_RADIUS_M`) oder die Peilung zum Blitzer mehr als
100° vom Heading abweicht (`PASSED_BEARING_DIFF_DEG`, "passiert"). Unter
3 m/s wird die "passiert"-Bedingung nicht geprüft (Heading unzuverlässig), nur
die Abstandsbedingung — ein Halt an der Ampel neben einem Blitzer lässt die
Warnung also nicht grundlos verschwinden.

**Cooldown:** pro Blitzer-`id` höchstens eine *Ansage* alle 3 Minuten
(`COOLDOWN_MILLIS`). Das gilt nur für die Sprachausgabe
(`SpeedCameraWarner.announcements`, ein `SharedFlow`, das genau einmal pro
Vorbeifahrt feuert) — der sichtbare `warning`-Zustand (`StateFlow`) ist davon
unabhängig: fährt der Fahrer innerhalb von 3 Minuten zweimal am selben
Blitzer vorbei (z. B. Wendekreis), zeigt der Bildschirm beide Male Rot, aber
nur die erste Vorbeifahrt wird angesagt.

**Ausgabe:** `StateFlow<SpeedCameraWarning?>` mit `camera`, `distanceMeters`,
`maxSpeedKmh`.

## 7. Ansage

`domain/NavigationManager.kt` bekommt additiv `AnnouncementKind.SPEED_CAMERA`
und `VoiceAnnouncement.speedCameraLimitKmh: Int?`. `domain/guidance/Phrasebook.kt`
bekommt additiv eine Phrase je Sprache:

- Deutsch: "Achtung, Blitzer" bzw. mit bekanntem Limit "Achtung, Blitzer, 50"
  (die Zahl wird wie überall sonst im Phrasebook roh interpoliert — die TTS-Engine
  spricht "50" als "fünfzig", genau wie bei den Distanzangaben).
- Englisch: "Speed camera ahead" bzw. "Speed camera ahead, 50".

**Verdrahtung** (`domain/NavigationController.kt`, additiv): der Warner
bekommt in `onFix()` jeden Fix **vor** `manager.onLocation(fix)` — wird auf
demselben Fix sowohl eine Blitzerwarnung als auch eine Abbiege-Ansage fällig,
landet die Blitzerwarnung dadurch zuerst in der TTS-Warteschlange (siehe
`1.Doku/Sprachausgabe.md` §3/§5: `VoiceGuidance.speak()` ist die einzige
Stelle, die tatsächlich `tts.speak()` aufruft, mit `QUEUE_ADD` für alles außer
der letzten "Jetzt"-Ansage). `SpeedCameraWarner.announcements` löst über einen
eigenen `onEach`-Collector im `NavigationController`-`init`-Block **genau
eine** `VoiceAnnouncement(kind = SPEED_CAMERA, isFinal = false)` je
Vorbeifahrt aus (`isFinal = false` → `QUEUE_ADD`, schneidet also nie eine
laufende oder bereits wartende Abbiege-Ansage ab — die Vorgabe "darf eine
Abbiege-Ansage nicht verschlucken" ist damit strukturell erfüllt, nicht nur
per Vereinbarung). `voice/VoiceGuidance.kt` selbst brauchte **keine** Änderung:
`speak(VoiceAnnouncement)` ist bereits vollständig generisch über `kind`.

## 8. Warn-Composable

`ui/navigation/SpeedCameraAlert.kt`: `@Composable fun SpeedCameraAlert(warning: SpeedCameraWarning?, modifier)`.
Vollflächiges, halbtransparentes Rot (`LocalRideColors.current.danger`, Alpha
0,88 — Farbtoken aus `ui/theme/Color.kt`/`Theme.kt`, siehe
`1.Doku/Design_System.md`), zentriert: Kamera-Icon 112 dp (≥ 96 dp gefordert),
"Blitzer"/"Speed camera" in 40 sp Black (≥ 34 sp gefordert), darunter die
Distanz ("in 600 m" — wiederverwendet `ui/navigation/NavigationComponents.kt`s
eigene `formatDistance()`, dieselbe Rundung wie auf der Abbiege-Leiste), und
bei bekanntem Tempolimit ein rundes Verkehrszeichen-274-Schild (weiße
Scheibe, roter Ring, schwarze Zahl). Ein-/Ausblenden über `AnimatedVisibility`
mit Fade.

**Icon:** `1.Doku/design/icons/camera_8738440.svg` (512×512, ausschließlich
`<path>`-Elemente mit `fill`, keine Gradients/Filter/Masken/Strokes — geprüft
vor der Umwandlung) → `app/src/main/res/drawable/ic_poi_camera.xml`: alle 16
Pfade übernommen, jeder mit `android:fillColor="#FFFFFFFF"` (statt der
ursprünglichen Graustufen — die Vorgabe verlangt explizit Weiß + Tint, damit
das Icon auf jedem Grund funktioniert) und `android:tint="#FFFFFFFF"` am
Vektor selbst, wie die übrigen Drawables in `res/drawable/` es schon
handhaben (z. B. `ic_maneuver_left.xml`).

**Einbau (Welle 7 baut das eigentliche HUD):** Damit das Composable nicht tot
herumliegt, hängt `ui/OpenCurvRoot.kt` es additiv als oberstes Overlay ein —
oberhalb des `NavHost`/`Box`-Inhalts jedes Screens, gespeist direkt aus
`(context.applicationContext as OpenCurvApp).container.speedCameraWarner.warning`
statt über `MapViewModel` (das gehört zu `ui/map/*`, für diese Aufgabe
gesperrt, und die Warnung muss ohnehin bildschirmunabhängig funktionieren).
Das ist absichtlich global sichtbar auf jedem Screen, nicht nur der Karte.
**Welle 7** soll die reichhaltigere HUD-Version (kleiner, in die
Navigationsleiste integriert statt vollflächig) direkt in
`ui/navigation/ActiveNavigationScreen.kt` einbauen und dafür entweder dieses
Composable wiederverwenden oder gegen `speedCameraWarner.warning` einen
eigenen kompakten Indikator bauen; der Icon-Layer auf der Karte selbst kommt
aus `SpeedCameraRepository.toGeoJson()` (`properties.maxspeed`,
`properties.direction`).

## 9. Einstellungen

`data/settings/SettingsRepository.kt`: additiv `Settings.speedCameraWarnings: Boolean = false`,
persistiert unter dem SharedPreferences-Key `speed_camera_warnings`.
`ui/settings/SettingsScreen.kt`: additiv eine neue `PanelCard` "Blitzerwarnung"
ganz am Ende der Liste, mit `SwitchRow` und dem Warnhinweis-Text als
Untertitel — derselbe `SwitchRow`-Baustein, den "Ansagen"/"Fahrverhalten"
schon benutzen. `ui/OpenCurvRoot.kt` verdrahtet den Schalter direkt gegen
`container.settings.update { ... }` (aus demselben Grund wie beim Lesen der
Warnung: `ui/map/MapViewModel.kt` ist gesperrt).

## 10. Tests

- `SpeedCameraWarnerTest.kt` (App-Modul **und** `tools/verifier`, 15 Tests):
  Annäherung von vorn (warnt), Wegfahren (warnt nicht), seitlich vorbei (warnt
  nicht), Gegenrichtung mit `direction`-Tag (warnt nicht), passende Richtung
  mit `direction`-Tag (warnt), unter 3 m/s startet keine neue Warnung,
  Deaktivieren löscht sofort, Hysterese hält eine Warnung über eine Peilung,
  die für einen Neustart zu groß wäre, Hysterese löst bei "passiert" und bei
  zu großem Abstand, genau eine Ansage über mehrere Fixes einer Annäherung,
  Cooldown unterdrückt die zweite Ansage aber nicht die Anzeige, Cooldown
  rearmt nach 3 Minuten, Gitterabfrage über eine Zellgrenze hinweg (direkt am
  Grid und Ende-zu-Ende über den Warner).
- `SpeedCameraRepositoryTest.kt` (nur App-Modul, wegen `Context`-Ausschluss im
  Verifier): TSV-Parsing (wohlgeformt, leere Felder, Kommentare/Leerzeilen,
  eine kaputte Zeile wirft die Datei nicht komplett weg), Dubletten-Merge,
  Stabilität der gerundeten ID.

**Ergebnisse dieser Änderung:** `./gradlew testDebugUnitTest` — **190 Tests,
0 Fehlschläge**. `./gradlew -p tools/verifier test` — **182 Tests, 0
Fehlschläge**. `./gradlew assembleDebug` — erfolgreich.

## 11. Offene Punkte

- **Download-Anbindung** (Welle 6.1/6.4, siehe Abschnitt 5): `kind ==
  "cameras"` muss im Download-Pfad erkannt und nach `camerasDir` gelegt
  werden; danach `speedCameraRepository.refresh()` +
  `speedCameraWarner.updateCameras(...)` aufrufen.
- **HUD-Einbau** (Welle 7.3): die vollflächige rote Warnung ist absichtlich
  ein Übergangszustand außerhalb des eigentlichen HUD; Welle 7 soll sie durch
  eine ins Navigations-HUD integrierte Variante ersetzen oder ergänzen und
  den Kamera-Icon-Layer aus `toGeoJson()` auf der Karte zeichnen.
- **Manuelle Emulator-Prüfung nicht durchgeführt:** Der laufende Emulator
  wird derzeit von einem anderen, parallel laufenden Agenten für den
  Download-Umbau benutzt (`files/segments/de-ni_E10_N50.rd5.part` war beim
  Prüfen nur teilweise heruntergeladen, `files/maptiles/` und `files/maps/`
  waren leer) — es gab keine vollständige Offline-Karte, gegen die sich
  ohne die App der anderen Sitzung zu stören eine Demo-Route hätte berechnen
  lassen. Die Warn- und Ansagelogik ist stattdessen vollständig durch
  `SpeedCameraWarnerTest` abgedeckt; ein echter Emulator-Nachweis (Bildschirm
  wird rot, Ansage kommt über das Headset) steht noch aus und sollte in der
  Abnahme (Welle 8) nachgeholt werden, sobald echte `de-ni`-Kartendaten und
  eine Route entlang eines bekannten Blitzers verfügbar sind.
- **`enforcement=maxspeed`-Relationen fehlen im Niedersachsen-Starter:** die
  per Overpass geladene Starter-Datei enthält, wie im Auftrag spezifiziert,
  nur `highway=speed_camera`-Nodes. Relationskameras kommen erst mit dem
  ersten echten Pipeline-Lauf über `build_cameras.py` (der beide Quellen
  abdeckt) und ersetzen/ergänzen die Starter-Datei dann automatisch über den
  Download-Pfad (sobald der wie oben beschrieben angebunden ist).
