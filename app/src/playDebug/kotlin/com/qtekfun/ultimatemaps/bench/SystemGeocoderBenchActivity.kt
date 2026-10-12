package com.qtekfun.ultimatemaps.bench

import android.app.Activity
import android.location.Geocoder
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.search.GeocodeBounds
import com.qtekfun.ultimatemaps.core.search.GeocoderProbe
import com.qtekfun.ultimatemaps.core.search.OnlineGeocodeOutcome
import com.qtekfun.ultimatemaps.core.search.OnlineGeocoder
import com.qtekfun.ultimatemaps.core.search.SystemGeocoderFallback
import com.qtekfun.ultimatemaps.platform.AndroidGeocoderBackend
import com.qtekfun.ultimatemaps.platform.MainLooperScheduler

/**
 * System geocoder bench (playDebug only: it does not exist in the foss flavor nor in release builds). Launched with
 * `am start -n com.qtekfun.ultimatemaps.play/com.qtekfun.ultimatemaps.bench.SystemGeocoderBenchActivity
 *   --es q "Calle Mayor 5, Getafe|Mercadona Alcala|hospital 12 de octubre" [--es near "40.4168,-3.7038"] [--ei radius 30000]`
 * and logs under the UMBENCH tag, one `geocoder` line per query and one per result, so the same queries can later be
 * compared with the Photon fallback. `--ez probe true` runs only the backend probe. Queries are separated by `|`.
 *
 * It deliberately ignores the Settings switch: starting this activity is the explicit request. Every query is sent to the
 * phone's geocoder service (Google's with Play Services), so it is only for the owner's own comparison runs. The `near`
 * point is used only as a bias box and is not logged.
 */
class SystemGeocoderBenchActivity : Activity() {
    private val tag = "UMBENCH"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "System geocoder bench: see logcat (UMBENCH)" })
        val geocoder: OnlineGeocoder = SystemGeocoderFallback(
            backend = AndroidGeocoderBackend(this),
            scheduler = MainLooperScheduler(),
            enabled = { true },
            timeoutMs = 15_000,
        )
        Log.i(tag, "geocoder isPresent=${Geocoder.isPresent()}")
        if (intent?.getBooleanExtra("probe", false) == true) {
            GeocoderProbe(geocoder).run { Log.i(tag, "geocoder probe working=$it") }
            return
        }
        val queries = intent?.getStringExtra("q")?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val near = intent?.getStringExtra("near")?.split(',')?.takeIf { it.size == 2 }?.let {
            LatLon.ofOrNull(it[0].trim().toDoubleOrNull() ?: return@let null, it[1].trim().toDoubleOrNull() ?: return@let null)
        }
        val radius = intent?.getIntExtra("radius", 30_000) ?: 30_000
        val bounds = near?.let { GeocodeBounds.around(it, radius.toDouble()) }
        if (queries.isEmpty()) {
            Log.w(tag, "geocoder: no queries; pass --es q \"a|b|c\"")
            return
        }
        runNext(geocoder, queries, 0, bounds)
    }

    /** One query at a time: the next starts from the callback of the previous one, so the timings do not overlap. */
    private fun runNext(geocoder: OnlineGeocoder, queries: List<String>, index: Int, bounds: GeocodeBounds?) {
        if (index >= queries.size) {
            Log.i(tag, "geocoder done queries=${queries.size}")
            return
        }
        val q = queries[index]
        geocoder.search(q, bounds, 5) { outcome ->
            when (outcome) {
                is OnlineGeocodeOutcome.Found -> {
                    Log.i(tag, "geocoder q=\"$q\" n=${outcome.results.size} ms=${outcome.latencyMs}")
                    outcome.results.forEachIndexed { i, r ->
                        Log.i(tag, "geocoder   #${i + 1} ${r.name} | ${r.address.orEmpty()} | ${r.point.lat},${r.point.lon}")
                    }
                }
                is OnlineGeocodeOutcome.NoMatch -> Log.i(tag, "geocoder q=\"$q\" n=0 ms=${outcome.latencyMs}")
                is OnlineGeocodeOutcome.Failed -> Log.i(tag, "geocoder q=\"$q\" failed=${outcome.reason} ms=${outcome.latencyMs} msg=${outcome.message}")
                is OnlineGeocodeOutcome.Skipped -> Log.i(tag, "geocoder q=\"$q\" skipped=${outcome.reason}")
            }
            runNext(geocoder, queries, index + 1, bounds)
        }
    }
}
