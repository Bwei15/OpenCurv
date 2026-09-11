# Verkehrsdaten-Live (Welle 6)

Welle 5 hat Datenmodell (`TrafficIncident`, `NoGoArea`, `NoGoPolygon`), Parser
(`MobilithekTrafficParser`) und Cache (`TrafficRepository`) gebaut, aber keine
Quelle angeschlossen. Dieser Auftrag liefert die Quelle und den
Aktualisierungs-Mechanismus.

## Quelle: BMDV/Autobahn GmbH Open-Data-API

`https://verkehr.autobahn.de/o/autobahn/` - frei, ohne API-Schlüssel,
**nur Autobahnen** (keine Landes-/Bundesstraßen).

1. `GET /o/autobahn/` → `{"roads": ["A1", "A2", ...]}` (111 Straßen, Stand
   11.09.2026; ein Eintrag `"A60 "` trägt ein Leerzeichen - wird vor dem
   URL-Aufbau getrimmt).
2. Für jede Straße drei Dienste:
   - `GET /o/autobahn/{road}/services/closure`
   - `GET /o/autobahn/{road}/services/roadworks`
   - `GET /o/autobahn/{road}/services/warning`

   Macht **~330 Requests pro Refresh**. `AutobahnTrafficSource` holt sie mit
   höchstens 6 gleichzeitig (`Semaphore`), 10 s Timeout je Request; eine
   fehlschlagende Straße (Timeout, 404, 5xx) liefert für diese Straße leer
   und reißt die übrigen 329 Requests nicht mit.

### Felder, die ausgewertet werden

| Feld | Bedeutung | Verwendung |
| --- | --- | --- |
| `identifier` | global eindeutig | `TrafficIncident.id` (Präfix `autobahn:`) |
| `isBlocked` | `"true"`/`"false"` (String!) | primäres Vollsperrungs-Signal |
| `display_type` | `CLOSURE`, `CLOSURE_ENTRY_EXIT`, `SHORT_TERM_ROADWORKS`, `WARNING`, ... | Klassifikation zusammen mit `impact.symbols` |
| `impact.symbols` | z.B. `["SEPARATE","CLOSED","CLOSED"]`, `["ARROW_UP",...]` | sekundäres Vollsperrungs-Signal (siehe unten) |
| `future` + `startTimestamp` | geplante, noch nicht aktive Sperrung | `startEpochMillis` (nur wenn `future==true`) |
| `extent` | `"lat1,lon1,lat2,lon2"` | Radius der NoGo-Fläche (halbe Diagonale, 60-4000 m geklemmt) |
| `point` / `coordinate` | Referenzpunkt | `TrafficIncident.location` |
| `geometry` (LineString) | Streckenverlauf | `TrafficIncident.polyline` |
| `title`, `subtitle`, `description[]` | Beschreibung | `title`/`description` |

Kein Feld liefert ein strukturiertes Ende - nur Freitext wie "Ende der
Gesamtmaßnahme: 15.09.26" in `description`. Deshalb bleibt `endEpochMillis`
immer `null`: eine Meldung gilt bis zum nächsten erfolgreichen Refresh (siehe
unten), nicht bis zu einem festen Zeitpunkt.

### Vollsperrung erkennen ("Sperr-Symbol")

`isBlocked=="true"` gewinnt immer. Fehlt das (im Praxistest am 11.09.2026
über alle 111 Straßen kein einziges `isBlocked=="true"` gefunden - die API
markiert offenbar auch echte Vollsperrungen meist über die Symbole, nicht das
Flag), gilt eine `closure`-Meldung als Vollsperrung, wenn `impact.symbols`
nicht leer ist, mindestens ein `CLOSED` enthält und **kein** `ARROW_*`
enthält (ein `ARROW_*` bedeutet: Verkehr wird auf eine verbleibende Spur
geführt, also noch passierbar). Das wurde gegen echte Daten geprüft (siehe
"Ergebnis vom Emulator-Lauf" unten) - der erste gefundene Fall dieser Art war
eine Richtungsfahrbahnsperrung auf der A1 für einen Schwertransport, textlich
als "Sperrung" beschrieben, also ein plausibler Treffer.

`roadworks` ohne Vollsperrungssignal wird `CONSTRUCTION`/`WARNING` (wenn
Symbole vorhanden, also Fahrstreifen betroffen) oder `CONSTRUCTION`/`INFO`
(keine Symbole). `warning` wird immer `HAZARD`/`WARNING`.

**Nur Vollsperrungen (`isImpassable`) werden zu NoGo-Flächen** - eine
Baustelle mit Spurverengung sperrt die Route nicht.

### Linien vs. Punkte

Ein `geometry`-`LineString` wird zu `TrafficIncident.polyline`.
`TrafficIncident.toNoGoAreas(spacingMeters = 150.0)` sampelt bei einer Linie
alle 150 m einen Punkt entlang der Strecke und baut daraus eine Kette sich
überlappender `NoGoArea`-Kreise (Radius mindestens `spacingMeters / 2 + 25`),
damit BRouter die ganze gesperrte Strecke meidet statt nur ihren
Referenzpunkt. Ein einzelner Punkt (kein `polyline`) bleibt ein einzelner
Kreis wie bisher (`toNoGoArea()`).

BRouter unterstützt über `NoGoPolygon`/`OsmNogoPolygon` bereits eine echte
Nogo-*Linie* (unclosed Polygon), was für lange Sperrungen weniger und
treffsicherere Einträge ergäbe als eine Kreiskette. Das ist aber nur bis
`BRouterEngine`/`RouteRequest` verdrahtet - `NavigationController.calculate()`
übergibt bisher nur `noGos`, nicht `noGoPolygons`, und
`NavigationController.kt` liegt außerhalb des Auftragsbereichs dieses Agenten
(`app/src/main/java/com/motoroute/data/traffic/*` und einige explizit
genannte additive Stellen). Die Kreiskette ist der Kompromiss, der ganz
innerhalb von `data/traffic/` funktioniert. Siehe "Offene Punkte" unten für
den Nachfolgeauftrag.

## `TrafficUpdater`: wann wird geladen

Einziger Android-spezifischer Teil (`ConnectivityManager`, `SharedPreferences`)
- deshalb in `tools/verifier/build.gradle.kts` aus dem JVM-Build
ausgeschlossen, alles andere in `data/traffic/` bleibt Android-frei und läuft
dort mit.

- **App-Start**: `OpenCurvApp.onCreate()` ruft `container.trafficUpdater.start()`
  nicht-blockierend auf.
- **Netzwerkwechsel**: `ConnectivityManager.registerDefaultNetworkCallback` -
  jeder `onAvailable` löst einen Prüfversuch aus (WLAN↔Mobilfunk↔kein Netz).
- **Alle 30 Minuten**, solange die App läuft (`REFRESH_INTERVAL_MS`).
- Jeder Auslöser lädt nur, wenn **beides** gilt: `NetworkCapabilities` zeigt
  `NET_CAPABILITY_INTERNET` **und** `NET_CAPABILITY_VALIDATED` (tatsächlich
  erreichbares Internet, nicht nur assoziiert), und der letzte erfolgreiche
  Abruf ist älter als 30 Minuten (Zeitstempel in
  `SharedPreferences("traffic_updater")`). Ohne Internet passiert nichts -
  der Cache bleibt unangetastet, offline-first.
- Ein Refresh **ersetzt nur die Daten** (`TrafficRepository.updateIncidents`)
  und berechnet nie selbst eine Route neu. Während aktiver Navigation ist ein
  Refresh also unauffällig im Hintergrund erlaubt; die nächste
  Routenberechnung (manuelles Reroute oder neue Planung) liest dann
  automatisch die frischen `activeNoGoAreas()`.

## Cache-Format

`filesDir/traffic_cache.json`, GeoJSON `FeatureCollection`, geschrieben von
`MobilithekTrafficParser.toGeoJson(incidents, fetchedAtMillis)`:

- Wurzel trägt zusätzlich `"fetchedAt": <epoch millis>` - Zeitpunkt des
  letzten erfolgreichen Abrufs. `TrafficRepository.lastUpdated: StateFlow<Long?>`
  liest das beim Start aus dem Cache und hält es danach in memory nach; für
  eine spätere "Stand 14:32"-Anzeige.
- **Ein Feature pro Meldung**, absichtlich round-trip-fähig durch
  `parseGeoJson` - ein Neustart der App liest denselben Stand ohne
  Duplikate.
- `TrafficRepository.isRefreshing: StateFlow<Boolean>` ist während eines
  laufenden Abrufs `true`; Routing benutzt währenddessen weiter den letzten
  bekannten Stand.
- Bei Erfolg ersetzt der neue Abruf den kompletten alten Stand
  (`updateIncidents`), bei Fehlschlag bleibt Cache und `lastUpdated`
  unverändert (`refreshFrom` gibt `Result.failure` zurück).

## GeoJSON-Schema für den Karten-Agenten (Welle 7)

`TrafficRepository.getIncidentsGeoJson()` ruft
`MobilithekTrafficParser.toDisplayGeoJson(incidents, lastUpdated)` auf - **nicht**
dieselbe Funktion wie der Cache (`toGeoJson`), weil die Anzeige-Variante pro
Linien-Meldung ein zusätzliches Punkt-Feature einfügt (würde beim Cache zu
doppelten Incidents beim nächsten Neustart führen, siehe KDoc in
`MobilithekTrafficParser.kt`).

```jsonc
{
  "type": "FeatureCollection",
  "fetchedAt": 1789131656816,          // epoch millis, für "Stand HH:MM"
  "features": [
    // Punkt-Meldung (Baustelle, Warnung): ein Feature.
    {
      "type": "Feature",
      "id": "autobahn:INRIX-...",
      "geometry": { "type": "Point", "coordinates": [lon, lat] },
      "properties": {
        "id": "autobahn:INRIX-...",
        "title": "A7 | Tannengarten - Badhauser Wald-Ost",
        "description": "...",
        "type": "HAZARD",           // IncidentType-Name
        "severity": "WARNING",      // IncidentSeverity-Name
        "impassable": false,        // true => BRouter behandelt es als NoGo
        "radiusMeters": 50,
        "road": "A7"
      }
    },
    // Linien-Meldung (gesperrte Strecke): LineString-Feature ...
    {
      "type": "Feature",
      "id": "autobahn:2026-045959-...",
      "geometry": { "type": "LineString", "coordinates": [[lon,lat], ...] },
      "properties": { "...": "wie oben", "impassable": true }
    },
    // ... plus ein Punkt-Feature am Streckenmittelpunkt fürs Icon.
    {
      "type": "Feature",
      "id": "autobahn:2026-045959-...:icon",
      "geometry": { "type": "Point", "coordinates": [lon, lat] },
      "properties": { "...": "identisch zur Linie", "role": "icon" }
    }
  ]
}
```

Empfehlung für Welle 7: Linien mit `properties.impassable == true` rot
zeichnen, auf den `role == "icon"`-Punkten ein Sperr-/Barriere-Icon anzeigen;
Punkt-Features ohne `role` sind die eigentlichen Meldungen (Baustellen-/
Warnsymbol nach `properties.type`/`severity`).

## Erweiterungspunkt: Mobilithek/DATEX II

`TrafficSource` (`fun interface { suspend fun fetch(): List<TrafficIncident> }`)
ist der Anschlusspunkt für Landes-/Bundesstraßen. Mobilithek/MDM verlangt ein
Konto und einen API-Key (DATEX-II-Export), was ohne manuelle Registrierung
hier nicht umsetzbar war. Eine künftige `MobilithekTrafficSource: TrafficSource`
würde denselben `MobilithekTrafficParser` (bereits GeoJSON-generisch) nutzen
und in `TrafficUpdater`/`AppContainer` neben `AutobahnTrafficSource`
registriert - z.B. als zweite Quelle, deren Ergebnisse vor `updateIncidents`
zusammengeführt werden.

## Bekannte Grenzen

- **Nur Autobahnen.** Landes-/Bundesstraßen fehlen komplett (s.o.).
- **Kein strukturiertes Ende.** Meldungen laufen nicht selbst ab, sondern nur
  durch den nächsten erfolgreichen Refresh (oder wenn die API sie nicht mehr
  liefert).
- **~330 Requests pro Refresh** gegen einen öffentlichen Dienst ohne
  dokumentiertes Rate-Limit. Ein Emulator-Lauf brauchte ca. 30 s End-to-End
  (App-Start bis `TrafficUpdater`-Log "refreshed"); bei schlechterem Netz
  könnte das deutlich länger dauern. Kein eigenes Backoff über den
  30-Minuten-Mindestabstand hinaus implementiert.
- **Cache-Größe:** ein realer Abruf am 11.09.2026 ergab 4104 Meldungen und
  eine ~6,8 MB große `traffic_cache.json`. Für ein Telefon unproblematisch,
  aber deutlich mehr als die wenigen Test-Fixtures vermuten lassen.
- **Potenziell viele NoGo-Kreise.** Von den 4104 Meldungen waren 657 als
  Vollsperrung (`impassable`) eingestuft, überwiegend Linien - das sampelt zu
  rund 4300 einzelnen `NoGoArea`-Kreisen, die `activeNoGoAreas()`
  `RouteRequest.noGos` mitgibt. Ob BRouter das bei einer echten
  Routenberechnung ohne spürbare Verlangsamung verkraftet, wurde in diesem
  Auftrag **nicht** gemessen (die Emulator-Verifikation hat nur den
  Datenabruf geprüft, keine Route berechnet). Ein Folgeauftrag sollte das
  benchmarken und bei Bedarf entweder die NoGo-Liste auf die
  Routen-Bounding-Box einschränken oder `NoGoPolygon` (statt Kreiskette)
  durch `NavigationController` verdrahten.
- **Heuristik für "Sperr-Symbol"** (`isBlocked` fehlt oft) wurde nur gegen
  wenige reale Beispiele geprüft, nicht formal validiert. Falsch-positive
  Vollsperrungen würden Routen unnötig umleiten; falsch-negative würden eine
  echte Sperrung nicht vermeiden. Sollte mit mehr Praxisdaten nachgeschärft
  werden (z.B. Abgleich gegen `description`-Freitext auf "Vollsperrung"/
  "gesperrt").

## Nachweis (Emulator, 11.09.2026)

Debug-APK gebaut, auf `emulator-5554` installiert, App gestartet.
`adb logcat -s TrafficUpdater` zeigt:

```
I TrafficUpdater: refreshed (start): 4104 incidents
```

`adb shell run-as com.motoroute.debug ls -la files/` zeigt
`traffic_cache.json` (7.126.712 Bytes, Zeitstempel 15:00:57 - direkt nach dem
Log-Eintrag). Inhalt geprüft: 4104 Features, davon 657 `ROAD_CLOSURE`/
`CRITICAL` (alle als `LineString`), 3227 `CONSTRUCTION`, 220 `HAZARD`.
