package com.qtekfun.ultimatemaps.core.data

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.geo.io.PathKind
import com.qtekfun.ultimatemaps.core.geo.io.TrackPoint

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

    // Special places (Home, Work, parked car): one place per slot, outside the lists

    /** Stores [name] and [point] in [slot], replacing whatever was there. */
    fun setSpecial(slot: SpecialSlot, name: String, point: LatLon, savedAt: Long = System.currentTimeMillis())

    fun special(slot: SpecialSlot): SpecialPlace?
    fun clearSpecial(slot: SpecialSlot): Boolean

    // Recent searches (query text only; never positions)

    /**
     * Remembers [query] (trimmed; blank is ignored). The same text, ignoring case and accents, only moves to the
     * top. Only the [keep] most recent are kept.
     */
    fun addSearch(query: String, at: Long = System.currentTimeMillis(), keep: Int = DEFAULT_SEARCHES_KEPT)

    /** Most recent first. */
    fun recentSearches(limit: Int = DEFAULT_SEARCHES_KEPT): List<String>

    fun clearSearches()

    /** Removes the places, lists and tracks (used by "replace" restores). Special places and searches are kept. */
    fun clearAll()

    /** Runs [block] atomically: all changes are rolled back if it throws. */
    fun <T> transaction(block: () -> T): T
}

const val DEFAULT_SEARCHES_KEPT = 20
