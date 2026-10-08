package com.qtekfun.ultimatemaps.transit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.regions.TransitAsset
import com.qtekfun.ultimatemaps.core.transit.CoverageEntry
import com.qtekfun.ultimatemaps.core.transit.InstalledTransit
import com.qtekfun.ultimatemaps.core.transit.TransitDataManager
import com.qtekfun.ultimatemaps.core.transit.TransitCoverage
import com.qtekfun.ultimatemaps.core.transit.TransitFailure
import com.qtekfun.ultimatemaps.core.transit.TransitService
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executor

/** One row of the "Public transport" section of the Maps screen. */
data class TransitCityRow(
    val id: String,
    val city: String,
    val validFrom: String,
    val validTo: String,
    val sizeBytes: Long,
    val installed: Boolean,
    /** The installed copy is older than the catalog's (same city, different file). */
    val updateAvailable: Boolean,
    /** The catalog's validity already ended: it cannot be installed. */
    val expired: Boolean,
    val downloading: Boolean,
    val failure: TransitFailure?,
    val attribution: List<String>,
)

/**
 * Transit data of the device: what the catalog offers, what is installed, user-started downloads and the planner for a
 * trip. The catalog comes from [catalogAssets] (the app's region catalog, already fetched through the NetworkPolicy);
 * downloads run on [io] and only when [download] is called by the user.
 */
class TransitRepository(
    private val manager: TransitDataManager,
    private val catalogAssets: () -> List<TransitAsset>,
    private val io: Executor,
    private val clock: TransitClock = TransitClock.System,
) : TransitSource {
    private var installed by mutableStateOf<List<InstalledTransit>>(emptyList())
    private var busy by mutableStateOf<Set<String>>(emptySet())
    private var failures by mutableStateOf<Map<String, TransitFailure>>(emptyMap())

    private val cache = HashMap<String, Pair<String, TransitService>>() // id -> (sha, service)

    /** Re-reads what is installed (cheap: a few small files). Call off the main thread or from tests. */
    fun refresh() {
        installed = manager.installed()
    }

    /** Attribution lines of every installed city, for the About screen. Empty when nothing is installed. */
    val attributions: List<String> get() = installed.flatMap { it.attribution }.distinct()

    fun rows(): List<TransitCityRow> {
        val offered = catalogAssets()
        val today = clock.now().atZone(clock.zone()).toLocalDate()
        val rows = offered.map { a ->
            val mine = installed.firstOrNull { it.id == a.id }
            TransitCityRow(
                a.id, a.city, a.validFrom, a.validTo, a.asset.sizeBytes, mine != null,
                updateAvailable = mine != null && !mine.sha256.equals(a.asset.sha256, ignoreCase = true),
                expired = runCatching { LocalDate.parse(a.validTo).isBefore(today) }.getOrDefault(false),
                downloading = a.id in busy, failure = failures[a.id], attribution = a.attribution,
            )
        }
        // installed cities the catalog no longer lists stay usable and deletable
        val extra = installed.filter { i -> offered.none { it.id == i.id } }.map { i ->
            TransitCityRow(i.id, i.city, i.validFrom, i.validTo, 0, true, false, false, false, failures[i.id], i.attribution)
        }
        return rows + extra
    }

    /** User pressed "Download" / "Update". Returns immediately; the result shows in [rows]. */
    fun download(id: String) {
        val asset = catalogAssets().firstOrNull { it.id == id } ?: return
        if (id in busy) return
        busy = busy + id
        failures = failures - id
        io.execute {
            val failure = manager.download(asset)
            if (failure == null) synchronized(cache) { cache.remove(id) }
            installed = manager.installed()
            if (failure != null) failures = failures + (id to failure)
            busy = busy - id
        }
    }

    fun delete(id: String) {
        io.execute {
            manager.delete(id)
            synchronized(cache) { cache.remove(id) }
            installed = manager.installed()
        }
    }

    override fun lookup(origin: LatLon, destination: LatLon): TransitLookup {
        val have = manager.installed()
        installed = have
        val offered = catalogAssets()
        val haveEntries = have.map { CoverageEntry(it.id, it.city, it.bounds) }
        val offeredEntries = offered.map { CoverageEntry(it.id, it.city, it.bounds) }
        // 1. an installed index serves the whole trip (the tightest box when several do)
        TransitCoverage.covering(haveEntries, origin, destination)?.let { hit ->
            val info = have.first { it.id == hit.id }
            val service = serviceOf(info) ?: return TransitLookup.Unreadable
            return TransitLookup.Ready(service, info.city)
        }
        // 2. the catalog offers one that serves the whole trip but it is not installed
        TransitCoverage.covering(offeredEntries.filter { o -> have.none { it.id == o.id } }, origin, destination)
            ?.let { return TransitLookup.NotDownloaded(it.city) }
        // 3. each end has data, but in two different indexes: say so instead of "outside coverage"
        val all = haveEntries + offeredEntries.filter { o -> have.none { it.id == o.id } }
        TransitCoverage.across(all, origin, destination)?.let { (a, b) -> return TransitLookup.AcrossIndexes(a.city, b.city) }
        return if (have.isEmpty() && offered.isEmpty()) TransitLookup.NoData else TransitLookup.OutsideCoverage
    }

    private fun serviceOf(info: InstalledTransit): TransitService? = synchronized(cache) {
        cache[info.id]?.takeIf { it.first == info.sha256 }?.second?.let { return it }
        val index = manager.open(info.id) ?: return null
        val zone = runCatching { ZoneId.of(info.timezone) }.getOrDefault(ZoneId.of("UTC"))
        TransitService(index, zone).also { cache[info.id] = info.sha256 to it }
    }
}
