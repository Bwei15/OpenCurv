# Tech-Scouting: Open-Source-Bausteine für OpenCurv

Stand: September 2026. Diese Datei ist eine Entscheidungsvorlage für die geplante Architektur (GitHub Actions als Verarbeitungszentrale, On-Device-Routing + -Rendering auf Basis vorprozessierter Artefakte). Alle Aktivitätsangaben (`pushed_at`) stammen aus der GitHub-API, abgefragt am 10.09.2026.

## Entscheidungstabelle

| # | Thema | Empfehlung | Lizenz | Risiko |
|---|-------|-----------|--------|--------|
| A | Routing-Engine + Kanten-Score | **BRouter behalten**, curve_score als Pseudo-Tag einbacken | MIT (BRouter) | mittel |
| B | Kurvigkeits-Berechnung | Algorithmus von `adamfranco/curvature` als Vorlage, selbst reimplementieren | GPL-3.0 (nur Referenz, nicht einbinden) | niedrig |
| C | Vektorkachel-Erzeugung in CI | **Planetiler einbinden** | Apache-2.0 | niedrig |
| D | Offline-Vektorkacheln auf MapLibre Android | **PMTiles einbinden** (ab SDK 11.7.0, `pmtiles://file://`) | BSD-3-Clause | mittel |
| E | Höhendaten | Copernicus DEM GLO-30 (AWS S3, kein Login) + BRouters SRTM-Importer nutzen | Copernicus-Lizenz (frei, Attribution) | niedrig |
| F | H3-Hexagonraster | `h3-android` einbinden (optional, nur für Hotspot-Layer) | Apache-2.0 | niedrig |
| G | Sprachausgabe/Bluetooth | Muster von OsmAnd/Sygic übernehmen, selbst bauen | – (kein fertiges Modul) | mittel |
| H | MapLibre Navigation SDK Android | **Nicht einbinden**, nur UI-Ideen als Vorlage nutzen | MIT | mittel-hoch |

---

## A) On-Device-Routing-Engine mit vorberechnetem Kanten-Score

### BRouter behalten oder auf GraphHopper wechseln?

**BRouter — Map-Creation-Pipeline und Pseudo-Tags**

BRouter besitzt ein eigenes binäres Kartenformat (`.rd5`, 5°×5°-Kacheln), erzeugt aus OSM-PBF-Dateien über das Skript `misc/scripts/mapcreation/process_pbf_planet.sh` ([build_segments.md](https://github.com/abrensch/brouter/blob/master/docs/developers/build_segments.md)). Die zulässigen Tags/Werte, die in die binäre `.rd5`-Kodierung einfließen, stehen in `lookups.dat` ([misc/profiles2/lookups.dat](https://github.com/abrensch/brouter/blob/master/misc/profiles2/lookups.dat), [Profile Developers Guide](https://github.com/abrensch/brouter/blob/master/docs/developers/profile_developers_guide.md)). Die Tabelle darf um neue Tags/Werte erweitert werden, sofern nur am Ende angehängt wird (Versionierung über Major/Minor-Nummer in `lookups.dat`). Ein `.brf`-Profil liest Tags per `<tag-name>=<value>`-Lookup-Match.

Wichtiger Fund: Es existiert eine **Produktionsvariante** des Build-Skripts, `process_pbf_planet_production.sh`, die laut Doku "a more compact process and allows the integration of pseudo tags" bietet ([build_segments.md](https://github.com/abrensch/brouter/blob/master/docs/developers/build_segments.md)). Das ist exakt der Hebel, um einen synthetischen `curve_score` pro Way einzubacken — **aber**: Die genaue Mechanik (wie/wo Pseudo-Tags injiziert werden, Datenformat der Zusatzdaten) ist in der Doku nicht im Detail beschrieben; das ist unsicher und müsste am Quellcode/durch Ausprobieren verifiziert werden. Elevation wird bereits im selben Pipeline-Schritt (PosUnifier) aus SRTM-Kacheln aufgeprägt ([BRouter Elevation](https://brouter.de/brouter/elevation.html)).

BRouter selbst ist mit `pushed_at: 2026-09-09` (GitHub-API) hochaktiv gepflegt, MIT-lizenziert, 714 Stars, 235 offene Issues — kein verwaistes Projekt. Ein containerisierter Community-Wrapper für die Segment-Erzeugung existiert (`mjaschen/brouter-routingdata-builder`, zuletzt Nov 2025 aktualisiert, [Repo](https://github.com/mjaschen/brouter-routingdata-builder)).

**GraphHopper — Android-Tauglichkeit**

- Aktuelle Version: **GraphHopper 11.0** (14.10.2024, [Release Notes](https://github.com/graphhopper/graphhopper/releases)), benötigt laut Build-Doku **Java 17+**.
- GraphHopper hat den offiziellen Android-Support **eingestellt**: Die Android-Demo wurde bereits in Version 2.0 entfernt ([graphhopper.com Blog 2.0](https://www.graphhopper.com/blog/2020/09/30/graphhopper-routing-engine-2-0-released/)); ein Nutzer bestätigt 2024 im offiziellen Issue-Tracker "Am aware you already not giving support more to android" ohne Widerspruch der Maintainer ([Issue #2479](https://github.com/graphhopper/graphhopper/issues/2479)).
- Praktisch scheitert `graphhopper-core` auf Android an harten Inkompatibilitäten: `java.awt`-Importe (Android hat kein AWT), fehlschlagende Log4j-URL-Auflösung im APK-Kontext, sowie ART-Klasseninitialisierungsfehler bei Lambda-Synthetics ([Issue #2479](https://github.com/graphhopper/graphhopper/issues/2479), [Forum-Thread "Compilation for Android"](https://discuss.graphhopper.com/t/compilation-for-android/984)). Ältere Snapshot-Versionen (0.9/0.10) verlangten zusätzlich minSdk ≥ 26 wegen `MethodHandle.invoke`/Desugaring-Grenzen ([Forum-Thread](https://discuss.graphhopper.com/t/how-to-support-previous-versions-of-android-prior-to-oreo/2489)).
- **Positiv:** GraphHopper hat exakt das gesuchte Feature bereits eingebaut — einen `curvature`-EncodedValue (`beeline distance / edge_distance`, 0..1) plus ein eingebautes Beispiel-Custom-Model `curvature.json`, das kurvige Straßen im Priority-Term bevorzugt ([custom-models.md](https://github.com/graphhopper/graphhopper/blob/master/docs/core/custom-models.md), [Forum: Curvature settings](https://discuss.graphhopper.com/t/help-on-curvature-settings-in-custom-model/9885)). Und: **Kurviger**, der direkte Konkurrent im Motorrad-Segment, baut nachweislich auf GraphHopper auf ([OSM-Wiki: Kurviger](https://wiki.openstreetmap.org/wiki/Kurviger)) — allerdings serverseitig über die Kurviger/GraphHopper-API, nicht on-device.
- Kein aktiv gepflegtes Android-Beispiel gefunden: `mayfourth/graphhopper-android` und das offizielle `graphhopper/graphhopper/tree/master/android`-Verzeichnis sind Altlasten aus der Vor-2.0-Ära; ein GraphHopper-Forumsthread beschreibt Offline-Android-Support 2023 als "okayish", aber mit Zusatzaufwand für Graph-Merging bei Kachel-Übergängen ([Issue #1940](https://github.com/graphhopper/graphhopper/issues/1940)).

**Valhalla auf Android**

Kein offizieller Mobile-Support im Hauptrepo ([Discussion #4509](https://github.com/valhalla/valhalla/discussions/4509)). Es gibt aber eine aktive Community-Lösung: `Rallista/valhalla-mobile`, MIT-lizenziert, Maven-Central-Artefakt `io.github.rallista:valhalla-mobile:0.6.1`, JNI-Bindings für Android + Swift für iOS, `pushed_at: 2026-09-06` (sehr aktiv), basiert auf Valhalla 3.6.3 als Fork ([Repo](https://github.com/Rallista/valhalla-mobile), [Discussion #4746](https://github.com/valhalla/valhalla/discussions/4746)). Vorteil laut Diskussion: kleiner Speicher-Footprint durch hierarchisches Tile-Format; Herausforderung waren Protobuf-Abhängigkeiten im Mobile-Build. Version ist Pre-1.0, API kann sich noch ändern.

**Größenabschätzung Graph/Kacheln pro Bundesland-Region**

- Geofabrik-Rohdaten Bayern (`bayern-latest.osm.pbf`): **805 MB** ([Geofabrik Bayern](https://download.geofabrik.de/europe/germany/bayern.html)).
- BRouter-`.rd5`: keine belastbare öffentliche Zahl je Bundesland gefunden (**unsicher** — müsste selbst gemessen werden); die Segmentierung erfolgt in 5°×5°-Kacheln, Bayern liegt in 1–2 solcher Kacheln, die Kompression ist laut BRouter-Doku bewusst kompakt gehalten.
- Für Planetiler gilt als Faustregel: Ausgabe-Größe und RAM-Bedarf skalieren mit der PBF-Eingabegröße (siehe Abschnitt C) — bei 805 MB PBF ist ein Vektorkachel-Output im niedrigen einstelligen GB-Bereich plausibel, aber **unsicher**, da keine Bayern-spezifische Messung gefunden wurde.
- Das 2-GB-Limit pro GitHub-Release-Datei ([GitHub-Doku zu Release-Assets](https://docs.github.com/en/repositories/releasing-projects-on-github)) sollte für ein einzelnes Bundesland in beiden Artefakten ausreichen, für "Deutschland gesamt" in einer Datei ggf. eng werden — vor dem produktiven Rollout mit echten Zahlen verifizieren.

**Urteil**: BRouter behalten. Der Wechsel zu GraphHopper scheitert nicht an Lizenz oder Feature-Reife (GraphHopper hat curvature/CustomModel sogar eingebaut und Kurviger beweist das Konzept serverseitig), sondern schlicht daran, dass GraphHopper offiziell keinen Android-Support mehr bietet und in der Praxis an AWT-/Log4j-/ART-Inkompatibilitäten scheitert. BRouter ist aktiv gepflegt, bereits im Repo vendored, unterstützt bereits SRTM-Elevation-Imprinting, und bietet mit den "Pseudo Tags" der Produktions-Buildskripte einen plausiblen (wenn auch unzureichend dokumentierten) Weg zu einem eigenen `curve_score`-Tag. Valhalla ist technisch am saubersten für native Performance, aber die Mobile-Bindings sind ein junger Drittanbieter-Fork (Pre-1.0) ohne offiziellen Upstream-Support — als Fallback/Beobachtungskandidat vormerken, nicht jetzt migrieren.

---

## B) Kurvigkeits-Berechnung aus OSM

**`adamfranco/curvature`** ([Repo](https://github.com/adamfranco/curvature), [Wiki](https://github.com/adamfranco/curvature/wiki)) ist das bekannteste OSS-Projekt in diesem Raum: Es berechnet für jede OSM-Way einen "curvature"-Wert, indem über jedes Dreier-Punktfenster der Linie der Umkreisradius (Circumcircle) bestimmt wird — dieser Radius entspricht dem lokalen Kurvenradius. Segmente werden nach Radius-Klasse gewichtet (0 für gerade, 1 für weite Kurven, bis 2 für enge Spitzkehren) und aufsummiert ([README](https://github.com/adamfranco/curvature/blob/master/README.md)). Lizenz: **GPL-3.0-or-later**. Letzter Push laut GitHub-API: **2022-01-12** — das Projekt ist de facto eingefroren, aber der Algorithmus ist simpel genug, um ihn ohne Codeübernahme neu zu implementieren (reine Geometrie, keine komplexe Bibliothek). Der Output ist auf KML/GeoJSON-Visualisierung ausgelegt, nicht auf direktes OSM-Way-Tagging — für unsere Zwecke (Tag-Wert pro Way in der `.rd5`/Planetiler-Pipeline) müsste ohnehin ein eigener Exporter geschrieben werden.

**GraphHopper `curvature`-EncodedValue**: siehe Abschnitt A — arbeitet mit der einfacheren Metrik `beeline_distance / edge_length` (Umwegfaktor statt Kreisradius), ist aber production-tested und Teil eines aktiv gepflegten Projekts ([custom-models.md](https://github.com/graphhopper/graphhopper/blob/master/docs/core/custom-models.md)). Diese Metrik ist deutlich einfacher zu implementieren als Umkreisradien und dürfte für S-Kurven-Erkennung in Kombination mit Wegrichtungsänderungen ausreichen.

**OsmAnd-Motorbike** (`schmidi2/osmand-motorbike`, [Repo](https://github.com/schmidi2/osmand-motorbike)) ist ein OsmAnd-Routing-Profil + Rendering-Theme speziell fürs Motorradfahren: Es straft "uninteressante" (gerade) Straßen und niedrige Speed-Limits ab, ohne Autobahnen pauschal zu bestrafen ("a straight motorway is still better than a straight primary road"). Konzeptionell deckt sich das exakt mit den in der Aufgabenstellung genannten Abwertungen (Zickzack im Ort, stumpfe Abbieger). Aber: Laut GitHub-API zuletzt gepusht **2016-06-30** — seit fast 10 Jahren tot, nur 9 Stars, keine Lizenzangabe im Repo. Nutzbar nur als Ideengeber für die `.brf`-Gewichtungslogik, nicht als Codequelle.

**Kurviger** (der direkte Konkurrent, [OSM-Wiki](https://wiki.openstreetmap.org/wiki/Kurviger)) veröffentlicht seinen Algorithmus nicht offen; bekannt ist nur "basiert auf GraphHopper" und dass Nutzer den Grad der Kurvigkeit einstellen können. Kein Code zum Übernehmen.

**Urteil**: Kein fertiges Projekt lässt sich 1:1 einbinden (GPL-Lizenz bei curvature, tote Projekte bei osmand-motorbike, closed-source bei Kurviger). Die Umkreisradius-Methode von `adamfranco/curvature` ist aber ein gut dokumentierter, einfach nachzubauender Algorithmus, und GraphHoppers `curvature`-EncodedValue liefert eine noch simplere Alternative/Ergänzung (Umwegfaktor). Empfehlung: Algorithmus-Idee übernehmen (nicht Code), eigene Implementierung in der BRouter-Pseudo-Tag-Pipeline (Abschnitt A), kombiniert mit den aus OsmAnd-Motorbike bekannten Abwertungsregeln für Ortsdurchfahrten/90°-Ecken.

---

## C) Vektorkachel-Erzeugung in GitHub Actions

**Planetiler vs. Tilemaker — RAM/Zeit-Vergleich**

- Planetiler-Faustregel (offiziell): **mindestens 0,5× RAM relativ zur `.osm.pbf`-Größe**, bei ≥1,5× RAM kann der Node-Location-Cache komplett im Speicher gehalten werden (schneller) ([PLANET.md](https://github.com/onthegomap/planetiler/blob/main/PLANET.md)). Für Bayern (805 MB PBF) bedeutet das ca. 0,4–1,2 GB RAM-Minimalbedarf — weit innerhalb der 16 GB eines Standard-GitHub-Runners.
- Planet-Maßstab als Referenz: Planetiler schafft den kompletten Planeten in ca. 1–1,5 Stunden bei ~100 GB RAM ([Tilemaker-Diskussion #434](https://github.com/systemed/tilemaker/discussions/434)); Tilemaker braucht für denselben Job 7–8 Stunden und ~144 GB RAM (gleiche Quelle). Ein konkretes Custom-Runner-Beispiel (First Basemap / palewi.re) nutzt einen `r5d.8xlarge` (32 vCPU, 256 GB RAM) und braucht dafür ca. 90 Minuten pro Produkt für den ganzen Planeten, mit NVMe-Mount-Trick und 200 GB Java-Heap ([planetiler-action.html](https://palewi.re/docs/first-basemap/planetiler-action.html)) — explizit weil "GitHub's standard Actions runners have fairly limited resources" für Planet-Scale.
- Tilemaker kann mit 16 GB RAM den Planeten verarbeiten, aber "albeit slowly" ([Tilemaker-Diskussion #593](https://github.com/systemed/tilemaker/discussions/593)); für ein einzelnes Bundesland ist Tilemakers RAM-Hunger kein Thema mehr, dort skaliert das Verhältnis günstiger.
- **GitHub-Actions-Standard-Runner (2026)**: 4 vCPU, 16 GB RAM (public repos), aber nur **14 GB garantiert freier Plattenspeicher** (72 GB Gesamtplatte, 84 GB OS-Disk abzüglich vorinstallierter Tools) ([runner-images Discussion #9329](https://github.com/actions/runner-images/discussions/9329), [GitHub Docs](https://docs.github.com/en/actions/reference/specifications-for-github-hosted-runners)). Das ist der eigentliche Engpass, nicht RAM — mit einer "Free Disk Space"-Action lassen sich daraus ca. 63 GB freispielen ([free-disk-space Action](https://github.com/jlumbroso/free-disk-space)).

**Für eine Bayern-große Region**: RAM reicht auf dem 16-GB-Standard-Runner komfortabel. Plattenplatz (14 GB frei out-of-the-box) ist knapp, aber mit einem Disk-Cleanup-Schritt (Docker-Images/Toolchains vorab entfernen) für eine 805-MB-PBF plus Zwischendateien plausibel machbar — sollte aber in einem echten CI-Lauf verifiziert werden (**unsicher**, da keine Bayern-spezifische GH-Actions-Messung öffentlich gefunden wurde).

**Schema-Optionen**: OpenMapTiles-Schema ist der De-facto-Standard für MapLibre-Styles, hat aber Attributions-/Lizenzauflagen der Herkunftsfirma MapTiler ([madewithmaplibre.com](https://madewithmaplibre.com/basemaps/tiling/openmaptiles/)). Shortbread (von Geofabrik entwickelt) ist eine alternative, attributionsärmere Schema-Definition, die eigene Styles braucht (nicht kompatibel zu OpenMapTiles-Styles) ([Shortbread-Vergleich](https://pka.github.io/customizing-shortbread-vector-tiles/), [shortbread-demo-maplibre](https://github.com/shortbread-tiles/shortbread-demo-maplibre)). Planetiler bringt ein eingebautes OpenMapTiles-Profil mit; Tilemaker unterstützt beide Schemata je nach Konfigurationsdatei.

**Urteil**: Planetiler einbinden. Es ist für Regionen unterhalb von "ganz Europa" klar überlegen in Speed/RAM-Effizienz, hat ein eingebautes OpenMapTiles-Profil (spart eigene Schema-Arbeit), ist Apache-2.0-lizenziert und aktiv gepflegt (`pushed_at: 2026-09-10`). Tilemaker bleibt als Fallback interessant, falls das eigene Schema stark vom OpenMapTiles-Standard abweichen soll (z. B. eigene Kurven-Hotspot-Layer), da es flexiblere Lua-Konfiguration bietet.

---

## D) Offline-Vektorkacheln auf MapLibre Native Android

- MapLibre Native Android unterstützt PMTiles **ab Version 11.7.0** nativ über das URL-Schema `pmtiles://` ([MapLibre-Newsletter Januar 2025](https://maplibre.org/news/2025-02-03-maplibre-newsletter-january-2025/), [PMTiles-Beispielseite](https://maplibre.org/maplibre-native/android/examples/data/PMTiles/)).
- Für eine **lokale, mitgelieferte** Datei ist das Schema `pmtiles://file://<absoluter Pfad>` zu verwenden, z. B. `pmtiles://file://${context.getExternalFilesDir(null)}/bayern.pmtiles` ([PMTiles-Beispielseite](https://maplibre.org/maplibre-native/android/examples/data/PMTiles/)). **Wichtig:** `pmtiles://asset://` (Dateien in `src/main/assets/`) wird explizit **nicht** unterstützt, weil der Android-`AssetManagerFileSource` keine Byte-Range-Reads kann, die PMTiles für Header/Metadaten braucht — die Datei muss also ins App-eigene Dateisystem entpackt/kopiert werden (z. B. beim ersten Start oder Download).
- Einschränkung: "PMTiles sources do not support offline pack downloads or caching" laut offizieller Doku — betrifft aber primär MapLibres eingebaute Offline-Pack-API für Online-Kacheln; für unseren Fall (die Datei ist ohnehin lokal und wird von GitHub Releases heruntergeladen, nicht über MapLibres Offline-Manager) ist das unkritisch.
- Alternative: MBTiles über einen lokalen HTTP-Server (z. B. eingebetteter NanoHTTPD) oder eine Custom-FileSource-Implementierung — historisch der übliche Weg vor PMTiles-Support, aber Mehraufwand/Overhead ggü. dem nativen `pmtiles://`-Schema.
- Aktuelles Maven-Artefakt: `org.maplibre.gl:android-sdk:13.4.1` ([Maven Central](https://search.maven.org/artifact/org.maplibre.gl/android-sdk)).
- Reifegrad 3D/Symbol-Rendering: Pitch/Bearing und Symbol-Layer sind über den Style-Spec seit Langem stabil dokumentiert ([MapLibre Style Spec](https://maplibre.org/maplibre-style-spec/layers/)); **3D-Terrain** ist dagegen laut MapLibre-Newsletter Ende 2025/Anfang 2026 noch aktiv in Entwicklung, nicht als vollständig ausgereift zu betrachten ([Discussion #326](https://github.com/maplibre/maplibre/discussions/326), [Newsletter Dez. 2025](https://maplibre.org/news/2026-01-03-maplibre-newsletter-december-2025/)).
- PMTiles selbst: Referenzimplementierungen unter **BSD-3-Clause**, die Formatspezifikation ist public-domain/CC0 ([PMTiles LICENSE](https://github.com/protomaps/PMTiles/blob/main/LICENSE), [Doku](https://docs.protomaps.com/pmtiles/)).

**Urteil**: PMTiles einbinden. Es ist die technisch sauberste Lösung für "eine Datei, lokal, ohne eigenen Server" und seit gut anderthalb Jahren nativ in MapLibre Android integriert — spart einen eigenen HTTP-Server oder Custom-FileSource-Code. Risiko mittel, weil die Funktion noch relativ jung ist (Edge Cases wie fehlender Offline-Pack-Support, keine Caching-Schicht) und 3D-Terrain in MapLibre Native allgemein noch nicht als "fertig" gelten sollte — für reines Pitch/Bearing (ohne echtes Terrain-Relief) ist die Reife aber ausreichend.

---

## E) Höhendaten

- **Copernicus DEM GLO-30** ist ohne Login per AWS S3 abrufbar: `aws s3 ls --no-sign-request s3://copernicus-dem-30m/` ([AWS Open Data Registry](https://registry.opendata.aws/copernicus-dem/), [S3-Bucket-Readme](https://copernicus-dem-30m.s3.amazonaws.com/readme.html)) — das funktioniert 1:1 in einer GitHub Action (aws-cli ist auf `ubuntu-latest` vorinstalliert, kein Credential nötig dank `--no-sign-request`).
- OpenTopography bietet ebenfalls anonymen S3-Zugriff auf GLO-30 an, hat seine Quelle aber seit dem 23.07.2024 von Sinergise auf direkten ESA-Download (DGED-2023_1-Version) umgestellt ([OpenTopography](https://portal.opentopography.org/raster?opentopoID=OTSDEM.032021.4326.3)).
- **BRouter bringt sein eigenes SRTM-Imprinting bereits mit**: Der PosUnifier-Schritt der Map-Creation-Pipeline liest SRTM-Kacheln (hole-filled v4.1 von `srtm.csi.cgiar.org`, "ArcInfo ASCII"-Format oder `.hgt`) und schreibt Höhenwerte direkt in die `.rd5`-Ways ([BRouter Elevation-Doku](https://brouter.de/brouter/elevation.html), [build_segments.md](https://github.com/abrensch/brouter/blob/master/docs/developers/build_segments.md)). Das deckt den Routing-Graph-Teil ab, ohne zusätzliches Tool.
- Für die Vektorkachel-Seite (z. B. Hangschattierung/Contours im Kartenbild) sind **`pyhgtmap`** (aktiv gepflegter Fork von `phyghtmap`, [Repo](https://github.com/agrenott/pyhgtmap)) und **GDAL** (`gdaldem hillshade`, `gdal_contour`) die Standardwerkzeuge, um DEM-Kacheln in Höhenlinien/Hangschattierungs-Raster für Planetiler/Tilemaker umzuwandeln. `phyghtmap` selbst gilt laut eigenem Fork-Hinweis als "doesn't seem to be maintained anymore" ([pyhgtmap README](https://github.com/agrenott/pyhgtmap/blob/master/README.md)).
- `srtm.py` (`tkrajina/srtm.py`, [Repo](https://github.com/tkrajina/srtm.py)) ist ein einfacher Python-Parser für punktuelle Höhenabfragen (z. B. GPS-Track-Anreicherung), aber kein Batch-Tool für flächendeckendes Way-Tagging.

**Urteil**: Copernicus DEM GLO-30 über den anonymen S3-Zugriff beziehen (schnell, robust, in CI scriptbar), BRouters eigenen SRTM/PosUnifier-Mechanismus für den Routing-Graphen weiterverwenden (bereits vorhanden, kein Neubau nötig) und GDAL/`pyhgtmap` nur bei Bedarf für kartografische Hangschattierung in der Vektorkachel-Pipeline ergänzen.

---

## F) H3 (Uber Hexagonraster)

- Offizielle Java-Bindings: `uber/h3-java` ([Repo](https://github.com/uber/h3-java)), Apache-2.0, `pushed_at: 2026-08-19` (aktiv gepflegt).
- Für Android gibt es ein eigenes Artefakt `com.uber:h3-android`, aktuellste bekannte Version **4.4.0** als AAR ([Maven Central](https://central.sonatype.com/artifact/com.uber/h3-android)), daneben die generische Java-Variante `com.uber:h3` (bis 4.5.0) ([mvnrepository](https://mvnrepository.com/artifact/com.uber/h3)).
- H3 ist in C geschrieben; die Java/Android-Bindings sind JNI-Wrapper um die native Bibliothek (Build erfordert JDK, Gradle, CMake, C-Compiler laut Repo-Doku) — das bedeutet zusätzliche native `.so`-Dateien pro ABI im fertigen AAR, was die App-Größe leicht erhöht (konkrete Byte-Zahl **unsicher**, keine belastbare Quelle gefunden).
- Reifegrad: Sehr hoch — H3 wird produktiv von Uber selbst und zahlreichen Geo-Projekten genutzt, die Android-Bindings sind kein Nischenprojekt.

**Urteil**: `h3-android` einbinden, wenn das optionale Hotspot-Feature tatsächlich umgesetzt wird. Reifegrad und Lizenz sind unproblematisch; einziger Kostenpunkt ist der native-Library-Anteil an der APK-Größe, der aber im Vergleich zu den Kartendaten selbst (mehrere hundert MB) vernachlässigbar ist.

---

## G) Sprachausgabe/Bluetooth

Es gibt **kein fertiges, einbindbares OSS-Modul** speziell für "Bluetooth-Aufwachlatenz bei Navigationsansagen" — das Thema wird in bestehenden Apps nur punktuell und meist unzureichend gelöst:

- **OsmAnd** hat dokumentierte, bis heute offene Probleme mit abgeschnittenen/zu leisen Bluetooth-Ansagen (mehrere offene Issues: [#16204](https://github.com/osmandapp/OsmAnd/issues/16204), [#19552](https://github.com/osmandapp/OsmAnd/issues/19552), [#3197](https://github.com/osmandapp/Osmand/issues/3197), Übersichts-Issue ["Audio Focus Overhaul"](https://github.com/osmandapp/OsmAnd/issues/12715)) sowie einen "Bluetooth delay compensation"-Schalter im Entwickler-Plugin.
- Im OsmAnd-Feature-Request [#2590](https://github.com/osmandapp/Osmand/issues/2590) wird explizit auf den Konkurrenten **Sygic** verwiesen: Sygic spielt vor jeder Ansage eine **vordefinierte Stille-Präambel ("preamble of a predefined length of silent output")** ab, damit die Nachricht während des Bluetooth-Reconnects nicht abgeschnitten wird — das ist exakt das in der Aufgabenstellung skizzierte "Preroll-Chime"-Muster, aber nur als Beschreibung, nicht als übernehmbarer Code.
- Ein alternatives, umsetzbares Muster liefert die Android-App **`danielgjackson/speaker`** ([Repo](https://github.com/danielgjackson/speaker), MIT): Sie hält die Bluetooth-A2DP-Verbindung durch kontinuierliches, kaum hörbares Hintergrundrauschen wach, um das bekannte Problem zu vermeiden, dass manche BT-Lautsprecher/Headsets den Anfang des ersten Tons nach einer Stille-Pause verschlucken. Das ist eine andere Strategie als ein Preroll-Chime (dauerhaft vs. punktuell), aber MIT-lizenziert und als Code tatsächlich einsehbar.
- **Android-API-Fakten** (nicht projektspezifisch, aber grundlegend für die Eigenimplementierung): `AudioManager` unterscheidet A2DP (Musikqualität) und SCO/HFP (Telefonie-Codec, für Freisprech-Mikrofon-Pfad) als getrennte Bluetooth-Profile ([Google Oboe Wiki: Bluetooth Audio](https://github.com/google/oboe/wiki/TechNote_BluetoothAudio)); `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` ist laut offizieller Doku genau für Ansagen wie Navigationsanweisungen vorgesehen (Musik wird leiser statt gestoppt).
- Für Turn-by-Turn-Referenzarchitekturen (nicht Bluetooth-spezifisch) sind **OsmAnd** und **Organic Maps** die einschlägigen aktiven OSS-Navigations-Apps ([osmandapp/OsmAnd](https://github.com/osmandapp/OsmAnd), [organicmaps/organicmaps](https://github.com/organicmaps/organicmaps)) — beide quelloffen genug, um die jeweiligen Audio-/TTS-Module als Lernvorlage zu lesen, auch wenn kein sauber isoliertes "Ducking+Preroll"-Modul zum Herauslösen existiert.

**Urteil**: Kein OSS-Baustein zum Einbinden — die Kernidee (Preroll-Silence/Chime + Audio-Ducking statt SCO-Telefonie-Simulation) ist durch die Sygic-Beschreibung und OsmAnds offene Issues gut belegt und muss selbst implementiert werden. Das Ducking-API (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) ist Standard-Android und benötigt keine Bibliothek.

---

## H) MapLibre Navigation SDK für Android

`maplibre/maplibre-navigation-android` ([Repo](https://github.com/maplibre/maplibre-navigation-android), [README](https://github.com/maplibre/maplibre-navigation-android/blob/main/README.md)) ist ein MIT-lizenzierter Fork der alten Mapbox-Navigation-SDK v0.19 (entstanden, weil Mapbox seine Navigation-SDK proprietär gemacht und Telemetrie eingebaut hat). Es deckt ab: Turn-by-Turn-UI, Voice-Guidance, Routen-Simulation, Rerouting-Logik. Aktivität hoch (`pushed_at: 2026-09-08`), aber Version ist **5.0.0-pre13** (Pre-Release) und das Projekt wird laut README aktuell von einer reinen Android-Library auf **Kotlin Multiplatform** umgebaut — architektonisch im Umbruch.

Entscheidend für unseren Fall: Die SDK ist konzeptionell auf die **Mapbox-Directions-API bzw. kompatible REST-Endpunkte** (OSRM, GraphHopper-`/navigate`) zugeschnitten — sie konfiguriert einen "Direction-Server", von dem sie eine Route inkl. Turn-Instruktionen als JSON abruft. Ein Beleg für direkte Einbettung eines rein lokalen, offline arbeitenden Routing-Prozesses (wie unser vendorted BRouter) wurde nicht gefunden; die Doku spricht durchgehend von HTTP-Backends. Das als Referenz genannte Beispiel `PimpinPumpkin/Vela` (MapLibre + Jetpack Compose, GPLv3, sehr aktiv, [Repo](https://github.com/PimpinPumpkin/Vela)) beschreibt sein Routing selbst als "runs on open OSRM/GraphHopper" — das deutet eher auf einen (ggf. selbst gehosteten) Server-Call als auf eine echte On-Device-Einbettung hin; **unsicher**, da die Vela-Doku das nicht eindeutig klärt.

**Urteil**: Nicht einbinden. Die SDK passt architektonisch nicht zu einem Setup, in dem die Route komplett lokal aus einem vorprozessierten Graphen berechnet wird (BRouter/GraphHopper on-device) — sie erwartet einen Directions-Server bzw. eine kompatible REST-Antwort, keine native Java/Kotlin-Routingklasse. Zusätzlich ist der Pre-Release-Status (5.0.0-pre13) und der laufende KMP-Umbau ein Stabilitätsrisiko. Sinnvoll ist es trotzdem, sich UI-Bausteine (Turn-Icons, Voice-Instruction-Modelle, Maneuver-Typen) als Inspiration anzusehen, ohne die Bibliothek als Abhängigkeit zu übernehmen.

---

## Größte Risiken

1. **Der zentrale technische Kern der Architektur — ein synthetischer `curve_score`-Tag in BRouters `.rd5`-Format via "Pseudo Tags" — ist nur in einer Randnotiz der offiziellen Doku erwähnt und nirgends im Detail vorgeführt.** Sollte sich diese Pseudo-Tag-Mechanik als nicht praktikabel erweisen (z. B. weil sie nur für interne BRouter-Zwecke gedacht ist), fehlt die tragende Säule von Punkt A, und es müsste entweder eine eigene Lookup-Erweiterung tief im BRouter-Quellcode gebaut oder doch auf einen Fork/eine andere Engine ausgewichen werden ([build_segments.md](https://github.com/abrensch/brouter/blob/master/docs/developers/build_segments.md)).
2. **Plattenspeicher auf dem kostenlosen GitHub-Actions-Runner** (nur 14 GB garantiert frei, [Discussion #9329](https://github.com/actions/runner-images/discussions/9329)) ist der eigentliche Engpass, nicht RAM — bei gleichzeitiger Verarbeitung von OSM-PBF, DEM-Kacheln, `.rd5`-Zwischendateien und Vektorkacheln in einem Job/Runner kann das knapp werden, besonders wenn später mehrere Bundesländer oder ganz Deutschland in einem Lauf erzeugt werden sollen.
3. **MapLibre Native Androids PMTiles- und 3D-Funktionalität ist noch jung** (PMTiles seit Anfang 2025, 3D-Terrain laut Newsletter Ende 2025/Anfang 2026 noch in aktiver Entwicklung, [Discussion #326](https://github.com/maplibre/maplibre/discussions/326)) — Breaking Changes oder unerwartete Lücken (z. B. fehlender Offline-Pack-Support für PMTiles-Quellen) sind bei einem so jungen Feature-Set wahrscheinlicher als bei etablierter Software wie dem bisherigen Mapsforge-Rendering.

## Was wir selbst bauen müssen

- **Die komplette `curve_score`-Berechnung**: Kein Projekt liefert fertig einen kombinierten Score aus Winkeländerung, S-Kurven-Wechsel, Steigung (aus DEM) und Umgebungspolygonen (Wald/Wasser) inkl. Abwertung für Ortsdurchfahrten-Zickzack, 90°-Abbieger und schlechten Belag. `adamfranco/curvature` und GraphHoppers `curvature`-EncodedValue liefern nur Bausteine/Ideen (Abschnitt B).
- **Die Pipeline, die diesen Score als Pseudo-Tag in BRouters `.rd5`-Format injiziert**, und das `.brf`-Profil, das ihn als Priority-Faktor liest (Abschnitt A) — dokumentiert existiert dafür nichts Fertiges.
- **H3-Hexagon-Aggregation der Kurven-Hotspots**: Kein Tool kombiniert H3 direkt mit einem Kurven-Score; das Zusammenführen (welche Ways fallen in welches Hexagon, Score-Aggregation) ist Eigenentwicklung.
- **Audio-Ducking + Preroll-Chime + Bluetooth-SCO/A2DP-Handling** für die Sprachausgabe: kein fertiges OSS-Modul, nur Verhaltensbeschreibungen (Sygic) und ein artverwandtes, aber anders gelagertes Muster (`danielgjackson/speaker`) als Vorlage (Abschnitt G).
- **Turn-by-Turn-UI/Ansagelogik auf Jetpack Compose, gespeist aus lokal berechneten BRouter-Routen**: Die MapLibre-Navigation-SDK passt nicht zum Offline-On-Device-Modell (Abschnitt H), muss also selbst gebaut werden (Maneuver-Erkennung aus der BRouter-Route, Sprachtext-Generierung, zeitbasierte statt distanzbasierte Trigger wie in `1.Doku/AI_README.md` bereits skizziert).
- **Die GitHub-Actions-Orchestrierung selbst**: Ein Workflow, der OSM-Download (Geofabrik), DEM-Download (Copernicus S3), BRouter-Map-Creation, Planetiler-Kachel-Erzeugung und optional H3-Aggregation robust, plattenplatzsparend und reproduzierbar in einer Pipeline verkettet, existiert in dieser Kombination nirgends als Vorlage und muss komplett neu geschrieben werden.
