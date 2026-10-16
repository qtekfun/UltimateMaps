package com.qtekfun.ultimatemaps.core.traffic

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.io.IOException

/** The optional traffic switch (off by default; `play` flavor only). */
interface TrafficSettingsStore {
    var enabled: Boolean

    /** Draw the traffic on the map too (only while [enabled]). On by default: the switch is the consent. */
    var mapLayer: Boolean
}

/** The user's own TomTom API key, kept encrypted by the app. Never logged, never exported. */
interface TrafficKeyStore {
    fun read(): String?
    fun write(key: String)
    fun clear()
}

/** Why a tile could not be fetched. */
enum class TileFailure { OFFLINE, NOT_ALLOWED, BAD_KEY, RATE_LIMITED, NETWORK }

class TileFetchException(val failure: TileFailure, message: String? = null) : IOException(message ?: failure.name)

/** One GET of a flow tile, blocking, already decompressed. Throws [TileFetchException]. Replaced by a fake in tests. */
fun interface FlowTileFetch {
    fun get(tile: TileId, apiKey: String): ByteArray
}

sealed interface TrafficOutcome {
    data class Ready(val estimate: TrafficEstimate) : TrafficOutcome

    /** Nothing to say: the switch is off, there is no key, or the feed could not be read ([reason] tells which). */
    data class Unavailable(val reason: Reason) : TrafficOutcome

    enum class Reason { DISABLED, NO_KEY, OFFLINE, BAD_KEY, RATE_LIMITED, FAILED, NO_DATA }
}

/** What the route screen asks. The `foss` flavor gets [NoTrafficProvider]. */
interface TrafficProvider {
    /** True when the switch is on and a key is stored; says nothing about the network. */
    val enabled: Boolean

    /**
     * The route's travel time with live traffic. Blocking network calls happen inside, so call it off the main thread.
     * [baseSpeedMps] is the speed the router assumes along the route (null: the same everywhere).
     */
    suspend fun estimate(geometry: List<LatLon>, coreSeconds: Double, baseSpeedMps: ((Double) -> Double)? = null): TrafficOutcome

    /** The flow lines of [tiles] for the map layer; null when nothing can be said (off, no key, offline, nothing came). */
    suspend fun flowLines(tiles: List<TileId>): List<FlowLine>? = null
}

object NoTrafficProvider : TrafficProvider {
    override val enabled: Boolean get() = false
    override suspend fun estimate(geometry: List<LatLon>, coreSeconds: Double, baseSpeedMps: ((Double) -> Double)?): TrafficOutcome =
        TrafficOutcome.Unavailable(TrafficOutcome.Reason.DISABLED)
}

/**
 * Traffic from TomTom flow tiles. The tiles of zoom [RouteTraffic.TILE_ZOOM] along the route are fetched through [fetch]
 * (which goes through the NetworkPolicy), decoded and cached in memory for [CACHE_MS]; nothing is written to disk. At most
 * [MAX_TILES] tiles are read for one route; a longer route is estimated on the first part only (its coverage says so).
 */
class FlowTrafficProvider(
    private val settings: TrafficSettingsStore,
    private val keys: TrafficKeyStore,
    private val fetch: FlowTileFetch,
    /** Milliseconds, monotonic; virtual in tests. */
    private val clock: () -> Long,
) : TrafficProvider {
    /** A decoded tile; [lines] (the simplified map lines) are made once, the first time the map layer asks. */
    private class Cached(val at: Long, val layers: List<MvtLayer>?, var lines: List<FlowLine>? = null)

    private val cache = LinkedHashMap<TileId, Cached>()

    override val enabled: Boolean get() = settings.enabled && !keys.read().isNullOrBlank()

    override suspend fun flowLines(tiles: List<TileId>): List<FlowLine>? {
        if (!settings.enabled) return null
        val key = keys.read()?.takeIf { it.isNotBlank() } ?: return null
        val now = clock()
        synchronized(cache) { cache.entries.removeAll { now - it.value.at > CACHE_MS } }
        val out = ArrayList<FlowLine>()
        var any = false
        for (tile in tiles.take(MAX_LAYER_TILES)) {
            val entry = synchronized(cache) { cache[tile] } ?: try {
                Cached(now, Mvt.decode(fetch.get(tile, key))).also { synchronized(cache) { cache[tile] = it } }
            } catch (e: TileFetchException) {
                if (e.failure != TileFailure.NETWORK) return null // a bad key, the limit, offline: no point in asking the others
                continue
            } catch (e: RuntimeException) {
                continue
            }
            any = true
            val lines = entry.lines ?: FlowLines.of(tile, entry.layers.orEmpty()).also { entry.lines = it }
            out += lines
        }
        return if (any) FlowLines.limit(out) else null
    }

    override suspend fun estimate(geometry: List<LatLon>, coreSeconds: Double, baseSpeedMps: ((Double) -> Double)?): TrafficOutcome {
        if (!settings.enabled) return TrafficOutcome.Unavailable(TrafficOutcome.Reason.DISABLED)
        val key = keys.read()?.takeIf { it.isNotBlank() } ?: return TrafficOutcome.Unavailable(TrafficOutcome.Reason.NO_KEY)
        if (geometry.size < 2) return TrafficOutcome.Unavailable(TrafficOutcome.Reason.NO_DATA)
        val tiles = TileMath.tilesAlong(geometry, RouteTraffic.TILE_ZOOM).take(MAX_TILES)
        val builder = FlowField.Builder()
        var failure: TrafficOutcome.Reason? = null
        val now = clock()
        synchronized(cache) { cache.entries.removeAll { now - it.value.at > CACHE_MS } }
        for (tile in tiles) {
            val layers = synchronized(cache) { cache[tile]?.layers } ?: try {
                Mvt.decode(fetch.get(tile, key)).also { synchronized(cache) { cache[tile] = Cached(now, it) } }
            } catch (e: TileFetchException) {
                failure = when (e.failure) {
                    TileFailure.OFFLINE -> TrafficOutcome.Reason.OFFLINE
                    TileFailure.NOT_ALLOWED -> TrafficOutcome.Reason.DISABLED
                    TileFailure.BAD_KEY -> TrafficOutcome.Reason.BAD_KEY
                    TileFailure.RATE_LIMITED -> TrafficOutcome.Reason.RATE_LIMITED
                    TileFailure.NETWORK -> TrafficOutcome.Reason.FAILED
                }
                // a key or a quota problem will repeat on every tile: stop asking
                if (failure != TrafficOutcome.Reason.FAILED) break
                continue
            } catch (e: RuntimeException) {
                failure = TrafficOutcome.Reason.FAILED // a tile that does not decode
                continue
            }
            builder.addTile(tile, layers)
        }
        val field = builder.build()
        if (field.segmentCount == 0) return TrafficOutcome.Unavailable(failure ?: TrafficOutcome.Reason.NO_DATA)
        return TrafficOutcome.Ready(RouteTraffic.estimate(geometry, coreSeconds, field, baseSpeedMps))
    }

    companion object {
        const val CACHE_MS = 3 * 60_000L
        const val MAX_TILES = 12
        const val MAX_LAYER_TILES = FlowLines.MAX_TILES
    }
}
