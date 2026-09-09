// Vendored BRouter core (MIT licence, see LICENSE.brouter.txt).
// Kept as a plain java-library so it compiles and can be unit tested on a
// desktop JVM without the Android SDK.
plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // BRouter's sources are Java 8 clean; -Xlint is noisy on vendored code.
    options.compilerArgs.add("-nowarn")
}
