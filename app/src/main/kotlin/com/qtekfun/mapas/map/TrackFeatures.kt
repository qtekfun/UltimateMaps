package com.qtekfun.mapas.map

import com.qtekfun.mapas.core.map.TrackLine
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** GeoJSON for the imported-tracks layer: one `LineString` per segment, tagged with the track id and a `#RRGGBB` colour. */
object TrackFeatures {
    const val ID = "id"
    const val COLOR = "color"

    fun collection(tracks: List<TrackLine>): FeatureCollection {
        val features = ArrayList<Feature>()
        for (track in tracks) {
            val color = hex(track.color)
            for (segment in track.segments) {
                if (segment.size < 2) continue // a single point has no line
                val line = LineString.fromLngLats(segment.map { Point.fromLngLat(it.lon, it.lat) })
                features += Feature.fromGeometry(line).also {
                    it.addNumberProperty(ID, track.id)
                    it.addStringProperty(COLOR, color)
                }
            }
        }
        return FeatureCollection.fromFeatures(features)
    }

    /** Opaque `#RRGGBB` (the layer applies its own opacity). */
    fun hex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)
}
