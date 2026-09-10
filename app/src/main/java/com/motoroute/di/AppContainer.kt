package com.motoroute.di

import android.content.Context
import com.motoroute.data.brouter.BRouterEngine
import com.motoroute.data.brouter.ProfileManager
import com.motoroute.data.download.DownloadRepository
import com.motoroute.data.download.FileDownloader
import com.motoroute.data.download.MapCatalog
import com.motoroute.data.location.LocationProvider
import com.motoroute.data.download.RegionStore
import com.motoroute.data.map.OfflineDataRepository
import com.motoroute.data.search.PlaceSearchRepository
import com.motoroute.data.settings.SettingsRepository
import com.motoroute.domain.NavigationController
import com.motoroute.voice.VoiceGuidance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency wiring.
 *
 * The graph is small and entirely process-scoped, so a container beats a DI
 * framework here: no annotation processor, no build-time cost, and the whole
 * object graph is readable in one screen.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings = SettingsRepository(appContext)
    val offlineData = OfflineDataRepository(appContext)
    val profileManager = ProfileManager(appContext)
    val locationProvider = LocationProvider(appContext)
    val routingEngine = BRouterEngine()
    val voice = VoiceGuidance(appContext)

    val mapCatalog = MapCatalog(appContext)

    /** Which files belong to which downloaded region. */
    val regions = RegionStore(
        indexFile = offlineData.regionIndexFile,
        mapDir = offlineData.mapDir,
        segmentDir = offlineData.segmentDir,
    )

    /** Offline destination search, built from the maps already on the phone or bundled places. */
    val placeSearch = PlaceSearchRepository(
        mapFiles = offlineData::mapFiles,
        cacheDir = offlineData.indexDir,
        scope = scope,
        basePlacesProvider = {
            runCatching {
                appContext.assets.open("catalog/places_de.tsv").use { stream ->
                    PlaceSearchRepository.readPlaces(stream)
                }
            }.getOrNull().orEmpty()
        },
        placesFiles = {
            (offlineData.mapDir.listFiles { f -> f.isFile && f.extension.equals("places", ignoreCase = true) }.orEmpty().toList() +
             offlineData.mapTilesDir.listFiles { f -> f.isFile && f.extension.equals("places", ignoreCase = true) }.orEmpty().toList() +
             offlineData.indexDir.listFiles { f -> f.isFile && f.extension.equals("places", ignoreCase = true) }.orEmpty().toList()
            ).distinctBy { it.absolutePath }
        },
    )

    val downloads = DownloadRepository(
        scope = scope,
        downloader = FileDownloader(),
        directoryFor = offlineData::directoryFor,
        freeSpaceBytes = offlineData::freeSpaceBytes,
        regionStore = regions,
    )

    val navigation = NavigationController(
        locationProvider = locationProvider,
        routingEngine = routingEngine,
        profileManager = profileManager,
        offlineData = offlineData,
        settings = settings,
        voice = voice,
        scope = scope,
    )
}
