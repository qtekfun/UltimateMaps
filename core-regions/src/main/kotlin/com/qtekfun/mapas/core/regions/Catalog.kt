package com.qtekfun.mapas.core.regions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.net.URI

/** The two downloads of a region under option C (see docs/phase1/regions.md). */
enum class AssetKind(val key: String) {
    /** PMTiles for rendering (MapLibre). */
    RENDER("render"),

    /** CoMaps .mwm for search and routing. */
    SEARCH("search"),
}

/** One downloadable file. [sha256] is lowercase hex. */
data class RegionAsset(val url: String, val sizeBytes: Long, val sha256: String, val fileName: String)

/**
 * A node of the world hierarchy. Interior nodes (continents, countries) have no [assets];
 * leaves have both. [version] is the data version (a date such as `261005`).
 */
data class Region(
    val id: String,
    val name: String,
    val parentId: String?,
    val version: String,
    val assets: Map<AssetKind, RegionAsset> = emptyMap(),
) {
    val isDownloadable: Boolean get() = assets.size == AssetKind.entries.size
    val totalBytes: Long get() = assets.values.sumOf { it.sizeBytes }
}

class CatalogException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Versioned catalog of regions. Immutable. */
class RegionCatalog(val catalogVersion: String, regions: List<Region>) {
    val regions: List<Region> = regions.toList()
    private val byId = this.regions.associateBy { it.id }

    init {
        require(byId.size == this.regions.size) { "duplicate region ids" }
        this.regions.forEach { r ->
            require(r.parentId == null || r.parentId in byId) { "unknown parent ${r.parentId} of ${r.id}" }
            require(r.id.matches(ID_RE)) { "invalid region id ${r.id}" }
            require(r.version.matches(FILE_RE)) { "invalid version ${r.version}" }
            r.assets.values.forEach {
                require(it.sha256.matches(SHA256_RE)) { "invalid sha256 for ${r.id}" }
                require(it.sizeBytes > 0) { "invalid size for ${r.id}" }
                require(it.fileName.matches(FILE_RE)) { "invalid file name ${it.fileName}" }
            }
        }
        this.regions.forEach { r ->
            var cur: String? = r.parentId
            var hops = 0
            while (cur != null) {
                require(++hops <= this.regions.size) { "cycle at ${r.id}" }
                cur = byId[cur]?.parentId
            }
        }
    }

    operator fun get(id: String): Region? = byId[id]

    fun children(parentId: String?): List<Region> = regions.filter { it.parentId == parentId }

    /** All downloadable leaves under [id] (or [id] itself), e.g. for "download all of Spain". */
    fun downloadableUnder(id: String): List<Region> {
        val r = byId[id] ?: return emptyList()
        return if (r.isDownloadable) listOf(r) else children(id).flatMap { downloadableUnder(it.id) }
    }

    fun toJson(): String = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), buildJsonObject {
        put("schema", SCHEMA)
        put("catalogVersion", catalogVersion)
        put("regions", buildJsonArray {
            regions.forEach { r ->
                add(buildJsonObject {
                    put("id", r.id)
                    put("name", r.name)
                    if (r.parentId != null) put("parent", r.parentId) else put("parent", JsonNull)
                    put("version", r.version)
                    if (r.assets.isNotEmpty()) put("assets", buildJsonObject {
                        r.assets.forEach { (k, a) ->
                            put(k.key, buildJsonObject {
                                put("url", a.url)
                                put("size", a.sizeBytes)
                                put("sha256", a.sha256)
                                put("file", a.fileName)
                            })
                        }
                    })
                })
            }
        })
    })

    companion object {
        const val SCHEMA = 1
        private val ID_RE = Regex("[A-Za-z0-9_.-]{1,100}")
        private val SHA256_RE = Regex("[0-9a-f]{64}")
        private val FILE_RE = Regex("[A-Za-z0-9_.-]{1,120}")

        /** Parses catalog JSON. Relative asset URLs are resolved against [baseUrl]. */
        fun parse(json: String, baseUrl: String? = null): RegionCatalog = try {
            val root = Json.parseToJsonElement(json).jsonObject
            val schema = root["schema"]?.jsonPrimitive?.long
            if (schema != SCHEMA.toLong()) throw CatalogException("unsupported catalog schema: $schema")
            val base = baseUrl?.let(URI::create)
            val regions = root.getValue("regions").jsonArray.map { e ->
                val o = e.jsonObject
                val assets = (o["assets"] as? JsonObject)?.let { a ->
                    AssetKind.entries.mapNotNull { k ->
                        (a[k.key] as? JsonObject)?.let { x ->
                            val raw = x.getValue("url").jsonPrimitive.content
                            val url = if (base != null) base.resolve(raw).toString() else raw
                            k to RegionAsset(
                                url, x.getValue("size").jsonPrimitive.long,
                                x.getValue("sha256").jsonPrimitive.content.lowercase(), x.getValue("file").jsonPrimitive.content,
                            )
                        }
                    }.toMap()
                } ?: emptyMap()
                Region(
                    id = o.getValue("id").jsonPrimitive.content,
                    name = o.getValue("name").jsonPrimitive.content,
                    parentId = (o["parent"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
                    version = o.getValue("version").jsonPrimitive.content,
                    assets = assets,
                )
            }
            RegionCatalog(root.getValue("catalogVersion").jsonPrimitive.content, regions)
        } catch (e: CatalogException) {
            throw e
        } catch (e: Exception) { // malformed JSON, missing fields, failed invariants
            throw CatalogException("invalid catalog: ${e.message}", e)
        }
    }
}
