package com.qtekfun.mapas.nativecomaps.isolation

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.routing.RoutePlanCodec
import com.qtekfun.mapas.core.search.SearchResult
import com.qtekfun.mapas.nativecomaps.RouteOutcome
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.IOException

/**
 * Wire format between the main process and the isolated core process. Pure Kotlin on `java.io`: the same bytes
 * go through Binder on a device and through a fake in the unit tests. Nothing here is ever logged.
 *
 * A request is `op` + a payload; a reply is [Reply]. Binder limits all the transactions in flight of a process to
 * about 1 MB, and a route of tens of thousands of points is several hundred KB, so a reply bigger than
 * [MAX_INLINE] is kept by the host and fetched in [CHUNK]-sized pieces (see [CoreHost]).
 */
object CoreProtocol {
    const val OP_INIT = 1
    const val OP_REFRESH = 2
    const val OP_SEARCH = 3
    const val OP_ROUTE = 4
    const val OP_FETCH = 5
    const val OP_RELEASE = 6
    const val OP_PING = 7

    /** Not a normal call: `oneway`, makes the core process kill itself (the only way to stop a native calculation). */
    const val OP_KILL = 99

    const val MAX_INLINE = 192 * 1024
    const val CHUNK = 192 * 1024

    /** Upper bound of a whole reply the client accepts (a sanity limit against a corrupt length). */
    const val MAX_REPLY = 64 * 1024 * 1024

    const val KIND_OK = 0
    const val KIND_ERROR = 1
    const val KIND_CHUNKED = 2

    /** Error kinds a host can report; the client maps them to [CoreFailureKind]. */
    const val ERR_NOT_INITIALIZED = 1
    const val ERR_INIT_FAILED = 2
    const val ERR_INTERNAL = 3
    const val ERR_BAD_REQUEST = 4

    class Reply(val kind: Int, val bytes: ByteArray)

    inline fun write(block: (DataOutput) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(block)
        return bytes.toByteArray()
    }

    inline fun <T> read(bytes: ByteArray, block: (DataInput) -> T): T = DataInputStream(ByteArrayInputStream(bytes)).use(block)

    fun writeText(out: DataOutput, text: String?) {
        if (text == null) {
            out.writeInt(-1)
            return
        }
        val b = text.toByteArray(Charsets.UTF_8)
        out.writeInt(b.size)
        out.write(b)
    }

    fun readText(input: DataInput): String? {
        val n = input.readInt()
        if (n == -1) return null
        if (n < 0 || n > 1 shl 20) throw IOException("bad text length $n")
        val b = ByteArray(n)
        input.readFully(b)
        return String(b, Charsets.UTF_8)
    }

    // ---- requests ----

    class InitArgs(val apkPath: String, val mapsDir: String, val tmpDir: String, val locale: String)

    fun encodeInit(a: InitArgs): ByteArray = write { out ->
        writeText(out, a.apkPath); writeText(out, a.mapsDir); writeText(out, a.tmpDir); writeText(out, a.locale)
    }

    fun decodeInit(bytes: ByteArray): InitArgs = read(bytes) { i ->
        InitArgs(readText(i) ?: "", readText(i) ?: "", readText(i) ?: "", readText(i) ?: "en")
    }

    class SearchArgs(val query: String, val near: LatLon?, val limit: Int, val locale: String, val timeoutMs: Int)

    fun encodeSearch(a: SearchArgs): ByteArray = write { out ->
        writeText(out, a.query)
        out.writeBoolean(a.near != null)
        out.writeDouble(a.near?.lat ?: 0.0)
        out.writeDouble(a.near?.lon ?: 0.0)
        out.writeInt(a.limit)
        writeText(out, a.locale)
        out.writeInt(a.timeoutMs)
    }

    fun decodeSearch(bytes: ByteArray): SearchArgs = read(bytes) { i ->
        val query = readText(i) ?: ""
        val has = i.readBoolean()
        val lat = i.readDouble()
        val lon = i.readDouble()
        SearchArgs(query, if (has) LatLon.ofOrNull(lat, lon) else null, i.readInt(), readText(i) ?: "en", i.readInt())
    }

    class RouteArgs(val profile: Int, val points: DoubleArray, val avoidFlags: Int, val timeoutSec: Int, val withGuidance: Boolean)

    fun encodeRoute(a: RouteArgs): ByteArray = write { out ->
        out.writeInt(a.profile)
        out.writeInt(a.points.size)
        for (d in a.points) out.writeDouble(d)
        out.writeInt(a.avoidFlags)
        out.writeInt(a.timeoutSec)
        out.writeBoolean(a.withGuidance)
    }

    fun decodeRoute(bytes: ByteArray): RouteArgs = read(bytes) { i ->
        val profile = i.readInt()
        val n = i.readInt()
        if (n < 4 || n > 2_000 || n % 2 != 0) throw IOException("bad point count $n")
        val points = DoubleArray(n) { i.readDouble() }
        RouteArgs(profile, points, i.readInt(), i.readInt(), i.readBoolean())
    }

    // ---- replies ----

    fun encodeSearchResults(results: List<SearchResult>): ByteArray = write { out ->
        out.writeInt(results.size)
        for (r in results) {
            writeText(out, r.name)
            out.writeDouble(r.point.lat)
            out.writeDouble(r.point.lon)
            writeText(out, r.address)
            writeText(out, r.category)
        }
    }

    fun decodeSearchResults(bytes: ByteArray): List<SearchResult> = read(bytes) { i ->
        val n = i.readInt()
        if (n < 0 || n > 10_000) throw IOException("bad result count $n")
        List(n) {
            val name = readText(i) ?: ""
            val p = LatLon.ofOrNull(i.readDouble(), i.readDouble()) ?: throw IOException("bad position")
            SearchResult(name, p, readText(i), readText(i))
        }
    }

    fun encodeOutcome(o: RouteOutcome): ByteArray = write { out ->
        out.writeInt(o.code)
        writeText(out, o.guidanceError)
        out.writeBoolean(o.plan != null)
        o.plan?.let { RoutePlanCodec.write(out, it) }
        out.writeInt(o.absentCountries.size)
        for (c in o.absentCountries) writeText(out, c)
    }

    fun decodeOutcome(bytes: ByteArray): RouteOutcome = read(bytes) { i ->
        val code = i.readInt()
        val guidanceError = readText(i)
        val plan = if (i.readBoolean()) RoutePlanCodec.read(i) else null
        val n = i.readInt()
        if (n < 0 || n > 1_000) throw IOException("bad absent count $n")
        RouteOutcome(code, plan, guidanceError, List(n) { readText(i) ?: "" })
    }

    fun encodeError(kind: Int, message: String): ByteArray = write { out ->
        out.writeInt(kind)
        writeText(out, message.take(512))
    }

    fun decodeError(bytes: ByteArray): Pair<Int, String> = read(bytes) { i -> i.readInt() to (readText(i) ?: "") }

    fun encodeReply(r: Reply): ByteArray {
        val out = ByteArray(1 + r.bytes.size)
        out[0] = r.kind.toByte()
        System.arraycopy(r.bytes, 0, out, 1, r.bytes.size)
        return out
    }

    fun decodeReply(bytes: ByteArray): Reply {
        if (bytes.isEmpty()) throw IOException("empty reply")
        return Reply(bytes[0].toInt(), bytes.copyOfRange(1, bytes.size))
    }

    fun encodeChunkHeader(id: Long, total: Int, first: ByteArray): ByteArray = write { out ->
        out.writeLong(id)
        out.writeInt(total)
        out.writeInt(first.size)
        out.write(first)
    }

    class ChunkHeader(val id: Long, val total: Int, val first: ByteArray)

    fun decodeChunkHeader(bytes: ByteArray): ChunkHeader = read(bytes) { i ->
        val id = i.readLong()
        val total = i.readInt()
        val n = i.readInt()
        if (total < 0 || total > MAX_REPLY || n < 0 || n > total) throw IOException("bad chunk header")
        val first = ByteArray(n)
        i.readFully(first)
        ChunkHeader(id, total, first)
    }

    fun encodeFetch(id: Long, offset: Int): ByteArray = write { out -> out.writeLong(id); out.writeInt(offset) }
    fun decodeFetch(bytes: ByteArray): Pair<Long, Int> = read(bytes) { i -> i.readLong() to i.readInt() }
}
