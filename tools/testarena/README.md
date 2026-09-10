# Testarena

A standalone Gradle/JVM build (no Android SDK needed, mirrors `tools/verifier/`) that generates
a synthetic OSM map with exactly known road geometry, and a harness that scores a computed route
against it.

See **`1.Doku/Testarena.md`** (German) for the full picture: why this exists, the exact commands,
what every element is (with an ASCII sketch of the layout), how to score a route, and how to add
a new element.

Quick start:

```
./gradlew -p tools/testarena generateArena   # (re)writes data/arena.osm(.pbf) + arena_truth.json
./gradlew -p tools/testarena test            # determinism + geometry + harness tests
./gradlew -p tools/testarena evaluateRoute -ProuteFile=route.gpx
```
