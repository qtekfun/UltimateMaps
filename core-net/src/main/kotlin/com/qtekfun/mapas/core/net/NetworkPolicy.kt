package com.qtekfun.mapas.core.net

/** Why the app would open a connection. Shown to the user in the list of possible connections (RF-12). */
enum class ConnectionPurpose { ONLINE_TILES, SHORT_LINK_RESOLVE, MAP_DOWNLOAD, SYNC_WEBDAV, OTHER }

/** One whitelist entry. [host] is exact (`tile.openstreetmap.org`) or a wildcard (`*.example.org`). */
data class AllowedEndpoint(
    val host: String,
    val purpose: ConnectionPurpose,
    /** Disabled entries are listed to the user but never allowed. Online sources start disabled. */
    val enabled: Boolean = false,
)

sealed class NetworkDecision {
    data object Allowed : NetworkDecision()
    data class Denied(val reason: DenyReason) : NetworkDecision()

    val isAllowed: Boolean get() = this is Allowed
}

enum class DenyReason { OFFLINE_MODE, NOT_WHITELISTED, DISABLED_BY_USER }

/**
 * A logged connection attempt. Deliberately holds only the host, purpose and outcome: never a URL,
 * path, query or any position, so the log can never leak a location.
 */
data class ConnectionRecord(
    val timestampMillis: Long,
    val host: String,
    val purpose: ConnectionPurpose,
    val allowed: Boolean,
)

/**
 * The single exit to the network. Every component must call [authorize] before connecting and
 * refrain from connecting when the answer is [NetworkDecision.Denied].
 */
interface NetworkPolicy {
    /** When true nothing is allowed, whatever the whitelist says. */
    var offlineMode: Boolean

    /** Asks permission to connect to [host] for [purpose]; the attempt is recorded locally. */
    fun authorize(host: String, purpose: ConnectionPurpose): NetworkDecision

    /** Every connection the app could make (the visible list of RF-12), enabled or not. */
    fun possibleConnections(): List<AllowedEndpoint>

    fun setEndpointEnabled(host: String, purpose: ConnectionPurpose, enabled: Boolean)

    /** Most recent attempts first. Local only; never leaves the device. */
    fun recentConnections(): List<ConnectionRecord>

    fun clearLog()
}
