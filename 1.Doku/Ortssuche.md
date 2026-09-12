# Ortssuche mit Hausnummern (Adress-Index)

Stand: September 2026. Betroffene Module: `tools/pipeline/bin/build_places.py`,
`.github/workflows/opencurv-data.yml` (Schritt "Adress-Index bauen"),
`tools/pipeline/bin/make_catalog.py` (Katalog-Eintrag `"kind": "places"`),
`app/src/main/java/com/motoroute/data/search/Place.kt` (`PlaceQuery.normalise`,
die Referenzimplementierung der Normalisierung). Die App-Abfrageseite (Laden,
Cachen, Suchindex im Gerät) ist **nicht** Teil dieser Stufe — das baut Welle
6.4b auf der hier beschriebenen Datei auf.

---

## 1. Warum eine eigene Datei

`PlaceSearchRepository` (siehe `1.Doku`-Kommentar in `Place.kt`) indiziert
heute nur Orte (Stadt/Ort/Dorf/Vorort) aus den Mapsforge-Kacheln und liest
**keine** Straßen: "Streets are not indexed at all […] indexing every street
in Niedersachsen would cost more than it is worth." Für "Hauptstraße 12,
Hannover" reicht das nicht — dafür braucht es Straßennamen samt Hausnummern,
und die stecken nicht kompakt genug in den Routing-/Kartenkacheln, um sie zur
Laufzeit brauchbar zu durchsuchen.

Deshalb erzeugt die Cloud-Pipeline pro Region eine eigene,
fertig-normalisierte SQLite-Datei: `<region-id>.places.sqlite` — ausgeliefert
über denselben Release-Katalog wie `.rd5`, `.pmtiles` und
`<region-id>.cameras.tsv` (`"kind": "places"` in `catalog.json`).

---

## 2. Schema

```sql
-- Praefix-Suche: WHERE norm >= ? AND norm < ? (Bereichsscan ueber den
-- norm-Index) statt LIKE 'x%' -- eindeutig und unabhaengig von
-- PRAGMA case_sensitive_like. Obergrenze: Praefix + '￿' anhaengen.

CREATE TABLE places (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,             -- Originalschreibweise, z.B. "Göttingen"
    norm TEXT NOT NULL,             -- PlaceQuery.normalise(name), z.B. "goettingen"
    kind TEXT NOT NULL,             -- city|town|village|hamlet|suburb|neighbourhood|locality
    lat INTEGER NOT NULL,           -- WGS84 * 1e6
    lon INTEGER NOT NULL,           -- WGS84 * 1e6
    population INTEGER,             -- OSM population-Tag, wenn vorhanden
    parent_id INTEGER REFERENCES places(id)  -- naechstgelegene city/town
);
CREATE INDEX idx_places_norm ON places(norm);

CREATE TABLE streets (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    norm TEXT NOT NULL,
    place_id INTEGER REFERENCES places(id),  -- der Ort, dem die Strasse zugeordnet wurde
    lat INTEGER NOT NULL,           -- Mittelpunkt aller Way-Nodes der Strasse
    lon INTEGER NOT NULL
);
CREATE INDEX idx_streets_norm ON streets(norm);
CREATE INDEX idx_streets_place ON streets(place_id);

CREATE TABLE addresses (
    street_id INTEGER NOT NULL REFERENCES streets(id),
    housenumber TEXT NOT NULL,      -- Originalschreibweise, z.B. "12a"
    hn_norm TEXT NOT NULL,          -- kleingeschrieben, ohne Leerzeichen: "12a"
    lat INTEGER NOT NULL,
    lon INTEGER NOT NULL,
    postcode TEXT
);
CREATE INDEX idx_addresses_street_hn ON addresses(street_id, hn_norm);

CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
-- schema=1, region=<region-id>, built=<ISO-8601 UTC>,
-- normalisation="Place.normalise v1"
```

Koordinaten liegen als `INTEGER` (Grad × 1e6) vor, nicht als `REAL` — das
spart auf einer Datei mit hunderttausenden Zeilen spürbar Platz und macht
Vergleiche exakt reproduzierbar. Rückrechnung: `lat / 1e6`.

**Eine Zeile je (Straßenname, Ort).** Eine Straße, die aus mehreren
OSM-Ways besteht (die Regel, nicht die Ausnahme — Kreuzungen brechen Ways
auf), wird zu genau einer `streets`-Zeile zusammengefasst;
`lat`/`lon` ist der Mittelpunkt **aller** Way-Knoten aller Segmente dieses
Namens in diesem Ort (nicht nur der Segment-Mittelpunkte).

---

## 3. Normalisierung — die eine Regel, die auf beiden Seiten identisch sein muss

`norm` in dieser Datei und der Suchtext, den die App aus einer Nutzereingabe
macht, müssen **exakt** derselben Funktion entspringen — sonst liefert der
Bereichsscan `WHERE norm >= ? AND norm < ?` stillschweigend keine oder falsche
Treffer, statt sichtbar zu brechen.

Referenz: `PlaceQuery.normalise()` in
`app/src/main/java/com/motoroute/data/search/Place.kt` (Kotlin). Portiert
1:1 nach `tools/pipeline/bin/build_places.py::normalise()` (Python). Beide
Funktionen tragen den Kommentar "muss identisch bleiben mit …" mit
Verweis auf die jeweils andere Seite.

Regel: klein schreiben; `ä→ae`, `ö→oe`, `ü→ue`, `ß→ss`; gängige
Akzente auf den nächsten ASCII-Buchstaben falten (`é→e`, `ñ→n`, `ø→o`, …);
alles, was danach kein Buchstabe/keine Ziffer ist, wird zu einem Leerzeichen;
mehrfache Leerzeichen werden zu einem zusammengefasst, Rand getrimmt.

Beide Seiten halten dieselben 5 Beispiele fest, damit ein Drift durch einen
fehlschlagenden Test auffällt statt durch leise falsche Suchtreffer:

| Eingabe | `norm` |
|---|---|
| `Göttingen` | `goettingen` |
| `Münster` | `muenster` |
| `Straße` | `strasse` |
| `Bad Münder am Deister` | `bad muender am deister` |
| `Sankt-Florian-Weg 12a` | `sankt florian weg 12a` |

- Python: `_PARITY_EXAMPLES` in `build_places.py`.
- Kotlin: `app/src/test/java/com/motoroute/PlaceNormaliseParityTest.kt`.

`hn_norm` ist eine separate, simplere Regel (nur für Hausnummern): klein
schreiben, Leerzeichen entfernen — `"12 A"` → `"12a"`.

---

## 4. Wie `build_places.py` die Datei baut

Zwei Lesedurchgänge über den `.osm.pbf`-Extrakt mit **pyosmium**, im selben
Zwei-Pass-Stil wie `build_cameras.py`:

1. **`RelationScan`** (nur `relation()`): findet `place=city|town`- und
   adresstragende **Relationen** und merkt sich pro Relation eine
   Mitglieds-Way-ID (bevorzugt `role=outer`), deren Zentroid Pass 2 braucht.
   Billig — Nodes und Ways laufen ohne Python-Callback durch, wie
   `DeviceCollector` in `build_cameras.py`.
2. **`MainCollector`** (`node()`+`way()`+`relation()` in einem Durchgang,
   `apply_file(..., locations=True, idx='flex_mem')`): liest Node-Koordinaten
   direkt und Way-Knoten-Koordinaten über pyosmiums Location-Index (gefüllt,
   während die Nodes vorbeiziehen — in der PBF-Dateiordnung stehen Nodes vor
   Ways vor Relationen, weshalb ein einziger Durchgang reicht). Way-Zentroide,
   die Pass 1 für Relationen markiert hat, werden zwischengespeichert; wenn im
   selben Durchgang später die zugehörige Relation drankommt, steht ihr
   Zentroid bereits bereit.

Danach reine Python-Nachbearbeitung (keine weiteren PBF-Lesedurchgänge):

- **Orte:** Node-Kandidaten (`place=city|town|village|hamlet|suburb|
  neighbourhood|locality`) gewinnen immer. Way-/Relations-Kandidaten gibt es
  nur für `city`/`town`, und nur, wenn **kein** gleichnamiger Node-Ort
  innerhalb von 5 km liegt (Dubletten-Vermeidung bei Städten, die sowohl
  einen Zentrums-Node als auch eine Verwaltungsrelation tragen).
- **`parent_id`:** die nächstgelegene `city`/`town` je Nicht-Stadt-Ort, per
  Gitter-Suche (0,1°-Zellen ≈ 11 km, ringweise erweitert) statt einem
  O(n)-Scan über alle Orte.
- **Straßen:** Way-Segmente mit gleichem `norm(name)` werden zu Straßen
  gruppiert. Der Ort einer Straße kommt von `addr:city` der Adressen, die
  laut `addr:street` an dieser Straße liegen und innerhalb von 5 km ihrer
  Segmente — sonst vom nächstgelegenen Ort (gleiche Gitter-Suche wie oben).
- **Adressen:** jedes `addr:street`+`addr:housenumber`-Objekt wird über
  `(norm(addr:street), aufgelöster Ort)` der passenden `streets`-Zeile
  zugeordnet; ohne Treffer wird die Adresse verworfen (siehe §6, "Grenzen").

**Speicher:** `idx='flex_mem'` hält alle Node-Koordinaten im RAM (schnell
genug für Bundesländer bis Niedersachsen-Größe, siehe Messung unten). Ein
größerer Extrakt (z. B. Bayern, deutlich mehr Nodes) müsste auf
`idx='sparse_file_array,<pfad>'` wechseln (Platte statt RAM) — das ist ein
reiner Parameterwechsel in `main()`, keine Strukturänderung.

**Bauparameter:** `journal_mode=OFF`, `synchronous=OFF` während des Aufbaus
(keine Transaktionssicherheit nötig — die Datei ist ein Wegwerf-Build-
Artefakt, kein Live-System), Batch-Inserts über `executemany`, am Ende
`VACUUM`.

Aufruf:

```
build_places.py <in.osm.pbf> <out.places.sqlite> [--region-id ID]
```

---

## 5. Empfohlene Abfragen (für Welle 6.4b)

Alle drei nutzen ausschließlich den `norm`-Bereichsscan, nie `LIKE`:

**a) Ort-Präfix** ("hann" → Hannover, Hannoversch Münden, …):

```sql
SELECT id, name, kind, lat, lon FROM places
WHERE norm >= ? AND norm < ? || X'ffff'
ORDER BY kind LIMIT 20;
-- Parameter 1 und 2: derselbe normalisierte Präfix
```

**b) Straße-Präfix innerhalb eines Orts** ("haupt" in Ort-ID 42):

```sql
SELECT id, name, lat, lon FROM streets
WHERE place_id = ?1 AND norm >= ?2 AND norm < ?2 || X'ffff'
ORDER BY name LIMIT 20;
```

**c) Adresse** (Straße + Hausnummer in einem Ort, `Kornstraße 12, Bremen`
als Beispiel unten):

```sql
SELECT s.name, a.housenumber, a.lat, a.lon, p.name
FROM addresses a
JOIN streets s ON s.id = a.street_id
JOIN places  p ON p.id = s.place_id
WHERE s.place_id = ?1 AND s.norm = ?2 AND a.hn_norm = ?3;
```

`?2`/`?3` sind `PlaceQuery.normalise(strasse)` bzw. die App-seitige
Entsprechung von `hn_norm` (klein schreiben, Leerzeichen entfernen).

Alle drei Abfragen sind Index-Scans (`idx_places_norm`,
`idx_streets_norm`/`idx_streets_place`, `idx_addresses_street_hn`) — kein
Full-Table-Scan, kein `LIKE`.

---

## 6. Gemessen: Bremen und Niedersachsen

Messrechner: Apple Silicon, lokales Python 3.14 + pyosmium 4.3.1 in
`tools/pipeline/.venv` (gitignored, `pip install osmium` — dieselbe
Abhängigkeit, die der Workflow-Schritt "Werkzeuge" schon installiert).
Spitzen-RSS mit `/usr/bin/time -l` (macOS).

| | Bremen (`bremen-latest.osm.pbf`) | Niedersachsen (`niedersachsen-latest.osm.pbf`) |
|---|---:|---:|
| PBF-Größe | 20 MB | 481 MB |
| Laufzeit gesamt | 12,0 s | **NI_SECONDS** |
| Spitzen-RSS | 271 MB | **NI_RSS** |
| Ausgabedatei | 6,8 MB | **NI_SIZE** |
| Orte (`places`) | 112 | **NI_PLACES** |
| Straßen (`streets`) | 5.469 | **NI_STREETS** |
| Adressen (`addresses`) | 146.439 | **NI_ADDRESSES** |
| Adressen ohne Straßen-Treffer verworfen | 4.657 (3,1 %) | **NI_UNMATCHED** |

Größenziel aus dem Auftrag (< 100 MB für Niedersachsen): **NI_SIZE_VERDICT**.

**Konkrete Adressabfrage, gegen die gebaute `de-hb.places.sqlite` getestet:**

```sql
SELECT s.name, a.housenumber, a.lat/1e6, a.lon/1e6, p.name
FROM addresses a JOIN streets s ON s.id = a.street_id
JOIN places p ON p.id = s.place_id
WHERE s.place_id = 1 AND s.norm = 'kornstrasse' AND a.hn_norm = '12';
```

Treffer: `Kornstraße | 12 | 53.067586 | 8.795968 | Bremen`.

---

## 7. Grenzen

- **Straßenzuordnung ist eine Heuristik.** Wo `addr:city` fehlt und zwei
  gleichnamige Straßen weniger als ~5 km auseinanderliegen (z. B. zwei
  Nachbarorte mit je einer "Dorfstraße"), kann eine Adresse dem falschen Ort
  zugeordnet werden. In der Praxis selten, weil die meisten
  Adress-Importe in Deutschland `addr:city` mitliefern (siehe Unmatched-Quote
  oben — die verworfenen Adressen sind größtenteils Objekte, deren
  `addr:street` keiner extrahierten Straße entspricht, z. B. Privatwege ohne
  `highway`-Tag oder Tippfehler in der Quelle, nicht falsch zugeordnete).
- **Relationen als Geometriequelle sind vereinfacht.** Für `place=city/town`-
  und adresstragende Relationen wird nur **ein** Mitglieds-Way (bevorzugt
  `role=outer`) zur Zentroid-Berechnung herangezogen, keine vollständige
  Multipolygon-Zusammensetzung. Für die knapp bemessenen Fälle, in denen kein
  Node dieselbe Information trägt, reicht das für eine Suchposition; für eine
  exakte Flächenmitte reicht es nicht.
- **Keine Fuzzy-Suche in der Datei selbst.** `norm` liefert exakte
  Präfix-Treffer; Tippfehlertoleranz (falls gewünscht) ist Sache der
  App-Suchschicht (6.4b), nicht dieser Datei.
- **Straßen ohne jede Adresse fehlen nicht**, aber ihr Ort ist dann immer der
  nächstgelegene (Gitter-Suche), nie über `addr:city` bestätigt.
- **Populationswerte** kommen unverändert aus OSM (`population`-Tag) und sind
  oft veraltet oder fehlen ganz — `NULL` ist der erwartete Normalfall, keine
  Fehlererkennung nötig.
