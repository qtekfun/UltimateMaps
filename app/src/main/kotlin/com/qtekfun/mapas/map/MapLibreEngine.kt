package com.qtekfun.mapas.map

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.map.CameraState
import com.qtekfun.mapas.core.map.CameraStateStore
import com.qtekfun.mapas.core.map.MapEngine
import com.qtekfun.mapas.core.map.MapTheme
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import android.graphics.RectF
import com.qtekfun.mapas.core.map.FuelPin
import com.qtekfun.mapas.core.map.GeoBounds
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.symbolSortKey
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textOptional
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * [MapEngine] on MapLibre Native reading local PMTiles (spike option C render path).
 *
 * Render-loop discipline: no listener is registered on per-frame callbacks. The only callback is
 * camera-idle (end of a gesture/animation), which saves the camera and updates the compass. The
 * user-position and pin overlays update at GNSS rate or on demand, never per frame.
 * Performance of this class has not been measured (no device available).
 */
class MapLibreEngine(
    context: Context,
    private val files: MapFiles,
    private val store: CameraStateStore,
    initialTheme: MapTheme,
    private val onCameraIdle: (CameraState) -> Unit = {},
) : MapEngine, DefaultLifecycleObserver {

    private val main = Handler(Looper.getMainLooper())
    private var map: MapLibreMap? = null
    private var theme = initialTheme
    private var loadedTheme: MapTheme? = null
    private var loadedTiles: String? = null
    private var pendingUser: LatLon? = null
    private var pendingPin: LatLon? = null
    private var pendingMarkers: List<LatLon> = emptyList()
    private var pendingCamera: CameraState? = null
    private var lastIdle: CameraState
    private var closed = false
    private var pendingRoute: List<LatLon> = emptyList()
    private var routeFit = false
    private var tapListener: ((LatLon) -> Unit)? = null
    private var routeSource: GeoJsonSource? = null
    private var pendingFuel: List<FuelPin> = emptyList()
    private var fuelSource: GeoJsonSource? = null
    private var fuelTapListener: ((String) -> Unit)? = null
    private var viewportListener: ((GeoBounds, Double) -> Unit)? = null

    // Sources belong to one style: they are recreated on every style load (day/night switch).
    private var userSource: GeoJsonSource? = null
    private var pinSource: GeoJsonSource? = null
    private var markersSource: GeoJsonSource? = null

    /** The view to host. Created with the saved camera so the first frame already shows the last state. */
    val view: MapView

    init {
        MapLibre.getInstance(context.applicationContext)
        val initial = store.load() ?: CameraState.DEFAULT
        lastIdle = initial
        val options = MapLibreMapOptions.createFromAttributes(context)
            .camera(initial.toPosition())
            .attributionEnabled(false) // we draw our own always-visible OSM attribution (RF-13)
            .logoEnabled(false)
            .compassEnabled(false)
        view = MapView(context, options)
        view.onCreate(null)
        view.getMapAsync { m ->
            map = m
            m.addOnCameraIdleListener { handleIdle(m) }
            m.addOnMapClickListener { p -> handleTap(p) }
            pendingCamera?.let { m.moveCamera(CameraUpdateFactory.newCameraPosition(it.toPosition())); pendingCamera = null }
            loadStyle()
        }
    }

    // --- MapEngine ---

    override fun setCamera(center: LatLon, zoom: Double) {
        val m = map
        val state = CameraState(center, zoom, lastIdle.bearing, lastIdle.tilt)
        if (m == null) pendingCamera = state else m.moveCamera(CameraUpdateFactory.newCameraPosition(state.toPosition()))
    }

    override fun camera(): Pair<LatLon, Double> = lastIdle.let { it.center to it.zoom }

    override fun cameraState(): CameraState = map?.cameraPosition?.toState() ?: lastIdle

    override fun animateTo(state: CameraState, durationMillis: Int) {
        val m = map
        if (m == null) {
            pendingCamera = state
        } else if (durationMillis <= 0) {
            m.moveCamera(CameraUpdateFactory.newCameraPosition(state.toPosition()))
        } else {
            m.animateCamera(CameraUpdateFactory.newCameraPosition(state.toPosition()), durationMillis)
        }
    }

    override fun resetNorth() {
        val m = map ?: return
        val p = m.cameraPosition
        val north = CameraPosition.Builder(p).bearing(0.0).tilt(0.0).build()
        m.animateCamera(CameraUpdateFactory.newCameraPosition(north), 300)
    }

    override fun setTheme(theme: MapTheme) {
        if (this.theme == theme && loadedTheme == theme) return
        this.theme = theme
        if (map != null) loadStyle()
    }

    override fun showUserLocation(point: LatLon?, accuracyMeters: Float?) {
        pendingUser = point
        pushOverlay(userSource, point)
    }

    override fun showPin(point: LatLon?) {
        pendingPin = point
        pushOverlay(pinSource, point)
    }

    override fun showMarkers(points: List<LatLon>) {
        pendingMarkers = points
        pushMarkers()
    }

    private fun pushMarkers() {
        val source = markersSource ?: return
        if (map == null) return
        source.setGeoJson(
            org.maplibre.geojson.FeatureCollection.fromFeatures(
                pendingMarkers.map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) },
            ),
        )
    }

    // --- Route preview ---

    override fun showRoute(points: List<LatLon>, fit: Boolean) {
        pendingRoute = points
        routeFit = fit
        pushRoute()
    }

    override fun clearRoute() = showRoute(emptyList(), fit = false)

    override fun setMapTapListener(listener: ((LatLon) -> Unit)?) {
        tapListener = listener
    }

    // --- Petrol stations (RF-15) ---

    override fun showFuel(pins: List<FuelPin>) {
        pendingFuel = pins
        pushFuel()
    }

    override fun setFuelTapListener(listener: ((String) -> Unit)?) {
        fuelTapListener = listener
    }

    override fun setViewportListener(listener: ((GeoBounds, Double) -> Unit)?) {
        viewportListener = listener
        dispatchViewport()
    }

    private fun pushFuel() {
        val source = fuelSource ?: return
        if (map == null) return
        source.setGeoJson(
            FeatureCollection.fromFeatures(
                pendingFuel.map {
                    Feature.fromGeometry(Point.fromLngLat(it.point.lon, it.point.lat)).apply {
                        addStringProperty(FUEL_ID, it.id)
                        addStringProperty(FUEL_LABEL, it.label)
                        addBooleanProperty(FUEL_CHEAP, it.cheap)
                        addNumberProperty(FUEL_RANK, it.rank)
                    }
                },
            ),
        )
    }

    private fun addFuelLayer(style: Style, dark: Boolean) {
        val res = view.resources
        val d = res.displayMetrics
        style.addImage(FuelIcons.NORMAL, FuelIcons.render(false, dark, d.density, d.densityDpi))
        style.addImage(FuelIcons.CHEAP, FuelIcons.render(true, dark, d.density, d.densityDpi))
        style.addSource(GeoJsonSource(FUEL_SOURCE).also { fuelSource = it })
        val cheap = Expression.get(FUEL_CHEAP)
        style.addLayer(
            SymbolLayer(FUEL_LAYER, FUEL_SOURCE).withProperties(
                iconImage(Expression.switchCase(cheap, Expression.literal(FuelIcons.CHEAP), Expression.literal(FuelIcons.NORMAL))),
                iconAllowOverlap(true), // the badge always shows; only the price text yields to collisions
                iconAnchor(Property.ICON_ANCHOR_CENTER),
                textField(Expression.get(FUEL_LABEL)),
                textFont(arrayOf("Noto Sans Medium")),
                textSize(Expression.switchCase(cheap, Expression.literal(15f), Expression.literal(12f))),
                textAnchor(Property.TEXT_ANCHOR_TOP),
                textOffset(arrayOf(0f, 1.1f)),
                textOptional(true),
                textColor(if (dark) 0xFFF2F2F7.toInt() else 0xFF1C1C1E.toInt()),
                textHaloColor(if (dark) 0xFF1C1C1E.toInt() else WHITE),
                textHaloWidth(2f),
                symbolSortKey(Expression.get(FUEL_RANK)),
            ),
        )
        pushFuel()
    }

    /** Station under the finger: icon or price within a 48 dp square, unless a saved marker sits right under it. */
    private fun fuelAt(m: MapLibreMap, p: LatLng): String? {
        val listener = fuelTapListener ?: return null
        if (fuelSource == null || pendingFuel.isEmpty()) return null
        val d = view.resources.displayMetrics.density
        val at = m.projection.toScreenLocation(p)
        fun box(halfDp: Float) = RectF(at.x - halfDp * d, at.y - halfDp * d, at.x + halfDp * d, at.y + halfDp * d)
        val hits = m.queryRenderedFeatures(box(FUEL_TOUCH_DP / 2), FUEL_LAYER)
        if (hits.isEmpty()) return null
        val tight = box(TIGHT_DP)
        if (m.queryRenderedFeatures(tight, MARKERS_LAYER, PIN_LAYER, USER_LAYER).isNotEmpty() &&
            m.queryRenderedFeatures(tight, FUEL_LAYER).isEmpty()
        ) {
            return null // the finger is on another marker, not on a station
        }
        val best = hits.minByOrNull { f ->
            val g = f.geometry() as? Point
            if (g == null) Double.MAX_VALUE else (g.latitude() - p.latitude).let { a -> a * a } + (g.longitude() - p.longitude).let { a -> a * a }
        }
        val id = best?.getStringProperty(FUEL_ID) ?: return null
        listener(id)
        return id
    }

    private fun dispatchViewport() {
        val l = viewportListener ?: return
        val m = map ?: return
        val r = runCatching { m.projection.visibleRegion.latLngBounds }.getOrNull() ?: return
        if (r.longitudeWest > r.longitudeEast) return
        l(GeoBounds(r.latitudeSouth, r.longitudeWest, r.latitudeNorth, r.longitudeEast), m.cameraPosition.zoom)
    }

    private fun handleTap(p: LatLng): Boolean {
        val m = map
        if (m != null && fuelAt(m, p) != null) return true
        val listener = tapListener ?: return false
        listener(LatLon.ofOrNull(p.latitude, p.longitude) ?: return false)
        return true
    }

    private fun pushRoute() {
        val source = routeSource ?: return
        val m = map ?: return
        val points = pendingRoute
        if (points.size < 2) {
            source.setGeoJson(EMPTY_COLLECTION)
            return
        }
        source.setGeoJson(
            Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })),
        )
        if (routeFit) {
            routeFit = false
            val bounds = LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.lat, it.lon)) } }.build()
            val d = view.resources.displayMetrics.density
            // The bottom sheet covers roughly the lower half of the screen while the route is shown.
            val padding = intArrayOf((40 * d).toInt(), (80 * d).toInt(), (40 * d).toInt(), (300 * d).toInt())
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding[0], padding[1], padding[2], padding[3]), 600)
        }
    }

    private fun addRouteLayer(style: Style) {
        val source = GeoJsonSource(ROUTE_SOURCE).also { routeSource = it }
        style.addSource(source)
        style.addLayer(
            LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                lineColor(ROUTE_COLOR), lineWidth(6f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        pushRoute()
    }

    // --- Style ---

    private fun loadStyle() {
        val m = map ?: return
        val wanted = theme
        if (files.isInstalled()) {
            applyStyle(m, wanted)
        } else {
            // First run: copy sprites and glyphs off the main thread, then load.
            Thread({
                runCatching { files.install() }
                main.post { if (!closed) map?.let { applyStyle(it, theme) } }
            }, "mapas-assets").start()
        }
    }

    /**
     * Reloads the style if the set of installed tiles changed (a region was downloaded, deleted or replaced) since
     * it was loaded. Cheap when nothing changed: a directory scan and a stat per region, no style work.
     */
    fun refreshTilesIfChanged() {
        if (map != null && loadedTheme != null && files.tilesSignature() != loadedTiles) loadStyle()
    }

    private fun applyStyle(m: MapLibreMap, wanted: MapTheme) {
        loadedTiles = files.tilesSignature()
        val t0 = SystemClock.elapsedRealtime()
        val built = files.style(wanted)
        val buildMs = SystemClock.elapsedRealtime() - t0
        m.setStyle(Style.Builder().fromJson(built.json)) { style ->
            loadedTheme = wanted
            // Metrics only (no locations, no paths): to size the cost of many regions on a device later.
            Log.i(
                METRICS_TAG,
                "sources=${built.sources} layers=${built.layers} template_layers=${built.templateLayers} " +
                    "skipped=${built.skipped} json_kb=${built.json.length / 1024} build_ms=$buildMs " +
                    "style_load_ms=${SystemClock.elapsedRealtime() - t0 - buildMs}",
            )
            val user = GeoJsonSource(USER_SOURCE).also { userSource = it }
            val pin = GeoJsonSource(PIN_SOURCE).also { pinSource = it }
            addRouteLayer(style) // below the markers, pin and user dots
            addFuelLayer(style, wanted == MapTheme.DARK)
            style.addSource(user)
            style.addSource(pin)
            style.addSource(GeoJsonSource(MARKERS_SOURCE).also { markersSource = it })
            style.addLayer(
                CircleLayer(MARKERS_LAYER, MARKERS_SOURCE).withProperties(
                    circleRadius(6f), circleColor(MARKER_COLOR), circleStrokeColor(WHITE), circleStrokeWidth(2f),
                ),
            )
            style.addLayer(
                CircleLayer(PIN_LAYER, PIN_SOURCE).withProperties(
                    circleRadius(8f), circleColor(PIN_COLOR), circleStrokeColor(WHITE), circleStrokeWidth(2.5f),
                ),
            )
            style.addLayer(
                CircleLayer(USER_LAYER, USER_SOURCE).withProperties(
                    circleRadius(8f), circleColor(USER_COLOR), circleStrokeColor(WHITE), circleStrokeWidth(3f),
                ),
            )
            pushOverlay(userSource, pendingUser)
            pushOverlay(pinSource, pendingPin)
            pushMarkers()
            dispatchViewport() // the first station draw (a new style has no camera-idle of its own)
        }
    }

    private fun pushOverlay(source: GeoJsonSource?, point: LatLon?) {
        if (map == null || source == null) return
        if (point == null) {
            source.setGeoJson(EMPTY_COLLECTION)
        } else {
            source.setGeoJson(Feature.fromGeometry(Point.fromLngLat(point.lon, point.lat)))
        }
    }

    private fun handleIdle(m: MapLibreMap) {
        val state = runCatching { m.cameraPosition.toState() }.getOrNull() ?: return
        lastIdle = state
        store.save(state)
        onCameraIdle(state)
        dispatchViewport()
    }

    // --- Lifecycle ---

    override fun onStart(owner: LifecycleOwner) = view.onStart()
    override fun onResume(owner: LifecycleOwner) = view.onResume()
    override fun onPause(owner: LifecycleOwner) = view.onPause()
    override fun onStop(owner: LifecycleOwner) {
        map?.let { handleIdle(it) } // persist even if the process dies in the background
        view.onStop()
    }

    override fun onDestroy(owner: LifecycleOwner) = close()

    override fun close() {
        if (closed) return
        closed = true
        view.onDestroy()
    }

    private fun CameraState.toPosition(): CameraPosition = CameraPosition.Builder()
        .target(LatLng(center.lat, center.lon)).zoom(zoom).bearing(bearing).tilt(tilt).build()

    private fun CameraPosition.toState(): CameraState {
        val t = target ?: return lastIdle
        return CameraState(
            LatLon.ofOrNull(t.latitude, t.longitude) ?: lastIdle.center,
            zoom.coerceIn(0.0, 24.0),
            if (bearing.isFinite()) bearing else 0.0,
            tilt.coerceIn(0.0, 85.0),
        )
    }

    private companion object {
        const val METRICS_TAG = "UMSTYLE"
        const val USER_SOURCE ="mapas-user-src"
        const val USER_LAYER = "mapas-user"
        const val PIN_SOURCE = "mapas-pin-src"
        const val PIN_LAYER = "mapas-pin"
        const val MARKERS_SOURCE = "mapas-saved-src"
        const val MARKERS_LAYER = "mapas-saved"
        const val ROUTE_SOURCE = "mapas-route-src"
        const val ROUTE_LAYER = "mapas-route"
        const val FUEL_SOURCE = "mapas-fuel-src"
        const val FUEL_LAYER = "mapas-fuel"
        const val FUEL_ID = "id"
        const val FUEL_LABEL = "label"
        const val FUEL_CHEAP = "cheap"
        const val FUEL_RANK = "rank"
        const val FUEL_TOUCH_DP = 48f
        const val TIGHT_DP = 14f
        const val ROUTE_COLOR = 0xFF0A84FF.toInt()
        const val MARKER_COLOR = 0xFFFF9500.toInt()
        const val USER_COLOR = 0xFF007AFF.toInt()
        const val PIN_COLOR = 0xFFFF3B30.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        val EMPTY_COLLECTION = org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList<Feature>())
    }
}
