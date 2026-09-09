package com.motoroute.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.motoroute.data.model.GeoPoint
import com.motoroute.data.model.Route
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
 */
@Composable
fun MapScreen(
    controller: MapController,
    route: Route?,
    position: GeoPoint?,
    headingDegrees: Double,
    zoom: Int,
    headingUp: Boolean,
    perspectiveTilt: Float,
    modifier: Modifier = Modifier,
    onMapTap: ((GeoPoint) -> Unit)? = null,
) {
    val context = LocalContext.current
    val rideColors = LocalRideColors.current

    val mapView = remember(context) { controller.attach(context) }

    DisposableEffect(mapView) {
        onDispose { controller.detach() }
    }

    LaunchedEffect(rideColors.isNight) {
        controller.applyTheme(rideColors.isNight)
    }

    LaunchedEffect(route) {
        controller.showRoute(route, rideColors.route.toArgb())
    }

    LaunchedEffect(position, headingDegrees, zoom, headingUp) {
        controller.follow(position, headingDegrees, zoom, headingUp)
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
                onMapTap?.let { tap ->
                    view.setOnTouchListener(controller.tapListener(view, tap))
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
