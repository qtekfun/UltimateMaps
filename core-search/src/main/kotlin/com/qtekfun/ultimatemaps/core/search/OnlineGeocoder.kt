package com.qtekfun.ultimatemaps.core.search

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.util.Locale
import kotlin.math.round

/** What an online lookup gave. The fallback never throws: every failure is one of these. */
sealed interface OnlineOutcome {
    /** The server answered; [results] may be empty (nothing there either). Every result has `online = true`. */
    data class Found(val results: List<SearchResult>) : OnlineOutcome

    /** The switch is off or this build has no online geocoder. Nothing was sent. */
    data object Disabled : OnlineOutcome

    /** Offline mode is on (or the connection is not allowed). Nothing was sent. */
    data object Offline : OnlineOutcome

    /** The request failed or the answer could not be read. */
    data object Failed : OnlineOutcome
}

/**
 * Optional online fallback of the offline search (the `play` flavor, off by default). It is asked only after the offline search
 * found nothing, and the only data that may leave the phone is the typed text and a coarse bias point
 * ([OnlineSearch.roundBias]); never the live position with precision. The `foss` flavor has [NoOnlineGeocoder].
 */
interface OnlineGeocoder {
    /** Cheap: true when a lookup may be tried now (the switch is on and offline mode is not). Read on every offline miss. */
    val enabled: Boolean

    /** Looks [query] up around [near] (the map centre; the implementation rounds it before it is sent). */
    suspend fun search(query: String, near: LatLon?, limit: Int): OnlineOutcome
}

/** Never connects. */
object NoOnlineGeocoder : OnlineGeocoder {
    override val enabled: Boolean get() = false
    override suspend fun search(query: String, near: LatLon?, limit: Int): OnlineOutcome = OnlineOutcome.Disabled
}

/** Where the choice is kept. The app backs it with SharedPreferences. */
interface OnlineSearchSettingsStore {
    /** The switch. Off by default. */
    var enabled: Boolean

    /** The geocoder's address as typed by the owner; blank means [OnlineSearch.DEFAULT_PHOTON_URL]. */
    var serverUrl: String
}

class InMemoryOnlineSearchSettings(override var enabled: Boolean = false, override var serverUrl: String = "") : OnlineSearchSettingsStore

/** Constants and pure helpers of the online fallback. */
object OnlineSearch {
    /** The public Photon instance of komoot (OpenStreetMap data, fair-use policy: at most one request a second). */
    const val DEFAULT_PHOTON_URL = "https://photon.komoot.io"

    /** At most one request a second to the public instance. */
    const val MIN_INTERVAL_MS = 1_000L

    /** Texts shorter than this are never sent. */
    const val MIN_QUERY_CHARS = 3
    const val MAX_QUERY_CHARS = 200

    /** The address in use: the stored one when it is an https URL, else the default. */
    fun effectiveUrl(stored: String): String {
        val t = stored.trim()
        return if (isValidUrl(t)) t.trimEnd('/') else DEFAULT_PHOTON_URL
    }

    fun isValidUrl(text: String): Boolean = runCatching {
        val u = java.net.URI(text.trim())
        u.scheme.equals("https", ignoreCase = true) && !u.host.isNullOrEmpty() && u.userInfo == null
    }.getOrDefault(false)

    /** The host the app must allow in the connection list for [stored], or null when it is not usable. */
    fun host(stored: String): String? = runCatching { java.net.URI(effectiveUrl(stored)).host?.lowercase() }.getOrNull()

    /** The map centre as it is sent: each coordinate rounded to 0.01 degree (about 1 km). */
    fun roundBias(p: LatLon): LatLon = LatLon(round(p.lat * 100.0) / 100.0, round(p.lon * 100.0) / 100.0)

    /**
     * The Photon `lang` for the owner's place-language choice, or null to leave it out (the local names). The public instance
     * only knows `default`, `en`, `de`, `fr` and `it` and answers an error for anything else, so Spanish is left out.
     */
    fun photonLanguage(pref: PlaceLanguagePref, appLocale: Locale): String? {
        return when (pref) {
            PlaceLanguagePref.LOCAL, PlaceLanguagePref.ES -> null
            PlaceLanguagePref.EN -> "en"
            PlaceLanguagePref.AUTO -> appLocale.language.lowercase().takeIf { it in PHOTON_LANGUAGES }
        }
    }

    private val PHOTON_LANGUAGES = setOf("en", "de", "fr", "it")
}
