package com.motoroute.ui.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as NativePaint
import android.graphics.Path as NativePath
import android.view.MotionEvent
import android.view.View
import com.motoroute.data.map.OfflineDataRepository
import com.motoroute.data.map.OfflineFileKind
import com.motoroute.data.model.GeoPoint
import com.motoroute.data.model.Route
import com.motoroute.data.settings.MapStyle
import com.motoroute.domain.CameraController
import org.mapsforge.core.graphics.Cap
import org.mapsforge.core.graphics.Join
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Rotation
import org.mapsforge.map.android.graphics.AndroidBitmap
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.StreamRenderTheme
import org.mapsforge.map.view.InputListener
import kotlin.math.abs

/**
 * Owns the Mapsforge object graph.
 *
 * Kept out of Compose because these objects are expensive, must be destroyed
 * explicitly, and have to outlive recomposition. The tile cache in particular
 * is sized for a 4 GB phone: a small in-memory cache plus a file-backed second
 * level, so panning is smooth without the renderer competing with BRouter for
 * heap.
 */
class MapController(private val offlineData: OfflineDataRepository) {

    private var mapView: MapView? = null
    private var tileCache: TileCache? = null
    private var tileLayer: TileRendererLayer? = null
    private var routeLayer: Polyline? = null
    private var dataStore: MultiMapDataStore? = null
    private var currentThemeIsNight: Boolean? = null
    private var currentStyle: MapStyle = MapStyle.COLOUR

    private var positionMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var startMarker: Marker? = null
    private var lastPuckHeading = Float.NaN

    /** True once the camera has been put somewhere deliberate. */
    private var cameraPlaced = false

    /** False until the first fix has pulled the camera down to riding zoom. */
    private var riderZoomApplied = false

    val hasMaps: Boolean get() = offlineData.hasAny(OfflineFileKind.MAP)

    /**
     * Attaches the view.
     *
     * [onUserGesture] fires the moment the rider drags or pinches. Mapsforge
     * reports manual gestures separately from programmatic camera moves, which
     * is exactly what "stop snapping the map back while I am looking at
     * something" needs: the app can stop following without having to guess
     * whether a camera change came from the rider or from itself.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attach(context: Context, onUserGesture: () -> Unit): MapView {
        mapView?.let { return it }

        val view = MapView(context).apply {
            isClickable = true
            setBuiltInZoomControls(false)
            mapScaleBar.isVisible = false
            model.displayModel.setFixedTileSize(TILE_SIZE)
            model.mapViewPosition.setZoomLevelMin(CameraController.MIN_ZOOM.toByte())
            model.mapViewPosition.setZoomLevelMax(CameraController.MAX_ZOOM.toByte())
        }

        view.addInputListener(object : InputListener {
            override fun onMoveEvent() = onUserGesture()
            override fun onZoomEvent() = onUserGesture()
        })

        tileCache = AndroidUtil.createTileCache(
            context,
            "opencurv-tiles",
            view.model.displayModel.tileSize,
            SCREEN_RATIO,
            view.model.frameBufferModel.overdrawFactor,
        )

        mapView = view
        rebuildMapLayer(context)
        return view
    }

    fun detach() {
        routeLayer = null
        positionMarker = null
        destinationMarker = null
        startMarker = null
        tileLayer?.onDestroy()
        tileLayer = null
        tileCache?.destroy()
        tileCache = null
        dataStore?.close()
        dataStore = null
        mapView?.destroyAll()
        mapView = null
        currentThemeIsNight = null
        cameraPlaced = false
        riderZoomApplied = false
        lastPuckHeading = Float.NaN
    }

    /**
     * (Re)builds the tile layer from whatever .map files are imported.
     *
     * Several map files are merged through a [MultiMapDataStore] so a rider can
     * import, say, Bavaria and Tyrol and get one seamless map across the border
     * instead of two.
     */
    fun rebuildMapLayer(context: Context) {
        val view = mapView ?: return
        val cache = tileCache ?: return

        tileLayer?.let { view.layerManager.layers.remove(it); it.onDestroy() }
        dataStore?.close()

        val mapFiles = offlineData.list(OfflineFileKind.MAP)
        if (mapFiles.isEmpty()) {
            tileLayer = null
            dataStore = null
            return
        }

        val store = MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL).apply {
            mapFiles.forEach { file ->
                runCatching { addMapDataStore(MapFile(file.file), false, false) }
            }
        }
        dataStore = store

        val layer = TileRendererLayer(
            cache,
            store,
            view.model.mapViewPosition,
            false,
            true,
            true,
            AndroidGraphicFactory.INSTANCE,
        )
        layer.setXmlRenderTheme(themeFor(context, currentThemeIsNight ?: false, currentStyle))
        // Slightly larger labels than the mapsforge default: they have to be
        // readable at arm's length through a visor.
        layer.setTextScale(1.15f)

        view.layerManager.layers.add(0, layer)
        tileLayer = layer

        // Overlays must sit on top of a freshly built tile layer.
        listOfNotNull(routeLayer, startMarker, destinationMarker, positionMarker)
            .forEach { overlay ->
                view.layerManager.layers.remove(overlay)
                view.layerManager.layers.add(overlay)
            }

        // With no GPS yet, open on the data the rider actually downloaded
        // rather than on the Atlantic. This is the first thing they see after
        // a download finishes, so it has to be their region.
        if (!cameraPlaced) {
            val start = runCatching { store.startPosition() }.getOrNull()
                ?: runCatching { store.boundingBox()?.getCenterPoint() }.getOrNull()
            start?.let {
                view.model.mapViewPosition.setCenter(it)
                view.model.mapViewPosition.setZoomLevel(clampZoom(DEFAULT_OVERVIEW_ZOOM), false)
                cameraPlaced = true
            }
        }
    }

    fun applyTheme(night: Boolean, style: MapStyle) {
        if (currentThemeIsNight == night && currentStyle == style) return
        currentThemeIsNight = night
        currentStyle = style
        val view = mapView ?: return
        tileLayer?.setXmlRenderTheme(themeFor(view.context, night, style))
        tileCache?.purge()
        view.layerManager.redrawLayers()
    }

    private fun themeFor(context: Context, night: Boolean, style: MapStyle): StreamRenderTheme {
        val asset = when {
            style == MapStyle.CONTRAST && night -> "themes/opencurv_contrast_night.xml"
            style == MapStyle.CONTRAST -> "themes/opencurv_contrast_day.xml"
            night -> "themes/opencurv_colour_night.xml"
            else -> "themes/opencurv_colour_day.xml"
        }
        return StreamRenderTheme("/assets/", context.assets.open(asset))
    }

    /** Draws (or clears) the planned route. */
    fun showRoute(route: Route?, argbColor: Int) {
        val view = mapView ?: return

        routeLayer?.let { view.layerManager.layers.remove(it) }
        routeLayer = null
        if (route == null || route.isEmpty) {
            view.layerManager.redrawLayers()
            return
        }

        val paint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            setColor(argbColor)
            setStrokeWidth(ROUTE_STROKE_PX)
            setStyle(Style.STROKE)
            setStrokeCap(Cap.ROUND)
            setStrokeJoin(Join.ROUND)
        }
        val polyline = Polyline(paint, AndroidGraphicFactory.INSTANCE).apply {
            addPoints(route.points.map { LatLong(it.latitude, it.longitude) })
        }
        view.layerManager.layers.add(polyline)
        routeLayer = polyline
        // Markers belong above the line.
        listOfNotNull(startMarker, destinationMarker, positionMarker).forEach {
            view.layerManager.layers.remove(it)
            view.layerManager.layers.add(it)
        }
        view.layerManager.redrawLayers()
    }

    /**
     * Draws the rider.
     *
     * The arrow is rendered in map coordinates rather than screen coordinates,
     * so it points where the motorcycle points in both north-up and heading-up
     * mode without any extra bookkeeping. The bitmap is only redrawn when the
     * heading has actually moved, because at 1 Hz a new bitmap per fix is pure
     * garbage collection.
     */
    fun showPosition(point: GeoPoint?, headingDegrees: Double, argbColor: Int) {
        val view = mapView ?: return
        if (point == null) return
        val heading = headingDegrees.toFloat()

        val existing = positionMarker
        if (existing == null) {
            val marker = Marker(
                LatLong(point.latitude, point.longitude),
                puckBitmap(view.context, heading, argbColor),
                0,
                0,
            ).apply { isBillboard = false }
            view.layerManager.layers.add(marker)
            positionMarker = marker
            lastPuckHeading = heading
        } else {
            existing.latLong = LatLong(point.latitude, point.longitude)
            if (lastPuckHeading.isNaN() || abs(heading - lastPuckHeading) > PUCK_HEADING_STEP) {
                val old = existing.bitmap
                existing.bitmap = puckBitmap(view.context, heading, argbColor)
                lastPuckHeading = heading
                runCatching { old?.decrementRefCount() }
            }
        }
        view.layerManager.redrawLayers()
    }

    /** Marks the destination the rider tapped, or clears it. */
    fun showDestination(point: GeoPoint?, argbColor: Int) {
        destinationMarker = pin(destinationMarker, point, argbColor, filled = true)
    }

    /** Marks an explicitly chosen start, or clears it. */
    fun showStart(point: GeoPoint?, argbColor: Int) {
        startMarker = pin(startMarker, point, argbColor, filled = false)
    }

    private fun pin(
        current: Marker?,
        point: GeoPoint?,
        argbColor: Int,
        filled: Boolean,
    ): Marker? {
        val view = mapView ?: return current
        if (point == null) {
            current?.let { view.layerManager.layers.remove(it); it.onDestroy() }
            view.layerManager.redrawLayers()
            return null
        }
        val position = LatLong(point.latitude, point.longitude)
        if (current != null) {
            current.latLong = position
            view.layerManager.redrawLayers()
            return current
        }
        val bitmap = pinBitmap(view.context, argbColor, filled)
        val marker = Marker(position, bitmap, 0, -bitmap.height / 2)
        view.layerManager.layers.add(marker)
        // The rider is always on top of a pin.
        positionMarker?.let {
            view.layerManager.layers.remove(it)
            view.layerManager.layers.add(it)
        }
        view.layerManager.redrawLayers()
        return marker
    }

    /**
     * Centres the map on the rider.
     *
     * In heading-up mode the map is rotated so the direction of travel always
     * points up, and the rider is placed in the lower third of the screen so
     * most of the display shows what is coming rather than what is behind.
     */
    fun follow(
        position: GeoPoint?,
        headingDegrees: Double,
        zoom: Int?,
        headingUp: Boolean,
    ) {
        val view = mapView ?: return
        if (position == null) return

        // The map opens on the whole downloaded region; the first fix is what
        // turns that overview into a riding view. After that the zoom belongs
        // to the camera controller, or to the rider.
        val level = zoom ?: DEFAULT_FOLLOW_ZOOM.takeIf { !riderZoomApplied }
        level?.let {
            view.model.mapViewPosition.setZoomLevel(clampZoom(it), false)
            riderZoomApplied = true
        }
        view.model.mapViewPosition.setCenter(LatLong(position.latitude, position.longitude))
        cameraPlaced = true
        view.rotate(
            if (headingUp) {
                Rotation(
                    -headingDegrees.toFloat(),
                    view.width / 2f,
                    view.height * FOLLOW_PIVOT_Y,
                )
            } else {
                Rotation.NULL_ROTATION
            },
        )
    }

    fun centerOn(point: GeoPoint, zoom: Int? = null) {
        val view = mapView ?: return
        zoom?.let { view.model.mapViewPosition.setZoomLevel(clampZoom(it), false) }
        view.model.mapViewPosition.setCenter(LatLong(point.latitude, point.longitude))
        cameraPlaced = true
    }

    /** Puts the map back north-up, e.g. when the rider leaves follow mode. */
    fun resetRotation() {
        mapView?.rotate(Rotation.NULL_ROTATION)
    }

    /** Where the map is looking right now; the fallback start for a route without GPS. */
    fun center(): GeoPoint? = mapView?.model?.mapViewPosition?.center
        ?.let { GeoPoint(it.latitude, it.longitude) }

    fun zoomIn() {
        mapView?.model?.mapViewPosition?.zoomIn()
    }

    fun zoomOut() {
        mapView?.model?.mapViewPosition?.zoomOut()
    }

    /** Frames a whole route on screen, e.g. right after planning it. */
    fun showWholeRoute(route: Route) {
        val view = mapView ?: return
        if (route.isEmpty) return
        val bounds = route.bounds
        view.model.mapViewPosition.setCenter(LatLong(bounds.centerLat, bounds.centerLon))
        view.rotate(Rotation.NULL_ROTATION)
        view.model.mapViewPosition.setZoomLevel(
            zoomForSpan(bounds.maxLat - bounds.minLat, bounds.maxLon - bounds.minLon),
            false,
        )
        cameraPlaced = true
    }

    /**
     * Rough zoom for a lat/lon span. Mapsforge can compute this exactly from
     * the view dimension, but the view may not be laid out yet when a route
     * arrives, and a good guess that never crashes beats an exact answer that
     * sometimes does.
     */
    private fun zoomForSpan(latSpan: Double, lonSpan: Double): Byte {
        val span = maxOf(latSpan, lonSpan * 0.6)
        return when {
            span > 4.0 -> 6
            span > 2.0 -> 7
            span > 1.0 -> 8
            span > 0.5 -> 9
            span > 0.25 -> 10
            span > 0.12 -> 11
            span > 0.06 -> 12
            span > 0.03 -> 13
            else -> 14
        }
    }

    /**
     * A tap that does not move sets the destination; a press held in place sets
     * the start, which is how a rider plans a route for tomorrow from the
     * kitchen table instead of from wherever the GPS says they are.
     * Mapsforge's own gesture handler keeps pan and pinch working underneath.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun tapListener(
        view: MapView,
        onTap: (GeoPoint) -> Unit,
        onLongPress: (GeoPoint) -> Unit,
    ): View.OnTouchListener {
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        return View.OnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = event.eventTime
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.hypot(event.x - downX, event.y - downY)
                    val held = event.eventTime - downTime
                    if (moved < TAP_SLOP_PX) {
                        val point = runCatching {
                            view.mapViewProjection.fromPixels(
                                event.x.toDouble(),
                                event.y.toDouble(),
                            )
                        }.getOrNull()
                        if (point != null) {
                            val geo = GeoPoint(point.latitude, point.longitude)
                            if (held >= LONG_PRESS_MILLIS) onLongPress(geo) else onTap(geo)
                        }
                    }
                }
            }
            // Always let Mapsforge handle the gesture too.
            v.onTouchEvent(event)
        }
    }

    // ---- marker bitmaps ---------------------------------------------------

    /**
     * The rider: a heading cone under a white-ringed dot. Drawn here rather
     * than shipped as a drawable because it has to be re-rendered at the
     * current heading anyway.
     */
    private fun puckBitmap(
        context: Context,
        headingDegrees: Float,
        argbColor: Int,
    ): org.mapsforge.core.graphics.Bitmap {
        val density = context.resources.displayMetrics.density
        val size = (PUCK_DP * density).toInt().coerceAtLeast(24)
        val bitmap = android.graphics.Bitmap.createBitmap(
            size,
            size,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = AndroidCanvas(bitmap)
        val centre = size / 2f
        val paint = NativePaint(NativePaint.ANTI_ALIAS_FLAG)

        canvas.save()
        canvas.rotate(headingDegrees, centre, centre)
        val cone = NativePath().apply {
            moveTo(centre, centre - size * 0.46f)
            lineTo(centre - size * 0.22f, centre + size * 0.06f)
            lineTo(centre + size * 0.22f, centre + size * 0.06f)
            close()
        }
        paint.style = NativePaint.Style.FILL
        paint.color = argbColor
        paint.alpha = 210
        canvas.drawPath(cone, paint)
        canvas.restore()

        paint.alpha = 255
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(centre, centre, size * 0.19f, paint)
        paint.color = argbColor
        canvas.drawCircle(centre, centre, size * 0.13f, paint)

        return AndroidBitmap(bitmap)
    }

    /** A destination pin whose tip sits on the point. */
    private fun pinBitmap(
        context: Context,
        argbColor: Int,
        filled: Boolean,
    ): org.mapsforge.core.graphics.Bitmap {
        val density = context.resources.displayMetrics.density
        val width = (PIN_DP * density).toInt().coerceAtLeast(20)
        val height = (PIN_DP * 1.4f * density).toInt().coerceAtLeast(28)
        val bitmap = android.graphics.Bitmap.createBitmap(
            width,
            height,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = AndroidCanvas(bitmap)
        val paint = NativePaint(NativePaint.ANTI_ALIAS_FLAG)
        val centreX = width / 2f
        val headRadius = width * 0.36f
        val headY = headRadius + width * 0.08f

        val body = NativePath().apply {
            moveTo(centreX, height.toFloat())
            lineTo(centreX - headRadius * 0.75f, headY + headRadius * 0.72f)
            lineTo(centreX + headRadius * 0.75f, headY + headRadius * 0.72f)
            close()
        }

        paint.style = NativePaint.Style.FILL
        paint.color = argbColor
        canvas.drawPath(body, paint)
        canvas.drawCircle(centreX, headY, headRadius, paint)

        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(centreX, headY, headRadius * (if (filled) 0.34f else 0.52f), paint)

        return AndroidBitmap(bitmap)
    }

    private companion object {
        const val TILE_SIZE = 256

        /**
         * Fraction of the screen the in-memory tile cache is sized for. 1.5 is
         * modest on purpose: on a 4 GB device the renderer sharing heap with
         * BRouter is what causes stutter, not a cache miss.
         */
        const val SCREEN_RATIO = 1.5f

        /** 8-10 dp of route line, in pixels at mdpi-ish density. */
        const val ROUTE_STROKE_PX = 18f

        /** Rider sits at 72 % down the screen in follow mode. */
        const val FOLLOW_PIVOT_Y = 0.72f

        const val TAP_SLOP_PX = 24f
        const val LONG_PRESS_MILLIS = 450L

        const val PUCK_DP = 46f
        const val PIN_DP = 30f

        /** Redraw the arrow only past this much turn, in degrees. */
        const val PUCK_HEADING_STEP = 6f

        /** Wide enough to see a whole federal state before the first fix. */
        const val DEFAULT_OVERVIEW_ZOOM = 9

        /** Where the camera lands once it knows where the rider is. */
        const val DEFAULT_FOLLOW_ZOOM = 14
    }
}
