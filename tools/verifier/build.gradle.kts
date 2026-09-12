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
            // ConnectivityManager/SharedPreferences - Android only, unlike the
            // rest of data/traffic (AutobahnTrafficSource, TrafficRepository,
            // the parser and models are all Android-free and stay included).
            "**/data/traffic/TrafficUpdater.kt",
            // Touches Context for the assets/filesDir load, same reason as the
            // other data-layer exclusions above; the Android-free parts of the
            // speed-camera feature (SpeedCamera, SpeedCameraGrid,
            // SpeedCameraWarner) are not excluded and run here.
            "**/data/cameras/SpeedCameraRepository.kt",
            // Opens android.database.sqlite.SQLiteDatabase directly - Android
            // only, same reason as the rest of this list. QueryParser.kt, the
            // text-handling half of the address search (wave 6.4b), has no
            // such dependency and is deliberately not excluded.
            "**/data/search/SqlitePlaceIndex.kt",
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
