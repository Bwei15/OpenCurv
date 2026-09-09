package com.motoroute.ui.map

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import com.motoroute.data.map.OfflineDataRepository
import com.motoroute.data.map.OfflineFileKind
import com.motoroute.data.model.GeoPoint
import com.motoroute.data.model.Route
import com.motoroute.domain.CameraController
import org.mapsforge.core.graphics.Cap
import org.mapsforge.core.graphics.Join
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Rotation
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.StreamRenderTheme

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

    val hasMaps: Boolean get() = offlineData.hasAny(OfflineFileKind.MAP)

    @SuppressLint("ClickableViewAccessibility")
    fun attach(context: Context): MapView {
        mapView?.let { return it }

        val view = MapView(context).apply {
            isClickable = true
            setBuiltInZoomControls(false)
            mapScaleBar.isVisible = false
            model.displayModel.setFixedTileSize(TILE_SIZE)
            model.mapViewPosition.setZoomLevelMin(CameraController.MIN_ZOOM.toByte())
            model.mapViewPosition.setZoomLevelMax(CameraController.MAX_ZOOM.toByte())
        }

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
        tileLayer?.onDestroy()
        tileLayer = null
        tileCache?.destroy()
        tileCache = null
        dataStore?.close()
        dataStore = null
        mapView?.destroyAll()
        mapView = null
        currentThemeIsNight = null
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
        layer.setXmlRenderTheme(themeFor(context, currentThemeIsNight ?: false))
        // Slightly larger labels than the mapsforge default: they have to be
        // readable at arm's length through a visor.
        layer.setTextScale(1.15f)

        view.layerManager.layers.add(0, layer)
        tileLayer = layer

        // The route overlay must sit on top of a freshly built tile layer.
        routeLayer?.let {
            view.layerManager.layers.remove(it)
            view.layerManager.layers.add(it)
        }
    }

    fun applyTheme(night: Boolean) {
        if (currentThemeIsNight == night) return
        currentThemeIsNight = night
        val view = mapView ?: return
        tileLayer?.setXmlRenderTheme(themeFor(view.context, night))
        tileCache?.purge()
        view.layerManager.redrawLayers()
    }

    private fun themeFor(context: Context, night: Boolean): StreamRenderTheme {
        val asset = if (night) "themes/opencurv_night.xml" else "themes/opencurv_day.xml"
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
        view.layerManager.redrawLayers()
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
        zoom: Int,
        headingUp: Boolean,
    ) {
        val view = mapView ?: return
        if (position == null) return

        view.model.mapViewPosition.setZoomLevel(clampZoom(zoom), false)
        view.model.mapViewPosition.setCenter(LatLong(position.latitude, position.longitude))
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
    }

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
     * Long-press-free tap handling: a tap that does not move is a map tap.
     * Mapsforge's own gesture handler keeps pan and pinch working underneath.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun tapListener(view: MapView, onTap: (GeoPoint) -> Unit): View.OnTouchListener {
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
                    val quick = event.eventTime - downTime < TAP_MILLIS
                    if (moved < TAP_SLOP_PX && quick) {
                        runCatching {
                            view.mapViewProjection.fromPixels(
                                event.x.toDouble(),
                                event.y.toDouble(),
                            )
                        }.getOrNull()?.let { onTap(GeoPoint(it.latitude, it.longitude)) }
                    }
                }
            }
            // Always let Mapsforge handle the gesture too.
            v.onTouchEvent(event)
        }
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
        const val TAP_MILLIS = 400L
    }
}
