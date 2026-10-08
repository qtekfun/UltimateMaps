package com.qtekfun.ultimatemaps.search

import androidx.annotation.StringRes
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.distanceTo
import com.qtekfun.ultimatemaps.core.search.SearchResult

/**
 * The categories offered under the search field. [query] is the string resource holding the category name the core
 * searches (CoMaps' `categories.txt` name in the UI language, for example "pharmacy" / "farmacia"); [label] is what
 * the chip shows.
 */
enum class PlaceCategory(val id: String, @StringRes val label: Int, @StringRes val query: Int) {
    PHARMACY("pharmacy", R.string.category_pharmacy, R.string.category_query_pharmacy),
    SUPERMARKET("supermarket", R.string.category_supermarket, R.string.category_query_supermarket),
    RESTAURANT("restaurant", R.string.category_restaurant, R.string.category_query_restaurant),
    CAFE("cafe", R.string.category_cafe, R.string.category_query_cafe),
    FUEL("fuel", R.string.category_fuel, R.string.category_query_fuel),
    ATM("atm", R.string.category_atm, R.string.category_query_atm),
    HOSPITAL("hospital", R.string.category_hospital, R.string.category_query_hospital),
    PARKING("parking", R.string.category_parking, R.string.category_query_parking),
    LODGING("lodging", R.string.category_lodging, R.string.category_query_lodging),
    TOILETS("toilets", R.string.category_toilets, R.string.category_query_toilets),
    DRINKING_WATER("drinking_water", R.string.category_drinking_water, R.string.category_query_drinking_water),
}

/**
 * Attaches the straight-line distance from [origin] to every result and sorts nearest first. Without an origin the
 * order of the core is kept and no distance is shown.
 */
fun sortedByDistance(results: List<SearchResult>, origin: LatLon?): List<SearchResult> {
    if (origin == null) return results
    return results.map { it.copy(distanceMeters = origin.distanceTo(it.point)) }.sortedBy { it.distanceMeters }
}
