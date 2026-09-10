package com.motoroute

import com.motoroute.data.download.DownloadTarget
import com.motoroute.data.download.MapCatalog
import com.motoroute.data.map.OfflineFileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCatalogTest {

    @Test
    fun `parses new catalog json schema with pmtiles and rd5 files`() {
        val json = """
        {
          "schemaVersion": 1,
          "generated": "2026-09-10T17:42:11Z",
          "release": {
            "tag": "data-20260910",
            "repo": "Bwei15/OpenCurv",
            "baseUrl": "https://github.com/Bwei15/OpenCurv/releases/download/data-20260910"
          },
          "regions": [
            {
              "id": "de-by",
              "name": "Bayern",
              "bbox": [8.9752, 47.2654, 13.8495, 50.5662],
              "source": { "provider": "geofabrik", "path": "europe/germany/bayern" },
              "totalBytes": 87012542,
              "files": [
                {
                  "name": "de-by_E10_N45.rd5",
                  "kind": "routing",
                  "bytes": 72897030,
                  "sha256": "abc123",
                  "url": "https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/de-by_E10_N45.rd5"
                },
                {
                  "name": "de-by_E10_N50.rd5",
                  "kind": "routing",
                  "bytes": 8010115,
                  "sha256": "def456",
                  "url": "https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/de-by_E10_N50.rd5"
                },
                {
                  "name": "de-by.pmtiles",
                  "kind": "maptiles",
                  "bytes": 6105397,
                  "sha256": "789ghi",
                  "url": "https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/de-by.pmtiles"
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val regions = MapCatalog.parse(json)
        assertEquals(1, regions.size)

        val bayern = regions.single()
        assertEquals("europe/germany/bayern", bayern.path)
        assertEquals("Bayern", bayern.name)
        assertEquals("Germany", bayern.country)
        assertEquals("de-by.pmtiles", bayern.fileName)
        assertEquals(82, bayern.approxSizeMb) // ~87MB / 1024^2

        // BoundingBox: [minLon, minLat, maxLon, maxLat]
        assertEquals(8.9752, bayern.bounds.minLon, 0.0001)
        assertEquals(47.2654, bayern.bounds.minLat, 0.0001)
        assertEquals(13.8495, bayern.bounds.maxLon, 0.0001)
        assertEquals(50.5662, bayern.bounds.maxLat, 0.0001)

        // Segments
        assertEquals(listOf("de-by_E10_N45.rd5", "de-by_E10_N50.rd5"), bayern.segmentTiles)

        // Download targets
        val mapTarget = DownloadTarget.map(bayern)
        assertEquals("de-by.pmtiles", mapTarget.fileName)
        assertEquals(OfflineFileKind.MAP, mapTarget.kind)
        assertEquals("https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/de-by.pmtiles", mapTarget.url)

        val segTarget = DownloadTarget.segment("de-by_E10_N45.rd5", bayern)
        assertEquals("de-by_E10_N45.rd5", segTarget.fileName)
        assertEquals(OfflineFileKind.SEGMENT, segTarget.kind)
        assertEquals("https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/de-by_E10_N45.rd5", segTarget.url)
    }

    @Test
    fun `parses legacy regions json schema with backward compatibility`() {
        val legacyJson = """
        {
          "regions": [
            {
              "path": "europe/germany/bayern",
              "name": "Bayern",
              "country": "Germany",
              "minLat": 47.2,
              "minLon": 8.9,
              "maxLat": 50.6,
              "maxLon": 13.9,
              "approxSizeMb": 470
            }
          ]
        }
        """.trimIndent()

        val regions = MapCatalog.parse(legacyJson)
        assertEquals(1, regions.size)

        val bayern = regions.single()
        assertEquals("europe/germany/bayern", bayern.path)
        assertEquals("Bayern", bayern.name)
        assertEquals("Germany", bayern.country)
        assertEquals("bayern.map", bayern.fileName)
        assertEquals(470, bayern.approxSizeMb)

        assertEquals(4, bayern.segmentTiles.size)

        val mapTarget = DownloadTarget.map(bayern)
        assertEquals("bayern.map", mapTarget.fileName)
        assertEquals("https://download.mapsforge.org/maps/v5/europe/germany/bayern.map", mapTarget.url)

        val segTarget = DownloadTarget.segment("E5_N45.rd5", bayern)
        assertEquals("E5_N45.rd5", segTarget.fileName)
        assertEquals("https://brouter.de/brouter/segments4/E5_N45.rd5", segTarget.url)
    }
}
