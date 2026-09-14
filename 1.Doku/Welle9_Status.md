# Welle 9 — Fahrbericht September 2026

Auftrag: die Punkte aus einem echten Probelauf abarbeiten, getrennt nach
**Funktion** und **Design**. Dieses Dokument sagt, was geändert wurde, warum die
Zahlen so gewählt sind, und was nur auf einem echten Gerät geprüft werden kann.

Zwei Dinge vorweg, weil sie alles andere betreffen:

* **Kein Emulator im Workspace.** Design-Entscheidungen wurden vorher in
  `1.Doku/design/mockup/index.html` gebaut und angesehen (echte Farb- und
  Abstands-Tokens, 1 dp = 1 px). Die Datei bleibt liegen und ist der richtige Ort
  für die nächste Layout-Idee.
* **CI baute vorher kein Android.** Der Verifier schließt `ui/`, `service/`,
  `voice/` und `di/` bewusst aus, der APK-Job war in `bd91125` entfernt worden —
  eine Compose-Änderung, die nicht kompiliert, wäre also grün durchgelaufen, und
  die rund 300 App-Unit-Tests liefen nirgends. `.github/workflows/android.yml`
  hat jetzt einen zweiten Job, der die App kompiliert und diese Tests laufen
  lässt (kein `assembleDebug` — das Paket zu bauen fängt keine Fehler).

---

## Funktion

### 1. Stufenloser Zoom (`domain/CameraController.kt`)

**Befund:** „an ein, zwei Stellen zu dicht, man kann effektiv nicht viel sehen."

Der Zoom rastete auf ganze Stufen mit Hysterese an den Bandkanten. Jetzt ein
fortlaufender `Double`, zwischen Stützstellen interpoliert und pro Fix
nachgeführt; die ganze Kurve ist rund eine Stufe weiter herausgezogen.

| km/h | vorher | jetzt |
|---|---|---|
| 0 | 18 | 16,6 |
| 30 | 17 | 16,0 |
| 50 | 16 | 15,2 |
| 70 | 15 | 14,5 |
| 100 | 14 | 13,8 |
| 130 | 13 | 13,2 |

Die Hysterese ist ersatzlos weg: Bandkanten, die man verbergen müsste, gibt es
nicht mehr. MapLibre nimmt gebrochene Zoomwerte nativ — der `Int` war die ganze
Zeit eine Selbstbeschränkung.

### 2. Ansagen früher (`domain/guidance/AnnouncementTiming.kt`)

**Befund:** „kommen manchmal zu spät, und bei höherem Tempo müssten sie viel
früher kommen."

Zwei Ursachen, die das reine „N Sekunden vor dem Manöver" übersieht:

1. **Die Worte brauchen Zeit.** Ein Helm-Headset öffnet seine Audiospur in bis zu
   1,5 s (siehe `Sprachausgabe.md` §3), der Satz läuft nochmal 2–3 s. Eine Stufe
   nominell 3 s vorher ist damit *fertig*, wenn die Kreuzung schon da ist.
   `SPEECH_LEAD_SECONDS` bezahlt das auf jeder Stufe.
2. **Schnell fahren braucht überproportional mehr Vorlauf**, nicht proportional.
   Reaktion plus Bremsweg mit Aufrichten aus der Schräglage skaliert nicht
   linear. Jede Stufe wird deshalb mit `speedStretch` gedehnt — voll auf der
   Vorankündigung, fast gar nicht auf dem letzten Hinweis, der nah an der
   Kreuzung bleiben muss, um „jetzt" zu heißen.

| Tempo | früh | Bestätigung | letzter Hinweis |
|---|---|---|---|
| 50 km/h | ~236 m | — | ~56 m |
| 100 km/h | ~640 m | ~260 m | ~115 m |
| 130 km/h | ~950 m | ~360 m | ~150 m |

(vorher 420 / 196 / 84 m bei 100 km/h). Zusätzlich hat jede Stufe eine
Mindestdistanz, damit die Ansage im Stop-and-go nicht auf 45 m zusammenfällt.

### 3. Verkehrsdaten Bundes-/Landesstraßen (Mobilithek)

**Befund:** Autobahn-Daten gibt es, der Rest fehlt.

Der schlüssellose Dienst der Autobahn GmbH kennt nur Autobahnen. Die Mobilithek
(nationaler Zugangspunkt des BMDV) veröffentlicht den Rest, verlangt aber ein
Konto — also trägt der Fahrer seinen eigenen Token ein:

* **Einstellungen → Verkehrslage (Mobilithek)**: Token und (optional) die
  Download-Adresse des Abos. Speichern löst sofort einen Abruf aus, statt bis zu
  30 Minuten auf das Cache-Fenster zu warten.
* `MobilithekTrafficSource` bleibt ohne Token vollständig inaktiv — der
  Autobahn-Dienst läuft unverändert weiter.
* `Datex2Parser` liest DATEX II **nach lokalem Elementnamen**, nicht nach
  Schema-Pfad. Grund: das Schema ist riesig und jeder Veröffentlicher nutzt eine
  andere Teilmenge; ein Parser, der an einen Pfad gebunden ist, liefert beim
  nächsten Anbieter still null Meldungen. Erkannt werden `situationRecord`, der
  `xsi:type`, alle `latitude`/`longitude`-Paare darunter (ein Paar = Punkt,
  mehrere = gesperrte Strecke) und `overallStartTime`/`overallEndTime`.
* `CompositeTrafficSource` führt beide Quellen zusammen und überlebt den Ausfall
  einer davon; erst wenn **alle** fehlschlagen, propagiert der Fehler — sonst
  würde `TrafficRepository` seinen Offline-Cache mit einer leeren Liste
  überschreiben.

**Nicht geprüft:** ohne echtes Mobilithek-Abo konnte kein realer Feed abgerufen
werden. Der Parser ist gegen zwei unterschiedlich geformte DATEX-II-Fixtures
getestet, die Netzwerkseite nicht.

### 4. Querformat (`ui/theme/WindowSize.kt`)

Drehen war im Manifest längst erlaubt (`fullUser`, `configChanges` gesetzt) — die
Layouts waren es nicht. Ein Telefon im Querformat ist rund 400 dp hoch; die
Manöverkarte allein ist 144 dp, das Planungs-Sheet durfte 460 dp hoch werden.
`WindowShape` beantwortet an einer Stelle „womit arbeite ich", die Manöverkarte
und ihre Distanz gehen auf einem niedrigen Fenster eine Größe herunter, und beide
Sheets bekommen ihre Obergrenze aus der Fensterhöhe. Hochformat ist unverändert.

### 5. Autobahn meiden

**Befund:** eine vorgeschlagene Rundtour lief zu einem Drittel über die Autobahn.

`avoid_motorways` war schon an — aber mit Faktor 50 gegenüber einer Landstraße
ist eine Autobahn neben einem 60-km-Umweg immer noch billig. Jetzt:

* Autobahn **600×**, Auffahrt 150×, plus **20 000 Einstiegskosten** (entspricht
  20 km Landstraße). Das tötet den kurzen „acht Kilometer A2 mitnehmen"-Hüpfer,
  den die reine Streckenkosten-Rechnung auf einer langen Route noch rechtfertigt.
* Bewusst **nicht** gesperrt (≥ 10 000 wäre BRouters „unpassierbar"): ein
  gesuchtes Ziel oder einer der geometrischen Rundtour-Zwischenpunkte kann auf
  einen Autobahn-Knoten einrasten, und ein hartes Verbot würde daraus „keine
  Route gefunden" machen, gegen das der Fahrer nichts tun kann.
* Kraftfahrstraße härter, aber weit darunter — in Deutschland sind viele
  gewöhnliche Bundesstraßen als `trunk` getaggt.
* Kosten allein reichen gegen den eingerasteten Zwischenpunkt nicht, deshalb
  misst `Route.motorwayMeters` jetzt den Autobahn-Anteil (aus den Routing-Kacheln
  gelesen, wie die Tempolimits). Eine Rundtour über 12 % Autobahn wird verworfen
  und mit verschobenen Punkten neu versucht. Der Anteil steht außerdem im
  Sheet — „148 km · Ankunft 16:24 · 0 km Autobahn".

### 6. Blitzer

**Befund:** „da wurde nie etwas angezeigt oder angesagt."

Erwartbar: die Funktion ist **Opt-in und standardmäßig aus** (StVO §23 Abs. 1c,
siehe `Blitzer.md`), und sie braucht Blitzerdaten für die Region. Ohne beides
passiert nichts. Unabhängig davon waren die Ansagen zu dünn:

* vorher **eine** Ansage pro Annäherung, bei der Distanz, in der die Kamera
  zufällig zuerst gesehen wurde;
* jetzt **eine pro Stufe** — grob 1000 / 500 / 250 m bei Landstraßentempo,
  wieder in Sekunden definiert und mit der Geschwindigkeit skaliert
  (`domain/cameras/CameraWarningTiming.kt`);
* Text vorher „Achtung, Blitzer, 70" (weder Entfernung noch Bezug der 70), jetzt
  **„Blitzer in 500 Metern, erlaubt 70"**;
* zwei Stufen zwischen zwei Fixes übersprungen → nur die nähere wird gesagt.

### 7. Kaltstart

**Befund:** bis zu fünf Sekunden, bis man den eigenen Standort sieht.

Ein kalter GNSS-Fix dauert wirklich so lange, daran kann keine App etwas ändern —
aber es gab drei billigere Antworten, und keine wurde benutzt:

1. `LocationProvider.lastKnown()` war **toter Code**. Wird jetzt sofort benutzt.
2. Die zuletzt zentrierte Position steht in den Settings (höchstens alle 30 s
   geschrieben) und ist die zweite Wahl.
3. Erst danach der bisherige Weg (erster Eintrag des Offline-Index).

Zusätzlich wartet der erste Verkehrsdaten-Abruf sechs Sekunden, statt ~330
HTTP-Requests in genau der Sekunde loszuschicken, in der die erste Frame
gezeichnet und der GPS-Fix angefragt wird.

---

## Design

### 8. Planungs-Sheet

Vorher eine einzige Spalte mit allem: Hinweistext, Demo-Link, Stopp-Liste als
nackte Zeilen mit je drei Glyphen-Knöpfen, Profil-Chips, Kurven-Slider und zwei
Schaltern — einer davon („Rundtour") gar keine Option, sondern die Entscheidung,
*welche Art* Route das ist, ganz unten versteckt.

Jetzt:

* **Modus als Segmented Control** direkt unter dem Peek: „Zum Ziel" / „Rundtour".
* **Stopps als Kacheln** (`ui/components/StopTile.kt`): Griff links, nummerierter
  Pip, ein ✕ rechts. Mit dem Finger verschiebbar — langer Druck, dann ziehen
  (`ReorderableStopColumn`). Der lange Druck ist kein Detail: das Sheet zieht
  selbst vertikal, und nur ein langer Druck beansprucht die Geste für die Liste.
* **„+ Stopp hinzufügen"** als eigene Kachel im Umriss.
* **Routenoptionen** in einem eigenen Bildschirm (`RouteOptionsScreen`): Fahrstil,
  Kurvenhunger, Meiden (Autobahn), Suche. Im Sheet bleibt eine Zeile mit
  Zusammenfassung.
* **Demo-Fahrt-Knopf entfernt** — ein Entwicklungswerkzeug auf dem kritischen
  Pfad des Fahrers. Die Demo-Mechanik in `NavigationController` bleibt für Tests.

### 9. Fahr-HUD

* **Manöverkarte statt Leiste**: abgerundet (28 dp) wie die Pillen darunter,
  Distanz 64 sp statt 56 (über die Typo-Tokens, die der Readout vorher mit
  fest eingetragenen 56/26 sp umging), Pfeil 112 dp.
* **✕ oben rechts** beendet die Navigation. Vorher lag das als roter Knopf im
  Klappmenü neben Stummschalten und Neuberechnen — die riskanteste Aktion im
  Griffbereich des Daumens.
* **Das Klappmenü ist jetzt die Route**: aktuelle Position → Stopps → Ziel, je
  mit Entfernung und Ankunft, plus „Stopp hinzufügen". Die Arithmetik dahinter
  ist `domain/RideItinerary.kt` und ist getestet — ein Wegpunkt ist kein
  Routenpunkt (BRouter rastet ihn auf seinen eigenen Knoten), und ein Faktor-2-
  Fehler darin fällt monatelang niemandem auf.
* **Status-Pille statt Leiste**: Die Vollbreiten-Leiste war Teil des Layouts, also
  sprang bei jeder Neuberechnung, jedem Blitzer und jedem Verlassen der Route das
  gesamte HUD um 44 dp nach unten und wieder hoch. Die Pille ist ein Overlay:
  klein, mittig, verschiebt nichts. Immer nur **eine** — zwei Pillen würden
  anfangen, sich gegenseitig zu schieben.
* **Der rote Vollbild-Blitzeralarm ist weg.** Die Karte in genau dem Moment
  zuzudecken, in dem gebremst wird, war das Schlimmste, was er tun konnte.

### 10. Kreisverkehr

Das statische Icon zeigte einen allgemeinen Ring, die Ausfahrtnummer stand als
16-sp-Text daneben — die eigentliche Anweisung war das Kleinste auf der Leiste.
`RoundaboutIcon` zeichnet stattdessen: offener Ring (Lücke unten = Einfahrt),
Pfeilspitze oben, **Nummer in der Mitte**. Alles skaliert über `size`, also dient
derselbe Code dem 112-dp-Pfeil und der 56-dp-Vorschau.

### 11. Sheet-Geste

Nach unten wischen mitten im Inhalt scrollte erst den Inhalt nach oben und
übergab dann den Rest-Schwung ans Sheet — eine Geste, zwei Wirkungen. Das Sheet
folgt jetzt nur noch einem Zug nach unten, der **am oberen Ende des Inhalts**
beginnt.

### 12. Karte entzerrt

Jede Verkehrsmeldung wurde mit demselben roten „gesperrt"-Ring gezeichnet, mit
abgeschalteter Überlappungsprüfung, ab Zoom 8. Ein echter Abruf sind ~4100
Meldungen, davon ~3200 gewöhnliche Baustellen — die 650 echten Sperrungen waren
darin unsichtbar. Jetzt drei Ebenen:

| Art | Icon | ab Zoom |
|---|---|---|
| Sperrung | Ring | 8 |
| Gefahr/Unfall | Warndreieck | 11 |
| Baustelle | Leitkegel | 12 |

Dazu `iconAllowOverlap(false)` — das ist, was den Teppich tatsächlich ausdünnt:
MapLibre lässt ein Symbol weg, das mit einem bereits platzierten kollidiert.
Sperrungen werden zuerst hinzugefügt und gewinnen deshalb diese Kollisionen.

### 13. Flüssige Puck-Bewegung

Der Punkt sprang pro Fix. Die GPS-Rate ändert sich nicht, also wird die Bewegung
dazwischen gerechnet (`domain/PositionInterpolator.kt`): Koppelnavigation aus
Geschwindigkeit und Kurs, plus eine gedeckelte Korrektur (1,35× der eigenen
Geschwindigkeit), damit der Vorhersagefehler als Gleiten statt als Schritt
ankommt. Ab 40 m Fehler wird weiterhin gesprungen — nach einem Tunnel oder einer
Neuberechnung würde Gleiten den Puck quer durch eine halbe Stadt ziehen. Die
Karte wird dafür pro Frame gezeichnet, aber nur während der Fahrt.

### 14. Radien und Tiefe

Vierzehn Stellen mit eigenen Radien (10, 12, 14, 16, 18, 22 dp) laufen jetzt über
die `Radius`-Skala. Das ist der unscheinbarste Punkt auf dieser Liste und
vermutlich der, der am meisten zum „wirkt noch nicht fertig" beigetragen hat.
Dazu haben die über der Karte schwebenden Platten eine weiche Elevation bekommen
— zusätzlich zum 1-dp-Rand, nicht statt ihm: `Design_System.md` Regel 2 gilt
weiter, ein Schatten ist in praller Sonne unsichtbar und darf nichts tragen.

---

## Was noch offen ist

* **Alles Optische ist ungetestet auf einem Gerät.** Kompiliert und unit-getestet
  ja; wie 64 sp und 112 dp auf einem echten Lenker bei Sonne wirken, nicht.
* **Mobilithek-Feed nie real abgerufen** (kein Abo) — siehe oben.
* **Drag & Drop der Stopps** nutzt eine feste Kachelhöhe. Solange alle Kacheln
  `StopTile` sind, stimmt das; eine Kachel mit zwei Zeilen Untertitel würde die
  Trefferzonen verschieben.
* **`WindowShape` ist bewusst grob** (Breite vs. Höhe, eine Höhenschwelle). Für
  ein Tablet oder ein faltbares Gerät bräuchte es ein echtes zweispaltiges
  Layout, kein kompakteres einspaltiges.
* **Der Demo-Modus ist aus der Oberfläche verschwunden**, lebt aber weiter im
  `NavigationController`. Entweder bekommt er irgendwann einen Platz in den
  Einstellungen, oder er sollte ganz raus.
