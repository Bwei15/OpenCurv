package com.motoroute.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motoroute.OpenCurvApp
import com.motoroute.R
import com.motoroute.data.settings.MapTheme
import com.motoroute.domain.CameraController
import com.motoroute.domain.PlanningState
import com.motoroute.service.DownloadService
import com.motoroute.service.NavigationService
import com.motoroute.ui.data.MapDownloadScreen
import com.motoroute.ui.data.OfflineDataScreen
import com.motoroute.ui.map.MapScreen
import com.motoroute.ui.map.MapViewModel
import com.motoroute.ui.navigation.ActiveNavigationScreen
import com.motoroute.ui.navigation.GloveButton
import com.motoroute.ui.navigation.SpeedCameraBanner
import com.motoroute.ui.onboarding.OnboardingScreen
import com.motoroute.ui.plan.MissingDataCard
import com.motoroute.ui.plan.RoutePlanSheet
import com.motoroute.ui.search.SearchScreen
import com.motoroute.ui.settings.SettingsScreen
import com.motoroute.ui.theme.LocalRideColors

private enum class Screen { MAP, SEARCH, DATA, DOWNLOAD, SETTINGS }

/**
 * Top-level composition.
 *
 * While riding, the map plus the HUD is the entire interface. Everything else -
 * search, data, settings - is a full screen reached from the map and gone again
 * the moment navigation starts.
 */
@Composable
fun OpenCurvRoot(
    viewModel: MapViewModel,
    onImportRequested: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val context = LocalContext.current
    // Read directly off the AppContainer rather than through MapViewModel: the
    // camera warning fires with or without a ride (see
    // domain/cameras/SpeedCameraWarner.kt), so it cannot depend on a
    // ride-scoped ViewModel that Welle 7 owns and this task must not touch.
    val speedCameraContainer = remember { (context.applicationContext as OpenCurvApp).container }
    val speedCameraWarning by speedCameraContainer.speedCameraWarner.warning.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val navigationState by viewModel.navigationState.collectAsState()
    val planning by viewModel.planningState.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val zoom by viewModel.recommendedZoom.collectAsState()
    val message by viewModel.message.collectAsState()
    val follow by viewModel.followMode.collectAsState()
    val manualZoom by viewModel.manualZoom.collectAsState()
    val demoRunning by viewModel.demoRunning.collectAsState()

    var screen by remember { mutableStateOf(Screen.MAP) }
    var onboarding by remember { mutableStateOf(!settings.onboardingDone) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // Starting and stopping the foreground service follows the state machine
    // rather than the button press, so a ride resumed from the notification
    // behaves identically to one started from the map.
    // A demo ride is deliberately left out: it produces its own positions, so
    // there is no GPS to keep alive with the screen off, and no reason to ask
    // the platform for a location foreground service to try one out indoors.
    LaunchedEffect(navigationState.isNavigating, demoRunning) {
        if (navigationState.isNavigating && !demoRunning) {
            NavigationService.start(context)
        } else {
            NavigationService.stop(context)
        }
    }

    // The download queue runs in a foreground service so a 400 MB map survives
    // the screen locking; the service follows the queue rather than a button.
    val downloadQueue by viewModel.downloadQueue.collectAsState()
    LaunchedEffect(downloadQueue.isRunning) {
        if (downloadQueue.isRunning) DownloadService.start(context)
    }
    LaunchedEffect(downloadQueue.allDone) {
        if (downloadQueue.allDone) {
            viewModel.onDownloadedFilesChanged()
            // Getting the first region is the whole point of onboarding.
            if (viewModel.hasMaps) viewModel.completeOnboarding()
        }
    }

    LaunchedEffect(planning) {
        if (planning is PlanningState.Ready) {
            viewModel.mapController.showWholeRoute((planning as PlanningState.Ready).route)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadRegions()
        viewModel.centerOnDataIfIdle()
    }

    val colors = LocalRideColors.current
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = if (screen == Screen.MAP) Color.Transparent else colors.canvas,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (screen) {
                Screen.MAP -> MapRoot(
                viewModel = viewModel,
                settings = settings,
                navigationState = navigationState,
                planning = planning,
                selection = selection,
                follow = follow,
                manualZoom = manualZoom,
                demoRunning = demoRunning,
                speedCameraWarning = speedCameraWarning,
                zoom = zoom,
                onOpenData = { screen = Screen.DATA },
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenSearch = {
                    viewModel.prepareSearch()
                    screen = Screen.SEARCH
                },
                onRequestPermission = onRequestPermission,
            )

            Screen.SEARCH -> SearchRoot(
                viewModel = viewModel,
                onBack = { screen = Screen.MAP },
            )

            Screen.DATA -> OfflineDataRoot(
                viewModel = viewModel,
                onImport = onImportRequested,
                onOpenDownloads = { screen = Screen.DOWNLOAD },
                onBack = { screen = Screen.MAP },
            )

            Screen.DOWNLOAD -> DownloadRoot(
                viewModel = viewModel,
                queue = downloadQueue,
                onBack = { screen = Screen.DATA },
            )

            Screen.SETTINGS -> SettingsScreen(
                settings = settings,
                onBack = { screen = Screen.MAP },
                onMapStyle = viewModel::setMapStyle,
                onMapTheme = viewModel::setMapTheme,
                onPerspective = viewModel::setPerspective,
                onHeadingUp = viewModel::setHeadingUp,
                onVolumeZoom = viewModel::setVolumeKeyZoom,
                onVoice = { viewModel.toggleVoice() },
                onTestVoice = viewModel::testVoice,
                onOpenData = { screen = Screen.DATA },
                onSpeedCameraWarnings = { enabled ->
                    speedCameraContainer.settings.update { it.copy(speedCameraWarnings = enabled) }
                },
            )
        }

        if (onboarding && !navigationState.isNavigating) {
            OnboardingScreen(
                onOpenDownloads = {
                    onboarding = false
                    screen = Screen.DOWNLOAD
                },
                onImport = {
                    onboarding = false
                    onImportRequested()
                },
                onSkip = {
                    onboarding = false
                    viewModel.completeOnboarding()
                },
            )
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        )

    }
}
}

@Composable
private fun MapRoot(
    viewModel: MapViewModel,
    settings: com.motoroute.data.settings.Settings,
    navigationState: com.motoroute.domain.NavigationState,
    planning: PlanningState,
    selection: com.motoroute.ui.map.PlanSelection,
    follow: Boolean,
    manualZoom: Boolean,
    demoRunning: Boolean,
    speedCameraWarning: com.motoroute.domain.cameras.SpeedCameraWarning?,
    zoom: Int,
    onOpenData: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val colors = LocalRideColors.current
    val navigating = navigationState.isNavigating
    val position = navigationState.snappedPosition ?: navigationState.position

    val map: @Composable () -> Unit = {
        MapScreen(
            controller = viewModel.mapController,
            route = navigationState.route ?: (planning as? PlanningState.Ready)?.route,
            position = position,
            headingDegrees = navigationState.headingDegrees,
            // Only the riding camera picks the zoom, and only until the rider
            // picks one themselves; when planning, the zoom is always theirs.
            zoom = if (navigating && !manualZoom) zoom else null,
            headingUp = navigating && settings.headingUp,
            follow = follow,
            perspectiveTilt = if (navigating && settings.perspectiveEnabled) {
                CameraController.tiltFor(navigationState.speedKmh.toDouble())
            } else {
                0f
            },
            style = settings.mapStyle,
            destination = selection.destination,
            start = selection.start,
            onUserGesture = viewModel::onUserGesture,
            onMapTap = if (navigating) null else viewModel::onMapTap,
            onMapLongPress = if (navigating) null else viewModel::onMapLongPress,
        )
    }

    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val sheetPeek = if (selection.isComplete) 132.dp else 60.dp

    Box(modifier = Modifier.fillMaxSize()) {
        map()

        if (navigating) {
            ActiveNavigationScreen(
                state = navigationState,
                onStop = if (demoRunning) viewModel::stopDemo else viewModel::stopNavigation,
                onToggleVoice = viewModel::toggleVoice,
                onRecenter = viewModel::recenter,
                onForceReroute = viewModel::forceReroute,
                voiceEnabled = settings.voiceEnabled,
                following = follow,
                isDemo = demoRunning,
                cameraWarning = speedCameraWarning,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            ) {
            // A camera matters while standing still too; the banner renders
            // nothing until there is a warning.
            SpeedCameraBanner(
                warning = speedCameraWarning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchBar(
                    text = selection.destinationName,
                    onClick = onOpenSearch,
                    modifier = Modifier.weight(1f),
                )
                // The way back to your own position belongs where you look
                // first, and it lights up exactly while the map is not
                // following you.
                GloveButton(
                    iconRes = R.drawable.ic_action_center,
                    contentDescription = stringResource(R.string.action_center),
                    onClick = {
                        onRequestPermission()
                        viewModel.recenter()
                    },
                    background = if (follow) colors.hudBackground else colors.route,
                    tint = if (follow) colors.hudForeground else androidx.compose.ui.graphics.Color.Black,
                )
            }

            Spacer(Modifier.weight(1f))

            Column(
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GloveButton(
                    iconRes = R.drawable.ic_action_settings,
                    contentDescription = stringResource(R.string.settings_title),
                    onClick = onOpenSettings,
                )
                GloveButton(
                    iconRes = R.drawable.ic_action_layers,
                    contentDescription = stringResource(R.string.data_title),
                    onClick = onOpenData,
                )
                GloveButton(
                    iconRes = R.drawable.ic_action_daynight,
                    contentDescription = stringResource(R.string.settings_theme),
                    onClick = {
                        viewModel.setMapTheme(
                            when (settings.mapTheme) {
                                MapTheme.AUTO -> MapTheme.DAY
                                MapTheme.DAY -> MapTheme.NIGHT
                                MapTheme.NIGHT -> MapTheme.AUTO
                            },
                        )
                    },
                )
            }

            MissingDataCard(
                hasMaps = viewModel.hasMaps,
                hasSegments = viewModel.hasSegments,
                onOpenData = onOpenData,
                modifier = Modifier.padding(12.dp),
            )

            // Room for the sheet's peek, so the buttons never sit under it.
            Spacer(Modifier.height(sheetPeek + navBarBottom + 16.dp))
        }

        RoutePlanSheet(
            settings = settings,
            profiles = viewModel.profiles(),
            planning = planning,
            destinationName = selection.destinationName,
            hasDestination = selection.isComplete,
            hasExplicitStart = selection.start != null,
            onProfileChange = viewModel::setProfile,
            onCurvinessChange = viewModel::setCurviness,
            onAlternativesChange = viewModel::setAlternatives,
            onCalculate = viewModel::calculateRoute,
            onStart = viewModel::startNavigation,
            onDemo = viewModel::startDemo,
            onClear = viewModel::clearSelection,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
}

/** The search box on the map: a destination, or an invitation to pick one. */
@Composable
private fun SearchBar(text: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalRideColors.current
    Surface(
        color = colors.panel.copy(alpha = 0.96f),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .height(64.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_action_search),
                contentDescription = null,
                tint = colors.onPanel,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = text ?: stringResource(R.string.search_placeholder),
                color = if (text == null) colors.muted else colors.onPanel,
                fontSize = 17.sp,
                fontWeight = if (text == null) FontWeight.Normal else FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SearchRoot(viewModel: MapViewModel, onBack: () -> Unit) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val indexState by viewModel.indexState.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val navigationState by viewModel.navigationState.collectAsState()

    SearchScreen(
        query = query,
        results = results,
        indexState = indexState,
        searching = searching,
        near = navigationState.position ?: viewModel.mapController.center(),
        onQueryChange = viewModel::onQueryChange,
        onPick = {
            viewModel.chooseSearchResult(it)
            onBack()
        },
        onBack = onBack,
    )
}

@Composable
private fun DownloadRoot(
    viewModel: MapViewModel,
    queue: com.motoroute.data.download.DownloadQueueState,
    onBack: () -> Unit,
) {
    val regions by viewModel.regions.collectAsState()
    val dataVersion by viewModel.dataVersion.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadRegions() }

    val installed = remember(dataVersion, regions, queue) { viewModel.installedRegionPaths() }
    val freeSpace = remember(dataVersion, queue) { viewModel.freeSpace() }

    MapDownloadScreen(
        regions = regions,
        queue = queue,
        installedRegions = installed,
        freeSpaceBytes = freeSpace,
        blockedReason = viewModel.downloadBlockedReason(),
        onDownload = viewModel::downloadRegion,
        onCancel = viewModel::cancelDownloads,
        onRetry = viewModel::retryDownloads,
        onBack = onBack,
    )
}

@Composable
private fun OfflineDataRoot(
    viewModel: MapViewModel,
    onImport: () -> Unit,
    onOpenDownloads: () -> Unit,
    onBack: () -> Unit,
) {
    val dataVersion by viewModel.dataVersion.collectAsState()

    // Reading dataVersion here is what makes the list refresh after an import
    // or a delete; the files themselves are plain disk reads.
    val regions = remember(dataVersion) { viewModel.installedRegions() }
    val loose = remember(dataVersion) { viewModel.looseFiles() }
    val profiles = remember(dataVersion) {
        viewModel.filesOf(com.motoroute.data.map.OfflineFileKind.PROFILE)
    }
    val freeSpace = remember(dataVersion) { viewModel.freeSpace() }

    OfflineDataScreen(
        regions = regions,
        looseFiles = loose,
        profiles = profiles,
        freeSpaceBytes = freeSpace,
        onImport = onImport,
        onDownload = onOpenDownloads,
        onDeleteRegion = viewModel::deleteRegion,
        onDeleteFile = viewModel::deleteFile,
        onBack = onBack,
    )
}
