# Welle 6–8 — Arbeitsstand (11.09.2026, Nutzungslimit erreicht)

Dieses Dokument hält fest, wo die Orchestrierung unterbrochen wurde, damit die
nächste Sitzung ohne Neuanfang weitermachen kann. Vollständiger Plan: Abschnitt
"Welle 6 bis 8" in `Projektplan.md`.

## Erledigt und in `main` gemergt (Stand `11d32d9`)

| Nr. | Agent | Ergebnis | Beleg |
| --- | --- | --- | --- |
| 6.1 | Download-Reparatur (Sonnet) | `release-assets.githubusercontent.com` + `api.github.com` erlaubt (`DownloadTarget.isAllowedHost`), Katalog aus neuestem `data-*`-Release über die GitHub-API, SHA-256/Größe nach Download geprüft (auch bei Resume). Niedersachsen (427 MB, 3 Dateien) auf dem Emulator vollständig und prüfsummenkorrekt geladen. | Commit `cde9996`, Screenshots `design/screens/welle6_download_0*.png` |
| 6.2 | Verkehrsdaten-Live (Sonnet) | `AutobahnTrafficSource` (BMDV-API, ohne Schlüssel), `TrafficUpdater` (online → alle 30 min, offline → Cache), 4 104 Meldungen / 657 Vollsperrungen live geladen, Cache `files/traffic_cache.json` (6,8 MB). GeoJSON-Schema für die Karte in `Verkehrsdaten.md`. | Commit `5396877` |
| 6.3 | Blitzer (Sonnet) | 1 275 Blitzer Niedersachsen als `assets/cameras/de-ni.cameras.tsv`, `SpeedCameraRepository`/`Grid`/`Warner` (≤ 1 km, Richtung, Hysterese, Cooldown), Ansage `AnnouncementKind.SPEED_CAMERA`, Composable `SpeedCameraAlert` (derzeit global in `OpenCurvRoot`), Opt-in `settings.speedCameraWarnings` (Standard aus, §23 StVO), Pipeline `build_cameras.py` + Workflow-Schritt + Katalog-Art `cameras`. | Commit `159e925`, `Blitzer.md` |

Abnahme nach dem Merge: `testDebugUnitTest` 221/221, Verifier 209/209,
`assembleDebug` grün.

Bekannte Restpunkte aus Welle 6:
- `kind=="cameras"` und `kind=="places"` aus dem Katalog müssen noch in
  `MapCatalog.parseRegion`/`DownloadRepository` als Download-Ziele
  aufgenommen werden (Auftrag von 6.4).
- ~4 300 NoGo-Kreise je Routenberechnung — Bounding-Box-Filter ist Auftrag
  von 7.2 (Punkt 10).

## Unterbrochen — laufende Agenten (Worktrees unter `.claude/worktrees/`)

Alle vier wurden gestartet, aber vom Nutzungslimit abgebrochen. Die
Worktrees bleiben liegen; **vor dem Neustart prüfen**, ob dort schon
Änderungen liegen (`git -C <worktree> status`, `git log`). Uncommittete
Arbeit kann als Ausgangspunkt dienen, ist aber ungeprüft.

| Nr. | Agent | Modell | Worktree / Branch | Basis | Stand beim Abbruch |
| --- | --- | --- | --- | --- | --- |
| 6.4 | Adress-Index | Opus | `agent-a1850b679241a4587` | `11d32d9` | keine Änderungen committet |
| 7.1 | Karten-Overlays | Opus | `agent-a2c3f4e5beb9da884` | `11d32d9` | keine Änderungen committet |
| 7.3 | Navigations-HUD | Opus | `agent-af39d8696dffa5357` | `11d32d9` | 2 Dateien geändert, uncommittet |
| 7.2 | Planung & Touren | Opus | `agent-ad40929501926d302` | **`dfc5d86` (veraltet!)** | keine Änderungen |

**Achtung 7.2:** Der Worktree wurde versehentlich auf dem alten Stand
`dfc5d86` erzeugt (vor Welle 6). Beim Neustart den Worktree verwerfen und
neu von `main` anlegen, sonst fehlen Verkehrs-/Blitzer-Code und der Merge
wird unnötig konfliktreich.

### Emulator-Sperre
Parallel arbeitende Agenten sperren den Emulator per
`mkdir /tmp/opencurv-emulator.lock` (Freigabe `rmdir`). Falls das
Verzeichnis nach dem Abbruch noch existiert: löschen.

## Aufträge zum Neustart (Kurzfassung der Briefings)

Gemeinsame Regeln für alle: eigener Worktree (`isolation: worktree`),
`local.properties` aus dem Hauptverzeichnis kopieren, nur auf dem eigenen
Branch committen (`Co-Authored-By: Claude <Modell> 5 <noreply@anthropic.com>`),
Tests + `assembleDebug` grün, Bericht mit Branch/Commit/Zahlen/Screenshots.
Die Orchestrierung merged nacheinander in `main` und fährt die Abnahme.

### 6.4 Adress-Index (Opus) — startet zuerst, weil er den Download-Code anfasst
- Pipeline `tools/pipeline/bin/build_places.py`: aus dem Geofabrik-Extrakt je
  Region eine `<id>.places.sqlite` mit Orten, Straßen (je Straße+Ort eine
  Zeile mit Mittelpunkt, nur motorradtaugliche `highway`), Adressen
  (`addr:street`+`addr:housenumber`, Zentroid), normalisierte Suchspalten
  **identisch zu `Place.normalise()`**, Präfix-Indexe, Ziel < 100 MB für
  Niedersachsen. Workflow-Schritt + `make_catalog.py` (`kind: places`).
  Lokal gegen Bremen testen; wenn möglich Niedersachsen bauen (Datei im
  Scratch lassen — Hochladen ins Release entscheidet der Auftraggeber).
- App: `MapCatalog`/`DownloadRepository` um `places` (→ neues `placesDir`)
  und `cameras` (→ `camerasDir`, dann `speedCameraRepository.refresh()` +
  `speedCameraWarner.updateCameras`) erweitern; `OfflineFileKind.PLACES`;
  `RegionStore` ordnet die Dateien der Region zu.
- Suche: `SqlitePlaceIndex` (read-only), Android-freier `QueryParser`
  ("Hauptstr. 12 Hannover", "Hannover, Hauptstraße 12", "12a", PLZ),
  Ranking Ort > Straße im Ort > Straße nahe > Adresse, < 200 ms, max. 30
  Treffer; in `PlaceSearchRepository.search()` einmischen; toten
  Mapsforge-Pfad (`MapPlaceReader`, Abhängigkeit) entfernen, falls möglich.
- Nicht anfassen: `ui/search/SearchScreen.kt`, `ui/plan/*`, `ui/map/Map*`,
  `ui/navigation/*`, `DraggableSheet.kt`.
- Doku `1.Doku/Ortssuche.md`, Screenshot `welle6_suche_adresse.png`.

### 7.1 Karten-Overlays (Opus)
- Positionsmarke: nur ein großes Dreieck (44–52 dp, Verlauf hellblau→blau,
  heller Rand), mit Heading gedreht; kein Kreis mehr.
- POI-Icons statt Farbpunkte: `1.Doku/design/icons/gas-station_*.svg`
  (Tankstelle) und `terrace_*.svg` (Restaurant) → Vector-Drawables →
  `style.addImage`; in beiden Style-JSONs circle-Layer durch symbol-Layer
  ersetzen (Tankstelle ab z12, Restaurant ab z13, Größe nach Zoom).
- POI antippen: `queryRenderedFeatures` → `MapViewModel.selectedPoi`;
  schwebende Karte im Ruhezweig von `OpenCurvRoot` mit "Als Ziel" /
  "Zwischenziel" / Schließen. Während Navigation aus.
- Sperrungen: Source aus `traffic.getIncidentsGeoJson()`; Linien
  `impassable` rot (6 px, weiße Kontur), sonst orange; Barriere-Icon
  (`barrier_*.svg`) auf `role=="icon"`-Punkten ab z8; Tap zeigt Titel/Straße.
- Blitzer: Source aus `speedCameraRepository.toGeoJson()`, `ic_poi_camera`,
  nur sichtbar wenn `settings.speedCameraWarnings`.
- Karte in der Navigation **immer leicht gekippt** wie im Demo-Modus —
  Ursache finden, warum sie flach bleibt (`perspectiveEnabled`-Default,
  `CameraController.tiltFor`), Standard = an.
- Überlebt Tag/Nacht-Wechsel (`reapplyOverlays`). `MapLibreStyleTest`
  anpassen. Screenshots `welle7_karte_*.png`.
- Eigene Dateien: `ui/map/MapController.kt`, `MapScreen.kt`,
  `assets/maplibre/*.json`, Drawables. Additiv: `MapViewModel` (nur POI),
  `OpenCurvRoot` (nur Map-Parameter + POI-Karte), `CameraController`,
  `SettingsRepository` (Default).

### 7.2 Planung & Touren (Opus) — Worktree NEU von `main` anlegen
- Sheet (`DraggableSheet.kt`) von überall ziehbar (NestedScroll, Slider
  bleibt horizontal bedienbar), Einrasten nach Schwelle (~25 %) und
  Fling-Geschwindigkeit, Zustände Peek / (halb) / voll.
- Peek mit Route: Fahrzeit groß, Distanz + Ankunft klein, rechts runder
  Play-Knopf ≥ 64 dp "Navigation starten"; keine Anstiege/Kurvigkeit im
  Peek. Hell/verspielt (Ruhe-Design).
- Mehrpunkt: geordnete Stoppliste (umordnen, löschen), "Stopp hinzufügen"
  über Suche (`SearchMode { DESTINATION, STOP, START }`), Rundtour-Schalter
  (Ziel = Start), optional "Rundtour vorschlagen" (Wunschlänge, drei
  Zwischenpunkte auf einem Kreis, Retry bei Routing-Fehler).
- Verlauf `data/history/RouteHistory` (JSON, max. 30): letzte Ziele in der
  Suche bei leerer Eingabe, letzte Touren im Sheet → laden und bearbeiten.
- Auto-Neuberechnung bei Profil-/Kurvenhunger-Wechsel (400 ms Debounce,
  laufenden Job abbrechen, Fortschritt zeigen).
- JVM-Test mit `~/Downloads/region-de-ni/*.rd5` (Assume, wenn fehlend):
  Routen für curvy(0/1/2), fast, enduro vergleichen (Distanz, Zeit,
  `curvinessScore`); bei < 10 % Unterschied den `curviness`-Effekt in
  `motorcycle_curvy.brf` verstärken. Messwerte in `Kurven_Score.md`.
- NoGo-Filter in `NavigationController`: Bounding-Box der Wegpunkte + 30 km.
- Eigene Dateien: `DraggableSheet.kt`, `ui/plan/*`, `ui/search/SearchScreen.kt`,
  `MapViewModel.kt` (Planung), `data/history/*`, `NavigationController.kt`,
  Profile. Additiv: `OpenCurvRoot` (Ruhezweig), `AppContainer`. Nicht:
  `ui/map/MapController|MapScreen`, `ui/navigation/*`, `data/search/*`,
  `data/download/*`. Screenshots `welle7_planung_*.png`.

### 7.3 Navigations-HUD (Opus)
- Kurvigkeits-Anzeige komplett raus; Ankunft + Restkilometer klein unten
  links (Zahlen ≥ 22 sp fett).
- Tempolimit-Schild (rund, rot/weiß/schwarz) mit darunter Viereck der
  gefahrenen Geschwindigkeit (rot bei Überschreitung) als ein Element am
  rechten Rand zwischen Manöverleiste und Knöpfen; ohne Limit nur Viereck.
- Lautstärke / Neu berechnen / Beenden → ein aufklappbares Menü (klappt
  nach oben, Menü-Knopf wird X); Zentrieren bleibt eigenständig unten
  rechts. Knöpfe ≥ 56 dp, Abstand ≥ 12 dp.
- `SpeedCameraAlert` aus `OpenCurvRoot` entfernen und im HUD zeigen
  (Manöverleiste bleibt sichtbar); im Ruhebildschirm kompaktes Banner
  `SpeedCameraBanner` oben.
- Eigene Dateien: `ui/navigation/*`, Drawables (`ic_action_menu`). Additiv:
  `OpenCurvRoot` (nur HUD-Aufruf/Overlay/Banner), `Controls.kt`, strings.
  Nicht: `ui/map/*`, `ui/plan/*`, `ui/search/*`, `DraggableSheet.kt`,
  `domain/*`, `data/*`. Screenshots `welle7_hud_*.png`.

## Danach: Welle 8 (Orchestrierung)
1. Branches in dieser Reihenfolge mergen: 6.4 → 7.1 → 7.3 → 7.2; erwartete
   Konflikte: `OpenCurvRoot.kt`, `MapViewModel.kt`, `AppContainer.kt`,
   `tools/verifier/build.gradle.kts` (alle additiv).
2. Nach 6.4: ggf. neue `IndexState`-Zustände/`PlaceKind`s in
   `SearchScreen` (7.2) nachziehen.
3. `testDebugUnitTest`, Verifier, `assembleDebug`, Emulator-Lauf mit
   Screenshots, Download-Nachweis.
4. Mit dem Auftraggeber klären: `de-ni.places.sqlite` (und ggf.
   `de-ni.cameras.tsv`) ins Release `data-20260910` hochladen und
   `catalog.json` dort aktualisieren — oder die Pipeline
   (`opencurv-data.yml`) neu anstoßen.
5. `Projektplan.md` (Fortschritt), `AI_Workspace_Overview.md` (neue Pakete
   `data/traffic`, `data/cameras`, `data/search` SQLite, `data/history`)
   nachziehen.

## Neue Aufstellung (11.09.2026, nach zweitem Limit-Abbruch)

Vier Opus-Agenten parallel erschöpfen das Sitzungslimit in Minuten. Deshalb:
**ein Agent nach dem anderen**, Sonnet als Standardmodell, große Aufträge
in kleinere Schritte geteilt, höchstens 2–3 Screenshots je Agent.

| Reihenfolge | Schritt | Modell | Inhalt |
| --- | --- | --- | --- |
| 1 | 7.3 HUD | Sonnet | **erledigt**, gemergt in `bef52d6` (Kurvigkeit raus, Schild+Tacho, Klappmenü, Blitzer im HUD + Banner; 231 Tests grün). Visuelle Abnahme steht noch aus (Welle 8). |
| 2 | 7.1 Karte | Sonnet | **erledigt**, gemergt `20530c7` + Nachbesserung `64f1cf1` (Dreieck-Puck, Sperrungen rot + Barriere-Icon, Blitzer-Icons, POI-Tippkarte, Kippung 45° in Fahrt, Kipp-Bug nach Demo behoben; 238 Tests). **Offen:** Tankstellen-/Restaurant-Icons und deren Labels sind in Hannover-Mitte (z~15) weiterhin nicht sichtbar, obwohl der `poi`-Layer (z12–14) in den Kacheln liegt und die Icon-Layer jetzt zur Laufzeit nach `addImage` angelegt werden. Nächster Schritt: `querySourceFeatures("openmaptiles", ["poi"])` im Sichtfenster loggen — kommen Features mit `class=fuel` an? Falls nein: Planetiler-Profil/Kachelinhalt prüfen (evtl. nur `rank`-gefiltert); falls ja: Layer-Reihenfolge/`icon-allow-overlap`. |
| 3 | 7.2a Sheet | Sonnet | **erledigt**, gemergt `b05085a` (247 Tests). Nit für Welle 8: Anstiegs-Zeile lugt im Peek unten hervor. Ursprünglich: Sheet von überall ziehbar, Einrasten, Peek mit Play-Knopf, Auto-Neuberechnung, NoGo-Bbox-Filter |
| 4 | 7.2b Touren | Sonnet | **läuft** (Worktree `w72b`) — Stoppliste, Rundtour, Verlauf (letzte Ziele/Touren) |
| 5 | 7.2c Profile & Routing-Tempo | Sonnet | **erledigt** `68f2d22` + Nachtrag 7.2d `80da8b0` (262 Tests). Tile-Namenskollision behoben (App nutzte nie die getaggten Kacheln), Alternativen 3→1, `pass1coefficient` 1.5, Profile nachgeschärft (curvy(2) +30–50 % länger, kurvigste Option). NoGo-Filter jetzt Korridor statt Bounding-Box (2 281 → 4 Kreise; Ursache war ein Ausreißer-Wegpunkt bei (0,0) ohne GPS-Fix). Emulator: 1 km kalt ~12 s, warm ~5 s — `doRun` dominiert, JVM 89 ms; auf echtem Gerät zu messen. Ursprünglich: JVM-Messung curvy/fast/enduro auf Niedersachsen, Profile nachschärfen. **Neu:** Routing dauert auf dem Emulator 47 s für 2 km ohne NoGos, 82 s mit; 8 km laufen in den 60-s-Timeout (`pass0 timeout`). Ursachen prüfen: `routeCurviest` rechnet 4 Routen, `pass1coefficient = 2.0`, 48 MB Node-Cache, altes 182-MB-`E5_N50.rd5` neben dem neuen `de-ni_E5_N50.rd5` im Segmentordner. |
| 6 | 6.4a Pipeline | Sonnet | `build_places.py`, Workflow, Katalog-Art `places` |
| 7 | 6.4b Suche | Sonnet | Download `places`/`cameras`, `SqlitePlaceIndex`, `QueryParser`, Mapsforge-Reste raus |
| 8 | Welle 8 | Orchestrierung | Abnahme |

### Befunde der Orchestrierung (11.09.2026, spät)
- `NoGoFilter` (Bounding-Box der Wegpunkte + 30 km) ist in `main` (`bef52d6`), mit Test.
- Worktrees über `isolation: worktree` starten auf einem veralteten Commit — ab jetzt `git worktree add .claude/worktrees/<name> -b <name> main` von Hand und dem Agenten das Verzeichnis nennen.
- Agenten enden manchmal mit "warte auf Hintergrundprozess" ohne Bericht — im Briefing steht deshalb: mit Bericht abschließen, nicht warten.
- Kartenkacheln waren vom Emulator verschwunden (Neuinstallation); `de-ni.pmtiles` aus `~/Downloads/region-de-ni/` per adb wieder eingespielt.
- Nach der Demo-Fahrt bleibt die Karte im Ruhezustand gekippt → Auftrag an 7.1.
