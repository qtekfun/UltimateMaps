package com.qtekfun.ultimatemaps.core.net

/** In-memory [NetworkPolicy]: whitelist, offline mode and a bounded local connection log. Thread-safe. */
class DefaultNetworkPolicy(
    endpoints: List<AllowedEndpoint> = emptyList(),
    offlineMode: Boolean = false,
    private val maxLogEntries: Int = 200,
    private val clock: () -> Long = System::currentTimeMillis,
) : NetworkPolicy {

    private val lock = Any()
    private val endpoints = endpoints.toMutableList()
    private val log = ArrayDeque<ConnectionRecord>()

    init {
        require(maxLogEntries > 0) { "maxLogEntries must be positive" }
    }

    override var offlineMode: Boolean = offlineMode
        get() = synchronized(lock) { field }
        set(value) = synchronized(lock) { field = value }

    override fun authorize(host: String, purpose: ConnectionPurpose): NetworkDecision = synchronized(lock) {
        val h = host.trim().lowercase()
        val decision = decide(h, purpose)
        log.addFirst(ConnectionRecord(clock(), h, purpose, decision.isAllowed))
        while (log.size > maxLogEntries) log.removeLast()
        decision
    }

    private fun decide(host: String, purpose: ConnectionPurpose): NetworkDecision {
        if (offlineMode) return NetworkDecision.Denied(DenyReason.OFFLINE_MODE)
        val matching = endpoints.filter { it.purpose == purpose && hostMatches(it.host, host) }
        return when {
            matching.isEmpty() -> NetworkDecision.Denied(DenyReason.NOT_WHITELISTED)
            matching.any { it.enabled } -> NetworkDecision.Allowed
            else -> NetworkDecision.Denied(DenyReason.DISABLED_BY_USER)
        }
    }

    override fun possibleConnections(): List<AllowedEndpoint> = synchronized(lock) { endpoints.toList() }

    /**
     * Adds or replaces a whitelist entry at run time (e.g. the map server the user typed in). The caller is
     * responsible for the user having asked for it; the entry then appears in [possibleConnections].
     */
    fun addEndpoint(endpoint: AllowedEndpoint) {
        synchronized(lock) {
            val key = endpoint.host.trim().lowercase()
            endpoints.removeAll { it.host.lowercase() == key && it.purpose == endpoint.purpose }
            endpoints.add(endpoint.copy(host = key))
        }
    }

    /** Removes every entry for [host] (any purpose): it no longer is allowed nor listed. Unknown hosts are ignored. */
    fun removeEndpoint(host: String) {
        synchronized(lock) {
            val key = host.trim().lowercase()
            endpoints.removeAll { it.host.lowercase() == key }
        }
    }

    override fun setEndpointEnabled(host: String, purpose: ConnectionPurpose, enabled: Boolean) {
        synchronized(lock) {
            val key = host.trim().lowercase()
            val i = endpoints.indexOfFirst { it.host.lowercase() == key && it.purpose == purpose }
            require(i >= 0) { "unknown endpoint: $host / $purpose" }
            endpoints[i] = endpoints[i].copy(enabled = enabled)
        }
    }

    override fun recentConnections(): List<ConnectionRecord> = synchronized(lock) { log.toList() }

    override fun clearLog() = synchronized(lock) { log.clear() }

    private fun hostMatches(pattern: String, host: String): Boolean {
        val p = pattern.lowercase()
        return if (p.startsWith("*.")) host.endsWith(p.substring(1)) && host.length > p.length - 1 else host == p
    }
}
