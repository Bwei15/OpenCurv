import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories {
    mavenCentral()
    // Only needed for de.topobyte:osm4j-* (PBF read/write support). Not on
    // Maven Central; see README.md for why we depend on it and what happens
    // if it cannot be reached (the module still builds - only the .osm.pbf
    // output and its test are skipped).
    maven { url = uri("https://mvn.topobyte.de") }
    // Transitive dependency of osm4j-core/osm4j-pbf (their trove4j fork).
    maven { url = uri("https://mvn.slimjars.com") }
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")

    // Optional: used only by OsmPbfWriter to emit arena.osm.pbf alongside
    // the always-produced arena.osm (XML). See README.md "PBF-Unterstützung".
    implementation("de.topobyte:osm4j-core:1.4.1")
    implementation("de.topobyte:osm4j-pbf:1.4.1")

    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.opencurv.testarena.GenerateArenaKt")
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
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
    // Tests read/write fixtures relative to the module root and need to find
    // the checked-in arena under tools/testarena/data.
    systemProperty("testarena.moduleDir", projectDir.canonicalPath)
}

tasks.register<JavaExec>("generateArena") {
    group = "testarena"
    description = "Regenerates data/arena.osm, data/arena.osm.pbf and arena_truth.json deterministically."
    mainClass.set("com.opencurv.testarena.GenerateArenaKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf(projectDir.resolve("data").canonicalPath, projectDir.resolve("arena_truth.json").canonicalPath)
}

tasks.register<JavaExec>("evaluateRoute") {
    group = "testarena"
    description = "Evaluates a route (GPX or JSON point list) against the arena. Pass -ProuteFile=<path> [-Pout=<report.json>]."
    mainClass.set("com.opencurv.testarena.harness.EvaluateRouteKt")
    classpath = sourceSets["main"].runtimeClasspath
    doFirst {
        require(project.hasProperty("routeFile")) {
            "Pass -ProuteFile=<path/to/route.gpx-or-.json>"
        }
    }
    val routeFile = project.findProperty("routeFile") as String?
    val out = (project.findProperty("out") as String?) ?: projectDir.resolve("report.json").canonicalPath
    args = listOf(
        projectDir.resolve("data").canonicalPath,
        projectDir.resolve("arena_truth.json").canonicalPath,
        routeFile ?: "MISSING_ROUTE_FILE",
        out,
    )
}
