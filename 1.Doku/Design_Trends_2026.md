# Design-Leitlinie für Motorrad-Navigations-App 2026
## Basiert auf Material 3 Expressive, iOS Liquid Glass & modernen Apps

---

## 1. Trend-Überblick

**Stand September 2026:** Moderne Apps vereinen drei Paradigmen:

1. **Material 3 Expressive (Google):** Spring-basierte Motion, 10-stufige Shape-Skala, HCT-Farbraum
2. **Liquid Glass (Apple):** 30–40px Backdrop-Blur, intelligente Rundungen, GPU-Lensing-Effekte
3. **Konsistenz-Merkmale:** Weiche Schatten statt Linien, Glasmorphismus, Bento-Grids, AI-native Gesten

**Für Motorrad-Apps kritisch:** Größere Touch-Targets (Handschuhe), High-Contrast-Readability, Vibrations-Feedback, Voice-Integration.

---

## 2. Design-Tokens (Konkrete Werte)

### Corner-Radius-Stufen (dp)
| Stufe | Wert | Use-Case |
|-------|------|----------|
| extraSmall | 4dp | Toast, Chip-Label |
| small | 8dp | Icon-Button, mini-Card |
| medium | 12dp | Card, TextField |
| large | 16dp | Dialog, FAB-Container |
| extraLarge | 28dp | **Bottom-Sheet, Modal-Dialog** |
| Pill | 50dp | FAB-Button, Badge-Notification |

**iOS-Anpassung:** Continuous-Corner-Radius verwenden; 16pt für Hauptcontainer.

---

### Elevation & Shadow-Stufen (dp)
| Level | Elevation | Shadow-Charakteristik | Use-Case |
|-------|-----------|----------------------|----------|
| 0 | 0dp | Keine | Flat-Surface |
| 1 | 3dp | 30% Blur, offset 1dp | Bottom-Sheet (modal) |
| 2 | 6dp | 40% Blur, offset 2dp | Card, Floating-Action |
| 3 | 8dp | 50% Blur, offset 3dp | Dialog, Elevation-Peak |
| Glass-Layer | – | **40px Blur + Saturation +20%** | Notification-Banner |

**Schatten-Syntax Material 3:** Soft-Shadow (weich, kein scharfer Rand)

---

### Spacing-Raster (4dp-Basis)
| Token | Wert | Beispiel |
|-------|------|----------|
| xs | 4dp | Icon-Padding in Button |
| sm | 8dp | Element-Spacing, Innenabstand |
| md | 16dp | **Card-Padding, Listenabstand** |
| lg | 24dp | Screen-Padding, große Abstände |
| xl | 32dp | Abschnitt-Trennung |

---

### Typografie-Skala (sp für Text, dp für Höhen)
| Style | Größe/Höhe | Line-Height | Letter-Spacing | Use-Case |
|-------|-----------|-------------|-----------------|----------|
| displayLarge | 57sp / 68dp | 64sp | -0.25sp | Route-Info |
| headlineLarge | 32sp / 40dp | 40sp | 0sp | Destination-Titel |
| headlineSmall | 24sp / 32dp | 32sp | 0sp | Stop-Name, Straße |
| titleMedium | 16sp / 20dp | 24sp | 0.15sp | Chip-Text, Zeit |
| bodyLarge | 16sp / 20dp | 24sp | 0.5sp | Reisedauer, Notizen |
| labelLarge | 14sp / 18dp | 20sp | 0.1sp | **Button-Text, Icon-Label** |

---

### Icon-Größen (dp)
| Kontext | Größe | Padding | Touch-Target |
|---------|-------|---------|---------------|
| Small (Chip, Badge) | 16dp | 4dp | 24dp (min) |
| Standard (Button, Toolbar) | 24dp | 8dp | **40dp (ruhig)** |
| Large (FAB, Navigations-HUD) | 56dp | 16dp | **56–72dp (Fahrt)** |
| Kreisverkehr-Icon (Roadside) | 48–64dp | 12dp | Min 56dp (Handschuhe!) |

---

### Touch-Target-Größen
| Zustand | Größe | Kontext |
|---------|-------|---------|
| Ruhig (Fußgänger, statisch) | min. 44×44dp | Buttons, Chips |
| **Fahrt-Modus** | **min. 56×56dp** | Stop-Löschen, Benachrichtigung |
| Handschuh-Bedienung | 72×72dp | Notfall-Button, Pause-Stop |

---

## 3. Motion-Spezifikation (Material 3 Expressive)

### Spring-Parameter
```
Standard-Motion (z. B. Panel-Öffnen):
  stiffness = 600    // Schnelligkeit
  damping = 90       // Elastizität/Bounce
  mass = 1.0         // Physikalisches Gewicht
  duration = 300–500ms

Entertain-Motion (z. B. FAB-Puls):
  stiffness = 100    // Langsamer, federnder
  damping = 12
  mass = 0.5         // Leichter
  duration = 800–1200ms
```

### Dauer-Standards
- **Micro-Interaction** (Tap-Feedback, Icon-Change): 100–200ms
- **Component-Open** (Bottom-Sheet, Dialog): 300–500ms
- **Entrance-Animation** (Screen-Transition): 400–600ms
- **Exit-Animation**: 200–400ms

### Easing (Material 3 Expressive)
- **Standard:** Spring-Physics (stiffness/damping oben)
- **Fallback:** `CubicBezier(0.2, 0.0, 0.0, 1.0)` (Material-Curve)
- **Entrance:** `CubicBezier(0.0, 0.0, 0.2, 1.0)`

---

## 4. Konkrete UI-Komponenten

### A) Bottom-Sheet (Stop-Liste, Suchergebnisse)
```
✓ Corner-Radius:     28dp (oben)
✓ Elevation:         8dp (soft shadow)
✓ Backdrop-Blur:     20px (optional Glass-Layer)
✓ Padding:           16dp (md)
✓ Öffnungs-Anim:     Spring (600/90/1.0) 400ms
✓ Drag-Handle:       4dp × 48dp Pille, Farbe systemGray3
✓ Typografie:        titleMedium für Header, bodyLarge für Items
```

### B) Stop-Liste als Tiles (mit Drag-Handle + Lösch-X)
```
✓ Tile-Höhe:        56–64dp (extraSmall) oder 80dp (groß)
✓ Corner-Radius:     12dp
✓ Padding:           12dp (sm) Innen
✓ Spacing:           8dp zwischen Elementen
✓ Drag-Handle:       Links, 24dp Icon (grau), 8dp Abstand
✓ Löschen-Button:    Rechts, X-Icon 24dp, rot/orange
✓ Swipe-Geste:       Slide-to-Delete Anim 200ms
✓ Schrift:           headlineSmall (Stop-Name), labelLarge (Adresse)
✓ Icon (Marker):     32dp, Nummer oder Location-Pin
```

### C) Such-Feld
```
✓ Höhe:              56dp (Standard)
✓ Corner-Radius:     16dp (large)
✓ Padding:           12dp horizontal, 8dp vertikal
✓ Border:            Keine Linie, Elevation 1dp shadow stattdessen
✓ Placeholder-Text:  titleSmall, systemGray2
✓ Icon:              24dp Search-Icon, linksseitig
✓ Clear-Button:      X-Icon, nur bei Eingabe (Fade-In 150ms)
✓ Backdrop:          Optional semi-transparent Blur (15px) beim Fokus
```

### D) Notifizierungs-Banner (Pill/Dynamic-Island-Stil)
```
✓ Höhe:              56–64dp
✓ Corner-Radius:     50dp (Pill-Shape)
✓ Padding:           12dp horizontal, 8dp vertikal
✓ Elevation:         3dp + Glass-Layer (40px Blur, +20% Saturation)
✓ Animation:         Slide-In von oben 300ms spring
✓ Auto-Dismiss:      4s Verzögerung, dann Slide-Out 200ms
✓ Typografie:        labelLarge, Bold für Action
✓ Icon:              24dp, Links
✓ Action-Button:     56×56dp min., Tap-Anim 100ms
```

### E) Navigations-HUD-Banner (oben auf Map)
```
✓ Breite:            100% (Screen)
✓ Höhe:              72dp (Kopfzeile) + ggf. 80dp Lane-Guidance
✓ Corner-Radius:     0dp (oben), 16dp (unten) – Material 3 Sheet
✓ Padding:           12dp md
✓ Elevation:         2dp
✓ Typografie:        
   - Nächster Schritt: headlineSmall (Richtung + Straße)
   - Distanz:         displayLarge (72dp größte Nummer)
   - Zeit:            bodyLarge
✓ Icons:             
   - Abbiegung:       48–56dp, Schwarz/Weiß je Theme
   - Lane:            48×48dp je Lane
✓ Background:        SystemBackground + 5% Overlay
✓ Animation:         Fade-In beim Ziel-Update 200ms
```

### F) Kreisverkehr-Icon (speziell)
```
✓ Größe:             48–64dp (größer = besser bei Fahrt)
✓ Corner-Radius:     Kreis (50% + mathematische Aussenrundung)
✓ Stroke:            2–3dp, kontrastreich
✓ Innenlabel:        Ausfahrts-Nummer, labelMedium Bold
✓ Background:        Primär-Farbe oder systemGray4
✓ Animation:         Pulse-Effekt bei aktiver Navigation (300–600ms Spring)
✓ Shadow:            1–2dp für Tiefe
```

---

## 5. Was eine App "unfertig" wirken lässt – und wie man es behebt

| Problem | Symptom | Lösung |
|---------|---------|--------|
| **Fehlende Micro-Interactions** | Buttons fühlen sich tot an | Tap-Feedback: 100ms Skalierung (0.95x), Vibration |
| **Inkonsistente Motion** | Manche Panels schnell, andere langsam | Alle Animationen auf Spring-Parameter standardisieren |
| **Scharfe Linien statt Schatten** | Wirkt "flach" und billig | Weiche Schatten (40% Blur-Offset) + Elevation-System |
| **Zufällige Corner-Radius** | 4dp hier, 20dp da, 8dp dort | Einheitlich: 4/8/12/16/28dp-Skala verwenden |
| **Fehlende Voice-Kontrolle** | Modern wirken → unvollendet | Voice-Input für Suche + Stop-Liste (Android 12+) |
| **Keine Vibration-Choreografie** | Feedback vage/monoton | Unterschiedliche Muster: Short (50ms) = Fehler, Long (200ms) = Bestätigung |
| **Zuviel Farbe, keine Rollen** | Chaotisch | HCT-Farbraum: Primär, Secondary, Tertiary, Error mit konsistentem Tone |
| **Breakpoint-Sprünge** | Adaptive Layout ruckelt | Smooth-Transition bei Orientation-Change (300ms Spring) |
| **Icons ohne Consistenc-Weight** | Dünn/Fett gemischt | Alle Icons auf 24dp @ 2dp Stroke standardisieren |
| **Fehlende Keyboard-Kurzbefehle** | Fummelig-wirkend | Voice + Hardware-Tasten (Play/Pause für Stop) |

---

## 6. Implementation-Checkliste (Kotlin + Jetpack Compose + Material3)

- [ ] **Theme:** `MaterialTheme { ... }` mit HCT-generierter `colorScheme`
- [ ] **Shapes:** `shapes = Shapes(extraSmall = 4.dp, small = 8.dp, ..., extraLarge = 28.dp)`
- [ ] **Typography:** Alle 12 Stile aus M3 definiert (`displayLarge` bis `labelSmall`)
- [ ] **Elevation:** `surface.copy(alpha = 0.95f)` + `shadow { ... }` für 1–3dp
- [ ] **Motion:** Alle Animationen mit `Animatable` + Spring-Easing
- [ ] **Touch-Targets:** `Modifier.minimumInteractiveComponentSize(48.dp)` für ruhig, 56dp für Fahrt
- [ ] **Voice:** `SpeechRecognizer` Integration für Ziele + Sprachausgabe (TTS)
- [ ] **Vibration:** `HapticFeedback` Pattern-Definition (Short/Medium/Long)

---

## 7. Quellen & Referenzen

- [Material 3 Tokens System (seenode, 2025)](https://seenode.com/blog/what-is-material-3-and-why-it-matters-in-2025)
- [Google Material 3 Design Breakdown (superdesign.dev, 2026)](https://superdesign.dev/blog/material-design-system)
- [Material 3 Expressive (Google I/O 2025)](https://io.google/2025/explore/technical-session-24/)
- [Liquid Glass iOS 26 (Medium)](https://medium.com/@expertappdevs/liquid-glass-2026-apples-new-design-language-6a709e49ca8b)
- [Design for Driving (Google, 2026)](https://developers.google.com/cars/design/create-apps/apps-for-drivers/templates/navigation-template)
- [Material 3 in Jetpack Compose (Android Developers)](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [App Design Trends 2026 (muzli.com)](https://muz.li/blog/whats-changing-in-mobile-app-design-ui-patterns-that-matter-in-2026/)
- [Micro-Interactions & Motion 2026 (acodez.in)](https://acodez.in/micro-interactions-motion-design/)
- [Liquid Glass Reference (GitHub)](https://github.com/conorluddy/LiquidGlassReference)

---

**Gültig ab:** September 2026 | **Nächste Review:** Q1 2027
