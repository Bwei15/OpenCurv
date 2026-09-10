package com.opencurv.testarena

import com.opencurv.testarena.truth.JsonIo
import java.io.File

/**
 * CLI entry point: regenerates the Testarena fixture deterministically.
 *
 *     ./gradlew -p tools/testarena generateArena
 *
 * or directly:
 *
 *     ./gradlew -p tools/testarena run --args="data arena_truth.json"
 *
 * Args: <outputDir> <truthJsonPath>. Writes <outputDir>/arena.osm (always) and
 * <outputDir>/arena.osm.pbf (best effort - see OsmPbfWriter/README.md), plus the ground-truth
 * file at <truthJsonPath>.
 */
fun main(args: Array<String>) {
    val outDir = File(if (args.isNotEmpty()) args[0] else "data")
    val truthPath = File(if (args.size > 1) args[1] else "arena_truth.json")

    val (doc, truth) = ArenaDefinition.build()

    val osmFile = File(outDir, "arena.osm")
    OsmXmlWriter.write(doc, osmFile)
    println("Wrote ${doc.nodes.size} nodes, ${doc.ways.size} ways -> ${osmFile.path}")

    val pbfFile = File(outDir, "arena.osm.pbf")
    try {
        OsmPbfWriter.write(doc, pbfFile)
        println("Wrote ${pbfFile.path} (${pbfFile.length()} bytes)")
    } catch (t: Throwable) {
        System.err.println("PBF export skipped (${t::class.simpleName}: ${t.message}). arena.osm is still complete.")
    }

    JsonIo.writeArenaTruth(truth, truthPath)
    println("Wrote ${truth.elements.size} elements, ${truth.expectations.size} expectations -> ${truthPath.path}")
}
