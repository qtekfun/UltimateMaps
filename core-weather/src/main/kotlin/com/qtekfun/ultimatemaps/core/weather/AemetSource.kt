package com.qtekfun.ultimatemaps.core.weather

import com.qtekfun.ultimatemaps.core.cameras.BoundedHttp
import com.qtekfun.ultimatemaps.core.cameras.DownloadException
import com.qtekfun.ultimatemaps.core.cameras.DownloadFailure
import com.qtekfun.ultimatemaps.core.net.NetworkPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URI

enum class WeatherFailure { OFFLINE, NOT_ALLOWED, NETWORK, TIMEOUT, SERVER, TOO_LARGE, INVALID, UNAUTHORIZED, RATE_LIMITED }

class AemetException(val failure: WeatherFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

/** Fetches the national bundle of the last issued warnings. Blocking. The implementation sends nothing about the user but the key. */
fun interface AemetSource {
    /** The CAP XML documents of the whole of Spain; an empty list means "no warnings". */
    @Throws(AemetException::class)
    fun fetchNational(apiKey: String): List<ByteArray>
}

/** Where AEMET OpenData lives (checked 2026-10-08 against AEMET's OpenAPI document: server `https://opendata.aemet.es/opendata`). */
object AemetEndpoints {
    const val HOST = "opendata.aemet.es"
    const val BASE = "https://$HOST/opendata/api"

    /** Last issued warnings for all of Spain (`esp`). One request for everyone: the area is not a parameter of the user. */
    const val NATIONAL_PATH = "/avisos_cap/ultimoelaborado/area/esp"

    /** Shown wherever a warning appears. AEMET's legal notice asks for "Fuente: AEMET" (value-added) or "(c) AEMET". */
    const val ATTRIBUTION = "Fuente: AEMET"
    const val KEY_REQUEST_URL = "https://opendata.aemet.es/centrodedescargas/altaUsuario"
}

/**
 * AEMET OpenData in its two steps: the first request (with the key in the `api_key` HEADER, never in a URL, so it cannot reach a
 * log or a redirect) answers a small JSON with `estado` and a `datos` URL; the second request fetches that URL (same host, no
 * key) and returns the archive. Both go through [policy] under [WEATHER_ALERTS_PURPOSE]. [baseUrl] and [allowInsecure] exist
 * for tests only.
 */
class HttpAemetSource(
    policy: NetworkPolicy,
    private val baseUrl: String = AemetEndpoints.BASE,
    allowInsecure: Boolean = false,
    private val http: BoundedHttp = BoundedHttp(
        policy, WEATHER_ALERTS_PURPOSE, allowInsecure = allowInsecure, maxBytes = MAX_BYTES, userAgent = GENERIC_USER_AGENT,
    ),
) : AemetSource {
    override fun fetchNational(apiKey: String): List<ByteArray> {
        val first = baseUrl + AemetEndpoints.NATIONAL_PATH
        val envelope = try {
            http.get(first, "application/json", headers = mapOf("api_key" to apiKey)) { String(it.readBytes(), Charsets.UTF_8) }
        } catch (e: DownloadException) {
            if (e.httpStatus == 404) return emptyList() // AEMET answers 404 "no data" when nothing is in force
            throw map(e)
        }
        val obj = parseObject(envelope)
        val estado = obj["estado"]?.jsonPrimitive?.intOrNull
        when (estado) {
            200 -> Unit
            404 -> return emptyList()
            401, 403 -> throw AemetException(WeatherFailure.UNAUTHORIZED, "AEMET refused the key")
            429 -> throw AemetException(WeatherFailure.RATE_LIMITED, "AEMET rate limit")
            else -> throw AemetException(WeatherFailure.INVALID, "unexpected estado $estado")
        }
        val datos = obj["datos"]?.jsonPrimitive?.contentOrNull ?: throw AemetException(WeatherFailure.INVALID, "no datos URL")
        val expectedHost = runCatching { URI.create(first).host?.lowercase() }.getOrNull()
        val host = runCatching { URI.create(datos).host?.lowercase() }.getOrNull()
        if (host == null || host != expectedHost) throw AemetException(WeatherFailure.INVALID, "datos URL points to another host")
        val bytes = try {
            http.get(datos, "*/*") { it.readBytes() }
        } catch (e: DownloadException) {
            if (e.httpStatus == 404) return emptyList()
            throw map(e)
        }
        return try {
            CapBundle.documents(bytes)
        } catch (e: IOException) {
            throw AemetException(WeatherFailure.INVALID, e.message ?: "bad archive", e)
        }
    }

    private fun parseObject(text: String): JsonObject = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        throw AemetException(WeatherFailure.INVALID, "answer is not the expected JSON", e)
    }

    private fun map(e: DownloadException): AemetException {
        val failure = when {
            e.httpStatus == 401 || e.httpStatus == 403 -> WeatherFailure.UNAUTHORIZED
            e.httpStatus == 429 -> WeatherFailure.RATE_LIMITED
            else -> when (e.failure) {
                DownloadFailure.OFFLINE_MODE -> WeatherFailure.OFFLINE
                DownloadFailure.NOT_ALLOWED, DownloadFailure.NO_CATALOG -> WeatherFailure.NOT_ALLOWED
                DownloadFailure.NETWORK -> WeatherFailure.NETWORK
                DownloadFailure.TIMEOUT -> WeatherFailure.TIMEOUT
                DownloadFailure.SERVER -> WeatherFailure.SERVER
                DownloadFailure.TOO_LARGE -> WeatherFailure.TOO_LARGE
                DownloadFailure.INVALID_DATA -> WeatherFailure.INVALID
            }
        }
        // The message never carries the key (it is only in a header), but keep it generic anyway.
        return AemetException(failure, "AEMET request failed: ${e.failure}")
    }

    companion object {
        const val MAX_BYTES = 24L shl 20

        /** Looks like an ordinary client: nothing about the app or its version. */
        const val GENERIC_USER_AGENT = "Mozilla/5.0 (Android) AppleWebKit/537.36"
    }
}
