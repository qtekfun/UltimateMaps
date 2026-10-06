package com.qtekfun.mapas.core.search

import com.qtekfun.mapas.core.geo.LatLon

data class SearchResult(
    val name: String,
    val point: LatLon,
    val address: String? = null,
    /** Human-readable feature type (e.g. "cafe"), when the engine provides it. */
    val category: String? = null,
)

/** On-device search/geocoding contract. */
interface SearchEngine : AutoCloseable {
    /** Free-text search, optionally biased towards [near]. */
    fun search(query: String, near: LatLon? = null, limit: Int = 20): List<SearchResult>
}
