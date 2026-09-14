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
import androidx.compose.runtime.withFrameMillis
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
import com.motoroute.domain.PositionInterpolator
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
    zoom: Double?,
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
    /**
     * Speed in m/s and heading rate, used to carry the puck forward between GPS
     * fixes. Zero (the default) keeps the old behaviour: the puck is drawn where
     * the last fix put it and nowhere else.
     */
    speedMps: Double = 0.0,
    /** Off while planning: there is nothing to dead-reckon when the bike is parked. */
    interpolatePosition: Boolean = false,
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

    // ---- the puck ---------------------------------------------------------
    //
    // Two paths, deliberately. While riding, the interpolator carries the puck
    // forward between the one-per-second fixes and the map is redrawn every
    // frame (see PositionInterpolator for why a fix rate of 1 Hz cannot be
    // drawn directly). While planning, there is nothing to extrapolate, so the
    // puck is simply drawn where the fix says - and no frame loop runs, which
    // keeps a parked phone from rendering the map 60 times a second.

    val interpolator = remember(controller) { PositionInterpolator() }

    LaunchedEffect(interpolatePosition) {
        if (!interpolatePosition) interpolator.reset()
    }

    LaunchedEffect(position, headingDegrees, speedMps) {
        val p = position ?: return@LaunchedEffect
        interpolator.onFix(p, headingDegrees, speedMps, System.currentTimeMillis())
    }

    if (interpolatePosition) {
        // Read through rememberUpdatedState rather than as effect keys: zoom and
        // tilt change on every fix, and keying the loop on them would cancel and
        // relaunch the coroutine once a second for no reason.
        val liveZoom by rememberUpdatedState(zoom)
        val liveHeadingUp by rememberUpdatedState(headingUp)
        val liveFollow by rememberUpdatedState(follow)
        val liveTilt by rememberUpdatedState(perspectiveTilt)
        val livePuckColor by rememberUpdatedState(rideColors.rider.toArgb())

        LaunchedEffect(Unit) {
            while (true) {
                // Paced by the display, and suspended entirely while the app is
                // not drawing - so there is no timer to cancel.
                withFrameMillis { }
                val pose = interpolator.poseAt(System.currentTimeMillis()) ?: continue
                controller.showPosition(pose.point, pose.headingDegrees, livePuckColor)
                if (liveFollow) {
                    controller.follow(
                        position = pose.point,
                        headingDegrees = pose.headingDegrees,
                        zoom = liveZoom,
                        headingUp = liveHeadingUp,
                        tiltDegrees = liveTilt,
                        // Already smooth: easing on top would fight the frame loop.
                        animate = false,
                    )
                }
            }
        }
    } else {
        LaunchedEffect(position, headingDegrees, rideColors) {
            controller.showPosition(position, headingDegrees, rideColors.rider.toArgb())
        }

        LaunchedEffect(position, headingDegrees, zoom, headingUp, follow, perspectiveTilt) {
            if (follow && position != null) {
                controller.follow(position, headingDegrees, zoom, headingUp, perspectiveTilt)
            } else if (!headingUp) {
                // Also covers the moment a ride (or the demo) ends:
                // NavigationController.stop() resets to a fresh NavigationState, so
                // position drops to null while follow can still be true -
                // controller.follow() would then no-op above and leave the camera
                // exactly as tilted/rotated as the ride left it. Falling through here
                // puts it back flat and north-up instead of waiting for the rider to
                // pan by hand.
                controller.resetRotation()
            }
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
