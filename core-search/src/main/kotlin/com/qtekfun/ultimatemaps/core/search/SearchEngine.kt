package com.qtekfun.ultimatemaps.core.search

import com.qtekfun.ultimatemaps.core.geo.LatLon

data class SearchResult(
    val name: String,
    val point: LatLon,
    val address: String? = null,
    /** Human-readable feature type (e.g. "cafe"), when the engine provides it. */
    val category: String? = null,
    /** Straight-line distance in meters from the point the search was made around; set by category browsing only. */
    val distanceMeters: Double? = null,
    /** Phone, website, wheelchair and opening hours from the OSM tags; null while the engine does not provide them. */
    val extras: PlaceExtras? = null,
)

/** On-device search/geocoding contract. */
interface SearchEngine : AutoCloseable {
    /** Free-text search, optionally biased towards [near]. */
    fun search(query: String, near: LatLon? = null, limit: Int = 20): List<SearchResult>

    /**
     * Pure category search: [categoryName] is a category name in the engine's language ("pharmacy"), and only places of
     * that category are returned, not places whose name merely contains the word. Engines without a category mode fall
     * back to a free-text search.
     */
    fun searchCategory(categoryName: String, near: LatLon? = null, limit: Int = 20): List<SearchResult> =
        search(categoryName, near, limit)
}
