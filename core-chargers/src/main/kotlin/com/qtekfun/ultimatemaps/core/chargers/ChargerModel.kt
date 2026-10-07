package com.qtekfun.ultimatemaps.core.chargers

import com.qtekfun.ultimatemaps.core.geo.LatLon

/** The kind of plug of a charging point. [code] is the value stored in the data file (see docs/phase2/ev-chargers-data.md). */
enum class SocketType(val code: Int) {
    TYPE2(1), CCS(2), CHADEMO(3), SCHUKO(4), TYPE1(5), TESLA(6), OTHER(7);

    companion object {
        /** The plugs the Settings filter offers. Anything else is only shown while no plug filter is active. */
        val FILTERABLE: Set<SocketType> = setOf(TYPE2, CCS, CHADEMO, SCHUKO)

        fun ofCode(code: Int): SocketType = entries.firstOrNull { it.code == code } ?: OTHER
    }
}

/** One kind of plug at a station. [count] is 0 when the number is unknown; [powerKw] is null when OSM gives no output. */
data class ChargerSocket(val type: SocketType, val count: Int, val powerKw: Double?)

enum class ChargerFee { UNKNOWN, FREE, PAID }

enum class ChargerAccess { UNKNOWN, PUBLIC, CUSTOMERS, PRIVATE }

/** How a driver starts a session, as OpenStreetMap `authentication:*` says (bit flags in the data file). */
object ChargerAuth {
    const val NONE = 1
    const val APP = 2
    const val CARD = 4
    const val NFC = 8
}

/** Source of the records (bit flags in the data file). */
object ChargerSources {
    const val OSM = 1
}

/**
 * A charging station. Every field except the position can be unknown (blank, 0, null, empty): OpenStreetMap
 * does not tag everything everywhere, and the card says "unknown" rather than guessing.
 */
data class Charger(
    val id: String,
    val location: LatLon,
    val operator: String,
    val network: String,
    val name: String,
    val capacity: Int,
    val sockets: List<ChargerSocket>,
    val fee: ChargerFee,
    val access: ChargerAccess,
    val authMask: Int,
    val openingHours: String,
) {
    /** Largest known output of any plug in kW, or null when no plug has one. */
    val maxPowerKw: Double? get() = sockets.mapNotNull { it.powerKw }.maxOrNull()

    /** The name to show first: the operator, else the network, else the station's own name; blank when none. */
    val title: String get() = operator.ifBlank { network.ifBlank { name } }

    /** Marks the pin as a fast (high power) charger. */
    val isFast: Boolean get() = (maxPowerKw ?: 0.0) >= FAST_KW

    companion object {
        /** From this output up a station is drawn as a fast charger. */
        const val FAST_KW = 50.0
    }
}

/** The content of one charger data file. */
data class ChargerDataset(
    val generatedAtEpochSeconds: Long,
    val sourceFlags: Int,
    val chargers: List<Charger>,
) {
    companion object {
        val EMPTY = ChargerDataset(0L, 0, emptyList())
    }
}
