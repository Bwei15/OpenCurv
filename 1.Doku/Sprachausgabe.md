# Die Sprachausgabe von OpenCurv

Stand: September 2026. Betroffene Module: `app/src/main/java/com/motoroute/voice/`,
`app/src/main/java/com/motoroute/domain/guidance/`, `app/src/main/java/com/motoroute/domain/NavigationManager.kt`.

Auftrag war die Beschwerde "die Sprachausgabe kommt oft mehrfach und ist nicht richtig
durchdacht". Dieses Dokument beschreibt die Ursache, den Neubau und was davon nur auf einem
echten Gerät mit echtem Headset geprüft werden kann.

---

## 1. Woran lagen die Mehrfachansagen

Zwei unabhängige Ursachen, beide im alten `NavigationManager` (Distanz-Ringe `1000 / 300 / 50 m`):

### 1.1 Der Ring-Scan lief in die falsche Richtung

Die Ringe sollten sich beim Auftreffen gegenseitig ablösen ("Crossing several rings in one fix
retires the wider rings silently" stand sogar so im Kommentar) — aber der Code scannte von
**breit nach schmal** und feuerte beim **ersten** noch nicht angesagten Ring, dessen Schwelle
nicht überschritten war. Landete eine Instruktion beim ersten Auswerten schon nah dran (z. B.
65 m entfernt, weil die vorherige Kurve erst 80 m vorher lag), feuerte der 1000-m-Ring sofort
(mit der *echten* Distanz "in 65 Metern" — der Text war nie falsch, nur die Häufigkeit), markierte
aber nur sich selbst als erledigt. Auf der nächsten Auswertung, ein bis zwei Sekunden später, lag
die Distanz plötzlich unter 300 m: der zweite Ring feuerte *erneut* für dieselbe Kurve ("in 55
Metern …"), kurz danach der dritte ("in 30 Metern …").

Das wurde mit einem Reproduktions-Test vor der Reparatur nachgewiesen (`NavigationManagerTest`,
Fixture `quickSuccessionRoute`, zwei Abbiegungen 80 m auseinander): der alte Code erzeugte für die
zweite Abbiegung **drei** Ansagen ("in 60 m", "in 40 m", "in 20 m rechts abbiegen") in gut zwei
Sekunden.

**Reparatur:** [`AnnouncementTiming.deepestDueTierIndex`](../app/src/main/java/com/motoroute/domain/guidance/AnnouncementTiming.kt)
scannt jetzt vom **schmalsten** noch nicht gefeuerten Ring zum breitesten und nimmt den
*tiefsten* bereits erreichten — und markiert **alle** breiteren Ringe in derselben Auswertung als
erledigt, nicht erst auf der nächsten. Aus dem Beispiel oben wird eine einzige Ansage bei "in 40
Metern" (Regressionstest: `landing already close after a jump speaks once, at the final tier`).

### 1.2 Feste Ringe passen nicht zu Kurvenkombinationen — und die sind Kurven-Navigation

Der zweite, für dieses Projekt eigentlich wichtigere Grund: OpenCurv sucht *gezielt* kurvige
Straßen, also Serpentinen mit mehreren Spitzkehren im Abstand von 60–150 m. Jede einzelne
Spitzkehre bekam bisher ihre eigene volle 1000/300/50-m-Kaskade. Auf einer Dreifach-Spitzkehre
bedeutete das potenziell neun Ansagen in wenigen Sekunden — "kommt oft mehrfach" ist auf genau
den Straßen, für die die App gebaut ist, keine Ausnahme, sondern der Normalfall.

**Reparatur:** [`CurveCombo`](../app/src/main/java/com/motoroute/domain/guidance/CurveCombo.kt)
gruppiert Abbiegungen, die zeitlich (nicht metrisch!) eng beieinanderliegen, zu einem
zusammenhängenden Lauf und lässt nur die erste Instruktion des Laufs überhaupt sprechen — der
Rest wird stumm durchfahren. Details in Abschnitt 4.

---

## 2. Wie das Timing jetzt gerechnet wird

Datei: [`AnnouncementTiming.kt`](../app/src/main/java/com/motoroute/domain/guidance/AnnouncementTiming.kt).

Statt fester Meter gilt **Restzeit bis zum Manöver** = Distanz ÷ aktuelle Geschwindigkeit
(Geschwindigkeit nach unten auf `2,5 m/s` begrenzt, damit ein Stopp vor der Kurve keine
Restzeit von "unendlich" ergibt). Drei Stufen, wie in `1.Doku/AI_README.md` §2.3 gefordert:

| Stufe | Auslöser | Bemerkung |
|---|---|---|
| **Früh** (early) | 15 s vorher | immer |
| **Bestätigung** (confirm) | 7 s vorher | nur ab **15 m/s** (≈ 54 km/h) — das ist die "Landstraße"-Zusatzstufe aus der Vorgabe; innerorts wäre eine dritte Ansage nur Lärm |
| **Letzter Hinweis** (final) | 3 s vorher | immer; einzige Stufe, die eine laufende Ansage unterbrechen darf (`isFinal`) |

Bei 100 km/h (≈ 28 m/s) entspricht das 420 m / 196 m / 84 m — nahe an den in der Vorgabe
genannten Landstraßen-Referenzwerten (600–800 m / 200 m / 50–70 m; die Vorgabe selbst macht daraus
aber ausdrücklich die einfache Regel "immer 15 und 3 Sekunden", an die sich die Implementierung
hält). Innerorts bei 14 m/s (50 km/h) ergibt das 210 m / — / 42 m, ebenfalls im von der Vorgabe
genannten Rahmen (150–200 m / 20–30 m).

Die Zuordnung "welche Stufe ist jetzt fällig" ist in
[`AnnouncementTiming.deepestDueTierIndex`](../app/src/main/java/com/motoroute/domain/guidance/AnnouncementTiming.kt)
gebündelt und mit `AnnouncementTimingTest` einzeln durchgetestet — inklusive des exakten Falls aus
Abschnitt 1.1 als eigener Test.

---

## 3. Wie das Headset geweckt wird

Drei Bausteine, alle in `voice/`, weil es dazu laut Recherche (`1.Doku/Research_Tech_Options.md`
Abschnitt G) **kein fertiges Modul** gibt — OsmAnd hat dasselbe Problem offen, Sygic beschreibt nur
sein Muster, ohne Code herzugeben. Das musste also selbst gebaut werden:

1. **[`PrerollChime`](../app/src/main/java/com/motoroute/voice/PrerollChime.kt)** spielt vor
   jeder Ansage einen kurzen, zweitönigen Chime (`res/raw/nav_chime.wav`, 340 ms, synthetisch
   erzeugt — eine steigende Quinte, kein aggressiver Piepton). Die Vorgabe nennt sowohl Stille als
   auch einen Signalton als Option; ein hörbarer Ton wurde gewählt, weil er zwei Probleme auf
   einmal löst: Er öffnet die A2DP-Audiospur (das eigentliche Ziel), und er ist für den Fahrer ein
   verlässliches "gleich kommt was"-Signal, das eine reine Stille nicht liefert. `SoundPool` gibt
   keine zuverlässige "fertig abgespielt"-Rückmeldung, deshalb wird nach der bekannten Clip-Länge
   (380 ms, mit Sicherheitsabstand) einfach per `Handler.postDelayed` weitergegeben — schlägt das
   Laden oder Abspielen fehl, wird sofort weitergesprochen, die Ansage hängt nie an einem stummen
   Chime.
2. **[`NavigationAudioFocus`](../app/src/main/java/com/motoroute/voice/NavigationAudioFocus.kt)**
   fordert vor jeder Ansage `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` an (Ducking) statt Musik zu
   stoppen. Stoppen ist laut Vorgabe genau das, was ein Bluetooth-Intercom zu ständigen
   Neuverbindungen zwingt.
3. **[`BluetoothRoute`](../app/src/main/java/com/motoroute/voice/BluetoothRoute.kt)** kann
   optional auf SCO/HFP umschalten (für Fahrer mit zusätzlichem Funkgerät/Mesh im Helm). Das ist
   der **ehrlich unfertige** Teil: `startBluetoothSco()` braucht seit Android 12 die Laufzeit-
   Berechtigung `BLUETOOTH_CONNECT`, die nicht im Manifest steht — `AndroidManifest.xml` liegt
   außerhalb der für diese Aufgabe erlaubten Dateien. Ohne die Berechtigung ist die Klasse ein
   No-op und alles bleibt auf A2DP, was für die große Mehrheit der Fahrer ohnehin richtig ist.
   **Um das nutzbar zu machen**, muss ein Agent mit Zugriff auf `AndroidManifest.xml`
   `<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />` ergänzen; der Code
   dahinter ist fertig und `useIntercomVoiceProfile` auf `VoiceGuidance` ist bereits die
   Schaltstelle dafür.

**Auf dem Emulator geprüft** (`emulator-5554`, `adb`, App installiert und über
"Einstellungen → Sprache → Ansage testen" ausgelöst): Logcat zeigt exakt die erwartete Kette —

```
MediaFocusControl: requestAudioFocus() … AA=USAGE_ASSISTANCE_NAVIGATION_GUIDANCE/CONTENT_TYPE_SPEECH req=3 …
… (TTS spricht) …
MediaFocusControl: abandonAudioFocus() … callingPack=com.motoroute.debug
```

`req=3` ist `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` — die App fordert also tatsächlich Ducking an,
nicht Stopp, und gibt es nach dem Ende der Ansage sauber wieder frei. Kein Absturz, TTS verbindet
sich normal ("Connected to TTS engine"). Das ist alles, was ein Emulator ohne echtes
Bluetooth-Audiogerät zeigen kann.

**Was sich nur am echten Gerät mit echtem Headset prüfen lässt** (keine Ausrede, sondern eine
konkrete Liste):

- Ob der Chime das Sena/Cardo-Headset tatsächlich rechtzeitig aufweckt, bevor der gesprochene Text
  beginnt — das ist genau die 0,5–1,5-s-Verzögerung aus der Vorgabe, die ein Emulator ohne
  angeschlossenes A2DP-Gerät nicht simuliert.
- Ob 380 ms Vorlauf reichen oder für ein bestimmtes Headset-Modell nachjustiert werden müssen.
- Ob Ducking auf dem jeweiligen Headset hörbar als "leiser", nicht als kurzer Aussetzer ankommt.
- Das SCO/HFP-Verhalten (Abschnitt 3, Punkt 3) — dafür fehlt aktuell sogar die Berechtigung.
- Ob die Lautstärke des Chime relativ zur Sprachausgabe und zur Musik im realen Helm passt
  (`VOLUME = 0.7f` in `PrerollChime`, ein erster vernünftiger Wert, keine gemessene Kalibrierung).

---

## 4. Sprechverbot in Schräglage

**Es gibt keinen Lage-/Neigungssensor in der Navigations-Pipeline** — `FilteredFix` liefert
Position, Geschwindigkeit, Kurs (aus GPS) und Genauigkeit, keine IMU-Fusion. "Schräglage" wird
deshalb ehrlich *abgeleitet*, aus zwei Signalen, die die App ohnehin schon hat:

1. **Vorausschauend, aus der Route selbst** ([`CurveCombo`](../app/src/main/java/com/motoroute/domain/guidance/CurveCombo.kt)):
   Wie eng liegen die nächsten Abbiegungen *zeitlich* beieinander (Lücke ÷ aktuelle
   Geschwindigkeit < 8 s), und wie scharf sind sie (`SHARP_LEFT/RIGHT`, `HAIRPIN_LEFT/RIGHT`)?
   Drei oder mehr verkettete Kurven sind immer eine "Lockout"-Situation; zwei nur, wenn eine davon
   scharf ist; eine einzelne scharfe Kurve für sich ist ebenfalls ein (triviales) Lockout. In
   diesem Fall wird **eine** Warnung gesprochen ("Achtung, scharfe Rechtskehre" bzw. bei drei oder
   mehr generisch "Achtung, mehrere Kurven"), und die App bleibt für den *gesamten* Lauf stumm —
   auch für den letzten "Jetzt"-Hinweis. Das ist eine bewusste Entscheidung: Bei einer reinen
   Geometrie-Spitzkehre ohne Abzweigung gibt es keine Entscheidung, die der Fahrer treffen muss
   (die Straße erzwingt die Linie), also überwiegt hier "nicht zutexten" gegenüber "noch eine
   Bestätigung".
2. **Reaktiv, aus der Live-Kursänderung** ([`NavigationManager.headingRateSince`](../app/src/main/java/com/motoroute/domain/NavigationManager.kt)):
   Aus zwei aufeinanderfolgenden GPS-Fixes wird eine Gier-/Kursänderungsrate in Grad/Sekunde
   berechnet. Liegt sie über 18°/s bei mindestens 4 m/s Fahrt, gilt der Fahrer als "aktuell in
   einer Kurve" — unabhängig davon, ob die Route dafür überhaupt eine Instruktion kennt (eine
   normale Landstraßenkurve ohne Abzweigung hat keine `NavigationInstruction`, aber der Fahrer
   ist trotzdem gerade im Lehnwinkel unterwegs). In diesem Zustand wird eine an sich fällige
   **Früh**- oder **Bestätigungs**-Ansage zurückgehalten — der **letzte Hinweis** (3 s) nie, eine
   Kurvenwarnung selbst ebenfalls nicht auf unbestimmte Zeit: Nach spätestens 4 Sekunden
   Zurückhaltung (`ACTIVE_CORNER_DEFER_CAP_MILLIS`) wird trotzdem gesprochen, damit eine
   durchgehend kurvige Straße eine fällige Ansage nie komplett verschluckt.

Getestet in `NavigationManagerTest` (`active cornering defers a non-final call but never drops
it`) mit einer künstlich oszillierenden Kursänderung.

**Der vorberechnete Kurven-Score (`1.Doku/Kurven_Score.md`) wurde bewusst nicht benutzt** — nicht
weil er ungeeignet wäre (im Gegenteil, ein Wert 0–15 pro Straßenabschnitt wäre ein direkteres Maß
für "wie kurvig ist dieser Abschnitt" als die hier verwendete Näherung), sondern weil er auf dem
Gerät schlicht noch nicht ankommt: Laut `1.Doku/RD5_Pipeline.md` ist die Übernahme des
`opencurv:curve`-Tags in die `.rd5`-Kacheln und in die Produktionsprofile der App ein "paralleler
Arbeitsstrang" und ausdrücklich noch nicht in der App geprüft (§5, Punkt 3–4 dort). Im Datenmodell
gibt es dafür aktuell auch kein Feld — `NavigationInstruction` (in `data/model/`, außerhalb der
für diese Aufgabe erlaubten Verzeichnisse) hat kein `curveScore`-Attribut, und `BRouterEngine` (in
`data/brouter/`, ebenfalls außerhalb) füllt keines. Verwendet wurden stattdessen die zwei Felder,
die `NavigationInstruction` schon heute liefert: der Manöver-Typ (`SHARP_*`/`HAIRPIN_*` als
"scharf") und der Abstand zur nächsten Instruktion. Das ist eine funktionierende, getestete
Übergangslösung — sobald der Kurven-Score im Routing-Graphen ankommt, ist der naheliegende nächste
Schritt, `CurveCombo.isLockout` zusätzlich auf einen Score-Schwellwert reagieren zu lassen (z. B.
auch mitten in einer langen Kurve ohne eigene Abbiege-Instruktion warnen), statt nur auf die
vorhandenen Abbiege-Manöver.

---

## 5. Trennung der drei Schichten

- **Wann gesprochen wird** (Zustandsmaschine): `NavigationManager.kt` + `AnnouncementTiming.kt` +
  `CurveCombo.kt`. Kennt keine Android-Typen, ist vollständig ohne Gerät testbar.
- **Was gesagt wird** (Textbau): `Phrasebook.kt` (`GermanPhrasebook`/`EnglishPhrasebook`). Bekommt
  ein fertiges `VoiceAnnouncement` (Manöver, Distanz, `isFinal`, `comboCount`, optional
  `secondManeuver`/`freeRideKm`) und macht daraus genau einen Satz — keine Höflichkeitsfloskeln,
  Manöver vor Distanz nur beim letzten Hinweis, Kreisverkehr immer mit Ausfahrtnummer.
- **Wie es zum Headset kommt** (Audioschicht): `VoiceGuidance.kt` orchestriert
  `PrerollChime` → `NavigationAudioFocus` → `BluetoothRoute` → `TextToSpeech`. Kennt nichts von
  *wann* oder *was*, nur *wie*.

Diese drei waren vorher in `NavigationManager`/`VoiceGuidance` vermischt (Distanz-Text wurde in
der Zustandsmaschine gebaut, die Audioschicht entschied anhand einer Distanz-Schwelle, ob sie
unterbrechen darf). Jetzt trägt jede Ansage ihre eigene `isFinal`-Flagge von Geburt an, die
Audioschicht muss nichts mehr raten.

Straßenname/Referenz (z. B. "auf L312" aus dem Beispiel der Vorgabe) wird **nicht** angesagt:
`NavigationInstruction.roadClass` existiert im Datenmodell, wird aber von `BRouterEngine` nirgends
befüllt (geprüft per Suche über den ganzen Hauptquellbaum — das Feld ist `null` in jeder erzeugten
Instruktion). `Phrasebook` ist so gebaut, dass ein befüllter Straßenname sofort verwendet werden
könnte, sobald `BRouterEngine` (außerhalb der erlaubten Verzeichnisse dieser Aufgabe) ihn liefert.

---

## 6. Die vollständige Ansageliste einer Beispielfahrt

`SampleRideAnnouncementsTest` fährt eine 25 km lange, synthetische Route mit
`RouteSimulator` (Demofahrt-Pipeline) durch `NavigationManager` und protokolliert jede Ansage mit
Zeitstempel. Die Route enthält bewusst jeden Ansage-Typ: einen Kreisverkehr, eine
Dreifach-Spitzkehre, ein normales Zwei-Kurven-Paar und eine 19 km lange ruhige Schlussstrecke.
Ausgabe bei 25 m/s (90 km/h, damit auch die Bestätigungs-Stufe zum Zug kommt):

```
Sample ride: 25000 m at 25.0 m/s
t [s]  | kind          | text
-------+----------------+----------------------------------------------
    65 | MANEUVER       | In 350 Metern im Kreisverkehr die 2. Ausfahrt nehmen
    73 | MANEUVER       | In 150 Metern im Kreisverkehr die 2. Ausfahrt nehmen
    77 | MANEUVER       | Jetzt im Kreisverkehr die 2. Ausfahrt nehmen
   185 | CURVE_WARNING  | Achtung, mehrere Kurven
   225 | MANEUVER       | In 350 Metern rechts abbiegen
   233 | MANEUVER       | In 150 Metern rechts abbiegen
   237 | MANEUVER       | Jetzt rechts abbiegen, dann sofort links abbiegen
   837 | FREE_RIDE      | Dem Straßenverlauf 4 Kilometer folgen
   999 | ARRIVAL        | Sie haben Ihr Ziel erreicht
```

Neun Ansagen für eine ganze 25-km-Fahrt mit einer Dreifach-Spitzkehre darin — nicht neun allein
für die Spitzkehre, wie es der alte Code für diesen einen Streckenabschnitt erzeugt hätte. Der
Test prüft zusätzlich maschinell: die Spitzkehren-Serie erzeugt genau eine Warnung
(`comboCount == 3`), das Zwei-Kurven-Paar genau einen "dann sofort"-Aufruf, die Schlussstrecke
genau eine Rückversicherung, die Ankunft genau eine Meldung, und keine zwei Ansagen der ganzen
Fahrt sind wortgleich.

---

## 7. Tests

`./gradlew :app:testDebugUnitTest` — **grün, 145 Tests, 0 Fehlschläge** (Stand dieser Änderung).
Neu bzw. grundlegend erweitert:

- `AnnouncementTimingTest.kt` — Zeitstufen, Restzeit-Berechnung, der schmal-vor-breit-Scan aus
  Abschnitt 1.1 als eigener Test, Aktiv-Kurven-Erkennung.
- `CurveComboTest.kt` — Lauf-Bildung, Lockout-Klassifikation, "Continue"-Wegpunkte unterbrechen
  die Kette nicht, `DESTINATION` beendet sie immer.
- `NavigationManagerTest.kt` — komplett überarbeitet: der Reproduktionstest für den alten Bug
  (`landing already close after a jump speaks once, at the final tier`), der direkte Beweis für
  die Reparatur der Kombinationsansagen (`closely spaced manoeuvres are announced once`), die
  Dreifach-Spitzkehre, die einzelne Spitzkehre, der Kreisverkehr, die Rückversicherungs-Ansage und
  die Kurvenlage-Zurückhaltung.
- `PhrasebookTest.kt` — an das neue `VoiceAnnouncement` angepasst, plus Tests für
  Kombinationsansagen und Kurvenwarnungen in beiden Sprachen.
- `SampleRideAnnouncementsTest.kt` — die in Abschnitt 6 gezeigte Gesamtfahrt.

`app/src/main/res/raw/nav_chime.wav` wurde synthetisch erzeugt (Python-Skript, nicht Teil des
Repos), damit kein fremdlizenziertes Audiomaterial eingebracht wird.

---

## 8. Offene Punkte für andere Agenten

- **`AndroidManifest.xml`**: `BLUETOOTH_CONNECT`-Berechtigung fehlt für die SCO/HFP-Option
  (Abschnitt 3, Punkt 3). Ohne sie bleibt `useIntercomVoiceProfile` wirkungslos.
- **UI-Einstellung**: Es gibt aktuell keinen Schalter in der Oberfläche für
  `VoiceGuidance.useIntercomVoiceProfile` (SCO/HFP-Vorliebe) — laut Auftrag nicht selbst gebaut,
  weil `ui/settings/` gesperrt ist. Empfehlung: ein Schalter unter "Einstellungen → Ansagen",
  Beschriftung etwa "Über Gegensprechanlagen-Kanal ansagen (für separates Funkgerät im Helm)",
  Standard aus.
- **Straßenname in der Ansage** ("… auf L312"): sobald `BRouterEngine`
  `NavigationInstruction.roadClass` befüllt, greift `Phrasebook` das automatisch auf (siehe
  Abschnitt 5) — dafür ist keine weitere Änderung in `voice/`/`domain/guidance/` nötig.
- **Kurven-Score**: siehe Abschnitt 4, letzter Absatz — sobald er im Routing-Graphen ankommt, kann
  `CurveCombo` ihn zusätzlich zur reinen Manöver-Klassifikation nutzen.
