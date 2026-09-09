package com.motoroute.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.unit.dp
import com.motoroute.R
import com.motoroute.data.map.OfflineFileKind
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
import com.motoroute.ui.plan.MissingDataNotice
import com.motoroute.ui.plan.RoutePlanPanel
import com.motoroute.ui.theme.LocalRideColors

private enum class Screen { MAP, DATA, DOWNLOAD }

/**
 * Top-level composition.
 *
 * There is no bottom navigation bar and no drawer: while riding, the map plus
 * the HUD is the entire interface. Data management is a separate screen reached
 * by one oversized button, and it disappears the moment navigation starts.
 */
@Composable
fun OpenCurvRoot(
    viewModel: MapViewModel,
    onImportRequested: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val navigationState by viewModel.navigationState.collectAsState()
    val planning by viewModel.planningState.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val zoom by viewModel.recommendedZoom.collectAsState()
    val message by viewModel.message.collectAsState()

    var screen by remember { mutableStateOf(Screen.MAP) }
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
    LaunchedEffect(navigationState.isNavigating) {
        if (navigationState.isNavigating) {
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
        if (downloadQueue.allDone) viewModel.onDownloadedFilesChanged()
    }

    LaunchedEffect(planning) {
        if (planning is PlanningState.Ready) {
            viewModel.mapController.showWholeRoute((planning as PlanningState.Ready).route)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (screen) {
            Screen.MAP -> MapRoot(
                viewModel = viewModel,
                context = context,
                settings = settings,
                navigationState = navigationState,
                planning = planning,
                hasDestination = selection.isComplete,
                zoom = zoom,
                onOpenData = { screen = Screen.DATA },
                onRequestPermission = onRequestPermission,
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
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        )
    }
}

@Composable
private fun MapRoot(
    viewModel: MapViewModel,
    context: Context,
    settings: com.motoroute.data.settings.Settings,
    navigationState: com.motoroute.domain.NavigationState,
    planning: PlanningState,
    hasDestination: Boolean,
    zoom: Int,
    onOpenData: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val navigating = navigationState.isNavigating
    val position = navigationState.snappedPosition ?: navigationState.position

    val map: @Composable () -> Unit = {
        MapScreen(
            controller = viewModel.mapController,
            route = navigationState.route
                ?: (planning as? PlanningState.Ready)?.route,
            position = position,
            headingDegrees = navigationState.headingDegrees,
            zoom = if (navigating) zoom else 14,
            headingUp = navigating && settings.headingUp,
            perspectiveTilt = if (navigating && settings.perspectiveEnabled) {
                CameraController.tiltFor(navigationState.speedKmh.toDouble())
            } else {
                0f
            },
            onMapTap = if (navigating) null else viewModel::onMapTap,
        )
    }

    if (navigating) {
        ActiveNavigationScreen(
            state = navigationState,
            onStop = viewModel::stopNavigation,
            onToggleVoice = viewModel::toggleVoice,
            onRecenter = viewModel::recenter,
            onForceReroute = viewModel::forceReroute,
            voiceEnabled = settings.voiceEnabled,
            map = map,
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        map()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GloveButton(
                    iconRes = R.drawable.ic_action_layers,
                    contentDescription = "Offline data",
                    onClick = onOpenData,
                )
                GloveButton(
                    iconRes = R.drawable.ic_action_center,
                    contentDescription = "Center on me",
                    onClick = {
                        onRequestPermission()
                        viewModel.recenter()
                    },
                )
                GloveButton(
                    iconRes = if (settings.mapTheme == MapTheme.NIGHT) {
                        R.drawable.ic_action_sound_off
                    } else {
                        R.drawable.ic_action_settings
                    },
                    contentDescription = "Toggle day and night",
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

            Box(modifier = Modifier.weight(1f))

            RoutePlanPanel(
                settings = settings,
                profiles = viewModel.profiles(),
                planning = planning,
                hasDestination = hasDestination,
                onProfileChange = viewModel::setProfile,
                onCurvinessChange = viewModel::setCurviness,
                onAlternativesChange = viewModel::setAlternatives,
                onCalculate = viewModel::calculateRoute,
                onStart = viewModel::startNavigation,
                onClear = viewModel::clearSelection,
            )
        }

        MissingDataNotice(
            hasMaps = viewModel.hasMaps,
            hasSegments = viewModel.hasSegments,
            onOpenData = onOpenData,
        )
    }
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

    val installed = remember(dataVersion, regions) { viewModel.installedRegions() }
    val freeSpace = remember(dataVersion) { viewModel.freeSpace() }

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
    val colors = LocalRideColors.current
    val dataVersion by viewModel.dataVersion.collectAsState()

    // Reading dataVersion here is what makes the list refresh after an import
    // or a delete; the files themselves are plain disk reads.
    val maps = remember(dataVersion) { viewModel.filesOf(OfflineFileKind.MAP) }
    val segments = remember(dataVersion) { viewModel.filesOf(OfflineFileKind.SEGMENT) }
    val profiles = remember(dataVersion) { viewModel.filesOf(OfflineFileKind.PROFILE) }
    val freeSpace = remember(dataVersion) { viewModel.freeSpace() }

    Box(modifier = Modifier.fillMaxSize()) {
        OfflineDataScreen(
            maps = maps,
            segments = segments,
            profiles = profiles,
            freeSpaceBytes = freeSpace,
            onImport = onImport,
            onDownload = onOpenDownloads,
            onDelete = viewModel::deleteFile,
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
        ) {
            GloveButton(
                iconRes = R.drawable.ic_action_route,
                contentDescription = "Back to the map",
                onClick = onBack,
                background = colors.hudBackground,
            )
        }
    }
}
