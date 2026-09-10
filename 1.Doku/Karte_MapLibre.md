# Karten-Rendering mit MapLibre Native & PMTiles

Dieses Dokument beschreibt die Architektur und Implementierung des Karten-Renderers
in OpenCurv nach der Migration von Mapsforge zu MapLibre Native in Welle 3.

---

## 1. Motivation: Warum MapLibre Native?

OpenCurv ist eine Motorrad-Navigations-App. Für den Einsatz am Lenker gelten
extreme Anforderungen an die Kartendarstellung:

1. **Flüssige 60 fps bei Vibration und hoher Geschwindigkeit:**
   Bei 100 km/h bewegt sich das Fahrzeug rund 28 Meter pro Sekunde. Mapsforge
   renderte Kacheln als Software-Bitmaps auf der CPU. Bei Rotation (`headingUp`),
   stetigem Zoomen oder Neigen führte dies unweigerlich zu Rucklern, Frame-Drops
   und hohem Akkuverbrauch.
2. **Echte 3D-Perspektive und stufenlose Rotation:**
   MapLibre Native nutzt moderne Hardware-Beschleunigung (OpenGL ES / Vulkan).
   Neigungswinkel (`perspectiveTilt` bis 60°) und fahrtrichtungsbezogene
   Ausrichtung (`headingUp`) werden direkt in der GPU berechnet, ohne dass Kacheln
   neu gerendert werden müssen.
3. **Vektor-Typografie und Schärfe:**
   Straßennamen und Ortsbezeichnungen werden als Vektorsymbole bei jeder Zoomstufe
   und jeder Drehung gestochen scharf und aufrecht lesbar gehalten.

---

## 2. Einbindung lokaler PMTiles (`pmtiles://file://`)

Klassische Vector-Tile-Stacks (z. B. Mapbox GL / MapLibre) erwarten TileJSON- oder
XYZ-Kacheln über HTTP. Ein lokaler HTTP-Server auf dem Smartphone (z. B. NanoHTTPD)
wäre instabil, ressourcenhungrig und ein Sicherheitsrisiko.

OpenCurv nutzt das moderne **PMTiles-Format (v3)**:
- **Single-File-Archiv:** Die gesamte Kachelhierarchie einer Region (z. B. Bayern,
  Alpen) liegt in einer einzigen `.pmtiles`-Datei im internen Speicher
  (`offlineData.mapTilesDir`).
- **Nativer Protokoll-Handler:** Ab MapLibre Android SDK 11.7.0 unterstützt die
  C++-Engine das Schema `pmtiles://file://`.
- **Zero-Network-Stack:** Die Kacheln werden direkt über POSIX-File-Reads aus dem
  Dateisystem gelesen. Weder Sockets noch Localhost-HTTP-Anfragen sind nötig.

### URL-Auflösung zur Laufzeit

In den Stil-Vorlagen (`assets/maplibre/style_day.json` / `style_night.json`) steht
ein eindeutiger Platzhalter:

```json
"sources": {
  "openmaptiles": {
    "type": "vector",
    "url": "__PMTILES_URL__"
  }
}
```

Wenn eine `.pmtiles`-Datei im Kachelverzeichnis vorhanden ist, ersetzt
`MapController.buildStyleJson()` den Platzhalter:

```kotlin
val pmtilesFile = offlineData.mapTilesDir
    .listFiles { f -> f.isFile && f.extension.equals("pmtiles", ignoreCase = true) }
    ?.sortedBy { it.name }
    ?.firstOrNull()

if (pmtilesFile != null) {
    val url = "pmtiles://file://${pmtilesFile.absolutePath}"
    return template.replace(PMTILES_PLACEHOLDER, url)
}
```

Ist noch keine Kacheldatei installiert, entfernt `MapController` die Vektorquellen
sauber aus dem JSON und zeigt einen neutralen Hintergrund in der Markenfarbe,
statt einen Renderer-Fehler zu werfen.

---

## 3. Stil-Struktur & Farbpalette (Tag & Nacht)

Die Stile basieren auf der OpenCurv-Designspezifikation (`1.Doku/Map_Design.md`
und `1.Doku/Design_System.md`):

### Schichten (Layer-Hierarchie)
1. `background`: Flächiger Untergrund (`#F1EFE8` Tag, `#17150E` Nacht).
2. `landuse-residential`, `landcover_grass`, `landcover_wood`: Gedämpfte Grün-
   und Naturtöne, bewusst kontrastarm, damit Straßen optisch hervortreten.
3. `water`, `waterway`: Gewässerflächen und Flussläufe.
4. `building`: Dezente Gebäudeumrisse ab Zoom 13.
5. **Straßen (Zweischicht-Konstruktion / Casing & Core):**
   - Jede Straßenklasse (`road_minor`, `road_major`) besteht aus zwei Layern:
     - **Casing (Fassung):** Ein dunklerer oder kontrastreicherer Außenstrich.
     - **Core (Kern):** Der helle Innenstrich.
   - Der Casing-Layer liegt in der Zeichenreihenfolge *unter* dem Core-Layer
     und ist bei jedem Zoom-Stopp um 0.8 bis 4.0 Einheiten breiter.
   - Dadurch bleiben parallele Fahrbahnen auch bei schlechten Lichtverhältnissen
     oder im Sonnenlicht klar trennbar.
6. `road_label`: Straßennamen mit 2 dp Halo zur Hintergrundabgrenzung.
7. `poi_fuel`, `poi_food`: Relevante Anlaufstellen mit Pin und Label.
8. `place_label_town`: Städte- und Ortsnamen in Noto Sans Bold Uppercase.

### Tag- und Nachtregister

- **Tag (`style_day.json`):** Warme Naturtöne (`#F1EFE8` Land, `#DCF0D6` Wiese,
  `#E5C16C`/`#FFE082` Hauptstraßen). Hohe Leuchtdichte für beste Ablesbarkeit
  bei Tageslicht und direkter Sonneneinstrahlung.
- **Nacht (`style_night.json`):** Dunkles Register (`#17150E` Hintergrund,
  `#1E3C15` Wald). Straßen in tiefem Gelb/Bernstein (`#664F00` / `#997700`).
  Blendet den Fahrer bei Nachtfahrten nicht und erhält die Dunkeladaption des
  Auges.

---

## 4. Lokale Glyphen (Schriften)

MapLibre benötigt vorgerenderte Protocol-Buffer-Glyphen (`.pbf`) für die Textdarstellung.
Um externe Server-Abfragen (wie z. B. zu MapTiler oder Mapbox Fonts) vollständig zu
unterbinden, sind die benötigten Schriftschnitte lokal in der APK gebündelt:

- Pfad: `app/src/main/assets/maplibre/glyphs/`
- Enthaltene Schnitte:
  - `Noto Sans Regular` (0–255.pbf, 256–511.pbf)
  - `Noto Sans Bold` (0–255.pbf, 256–511.pbf)
- Konfiguration im Stil:
  ```json
  "glyphs": "asset://maplibre/glyphs/{fontstack}/{range}.pbf"
  ```

---

## 5. Dynamische Overlays & GPU-Ebenen

Dynamische Fahrdaten (geplante Route, gefahrener Pfad, GPS-Positionspuck, Ziel-Pin)
sind **nicht** in die statischen PMTiles eingebrannt, sondern werden als
Laufzeit-Overlays über die Karte gelegt:

1. **Routenlinie (`showRoute`):**
   - Einspeisung über eine eigene `GeoJsonSource` (`ROUTE_SOURCE_ID`).
   - Zweistufige `LineLayer`-Konstruktion:
     - `ROUTE_CASING_LAYER_ID`: 14 dp Breite in `routeCasing` (`#000000`).
     - `ROUTE_LAYER_ID`: 10 dp Breite in Signal-Gelb (`colors.route`).
2. **Positions-Puck (`updatePuck`):**
   - Eigene `GeoJsonSource` mit Kursrichtung (`headingDegrees`).
   - Gerendert als `SymbolLayer` mit Richtungspfeil oder Kreis.
3. **Lebenszyklus bei Stil-Wechsel:**
   - Ein Wechsel zwischen Tag- und Nachtstil zerstört alle Layer und Sources der
     MapLibre-Instanz.
   - `MapController.attachOverlayLayers()` baut die dynamischen Sources und Layer
     sofort nach Fertigstellung des neuen Stils wieder auf (`reapplyOverlays()`).

---

## 6. Lifecycle-Handling & Compose-Integration

MapLibre Native bringt eine eigene Android-`MapView` mit OpenGL-Kontext und
Thread-Management mit.

- **Auskopplung aus Compose:**
  `MapController` wird im Prozess-Scope gehalten und überlebt Recompositionen
  sowie Konfigurationswechsel (z. B. Display-Drehung).
- **Compose-Brücke (`MapScreen.kt`):**
  Bindet die `MapView` über `AndroidView` ein. `LifecycleEventObserver` leitet
  `ON_START`, `ON_RESUME`, `ON_PAUSE`, `ON_STOP` und `ON_DESTROY` direkt an die
  `MapView` weiter, um Lecks und GPU-Kontextverlust zu verhindern.

---

## 7. Offline-Autarkie & Sicherheit

OpenCurv funktioniert zu 100 % offline. Für das Karten-Rendering wird **kein
einziges Datenpaket** über das Netzwerk übertragen:

- `style_day.json` und `style_night.json` referenzieren ausschließlich `asset://`
  und `pmtiles://file://`.
- In `network_security_config.xml` existiert **kein** Eintrag für Kacheln oder Fonts:
  ```xml
  <!-- Map rendering (MapLibre) needs no entry here at all: the style JSON, the
       glyph PBFs and the PMTiles vector-tile archive are all read from the
       device (asset:// and pmtiles://file://), never fetched over HTTP. Verified
       in airplane mode - see 1.Doku/Karte_MapLibre.md. -->
  ```
- **Verifikation im Flugmodus:**
  Wird das Gerät in den Flugmodus versetzt, rendert die Karte unverändert schnell,
  flüssig und vollständig ohne Fehlermeldungen oder Timeout-Verzögerungen.
