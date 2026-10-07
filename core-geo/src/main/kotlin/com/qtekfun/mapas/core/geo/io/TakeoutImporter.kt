package com.qtekfun.mapas.core.geo.io

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.geo.link.MapLink
import com.qtekfun.mapas.core.geo.link.MapLinkParser
import java.io.IOException
import java.io.InputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Google Takeout importers (RF-09):
 *  - "Saved Places.json" (GeoJSON FeatureCollection of starred/saved places)
 *  - list CSVs (Title, Note, URL, Comment), which carry no coordinates
 *
 * Places without coordinates come back with `point = null` and their [ImportedPlace.url], so the
 * app can resolve them later (opt-in link resolution or local search by name).
 */
object TakeoutImporter {

    fun parseSavedPlacesGeoJson(input: InputStream): GeoDocument {
        val root = try {
            Json.parseToJsonElement(input.readBytes().toString(Charsets.UTF_8).removePrefix("﻿"))
        } catch (e: SerializationException) {
            throw GeoImportException("Invalid GeoJSON: ${e.message}", e)
        } catch (e: IOException) {
            throw GeoImportException("Cannot read GeoJSON: ${e.message}", e)
        }
        val features = (root as? JsonObject)?.get("features") as? JsonArray
            ?: throw GeoImportException("Not a GeoJSON FeatureCollection")
        val places = ArrayList<ImportedPlace>()
        var skipped = 0
        for (f in features) {
            val place = (f as? JsonObject)?.let(::featureToPlace)
            if (place == null) skipped++ else places += place
        }
        return GeoDocument(places, emptyList(), skipped)
    }

    private fun featureToPlace(feature: JsonObject): ImportedPlace? {
        val props = feature.field("properties") as? JsonObject
        val location = props?.field("location") as? JsonObject
        val url = props?.str("google_maps_url")
        val name = props?.str("title") ?: props?.str("name") ?: location?.str("business_name") ?: location?.str("name")
        val address = location?.str("address")

        var point: LatLon? = null
        val geometry = feature.field("geometry") as? JsonObject
        val geometryType = geometry?.str("type")
        if (geometry != null && geometryType != null && geometryType != "Point") return null
        val coords = geometry?.get("coordinates") as? JsonArray
        if (coords != null && coords.size >= 2) {
            val lon = coords[0].number()
            val lat = coords[1].number()
            // Takeout writes [0, 0] when it does not know the position.
            if (lat != null && lon != null && !(lat == 0.0 && lon == 0.0)) point = LatLon.ofOrNull(lat, lon)
        }
        if (point == null) {
            val gc = location?.field("geo_coordinates") as? JsonObject
            val lat = gc?.field("latitude")?.number()
            val lon = gc?.field("longitude")?.number()
            if (lat != null && lon != null && !(lat == 0.0 && lon == 0.0)) point = LatLon.ofOrNull(lat, lon)
        }
        if (point == null && url != null) {
            point = (MapLinkParser.parse(url) as? MapLink.Coordinates)?.point
        }
        if (point == null && name == null && url == null) return null
        return ImportedPlace(point = point, name = name, description = address, url = url)
    }

    /** Takeout has changed its key spelling over time ("Title" / "title", "Geo Coordinates" / "geo_coordinates"). */
    private fun normKey(key: String) = key.trim().lowercase().replace(' ', '_')

    private fun JsonObject.field(key: String): JsonElement? =
        this[key] ?: entries.firstOrNull { normKey(it.key) == key }?.value

    private fun JsonObject.str(key: String): String? = (field(key) as? JsonPrimitive)?.contentOrNull.cleanText()

    private fun JsonElement.number(): Double? = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull() }

    // ------------------------------------------------------------------ CSV lists

    private val titleKeys = setOf("title", "título", "titulo", "name", "nombre")
    private val noteKeys = setOf("note", "nota", "notes", "notas")
    private val commentKeys = setOf("comment", "comentario")
    private val urlKeys = setOf("url", "enlace")

    fun parseListCsv(input: InputStream): GeoDocument {
        val rows = try {
            Csv.parse(input.readBytes().toString(Charsets.UTF_8).removePrefix("﻿"))
        } catch (e: IOException) {
            throw GeoImportException("Cannot read CSV: ${e.message}", e)
        }
        val headerIdx = rows.indexOfFirst { r -> r.any { it.trim().lowercase() in titleKeys + urlKeys } }
        if (headerIdx < 0) throw GeoImportException("CSV has no Title/URL header")
        val header = rows[headerIdx].map { it.trim().lowercase() }
        val iTitle = header.indexOfFirst { it in titleKeys }
        val iNote = header.indexOfFirst { it in noteKeys }
        val iComment = header.indexOfFirst { it in commentKeys }
        val iUrl = header.indexOfFirst { it in urlKeys }

        val places = ArrayList<ImportedPlace>()
        var skipped = 0
        for (row in rows.drop(headerIdx + 1)) {
            if (row.all { it.isBlank() }) continue
            fun cell(i: Int) = if (i >= 0) row.getOrNull(i).cleanText() else null
            val title = cell(iTitle)
            val url = cell(iUrl)
            if (title == null && url == null) {
                skipped++
                continue
            }
            val description = listOfNotNull(cell(iNote), cell(iComment)).joinToString("\n").ifEmpty { null }
            val point = url?.let { (MapLinkParser.parse(it) as? MapLink.Coordinates)?.point }
            places += ImportedPlace(point = point, name = title, description = description, url = url)
        }
        return GeoDocument(places, emptyList(), skipped)
    }
}

/** Small RFC 4180 reader: quoted fields, doubled quotes, embedded newlines, CRLF or LF. */
internal object Csv {
    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(c)
                }
            } else when (c) {
                '"' -> inQuotes = true
                ',' -> {
                    row += field.toString()
                    field.setLength(0)
                }
                '\r' -> {}
                '\n' -> {
                    row += field.toString()
                    field.setLength(0)
                    rows += row
                    row = ArrayList()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            rows += row
        }
        return rows
    }
}
