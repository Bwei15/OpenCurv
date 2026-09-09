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
            "**/data/map/**",
            "**/data/location/LocationProvider.kt",
            "**/data/brouter/ProfileManager.kt",
            "**/domain/NavigationController.kt",
        )
        resources.setSrcDirs(emptyList<String>())
    }
    test {
        kotlin.setSrcDirs(listOf(appTest))
        resources.setSrcDirs(emptyList<String>())
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
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
