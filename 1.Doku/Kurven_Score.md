# Der Kurven-Score von OpenCurv

Stand: September 2026. Modul: `tools/curvescore/` (eigenständiger Gradle-Build, **kein** Teil des
Android-Builds). Prüfstand: `tools/testarena/` (nur lesend benutzt).

> **Vertrag nach außen**
> Eingabe: OSM-Daten (Ways mit Geometrie und Tags, Knoten mit optionaler Höhe).
> Ausgabe je Way: eine ganze Zahl **0–15** (0 = meiden, 15 = Traumstrecke), dazu der rohe
> kontinuierliche Wert, die sieben Teilbewertungen, die vier Strafmultiplikatoren und eine
> Datenkonfidenz.
> Stufenzahl konfigurierbar (`--levels`, Vorgabe 16).

---

## 1. Recherche: was es schon gab, und warum es nicht reicht

Ausgangspunkt war `1.Doku/Research_Tech_Options.md`, Abschnitt B.

### `adamfranco/curvature` (GPL-3.0 — nur Ideengeber, kein Code übernommen)

Das Projekt bestimmt für jedes Dreier-Punktfenster einer Linie den **Umkreisradius** und wertet
Segmente nach Radiusklasse. Die Idee „Radius statt Winkel" ist richtig und wurde übernommen. Die
*Methode* wurde bewusst **nicht** übernommen, aus einem konkreten Grund:

Der Umkreisradius dreier abgetasteter Punkte ist eine rein lokale Größe. Er kann eine echte Kehre
nicht von einer rechtwinkligen Kreuzungsecke unterscheiden, wenn beide zufällig gleich dicht
abgetastet sind. Rechenbeispiel mit den Zahlen der Testarena: eine 90°-Ecke zwischen zwei 25 m
langen Geraden hat den Umkreisradius

```
R = (a·b·c) / (4·A) = (25 · 25 · 35,36) / (4 · 312,5) = 17,7 m
```

— praktisch derselbe Wert wie die Serpentine `R2_SERPENTINE` mit ihren echten 24-m-Kehren. Ein
Umkreis-Scorer hält `R5_GRID` (das Wohnviertel-Gitter, 717° Gesamt-Richtungsänderung) deshalb für
die kurvigste Strecke der Arena. Genau diese Falle sollen `E3` und `E7` fangen. Der hier gebaute
Scorer verwendet stattdessen die **Sehnen-Ablenkungs-Beziehung** plus ein explizites
Persistenzkriterium (Abschnitt 3.3).

Der zweite Grund: `curvature` summiert Segmentgewichte je Way. Ein OSM-Way ist aber eine willkürliche
Einheit (siehe Abschnitt 4).

### GraphHopper `curvature` = Luftlinie / Kantenlänge

Diese Metrik ist **für unseren Zweck zu grob**, und zwar aus vier unabhängigen Gründen:

1. **Sie ist nicht invariant gegen die Kantenlänge.** Zwei aufeinanderfolgende Gegenkurven bringen
   die Kante fast wieder auf die Luftlinie zurück: eine perfekte S-Kurven-Strecke, das Beste was
   eine Straße einem Motorradfahrer bieten kann, hat einen Umwegfaktor nahe 1,0 — also „gerade".
   Auf `R4_S_CURVES` (30 Kurven, R = 45 m) liefert Luftlinie/Länge 0,94; auf `R1_HIGHWAY`
   (kerzengerade) 1,00. Ein Unterschied von 6 % soll den Unterschied zwischen der besten und der
   langweiligsten Strecke der Arena tragen.
2. **Sie kennt keinen Radius.** Eine 180°-Wende über 500 m und eine 15-m-Kehre erzeugen denselben
   Umwegfaktor. Der fahrbare Radiusbereich (Abschnitt 3.4) lässt sich damit nicht ausdrücken.
3. **Sie unterscheidet Kurve und Ecke nicht.** `R5_GRID` hat einen exzellenten Umwegfaktor —
   ein Zickzack durch ein Wohnblockraster ist maximal umwegig.
4. **Sie hängt vom Kantenschnitt ab.** Dieselbe Straße, an einer Brücke in zwei Ways geteilt,
   bekommt zwei andere Werte.

Wo sie *gut genug* wäre: als grober Vorfilter, um offensichtlich gerade Kanten schnell auszusortieren,
bevor die teurere Analyse läuft. Dafür brauchen wir sie nicht — die teure Analyse ist ohnehin
schnell genug (Abschnitt 8).

### OsmAnd-Motorbike, Kurviger

`schmidi2/osmand-motorbike` (2016, keine Lizenzangabe) liefert die Abwertungsregeln konzeptionell —
gerade Straßen und niedrige Tempolimits abstrafen, Autobahnen aber nicht pauschal. Diese Haltung
findet sich hier im Straßencharakter-Term und im Ortslage-Malus wieder. Kurviger ist closed source.

**Ergebnis:** Eigenimplementierung, Ideen übernommen, kein Code.

---

## 2. Die Formel

```
  Score_roh = clamp01( Attraktivität × Strafe )

  Attraktivität = 0,50·Dichte      +  0,12·Kurvenanteil  +  0,12·Wechselschwünge
                + 0,05·Rhythmus    +  0,07·Steigung      +  0,08·Umgebung
                + 0,06·Straßenklasse

  Strafe        = P_Ecken · P_Ortslage · P_Belag · P_Unterbrechungen

  Stufe         = floor( Score_roh × 16 ),  gekappt auf 15
```

Alle sieben positiven Terme liegen in **[0, 1]**, die Gewichte summieren sich auf **1,0** — jedes
Gewicht liest sich damit direkt als „Anteil am Urteil". Alle vier Strafen liegen in **(0, 1]**.

### Warum additiv plus multiplikativ

Das ist die wichtigste strukturelle Entscheidung, und sie folgt direkt aus der Aufgabenstellung.

**Boni addieren sich**, weil sie voneinander unabhängige Gründe sind, eine Straße zu mögen: dass sie
kurvig ist, dass sie durch den Wald führt und dass sie bergauf geht, sind drei getrennte Argumente.

**Strafen multiplizieren**, weil sie *entwerten, was da ist*. Die Vorgabe „Zickzack-Kurven in
geschlossenen Ortschaften abwerten" ist genau ein Faktor und keine Subtraktion: eine kurvige Straße
mitten durch eine Wohnsiedlung muss den *größten Teil ihres Kurvenwerts* verlieren, während eine
gerade Straße durch dieselbe Siedlung nichts zu verlieren hat und so oder so bei null bleibt. Eine
Subtraktion würde beide gleich hart treffen und könnte den Score negativ machen.

### Die Terme im Überblick

| Term | Gewicht | Wertebereich | Was er misst |
|---|---|---|---|
| Dichte | 0,50 | 0…1 | qualitätsgewichtete Richtungsänderung je Meter |
| Kurvenanteil | 0,12 | 0…1 | Anteil der Strecke in Kurve, Anfahrt oder Ausfahrt |
| Wechselschwünge | 0,12 | 0…1 | wie oft die nächste Kurve andersherum geht, und wie eng verkettet |
| Rhythmus | 0,05 | 0…1 | Gleichmäßigkeit der Kurvenabstände |
| Steigung | 0,07 | 0…1 | mittleres Gefälle als Geländeindikator |
| Umgebung | 0,08 | 0…1 (0,5 neutral) | Wald/Wasser gegen Industrie/Wohngebiet |
| Straßenklasse | 0,06 | 0…1 | was für eine Straße es ist, unabhängig von Kurven |
| P_Ecken | ×  | 0…1 | Dichte stumpfer 90°-Abbieger |
| P_Ortslage | × | 0,25…1 | Anteil innerhalb bebauten Gebiets |
| P_Belag | × | 0,05…1 | Belag (profilabhängig) |
| P_Unterbrechungen | × | 0…1 | Ampeln, Kreisverkehre, Bahnübergänge, Tempo 30 |

---

## 3. Die Geometriestufe

### 3.1 Vorfilter: Douglas-Peucker mit 0,5 m

OSM-Geometrie ist ungleichmäßig abgetastet — vom Knoten alle 2 m (aus Luftbildern abgezeichnet) bis
alle 300 m. Wird daraus direkt eine Krümmung je Stützpunkt berechnet, teilt man einen verrauschten
Winkel durch eine winzige Sehne: **eine kerzengerade, aus Luftbildern abgezeichnete Straße mit
±25 cm Streuung wird zu einer durchgehenden Kette von 40-m-Kurven.** Das ist kein hypothetischer
Fall; der Test `dense tracing jitter does not become curve value` baut ihn nach.

Douglas-Peucker entfernt genau das und sonst nichts: ein Punkt bleibt nur, wenn er mehr als 0,5 m
von der Linie abweicht, die seine Nachbarn beschreiben.

**Warum 0,5 m?** Über die *Pfeilhöhe* der Kurven, die überleben müssen. Eine Sehne der Länge `c` auf
einem Kreis mit Radius `R` wölbt sich um `c²/(8R)`. Bei ε = 0,5 m bleibt also:

| Radius | Knotenabstand nach DP | Ablenkung je Knoten | Radius wird noch korrekt gemessen |
|---|---|---|---|
| 15 m (Kehre) | 7,7 m | 29° | ja |
| 24 m | 9,8 m | 23° | ja |
| 180 m | 26,8 m | 8,5° | ja |
| 900 m (Autobahn) | 60 m | 3,8° | ja |

Die Sehnen-Ablenkungs-Beziehung ist skaleninvariant, deshalb messen alle vier weiter richtig.
0,5 m entspricht außerdem etwa der Lagegenauigkeit von OSM-Straßengeometrie — der Filter wirft nur
weg, was unterhalb der Auflösung der Daten selbst liegt.

Danach eine Untergrenze von **4 m** für die Messbasis (DP darf zwei Punkte legitim einen Meter
auseinander lassen; eine Ablenkung durch eine Ein-Meter-Sehne zu teilen gibt Unsinn). Ein Punkt mit
≥ 40° Ablenkung wird nie verworfen — dort steckt die Information.

### 3.2 Radius aus Sehne und Ablenkung

Ein Kreis mit Radius `R`, abgetastet mit Sehne `c`, dreht an jedem Stützpunkt um
`δ = 2·arcsin(c / 2R)`. Umgestellt:

```
  R = c / (2·sin(δ/2))
```

Für einen Kreis ist das **exakt** und braucht keinen Umkreis dreier Punkte.

Wenn die zwei an einem Stützpunkt zusammenstoßenden Sehnen verschieden lang sind, ist die Ablenkung
die Summe ihrer *halben* Winkel, `δ = (c_ein + c_aus)/2R` — die richtige Messbasis ist also ihr
**Mittel**, aber nur für Sehnen, **die zur selben Biegung gehören**. Beide Abkürzungen sind falsch,
und zwar messbar falsch:

- Nimmt man immer das **Minimum** (scheinbar die vorsichtige Wahl), wird eine 400-m-Kurve mit der
  ungleichmäßigen Punktverteilung, die DP hinterlässt, als 267-m-Kurve gemeldet.
- Nimmt man immer das **Mittel**, wird eine echte 33-m-Kurve, die an eine 200-m-Gerade anschließt,
  zur 147-m-Kurve verschmiert.

Deshalb wird die Basis erst festgelegt, *nachdem* die Stützpunkte zu Biegungen gruppiert sind: eine
Sehne zählt nur, wenn der Stützpunkt an ihrem anderen Ende zur selben Biegung gehört. Beide Seiten →
Mittel; eine Seite → diese Seite; eine isolierte Einzelbiegung → das Minimum.

### 3.3 Kurve oder Ecke — das eigentliche Problem

Eine 90°-Kreuzungsecke und der Scheitel einer 15-m-Kehre erzeugen **dieselbe** Ein-Punkt-Geometrie,
wenn sie zufällig gleich dicht abgetastet sind. Kein lokales Dreipunktmaß kann sie trennen.

Was sie trennt, ist **Persistenz**: eine echte Kurve biegt über mehrere aufeinanderfolgende
Stützpunkte, weil eine nach einem Radius gebaute Straße mit mehr als einem Knoten gezeichnet werden
muss, um auf diesem Radius zu bleiben. Eine Kreuzungsecke ist ein einzelner, isolierter Sprung
zwischen zwei Geraden.

> **Regel:** Ein Stützpunkt ist eine **Ecke**, wenn er um mindestens **40°** ablenkt *und* keiner
> seiner beiden Nachbarn mindestens **35 %** dieser Ablenkung in dieselbe Richtung weiterträgt.
> Eine Ecke bekommt Radius 0, damit Qualität 0, und geht stattdessen in den Ecken-Malus.

Die Zahlen: ein gleichmäßig abgetasteter Bogen liefert im Inneren 100 % Nachbaranteil und dort, wo er
auf die Gerade trifft, 50 %; eine Kreuzungsecke liefert ~0 %. 35 % liegt dazwischen und lässt Raum
für ungleichmäßige Abtastung. Die 40°-Schwelle: eine nach einem Radius gebaute Straße kann bei den in
OSM üblichen 10–30 m Knotenabstand nicht an einem Knoten um 40° drehen — das hieße Radius unter 15 m
*und* 10 m Knotenabstand, also eine mit einem einzigen Punkt gezeichnete Kehre. Unter 40° ist die
Mehrdeutigkeit echt, und im Zweifel wird zugunsten der Kurve entschieden.

**Die Nachbarn direkt zu prüfen** (statt zu fragen, ob der Punkt in der Gruppierung allein
übriggeblieben ist) ist nicht kosmetisch: ein einzelner 2°-Digitalisierungsknick neben einer
rechtwinkligen Kreuzung würde sich sonst mit ihr verbinden und aus der Kreuzung eine schmeichelhafte
70-m-„Kurve" machen.

Zusätzlich bricht eine Biegung ab, wenn zwischen zwei ablenkenden Stützpunkten mehr als **70 m**
liegen. Nach Douglas-Peucker sind die kollinearen Punkte einer Geraden verschwunden; die einzige
Spur, die eine Zwischengerade hinterlässt, ist eine lange Sehne. Ohne diese Regel verschmelzen zwei
28°-Kurven im Abstand von 170 m zu einer 56°-Kurve. 70 m sind rund drei Sekunden bei Landstraßentempo
und länger als der Knotenabstand, den DP auf jeder Kurve bis ~900 m Radius übrig lässt.

**Der Radius einer Biegung ist der ihres engsten Stützpunkts** — nicht der Mittelwert und nicht
Bogenlänge/Winkel. Beide werden von den Übergangspunkten nach oben gezogen (ein Punkt am Übergang
Gerade→Bogen trägt nur einen halben Schritt der Drehung, meldet also etwa den doppelten Radius) und
blähen eine 15-m-Kehre auf 22 m auf. Da dieser Fehler *immer* eine Überschätzung ist, ignoriert das
Minimum diese Punkte von selbst. Es ist zugleich die semantisch richtige Antwort: was ein Fahrer mit
„eine 40er-Kurve" meint und was das Tempo bestimmt, das er mitnehmen kann, ist der engste Punkt —
und es ist genau die Größe, die die Testarena als `minRadiusM` veröffentlicht.

### 3.4 Radiusqualität `q_R(R)` — der fahrbare Bereich

```
  q_R(R) =  0,25 · R/8                                 für R < 8 m
            0,25 + 0,75 · (R−8)/32                     für  8 ≤ R < 40 m
            1,0                                        für 40 ≤ R ≤ 130 m
            (130/R)^1,5                                für R > 130 m
```

Jede Zahl mit ihrem Grund:

| Konstante | Wert | Begründung |
|---|---|---|
| untere Plateaugrenze | **40 m** | Bei angenehm-zügigen 0,4 g ist `v = √(a·R) = √(4·40) = 12,6 m/s = 45 km/h` — zweiter Gang, volle Schräglage, Reserve. Der engste Radius, der sich noch nach *Fahren* anfühlt und nicht nach Rangieren. |
| obere Plateaugrenze | **130 m** | Dieselben 0,4 g geben `√(4·130) = 22,8 m/s = 82 km/h` — die Obergrenze eines legalen Landstraßentempos. Darüber ist keine befriedigende Schräglage mehr erreichbar, ohne das Limit zu brechen. |
| Bodenwert | **8 m / 0,25** | Unter 8 m ist es keine Straßenkurve mehr, sondern eine Hotelzufahrt oder ein Feldwegabzweig. Nicht null, weil Kehren das Herz eines Alpenpasses sind — aber 8 m sind Lenkanschlagarbeit im Schritttempo. |
| Abfall-Exponent | **1,5** | Bei festen 80 km/h fällt die nötige Schräglage mit 1/R: 22° bei 130 m, 11° bei 260 m, 6° bei 520 m, 3° bei 900 m. Etwas steiler als 1/R, weil ein weiter Bogen den Fahrer *zusätzlich* nichts kostet: kein Bremsen, keine Linienwahl, kein Gangwechsel. Ergebnis: „doppelter Sweet-Spot-Radius ist ein Drittel wert, vierfacher ein Achtel, ein Autobahnbogen nichts." |

Eine 15-m-Kehre landet damit bei 0,41, eine 24-m-Kehre bei 0,625, alles zwischen 40 und 130 m bei
1,0, ein 900-m-Autobahnbogen bei 0,055.

---

## 4. Die Bezugslänge: warum gleitende Fenster und kein Way

Ein OSM-Way ist eine willkürliche Einheit. Dieselbe physische Straße ist hier ein Way von 9 km und
dort elf Ways von 40 m, geteilt an jeder Brücke, jedem Tempolimitwechsel, jedem Namenswechsel. Ein
Score je Way würde von der Datenpflege abhängen: dieselbe Straße bekäme in zwei Nachbarlandkreisen
verschiedene Werte, und ein 40-m-Stück mitten in einer Serpentine hätte fast keine eigene Geometrie
und käme als „gerade" heraus.

Das Urteil eines Fahrers ist auch nicht per Way. „Diese Straße ist gut" bildet sich über etwa eine
halbe Minute Fahrt — bei 60–80 km/h sind das 500–1300 m. **1000 m** liegt mittendrin.

Deshalb:

1. **Korridor.** Der Way wird an beiden Enden entlang seiner geradlinigen Fortsetzung um bis zu einer
   Fensterlänge verlängert. Fortsetzungsregel bewusst konservativ:
   - Durch einen Knoten, an dem genau zwei befahrbare Way-Enden zusammenkommen, wird immer
     weitergegangen (das ist eine reine Datenteilung einer Straße).
   - An einer Kreuzung nur dann, wenn genau ein Kandidat innerhalb von 40° geradeaus weiterführt und
     jeder andere um mindestens 70° abzweigt.
   - Sonst Abbruch; das Fenster wird dann eben beschnitten.
2. **Fenster.** 1000 m lang, Mittelpunkte alle 100 m innerhalb des Ways.
3. **Rückprojektion.** Der Way-Score ist das Mittel der Fensterscores an den Mittelpunkten, die in
   ihm liegen. Erst danach wird quantisiert.

**Belegt durch Tests:** derselbe Weg in drei Ways geschnitten ergibt für jedes Stück denselben Score
(± 0,09 roh), und ein 40-m-Stummel mitten in einer Serpentine erbt deren Charakter statt als Gerade
zu erscheinen (`ScorerTest`).

**Eine Konsequenz, die man aussprechen muss:** weil der Korridor nur der *geraden* Fortsetzung folgt,
erfindet er nie eine 90°-Ecke an einer Kreuzung. Ecken im Score sind ausschließlich Ecken, die
wirklich in der Geometrie stehen. Die Kosten dafür, an einer Kreuzung tatsächlich abzubiegen, gehören
in das Abbiegekostenmodell der Routing-Engine, nicht in diesen Score.

---

## 5. Die Terme im Einzelnen

### 5.1 Dichte (Gewicht 0,50)

```
  ρ  = ( Σ  |δ_i| · q_R(R_i) )  /  Fensterlänge        [rad/m], nur Nicht-Ecken
  C  = min( 1 , √( ρ / 0,003 ) )
```

**Gewichtung nach Drehwinkel, nicht nach Bogenlänge.** Das ist eine bewusste Abkehr von
`adamfranco/curvature`. Bogenlänge belohnt lange sanfte Kurven doppelt: eine 25°-Kurve mit R = 250 m
ist 109 m Bogen, eine 170°-Kehre mit R = 24 m nur 71 m. Nach Bogenlänge gewichtet schlägt eine
gewöhnliche Landstraße eine Serpentine — nachgerechnet: 164 gegen 133 „Kurvenmeter" je Kilometer.
Was der Fahrer aufwendet, ist Lenkeinschlag, und das ist der Drehwinkel.

**Referenzdichte 0,003 rad/m = 3 rad/km ≈ 172°/km** qualitätsgewichteter Richtungsänderung. Zu lesen
als *eine 60°-Kurve mit idealem Radius alle 350 m, durchgehend*.

Gegenprobe an einer Straße, die jeder kennt: die Nordrampe des **Stilfser Jochs** hat 48 Kehren auf
24,3 km, je ~170° bei etwa 15 m Radius. `q_R(15) = 0,41`, also
`2 · 2,97 · 0,41 = 2,4 rad/km` — sie erreicht **0,90** der Referenz, nicht 1,0.

Das ist gewollt und verteidigbar: das Stilfser Joch ist großartig, aber je Kilometer *Fahren* ist eine
durchgehende 45-m-Schwungstrecke dichter und weniger Arbeit im ersten Gang. Was das Stilfser Joch zum
Stilfser Joch macht — Höhe, Steigung, Aussicht — fangen der Steigungs- und der Umgebungsterm ab,
statt so zu tun, als sei seine Kurvendichte unschlagbar.

**Die Wurzel** (Kompressionsexponent 0,5): ohne sie ist der Score praktisch unbrauchbar. 95 % des
europäischen Straßennetzes liegen unter 10 % der Referenz, also läge fast alles auf Stufe 0–1 und der
Router hätte kein Signal. Die Wurzel ist monoton — kein Vergleich zwischen zwei Straßen kann durch
sie kippen — und verteilt den Alltagsbereich über die Skala.

### 5.2 Kurvenanteil (0,12)

Der Anteil des Fensters, in dem der Fahrer in einer Kurve, in ihrer Anfahrt oder in ihrer Ausfahrt
ist. Berechnet aus einem Engagement-Profil: innerhalb einer Kurve der Wert
`min(1, q_R/0,5)`, linear auf 0 auslaufend über **100 m** zu beiden Seiten; überlappende Halos nehmen
das Maximum, damit dicht gereihte Kurven bei 1 sättigen statt doppelt zu zählen.

100 m sind rund 4,5 s bei 80 km/h — der Horizont, über den eine Kurve aufgebaut und wieder
abgewickelt wird.

`min(1, q_R/0,5)` statt `q_R`: Anwesenheit ist nicht dieselbe Frage wie Qualität. Der Dichteterm zieht
einer 15-m-Kehre schon ab, dass sie Arbeit im ersten Gang ist; ein zweites Mal abzuziehen würde genau
die Strecken bestrafen, für die es die App gibt. Jede Kurve zwischen ~19 m und ~215 m Radius ist
schlicht „eine Kurve"; ein Autobahnbogen (q = 0,055) registriert kaum.

**Dieser Term trennt** „vier weite Schwünge über fünf Kilometer" von „eine Überraschungskurve nach
drei Kilometern Gerade" — zwei Strecken mit fast gleicher roher Kurvendichte, die sich völlig
verschieden anfühlen. In der Arena: `R3_FLOWING` 0,18 gegen `R9_DOGLEG` 0,03.

### 5.3 Wechselschwünge (0,12)

Die Aufgabenstellung nennt das ausdrücklich, und zu Recht: das Umlegen von einer Seite auf die andere
ist das Anspruchsvollste, was eine Straße verlangt, und mehr wert als dieselbe Kurve zweimal in
derselben Richtung.

```
  A = Σ ( w_Paar · [Vorzeichen verschieden] · e^(−Lücke/300 m) )  /  Σ w_Paar
  w_Paar = min( |Θ_a| , |Θ_b| )
```

- `w_Paar = min(...)`: ein verkettetes Paar ist nur so stark wie seine schwächere Hälfte.
- **300 m Abklinglänge**: bei ~70 km/h (19 m/s) sind das rund 15 s. Danach war das Motorrad lange
  genug aufrecht und stabil, dass die beiden Kurven zwei getrennte Ereignisse sind und kein
  verketteter Richtungswechsel.
- Nur Kurven mit `q_R ≥ 0,30` zählen mit, sonst bekäme ein Paar Autobahnbögen denselben
  „dynamischen Richtungswechsel"-Bonus wie eine echte S.
- **Ecken sind ausgeschlossen** — ein Links-Rechts-Zickzack durch ein Wohnblockraster darf keinen
  S-Kurven-Bonus verdienen. Das ist ein zweiter, unabhängiger Riegel gegen `R5_GRID`.

### 5.4 Rhythmus (0,05)

`1 − VK` der Abstände zwischen aufeinanderfolgenden Kurven, mit VK = Variationskoeffizient
(Standardabweichung/Mittelwert), gemessen über ein 3000-m-Kontextfenster (Rhythmus ist eine
Eigenschaft eines Streckenabschnitts, nicht eines Kilometers). Unter drei Kurven ist der Term 0 —
einen Rhythmus, den man noch nicht hören kann, gibt es nicht.

**Verdient das einen eigenen Term?** Die ehrliche Antwort: knapp. Bei gleicher Dichte fährt sich eine
gleichmäßige Kurvenkette anders als ein Kurvennest gefolgt von zwei Kilometern Gerade — der Fahrer
kann sich einschwingen statt wiederholt zu beschleunigen und zu bremsen. Aber ein großer Teil davon
steckt schon in Dichte und Kurvenanteil. Deshalb **das kleinste Gewicht aller Terme (0,05)**. Es ist
der am schwächsten begründete Term, und das steht hier, weil es stimmt.

### 5.5 Steigung (0,07) — mit Einwand

0 in der Ebene, linear auf 1 bei **4 %** mittlerem Betragsgefälle, Plateau bis **8 %** (Alpenpässe
liegen im Mittel bei 6–8 %), dann linear zurück auf 0 bei **15 %** (darüber ist eine Straße eine
Rampe im ersten Gang, meist eine Almzufahrt mit passendem Belag). Nur Bonus, nie negativ — flach ist
langweilig, nicht schlecht. Höhenprofil über 200 m geglättet, damit DEM-Stufenrauschen nicht als
Gefälle durchschlägt.

> **Einwand zu Protokoll.** Der Auftraggeber hat „Steigungsprofil" als aufwertend vorgegeben, und es
> ist umgesetzt. Fachlich halte ich den Term für den schwächsten der positiven Terme, aus zwei
> Gründen. Erstens **doppelt er**: Bergstraßen sind ohnehin kurvig, der Steigungsterm belohnt also
> größtenteils, was der Dichteterm schon belohnt hat. Zweitens macht er den Score von einem
> **Höhenmodell abhängig**, das in der Pipeline vorhanden sein muss und dessen Auflösung (Copernicus
> GLO-30) für kurze Rampen grenzwertig ist. Deshalb trägt er nur 0,07, und ohne Höhendaten ist er 0
> statt negativ — fehlende Höhe bestraft nie eine Straße.
>
> Die Testarena formuliert mit `E8_ELEVATION_NEUTRALITY` (Severity `info`) die Gegenposition:
> eine reine Kurvenbewertung solle auf `ele` gar nicht reagieren. Beides ist honoriert, indem die
> Abweichung gedeckelt wird: die zwei Höhenrouten haben identische Grundrissgeometrie und dürfen
> sich um **höchstens eine Stufe** unterscheiden. Gemessen: 0 Stufen Unterschied (0,2893 gegen
> 0,3038), weil die 350 Höhenmeter der Arena auf 1184 m eine Steigung von 29,6 % ergeben — jenseits
> der 15 %, wo der Bonus ohnehin auf null gefallen ist. Das ist kein Ausweichmanöver, sondern
> dasselbe Urteil, das der Term auch an einer echten 30-%-Rampe fällen würde.

### 5.6 Umgebung (0,08)

Punkte entlang des Korridors werden gegen einen Rasterindex (500-m-Zellen) aus
`landuse`/`natural`/`leisure`-Polygonen und Gewässerlinien geprüft.

**Regel: die unattraktivste Umgebung entscheidet.** Liegt der Punkt in mehreren Polygonen, gewinnt
das Minimum — ein Industriegebiet, das in einen Wald geschlagen wurde, ist ein Industriegebiet. Liegt
er in keinem, ist der Wert der neutrale 0,5, niemals eine Vermutung.

| Umgebung | Wert |
|---|---|
| Wald, Naturschutzgebiet, Nationalpark, Gletscher | 1,00 |
| Wasser, Bucht | 0,95 |
| Heide, Moor, Weinberg, Feuchtgebiet | 0,85 |
| Wiese, Grasland, Obstplantage | 0,80 |
| Park | 0,70 |
| Acker, Grünfläche | 0,60 |
| *keine Information* | **0,50** |
| Wohngebiet | 0,20 |
| Gewerbe, Einzelhandel, Garagen, Bahnanlagen | 0,15 |
| Industrie, Steinbruch, Baustelle | 0,10 |
| Deponie | 0,05 |

Gewässer sind das Einzige, was eine neutrale Umgebung aufwerten kann, ohne den Punkt zu enthalten:
eine Straße am Flussufer ist landschaftlich schön, obwohl sie nicht „im" Wasser liegt. Die Aufwertung
klingt linear bis 120 m ab und kann eine bebaute Fläche nie überschreiben (ein Industriehafen bleibt
ein Industriehafen).

Multipolygon-Relationen werden nicht aufgelöst; ihre äußeren Ways tragen das Landuse-Tag meist selbst,
und der Term bewegt den Score ohnehin um höchstens 0,08.

### 5.7 Straßencharakter (0,06)

Wie gut eine Straße dieser Klasse für einen Motorradfahrer ist, *bevor* man eine einzige Kurve
angeschaut hat.

| `highway` | Wert | | `highway` | Wert |
|---|---|---|---|---|
| tertiary | 0,90 | | motorway | 0,30 |
| secondary | 0,85 | | residential | 0,25 |
| unclassified | 0,80 | | service | 0,15 |
| primary | 0,60 | | living_street | 0,10 |
| road (unbekannt) | 0,55 | | track | 0,35 |
| trunk | 0,40 | | *_link | −0,10…−0,15 |

Die klassische Motorradstraße ist die tertiary/secondary Landstraße: wenig Verkehr, brauchbarer
Belag, keine Maut, direkter Zugang zur Landschaft. Primary trägt Lastwagen und Überholdruck. Autobahn
ist Transit — schnell, monoton und das Gegenteil des Grundes, ein Motorrad zu besitzen.

---

## 6. Die Strafen

### 6.1 Ecken

```
  Rate  = (Σ |Θ_Ecke|) / (π/2)  je Kilometer          [90°-Äquivalente/km]
  P     = 1 / (1 + (Rate / 0,8)²)
```

`k = 0,8/km`: etwa ein rechtwinkliger Abbieger je 1,25 km kostet die Hälfte. Darunter sind Ecken
vereinzelte Merkmale, die ein Fahrer wegsteckt; darüber navigiert man ein Straßenraster oder ein
Feldwegnetz und hält ständig an. **Exponent 2 statt 1**, weil Ecken sich verstärken: jede zerstört
zusätzlich den Rhythmus, den die vorangegangenen Kurven aufgebaut haben.

Zusammen mit `q_R(0) = 0` ist das der Mechanismus, an dem `R5_GRID` zerbricht: 8 rechtwinklige Ecken
auf 4,5 km ergeben 1,73/km und Faktor 0,67 (fensterweise gemittelt) — obendrauf auf einen Kurvenwert,
der ohnehin fast null ist, weil keines dieser 717° einen Radius hat.

### 6.2 Ortslage

`P = 1 − 0,75 · Anteil`. Eine Strecke vollständig innerhalb eines bebauten Gebiets behält ein
Viertel. Nicht null, weil eine Ortsdurchfahrt fahrbar und manchmal unvermeidlich ist — aber eine
Kurve bei 50 km/h zwischen parkenden Autos und Grundstückseinfahrten ist keine Kurve, für die man
losgefahren ist.

Als bebaut zählt: Punkt innerhalb eines `landuse`-Polygons der Klassen residential/commercial/retail/
industrial/garages/railway/construction/landfill/brownfield, **oder** `highway=residential` bzw.
`living_street` (die Klasse ist selbst ein Beleg für Ortslage, auch wo niemand ein Polygon gezeichnet
hat), **oder** `maxspeed ≤ 30`.

### 6.3 Belag

Explizites `surface` gewinnt, sonst `tracktype`, sonst ein klassenabhängiger Prior; `smoothness`
modifiziert immer. Asphalt/paved 1,0; Beton 0,98; Pflaster 0,60; Kopfsteinpflaster 0,40; verdichtet/
Feinschotter 0,70; Schotter/unbefestigt 0,50; Erde/Gras 0,25–0,30; Sand 0,20.

Kopfsteinpflaster wird **schlechter** bewertet als Schotter, obwohl es nominell „befestigt" ist: auf
zwei Rädern ist eine nasse Kopfsteinkurve der Belag mit dem geringsten Vertrauen überhaupt.

Mit `--enduro` ist dieser Faktor durchgehend 1,0 (Enduro-/Adventure-Profil) — das ist die
Umkehrbarkeit, die `E6` als `soft` markiert.

### 6.4 Unterbrechungen

Gewichtete „Halt-Äquivalente" je Kilometer aus Knoten- und Way-Tags: Ampel 1,0; Stoppschild 0,7;
Kreisverkehr 0,8; Bahnübergang 0,4; Verkehrsberuhigung 0,5; Schranke 0,6; Fußgängerüberweg 0,3;
Vorfahrt-gewähren 0,25; Mautstelle 0,8; Tempo 30 zusätzlich 0,5/km.

`P = 1 / (1 + Rate/3,0)`. Drei Unterbrechungen je Kilometer sind Innenstadtdichte und halbieren den
Wert; eine Ampel alle 2 km kostet rund 14 %.

---

## 7. Fehlende Tags

In einem typischen deutschen Geofabrik-Auszug hat grob **zwei Drittel aller `highway`-Ways kein
`surface`** und ein ähnlicher Anteil kein `maxspeed`. Ein Score, der diese Tags braucht, ist außerhalb
einer Demo wertlos.

**Grundsatz: zum neutralen Wert schrumpfen, nie zum schlechtesten raten, und die Unsicherheit
mitliefern.**

| fehlt | Verhalten | Konfidenzfaktor |
|---|---|---|
| `surface`, Straßenklasse ≥ tertiary | Prior 0,97–0,98 (in Europa mit hoher Sicherheit befestigt) | 0,88–0,95 |
| `surface`, `unclassified` | Prior 0,94 | 0,75 |
| `surface`, `service` | Prior 0,92 | 0,70 |
| `surface`, `track` | Prior **0,62** statt 0,50 | **0,35** |
| `maxspeed` | wird nur als Ortslage-Indikator genutzt | 0,95 |
| Höhendaten | Steigungsterm = 0 (neutral, nie Malus) | 0,95 |
| Landuse-Polygone | Umgebung = 0,50 (neutral) | – |

Der `track`-Prior ist der wichtigste Fall und zeigt das Prinzip: ein Feldweg ohne `surface` ist
*wahrscheinlich* unbefestigt (Faktor 0,50), aber oft genug ein asphaltierter Wirtschaftsweg — also
liegt der Prior bei 0,62 und die Konfidenz bei 0,35. Aus einem fehlenden Tag hart zu raten ist,
wie ein Scorer selbstbewusst falsch liegt.

Die **Konfidenz** ist eine eigene Ausgabe (und optional ein eigenes Tag, `--conf-tag`). Sie beschreibt
die *Eingaben*, nicht das Urteil: sie sagt, wie viel des Scores auf Tags ruht, die tatsächlich da
waren. Sie fließt bewusst **nicht** in den Score ein — ein unsicherer Score ist kein schlechterer
Score, und die beiden Größen zu vermischen macht beide unlesbar. Die spätere Pipeline kann sie nutzen,
um z. B. Kacheln mit niedriger mittlerer Konfidenz zur Nachbearbeitung zu markieren.

Was der Score **nie** tut: `maxspeed` in die Kurvenqualität einrechnen. Das wäre zirkulär — Tempolimits
werden unter anderem *wegen* enger Kurven gesetzt, eine kurvige Straße würde sich also selbst
abwerten. `maxspeed` dient hier nur als Ortslage-Indikator.

---

## 8. Ergebnis an der Testarena

`./gradlew -p tools/curvescore arenaReport`, Stand dieses Berichts:

```
Route            Stufe      roh  Laenge Grad/km Ecke/km   Dich  Enga  SKur  Rhyt  Szen  Klas   Ecke   Ort Belag
--------------------------------------------------------------------------------------------------------
R4_S_CURVES          9   0.5905    4254     255    0.00   0.73  0.48  0.49  0.28  0.50  0.90   1.00  1.00  1.00
R2_SERPENTINE        8   0.5087    4804     443    0.00   0.66  0.33  0.47  0.25  0.49  0.90   1.00  0.97  1.00
R_HILL_CLIMB         4   0.3038    2239      85    0.00   0.32  0.22  0.05  0.00  0.50  0.90   1.00  1.00  1.00
R_HILL_FLAT          4   0.2893    2239      85    0.00   0.32  0.22  0.05  0.00  0.50  0.90   1.00  1.00  1.00
R_FOREST             4   0.2648    2239      85    0.00   0.32  0.22  0.05  0.00  0.73  0.90   1.00  0.90  1.00
R3_FLOWING           3   0.2275    4951      46    0.00   0.23  0.18  0.00  0.00  0.50  0.85   1.00  1.00  1.00
R9_DOGLEG            3   0.1952    4321      23    0.00   0.14  0.03  0.21  0.00  0.50  0.90   1.00  1.00  1.00
R_INDUSTRIAL         1   0.1105    2239      85    0.00   0.32  0.22  0.05  0.00  0.22  0.90   1.00  0.47  1.00
R8_JOG90             1   0.0980    4865      30    1.00   0.19  0.03  0.00  0.00  0.50  0.90   0.52  1.00  1.00
R6_GRAVEL            1   0.0825    4337      24    0.11   0.26  0.10  0.00  0.24  0.44  0.35   0.94  0.84  0.50
R1_HIGHWAY           1   0.0760    3996       0    0.00   0.00  0.00  0.00  0.00  0.50  0.60   1.00  1.00  1.00
R7_MOTORWAY          0   0.0526    4776       4    0.21   0.02  0.01  0.00  0.00  0.47  0.30   0.87  0.92  1.00
R5_GRID              0   0.0096    4466       3    1.73   0.03  0.01  0.00  0.00  0.39  0.25   0.67  0.25  1.00
```

Bildlich: `tools/curvescore/report/arena.svg` — Serpentine und S-Kurven grün, Gitter und Autobahn rot.

### Die neun Erwartungen

| ID | Severity | Ergebnis | Zahlen |
|---|---|---|---|
| E1_CURVES_OVER_HIGHWAY | hard | **erfüllt** | min(R2/R3/R4) = R3 0,2275 > R1 0,0760 |
| E2_CURVES_OVER_MOTORWAY | hard | **erfüllt** | R3 0,2275 > R7 0,0526 |
| E3_CURVES_OVER_GRID | hard | **erfüllt** | R3 0,2275 > R5 0,0096 (Faktor 24) |
| E4_CURVES_OVER_DOGLEG | hard | **erfüllt** | R3 0,2275 > R9 0,1952 |
| E5_CURVES_OVER_JOG90 | hard | **erfüllt** | R3 0,2275 > R8 0,0980 |
| E6_CURVES_OVER_GRAVEL | soft | **erfüllt** | R3 0,2275 > R6 0,0825 |
| E7_HIGHWAY_OVER_GRID | hard | **erfüllt** | R1 0,0760 > R5 0,0096 (Faktor 8) |
| E8_ELEVATION_NEUTRALITY | info | **erfüllt** | 0,2893 vs. 0,3038, 0 Stufen Differenz (erlaubt ≤ 1) |
| E9_SCENIC_FOREST_OVER_INDUSTRIAL | soft | **erfüllt** | R_FOREST 0,2648 > R_INDUSTRIAL 0,1105 |

Geprüft wird die **strenge Lesart**: *jede* bevorzugte Route muss *jede* abgelehnte schlagen. Die
schwache Lesart (beste gegen beste) würde schon durchgehen, wenn zwei der drei bevorzugten Routen
schlecht abschneiden — das ist nicht, was die Erwartung meint.

`ArenaExpectationsTest` lässt den Build rot werden, sobald eine harte Erwartung fällt.

### `R5_GRID` — die eingebaute Falle

`R5_GRID` hat die größte Gesamt-Richtungsänderung der Arena (717°, mehr als die Serpentine) und
landet trotzdem **letzter, auf Stufe 0**. Vier unabhängige Mechanismen sorgen dafür:

1. Alle 8 Richtungswechsel werden als **Ecken** klassifiziert (isolierte ≥ 40°-Sprünge ohne
   persistente Nachbarn) und bekommen `q_R = 0`. Von 160°/km überleben **3°/km** als Kurvenwert.
2. Der **Ecken-Malus** (1,73 Ecken/km) drückt mit Faktor 0,67.
3. Der **Ortslage-Malus** greift doppelt begründet (`landuse=residential` **und**
   `highway=residential`) und drückt mit Faktor 0,25.
4. Der **Straßencharakter** einer residential-Straße ist 0,25.

Ergebnis 0,0096 — Faktor 8 unter der langweiligsten Alternative, der kerzengeraden Schnellstraße.
Das ist genau, was `E7` verlangt.

`R8_JOG90` (6 stumpfe Ecken, keine echte Kurve) landet aus demselben Grund auf Stufe 1.

### Kalibrierung an realen Strecken

Die Arena beweist die *Reihenfolge*. Sie kann die *Skala* nicht beweisen, weil in ihr keine Straße
liegt, die je jemand gefahren ist. `ReferenceRoadsTest` schließt diese Lücke: sechs Archetypen aus
ihrer echten veröffentlichten Geometrie nachgebaut, jeder mit einem Band, in dem er landen muss.

| Referenzstrecke | gemessen | Band |
|---|---|---|
| Autobahn (R = 1500 m alle 2 km) | **1** (roh 0,068) | 0–1 |
| Ortsstraße im Wohngebiet (90°-Raster) | **0** (roh 0,000) | 0 |
| gewöhnliche Landstraße (R = 300 m alle 700 m) | **3** (roh 0,233) | 3–6 |
| sehr kurvige Landstraße (R = 120 m alle 250 m, Wald, 3 %) | **14** (roh 0,899) | 11–15 |
| Stilfser Joch Nordrampe (R = 15 m Kehren, 7,4 %) | **12** (roh 0,762) | 9–13 |
| Traumstrecke (Dauerkurven R = 75 m, Wald, 6 %) | **15** (roh 0,975) | 14–15 |

Die Skala wird also wirklich benutzt: Stufe 15 ist erreichbar (eine durchgehend schwingende
75-m-Waldstraße mit 6 % Steigung gibt es im Schwarzwald und in den Vogesen), Stufe 0 auch, und
dazwischen liegen die Alltagsfälle sauber verteilt. Ein zweiter Test prüft, dass eine
Steigerungsreihe von fünf Straßen monoton ist und mindestens neun Stufen überspannt (gemessen:
1 → 3 → 8 → 12 → 13).

---

## 9. Laufzeit und Speicher

Gemessen auf dem Entwicklungsrechner (Apple Silicon, 10 Kerne, JDK 17,
`./gradlew -p tools/curvescore benchmark`), 300 000 Ways × 35 Knoten = 183 591 km synthetischer
Bogen-Geometrie plus 75 000 Landuse-Polygone:

```
Umgebungsindex (75000 Polygone): 0,59 s
parallel  (10 Kerne): 2,71 s  ->  110 747 Ways/s,  67 774 km/s
seriell   (1 Kern)  : 11,79 s ->   25 453 Ways/s,  15 576 km/s
```

PBF-Lesen (`bench-io`, 400 000 Ways / 8 Mio. Knoten, 30,4 MB):

```
lesen: 1,61 s -> 5,2 Mio. Entities/s, 18,9 MB/s (1 Kern)
```

### Hochrechnung auf Bayern

Bayern (`bayern-latest.osm.pbf`, **805 MB**, laut Research-Dokument) enthält grob 1,5–2 Mio.
`highway`-Ways mit zusammen rund 400 000 km und etwa 60–70 Mio. Knoten.

| Schritt | Rechnung | 4 Kerne |
|---|---|---|
| PBF dekodieren | 805 MB ÷ 18,9 MB/s, mit Faktor 3 Sicherheitszuschlag für echte, tag-reiche Daten | **2–4 min** |
| Umgebungsindex | ~600 000 Polygone, linear zu den gemessenen 0,59 s / 75 000 | **~10 s** |
| Bewerten | 1,75 Mio. Ways ÷ (25 453 Ways/s × 4 Kerne) = 17 s; Faktor 5 Zuschlag für echte Geometrie, Korridorsuche über ein dichtes Netz und Szenerie-Lookups | **~1,5 min** |
| Tag zurückschreiben | I/O-gebunden | **~1 min** |
| **Summe** | | **5–7 min** |

Der Richtwert lautet „unter einer Stunde auf 4 Kernen". Die Bewertungsstufe braucht davon
**etwa ein Zehntel**; das Budget ist auch mit großzügigen Zuschlägen nicht gefährdet. Der Engpass
liegt eindeutig beim PBF-Dekodieren, nicht bei der Bewertung.

### Speicher — der eigentliche Engpass

Der aktuelle Reader hält alles in einer `HashMap<Long, OsmNode>`. Das ist für Stadtauszüge und die
Testarena richtig, für Bayern **nicht**: 65 Mio. Knoten als Objekte kosten grob 5–7 GB Heap, und ein
GitHub-Actions-Runner hat 16 GB.

Für die Produktion ist der Umbau vorgezeichnet und in `OsmPbfReader` dokumentiert:

1. **Erster Durchgang** nur über die Ways: Knoten-IDs der `highway`-Ways in ein `LongOpenHashSet`
   (~30 Mio. Einträge, ~500 MB).
2. **Zweiter Durchgang** über die Knoten: nur die referenzierten in parallele Primitiv-Arrays
   (`long[] id`, `int[] lat`, `int[] lon`, `float[] ele` — Koordinaten als 1e7-Festkomma),
   nach ID sortiert, Zugriff per Binärsuche. ~30 Mio. × 20 Byte = **~600 MB**.
3. Ways streamend bewerten, Ergebnisse als `long[] wayId` + `byte[] level` sammeln (~20 MB).

Damit passt Bayern in **unter 2 GB** und die Pipeline läuft mit `-Xmx4g` bequem. Der Umbau berührt
ausschließlich `io/`; die Bewertungsstufe sieht ihn nicht.

---

## 10. Wo dieser Score falsch liegt

Jeder Score hat Fälle, in denen er ein Fehlurteil fällt. Diese hier sind bekannt, nicht vermutet —
die meisten sind beim Bauen aufgetreten.

### 10.1 Eine mit einem einzigen Knoten gezeichnete Kehre wird zur Ecke

**Der wichtigste Fehlerfall, weil er die Kernentscheidung betrifft.** Die Trennung Kurve/Ecke beruht
auf Persistenz: eine Kehre, die ein Mapper mit einem einzigen Knoten gezeichnet hat, ist geometrisch
von einer Kreuzungsecke *nicht unterscheidbar* und wird als Ecke gelesen — mit Kurvenwert 0 und
Ecken-Malus obendrauf. Eine echte Serpentine kann so auf Stufe 0 landen.

Das ist eine Datenqualitätsgrenze, keine algorithmische, und keine denkbare Methode löst sie aus der
Geometrie allein. Milderung in echten Daten: eine Kreuzungsecke sitzt fast immer auf einem Knoten,
den mehrere Ways teilen, eine Kehre nicht. Diese Zusatzinformation wird derzeit **nicht** genutzt
(die Arena braucht sie nicht — ihr Gitter ist ein einziger Way ohne Kreuzungsknoten). Sie ist der
naheliegendste nächste Verbesserungsschritt, sobald echte Daten vorliegen.

### 10.2 Ein 90°-Abbieger unmittelbar vor einer Kurve wird zur Kehre

Die Kehrseite derselben Regel. Wenn eine echte Kurve direkt an einer rechtwinkligen Ecke anschließt,
trägt sie mehr als 35 % der Ablenkung weiter, die Ecke wird nicht als solche erkannt, und beides
verschmilzt zu einer schmeichelhaft engen „Kurve". Genau das passiert in der Arena bei `R8_JOG90`:
eine der sechs Ecken wird mit dem Anschlussbogen zu einer 151°-Kurve mit R = 57 m verschmolzen, und
`R8` bekommt 30°/km Kurvenwert, den es nicht verdient. Es hebt `R8` von Stufe 0 auf Stufe 1 — hier
ohne Folgen, weil keine Erwartung darauf beruht, aber an einer echten Ortsausfahrt mit anschließender
Kurve wäre es eine Überbewertung.

### 10.3 Systematisches Lagerauschen über 0,5 m

Der Douglas-Peucker-Vorfilter räumt Digitalisierungsrauschen unterhalb seiner Toleranz vollständig
weg. Rauschen *oberhalb* — etwa eine aus einer einzelnen, schlechten GPS-Spur erzeugte Strecke mit
±1 m Streuung — überlebt und wird als echte Krümmung gelesen: ±1 m über eine 20-m-Sehne ist ein
50-m-Radius, also volle Punktzahl. Betroffen sind vor allem `track`- und `path`-Geometrien in dünn
gemappten Gegenden. Die Toleranz zu erhöhen ist kein Ausweg — bei 1,5 m verschwinden echte
15-m-Kehren (Pfeilhöhe 0,83 m über eine 10-m-Sehne).

### 10.4 Der Score kennt die Sicht nicht

Die Aufgabenstellung nennt „schlechte Sicht" unter dem, was Kurven entwertet, und der Score hat dafür
**nichts**. Eine uneinsehbare Kurve hinter einer Kuppe und eine offene Kurve mit voller Einsicht
bekommen denselben Wert. Sichtweite ließe sich prinzipiell aus dem DEM plus Bewuchs berechnen, aber
nicht seriös aus einem 30-m-Höhenmodell ohne Vegetationsmodell. Der Score ist an dieser Stelle
schlicht blind, und das steht hier statt eines Scheinterms.

### 10.5 Verkehr und Tageszeit

Der Score bewertet Geometrie und Tags, nicht Verkehr. Eine wunderbare Passstraße am Sonntagnachmittag
im Juli ist ein Stau; derselbe Pass am Dienstagmorgen ist die Traumstrecke. Dieselbe Zahl steht für
beides. Genauso fehlt die Jahreszeit — der Score empfiehlt im Januar begeistert einen Alpenpass, der
gesperrt ist. Beides gehört in Datenschichten, die es offline nicht gibt; ein Wintersperren-Hinweis
aus `seasonal`/`snowplowing`-Tags wäre machbar und fehlt.

### 10.6 Radiusqualität ohne Bezug zum Tempolimit

`q_R` ist bewusst geschwindigkeitsunabhängig, damit der Score nicht zirkulär wird (Abschnitt 7). Der
Preis: eine 60-m-Kurve mit Tempo 30 wird genauso gut bewertet wie mit Tempo 100, obwohl man bei
Tempo 30 hindurchkriecht. Der Ortslage-Malus fängt den häufigsten Fall (Ortsdurchfahrt) ab, aber
nicht ein Tempo-50-Limit auf freier Strecke.

### 10.7 Landuse-Polygone sind ungleich gepflegt

Der Umgebungsterm ist nur so gut wie die Landuse-Abdeckung. In Deutschland ist `landuse=forest` gut
gepflegt, in Südosteuropa lückenhaft. Wo Polygone fehlen, ist der Term neutral (0,5) — eine echte
Waldstraße verliert also bis zu 0,04 roh, rund eine halbe Stufe, weil niemand den Wald gezeichnet
hat. Das ist der richtige Fehler (Zurückhaltung statt Raten), aber es macht den Score regional
ungleich streng.

### 10.8 Kalibriert an einem Fahrertyp

Die Konstanten in `q_R` gehen von einem Fahrer aus, der ~0,4 g bequem findet und die Kurve zwischen
45 und 82 km/h nimmt. Das ist ein Tourenfahrer auf einer mittelschweren Maschine. Ein Supersportler
findet 130 m langweilig, wo hier das Plateau endet; ein Chopper-Fahrer findet 40 m unangenehm eng, wo
es beginnt. `ScoreConfig` ist deshalb ein `data class` mit benannten Parametern: ein zweites Profil
ist eine Konstruktorzeile und kein Umbau. Ausgeliefert wird zunächst nur eins, plus `--enduro` für
den Belag.

### 10.9 Nur eine Zahl je Way, obwohl der Score fensterweise rechnet

Intern hat der Scorer für jeden 100-m-Schritt ein eigenes Urteil. Nach außen geht davon ein Mittel je
Way, weil BRouter Kantenattribute liest. Bei einem 8 km langen Way, dessen erste Hälfte Serpentine
und dessen zweite Hälfte Gerade ist, bekommen beide Hälften die Mittelstufe — die Serpentine wird
unterschätzt, die Gerade überschätzt. Saubere Lösung wäre, solche Ways in der Pipeline an den
Stellen zu teilen, an denen sich der Fensterscore stark ändert. Die Information dafür liegt vor
(`score`-Modus gibt sie aus); der Schnitt gehört in die rd5-Stufe und ist dort noch nicht vorgesehen.

### 10.10 Zwei Stufen sind manchmal eine Stufe

`R3_FLOWING` (0,2275) und `R9_DOGLEG` (0,1952) unterscheiden sich roh um 16 %, landen bei 16 Stufen
aber beide auf **Stufe 3**. Die Reihenfolge stimmt, der Router sieht sie über den ganzzahligen Tag
aber als gleichwertig. Wenn sich herausstellt, dass BRouter den Wert numerisch nutzen kann, ist
`--levels 32` oder der rohe Wert die bessere Wahl; deshalb ist die Stufenzahl konfigurierbar und der
Rohwert Teil der Ausgabe.

---

## 11. Schnittstelle zur Pipeline

```bash
./gradlew -p tools/curvescore run --args="tag --in region.osm --out region.tagged.osm"
```

schreibt an jeden bewerteten Way `<tag k="opencurv:curve" v="0..15"/>`. Tagname konfigurierbar
(`--tag-name`), optional zusätzlich Rohwert (`--raw-tag`) und Konfidenz (`--conf-tag`).

Eigenschaften, die durch `TagWriteBackTest` festgenagelt sind:

- Die Ausgabedatei ist ein **Echo** der Eingabe — jedes vorhandene Tag, jede Geometrie, jedes
  Attribut überlebt unverändert; es kommt genau ein Tag je bewertetem Way hinzu.
- Nicht bewertete Ways (Fußwege, Landuse-Polygone) bleiben unangetastet.
- Ein **zweiter Lauf** über eine bereits getaggte Datei ersetzt das Tag, statt es zu verdoppeln —
  die Pipeline ist idempotent.
- Der geschriebene Wert liegt garantiert in `0 … levels−1`.
- XML- und PBF-Eingabe liefern dieselben Scores.

Zurückgeschrieben wird derzeit **OSM-XML**. Wie der Wert von dort in die `.rd5`-Kacheln kommt
(`lookups.dat`-Erweiterung, Pseudo-Tags in `process_pbf_planet_production.sh`), klärt ein paralleler
Arbeitsstrang; siehe `1.Doku/RD5_Pipeline.md`.

---

## 12. Tests

`./gradlew -p tools/curvescore test` — **50 Tests, alle grün.**

| Datei | deckt ab |
|---|---|
| `GeometryTest` | Radiusrückgewinnung (15–400 m, ≤ 3 % Fehler), Ecke vs. Kehre, das 8-Ecken-Raster, Richtungsinvarianz, Lagerauschen, grob gemappte Kurven |
| `TermsTest` | jeder Term einzeln gegen seine dokumentierten Ankerwerte und Formen (Monotonie, Plateaus, Halbwertsraten, Quantisierung bei 2/4/8/16/32 Stufen) |
| `ScorerTest` | Way-Schnitt-Invarianz, Stummel im Korridor, Richtungsinvarianz, fehlende Tags, Enduro-Profil, Ortsraster, Wald vs. Industrie, Ampeln, gesperrte Wege, Stufenzahl |
| `ReferenceRoadsTest` | die sechs realen Referenzstrecken und die Spreizung der Skala |
| `TagWriteBackTest` | Tag-Rückschrieb, Idempotenz, konfigurierbarer Name, XML/PBF-Gleichheit |
| `ArenaExpectationsTest` | **E1–E9**, `R5_GRID`-Falle, Abgleich mit der analytischen Ground Truth, SVG |
| `Diagnostics` | Ausdruck der Rohgeometrie je Arena-Route (zum Kalibrieren, keine Zusicherung) |
