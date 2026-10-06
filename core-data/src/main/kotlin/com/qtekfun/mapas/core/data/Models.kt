package com.qtekfun.mapas.core.data

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.geo.io.PathKind
import com.qtekfun.mapas.core.geo.io.TrackPoint

/** [color] is ARGB; [icon] is a symbolic name resolved by the UI. Both are optional. */
data class Place(
    val id: Long,
    val name: String,
    val point: LatLon,
    val notes: String? = null,
    val icon: String? = null,
    val color: Int? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

data class PlaceList(
    val id: Long,
    val name: String,
    val color: Int? = null,
    val icon: String? = null,
    val notes: String? = null,
    val placeCount: Int = 0,
)

data class TrackInfo(
    val id: Long,
    val name: String,
    val kind: PathKind,
    val notes: String? = null,
    val color: Int? = null,
    val pointCount: Int = 0,
    val createdAt: Long = 0,
)

class Track(val info: TrackInfo, val segments: List<List<TrackPoint>>)

/** Result of [PlacesRepository.addPlaceIfNew]. */
data class AddResult(val id: Long, val created: Boolean)
