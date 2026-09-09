# Third-party components

OpenCurv itself is licensed under the GNU General Public License v3.0 (see
[`LICENSE`](LICENSE)). It builds on the following work.

## Bundled source

| Component | Version | Licence | Where |
|---|---|---|---|
| [BRouter](https://github.com/abrensch/brouter) routing core (`brouter-core`, `brouter-mapaccess`, `brouter-expressions`, `brouter-util`, `brouter-codec`) | 1.7.7 | MIT | `brouter/src/main/java/btools/**`, licence text in `brouter/LICENSE.brouter.txt` |
| BRouter `lookups.dat` tag table | 1.7.7 | MIT | `app/src/main/assets/profiles/lookups.dat` |

The vendored BRouter sources are unmodified. The one file in that package tree
that is *not* upstream is `brouter/src/main/java/btools/router/OpenCurvTrackAccess.java`,
an OpenCurv addition that reads BRouter's package-private turn-instruction data;
it carries a header saying so.

## Runtime dependencies (resolved by Gradle, not bundled as source)

| Component | Licence |
|---|---|
| [Mapsforge](https://github.com/mapsforge/mapsforge) (`mapsforge-map-android`, `mapsforge-map-reader`, `mapsforge-themes`) | LGPL-3.0 |
| [AndroidSVG](https://github.com/BigBadaboom/androidsvg) (transitive via Mapsforge) | Apache-2.0 |
| AndroidX (core, lifecycle, activity, compose, documentfile) | Apache-2.0 |
| Jetpack Compose / Material 3 | Apache-2.0 |
| Kotlin standard library and kotlinx.coroutines | Apache-2.0 |

Mapsforge is LGPL-3.0 and is used as an unmodified library dependency, which
GPL-3.0 permits and is compatible with.

## Data

OpenCurv ships no map data. Maps (`.map`) and routing tiles (`.rd5`) are derived
from [OpenStreetMap](https://www.openstreetmap.org/), © OpenStreetMap
contributors, available under the
[Open Database License](https://opendatacommons.org/licenses/odbl/). If you
distribute a build with map data included, you must carry that attribution.
