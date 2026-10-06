package com.qtekfun.mapas.core.data

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.geo.io.PathKind
import com.qtekfun.mapas.core.geo.io.TrackPoint

/** Local store for places, lists and tracks (RF-08). Implementations are thread-safe. */
interface PlacesRepository : AutoCloseable {
    // Places
    fun addPlace(
        name: String, point: LatLon, notes: String? = null, icon: String? = null, color: Int? = null,
        createdAt: Long = System.currentTimeMillis(),
    ): Long

    /** Adds the place unless one with the same name and (≈1 m rounded) position exists. */
    fun addPlaceIfNew(
        name: String, point: LatLon, notes: String? = null, icon: String? = null, color: Int? = null,
    ): AddResult

    fun updatePlace(place: Place): Boolean
    fun deletePlace(id: Long): Boolean
    fun getPlace(id: Long): Place?

    /**
     * Places, optionally restricted to list [listId] and to those matching every word of [query]
     * (case/accent-insensitive, name and notes). Sorted by distance from [near] when given, else by name.
     */
    fun places(listId: Long? = null, query: String? = null, near: LatLon? = null): List<Place>

    // Lists
    fun addList(name: String, color: Int? = null, icon: String? = null, notes: String? = null): Long
    fun updateList(list: PlaceList): Boolean
    fun deleteList(id: Long): Boolean
    fun getList(id: Long): PlaceList?
    fun lists(): List<PlaceList>
    fun addToList(listId: Long, placeId: Long): Boolean
    fun removeFromList(listId: Long, placeId: Long): Boolean
    fun listsOf(placeId: Long): List<PlaceList>

    // Tracks
    fun addTrack(
        name: String, kind: PathKind, segments: List<List<TrackPoint>>, notes: String? = null, color: Int? = null,
        createdAt: Long = System.currentTimeMillis(),
    ): Long

    /** Adds the track unless one with the same name, kind and geometry exists. */
    fun addTrackIfNew(name: String, kind: PathKind, segments: List<List<TrackPoint>>): AddResult

    fun updateTrack(info: TrackInfo): Boolean
    fun deleteTrack(id: Long): Boolean
    fun tracks(query: String? = null): List<TrackInfo>
    fun track(id: Long): Track?

    /** Removes everything (used by "replace" restores). */
    fun clearAll()

    /** Runs [block] atomically: all changes are rolled back if it throws. */
    fun <T> transaction(block: () -> T): T
}
