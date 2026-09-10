import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories {
    mavenCentral()
    // Only needed for de.topobyte:osm4j-* (PBF reading). Not on Maven Central.
    // Same two hosts tools/testarena already depends on - see its README for
    // the fallback plan if they ever disappear (drop the dependency, keep the
    // XML path; .osm XML input/output is unaffected).
    maven { url = uri("https://mvn.topobyte.de") }
    maven { url = uri("https://mvn.slimjars.com") }
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")

    // Same PBF library tools/testarena uses to *write* arena.osm.pbf; here it
    // is used to *read* .osm.pbf. Optional: everything works on .osm XML too.
    implementation("de.topobyte:osm4j-core:1.4.1")
    implementation("de.topobyte:osm4j-pbf:1.4.1")

    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.opencurv.curvescore.MainKt")
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xjvm-default=all")
}

tasks.withType<Test>().configureEach {
    useJUnit()
    maxHeapSize = "2g"
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
    systemProperty("curvescore.moduleDir", projectDir.canonicalPath)
    systemProperty("curvescore.repoDir", projectDir.resolve("../..").canonicalPath)
}

/** Scores the checked-in testarena, prints the ranking, checks E1-E9 and writes report/arena.svg. */
tasks.register<JavaExec>("arenaReport") {
    group = "curvescore"
    description = "Scores tools/testarena/data/arena.osm, prints the ranking, verifies E1-E9 and writes report/arena.svg + report/arena_scores.json."
    mainClass.set("com.opencurv.curvescore.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf(
        "arena",
        "--arena-dir", projectDir.resolve("../testarena").canonicalPath,
        "--out-dir", projectDir.resolve("report").canonicalPath,
    )
}

/** Rough throughput measurement used for the Bavaria runtime extrapolation in 1.Doku/Kurven_Score.md. */
tasks.register<JavaExec>("benchmark") {
    group = "curvescore"
    description = "Measures scoring throughput (ways/s and km/s) on synthetic geometry."
    mainClass.set("com.opencurv.curvescore.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    maxHeapSize = "2g"
    args = listOf("bench")
}
