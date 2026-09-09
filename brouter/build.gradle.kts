// Vendored BRouter core (MIT licence, see LICENSE.brouter.txt).
// Kept as a plain java-library so it compiles and can be unit tested on a
// desktop JVM without the Android SDK.
plugins {
    id("java-library")
}

// Java 11, matching upstream BRouter: its sources use the diamond operator on
// anonymous classes, which is a Java 9+ construct. The app module enables core
// library desugaring, so Java 11 bytecode is fine down to minSdk 26.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // -Xlint is noisy on vendored sources we do not maintain.
    options.compilerArgs.add("-nowarn")
}
