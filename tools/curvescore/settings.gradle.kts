// Standalone JVM build for the OpenCurv curve scorer: it turns OSM geometry
// into a 0..15 "how much fun is this road on a motorcycle" number.
// Deliberately NOT part of the Android build - it runs in the GitHub-Actions
// map pre-processing pipeline, never on the phone. Mirrors how
// tools/testarena and tools/verifier keep tool builds independent of the
// Android SDK.
rootProject.name = "opencurv-curvescore"
