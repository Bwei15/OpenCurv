# RD5-Pipeline — Risiko-Spike: eigener Tag von OSM bis ins BRouter-Profil

**ERGEBNIS: ANNAHME BESTÄTIGT.**
**Ein frei erfundener OSM-Tag (`opencurv:curve`) lässt sich über die reguläre
BRouter-Kachelerzeugung in eine `.rd5` transportieren und in einem `.brf`-Profil
als Kostenfaktor auslesen. Zwei sonst identische Wege werden allein wegen ihres
Tag-Wertes unterschiedlich teuer, und der Router wählt messbar den jeweils
billigeren. Der Beweis wurde sowohl mit dem Upstream-BRouter als auch mit dem im
Repo einvendorten `brouter/`-Modul (dem Code, der auf dem Handy läuft) geführt.
Der undokumentierte Pseudo-Tag-Weg wurde NICHT gebraucht.**

**Und die praktisch wichtigste Zusatzfrage ist ebenfalls positiv beantwortet:
Ein feinstufiger, NUMERISCHER Score ist möglich — nicht nur Gleichheitsvergleiche.
Dafür muss der Tag in `lookups.dat` mit dem Wildcard-Wert `*` deklariert werden.
Das funktioniert mit dem einvendorten Code unverändert. Die Alternative
(diskrete Werteliste + numerischer Zugriff) funktioniert nur mit Upstream-Code
und würde einen kleinen Patch am einvendorten Modul erfordern — siehe §6.**

Datum: 2026-09-10 · BRouter Upstream `2f4bcb8` (1.7.11-beta) · Java 17 (Homebrew openjdk@17)

---

## 1. Der reproduzierbare Weg in Kurzform

Alles liegt in `tools/rd5build/`. Kein manueller Schritt.

```bash
# Schritt 1-6 komplett, inkl. Klonen und Bauen von Upstream-BRouter:
tools/rd5build/run_spike.sh enum     # diskrete Werte 0..15
tools/rd5build/run_spike.sh num      # Wildcard '*', numerisch  <-- empfohlen

# Grenzen ausloten (setzt einen run_spike.sh-Lauf mit gleichem WORK voraus):
tools/rd5build/run_limits.sh
```

Steuerung über Umgebungsvariablen: `RD5_SPIKE_WORK` (Arbeitsverzeichnis,
Default `$TMPDIR/opencurv-rd5-spike`), `RD5_SPIKE_UPSTREAM`, `RD5_SPIKE_BROUTER_REF`.
Im Repository entsteht dabei nichts außer den ohnehin ignorierten Gradle-Build-Ordnern.

**Verifiziert:** `run_spike.sh` wurde einmal komplett aus dem Nichts gestartet
(leeres Arbeitsverzeichnis, frischer Klon, frischer Gradle-Build) und lieferte
Byte für Byte dieselben Zahlen wie unten in §4. Exit-Code 0, keine manuellen
Eingriffe.

Die sechs Schritte im Einzelnen:

| # | Schritt | Womit |
|---|---------|-------|
| 1 | Upstream-BRouter klonen und Fat-Jar bauen (enthält `btools.mapcreator`) | `git clone` + `./gradlew :brouter-server:fatJar` |
| 2 | Winzige OSM-Eingabe erzeugen (6 Knoten, 2 Wege, 327 Byte `.pbf`) | `make_testbench_pbf.py` |
| 3 | `lookups.dat` um `opencurv:curve` erweitern, Minor-Version +1 | `patch_lookups.py` |
| 4 | Kacheln bauen: `OsmFastCutter` → `PosUnifier` → `WayLinker` | Upstream-Jar |
| 5 | Profilvarianten aus `profiles/spike_base.brf` erzeugen | `sed` in `run_spike.sh` |
| 6 | Routen und messen, gegen Upstream **und** gegen `brouter/` aus dem Repo | `RouteProbe.java` |

### Werkzeuge — was nachinstalliert werden musste

**Nichts.** `osmium`, `osmosis`, `osmconvert` und `protoc` sind auf dieser
Maschine nicht vorhanden; sie werden auch nicht gebraucht. `make_testbench_pbf.py`
schreibt das Protobuf der `.osm.pbf` von Hand (rund 100 Zeilen, nur `struct` aus
der Standardbibliothek) und nutzt aus, dass BRouters `BPbfBlobDecoder`
unkomprimierte („raw") Blobs akzeptiert
(`BPbfBlobDecoder.java:62-64`, Upstream). Damit ist die ganze Kette
GitHub-Actions-tauglich ohne apt/brew-Abhängigkeiten außer JDK 17 und Python 3.

**Stolperfalle beim PBF-Schreiben (hat mich eine Stunde gekostet, deshalb hier
explizit):** In `osmformat.proto` ist `Node.id` ein **`sint64` (Zigzag)**,
während `Way.id` ein gewöhnliches `int64` ist. Wer die Knoten-ID als schlichten
Varint schreibt, bekommt im Mapcreator verdrehte IDs (1→−1, 2→1, 11→−6 …), der
`NodeFilter` wirft dann fast alle Knoten weg, und in der `.rd5` bleibt ein
einzelner Link übrig. Der Fehler ist völlig stumm — die Kachel wird gebaut, sie
ist nur fast leer. Siehe Kommentar in `make_testbench_pbf.py`.

**Zweite Stolperfalle:** `-DuseDenseMaps=true` (wie im Produktionsskript
`misc/scripts/mapcreation/process_pbf_planet.sh`) stürzt bei sehr kleinen
Eingaben ab:
`IndexOutOfBoundsException: Index -1 out of bounds for length 0` in
`DenseLongMap.put`. Für kleine Extrakte muss `-DuseDenseMaps=false`
(`TinyDenseLongMap`) gesetzt werden. Für ganz Deutschland ist `true` wieder
richtig. Ebenso nötig: `-DavoidMapPolling=true`, sonst wartet `OsmParser`
zwei Minuten auf angeblich noch wachsende Eingabedaten
(`OsmParser.java:42-71`, Upstream).

---

## 2. Wie `lookups.dat` aufgebaut ist (Schritt-1-Antworten mit Quellcode-Belegen)

Alle Zeilenangaben beziehen sich auf das **einvendorte** Modul unter
`brouter/src/main/java/btools/`, sofern nicht „Upstream" dabeisteht.

### 2.1 Dateiformat

`lookups.dat` ist reiner Text, gelesen von `BExpressionMetaData.readMetaData()`
(`expressions/BExpressionMetaData.java:33-70`). Vier Steuerzeilen:

| Zeile | Bedeutung | Beleg |
|---|---|---|
| `---lookupversion:11` | Major-Version | `BExpressionMetaData.java:18,48-50` |
| `---minorversion:2` | Minor-Version | `BExpressionMetaData.java:19,52-54` |
| `---context:way` / `:node` / `:global` | schaltet auf den jeweiligen `BExpressionContext` um | `BExpressionMetaData.java:17,44-46` |
| `---minappversion:` | Mindest-App-Version, nur informativ | `BExpressionMetaData.java:21,56-58` |

Alles andere sind Datenzeilen der Form

```
<tagname>;<häufigkeit> <hauptwert> [<alias> <alias> ...]
```

geparst in `BExpressionContext.parseMetaLine()`
(`expressions/BExpressionContext.java:275-292`). Der Teil ab `;` ist reine
Statistik und wird weggeworfen (`:279-281`). Der erste Token nach dem Namen ist
der Hauptwert, alle weiteren sind Aliase (`:293`, `BExpressionLookupValue.matches()`).

**Die Reihenfolge ist das Format.** Jeder Tagname bekommt beim ersten Auftreten
die nächste freie laufende Nummer (`addLookupValue`, `:536-537`), jeder Wert die
nächste freie Nummer innerhalb seines Tags (`:667-681`). Index 0 ist immer
„nicht gesetzt", Index 1 immer `unknown` — beide werden automatisch angelegt
(`:539-540`). In der `.rd5` stehen nur diese Nummern, nie die Strings.

Zwei Einträge sind fest verdrahtet und werden aus der Datei ignoriert:
`reversedirection` (way) und `nodeaccessgranted` (node), `:283-288`.

### 2.2 Wie Tag-Werte binär kodiert werden

`BExpressionContext.encode(int[] ld)` (`:132-173`):

- Bit 0 (`reversedirection`) wird übersprungen (`:142`).
- Für jeden Tag mit Wert ≠ 0 wird erst der Abstand zum vorherigen gesetzten Tag
  als Varbits geschrieben (`ctx.encodeVarBits(skippedTags + 1)`, `:148`), dann
  der Wertindex, rotiert, damit die häufigen kleinen Codes kurz bleiben:
  `int dd = d < 2 ? 7 : (d < 9 ? d - 2 : d - 1);` (`:154`).
- Abschluss mit einer 0 (`:157`).
- Danach wird sofort wieder dekodiert und verglichen; eine Abweichung wirft
  (`:166-172`). Die Kodierung ist also selbstprüfend.

Varbits ist ein Präfixcode („poor man's Huffman", `util/BitCoderContext.java:80-95`):
Wert `v` kostet rund `2·log2(v+1)+1` Bits.

Es gibt **zwei Wertarten**:

1. **Diskret** — der Wert steht in `lookups.dat`, gespeichert wird sein Index (2, 3, 4 …).
2. **Numerisch** — der Tag ist in `lookups.dat` mit dem Sonderwert `*` deklariert.
   Dann wird ein unbekannter Wert als Zahl geparst und als
   `1000 + round(|wert| · 100)` abgelegt (`:655`). Die Erkennung läuft über
   `bFoundAsterix` (`:554`); `*` ist dabei **kein** Wildcard im Sinne von
   `matches()`, sondern ein Marker. Vor dem Parsen läuft eine
   Einheiten-Normalisierung (ft, in, cm, mph, km/h …, `:576-650`) — für einen
   reinen Zahlenwert stört sie nicht.

### 2.3 Was mit einem Tag passiert, der nicht in `lookups.dat` steht

Drei Fälle, alle definiert und alle harmlos:

| Fall | Verhalten | Beleg |
|---|---|---|
| Kachelerzeugung sieht einen Tag-**Namen**, den `lookups.dat` nicht kennt | wird stillschweigend verworfen; für ein extern übergebenes Array legt `addLookupValue` keinen neuen Namen an | `:528-534` (`if (lookupData2 != null) return`) |
| Kachelerzeugung sieht einen bekannten Namen mit unbekanntem **Wert**, ohne `*` | wird als Index 1 = `unknown` gespeichert | `:561` |
| Beim Dekodieren enthält die Kachel mehr Tags, als das Profil-`lookups.dat` kennt | Abbruch der Schleife, Rest bleibt 0 — Kommentar im Code: „higher minor version is o.k." | `:202` |
| Beim Dekodieren ist ein Wertindex größer als die Werteliste | wird auf 1 = `unknown` abgebildet, aber nur unterhalb von 1000 (numerische Werte bleiben unangetastet) | `:209` |
| Profil referenziert einen Tag/Wert, den sein `lookups.dat` nicht kennt | **harter Fehler beim Parsen des Profils** — `unknown lookup name` / `unknown lookup value` | `BExpression.java:192, 202` |

Die letzte Zeile ist der einzige unangenehme Fall und der Grund, warum
`lookups.dat` und `.brf` immer zusammen ausgeliefert werden müssen.

### 2.4 Was die Versionsnummern bedeuten — und was bei Abweichung passiert

`WayLinker` schreibt beide Nummern in den Kachelkopf: Die Major-Version steht in
den oberen 16 Bit jedes Index-Eintrags, die Minor-Version im Eintrag Nr. 1
(`WayLinker.java:553-561`, Upstream).

Beim Öffnen der `.rd5` prüft `PhysicalFile` **nur die Major-Version**
(`mapaccess/PhysicalFile.java:100-105`):

```java
if (i == 0 && lookupVersion != -1 && readVersion != lookupVersion) {
  throw new IOException("lookup version mismatch (old rd5?) lookups.dat=" ...);
}
```

Die Minor-Version wird gelesen und weitergereicht, aber nirgends verglichen
(`NodesCache.java:64-65, 363`). Das deckt sich mit dem Profile Developers Guide
(Upstream `docs/developers/profile_developers_guide.md`, Abschnitt
„Lookup-Table evolution"): Anhängen am Ende der Kontext-Sektion und Anhängen von
Werten am Ende der Werteliste sind minor-verträglich, alles andere braucht eine
Major-Erhöhung.

**Gemessen** (siehe §5.3): rd5 mit Tag gegen Profil-`lookups.dat` ohne Tag →
Route läuft, Tag ist unsichtbar. rd5 ohne Tag gegen Profil-`lookups.dat` mit Tag
→ Route läuft, Tag ist unbelegt. Major-Version verbogen → sauberer Abbruch mit
lesbarer Meldung.

### 2.5 Das einvendorte Modul enthält keinen Mapcreator

Bestätigt: `brouter/src/main/java/btools/` enthält nur `util`, `codec`,
`mapaccess`, `expressions`, `router`. Kein `btools.mapcreator`. Deshalb holt
`run_spike.sh` das Original-Repo ins Arbeitsverzeichnis (außerhalb des Projekts)
und baut daraus `brouter-<version>-all.jar`.

Die einvendorte `app/src/main/assets/profiles/lookups.dat` ist **byteweise
identisch** mit `misc/profiles2/lookups.dat` aus dem heutigen Upstream-Master
(Version 11.2). Die Java-Quellen sind es nicht — dazu §6.

---

## 3. Der Prüfstand

`make_testbench_pbf.py` erzeugt 6 Knoten und 2 Wege, spiegelsymmetrisch um
Breite 52,0° bei Länge 12,0° (gut im Inneren der 5×5-Kachel `E10_N50`):

```
        N1 --------- N2        Way 101, opencurv:curve = 15
       /               \
      A                 B      A = 12,000/52,000   B = 12,020/52,000
       \               /
        S1 --------- S2        Way 102, opencurv:curve = 0
```

Beide Wege tragen `highway=secondary`, `surface=asphalt`, `maxspeed=80`.
Der einzige Unterschied ist `opencurv:curve`. Die Datei ist 327 Byte groß,
ein Durchlauf der kompletten Kachelerzeugung dauert rund 4 Sekunden.

Restunterschied, ehrlich benannt: Durch die Spiegelung an einem Breitenkreis ist
die Nordroute wegen des kleineren cos(Breite) **4 m kürzer** (1504 m gegen
1508 m, 0,27 %). Das neutrale Profil bevorzugt deshalb Nord. Genau deshalb ist
der Beweis als **Umkehrung** angelegt: Das Profil, das den niedrigen Score
belohnt, nimmt die Südroute *obwohl* sie länger ist und *obwohl* die neutrale
Referenz Nord wählt.

---

## 4. Der Beweis — tatsächliche Programmausgaben

`lookups.dat`-Erweiterung (Variante `enum`, ans Ende der `---context:way`-Sektion,
Minor-Version 2 → 3):

```
---lookupversion:11
---minorversion:3
...
# OpenCurv: vorberechneter Kurven-/Fahrspass-Score, 0..15
opencurv:curve;0000000001 0
opencurv:curve;0000000001 1
...
opencurv:curve;0000000001 15
```

Die vier Profilvarianten unterscheiden sich in genau einer Zeile von
`tools/rd5build/profiles/spike_base.brf`:

```
spike_neutral      assign curvecost = 1.0
spike_prefer_high  assign curvecost = switch opencurv:curve=15  1.0  9.0
spike_prefer_low   assign curvecost = switch opencurv:curve=0   1.0  9.0
spike_numeric      assign curvecost = max 0.2 sub 1.0 multiply 0.05 v:opencurv:curve
```

### 4.1 Upstream-BRouter, Variante `enum`

```
== routing with UPSTREAM brouter (Start 12.0000,52.0000  Ziel 12.0200,52.0000)
PROFILE=spike_neutral     BRANCH=NORD DIST=1504m COST=1504 LATRANGE=[52.0 .. 52.002]
   dist=1504m costfactor=1.0  tags: highway=secondary
PROFILE=spike_prefer_high BRANCH=NORD DIST=1504m COST=1504 LATRANGE=[52.0 .. 52.002]
   dist=1504m costfactor=1.0  tags: highway=secondary opencurv:curve=15
PROFILE=spike_prefer_low  BRANCH=SUED DIST=1508m COST=1526 LATRANGE=[51.998 .. 52.0]
   dist=0m    costfactor=9.0  tags: reversedirection=yes highway=secondary opencurv:curve=15
   dist=1508m costfactor=1.0  tags: highway=secondary opencurv:curve=0
PROFILE=spike_numeric     BRANCH=NORD DIST=1504m COST=376  LATRANGE=[52.0 .. 52.002]
   dist=1504m costfactor=0.25 tags: highway=secondary opencurv:curve=15
```

Drei Dinge stehen damit fest:

1. **Der Tag ist in der `.rd5`.** Die Zeile `tags: ... opencurv:curve=15` kommt
   aus `BExpressionContextWay.getKeyValueDescription()`, angewandt auf die
   Beschreibungs-Bytes, die BRouter beim Routen **aus der Binärkachel** gelesen
   hat. Es wird an dieser Stelle keine OSM-Eingabe mehr angefasst.
2. **Der Tag steuert die Kosten.** `costfactor` springt zwischen 1,0 und 9,0,
   je nachdem, welchen Wert das Profil belohnt.
3. **Der Router folgt.** `spike_prefer_low` nimmt die Südroute, obwohl sie
   4 m länger ist und obwohl das neutrale Referenzprofil Nord wählt. Die
   Umkehrung ist der eigentliche Beweis.

### 4.2 Einvendortes `brouter/`-Modul — derselbe Lauf, dieselben Kacheln

```
== routing with VENDORED OpenCurv brouter (das ist der Code auf dem Handy)
PROFILE=spike_neutral     BRANCH=NORD DIST=1504m COST=1504
   dist=1504m costfactor=1.0  tags: highway=secondary
PROFILE=spike_prefer_high BRANCH=NORD DIST=1504m COST=1504
   dist=1504m costfactor=1.0  tags: highway=secondary opencurv:curve=15
PROFILE=spike_prefer_low  BRANCH=SUED DIST=1508m COST=1526
   dist=1508m costfactor=1.0  tags: highway=secondary opencurv:curve=0
PROFILE=spike_numeric     BRANCH=NORD DIST=1504m COST=2243
   dist=1504m costfactor=1.492 tags: highway=secondary opencurv:curve=15
```

Die drei Gleichheitsvergleiche verhalten sich identisch zum Upstream.
**Nur `spike_numeric` weicht ab** — 1,492 statt 0,25. Das ist kein Zufall,
sondern exakt die in §6 beschriebene Abweichung im einvendorten Quellcode.

### 4.3 Variante `num` (Wildcard `*`) — hier stimmen beide überein

`lookups.dat` enthält dann nur eine Zeile: `opencurv:curve;0000000001 *`.
Die Profile arbeiten durchgehend numerisch:

```
spike_prefer_high  assign curvecost = switch greater v:opencurv:curve 7.5  1.0  9.0
spike_prefer_low   assign curvecost = switch lesser  v:opencurv:curve 7.5  1.0  9.0
spike_numeric      assign curvecost = max 0.2 sub 1.0 multiply 0.05 v:opencurv:curve
```

Ergebnis, **wortgleich für Upstream und einvendortes Modul**:

```
PROFILE=spike_neutral     BRANCH=NORD DIST=1504m COST=1504
   dist=1504m costfactor=1.0  tags: highway=secondary
PROFILE=spike_prefer_high BRANCH=NORD DIST=1504m COST=1504
   dist=1504m costfactor=1.0  tags: highway=secondary opencurv:curve=15.0
PROFILE=spike_prefer_low  BRANCH=SUED DIST=1508m COST=1526
   dist=1508m costfactor=1.0  tags: highway=secondary opencurv:curve=0.0
PROFILE=spike_numeric     BRANCH=NORD DIST=1504m COST=376
   dist=1504m costfactor=0.25 tags: highway=secondary opencurv:curve=15.0
```

`costfactor=0.25` ist exakt `1,0 − 0,05 · 15`. Der Wert 15 ist also als **Zahl**
im Profil angekommen, nicht als Kategorie. Auch `greater` und `lesser` gegen
einen Schwellwert funktionieren. Damit ist die Kernfrage aus Schritt 4 der
Aufgabe beantwortet: **feinstufiger numerischer Score ist möglich.**

---

## 5. Grenzen

### 5.1 Wie viele Werte darf der Tag haben?

Gemessen an einem Gitternetz aus 40×40 Knoten = 3120 Links, jeweils mit und ohne
Tag, gleiche Geometrie (`run_limits.sh`, `GRID=40`):

| Variante | rd5 [Byte] | Δ [Byte] | Δ pro Link |
|---|---:|---:|---:|
| ohne Tag (Referenz) | 12 627 | 0 | 0,000 |
| 1 Wert | 12 628 | 1 | 0,000 |
| 2 Werte | 13 024 | 397 | 0,127 |
| 4 Werte | 13 425 | 798 | 0,256 |
| 8 Werte | 13 839 | 1 212 | 0,388 |
| **16 Werte (0..15)** | **14 279** | **1 652** | **0,529** |
| 32 Werte | 14 771 | 2 144 | 0,687 |
| 64 Werte | 15 374 | 2 747 | 0,880 |
| 256 Werte | 17 507 | 4 880 | 1,564 |
| Wildcard `*`, Werte 0..15 | 14 310 | 1 683 | 0,539 |
| Wildcard `*`, Werte 0,00..0,15 | 14 305 | 1 678 | 0,538 |

Die Codec-Statistik von `WayLinker` erklärt das exakt
(`MicroCache2.java:324, 436`, Upstream):

```
ohne Tag:            wayDescIdx count=3120 bits=0        wayTagDictionary bits=28
16 Werte:            wayDescIdx count=3120 bits=12480    wayTagDictionary bits=765
256 Werte:           wayDescIdx count=3120 bits=24960    wayTagDictionary bits=14109
```

12480 / 3120 = **genau 4 Bit pro Link** = log2(16).
24960 / 3120 = **genau 8 Bit pro Link** = log2(256).

**Die Regel lautet also: jede Verdopplung der Wertanzahl kostet exakt 1 Bit pro
Link, dazu einmal pro Microcache das Wörterbuch.** Nicht der Wert selbst wird
gespeichert, sondern ein Index in ein Wörterbuch der vorkommenden
Tag-Kombinationen. Deshalb ist die numerische Wildcard-Variante bei gleicher
Stufenzahl **genauso teuer** wie die diskrete (0,539 gegen 0,529 B/Link) — die
teurere Varbits-Kodierung der Zahl schlägt nur im Wörterbuch durch, nicht pro Link.

Harte Obergrenze: **500 Werte pro Tag**, danach werden weitere still ignoriert
(`BExpressionContext.java:665-670`).

**Empfehlung: 16 Stufen (4 Bit/Link).** 32 Stufen wären noch vertretbar, 256
sind Verschwendung — die Routing-Entscheidung wird von einem Score, der feiner
als ~5 % auflöst, nicht mehr sichtbar beeinflusst.

### 5.2 Wie stark wachsen die `.rd5`-Dateien?

Auf meinem Prüfstand +13,1 % (12 627 → 14 279 Byte) bei 16 Stufen. **Diese Zahl
ist aber der Worst Case und nicht hochrechenbar**, weil mein Gitter ohne den Tag
nur eine einzige Wegbeschreibung kennt (`wayDescIdx bits=0`). Echte Daten haben
schon tausende verschiedene Beschreibungen, dort ist `wayDescIdx` bereits
11–14 Bit breit.

Die belastbare Hochrechnung ist die Bit-Formel:

> **Obergrenze: +log2(Stufenzahl) Bit pro Link**, also +4 Bit = 0,5 Byte pro Link
> bei 16 Stufen. In der Praxis weniger, weil der Kurven-Score mit `highway`,
> `maxspeed` und `surface` korreliert und der Wörterbuch-Coder das ausnutzt.

Für eine Deutschland-Kachel mit z. B. 20 Mio. Links wären das rund **10 MB
Zuwachs** — bei Segmentdateien, die heute in der Größenordnung mehrerer hundert
MB liegen, ein einstelliger Prozentsatz. **Nicht gemessen** — siehe §7.

### 5.3 Versionsvertäglichkeit (gemessen)

```
--- rd5 minor=3 (mit Tag)  <->  Profil-lookups minor=2 (ohne Tag)
PROFILE=spike_neutral BRANCH=NORD DIST=1504m COST=1504
   dist=1504m costfactor=1.0  tags: highway=secondary          <- Tag einfach unsichtbar

--- rd5 ohne Tag  <->  Profil-lookups mit Tag, Profil liest den Tag
PROFILE=spike_prefer_low BRANCH=NORD DIST=1504m COST=13536
   dist=1504m costfactor=9.0  tags: highway=secondary          <- Tag unbelegt, else-Zweig

--- Major-Version 12 im Profil vs. 11 in der rd5
PROFILE=spike_prefer_low ERROR=lookup version mismatch (old rd5?) lookups.dat=12 E10_N50.rd5=11
```

Beide Minor-Richtungen sind unkritisch. Alte App mit neuen Kacheln und neue App
mit alten Kacheln funktionieren beide. Das ist für den Rollout wichtig: Wir
können die Kacheln vor der App ausliefern oder umgekehrt.

### 5.4 Zwei Fallstricke für das Profildesign

**(a) „nicht gesetzt" ist nicht dasselbe wie „Wert 0".**
Ein Weg ohne `opencurv:curve` bekommt Index 0. Der Ausdruck `opencurv:curve=0`
matcht auf Index 2 (den Wert „0"). Im Test oben landet ein untaggter Weg deshalb
im else-Zweig mit Faktor 9,0 — nicht bei 1,0. Wer „kein Score bekannt" abfangen
will, muss `opencurv:curve=` (leerer Wert) prüfen.

**(b) `v:tag` liefert NaN, wenn der Tag fehlt — und das killt das ganze Netz.**
`getLookupValue` gibt `Float.NaN` zurück, sobald der Wertindex 0 ist
(`BExpressionContext.java:255-258`). NaN pflanzt sich durch die Kostenformel
fort, der Kostenfaktor wird NaN, und BRouter hält damit sämtliche Wege für
unbefahrbar. Gemessen:

```
### Wege OHNE Tag, Profil liest v:opencurv:curve ungeschuetzt
PROFILE=spike_numeric ERROR=from-position not mapped in existing datafile
```

Mit Schutz ist alles in Ordnung:

```
assign curvecost = switch not opencurv:curve=
                     max 0.2 sub 1.0 multiply 0.05 v:opencurv:curve
                     1.0
```

```
### Wege OHNE Tag   -> costfactor=1.0  tags: highway=secondary
### Wege MIT  Tag   -> costfactor=0.25 tags: highway=secondary opencurv:curve=15.0
```

**Das ist die wichtigste operative Konsequenz aus diesem Spike: Jedes
OpenCurv-Profil, das `v:opencurv:curve` benutzt, MUSS die Existenz des Tags
vorher prüfen.** Sonst ist bei der ersten Kachel ohne Score die App tot.

---

## 6. Der eine Punkt, an dem das einvendorte Modul vom Upstream abweicht

Der einvendorte Code ist gegenüber dem heutigen Upstream älter. Für unsere Frage
zählt genau eine Stelle: `BExpressionContext.getLookupValue(int key)`.

**Einvendort** (`brouter/src/main/java/btools/expressions/BExpressionContext.java:255-261`):

```java
public float getLookupValue(int key) {
  float res = 0f;
  int val = lookupData[key];
  if (val == 0) return Float.NaN;
  res = (val - 1000) / 100f;
  return res;
}
```

**Upstream** (`brouter-expressions/.../BExpressionContext.java:259-275`):

```java
public float getLookupValue(int key) {
  float res = 0f;
  int val = lookupData[key];
  if (val == 0) return Float.NaN;
  if (val < 900) {
    try {
      BExpressionLookupValue[] va = lookupValues.get(key);
      String sval = va[val].toString();
      res = Float.parseFloat(sval);          // <-- diskreten Wert als Zahl lesen
    } catch (NumberFormatException e) {
      res = 0f;
    }
  } else {
    res = (val - 1000) / 100f;
  }
  return res;
}
```

Das erklärt die 1,492 aus §4.2 exakt: Der Wert „15" hat den Index 17, und
`(17 − 1000) / 100 = −9,83`, also `1,0 − 0,05 · (−9,83) = 1,4915`.

Daraus folgen zwei gangbare Wege — beide funktionieren, die Wahl ist eine
Architekturentscheidung:

| Weg | `lookups.dat` | Profil | Kosten | Einvendortes Modul |
|---|---|---|---|---|
| **A (empfohlen)** | `opencurv:curve;0 *` | `v:opencurv:curve` numerisch, plus Existenzprüfung | 0,54 B/Link bei 16 Stufen | **läuft unverändert** |
| B | `opencurv:curve;0 0` … `15` | `v:opencurv:curve` numerisch | 0,53 B/Link | braucht den obigen 10-Zeilen-Patch |
| C | `opencurv:curve;0 0` … `15` | nur `opencurv:curve=7` Gleichheitsvergleiche | 0,53 B/Link | läuft unverändert, aber nur grobe Klassen |

**Weg A** ist der einzige, der einen feinstufigen Score liefert, ohne den
einvendorten BRouter anzufassen. Zusätzlicher Vorteil: Die Stufenzahl steht
nicht mehr in `lookups.dat`, wir können die Auflösung später ändern, ohne die
Lookup-Tabelle anzufassen. Zusätzlicher Nachteil: Es gibt keine
Wertebereichsprüfung mehr — ein kaputter Vorberechner kann `opencurv:curve=999`
schreiben, und niemand merkt es. Eine Plausibilitätsprüfung gehört also in den
Vorberechnungsschritt.

Wenn ohnehin geplant ist, `brouter/` auf den aktuellen Upstream nachzuziehen,
werden A und B gleichwertig, und B hätte den Vorteil der eingebauten
Wertebereichsprüfung.

---

## 7. Was ich NICHT geprüft habe

Ehrliche Liste. Alles hier ist offen und kann das Bild noch verändern:

1. **Kein echter Geofabrik-Extrakt.** Der gesamte Beweis läuft auf 6 selbst
   erzeugten Knoten und einem 40×40-Gitter. Dass die Kachelerzeugung mit
   `opencurv:curve` auch auf echten Deutschland-Daten durchläuft (Laufzeit,
   Speicher, `-DuseDenseMaps=true`, Grenzknoten zwischen Kacheln,
   Turn-Restrictions, Relationen), ist **nicht** gezeigt. Das ist der größte
   verbliebene Unsicherheitsfaktor.
2. **Die Größenhochrechnung auf Deutschland ist eine Rechnung, keine Messung.**
   Die Bit-Formel (+log2(N) Bit/Link) ist am Prüfstand exakt belegt, die
   Link-Anzahl einer echten Deutschland-Kachel habe ich nicht bestimmt.
3. **Nicht auf einem Android-Gerät ausprobiert.** Ich habe gegen das
   einvendorte Modul als JVM-Jar geroutet, nicht in der App und nicht auf ARM.
   Der `.rd5`-Lesepfad ist derselbe, aber die Asset-Verwaltung der App
   (`lookups.dat` liegt unter `app/src/main/assets/profiles/`) habe ich nicht
   angefasst und nicht getestet.
4. **Die Produktionsprofile der App wurden nicht verändert und nicht geprüft.**
   `motorcycle_curvy.brf`, `motorcycle_enduro.brf`, `motorcycle_fast.brf` und
   die dortige `lookups.dat` sind unberührt. Ob sie nach einer
   `lookups.dat`-Erweiterung noch parsen, ist wahrscheinlich (nur Anhängen),
   aber ungetestet.
5. **Der Pseudo-Tag-Weg wurde nicht angesehen.** War laut Auftrag nur
   Rückfallebene und wurde nicht gebraucht. `DatabasePseudoTagProvider` und
   `process_pbf_planet_production.sh` bleiben unerforscht.
6. **Keine Aussage zur Vorberechnung selbst.** Wie der Kurven-Score aus der
   Geometrie berechnet wird, wie er auf OSM-Wege abgebildet wird und wie diese
   Tags in die `.pbf` gelangen (Osmium-Filter? eigenes Werkzeug?), ist nicht
   Gegenstand dieses Spikes.
7. **Kein Test mit mehreren Zusatz-Tags gleichzeitig.** Falls später noch
   `opencurv:scenery`, `opencurv:surface_quality` o. Ä. dazukommen, addieren
   sich die Bits pro Link multiplikativ in der Zahl der Kombinationen — das ist
   plausibel, aber nicht gemessen.
8. **Turn-Restrictions und Relationen** kamen im Prüfstand nicht vor
   (`restrictions.dat` blieb leer, `relations.dat` 0 Byte).
9. **Nicht in GitHub Actions ausgeführt.** Die Skripte sind so gebaut, dass sie
   dort laufen sollten (nur JDK 17 + Python 3 + git), aber ein tatsächlicher
   CI-Lauf steht aus. Insbesondere die Laufzeit des Upstream-Gradle-Builds
   (hier ~90 s kalt) und der Bedarf an Cache sind ungetestet.

---

## 8. Dateien

| Datei | Zweck |
|---|---|
| `tools/rd5build/run_spike.sh` | Kompletter Beweislauf, Modus `enum` oder `num` |
| `tools/rd5build/run_limits.sh` | Größenmessung und Versionsvertäglichkeit |
| `tools/rd5build/make_testbench_pbf.py` | Erzeugt `.osm.pbf` (und `.osm`) ohne externe Werkzeuge; `--grid N` für das Messgitter |
| `tools/rd5build/patch_lookups.py` | Hängt `opencurv:curve` an `lookups.dat` an, erhöht die Minor-Version |
| `tools/rd5build/profiles/spike_base.brf` | Minimalprofil mit Platzhalter `@@CURVECOST@@` |
| `tools/rd5build/RouteProbe.java` | Routet und gibt Zweig, Kostenfaktor und die **aus der `.rd5` dekodierten** Tags aus; läuft gegen beide Jars |

Die Produktionsprofile unter `app/src/main/assets/profiles/` wurden **nicht**
angefasst.
