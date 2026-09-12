# OpenCurv: AI Workspace Overview

## Kurzfassung

OpenCurv ist eine Offline-Motorradnavigations-App für Android (SDK 26–35, Version 0.1.1), die kurvenreiche Strecken findet und optimiert navigiert. Tech-Stack: Kotlin + Jetpack Compose für UI, Mapsforge für Kartendarstellung, vendored BRouter als Offline-Routing-Engine, Coroutines für Async. Reifegrad: Beta-reif mit stabiler Kernlogik (Route, Navigation, Kalman-Filter, Map-Matching) aber noch fehlendem offiziellen Release.

---

## Modul-/Verzeichnisbaum

- **`app/`** — Die Android-Anwendung (minSdk 26, targetSdk 35). Hauptquelle aller Features: Routing, Navigation, UI, Download-Verwaltung, GPS-Filterung.
- **`brouter/`** — Vendored BRouter-Modul (Open-Source-Offline-Router). Nur zwei projekteigene Dateien: `OpenCurvTrackAccess.java` (Dateizugriff für Routing-Tiles) und `MotorbikeProfileProvider.java` (Profil-Logik).
- **`tools/verifier/`** — Standalone JVM-Build zum Testen der Framework-freien Kernlogik (Routing, Kalman-Filter, Map-Matching, Phrasebook) ohne Android SDK.
- **`1.Doku/`** — Dokumentation und Überblicke für spätere KI-Sitzungen.
- **`.github/workflows/`** — CI-Pipeline: Unit-Tests auf JVM (`tools/verifier`) + Android APK-Build & Tests.

---

## Architektur der App

### Struktur `app/src/main/java/com/motoroute/`

**DI & Entry Points:**
- `di/AppContainer.kt` — Manuelle Dependency Injection (kein Framework, lesbar auf einer Seite). Instantiiert alle globalen Komponenten (Settings, LocationProvider, BRouterEngine, voice, navigation, downloads).
- `OpenCurvApp.kt` — Application-Klasse. Initialisiert Mapsforge Graphics Factory, AppContainer, NotificationChannels, ProfileManager.
- `MainActivity.kt` — Einzige Activity. MapViewModel Factory, Permissions (FINE_LOCATION, NOTIFICATIONS), Compose-Root, Volume-Keys zum Zoomen, Screen-Lock.

**Data Layer** (`data/`):

- `brouter/BRouterEngine.kt` — Wrapper um BRouter RoutingEngine. `route(RouteRequest)` suspendierend: Offline-Routing mit Profilen (.brf), Lookup-Tables, Segment-Daten (.rd5). Rückgabe: `Route` mit Punkten, Instruktionen, Distanzen, Steigung, Geschwindigkeitsbegrenzungen.
- `brouter/ProfileManager.kt` — Lädt/cached Routing-Profile (.brf) und Lookup-Tabellen. Stellt sicher, dass Profiles zu App-Version passen.
- `brouter/RouteRequest` (data class) — Routing-Eingabe: Waypoints, Profil-Datei, Segment-Verzeichnis, Parameter, Alternative, Timeout, Speicherlimit.

- `download/DownloadRepository.kt` — Verwaltet Karten-Downloads (MapRegion). Nutzt FileDownloader, RegionStore. StateFlow für Fortschritt.
- `download/MapCatalog.kt` — Liest `catalog/regions.json` aus Assets. Liefert Liste der verfügbaren Regionen (BoundingBox, Größe, Land).
- `download/FileDownloader.kt` — HTTP-Download (keine Abhängigkeitsdetails lesbar aus Schnipsel).
- `download/RegionStore.kt` — Index welche Dateien welcher Region gehören.
- `download/SegmentTiles.kt` — Verwaltung der .rd5 Segment-Tiles für BRouter.
- `download/DownloadTarget.kt`, `DownloadSummary.kt` — Modelle für Download-Zustand.

- `location/LocationProvider.kt` — Nutzt Android LocationManager (nicht Google Play Services, damit F-Droid-kompatibel). Jeder Fix durch `KalmanFilter`, rückgabe Flow<FilteredFix> (Position, Geschwindigkeit, Heading, Genauigkeit).
- `location/KalmanFilter.kt` — 6D Kalman-Filter: Glättet GPS-Jitter, extrahiert Geschwindigkeit und Heading. Läuft auf jedem Fix.

- `map/OfflineDataRepository.kt` — Verwaltet Dateisystem-Verzeichnisse: mapDir (.map), segmentDir (.rd5), indexDir (Caching). Import via Storage Access Framework.
- `map/OfflineFile.kt` — Modell für eine Offline-Datei (File, Größe, Typ).

- `model/Route.kt` — Immutable Route: Points, Instructions, cumulative Distances (O(1)-Lookup), estimated Time, Ascend, speedLimits. Lazy: bounds, curvinessScore.
- `model/GeoPoint.kt` — WGS84 Position (lat/lon, optional Höhe). Konvertierungen zu/von BRouter fixed-point.
- `model/NavigationInstruction.kt` — Maneuver + Text + Distanz.
- `model/Maneuver.kt` — Enum: LEFT, RIGHT, STRAIGHT, FORK_LEFT, FORK_RIGHT, etc.
- `model/Curviness.kt` — Score-Berechnung: absolute Heading-Änderung pro km.

- `search/PlaceSearchRepository.kt` — Suche in Offline-Karten (Mapsforge-Reader). Gibt Place-Treffer mit Koordinaten.
- `search/Place.kt` — Modell: Name, Lat/Lon, optional Typ.
- `search/MapPlaceReader.kt` — Liest OSM-POIs aus .map-Dateien.

- `settings/SettingsRepository.kt` — SharedPreferences: mapTheme, volumeKeyZoom, etc. StateFlow.

**Domain Layer** (`domain/`):

- `NavigationController.kt` — **Kernorchestrator**, lebt im App-scoped Coroutine Scope. Startet LocationProvider.fixes(), Feed über MapMatcher, wendet Rerouting an, steuert Voice. State Machine über `NavigationManager`. Kein ViewModel (übernimmt Screen-Rotation).
- `NavigationManager.kt` — Turn-by-Turn State Machine: idle → active → arrived. Berechnet Abstände, prüft off-route, generiert `VoiceAnnouncement` (MutableSharedFlow).
- `CameraController.kt` — Verwaltet Zoom/Pan/Follow für MapView.
- `MapMatcher.kt` — Projiziert GPS-Positionen auf Route. Sliding Window, Heading-Toleranz, penalisiert Candidate mit >45° Abweichung.
- `ReroutingEngine.kt` — Berechnet neue Route wenn Rider off-track geht.
- `RouteSimulator.kt` — Demo-Mode: simüliert ein Fahren entlang der Route (für Testing).
- `geo/Geo.kt` — Geodäsie: distanceMeters (equirectangular Approximation, O(1)), haversineMeters, metersPerDegLon. Keine Allokationen.
- `guidance/Phrasebook.kt` — Maneuver → Sprachtext, mehrsprachig (Locale-basiert, fallback auf English).

**UI Layer** (`ui/`):

- `OpenCurvRoot.kt` — Routing-Komposable: NavHost mit 5 Screens (Map, Navigation HUD, Route-Plan, Search, Settings, Data Download).
- `theme/Theme.kt`, `Color.kt` — Compose Material3 Theme mit Day/Night-Varianten, Ride-Colors (route, destination, warning).
- `components/Controls.kt` — Button, Slider, FABs.
- `components/DraggableSheet.kt` — Draggbares Bottom Sheet (Route-Plan).
- `map/MapScreen.kt` — Mapsforge MapView in Compose via AndroidView. Perspektive-Tilt (3D-Effekt), Theme-Anwendung, Route/Marker zeichnen.
- `map/MapController.kt` — Mapsforge-API: MapView attach/detach, Overlays (route line, destination marker, start marker), Theme anwenden.
- `map/MapViewModel.kt` — Hauptscreen-ViewModel. Hält PlanSelection, beobachtet navigation.state, ruft BRouterEngine auf, triggert downloads.
- `navigation/ActiveNavigationScreen.kt` — HUD: Speed, next Maneuver, Distance, ETA, Curviness-Abzeichen.
- `navigation/NavigationComponents.kt` — Kleinere UI-Elemente für HUD.
- `onboarding/OnboardingScreen.kt` — Willkommenbildschirm.
- `plan/RoutePlanScreen.kt` — Route-Planung: Start/Destination/Via-Punkte setzen, Routing triggern, Alternativen.
- `search/SearchScreen.kt` — Offline-Ortssuche.
- `settings/SettingsScreen.kt` — mapTheme, volumeKeyZoom, Voice on/off.
- `data/MapDownloadScreen.kt` — Download-Manager UI.
- `data/OfflineDataScreen.kt` — Importieren/Löschen von Map-Dateien.

**Services** (`service/`):

- `NavigationService.kt` — Foreground Service für Navigation. Notification mit laufenden Details.
- `DownloadService.kt` — Foreground Service für Datei-Downloads.

**Voice** (`voice/`):

- `VoiceGuidance.kt` — TextToSpeech-Wrapper. USAGE_ASSISTANCE_NAVIGATION_GUIDANCE (Bluetooth-Intercom), Locale-Erkennung, Phrasebook-Auswahl.

---

## Datenfluss

### Route planen
1. **Nutzer** tippt Start + Destination in `MapViewModel` (oder drückt auf Karte).
2. **MapViewModel** ruft `NavigationController.planRoute(from, to, via)` auf.
3. **NavigationController** konstruiert `RouteRequest` (waypoints, profile vom `ProfileManager`, segments vom `OfflineDataRepository`).
4. **BRouterEngine.route()** wird suspendierend aufgerufen → BRouter RoutingEngine läuft auf Dispatchers.Default.
5. **BRouter** sucht über Segment-Tiles (.rd5) offline mit „Curviness"-Profil.
6. **Route** (GeoPoints + Instructions) kommt zurück.
7. **MapViewModel** speichert Route, zeigt sie in `MapScreen` (blaue Linie über Mapsforge).

### Navigation starten
1. **Nutzer** drückt Start in `RoutePlanScreen`.
2. **NavigationController.startNavigation(route)** wird aufgerufen.
3. **LocationProvider.fixes()** wird subscribed. Jeder GPS-Fix kommt in `onFix()`.
4. **KalmanFilter** glättet Position/Heading/Speed.
5. **MapMatcher** projiziert GPS auf Route → `MatchResult`.
6. **NavigationManager** aktualisiert `NavigationState` (next Maneuver, Distance, ETA, isOffRoute).
7. **VoiceGuidance** spricht Ansagen (über Phrasebook).
8. **ActiveNavigationScreen** zeigt Speed, Maneuver, Curviness-Badge.

### Karte herunterladen
1. **Nutzer** wählt Region in `MapDownloadScreen`.
2. **MapCatalog** liefert MapRegion (URL, Größe, BoundingBox) aus `catalog/regions.json`.
3. **DownloadRepository** triggert `FileDownloader`.
4. **FileDownloader** lädt .map und .rd5 via HTTP in offlineData.mapDir / segmentDir.
5. **RegionStore** indexiert sie.
6. **MapScreen** lädt neu über Mapsforge MapView.

### Kartendarstellung (Mapsforge)
1. **MapController.applyTheme()** wählt Theme-Datei (`themes/*.xml`) passend zu Tag/Nacht.
2. **MapView** rendert VectorMap aus .map-Dateien mit XmlRenderTheme.
3. **MapController.showRoute()** zeichnet PolylineOverlay (Route blau).
4. **MapController.showDestination/Start()** zeichnet CircleOverlay (Marker rot/grün).

---

## Externe Abhängigkeiten

Aus `gradle/libs.versions.toml` und `app/build.gradle.kts`:

- **Kotlin** 2.0.21 — Sprache.
- **AndroidX Core KTX** 1.13.1 — Context-Shortcuts.
- **Lifecycle** 2.8.6 — ViewModel, Service Lifecycle.
- **Compose BOM** 2024.10.01 → **Compose UI, Material3** — Moderne UI.
- **Activity Compose** 1.9.3 — ComponentActivity + setContent.
- **Coroutines** 1.8.1 (core + android + test) — Async, Flow, scope management.
- **Mapsforge** 0.25.0 (map-android, map-reader, themes, core) — Vektor-Kartendarstellung offline.
- **DocumentFile** 1.0.1 — Storage Access Framework (File-Import).
- **Desugar JDK Libs** 2.1.2 — Java 8+ APIs für minSdk 26.
- **JUnit** 4.13.2 — Unit-Tests.

Keine Google Play Services (F-Droid-Kompatibilität); LocationManager statt Fused Provider.

---

## Assets

`app/src/main/assets/`:

- **`catalog/regions.json`** — JSON-Array: Region-Metadaten (Name, Land, BoundingBox, URL-Pfad, Größe). Gelesen von `MapCatalog.load()`.
- **`profiles/`** — BRouter-Routing-Profile (.brf):
  - `motoroute.brf` — Haupt-Profil: Kurven-Routing, Steigungen ignorieren, Offroad-Wege bevorzugen.
  - `lookups.dat` — Lookup-Tabelle (Tag-Klassifikation).
  - `*-profile-info.xml` — Profil-Beschreibungen (UI-Anzeige).
- **`themes/`** — Mapsforge XML-Render-Themes:
  - `day.xml`, `night.xml` — Kartenstyling Tag/Nacht.

Gelesen von:
- `MapCatalog` → `regions.json`.
- `ProfileManager` → `profiles/*.brf + lookups.dat`.
- `MapController.applyTheme()` → `themes/*.xml`.

---

## Tests

### App Unit Tests (`app/src/test/java/com/motoroute/`)

19 Test-Dateien, decken ab:
- **CameraControllerTest** — Zoom/Pan Logik.
- **CurvinessTest** — Kurvenheading-Berechnung.
- **DownloadRepositoryTest** — Download-Zustand-Maschine.
- **FileDownloaderTest** — Datei-Download-Fehlerbehandlung.
- **GeoTest** — Distanzberechnung (equirectangular, haversine).
- **KalmanFilterTest** — GPS-Filterung.
- **ManeuverTest** — Turn-Berechnung.
- **MapMatcherTest** — GPS-auf-Route-Projektion.
- **NavigationManagerTest** — Turn-by-Turn-State-Machine.
- **PhrasebookTest** — Maneuver-zu-Text Lokalisierung.
- **PlaceSearchTest** — Offline-Ortssuche.
- **RegionStoreTest** — Datei-Region-Indexing.
- **ReroutingEngineTest** — Off-Route-Recovery.
- **RouteSimulatorTest** — Demo-Route-Playback.
- **RoutingProfileTest** — BRouter-Profil-Parsing.
- **SegmentTilesTest** — .rd5-Tile-Organisation.

**Ausführen:**
```bash
cd /Users/benwiederhold/Documents/OpenCurv/OpenCurv
./gradlew testDebugUnitTest
```

### Logic Verifier (`tools/verifier/`)

Standalone JVM-Build (kein Android SDK nötig). Kompiliert:
- `brouter/src/main/java` (BRouter Java-Quellen).
- `app/src/main/java` außer UI/Service/Voice/DI/MainActivity/OpenCurvApp + bestimmte Data-Layer (LocationProvider, MapCatalog, ProfileManager).
- `app/src/test/java` (identische Tests wie App-Build).

Deckt Kernlogik ab, die Android-frei ist.

**Ausführen:**
```bash
cd /Users/benwiederhold/Documents/OpenCurv/OpenCurv/tools/verifier
gradle test
# oder
cd /Users/benwiederhold/Documents/OpenCurv/OpenCurv
./gradlew -p tools/verifier test
```

---

## CI

`.github/workflows/android.yml`:

**Job 1: Logic** (Ubuntu, JVM 17)
- Checkout.
- Setup Java 17 (Temurin).
- `gradle --project-dir tools/verifier test --no-daemon` — Verifier-Tests.
- Upload Report.

**Job 2: APK** (Ubuntu, Android SDK)
- Checkout.
- Setup Java 17.
- Setup Android SDK.
- `./gradlew testDebugUnitTest --no-daemon` — App Unit-Tests.
- `./gradlew assembleDebug` — Debug APK.
- `./gradlew assembleRelease` — Release APK (debug-signiert, für CI).
- Report APK-Größen.
- Upload APKs + Test-Reports.

**Trigger:** Auf jeden Push + PR + Manual (workflow_dispatch).
**Concurrency:** Cancelt laufende Jobs bei neuem Push (same branch).

---

## Bekannte Schwächen / offene Baustellen

1. **Profil-Parameter hartkodiert** — In `NavigationController.calculateRoute()` wird `profileParams: Map<String, String> = emptyMap()` übergeben (default). Curviness-Parameter könnten von Settings kommen.

2. **Offline-Ortssuche begrenzt** — `PlaceSearchRepository` parst nur POIs aus Mapsforge-Dateien, keine Straßen-Reverse-Geocoding. Nutzer mit unbekanntem Start/Ziel haben wenig Suchoptionen.

3. **Keine Höhen-Darstellung** — Route.ascendMeters wird berechnet (aus BRouter-Daten), aber in UI nicht angezeigt (nur Curviness-Badge).

4. **Rerouting-Schwellen sind hartkodiert** — `NavigationManager` erkennt "off route" bei `OFF_ROUTE_METERS = 35.0` und erst nach `OFF_ROUTE_FIXES = 3` aufeinanderfolgenden Fixes; `ReroutingEngine` hat selbst keine Distanzschwelle, nur einen Cooldown von 12 s. Diese Werte sind geschwindigkeitsunabhängig — bei Landstraßentempo vergehen bis zur Neuberechnung leicht über 100 m.

5. **Keine Karten-Tile-Kompression** — .rd5-Segment-Daten und .map-Dateien liegen unkomprimiert im filesDir. Speicher auf Smartphones limitierend.

6. **Profile bei App-Update manuell sichern** — `ProfileManager.ensureInstalled()` überschreibt alte Profile. Benutzer-modifizierte Profile gehen verloren.

7. **Keine Fallback-Ortssuche online** — Wenn offline-Suche nichts findet, keine Möglichkeit, online zu suchen (keine Netzwerk-API implementiert).

8. **Test-Coverage UI** — Compose-Screens haben keine UI-Tests (nur Unit-Tests für Business Logic).

---

## Build & Run auf diesem Rechner

### Java
- **Version:** 17 (Homebrew oder System).
- **Check:** `java -version`.

### Android SDK
- **Pfad:** `~/Library/Android/sdk` (Standard macOS-Path).
- **Build-Tools/Platform:** Android Gradle Plugin lädt automatisch (via Android Gradle Actions in CI).
- **Lokal:** Falls AVD-Start nötig: `~/Library/Android/sdk/emulator/emulator` (NICHT im PATH).

### AVD
- **Name:** `Medium_Phone` (existierend oder via Android Studio erstellen).
- **Starten:** `~/Library/Android/sdk/emulator/emulator -avd Medium_Phone &`.

### adb / emulator
- **Nicht im PATH.** Volle Pfade nötig:
  - `~/Library/Android/sdk/platform-tools/adb`.
  - `~/Library/Android/sdk/emulator/emulator`.

### Gradle
- **Wrapper:** `./gradlew` (im Repo-Root).
- **Optionen:** `--no-daemon` (für CI), `--stacktrace` (Debug).

**Build-Kommandos:**
```bash
cd /Users/benwiederhold/Documents/OpenCurv/OpenCurv

# Debug APK
./gradlew assembleDebug --stacktrace

# Release APK
./gradlew assembleRelease --stacktrace

# Unit Tests (App)
./gradlew testDebugUnitTest --stacktrace

# Unit Tests (Logic Verifier)
./gradlew -p tools/verifier test --stacktrace

# Install Debug APK (Emulator/Device an USB)
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk

# Run Tests
./gradlew test --stacktrace
```

**Emulator-Start (vorher booten):**
```bash
~/Library/Android/sdk/emulator/emulator -avd Medium_Phone -no-window &
sleep 20
~/Library/Android/sdk/platform-tools/adb wait-for-device
./gradlew assembleDebug
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
~/Library/Android/sdk/platform-tools/adb shell am start -n com.motoroute.debug/com.motoroute.MainActivity
```

---

## Zusammenfassung für KI

Diese Übersicht dokumentiert:

1. **Projektstruktur:** Kotlin/Compose-App + vendored BRouter + standalone Test-Verifier.
2. **54 Kotlin-Dateien** (app/) systematisch nach Schichten (DI, Data, Domain, UI, Service, Voice): jede Datei mit Zweck + öffentliche Typen/Funktionen.
3. **Datenflüsse:** Von Nutzer-Eingabe → BRouter-Routing → Mapsforge-Rendering; GPS-Fix → Kalman-Filter → Map-Matching → Navigation-State → Voice/HUD.
4. **Abhängigkeiten:** AndroidX, Compose, Mapsforge, Coroutines, keine Google Play Services.
5. **Tests:** 19 Unit-Tests (App) + Verifier-JVM-Tests; CI mit GitHub Actions (Logic + APK).
6. **9 bekannte Schwächen:** Profile-Parameter, Ortssuche-Limits, Höhen-UI, Rerouting-Timing, Speicher, Profile-Update-Handling, keine Online-Fallback, UI-Test-Gap, keine Tile-Kompression.
7. **Build:** Java 17, Android SDK unter ~/Library/Android/sdk, adb/emulator ohne PATH, Gradle-Wrapper.

Spätere KI-Sitzungen können diese Datei lesen, ohne die Architektur selbst durchzuarbeiten.

---

## Nachtrag 12.09.2026 (Wellen 6–8)

Neue Pakete und Dateien, die oben noch fehlen:

- **`data/traffic/`** — `AutobahnTrafficSource` (BMDV-Autobahn-API, kein Schlüssel), `TrafficUpdater` (Netzprüfung, 30-min-Takt, Cache `files/traffic_cache.json`), `TrafficRepository`, `MobilithekTrafficParser` (GeoJSON, Anzeige-Schema mit `role=="icon"`), `TrafficIncident.toNoGoAreas()`.
- **`data/cameras/`** + **`domain/cameras/`** — `SpeedCameraRepository` (Assets `cameras/*.cameras.tsv` + `files/cameras/`), `SpeedCameraGrid`, `SpeedCameraWarner` (≤ 1 km, Richtung, Hysterese, Cooldown), Opt-in `settings.speedCameraWarnings`.
- **`data/search/`** — Mapsforge-Pfad entfernt. `QueryParser` (Android-frei), `SqlitePlaceIndex` (liest `files/places/*.places.sqlite`), `PlaceIndexSource`; `PlaceKind.STREET/ADDRESS`.
- **`data/history/RouteHistory`** — letzte Ziele/Touren in `files/history.json`.
- **`domain/NoGoFilter`** (Korridor um die Wegpunkte), **`domain/RecalcTrigger`** (Debounce), **`domain/RoundTripPlanner`**.
- **`data/download/`** — Katalog-Arten `maptiles`, `routing`, `cameras`, `places`; `SegmentTiles.canonicalName()` (Kachelname ohne Regionspräfix, sonst findet BRouter sie nicht); SHA-256-Prüfung; `MapCatalog.refreshFromNetwork()` über `api.github.com` (neuestes `data-*`-Release).
- **`ui/map/MapController`** — Laufzeit-Layer: Puck (Dreieck), POI-Icons, `opencurv-traffic`, `opencurv-cameras`; `PoiHit`/`onPoiTap`.
- **`ui/navigation/`** — HUD mit Klappmenü, `SpeedCameraAlert`/`SpeedCameraBanner`.
- **`ui/components/DraggableSheet`** — `snapTarget()` (Weg + Fling), ganze Fläche ziehbar.
- **Pipeline** — `build_cameras.py`, `build_places.py`; Katalog-Arten `cameras`, `places`.
- **Tests** — App 300, Verifier 267 (`tools/verifier/build.gradle.kts` schließt Android-/UI-Klassen und -Tests aus; Test-Ressourcen aus `app/src/test/resources`).
- **Karten-Abhängigkeit** — Mapsforge ist vollständig entfernt; nur noch MapLibre Native.
