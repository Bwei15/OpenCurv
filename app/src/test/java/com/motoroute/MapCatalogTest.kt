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

        // The catalog's per-file sha256/bytes must reach the download target,
        // not just the raw JSON - FileDownloader verifies against these.
        assertEquals("789ghi", mapTarget.sha256)
        assertEquals(6105397L, mapTarget.expectedBytes)
        assertEquals("abc123", segTarget.sha256)
        assertEquals(72897030L, segTarget.expectedBytes)
    }

    @Test
    fun `a places kind file becomes a places download target`() {
        val json = """
        {
          "release": { "baseUrl": "https://github.com/Bwei15/OpenCurv/releases/download/data-20260910" },
          "regions": [
            {
              "id": "de-hb",
              "name": "Bremen",
              "bbox": [8.4, 52.9, 9.0, 53.6],
              "source": { "path": "europe/germany/bremen" },
              "files": [
                { "name": "de-hb.pmtiles", "kind": "maptiles", "bytes": 1000 },
                {
                  "name": "de-hb.places.sqlite",
                  "kind": "places",
                  "bytes": 4530176,
                  "sha256": "places-sha"
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val bremen = MapCatalog.parse(json).single()
        assertEquals("de-hb.places.sqlite", bremen.placesFile)
        assertEquals(
            "https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/de-hb.places.sqlite",
            bremen.placesUrl,
        )

        val target = DownloadTarget.places(bremen)
        assertNotNull(target)
        assertEquals("de-hb.places.sqlite", target!!.fileName)
        assertEquals(com.motoroute.data.map.OfflineFileKind.PLACES, target.kind)
        assertEquals("places-sha", target.sha256)
        assertEquals(4530176L, target.expectedBytes)
    }

    @Test
    fun `a region with no places file in the catalog gets no places download target`() {
        val json = """
        {
          "regions": [
            { "id": "de-hb", "name": "Bremen", "bbox": [8.4, 52.9, 9.0, 53.6],
              "files": [ { "name": "de-hb.pmtiles", "kind": "maptiles", "bytes": 1000 } ] }
          ]
        }
        """.trimIndent()

        val bremen = MapCatalog.parse(json).single()
        assertEquals(null, bremen.placesFile)
        assertEquals(null, DownloadTarget.places(bremen))
    }

    @Test
    fun `a file entry without a checksum leaves the target unverified`() {
        val json = """
        {
          "regions": [
            {
              "id": "de-hb",
              "name": "Bremen",
              "bbox": [8.4, 52.9, 9.0, 53.6],
              "files": [
                { "name": "de-hb.pmtiles", "kind": "maptiles", "bytes": 1000,
                  "url": "https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/de-hb.pmtiles" }
              ]
            }
          ]
        }
        """.trimIndent()

        val bremen = MapCatalog.parse(json).single()
        val target = DownloadTarget.map(bremen)
        assertEquals(null, target.sha256)
    }

    @Test
    fun `the newest data release wins over older ones and the apk release`() {
        // Shaped like GET /repos/.../releases: newest first is how GitHub
        // actually orders it, but the selection must not depend on that -
        // it is deliberately out of order here.
        val releasesJson = """
        [
          { "tag_name": "v1.0.0", "assets": [
            { "name": "app-release.apk", "browser_download_url": "https://example.invalid/app-release.apk" }
          ] },
          { "tag_name": "data-20260901", "assets": [
            { "name": "catalog.json", "browser_download_url": "https://github.com/Bwei15/OpenCurv/releases/download/data-20260901/catalog.json" }
          ] },
          { "tag_name": "data-20260910", "assets": [
            { "name": "catalog.json", "browser_download_url": "https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/catalog.json" }
          ] }
        ]
        """.trimIndent()

        val url = MapCatalog.selectLatestDataCatalogUrl(releasesJson)
        assertEquals(
            "https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/catalog.json",
            url,
        )
    }

    @Test
    fun `a data release without a catalog json asset is skipped`() {
        val releasesJson = """
        [
          { "tag_name": "data-20260910", "assets": [
            { "name": "de-ni.pmtiles", "browser_download_url": "https://github.com/Bwei15/OpenCurv/releases/download/data-20260910/de-ni.pmtiles" }
          ] },
          { "tag_name": "data-20260901", "assets": [
            { "name": "catalog.json", "browser_download_url": "https://github.com/Bwei15/OpenCurv/releases/download/data-20260901/catalog.json" }
          ] }
        ]
        """.trimIndent()

        val url = MapCatalog.selectLatestDataCatalogUrl(releasesJson)
        assertEquals(
            "https://github.com/Bwei15/OpenCurv/releases/download/data-20260901/catalog.json",
            url,
        )
    }

    @Test
    fun `no data release at all yields no url rather than a crash`() {
        val releasesJson = """[ { "tag_name": "v1.0.0", "assets": [] } ]"""
        assertEquals(null, MapCatalog.selectLatestDataCatalogUrl(releasesJson))
        assertEquals(null, MapCatalog.selectLatestDataCatalogUrl("not json"))
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
