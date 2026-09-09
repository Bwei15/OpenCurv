package com.motoroute.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.motoroute.data.model.GeoPoint
import com.motoroute.data.model.Route
import com.motoroute.data.settings.MapStyle
import com.motoroute.domain.CameraController
import com.motoroute.ui.theme.LocalRideColors
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.view.MapView

/**
 * The map.
 *
 * A Mapsforge [MapView] hosted in Compose through [AndroidView]. Mapsforge is
 * a 2-D renderer, so the "3-D" perspective the cockpit spec asks for is applied
 * as a projective transform on the view itself ([perspectiveTilt]). That is an
 * honest trade: the road layout gets the depth cue a rider expects, at the cost
 * of labels leaning with it. It can be switched off in settings.
 *
 * Camera control is one-directional: the map is only moved when [follow] is on.
 * The moment the rider drags or pinches, the host turns [follow] off and the
 * map stays exactly where they left it - the behaviour every phone map has, and
 * the one this app was missing.
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
) {
    val context = LocalContext.current
    val rideColors = LocalRideColors.current
    val gesture = rememberUpdatedState(onUserGesture)

    val mapView = remember(context) { controller.attach(context) { gesture.value() } }

    DisposableEffect(mapView) {
        onDispose { controller.detach() }
    }

    LaunchedEffect(rideColors.isNight, style) {
        controller.applyTheme(rideColors.isNight, style)
    }

    LaunchedEffect(route) {
        controller.showRoute(route, rideColors.route.toArgb())
    }

    LaunchedEffect(destination) {
        controller.showDestination(destination, rideColors.destination.toArgb())
    }

    LaunchedEffect(start) {
        controller.showStart(start, rideColors.ok.toArgb())
    }

    LaunchedEffect(position, headingDegrees) {
        controller.showPosition(position, headingDegrees, rideColors.rider.toArgb())
    }

    LaunchedEffect(position, headingDegrees, zoom, headingUp, follow) {
        if (follow) {
            controller.follow(position, headingDegrees, zoom, headingUp)
        } else if (!headingUp) {
            controller.resetRotation()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // A tilt of ~50 degrees, with the horizon pushed to the top
                    // third so most of the screen shows the road ahead.
                    rotationX = perspectiveTilt
                    cameraDistance = 24f * density
                    transformOrigin = TransformOrigin(0.5f, 0.72f)
                },
            update = { view ->
                if (onMapTap != null || onMapLongPress != null) {
                    view.setOnTouchListener(
                        controller.tapListener(
                            view = view,
                            onTap = { onMapTap?.invoke(it) },
                            onLongPress = { onMapLongPress?.invoke(it) },
                        ),
                    )
                }
            },
        )
    }
}

/** Convenience: turns a Mapsforge LatLong into our own point type. */
fun LatLong.toGeoPoint(): GeoPoint = GeoPoint(latitude, longitude)

/** Convenience: and back. */
fun GeoPoint.toLatLong(): LatLong = LatLong(latitude, longitude)

/** Zoom clamped to what the renderer and the data can actually serve. */
fun clampZoom(zoom: Int): Byte =
    zoom.coerceIn(CameraController.MIN_ZOOM, CameraController.MAX_ZOOM).toByte()
