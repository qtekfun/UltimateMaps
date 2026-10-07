package com.qtekfun.ultimatemaps.core.fuel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.PriorityQueue
import kotlin.math.floor

/** Downloaded data of one fuel: when it was fetched and the stations that sell it (one price each). */
data class FuelData(val fuelId: String, val fetchedAtMillis: Long, val serviceDate: String?, val stations: List<RawStation>)

/**
 * Immutable in-memory index: stations merged by id (prices of several fuels in [FuelStation.prices]) and, per fuel,
 * a grid of 0.1 degree cells (about 11 km) for cheap rectangle queries. Built off the UI thread, then swapped in
 * atomically, so readers never lock.
 */
class FuelSnapshot private constructor(
    private val byId: Map<String, FuelStation>,
    private val grids: Map<String, Grid>,
    val fetchedAt: Map<String, Long>,
) {
    class Grid(val cells: HashMap<Long, Array<FuelStation>>)

    val stationCount: Int get() = byId.size

    fun station(id: String): FuelStation? = byId[id]

    fun stationsIn(bounds: LatLonBounds, fuel: FuelType, limit: Int): List<FuelStation> {
        if (limit <= 0) return emptyList()
        val grid = grids[fuel.id] ?: return emptyList()
        val r0 = cell(bounds.south); val r1 = cell(bounds.north)
        val c0 = cell(bounds.west); val c1 = cell(bounds.east)
        if (r1 < r0 || c1 < c0) return emptyList()
        // Max-heap on price: keeps the `limit` cheapest candidates.
        val heap = PriorityQueue<FuelStation>(minOf(limit, 64) + 1, WORST_FIRST(fuel.id))
        fun offer(cellStations: Array<FuelStation>) {
            for (s in cellStations) {
                if (!bounds.contains(s.location)) continue
                heap.add(s)
                if (heap.size > limit) heap.poll()
            }
        }
        val cellCount = (r1 - r0 + 1).toLong() * (c1 - c0 + 1)
        if (cellCount > grid.cells.size) {
            for ((key, arr) in grid.cells) {
                val r = (key shr 32).toInt() - OFFSET
                val c = (key and 0xFFFFFFFFL).toInt() - OFFSET
                if (r in r0..r1 && c in c0..c1) offer(arr)
            }
        } else {
            for (r in r0..r1) for (c in c0..c1) grid.cells[key(r, c)]?.let(::offer)
        }
        return heap.sortedWith(BEST_FIRST(fuel.id))
    }

    companion object {
        private const val CELL_DEGREES = 10.0 // cells per degree
        private const val OFFSET = 10_000

        private fun cell(deg: Double) = floor(deg * CELL_DEGREES).toInt()
        private fun key(r: Int, c: Int) = ((r + OFFSET).toLong() shl 32) or (c + OFFSET).toLong()
        private fun WORST_FIRST(fuelId: String) = Comparator<FuelStation> { a, b ->
            val d = (b.prices[fuelId] ?: 0.0).compareTo(a.prices[fuelId] ?: 0.0)
            if (d != 0) d else b.id.compareTo(a.id)
        }
        private fun BEST_FIRST(fuelId: String) = Comparator<FuelStation> { a, b ->
            val d = (a.prices[fuelId] ?: 0.0).compareTo(b.prices[fuelId] ?: 0.0)
            if (d != 0) d else a.id.compareTo(b.id)
        }

        val EMPTY = FuelSnapshot(emptyMap(), emptyMap(), emptyMap())

        /** Joins the per-fuel data by station id (`IDEESS`). The first fuel (in [data] order) supplies the station's own fields. */
        fun build(data: Collection<FuelData>): FuelSnapshot {
            if (data.isEmpty()) return EMPTY
            val prices = HashMap<String, HashMap<String, Double>>()
            val first = HashMap<String, RawStation>()
            for (d in data) for (s in d.stations) {
                first.putIfAbsent(s.id, s)
                prices.getOrPut(s.id) { HashMap(4) }[d.fuelId] = s.price
            }
            val byId = HashMap<String, FuelStation>(first.size * 2)
            for ((id, s) in first) {
                byId[id] = FuelStation(
                    id = id, brand = s.brand, address = s.address, municipality = s.municipality,
                    province = s.province, location = s.location, schedule = s.schedule, prices = prices.getValue(id),
                )
            }
            val grids = HashMap<String, Grid>()
            for (d in data) {
                val cells = HashMap<Long, MutableList<FuelStation>>()
                for (s in d.stations) {
                    val st = byId.getValue(s.id)
                    cells.getOrPut(key(cell(st.location.lat), cell(st.location.lon))) { ArrayList(4) }.add(st)
                }
                grids[d.fuelId] = Grid(HashMap<Long, Array<FuelStation>>(cells.size * 2).also { m ->
                    for ((k, v) in cells) m[k] = v.toTypedArray()
                })
            }
            return FuelSnapshot(byId, grids, data.associate { it.fuelId to it.fetchedAtMillis })
        }
    }
}

/**
 * The [FuelRepository] the map and the station card read. Reads hit an immutable [FuelSnapshot] held in a
 * volatile field: no locks, cheap from the UI thread, safe from any thread.
 */
class FuelDataRepository : FuelRepository {
    @Volatile private var snapshot: FuelSnapshot = FuelSnapshot.EMPTY
    private val lastUpdate = MutableStateFlow<Long?>(null)

    override val lastUpdateMillis: StateFlow<Long?> get() = lastUpdate

    override fun stationsIn(bounds: LatLonBounds, fuel: FuelType, limit: Int): List<FuelStation> =
        snapshot.stationsIn(bounds, fuel, limit)

    override fun station(id: String): FuelStation? = snapshot.station(id)

    /** Per-fuel fetch times of what is currently served. */
    val fetchedAt: Map<String, Long> get() = snapshot.fetchedAt

    /** Replaces what is served. [lastUpdateMillis] becomes the OLDEST fetch time (honest about the stalest fuel), or null when empty. */
    internal fun install(s: FuelSnapshot) {
        snapshot = s
        lastUpdate.value = s.fetchedAt.values.minOrNull()
    }
}
