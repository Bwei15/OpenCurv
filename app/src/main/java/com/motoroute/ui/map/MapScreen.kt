package com.motoroute.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.motoroute.OpenCurvApp
import com.motoroute.data.model.GeoPoint
import com.motoroute.data.model.Route
import com.motoroute.data.settings.MapStyle
import com.motoroute.ui.theme.LocalRideColors

/**
 * The map.
 *
 * A MapLibre [org.maplibre.android.maps.MapView] hosted in Compose through
 * [AndroidView]. Unlike the Mapsforge screen this replaces, the "3-D"
 * perspective the cockpit spec asks for is a real camera tilt rendered by the
 * GPU ([MapController.follow]), not a projective transform on the Android
 * view - so road labels stay upright as the map tilts instead of leaning with
 * it.
 *
 * Camera control is one-directional: the map is only moved when [follow] is
 * on. The moment the rider drags, pinches, rotates or tilts the map by hand,
 * the host turns [follow] off and the map stays exactly where they left it.
 */
@Composable
fun MapScreen(
    controller: MapController,
    route: Route?,
    position: GeoPoint?,
    headingDegrees: Double,
    zoom: Int?,
    headingUp: Boolean,
    follow: Boolean,
    perspectiveTilt: Float,
    style: MapStyle,
    destination: GeoPoint?,
    start: GeoPoint?,
    modifier: Modifier = Modifier,
    onUserGesture: () -> Unit = {},
    onMapTap: ((GeoPoint) -> Unit)? = null,
    onMapLongPress: ((GeoPoint) -> Unit)? = null,
    onPoiTap: ((PoiHit) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val rideColors = LocalRideColors.current
    val gesture = rememberUpdatedState(onUserGesture)

    // Sperrungen and Blitzer come straight from the container, not the (POI-only) view model -
    // container.traffic.incidents already updates on every TrafficUpdater refresh, and the
    // speed-camera set only changes after a region download, so both are cheap to recompute here.
    val container = remember(context) { (context.applicationContext as OpenCurvApp).container }
    val incidents by container.traffic.incidents.collectAsState()
    val settings by container.settings.settings.collectAsState()

    // Seeding the theme the map is attached with matters: see the doc comment
    // on MapController.attach for why loading day and then immediately
    // reloading night used to leave the map blank.
    val mapView = remember(context) {
        controller.attach(context, rideColors.isNight, style) { gesture.value() }
    }

    // MapLibre's GL surface needs the full Android lifecycle forwarded - a
    // MapView is not a Fragment here, Compose does not do that for us.
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.onStart()
                Lifecycle.Event.ON_RESUME -> controller.onResume()
                Lifecycle.Event.ON_PAUSE -> controller.onPause()
                Lifecycle.Event.ON_STOP -> controller.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(mapView) {
        onDispose { controller.detach() }
    }

    LaunchedEffect(rideColors.isNight, style) {
        controller.applyTheme(rideColors.isNight, style)
    }

    LaunchedEffect(route, rideColors) {
        controller.showRoute(route, rideColors.route.toArgb(), rideColors.routeCasing.toArgb())
    }

    LaunchedEffect(destination, rideColors) {
        controller.showDestination(destination, rideColors.destination.toArgb())
    }

    LaunchedEffect(start, rideColors) {
        controller.showStart(start, rideColors.ok.toArgb())
    }

    LaunchedEffect(position, headingDegrees, rideColors) {
        controller.showPosition(position, headingDegrees, rideColors.rider.toArgb())
    }

    LaunchedEffect(position, headingDegrees, zoom, headingUp, follow, perspectiveTilt) {
        if (follow && position != null) {
            controller.follow(position, headingDegrees, zoom, headingUp, perspectiveTilt)
        } else if (!headingUp) {
            // Also covers the moment a ride (or the demo) ends: NavigationController.stop()
            // resets to a fresh NavigationState, so position drops to null while follow can
            // still be true - controller.follow() would then no-op above and leave the camera
            // exactly as tilted/rotated as the ride left it. Falling through here puts it back
            // flat and north-up instead of waiting for the rider to pan by hand.
            controller.resetRotation()
        }
    }

    LaunchedEffect(incidents) {
        controller.setTrafficGeoJson(container.traffic.getIncidentsGeoJson())
    }

    LaunchedEffect(settings.speedCameraWarnings) {
        controller.setCameraGeoJson(container.speedCameraRepository.toGeoJson())
        controller.setCameraVisible(settings.speedCameraWarnings)
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                (mapView.parent as? android.view.ViewGroup)?.removeView(mapView)
                mapView
            },
            modifier = Modifier.fillMaxSize(),
            update = { controller.setTapHandlers(onMapTap, onMapLongPress, onPoiTap) },
        )
    }
}
