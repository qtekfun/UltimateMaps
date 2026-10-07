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
    /**
     * The CoMaps country id (`Spain_La Rioja`, spaces included): the core only finds a map as
     * `<comapsId>.mwm`. Optional (older catalogs lack it); without it the region cannot be linked to the core.
     */
    val comapsId: String? = null,
) {
    val isDownloadable: Boolean get() = assets.size == AssetKind.entries.size
    val totalBytes: Long get() = assets.values.sumOf { it.sizeBytes }
}

/**
 * `World.mwm` and `WorldCoasts.mwm`: not a region, but the core needs both next to every region map.
 * Downloaded once per [version] (a data date such as `261004`), before or with the first region.
 */
data class BaseMaps(val version: String, val world: RegionAsset, val worldCoasts: RegionAsset) {
    val totalBytes: Long get() = world.sizeBytes + worldCoasts.sizeBytes
}

class CatalogException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Versioned catalog of regions. Immutable. */
class RegionCatalog(val catalogVersion: String, regions: List<Region>, val base: BaseMaps? = null) {
    val regions: List<Region> = regions.toList()
    private val byId = this.regions.associateBy { it.id }

    init {
        require(byId.size == this.regions.size) { "duplicate region ids" }
        this.regions.forEach { r ->
            require(r.parentId == null || r.parentId in byId) { "unknown parent ${r.parentId} of ${r.id}" }
            require(r.id.matches(ID_RE)) { "invalid region id ${r.id}" }
            require(r.version.matches(FILE_RE)) { "invalid version ${r.version}" }
            r.assets.values.forEach { checkAsset(it, r.id) }
            r.comapsId?.let { require(it.matches(COMAPS_ID_RE) && !it.startsWith(".")) { "invalid comapsId for ${r.id}" } }
        }
        base?.let {
            require(it.version.matches(FILE_RE)) { "invalid base version ${it.version}" }
            checkAsset(it.world, "base.world")
            checkAsset(it.worldCoasts, "base.worldCoasts")
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

    private fun checkAsset(a: RegionAsset, what: String) {
        require(a.sha256.matches(SHA256_RE)) { "invalid sha256 for $what" }
        require(a.sizeBytes > 0) { "invalid size for $what" }
        require(a.fileName.matches(FILE_RE)) { "invalid file name ${a.fileName}" }
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
        if (base != null) put("base", buildJsonObject {
            put("version", base.version)
            put("world", assetJson(base.world))
            put("worldCoasts", assetJson(base.worldCoasts))
        })
        put("regions", buildJsonArray {
            regions.forEach { r ->
                add(buildJsonObject {
                    put("id", r.id)
                    put("name", r.name)
                    if (r.parentId != null) put("parent", r.parentId) else put("parent", JsonNull)
                    put("version", r.version)
                    if (r.comapsId != null) put("comapsId", r.comapsId)
                    if (r.assets.isNotEmpty()) put("assets", buildJsonObject {
                        r.assets.forEach { (k, a) ->
                            put(k.key, assetJson(a))
                        }
                    })
                })
            }
        })
    })

    private fun assetJson(a: RegionAsset) = buildJsonObject {
        put("url", a.url)
        put("size", a.sizeBytes)
        put("sha256", a.sha256)
        put("file", a.fileName)
    }

    companion object {
        const val SCHEMA = 1
        private val COMAPS_ID_RE = Regex("[^/\\\\\\p{Cntrl}]{1,150}")
        private val ID_RE = Regex("[A-Za-z0-9_.-]{1,100}")
        private val SHA256_RE = Regex("[0-9a-f]{64}")
        private val FILE_RE = Regex("[A-Za-z0-9_.-]{1,120}")

        /** Parses catalog JSON. Relative asset URLs are resolved against [baseUrl]. */
        fun parse(json: String, baseUrl: String? = null): RegionCatalog = try {
            val root = Json.parseToJsonElement(json).jsonObject
            val schema = root["schema"]?.jsonPrimitive?.long
            if (schema != SCHEMA.toLong()) throw CatalogException("unsupported catalog schema: $schema")
            val base = baseUrl?.let(URI::create)
            fun asset(x: JsonObject): RegionAsset {
                val raw = x.getValue("url").jsonPrimitive.content
                return RegionAsset(
                    if (base != null) base.resolve(raw).toString() else raw, x.getValue("size").jsonPrimitive.long,
                    x.getValue("sha256").jsonPrimitive.content.lowercase(), x.getValue("file").jsonPrimitive.content,
                )
            }
            val baseMaps = (root["base"] as? JsonObject)?.let { b ->
                BaseMaps(
                    b.getValue("version").jsonPrimitive.content,
                    asset(b.getValue("world").jsonObject), asset(b.getValue("worldCoasts").jsonObject),
                )
            }
            val regions = root.getValue("regions").jsonArray.map { e ->
                val o = e.jsonObject
                val assets = (o["assets"] as? JsonObject)?.let { a ->
                    AssetKind.entries.mapNotNull { k ->
                        (a[k.key] as? JsonObject)?.let { x -> k to asset(x) }
                    }.toMap()
                } ?: emptyMap()
                Region(
                    id = o.getValue("id").jsonPrimitive.content,
                    name = o.getValue("name").jsonPrimitive.content,
                    parentId = (o["parent"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
                    version = o.getValue("version").jsonPrimitive.content,
                    assets = assets,
                    comapsId = (o["comapsId"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
                )
            }
            RegionCatalog(root.getValue("catalogVersion").jsonPrimitive.content, regions, baseMaps)
        } catch (e: CatalogException) {
            throw e
        } catch (e: Exception) { // malformed JSON, missing fields, failed invariants
            throw CatalogException("invalid catalog: ${e.message}", e)
        }
    }
}
