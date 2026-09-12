package com.motoroute.di

import android.content.Context
import com.motoroute.data.brouter.BRouterEngine
import com.motoroute.data.brouter.ProfileManager
import com.motoroute.data.cameras.SpeedCameraRepository
import com.motoroute.data.download.DownloadRepository
import com.motoroute.data.download.FileDownloader
import com.motoroute.data.download.MapCatalog
import com.motoroute.data.location.LocationProvider
import com.motoroute.data.download.RegionStore
import com.motoroute.data.history.RouteHistory
import com.motoroute.data.map.OfflineDataRepository
import com.motoroute.data.search.PlaceSearchRepository
import com.motoroute.data.search.SqlitePlaceIndex
import com.motoroute.data.settings.SettingsRepository
import com.motoroute.domain.NavigationController
import com.motoroute.domain.cameras.SpeedCameraWarner
import com.motoroute.voice.VoiceGuidance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    /** Last destinations and last tours, for the search box and the plan sheet (Welle 7.2b). */
    val routeHistory = RouteHistory(java.io.File(appContext.filesDir, "history.json"))

    /** Which files belong to which downloaded region. */
    val regions = RegionStore(
        indexFile = offlineData.regionIndexFile,
        mapDir = offlineData.mapDir,
        segmentDir = offlineData.segmentDir,
        placesDir = offlineData.placesDir,
    )

    /** Street and house-number lookups from downloaded `<region-id>.places.sqlite` files. */
    val sqlitePlaceIndex = SqlitePlaceIndex(filesDir = { offlineData.placesDir })

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
        sqliteIndex = sqlitePlaceIndex,
        logger = { message -> android.util.Log.d("PlaceSearch", message) },
    )

    val downloads = DownloadRepository(
        scope = scope,
        downloader = FileDownloader(),
        directoryFor = offlineData::directoryFor,
        freeSpaceBytes = offlineData::freeSpaceBytes,
        regionStore = regions,
    )

    val traffic = com.motoroute.data.traffic.TrafficRepository(
        cacheFile = java.io.File(appContext.filesDir, "traffic_cache.json"),
    )

    /** Fetches from the BMDV/Autobahn GmbH API when online and the cache is stale; see TrafficUpdater. */
    val trafficUpdater = com.motoroute.data.traffic.TrafficUpdater(
        context = appContext,
        repository = traffic,
        scope = scope,
    )
    /** Stationary speed cameras: bundled starter data plus whatever a region download adds. */
    val speedCameraRepository = SpeedCameraRepository(appContext, offlineData.camerasDir)
    val speedCameraWarner = SpeedCameraWarner()

    val navigation = NavigationController(
        locationProvider = locationProvider,
        routingEngine = routingEngine,
        profileManager = profileManager,
        offlineData = offlineData,
        settings = settings,
        voice = voice,
        trafficRepository = traffic,
        speedCameraWarner = speedCameraWarner,
        scope = scope,
    )

    init {
        // Off the main thread and off the startup path, same reason
        // ProfileManager.ensureInstalled() runs in a launched coroutine rather
        // than AppContainer's constructor: parsing a region's worth of TSV
        // rows (Niedersachsen alone is low thousands) is not something a cold
        // start should wait on.
        scope.launch(Dispatchers.IO) {
            speedCameraWarner.updateCameras(speedCameraRepository.grid())
        }
    }
}
