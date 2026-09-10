package com.opencurv.testarena.harness

import com.opencurv.testarena.truth.JsonIo
import java.io.File
import kotlin.system.exitProcess

/**
 * CLI entry point for the Mess-Harness:
 *
 *     ./gradlew -p tools/testarena evaluateRoute -ProuteFile=/path/to/route.gpx [-Pout=report.json]
 *
 * or directly:
 *
 *     ./gradlew -p tools/testarena run --args="..."   (see build.gradle.kts for the plain args form)
 *
 * Args: <arenaDataDir> <arenaTruthJson> <routeFile.gpx|.json> <reportOut.json>
 */
fun main(args: Array<String>) {
    if (args.size < 4) {
        System.err.println(
            "Usage: EvaluateRoute <arenaDataDir> <arenaTruthJson> <routeFile.gpx|.json> <reportOut.json>",
        )
        exitProcess(2)
    }
    val arenaDataDir = File(args[0])
    val truthFile = File(args[1])
    val routeFile = File(args[2])
    val reportOut = File(args[3])

    val arena = ArenaGraph.load(File(arenaDataDir, "arena.osm"))
    val truth = JsonIo.readArenaTruth(truthFile)
    val route = RouteInput.read(routeFile)

    val report = Evaluator.evaluate(routeFile.path, route, arena, truth)

    println(report.toConsoleText())
    reportOut.parentFile?.mkdirs()
    reportOut.writeText(JsonIo.gson.toJson(report) + "\n", Charsets.UTF_8)
    println("Bericht geschrieben nach ${reportOut.path}")

    if (!report.ok) exitProcess(1)
}
