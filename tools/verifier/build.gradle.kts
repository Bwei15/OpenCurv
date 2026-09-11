import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
}

/**
 * The Android-free slice of the app.
 *
 * Everything OpenCurv does that can go subtly wrong - map matching, the Kalman
 * filter, the turn-by-turn state machine, BRouter integration, the routing
 * profiles - is deliberately written without Android imports so it can be
 * compiled and tested on a plain JVM. This build points straight at the app's
 * own sources; there is no copy to drift out of date.
 */
private val appMain = "../../app/src/main/java"
private val appTest = "../../app/src/test/java"
private val brouterMain = "../../brouter/src/main/java"

sourceSets {
    main {
        java.setSrcDirs(listOf(brouterMain))
        // The Kotlin source set has to see the Java sources too, otherwise
        // kotlinc cannot resolve the BRouter types the engine wrapper uses.
        kotlin.setSrcDirs(listOf(appMain, brouterMain))
        kotlin.exclude(
            "**/ui/**",
            "**/service/**",
            "**/voice/**",
            "**/di/**",
            "**/MainActivity.kt",
            "**/OpenCurvApp.kt",
            "**/data/settings/**",
            "**/data/map/OfflineDataRepository.kt",
            "**/data/download/MapCatalog.kt",
            "**/data/location/LocationProvider.kt",
            "**/data/brouter/ProfileManager.kt",
            "**/domain/NavigationController.kt",
            // Touches Context for the assets/filesDir load, same reason as the
            // other data-layer exclusions above; the Android-free parts of the
            // speed-camera feature (SpeedCamera, SpeedCameraGrid,
            // SpeedCameraWarner) are not excluded and run here.
            "**/data/cameras/SpeedCameraRepository.kt",
        )
        resources.setSrcDirs(emptyList<String>())
    }
    test {
        kotlin.setSrcDirs(listOf(appTest))
        kotlin.exclude(
            "**/MapCatalogTest.kt",
            // Exercises SpeedCameraRepository, which is excluded above for
            // the same Context dependency as MapCatalog.
            "**/SpeedCameraRepositoryTest.kt",
        )
        resources.setSrcDirs(emptyList<String>())
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // The offline place search reads Mapsforge maps directly, and the reader is
    // plain Java - so that code compiles and can be tested here too, rather
    // than only inside an Android build.
    implementation("org.mapsforge:mapsforge-map-reader:0.25.0")
    implementation("org.mapsforge:mapsforge-map:0.25.0")
    implementation("org.mapsforge:mapsforge-core:0.25.0")
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

// No toolchain pin: this build is meant to run on whatever JDK the developer
// or CI already has (17 or newer).

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-nowarn")
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xjvm-default=all")
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
    // Tests locate the bundled routing profiles relative to the repo root.
    systemProperty("opencurv.repo", rootDir.resolve("../..").canonicalPath)
}
