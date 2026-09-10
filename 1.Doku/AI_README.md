Hier eine ausführliche BEschreibung des Workspaces für KI:

"ausfüllen!"


---

Aufgaben:

1. Das geplante Projekt lässt sich als vollständig autarkes, datenschutzfreundliches und kostenloses Motorrad-Navigationssystem realisieren, indem anspruchsvolle Vorberechnungen in eine automatisierte Cloud-Pipeline ausgelagert werden und das Endgerät ausschließlich für die performante Offline-Nutzung zuständig ist. Da ein eigener Server mit laufenden Kosten und Wartungsaufwand vermieden werden soll, übernimmt GitHub Actions die Rolle der Verarbeitungszentrale: Regelmäßige automatisierte Workflows laden freie Rohdaten von OpenStreetMap über Geofabrik herunter, verschmelzen diese mit digitalen Höhenmodellen wie Copernicus DEM und erzeugen daraus zwei elementare Artefakte, die anschließend als komprimierte Binärdateien über GitHub Releases mit bis zu zwei Gigabyte Dateigröße kostenlos bereitgestellt werden.
Das erste Artefakt ist der optimierte Routing-Graph, für dessen Erstellung Frameworks wie GraphHopper oder Valhalla zum Einsatz kommen. Hier findet die eigentliche Kurvenanalyse statt, die Calimoto ebenbürtig oder überlegen sein soll: Anstatt lediglich stumpf die Reisezeit zu minimieren, wird jeder Straßenabschnitt anhand von Winkeländerungen der Koordinaten, dem Auftreten von dynamischen S-Kurven-Wechselschwüngen, Steigungsprofilen und Umgebungspolygonen wie Wäldern oder Gewässern mit einem quantitativen Fahrspaß-Score versehen. Gleichzeitig werden Zickzack-Kurven in geschlossenen Ortschaften, stumpfe 90-Grad-Kreuzungsabbieger sowie schlechte Straßenbeläge algorithmisch abgewertet oder gesperrt. Ergänzend dazu lässt sich auf GitHub ein Makro-Raster auf Basis des Hexagon-Systems Uber H3 berechnen, das regionale Kurven-Hotspots wie Mittelgebirge zusammenfasst, wodurch die App später ohne Rechenaufwand attraktive Zielregionen für automatische Rundtouren identifizieren kann.
Das zweite Artefakt betrifft die Kartendarstellung. Entgegen dem veralteten Ansatz, fertige Rasterbilder auf GitHub zu rendern, werden dort mithilfe von Tools wie Planetiler oder Tilemaker lediglich kompakte Vektorkacheln im MBTiles-Format generiert, die Straßen und Landschaften als mathematische Vektoren abbilden. Das tatsächliche Zeichnen der Karte geschieht erst auf dem Smartphone über das Framework MapLibre Native: Dessen Rendering-Engine nutzt die GPU des Geräts, um die Vektordaten auf Basis einer zentral vorgegebenen MapLibre-Style-JSON flüssig mit 60 Bildern pro Sekunde darzustellen. Dies ermöglicht eine stufenlose 3D-Perspektive in Fahrtrichtung mit stets lesbaren Straßennamen, erlaubt einen dynamischen Wechsel zwischen kontraststarkem Tag- und Nachtmodus und visualisiert vorprozessierte Kurven-Hotspots auf Wunsch farblich hervorgehoben direkt im Kartenbild.Das geplante Projekt lässt sich als vollständig autarkes, datenschutzfreundliches und kostenloses Motorrad-Navigationssystem realisieren, indem anspruchsvolle Vorberechnungen in eine automatisierte Cloud-Pipeline ausgelagert werden und das Endgerät ausschließlich für die performante Offline-Nutzung zuständig ist. Da ein eigener Server mit laufenden Kosten und Wartungsaufwand vermieden werden soll, übernimmt GitHub Actions die Rolle der Verarbeitungszentrale: Regelmäßige automatisierte Workflows laden freie Rohdaten von OpenStreetMap über Geofabrik herunter, verschmelzen diese mit digitalen Höhenmodellen wie Copernicus DEM und erzeugen daraus zwei elementare Artefakte, die anschließend als komprimierte Binärdateien über GitHub Releases mit bis zu zwei Gigabyte Dateigröße kostenlos bereitgestellt werden.
Das erste Artefakt ist der optimierte Routing-Graph, für dessen Erstellung Frameworks wie GraphHopper oder Valhalla zum Einsatz kommen. Hier findet die eigentliche Kurvenanalyse statt, die Calimoto ebenbürtig oder überlegen sein soll: Anstatt lediglich stumpf die Reisezeit zu minimieren, wird jeder Straßenabschnitt anhand von Winkeländerungen der Koordinaten, dem Auftreten von dynamischen S-Kurven-Wechselschwüngen, Steigungsprofilen und Umgebungspolygonen wie Wäldern oder Gewässern mit einem quantitativen Fahrspaß-Score versehen. Gleichzeitig werden Zickzack-Kurven in geschlossenen Ortschaften, stumpfe 90-Grad-Kreuzungsabbieger sowie schlechte Straßenbeläge algorithmisch abgewertet oder gesperrt. Ergänzend dazu lässt sich auf GitHub ein Makro-Raster auf Basis des Hexagon-Systems Uber H3 berechnen, das regionale Kurven-Hotspots wie Mittelgebirge zusammenfasst, wodurch die App später ohne Rechenaufwand attraktive Zielregionen für automatische Rundtouren identifizieren kann.
Das zweite Artefakt betrifft die Kartendarstellung. Entgegen dem veralteten Ansatz, fertige Rasterbilder auf GitHub zu rendern, werden dort mithilfe von Tools wie Planetiler oder Tilemaker lediglich kompakte Vektorkacheln im MBTiles-Format generiert, die Straßen und Landschaften als mathematische Vektoren abbilden. Das tatsächliche Zeichnen der Karte geschieht erst auf dem Smartphone über das Framework MapLibre Native: Dessen Rendering-Engine nutzt die GPU des Geräts, um die Vektordaten auf Basis einer zentral vorgegebenen MapLibre-Style-JSON flüssig mit 60 Bildern pro Sekunde darzustellen. Dies ermöglicht eine stufenlose 3D-Perspektive in Fahrtrichtung mit stets lesbaren Straßennamen, erlaubt einen dynamischen Wechsel zwischen kontraststarkem Tag- und Nachtmodus und visualisiert vorprozessierte Kurven-Hotspots auf Wunsch farblich hervorgehoben direkt im Kartenbild.



2. Sprachausgabe verbessern. Dies ekommt oft mehrfach und ist nicht richtig durchdacht.:
Bei einer Motorrad-Navigation steht die Sprachausgabe vor völlig anderen Herausforderungen als im Auto: Windgeräusche, Motorlärm, Helm-Gegensprechanlagen (Intercoms via Bluetooth), dicke Handschuhe und ein eingeschränktes Sichtfeld. Wenn eine Ansage unverständlich ist, kann der Fahrer nicht einfach kurz aufs Display tippen.
Folgende Kernaspekte müssen bei Audio-Engine, Timing, Textaufbau und Bluetooth-Handling beachtet werden:
1. Bluetooth-Audio & Intercom-Latenzen (Der größte Stolperstein)
Helm-Headsets (Sena, Cardo etc.) fallen im Ruhezustand in einen Energiesparmodus.
Das Problem: Wenn die App eine Ansage startet, braucht das Headset 0,5 bis 1,5 Sekunden, um die Bluetooth-Audiospur zu öffnen. Sagt die App „In 200 Metern links abbiegen“, hört der Fahrer nur noch: „...links abbiegen“ – die Distanz wird verschluckt.
Die Lösung:
Audio-Ducking: Hintergrundmusik leiser dimmen, statt sie komplett zu stoppen (verhindert ständige Reconnects).
Audio-Preroll (Padding): Spiele vor jeder Ansage eine halbe Sekunde absolute Stille oder einen kurzen, prägnanten Signalton (Chime/Beep) ab. Der Ton weckt das Headset auf, und der eigentliche Text wird vollständig übertragen.
SCO vs. A2DP: Die App sollte standardmäßig High-Quality-Audio (A2DP) nutzen, aber für reine Sprachanweisungen fallweise das Hands-Free-Profil (SCO/HFP) unterstützen, falls Nutzer parallel ein Funkgerät oder Mesh-Netzwerk im Helm betreiben.
2. Akustische Informationsarchitektur (Formulierung der Ansagen)
Im Auto schaut man zur Not aufs Display. Auf dem Motorrad muss die Ansage ein klares, unmissverständliches mentales Bild erzeugen.
Das Wichtigste zuerst:
Schlecht: „Um auf der L312 zu bleiben, biegen Sie bitte in 300 Metern rechts ab.“
Richtig: „In 300 Metern rechts abbiegen – auf L312.“
Kurz und kommandobasiert: Füllwörter komplett streichen. Keine Höflichkeitsfloskeln („Bitte jetzt abbiegen“ → „Jetzt links“).
Relative vs. absolute Ansagen bei Kurven/Kreuzungen:
Bei Kreisverkehren immer die Ausfahrtnummer nennen: „Im Kreisverkehr die zweite Ausfahrt nehmen.“
Bei mehrspurigen Straßen immer Fahrspuren ansagen: „Rechts halten, dann sofort links einordnen.“
3. Dynamisches Timing nach Geschwindigkeit
Ein festes Timing (z. B. immer 300 m vor der Kreuzung ansagen) funktioniert auf dem Motorrad nicht.
Innerorts (50 km/h = ~14 m/s):
Vorankündigung: 150–200 m vorher.
Letzter Abbiegehinweis: 20–30 m vorher.
Landstraße (100 km/h = ~28 m/s):
Vorankündigung: 600–800 m vorher.
Bestätigung: 200 m vorher.
Letzter Abbiegehinweis: 50–70 m vorher (wegen Reaktions- und Bremsweg mit Schräglage).
Regel: Berechne den Ansage-Trigger zeitbasiert (z. B. immer 15 Sekunden und 3 Sekunden vor dem Manöver), nicht rein distanzbasiert.
4. Besonderheit Motorrad: Kurvenwarnungen & Fahrdynamik
Da du ein Kurven-Navigationssystem baust, darf die Sprachausgabe nicht stören:
Sprechverbot in Schräglage: Wenn eine schnelle Kurvenkombination oder Kehre gefahren wird, darf das System den Fahrer nicht mit Ansagen über das nächste Dorf zutexten. Die Konzentration gehört der Linie.
Gefährliche Kurven ankündigen (Opt-in): Straßen mit plötzlicher Verengung des Radius (Hundskurven) oder Serpentinen nach schnellen Geraden können akustisch gewarnt werden: „Achtung, scharfe Rechtskehre.“
Routenbestätigung bei freier Fahrt: Wenn 15 km keine Abzweigung kommt, sorgt eine kurze Bestätigung („Dem Straßenverlauf 12 Kilometer folgen“) für Beruhigung, ohne dass man aufs Display schauen muss.
5. Technischer Stack: TTS (Text-to-Speech)
Auch hier gilt: Vollständig offline und datenschutzfreundlich.
System-TTS nutzen: Greife auf die nativen Text-to-Speech-Engines von Android (TextToSpeech) bzw. iOS (AVSpeechSynthesizer) zu.
Vorteil: Kostet nichts, funktioniert komplett offline, nutzt die vom Anwender bevorzugte Stimme und Sprache und erfordert keine externen Audio-Streaming-Server.


3. Design. Es soll modern, etwas verspielt wirken. Dabei soll sich an den modernen Naviagtiosn diensten orientiert werden wie Apple und Googel.
