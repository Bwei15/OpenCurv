package com.motoroute.ui.map

import android.content.Context
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as NativePaint
import android.graphics.Path as NativePath
import com.motoroute.data.map.OfflineDataRepository
import com.motoroute.data.map.OfflineFileKind
import com.motoroute.data.model.GeoPoint
import com.motoroute.data.model.Route
import com.motoroute.data.settings.MapStyle
import com.motoroute.domain.CameraController
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Owns the MapLibre object graph.
 *
 * Kept out of Compose for the same reason the Mapsforge version needed a
 * dedicated owner: a [MapView] is expensive, carries its own Android
 * lifecycle, and has to outlive individual recompositions untouched.
 *
 * The vector style itself is read from `assets/maplibre/style_day.json` /
 * `style_night.json` - both are the palette from `1.Doku/Map_Design.md`,
 * corrected (the shipped file was truncated, invalid JSON, and pointed at
 * MapTiler's servers and a remote sprite/glyph host) and pointed instead at
 * whatever `.pmtiles` archive is sitting in [OfflineDataRepository.mapTilesDir]
 * and at the glyph PBFs bundled under `assets/maplibre/glyphs/`. Nothing a
 * style load touches ever leaves the device - see `1.Doku/Karte_MapLibre.md`
 * for how that was verified in airplane mode.
 *
 * Route, position puck, destination and start pin are drawn on top of the
 * style rather than being part of it, as three small [GeoJsonSource]s feeding
 * a [LineLayer] pair (a dark casing under a bright core - the two-tone
 * treatment `Design_System.md` §2.6 asks for anything drawn directly on the
 * map) and two [SymbolLayer]s. All of it is destroyed by a style reload
 * (a day/night switch throws away every source and layer that belonged to the
 * old [Style] object), so it is re-added in [attachOverlayLayers] every time
 * and immediately redrawn from the last known state in [reapplyOverlays].
 */
class MapController(private val offlineData: OfflineDataRepository) {

    private var mapView: MapView? = null
    private var mapLibreMap: MapLibreMap? = null
    private var style: Style? = null

    private var currentNight: Boolean? = null
    private var currentMapStyle: MapStyle = MapStyle.COLOUR

    /** The (night, style) key the in-flight `setStyle()` call was made for, if any. */
    private var pendingStyleKey: Pair<Boolean, MapStyle>? = null

    // The last thing each overlay was asked to draw, reapplied whenever the
    // style (re)loads - a day/night switch must not lose the plan.
    private var lastRoute: Route? = null
    private var lastRouteColor = DEFAULT_ROUTE_ARGB
    private var lastRouteCasingColor = DEFAULT_CASING_ARGB
    private var lastDestination: GeoPoint? = null
    private var lastDestinationColor = DEFAULT_PIN_ARGB
    private var lastStart: GeoPoint? = null
    private var lastStartColor = DEFAULT_PIN_ARGB
    private var lastPosition: GeoPoint? = null
    private var lastHeading = 0.0
    private var lastPuckColor = DEFAULT_PIN_ARGB

    // Which colour is currently baked into each registered style image - so a
    // style reload always re-registers them, but a same-colour redraw within
    // a session does not re-encode a bitmap for no reason.
    private var registeredDestinationColor: Int? = null
    private var registeredStartColor: Int? = null
    private var registeredPuckColor: Int? = null

    /** True once the camera has been put somewhere deliberate. */
    private var cameraPlaced = false

    /** False until the first fix has pulled the camera down to riding zoom. */
    private var riderZoomApplied = false

    /** A camera move asked for while no map was ready yet. */
    private var pendingCenter: GeoPoint? = null
    private var pendingZoom: Int? = null

    private var tapCallback: ((GeoPoint) -> Unit)? = null
    private var longPressCallback: ((GeoPoint) -> Unit)? = null
    private var gestureCallback: () -> Unit = {}

    /** Whether there is a vector-tile archive to actually draw. Without one the map is a flat canvas. */
    val hasMapTiles: Boolean get() = offlineData.hasAny(OfflineFileKind.MAPTILES)

    // ---- attach / detach ---------------------------------------------------

    /**
     * Attaches the view.
     *
     * [initialNight]/[initialStyle] seed the very first style load. They
     * matter because the map is torn down and rebuilt every time the rider
     * leaves the map screen (Settings, Search, Data) and comes back -
     * necessary because a [MapView] holds a GL surface tied to this
     * particular composition - and a day/night switch made *while the map
     * was gone* only reaches [applyTheme] once the map is back. Seeding the
     * correct theme here means that first style load already asks for the
     * right one, instead of loading day and then immediately reloading
     * night: two `setStyle()` calls close together were, in practice, a race
     * that could leave the PMTiles source only half attached and the map
     * showing nothing but its background colour.
     *
     * [onUserGesture] fires the moment the rider pans, pinches, rotates or
     * two-finger-tilts by hand - the four MapLibre gesture-begin callbacks
     * below. That is what lets the app stop following without guessing
     * whether a camera change came from the rider or from itself.
     */
    fun attach(
        context: Context,
        initialNight: Boolean,
        initialStyle: MapStyle,
        onUserGesture: () -> Unit,
    ): MapView {
        gestureCallback = onUserGesture
        mapView?.let { return it }

        currentNight = initialNight
        currentMapStyle = initialStyle

        val view = MapView(context)
        view.onCreate(null)
        mapView = view

        view.getMapAsync { map ->
            mapLibreMap = map
            map.setMinZoomPreference(CameraController.MIN_ZOOM.toDouble())
            map.setMaxZoomPreference(CameraController.MAX_ZOOM.toDouble())

            // Our own buttons cover recentring and day/night; the built-in
            // chrome would be a second, uninvited set of controls on a screen
            // that already has to justify every pixel per Design_System.md.
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isLogoEnabled = false
            map.uiSettings.isAttributionEnabled = false

            map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) = gestureCallback()
                override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) = Unit
                override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) = Unit
            })
            map.addOnRotateListener(object : MapLibreMap.OnRotateListener {
                override fun onRotateBegin(detector: org.maplibre.android.gestures.RotateGestureDetector) = gestureCallback()
                override fun onRotate(detector: org.maplibre.android.gestures.RotateGestureDetector) = Unit
                override fun onRotateEnd(detector: org.maplibre.android.gestures.RotateGestureDetector) = Unit
            })
            map.addOnScaleListener(object : MapLibreMap.OnScaleListener {
                override fun onScaleBegin(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) = gestureCallback()
                override fun onScale(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) = Unit
                override fun onScaleEnd(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) = Unit
            })
            map.addOnShoveListener(object : MapLibreMap.OnShoveListener {
                override fun onShoveBegin(detector: org.maplibre.android.gestures.ShoveGestureDetector) = gestureCallback()
                override fun onShove(detector: org.maplibre.android.gestures.ShoveGestureDetector) = Unit
                override fun onShoveEnd(detector: org.maplibre.android.gestures.ShoveGestureDetector) = Unit
            })
            map.addOnMapClickListener { latLng ->
                tapCallback?.invoke(latLng.toGeoPoint())
                tapCallback != null
            }
            map.addOnMapLongClickListener { latLng ->
                longPressCallback?.invoke(latLng.toGeoPoint())
                longPressCallback != null
            }

            loadStyle()

            pendingCenter?.let { point ->
                centerOn(point, pendingZoom)
                pendingCenter = null
                pendingZoom = null
            }
        }
        return view
    }

    /** Forward the host lifecycle - MapLibre's GL surface needs every one of these. */
    fun onStart() = mapView?.onStart()
    fun onResume() = mapView?.onResume()
    fun onPause() = mapView?.onPause()
    fun onStop() = mapView?.onStop()

    fun detach() {
        mapLibreMap?.let {
            pendingCenter = center()
            pendingZoom = it.cameraPosition.zoom.toInt()
        }
        style = null
        mapLibreMap = null
        registeredDestinationColor = null
        registeredStartColor = null
        registeredPuckColor = null
        mapView?.onPause()
        mapView?.onStop()
        mapView?.onDestroy()
        mapView = null
        currentNight = null
        pendingStyleKey = null
        cameraPlaced = false
        riderZoomApplied = false
    }

    // ---- style / theme ------------------------------------------------------

    fun applyTheme(night: Boolean, style: MapStyle) {
        if (currentNight == night && currentMapStyle == style) return
        currentNight = night
        currentMapStyle = style
        loadStyle()
    }

    /**
     * (Re)loads the vector style and points its source at whatever `.pmtiles`
     * file is on disk right now. Called on attach, on a day/night switch, and
     * whenever [rebuildMapLayer] is told the file set may have changed (e.g.
     * after an import or a download finishes).
     */
    private fun loadStyle() {
        val map = mapLibreMap ?: return
        val context = mapView?.context ?: return
        val key = (currentNight ?: false) to currentMapStyle
        pendingStyleKey = key
        val json = buildStyleJson(context, key.first)
        registeredDestinationColor = null
        registeredStartColor = null
        registeredPuckColor = null
        map.setStyle(Style.Builder().fromJson(json)) { loaded ->
            // A second loadStyle() can start (and finish) while this one was
            // still in flight - e.g. attach() seeds one theme and a settings
            // change moments earlier asks for the other. Whichever call is
            // still the current one wins; a superseded callback must not
            // overwrite [style] with a Style object nobody wants anymore.
            if (pendingStyleKey != key) return@setStyle
            pendingStyleKey = null
            style = loaded
            attachOverlayLayers(loaded, context)
            reapplyOverlays()
        }
    }

    /**
     * Reads the day or night style template from assets and points its
     * `openmaptiles` source at the installed PMTiles archive.
     *
     * With no archive installed yet, the layers that reference it are
     * stripped instead of left dangling - a style whose layers reference a
     * missing source is invalid, and a blank canvas in the app's own
     * background colour is a far better empty state than a render error.
     */
    private fun buildStyleJson(context: Context, night: Boolean): String {
        val asset = if (night) "maplibre/style_night.json" else "maplibre/style_day.json"
        val template = context.assets.open(asset).bufferedReader().use { it.readText() }
        val pmtilesFile = (offlineData.mapTilesDir.listFiles { f -> f.isFile && f.extension.equals("pmtiles", ignoreCase = true) }.orEmpty().toList() +
            offlineData.mapDir.listFiles { f -> f.isFile && f.extension.equals("pmtiles", ignoreCase = true) }.orEmpty().toList())
            .sortedBy { it.name }
            .firstOrNull()

        if (pmtilesFile != null) {
            val url = "pmtiles://file://${pmtilesFile.absolutePath}"
            return template.replace(PMTILES_PLACEHOLDER, url)
        }

        val root = JSONObject(template)
        root.remove("sources")
        val layers = root.getJSONArray("layers")
        val kept = JSONArray()
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            if (!layer.has("source")) kept.put(layer)
        }
        root.put("layers", kept)
        return root.toString()
    }

    /** Called after a download or import so a newly-arrived `.pmtiles` gets picked up. */
    fun rebuildMapLayer(@Suppress("UNUSED_PARAMETER") context: Context) {
        loadStyle()
    }

    private fun attachOverlayLayers(style: Style, context: Context) {
        if (style.getSource(ROUTE_SOURCE) == null) {
            style.addSource(GeoJsonSource(ROUTE_SOURCE))
            style.addLayer(
                LineLayer(ROUTE_CASING_LAYER, ROUTE_SOURCE).apply {
                    setProperties(
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineWidth(ROUTE_CASING_WIDTH_DP),
                        PropertyFactory.lineColor(lastRouteCasingColor),
                    )
                },
            )
            style.addLayer(
                LineLayer(ROUTE_CORE_LAYER, ROUTE_SOURCE).apply {
                    setProperties(
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineWidth(ROUTE_CORE_WIDTH_DP),
                        PropertyFactory.lineColor(lastRouteColor),
                    )
                },
            )
        }
        if (style.getSource(START_SOURCE) == null) {
            style.addImage(START_ICON, pinBitmap(context, lastStartColor, filled = false))
            registeredStartColor = lastStartColor
            style.addSource(GeoJsonSource(START_SOURCE))
            style.addLayer(
                SymbolLayer(START_LAYER, START_SOURCE).apply {
                    setProperties(
                        PropertyFactory.iconImage(START_ICON),
                        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                    )
                },
            )
        }
        if (style.getSource(DEST_SOURCE) == null) {
            style.addImage(DEST_ICON, pinBitmap(context, lastDestinationColor, filled = true))
            registeredDestinationColor = lastDestinationColor
            style.addSource(GeoJsonSource(DEST_SOURCE))
            style.addLayer(
                SymbolLayer(DEST_LAYER, DEST_SOURCE).apply {
                    setProperties(
                        PropertyFactory.iconImage(DEST_ICON),
                        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                    )
                },
            )
        }
        if (style.getSource(PUCK_SOURCE) == null) {
            style.addImage(PUCK_ICON, puckBitmap(context, lastPuckColor))
            registeredPuckColor = lastPuckColor
            style.addSource(GeoJsonSource(PUCK_SOURCE))
            style.addLayer(
                SymbolLayer(PUCK_LAYER, PUCK_SOURCE).apply {
                    setProperties(
                        PropertyFactory.iconImage(PUCK_ICON),
                        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                        PropertyFactory.iconRotate(lastHeading.toFloat()),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                    )
                },
            )
        }
    }

    /** Redraws whatever the rider was last looking at, after a style reload wiped the layers. */
    private fun reapplyOverlays() {
        showRoute(lastRoute, lastRouteColor, lastRouteCasingColor)
        showDestination(lastDestination, lastDestinationColor)
        showStart(lastStart, lastStartColor)
        showPosition(lastPosition, lastHeading, lastPuckColor)
    }

    // ---- overlays -----------------------------------------------------------

    /** Draws (or clears) the planned route as a two-tone line: dark casing, bright core. */
    fun showRoute(route: Route?, coreArgb: Int, casingArgb: Int = coreArgb) {
        lastRoute = route
        lastRouteColor = coreArgb
        lastRouteCasingColor = casingArgb

        val activeStyle = style ?: return
        val source = activeStyle.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return
        activeStyle.getLayerAs<LineLayer>(ROUTE_CASING_LAYER)?.setProperties(PropertyFactory.lineColor(casingArgb))
        activeStyle.getLayerAs<LineLayer>(ROUTE_CORE_LAYER)?.setProperties(PropertyFactory.lineColor(coreArgb))

        if (route == null || route.isEmpty) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }
        val points = route.points.map { Point.fromLngLat(it.longitude, it.latitude) }
        source.setGeoJson(LineString.fromLngLats(points))
    }

    /** Draws the rider: a heading cone under a white-ringed dot, rotated on the GPU - no bitmap churn per fix. */
    fun showPosition(point: GeoPoint?, headingDegrees: Double, argbColor: Int) {
        lastPosition = point
        lastHeading = headingDegrees
        lastPuckColor = argbColor

        val activeStyle = style ?: return
        val context = mapView?.context ?: return
        val source = activeStyle.getSourceAs<GeoJsonSource>(PUCK_SOURCE) ?: return

        if (registeredPuckColor != argbColor) {
            activeStyle.addImage(PUCK_ICON, puckBitmap(context, argbColor))
            registeredPuckColor = argbColor
        }
        activeStyle.getLayerAs<SymbolLayer>(PUCK_LAYER)?.setProperties(PropertyFactory.iconRotate(headingDegrees.toFloat()))

        if (point == null) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }
        source.setGeoJson(Point.fromLngLat(point.longitude, point.latitude))
    }

    /** Marks the destination the rider tapped, or clears it. */
    fun showDestination(point: GeoPoint?, argbColor: Int) {
        lastDestination = point
        lastDestinationColor = argbColor
        if (drawPin(DEST_SOURCE, DEST_ICON, point, argbColor, registeredDestinationColor, filled = true)) {
            registeredDestinationColor = argbColor
        }
    }

    /** Marks an explicitly chosen start, or clears it. */
    fun showStart(point: GeoPoint?, argbColor: Int) {
        lastStart = point
        lastStartColor = argbColor
        if (drawPin(START_SOURCE, START_ICON, point, argbColor, registeredStartColor, filled = false)) {
            registeredStartColor = argbColor
        }
    }

    /** @return whether [argbColor] was newly baked into [imageId] as a style image. */
    private fun drawPin(
        sourceId: String,
        imageId: String,
        point: GeoPoint?,
        argbColor: Int,
        currentlyRegistered: Int?,
        filled: Boolean,
    ): Boolean {
        val activeStyle = style ?: return false
        val context = mapView?.context ?: return false
        val source = activeStyle.getSourceAs<GeoJsonSource>(sourceId) ?: return false

        val reRegistered = currentlyRegistered != argbColor
        if (reRegistered) {
            activeStyle.addImage(imageId, pinBitmap(context, argbColor, filled))
        }

        if (point == null) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        } else {
            source.setGeoJson(Point.fromLngLat(point.longitude, point.latitude))
        }
        return reRegistered
    }

    // ---- camera ---------------------------------------------------------

    /**
     * Centres the map on the rider, with a real 3-D tilt in the direction of
     * travel instead of the projective view-skew Mapsforge needed - MapLibre
     * renders the perspective for real, so labels stay upright instead of
     * leaning with it.
     *
     * Camera moves are eased rather than snapped: at roughly one call per GPS
     * fix, an eased transition is what turns the per-fix jump into something
     * that reads as tracking, and it is what turns a change in [tiltDegrees]
     * into the stepless overview-to-riding transition the spec asks for -
     * tilt travels inside the same eased camera move as position and bearing,
     * never as a separate jump cut.
     */
    fun follow(
        position: GeoPoint?,
        headingDegrees: Double,
        zoom: Int?,
        headingUp: Boolean,
        tiltDegrees: Float = 0f,
    ) {
        val map = mapLibreMap ?: return
        if (position == null) return

        val level = zoom ?: DEFAULT_FOLLOW_ZOOM.takeIf { !riderZoomApplied }
        level?.let { riderZoomApplied = true }
        val targetZoom = (level ?: map.cameraPosition.zoom.toInt())
            .coerceIn(CameraController.MIN_ZOOM, CameraController.MAX_ZOOM)

        // Tilted, the rider sits low in the frame so most of the screen shows
        // the road ahead rather than what is behind - top padding is how a
        // real 3-D camera does what the old view-skew did with a transform
        // pivot. Flat, there is no "ahead" to make room for.
        val viewHeight = mapView?.height?.toDouble() ?: 0.0
        val topPadding = if (tiltDegrees > 0f) viewHeight * FOLLOW_PIVOT_TOP_PADDING else 0.0

        val target = CameraPosition.Builder()
            .target(LatLng(position.latitude, position.longitude))
            .zoom(targetZoom.toDouble())
            .bearing(if (headingUp) headingDegrees else 0.0)
            .tilt(tiltDegrees.toDouble())
            .padding(doubleArrayOf(0.0, topPadding, 0.0, 0.0))
            .build()

        map.easeCamera(CameraUpdateFactory.newCameraPosition(target), FOLLOW_EASE_MS)
        cameraPlaced = true
    }

    fun centerOn(point: GeoPoint, zoom: Int? = null) {
        val map = mapLibreMap
        if (map == null) {
            pendingCenter = point
            pendingZoom = zoom
            return
        }
        zoom?.let { riderZoomApplied = true }
        val target = CameraPosition.Builder()
            .target(LatLng(point.latitude, point.longitude))
            .apply { zoom?.let { z -> zoom(z.coerceIn(CameraController.MIN_ZOOM, CameraController.MAX_ZOOM).toDouble()) } }
            .build()
        map.easeCamera(CameraUpdateFactory.newCameraPosition(target), CAMERA_EASE_MS)
        cameraPlaced = true
    }

    /** Puts the map back north-up and flat, e.g. when the rider leaves follow mode. */
    fun resetRotation() {
        val map = mapLibreMap ?: return
        val current = map.cameraPosition
        val target = CameraPosition.Builder(current).bearing(0.0).tilt(0.0).build()
        map.easeCamera(CameraUpdateFactory.newCameraPosition(target), CAMERA_EASE_MS)
    }

    /** Where the map is looking right now; the fallback start for a route without GPS. */
    fun center(): GeoPoint? =
        mapLibreMap?.cameraPosition?.target?.let { GeoPoint(it.latitude, it.longitude) }

    fun zoomIn() {
        mapLibreMap?.animateCamera(CameraUpdateFactory.zoomIn(), CAMERA_EASE_MS)
    }

    fun zoomOut() {
        mapLibreMap?.animateCamera(CameraUpdateFactory.zoomOut(), CAMERA_EASE_MS)
    }

    /** Frames a whole route on screen, e.g. right after planning it. */
    fun showWholeRoute(route: Route) {
        val map = mapLibreMap ?: return
        if (route.isEmpty) return
        val bounds = route.bounds
        val latLngBounds = LatLngBounds.Builder()
            .include(LatLng(bounds.minLat, bounds.minLon))
            .include(LatLng(bounds.maxLat, bounds.maxLon))
            .build()
        // A framed route is a fresh look at the plan, not a ride in progress:
        // flat and north-up, whatever the camera was doing before.
        map.easeCamera(CameraUpdateFactory.newLatLngBounds(latLngBounds, ROUTE_FRAME_PADDING_PX), CAMERA_EASE_MS)
        val flat = CameraPosition.Builder(map.cameraPosition).bearing(0.0).tilt(0.0).build()
        map.easeCamera(CameraUpdateFactory.newCameraPosition(flat), CAMERA_EASE_MS)
        cameraPlaced = true
    }

    // ---- tap / long-press -----------------------------------------------

    /** `null` disables that gesture entirely (e.g. while navigating). */
    fun setTapHandlers(onTap: ((GeoPoint) -> Unit)?, onLongPress: ((GeoPoint) -> Unit)?) {
        tapCallback = onTap
        longPressCallback = onLongPress
    }

    // ---- bitmaps ------------------------------------------------------------

    /**
     * The rider: a heading cone (pointing north/up) under a white-ringed dot.
     * Drawn once per colour - the heading itself is applied on the GPU via
     * the symbol layer's `icon-rotate`, not by redrawing this bitmap.
     */
    private fun puckBitmap(context: Context, argbColor: Int): android.graphics.Bitmap {
        val density = context.resources.displayMetrics.density
        val size = (PUCK_DP * density).toInt().coerceAtLeast(24)
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        val centre = size / 2f
        val paint = NativePaint(NativePaint.ANTI_ALIAS_FLAG)

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

        paint.alpha = 255
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(centre, centre, size * 0.19f, paint)
        paint.color = argbColor
        canvas.drawCircle(centre, centre, size * 0.13f, paint)

        return bitmap
    }

    /** A destination or start pin whose tip sits on the point (icon-anchor: bottom). */
    private fun pinBitmap(context: Context, argbColor: Int, filled: Boolean): android.graphics.Bitmap {
        val density = context.resources.displayMetrics.density
        val width = (PIN_DP * density).toInt().coerceAtLeast(20)
        val height = (PIN_DP * 1.4f * density).toInt().coerceAtLeast(28)
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
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

        return bitmap
    }

    private companion object {
        const val PMTILES_PLACEHOLDER = "__PMTILES_URL__"

        const val ROUTE_SOURCE = "opencurv-route"
        const val ROUTE_CASING_LAYER = "opencurv-route-casing"
        const val ROUTE_CORE_LAYER = "opencurv-route-core"

        const val START_SOURCE = "opencurv-start"
        const val START_LAYER = "opencurv-start-layer"
        const val START_ICON = "opencurv-start-icon"

        const val DEST_SOURCE = "opencurv-destination"
        const val DEST_LAYER = "opencurv-destination-layer"
        const val DEST_ICON = "opencurv-destination-icon"

        const val PUCK_SOURCE = "opencurv-puck"
        const val PUCK_LAYER = "opencurv-puck-layer"
        const val PUCK_ICON = "opencurv-puck-icon"

        /** Route line widths in dp - a dark casing under a bright core (Design_System.md §2.6). */
        const val ROUTE_CASING_WIDTH_DP = 10f
        const val ROUTE_CORE_WIDTH_DP = 6f

        const val DEFAULT_ROUTE_ARGB = android.graphics.Color.BLUE
        const val DEFAULT_CASING_ARGB = android.graphics.Color.BLACK
        const val DEFAULT_PIN_ARGB = android.graphics.Color.RED

        const val PUCK_DP = 46f
        const val PIN_DP = 30f

        /** Where the camera lands once it knows where the rider is. */
        const val DEFAULT_FOLLOW_ZOOM = 14

        /** Roughly one GPS fix apart - long enough to read as tracking, not a slideshow. */
        const val FOLLOW_EASE_MS = 900

        /** Fraction of the view height reserved above the rider while tilted. */
        const val FOLLOW_PIVOT_TOP_PADDING = 0.44

        /** Discrete user actions (recentre, a tap, a route being framed). */
        const val CAMERA_EASE_MS = 400

        const val ROUTE_FRAME_PADDING_PX = 96
    }
}

/** Convenience: turns a MapLibre LatLng into our own point type. */
fun LatLng.toGeoPoint(): GeoPoint = GeoPoint(latitude, longitude)

/** Convenience: and back. */
fun GeoPoint.toLatLng(): LatLng = LatLng(latitude, longitude)

/** Zoom clamped to what the renderer and the data can actually serve. */
fun clampZoom(zoom: Int): Int = zoom.coerceIn(CameraController.MIN_ZOOM, CameraController.MAX_ZOOM)
