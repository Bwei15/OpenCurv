# OpenCurv

An offline, open-source motorcycle navigator for Android that routes for
**curves**, not for arrival time.

No account, no cloud, no tracking. **Riding is completely offline**: maps,
routing, rerouting and voice guidance all run on files stored on the phone, and
your position never leaves the device.

The app does have the `INTERNET` permission, for exactly one job — downloading
those files in the first place, so you do not have to move hundreds of megabytes
from a PC. That traffic is confined by an Android
[network security config](app/src/main/res/xml/network_security_config.xml) to
the two servers the data comes from, enforced by the platform rather than by our
own code, and downloads are refused while navigation is running.

---

## What it does

- **Destinations without a network.** Type a town, a village or a street and
  OpenCurv finds it in the map already on the phone - Mapsforge maps carry the
  place nodes and street names they draw with, which is exactly the index an
  offline geocoder needs. Towns and villages come from a two-pass scan built
  once per map and cached next to it; streets and fuel stations are scanned live
  around wherever you are looking. Tapping the map still works, and a long press
  sets where the route starts, so a ride can be planned indoors with no GPS.
- **Curve-hungry routing.** BRouter runs on-device against `.rd5` routing tiles
  with purpose-built motorcycle profiles that make motorways and trunk roads
  expensive, keep the turn penalty near zero so a winding road is not punished
  for winding, and charge for every hop between road classes. Optionally it
  calculates BRouter's alternatives too and keeps the twistiest one that is not
  an absurd detour.
- **A cockpit while riding, an app while standing.** The riding HUD keeps its
  104 dp maneuver arrow, 56 sp distance and 84 dp controls for gloves on a bumpy
  road. The screens used with the engine off - downloads, settings, the region
  list - use 56 dp controls and a back button at the top, because that is where
  a back button belongs when both hands are free.
- **A map you can actually read.** Colour day and night cartography with the
  road classes coloured the way every driving map colours them, woodland green,
  water blue and place names from city down to hamlet - plus the original
  maximum-contrast pair, one tap away in settings, for low sun. Your own
  position is a heading puck, the destination a pin.
- **A map that stays where you put it.** Pan or pinch and the map stops
  following you; the recentre button lights up until you tap it. No more being
  dragged back a second after you moved.
- **A demo ride.** Calculate a route, tap *Demo ride*, and the app drives it on
  screen through the ordinary navigation pipeline: the HUD, the countdown, the
  rerouting logic and every spoken announcement, at the kitchen table.
- **German and English.** The interface and the announcements follow the phone's
  language ("In dreihundert Metern rechts abbiegen"), with English for
  everything else, and a button in settings that simply says one out loud.
- **Turn-by-turn that fits a motorcycle.** Announcements at 1000 m, 300 m and
  50 m; hairpins get their own icon and their own warning; "left, then
  immediately right" is announced as one instruction.
- **Rerouting that stays out of the way.** Off-route past 35 m for three
  consecutive fixes triggers a background recalculation with a cooldown and a
  failure backoff, so a ride through a car park does not recalculate every
  second and a ride off the edge of the imported tiles does not drain the
  battery trying.
- **GPS that survives a handlebar mount.** A constant-velocity Kalman filter
  over a local tangent plane smooths position, speed and heading, and the
  position is snapped onto the route by a windowed map matcher that will not
  teleport across a hairpin.
- **Speed limits without a network.** The routing profiles reference the OSM
  `maxspeed` tag, which makes BRouter carry it into the calculated track;
  OpenCurv reads it back out for the HUD. No speed database, no lookups.
- **Regions, not files.** Pick "Niedersachsen" and OpenCurv fetches the
  Mapsforge map *and* works out which BRouter routing tiles cover it — the part
  of setting up an offline navigator that everyone gets wrong by hand is pure
  arithmetic, so the app does it. It downloads as one package with one progress
  bar, appears as one row, and deletes as one package — keeping any routing tile
  a neighbouring region still needs. Downloads resume after a dropped connection
  and survive the screen locking.
- **Volume keys zoom the map**, and the screen never sleeps while the app is up.

## What it does not do

- **No house numbers, no postcodes.** Search finds places and street names, not
  addresses: a Mapsforge map carries the labels it draws, and house numbers are
  not among them at any useful zoom.
- **Street search is local.** Streets are scanned around where the map is
  looking, not indexed for a whole federal state — a street name is only a
  useful destination when it is a nearby one.
- **No street names in the HUD.** BRouter's `.rd5` tiles do not carry them.
- **The 3-D tilt is a projective transform**, not a 3-D renderer. Mapsforge
  draws in 2-D; the ~50° perspective is applied to the rendered view, which
  gives the depth cue but leans the labels with it. It can be switched off.

## Getting the offline data

OpenCurv ships **no** map data. There are two ways to get it.

### In the app (the easy way)

On the first start OpenCurv asks in three steps and then puts you in the region
list; later it is the layers button → **Download maps** → pick a region.
OpenCurv queues the Mapsforge map and every BRouter tile that covers it, one
file at a time, and reports the package rather than the files. Do this on Wi-Fi:
a German federal state is 100–400 MB of map plus 50–150 MB per routing tile, so
budget 1–2 GB for a comfortable riding area.

A region is deleted the same way it arrived: one row, one button, one
confirmation — and routing tiles another installed region still needs stay.

Downloads resume where they left off if the connection drops, keep running while
the screen is off, and refuse to start while you are navigating.

The region list lives in
[`app/src/main/assets/catalog/regions.json`](app/src/main/assets/catalog/regions.json)
— a plain file you can extend with any region the two servers carry, without
touching code.

### From a PC (the fallback)

If you already have the files, or want a region the catalog does not list:

1. **A Mapsforge map** (`.map`) from
   [download.mapsforge.org](https://download.mapsforge.org/), or built yourself
   with the Mapsforge map writer.
2. **BRouter routing tiles** (`.rd5`) — the 5° × 5° tiles covering your region
   from [brouter.de/brouter/segments4](https://brouter.de/brouter/segments4/).
   Bavaria, for instance, needs `E5_N45`, `E10_N45`, `E5_N50` and `E10_N50`.
   Fetch them fresh: every tile carries the version of the tag table it was
   built against (currently 11, see the head of
   [`assets/profiles/lookups.dat`](app/src/main/assets/profiles/lookups.dat)),
   and a tile kept from an older build cannot be read — routing then fails with
   a lookup version mismatch until the tile is downloaded again.

Copy them onto the phone, then Layers → **Import from this device**. Files are
copied into the app's own storage, so they survive reboots and need no storage
permission.

### What the app may talk to

Only `download.mapsforge.org` and `brouter.de`, only over HTTPS, and only when
you ask for a download. The host list is compiled in, mirrored in the network
security config, and re-checked on every hop of a redirect chain — so neither a
stale catalog entry nor a redirect can send the app somewhere else.

## Building

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # minified, signed with the debug key
```

Requires JDK 17 and the Android SDK (compileSdk 35). CI builds both APKs on
every push and uploads them as workflow artifacts — see
[`.github/workflows/android.yml`](.github/workflows/android.yml).

### Testing the logic without the Android SDK

Everything that can go subtly wrong — the routing profiles, BRouter integration,
the Kalman filter, map matching, the turn-by-turn state machine, the region
index, the place search and the announcement wording — is written without
Android imports, so it can be compiled and tested on a plain JVM:

```bash
gradle --project-dir tools/verifier test
```

This build reads the app's own sources directly; there is no copy to drift out
of date. The same tests also run as Android unit tests via
`./gradlew testDebugUnitTest`.

## Tuning for a 4 GB phone (e.g. Galaxy A16)

The defaults are already set for this class of device:

| Setting | Value | Why |
|---|---|---|
| BRouter node cache | 48 MB (`NavigationController.MEMORY_CLASS_MB`) | Enough for a day-long route across several tiles without starving the renderer |
| Tile cache | 1.5× screen (`MapController.SCREEN_RATIO`) | On 4 GB the renderer competing with BRouter for heap is what causes stutter, not a cache miss |
| `android:largeHeap` | `false` | A large heap makes GC pauses longer, which is worse than a smaller cache |
| ABI filters | `armeabi-v7a`, `arm64-v8a` | Smaller APK, no unused native code |
| Release build | R8 minify + resource shrinking | Faster cold start on a mid-range SoC |

If routing across a large area is slow, import fewer `.rd5` tiles rather than
raising the memory class: BRouter's search cost scales with the area it has to
consider.

## Architecture

```
app/src/main/java/com/motoroute/
├── data/
│   ├── brouter/     BRouterEngine (offline routing), ProfileManager (.brf assets)
│   ├── download/    Region catalog, tile arithmetic, resumable HTTPS
│   │                downloader, the region-package index
│   ├── search/      Offline place search read out of the Mapsforge maps
│   ├── location/    LocationProvider (platform GPS, no Play Services), KalmanFilter
│   ├── map/         OfflineDataRepository (.map / .rd5 / .brf on disk)
│   ├── model/       Route, NavigationInstruction, Maneuver, Curviness
│   └── settings/    SettingsRepository
├── domain/
│   ├── geo/         Geo (distance, bearing, cross-track projection)
│   ├── MapMatcher       windowed projection onto the route
│   ├── NavigationManager  turn-by-turn state machine
│   ├── ReroutingEngine    off-route detection, single-flight, backoff
│   ├── CameraController   speed to zoom and tilt
│   ├── RouteSimulator     the demo ride, feeding the real pipeline
│   ├── guidance/          what the voice says, per language
│   └── NavigationController  wires location -> state machine -> voice
├── service/         NavigationService (location), DownloadService (data sync)
├── voice/           VoiceGuidance (platform TTS)
└── ui/              Compose: map, navigation HUD, route planning, search,
                     onboarding, settings, offline data
brouter/             vendored BRouter core (MIT) + one bridge class
tools/verifier/      JVM-only build that tests the core without the Android SDK
```

Navigation state lives in a process-scoped controller rather than a ViewModel,
so rotating the phone on the handlebar cannot interrupt a ride.

## Routing profiles

Three profiles ship in `app/src/main/assets/profiles/`:

| Profile | For |
|---|---|
| `motorcycle_curvy.brf` | The default. Hunts bends, avoids main roads, paved only |
| `motorcycle_fast.brf` | Direct route, motorways allowed |
| `motorcycle_enduro.brf` | Allows gravel, tracks and unpaved surfaces |

All three expose a `curviness` parameter (0 = direct, 2 = maximum curves) that
the "curve appetite" slider drives at runtime. They are plain BRouter profiles:
edit them, or import your own `.brf`, and the app picks it up.
`RoutingProfileTest` parses them with the real BRouter expression engine on every
build and asserts they still say what they claim — a typo in a profile does not
fail a build, it quietly sends you down the motorway.

## Licence

GPL-3.0 — see [`LICENSE`](LICENSE). Third-party components and map data
attribution are listed in
[`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md).

Map data © OpenStreetMap contributors, ODbL.
