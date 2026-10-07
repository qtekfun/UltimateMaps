package com.qtekfun.mapas.core.regions

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tiny local HTTP server for tests: serves [files] with `Range` support and answers [redirects] with a 302.
 * [hits] lists every requested path, so a test can prove that a host was (or was not) contacted.
 */
internal class LocalHost : AutoCloseable {
    val files = HashMap<String, ByteArray>()
    val redirects = HashMap<String, String>()
    val hits = CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port get() = server.address.port

    init {
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            hits += path
            val to = redirects[path]
            val data = files[path]
            when {
                to != null -> { ex.responseHeaders.add("Location", to); ex.sendResponseHeaders(302, -1) }
                data == null -> ex.sendResponseHeaders(404, -1)
                else -> {
                    val start = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                    if (start > 0) ex.responseHeaders.add("Content-Range", "bytes $start-${data.size - 1}/${data.size}")
                    ex.sendResponseHeaders(if (start > 0) 206 else 200, (data.size - start).toLong())
                    ex.responseBody.write(data, start, data.size - start)
                }
            }
            ex.close()
        }
        server.start()
    }

    /** `http://127.0.0.1:<port>`, or with [alias] `http://localhost:<port>`: another host name for the same server. */
    fun url(path: String, alias: Boolean = false) = "http://${if (alias) "localhost" else "127.0.0.1"}:$port$path"

    override fun close() = server.stop(0)
}

internal fun sha256Hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
