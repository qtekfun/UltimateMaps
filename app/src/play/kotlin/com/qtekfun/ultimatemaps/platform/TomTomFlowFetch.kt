package com.qtekfun.ultimatemaps.platform

import com.qtekfun.ultimatemaps.core.cameras.BoundedHttp
import com.qtekfun.ultimatemaps.core.cameras.DownloadException
import com.qtekfun.ultimatemaps.core.cameras.DownloadFailure
import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import com.qtekfun.ultimatemaps.core.net.NetworkPolicy
import com.qtekfun.ultimatemaps.core.traffic.FlowTileFetch
import com.qtekfun.ultimatemaps.core.traffic.TileFailure
import com.qtekfun.ultimatemaps.core.traffic.TileFetchException
import com.qtekfun.ultimatemaps.core.traffic.TileId
import com.qtekfun.ultimatemaps.core.traffic.TomTomEndpoints

/**
 * `play` flavor: one TomTom Orbis flow tile per call, through [policy] under [ConnectionPurpose.TRAFFIC_FLOW]. What is sent:
 * the tile address (a square about 40 km wide: where the route is, not the route), the user's own key in a header and the
 * app's User-Agent. Nothing is stored; the answer is capped.
 */
object TomTomFlowFetch {
    fun over(
        policy: NetworkPolicy,
        userAgent: String,
        allowInsecure: Boolean = false,
        urlOf: (TileId) -> String = TomTomEndpoints::tileUrl,
    ): FlowTileFetch {
        val http = BoundedHttp(
            policy, ConnectionPurpose.TRAFFIC_FLOW, allowInsecure = allowInsecure, maxBytes = 2L * 1024 * 1024,
            connectTimeoutMs = 8_000, readTimeoutMs = 10_000, totalTimeoutMs = 20_000, userAgent = userAgent,
        )
        return FlowTileFetch { tile, key ->
            try {
                http.get(urlOf(tile), TomTomEndpoints.ACCEPT, mapOf(TomTomEndpoints.KEY_HEADER to key)) { it.readBytes() }
            } catch (e: DownloadException) {
                throw TileFetchException(failureOf(e))
            }
        }
    }

    fun failureOf(e: DownloadException): TileFailure = when {
        e.failure == DownloadFailure.OFFLINE_MODE -> TileFailure.OFFLINE
        e.failure == DownloadFailure.NOT_ALLOWED -> TileFailure.NOT_ALLOWED
        e.httpStatus == 401 || e.httpStatus == 403 -> TileFailure.BAD_KEY
        e.httpStatus == 429 -> TileFailure.RATE_LIMITED
        else -> TileFailure.NETWORK
    }
}
