package com.qtekfun.ultimatemaps.nativecomaps

/**
 * Switches for the long-route pipeline of the native core, used by the debug bench to A/B on a device without rebuilding
 * (see `docs/phase2/long-routes-perf.md`). The app never sets them: the default, [DEFAULT] = 0, is the stock behaviour.
 * The bit layout is the same as `um::PerfFlags` in `um_core.hpp`; keep both in sync.
 *
 * The text form is a comma separated list of tokens: `quiet`, `prune`, `cache`, `cand8` / `cand5` / `cand3`
 * (collect fewer leaps candidates than the stock 15), `tmo10` / `tmo5` / `tmo2` (cap the leaps search at 10 / 5 / 2 s
 * instead of 30 s), plus the presets `default` (nothing), `safe` (`quiet,prune,cache`: routes identical to the default)
 * and `fast` (`safe,cand5,tmo5`: faster, may pick a slightly worse route).
 */
object RoutePerfMode {
    const val DEFAULT = 0
    const val QUIET_LOG = 1 shl 0
    const val PRUNE_CANDIDATES = 1 shl 1
    const val PERSIST_GRAPHS = 1 shl 2
    const val CAND_SHIFT = 3
    const val TIMEOUT_SHIFT = 5

    private val tokens: Map<String, Int> = mapOf(
        "quiet" to QUIET_LOG,
        "prune" to PRUNE_CANDIDATES,
        "cache" to PERSIST_GRAPHS,
        "cand8" to (1 shl CAND_SHIFT),
        "cand5" to (2 shl CAND_SHIFT),
        "cand3" to (3 shl CAND_SHIFT),
        "tmo10" to (1 shl TIMEOUT_SHIFT),
        "tmo5" to (2 shl TIMEOUT_SHIFT),
        "tmo2" to (3 shl TIMEOUT_SHIFT),
    )

    private val presets: Map<String, String> = mapOf(
        "default" to "",
        "safe" to "quiet,prune,cache",
        "fast" to "quiet,prune,cache,cand5,tmo5",
    )

    private val fields = intArrayOf(3 shl CAND_SHIFT, 3 shl TIMEOUT_SHIFT)

    /** @throws IllegalArgumentException on an unknown token or on two different values of the same 2-bit field. */
    fun parse(text: String?): Int {
        if (text.isNullOrBlank()) return DEFAULT
        var flags = 0
        for (raw in text.split(',')) {
            val name = raw.trim().lowercase()
            if (name.isEmpty()) continue
            val value = presets[name]?.let { parse(it) } ?: tokens[name] ?: throw IllegalArgumentException("unknown perf token '$name'")
            for (field in fields) {
                val old = flags and field
                val new = value and field
                require(old == 0 || new == 0 || old == new) { "conflicting perf tokens for the same setting: '$name'" }
            }
            flags = flags or value
        }
        return flags
    }

    /** Readable form of [flags] for the bench log, e.g. `quiet,prune`; `default` for 0. */
    fun describe(flags: Int): String {
        if (flags == DEFAULT) return "default"
        return tokens.entries.filter { (_, v) -> matches(flags, v) }.joinToString(",") { it.key }
    }

    private fun matches(flags: Int, token: Int): Boolean {
        for (field in fields) {
            if ((token and field) != 0) return (flags and field) == (token and field)
        }
        return (flags and token) == token
    }
}
