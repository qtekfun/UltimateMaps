package com.qtekfun.ultimatemaps.nativecomaps.isolation

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.routing.RoutePlan
import com.qtekfun.ultimatemaps.core.routing.RouteRequest
import com.qtekfun.ultimatemaps.core.search.SearchEngine
import com.qtekfun.ultimatemaps.core.search.SearchResult
import com.qtekfun.ultimatemaps.nativecomaps.CoreHandle
import com.qtekfun.ultimatemaps.nativecomaps.DetailedRoutingEngine
import com.qtekfun.ultimatemaps.nativecomaps.RouteCode
import com.qtekfun.ultimatemaps.nativecomaps.RouteOutcome
import com.qtekfun.ultimatemaps.nativecomaps.toNative
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock

/** Why a call to the isolated core failed. Structured so the UI can say something useful and retry sensibly. */
enum class CoreFailureKind {
    /** The core process died during the call, and again during the one retry. */
    CRASHED,

    /** The call did not finish in time; the core process was killed (the only way to stop a native calculation). */
    TIMEOUT,

    /** The core process could not be reached (bind failed) or it keeps dying: calls fail fast for a while. */
    UNAVAILABLE,

    /** The caller gave up (its thread was interrupted); the core process was killed. */
    CANCELLED,

    /** `init` was refused (missing data...) or never called. */
    INIT_FAILED,

    /** Anything else: a Java exception inside the core, or a reply that does not parse. */
    INTERNAL,
}

class CoreException(val kind: CoreFailureKind, message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The process on the other side died (or was never there): the connection is useless from now on. */
class CoreDiedException(message: String = "core process died", cause: Throwable? = null) : Exception(message, cause)

/** One live link to the core process. Implemented over Binder on Android and by a fake in the tests. */
interface CoreConnection {
    /** Sends [payload] for operation [op] and returns the raw reply. Blocks. @throws CoreDiedException */
    fun call(op: Int, payload: ByteArray): ByteArray

    val isAlive: Boolean

    /** Makes the core process die now (it may be stuck in native code). Must unblock a [call] in progress. Never throws. */
    fun kill()

    /** Drops the link without killing the process. */
    fun close()
}

/** Starts (or finds) the core process and connects to it. */
fun interface CoreTransport {
    /** @throws CoreDiedException if the process could not be started or reached within [timeoutMillis]. */
    fun connect(timeoutMillis: Long): CoreConnection
}

/** Time and failure limits of the client; injectable so the tests are fast and exact. */
class IsolationConfig(
    /** Extra time on top of a call's own budget, for the process to start, initialise and load the maps. */
    val restartBudgetMillis: Long = 12_000L,
    /** Extra time on top of the native timeout of a search before the client stops waiting. */
    val searchSlackMillis: Long = 3_000L,
    val routeSlackMillis: Long = 5_000L,
    /** This many deaths within [crashWindowMillis] open the circuit: calls fail fast for [circuitOpenMillis]. */
    val maxCrashes: Int = 3,
    val crashWindowMillis: Long = 60_000L,
    val circuitOpenMillis: Long = 30_000L,
)

/**
 * The client half: a [CoreHandle] whose work happens in another process, so that a native abort (a CoMaps `CHECK`)
 * kills only that process.
 *
 * - **Lazy**: nothing is started until the first call; [init] only remembers the arguments. The remote `INIT` is
 *   sent once per connection (the host ignores a second one), so there is never a double initialisation, and after
 *   a restart the client initialises the new process again by itself, then re-scans the maps if that was asked.
 * - **Death**: detected as [CoreDiedException] from the connection. The call restarts the process and is retried
 *   exactly once; a second death is a [CoreFailureKind.CRASHED] error, not a loop. Deaths are counted: repeated
 *   ones open a circuit breaker so a poisoned request cannot crash-loop the app.
 * - **Time**: every call has a deadline. The blocking transport call runs on a worker thread; if it does not
 *   answer in time, or the caller's thread is interrupted (coroutine cancelled), the process is killed, which also
 *   releases the worker. So abandoned calculations never accumulate: there is at most one call in flight and a
 *   stuck one never outlives its deadline.
 * - **Size**: replies the host parked are fetched in chunks inside the same deadline.
 *
 * One call at a time (the native core is not re-entrant); other threads wait their turn within their own deadline.
 */
class IsolatedCore(
    private val transport: CoreTransport,
    private val config: IsolationConfig = IsolationConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val executor: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "core-call").apply { isDaemon = true } },
    /**
     * Told, in a few fixed words, when the core process dies or is killed ("died during route", "killed: deadline"). For the
     * local diagnostic notes only: never a query, a position or an exception message. Must not throw (a throw is ignored).
     */
    private val onEvent: (String) -> Unit = {},
) : CoreHandle, AutoCloseable {
    private val callLock = ReentrantLock()
    private var connection: CoreConnection? = null
    private var initArgs: CoreProtocol.InitArgs? = null
    @Volatile private var remoteInitDone = false
    @Volatile private var refreshWanted = false
    private val argsLock = Any()
    private val crashes = ArrayDeque<Long>()
    private var circuitOpenUntil = 0L

    /** Number of times the core process died or was killed since this client was created (diagnostics and tests). */
    @Volatile var restarts = 0
        private set

    /** True while connected to a live core process. */
    val isConnected: Boolean get() = connection?.isAlive == true

    // ---- CoreHandle ----

    override fun init(apkPath: String, mapsDir: String, tmpDir: String, locale: String) {
        val next = CoreProtocol.InitArgs(apkPath, mapsDir, tmpDir, locale)
        val restartNeeded = synchronized(argsLock) {
            val prev = initArgs
            initArgs = next // no call: the process starts on the first real use
            prev != null && (prev.apkPath != next.apkPath || prev.mapsDir != next.mapsDir || prev.tmpDir != next.tmpDir)
        }
        if (restartNeeded) {
            // Different data than the running core was started with: it must be started again with the new ones.
            callLock.lock()
            try {
                dropConnectionLocked(kill = true)
            } finally {
                callLock.unlock()
            }
        }
        // Same arguments (the usual case: every search and route calls init first): nothing to do, no lock taken.
    }

    override fun refreshMaps(): Int {
        refreshWanted = true
        val reply = call(CoreProtocol.OP_REFRESH, ByteArray(0), 0L, "refresh") { it }
        return try {
            CoreProtocol.read(reply) { it.readInt() }
        } catch (e: IOException) {
            throw CoreException(CoreFailureKind.INTERNAL, "bad refresh reply", e)
        }
    }

    override fun searchEngine(locale: String, timeoutMs: Int): SearchEngine = object : SearchEngine {
        override fun search(query: String, near: LatLon?, limit: Int): List<SearchResult> =
            ask(CoreProtocol.OP_SEARCH, query, near, limit)

        override fun searchCategory(query: String, near: LatLon?, limit: Int): List<SearchResult> =
            ask(CoreProtocol.OP_SEARCH_CATEGORY, query, near, limit)

        private fun ask(op: Int, query: String, near: LatLon?, limit: Int): List<SearchResult> {
            if (query.isBlank()) return emptyList()
            val payload = CoreProtocol.encodeSearch(CoreProtocol.SearchArgs(query.trim(), near, limit, locale, timeoutMs))
            val bytes = call(op, payload, timeoutMs + config.searchSlackMillis, "search") { it }
            return try {
                CoreProtocol.decodeSearchResults(bytes)
            } catch (e: IOException) {
                throw CoreException(CoreFailureKind.INTERNAL, "bad search reply", e)
            }
        }

        override fun close() = Unit
    }

    override fun routingEngine(timeoutSec: Int, withGuidance: Boolean): DetailedRoutingEngine = object : DetailedRoutingEngine {
        override fun route(request: RouteRequest): RoutePlan? = routeDetailed(request).plan

        override fun routeDetailed(request: RouteRequest): RouteOutcome {
            val pts = (listOf(request.from) + request.via + request.to).flatMap { listOf(it.lat, it.lon) }
            val payload = CoreProtocol.encodeRoute(
                CoreProtocol.RouteArgs(request.profile.toNative(), pts.toDoubleArray(), request.options.toFlags(), timeoutSec, withGuidance),
            )
            return try {
                val bytes = call(CoreProtocol.OP_ROUTE, payload, timeoutSec * 1000L + config.routeSlackMillis, "route") { it }
                CoreProtocol.decodeOutcome(bytes)
            } catch (e: CoreException) {
                RouteOutcome(codeFor(e.kind), null, "core_" + e.kind.name.lowercase())
            } catch (e: IOException) {
                RouteOutcome(RouteCode.CORE_INTERNAL, null, "core_bad_reply")
            } catch (e: RuntimeException) {
                // Anything else (a reply that parses into nonsense, an unexpected state): a failed route, never a crash of the caller.
                event("route: unexpected ${e.javaClass.simpleName}")
                RouteOutcome(RouteCode.CORE_INTERNAL, null, "core_unexpected")
            }
        }

        override fun close() = Unit
    }

    override fun close() {
        synchronized(this) { dropConnectionLocked(kill = false) }
        executor.shutdown()
    }

    // ---- the call machinery ----

    /**
     * Runs one operation with the retry-once, deadline and kill-on-timeout rules. [budgetMillis] is the call's own
     * time; the process may need up to [IsolationConfig.restartBudgetMillis] more to start and initialise.
     */
    private fun <T> call(op: Int, payload: ByteArray, budgetMillis: Long, what: String, convert: (ByteArray) -> T): T {
        val start = clock()
        var deadline = start + budgetMillis + config.restartBudgetMillis
        if (!tryLock(deadline - start)) throw CoreException(CoreFailureKind.TIMEOUT, "$what waited too long for the core")
        try {
            checkCircuit()
            var lastDeath: CoreDiedException? = null
            for (attempt in 0..1) {
                val conn = try {
                    ensureReady(deadline)
                } catch (e: CoreDiedException) {
                    dropConnectionLocked(kill = false)
                    noteDeath()
                    lastDeath = e
                    deadline = maxOf(deadline, clock() + config.restartBudgetMillis)
                    continue
                }
                try {
                    val out = exchange(conn, op, payload, deadline)
                    return convert(out)
                } catch (e: CoreDiedException) {
                    dropConnectionLocked(kill = false)
                    noteDeath()
                    lastDeath = e
                    // One retry, with a fresh process, inside the same overall deadline (never a loop).
                    deadline = maxOf(deadline, clock() + config.restartBudgetMillis)
                }
            }
            throw CoreException(CoreFailureKind.CRASHED, "$what: the core died twice", lastDeath)
        } finally {
            callLock.unlock()
        }
    }

    private fun tryLock(waitMillis: Long) = try {
        callLock.tryLock(waitMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private fun checkCircuit() {
        val now = clock()
        if (now < circuitOpenUntil) throw CoreException(CoreFailureKind.UNAVAILABLE, "the core keeps crashing; try again later")
    }

    private fun event(text: String) {
        try {
            onEvent(text)
        } catch (_: Throwable) {
        }
    }

    private fun noteDeath() {
        val now = clock()
        event("core process died (death ${crashes.size + 1} in the window, ${restarts + 1} since start)")
        restarts++
        crashes.addLast(now)
        while (crashes.isNotEmpty() && now - crashes.first() > config.crashWindowMillis) crashes.removeFirst()
        if (crashes.size >= config.maxCrashes) {
            circuitOpenUntil = now + config.circuitOpenMillis
            crashes.clear()
        }
    }

    /** A live, initialised connection (starting and initialising the process if needed). Holds [callLock]. */
    private fun ensureReady(deadline: Long): CoreConnection {
        var conn = connection?.takeIf { it.isAlive }
        if (conn == null) {
            connection = null
            remoteInitDone = false
            val wait = deadline - clock()
            if (wait <= 0) throw CoreException(CoreFailureKind.TIMEOUT, "no time left to start the core")
            conn = try {
                transport.connect(wait)
            } catch (e: CoreDiedException) {
                throw e
            } catch (e: RuntimeException) {
                throw CoreException(CoreFailureKind.UNAVAILABLE, "cannot reach the core process", e)
            }
            connection = conn
        }
        if (!remoteInitDone) {
            val args = synchronized(argsLock) { initArgs } ?: throw CoreException(CoreFailureKind.INIT_FAILED, "init() was never called")
            val reply = exchange(conn, CoreProtocol.OP_INIT, CoreProtocol.encodeInit(args), deadline, allowInit = true)
            check(reply.isEmpty())
            remoteInitDone = true
            if (refreshWanted) exchange(conn, CoreProtocol.OP_REFRESH, ByteArray(0), deadline, allowInit = true)
        }
        return conn
    }

    /**
     * One request/reply on [conn] with the deadline enforced: the blocking call runs on a worker and, when time
     * runs out or this thread is interrupted, the process is killed so the worker is released. Returns the OK
     * payload, reassembled if it came in chunks. Error replies become [CoreException].
     */
    private fun exchange(conn: CoreConnection, op: Int, payload: ByteArray, deadline: Long, allowInit: Boolean = false): ByteArray {
        val reply = try { CoreProtocol.decodeReply(transact(conn, op, payload, deadline)) } catch (e: IOException) { throw CoreException(CoreFailureKind.INTERNAL, "bad reply", e) }
        return when (reply.kind) {
            CoreProtocol.KIND_OK -> reply.bytes
            CoreProtocol.KIND_CHUNKED -> {
                val h = try { CoreProtocol.decodeChunkHeader(reply.bytes) } catch (e: IOException) { throw CoreException(CoreFailureKind.INTERNAL, "bad chunk header", e) }
                val whole = ByteArray(h.total)
                System.arraycopy(h.first, 0, whole, 0, h.first.size)
                var got = h.first.size
                while (got < h.total) {
                    val piece = exchange(conn, CoreProtocol.OP_FETCH, CoreProtocol.encodeFetch(h.id, got), deadline)
                    if (piece.isEmpty() || got + piece.size > h.total) throw CoreException(CoreFailureKind.INTERNAL, "bad chunk")
                    System.arraycopy(piece, 0, whole, got, piece.size)
                    got += piece.size
                }
                whole
            }
            CoreProtocol.KIND_ERROR -> {
                val (kind, message) = try { CoreProtocol.decodeError(reply.bytes) } catch (e: IOException) { 0 to "" }
                throw when (kind) {
                    CoreProtocol.ERR_NOT_INITIALIZED -> {
                        // The host lost its state (a restart we did not see): initialise again and let the caller retry.
                        remoteInitDone = false
                        CoreDiedException("the core lost its state")
                    }
                    CoreProtocol.ERR_INIT_FAILED -> CoreException(CoreFailureKind.INIT_FAILED, message)
                    else -> CoreException(CoreFailureKind.INTERNAL, message)
                }
            }
            else -> throw CoreException(CoreFailureKind.INTERNAL, "unknown reply kind ${reply.kind}")
        }
    }

    private fun transact(conn: CoreConnection, op: Int, payload: ByteArray, deadline: Long): ByteArray {
        val remaining = deadline - clock()
        if (remaining <= 0) {
            killLocked(conn)
            throw CoreException(CoreFailureKind.TIMEOUT, "deadline passed")
        }
        val future = executor.submit<ByteArray> { conn.call(op, payload) }
        try {
            return future.get(remaining, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            killLocked(conn)
            future.cancel(true)
            throw CoreException(CoreFailureKind.TIMEOUT, "the core did not answer in time; it was restarted")
        } catch (e: InterruptedException) {
            killLocked(conn)
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw CoreException(CoreFailureKind.CANCELLED, "cancelled; the core was restarted", e)
        } catch (e: ExecutionException) {
            when (val c = e.cause) {
                is CoreDiedException -> throw c
                is RuntimeException -> throw CoreException(CoreFailureKind.INTERNAL, c.javaClass.simpleName, c)
                else -> throw CoreException(CoreFailureKind.INTERNAL, "transport failure", c)
            }
        }
    }

    private fun killLocked(conn: CoreConnection) {
        event("core process killed by the client (deadline or cancellation)")
        try {
            conn.kill()
        } catch (_: Exception) {
        }
        if (connection === conn) connection = null
        remoteInitDone = false
        restarts++
    }

    private fun dropConnectionLocked(kill: Boolean) {
        connection?.let { if (kill) runCatching { it.kill() } else runCatching { it.close() } }
        connection = null
        remoteInitDone = false
    }

    companion object {
        fun codeFor(kind: CoreFailureKind): Int = when (kind) {
            CoreFailureKind.CRASHED -> RouteCode.CORE_CRASHED
            CoreFailureKind.TIMEOUT, CoreFailureKind.CANCELLED -> RouteCode.CANCELLED // "took too long" in the UI
            CoreFailureKind.UNAVAILABLE -> RouteCode.CORE_UNAVAILABLE
            CoreFailureKind.INIT_FAILED, CoreFailureKind.INTERNAL -> RouteCode.CORE_INTERNAL
        }
    }
}
