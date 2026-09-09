# Logic verifier

A standalone Gradle/JVM build that compiles the Android-free core of OpenCurv
straight out of `app/src/main/java` (no copies) together with the vendored
BRouter sources, and runs the shared unit tests from `app/src/test/java`.

Why it exists: the same tests run in CI as Android unit tests, but this build
needs no Android SDK, so the routing engine, the Kalman filter, the map matcher,
the turn-by-turn state machine and the `.brf` profiles can be verified anywhere
- including on a machine that cannot reach Google's Maven repository.

```
cd tools/verifier
gradle test        # or ../../gradlew -p tools/verifier test
```
