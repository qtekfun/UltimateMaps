package com.qtekfun.mapas.nativecomaps.isolation

import com.qtekfun.mapas.core.geo.LatLon
import com.qtekfun.mapas.core.routing.RouteRequest
import com.qtekfun.mapas.nativecomaps.CoreHandle
import com.qtekfun.mapas.nativecomaps.RouteOutcome
import com.qtekfun.mapas.nativecomaps.toNative
import com.qtekfun.mapas.core.routing.RoutingProfile

/**
 * The server half, running inside the `:core` process: turns a request into a call on the real [CoreHandle] (the
 * native core) and the answer into bytes. Pure Kotlin: the Binder service is a thin shell around [handle], and the
 * tests drive it directly.
 *
 * Calls are serialised (CoMaps has global state, one call at a time), initialisation happens once, and a reply
 * larger than [CoreProtocol.MAX_INLINE] is parked here and handed out in chunks. Only a handful of parked replies
 * are kept (the client fetches right away), so a client that dies mid-fetch cannot make the host leak.
 *
 * A Java exception becomes an error reply; a native abort (the SIGABRT this whole design exists for) kills the
 * process, which the client detects.
 */
class CoreHost(private val core: CoreHandle, private val maxParked: Int = 4) {
    private val lock = Any()
    private var initialized = false
    private var nextId = 1L
    private val parked = LinkedHashMap<Long, ByteArray>()

    /** Handles one request and returns the encoded [CoreProtocol.Reply]. Never throws. */
    fun handle(op: Int, payload: ByteArray): ByteArray = try {
        CoreProtocol.encodeReply(dispatch(op, payload))
    } catch (e: java.io.IOException) {
        error(CoreProtocol.ERR_BAD_REQUEST, "bad request")
    } catch (e: Exception) {
        error(CoreProtocol.ERR_INTERNAL, e.javaClass.simpleName) // never the message: it could echo a query
    }

    private fun error(kind: Int, message: String) =
        CoreProtocol.encodeReply(CoreProtocol.Reply(CoreProtocol.KIND_ERROR, CoreProtocol.encodeError(kind, message)))

    private fun ok(bytes: ByteArray) = CoreProtocol.Reply(CoreProtocol.KIND_OK, bytes)

    private fun errorReply(kind: Int, message: String) =
        CoreProtocol.Reply(CoreProtocol.KIND_ERROR, CoreProtocol.encodeError(kind, message))

    private fun dispatch(op: Int, payload: ByteArray): CoreProtocol.Reply = synchronized(lock) {
        when (op) {
            CoreProtocol.OP_PING -> ok(ByteArray(0))
            CoreProtocol.OP_INIT -> {
                val a = CoreProtocol.decodeInit(payload)
                if (!initialized) {
                    try {
                        core.init(a.apkPath, a.mapsDir, a.tmpDir, a.locale)
                    } catch (e: IllegalStateException) {
                        return errorReply(CoreProtocol.ERR_INIT_FAILED, e.message ?: "init failed")
                    }
                    initialized = true
                }
                ok(ByteArray(0))
            }
            CoreProtocol.OP_REFRESH -> {
                if (!initialized) return errorReply(CoreProtocol.ERR_NOT_INITIALIZED, "not initialized")
                ok(CoreProtocol.write { it.writeInt(core.refreshMaps()) })
            }
            CoreProtocol.OP_SEARCH -> {
                if (!initialized) return errorReply(CoreProtocol.ERR_NOT_INITIALIZED, "not initialized")
                val a = CoreProtocol.decodeSearch(payload)
                val results = core.searchEngine(a.locale, a.timeoutMs).search(a.query, a.near, a.limit)
                respond(CoreProtocol.encodeSearchResults(results))
            }
            CoreProtocol.OP_ROUTE -> {
                if (!initialized) return errorReply(CoreProtocol.ERR_NOT_INITIALIZED, "not initialized")
                val a = CoreProtocol.decodeRoute(payload)
                val pts = a.points
                val points = (0 until pts.size / 2).map { LatLon.ofOrNull(pts[2 * it], pts[2 * it + 1]) ?: return errorReply(CoreProtocol.ERR_BAD_REQUEST, "bad point") }
                val profile = RoutingProfile.entries.firstOrNull { it.toNative() == a.profile } ?: return errorReply(CoreProtocol.ERR_BAD_REQUEST, "bad profile")
                val request = RouteRequest(points.first(), points.last(), points.subList(1, points.size - 1), profile, optionsOf(a.avoidFlags))
                val outcome: RouteOutcome = core.routingEngine(a.timeoutSec, a.withGuidance).routeDetailed(request)
                respond(CoreProtocol.encodeOutcome(outcome))
            }
            CoreProtocol.OP_FETCH -> {
                val (id, offset) = CoreProtocol.decodeFetch(payload)
                val data = parked[id] ?: return errorReply(CoreProtocol.ERR_BAD_REQUEST, "unknown reply")
                if (offset < 0 || offset > data.size) return errorReply(CoreProtocol.ERR_BAD_REQUEST, "bad offset")
                val end = minOf(data.size, offset + CoreProtocol.CHUNK)
                if (end == data.size) parked.remove(id) // last piece: nothing left to keep
                ok(data.copyOfRange(offset, end))
            }
            CoreProtocol.OP_RELEASE -> {
                parked.remove(CoreProtocol.decodeFetch(payload).first)
                ok(ByteArray(0))
            }
            else -> errorReply(CoreProtocol.ERR_BAD_REQUEST, "unknown op")
        }
    }

    private fun respond(bytes: ByteArray): CoreProtocol.Reply {
        if (bytes.size <= CoreProtocol.MAX_INLINE) return ok(bytes)
        val id = nextId++
        parked[id] = bytes
        while (parked.size > maxParked) parked.remove(parked.keys.first())
        return CoreProtocol.Reply(
            CoreProtocol.KIND_CHUNKED,
            CoreProtocol.encodeChunkHeader(id, bytes.size, bytes.copyOfRange(0, CoreProtocol.CHUNK.coerceAtMost(bytes.size))),
        )
    }

    private fun optionsOf(f: Int) = com.qtekfun.mapas.core.routing.RouteOptions.fromFlags(f)

    /** Number of replies parked and not yet fetched (tests and diagnostics). */
    val parkedCount: Int get() = synchronized(lock) { parked.size }
}
