package com.qtekfun.mapas.map

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
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
    private var pendingUser: LatLon? = null
    private var pendingPin: LatLon? = null
    private var pendingMarkers: List<LatLon> = emptyList()
    private var pendingCamera: CameraState? = null
    private var lastIdle: CameraState
    private var closed = false

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

    private fun applyStyle(m: MapLibreMap, wanted: MapTheme) {
        val json = files.styleJson(wanted)
        m.setStyle(Style.Builder().fromJson(json)) { style ->
            loadedTheme = wanted
            val user = GeoJsonSource(USER_SOURCE).also { userSource = it }
            val pin = GeoJsonSource(PIN_SOURCE).also { pinSource = it }
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
        const val USER_SOURCE = "mapas-user-src"
        const val USER_LAYER = "mapas-user"
        const val PIN_SOURCE = "mapas-pin-src"
        const val PIN_LAYER = "mapas-pin"
        const val MARKERS_SOURCE = "mapas-saved-src"
        const val MARKERS_LAYER = "mapas-saved"
        const val MARKER_COLOR = 0xFFFF9500.toInt()
        const val USER_COLOR = 0xFF007AFF.toInt()
        const val PIN_COLOR = 0xFFFF3B30.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        val EMPTY_COLLECTION = org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList<Feature>())
    }
}
