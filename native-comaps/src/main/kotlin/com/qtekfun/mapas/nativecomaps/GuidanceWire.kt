package com.qtekfun.mapas.nativecomaps

import com.qtekfun.mapas.core.routing.Lane
import com.qtekfun.mapas.core.routing.LaneDirection
import com.qtekfun.mapas.core.routing.Maneuver
import com.qtekfun.mapas.core.routing.RouteGuidance
import com.qtekfun.mapas.core.routing.SpeedLimit
import com.qtekfun.mapas.core.routing.TurnType

/**
 * Decodificador del guiado que devuelve `um::Core::Route(withGuidance = true)` (ver `um_core.hpp`).
 * Todo son enteros dentro de un `DoubleArray`:
 * `[version, nManiobras, nLimites, maniobras..., limites...]`
 * - maniobra: `indiceGeometria, giro, salidaRotonda(-1), indiceNombre(-1), nCarriles, (mascaraLaneWay, recomendado)*`
 * - limite: `desde, hasta, kmh(-1 = sin dato)`
 * Un array vacio significa «sin guiado». Cualquier incoherencia lanza [IllegalArgumentException] (nunca se lee fuera).
 */
internal object GuidanceWire {
    const val VERSION = 1
    private const val MAX_LANES = 16

    fun decode(raw: DoubleArray, names: Array<String>, geometrySize: Int): RouteGuidance {
        if (raw.isEmpty()) return RouteGuidance.EMPTY
        val r = Reader(raw)
        val version = r.int("version")
        require(version == VERSION) { "version de guiado desconocida: $version" }
        val nMan = r.count("nManiobras", raw.size)
        val nLim = r.count("nLimites", raw.size)

        val maneuvers = ArrayList<Maneuver>(nMan)
        repeat(nMan) {
            val index = r.int("indiceGeometria")
            require(index in 0 until geometrySize) { "indice de geometria fuera de rango: $index (puntos: $geometrySize)" }
            val type = turnOf(r.int("giro"))
            val exit = r.int("salidaRotonda")
            require(exit >= -1) { "salida de rotonda invalida: $exit" }
            val nameIdx = r.int("indiceNombre")
            require(nameIdx in -1 until names.size) { "indice de nombre fuera de rango: $nameIdx (nombres: ${names.size})" }
            val nLanes = r.count("nCarriles", MAX_LANES)
            val lanes = List(nLanes) {
                val mask = r.int("mascara")
                val rec = r.int("recomendado")
                require(rec == 0 || rec == 1) { "marca de carril recomendado invalida: $rec" }
                Lane(directionsOf(mask), rec == 1)
            }
            val isRoundabout = type == TurnType.ROUNDABOUT_ENTER || type == TurnType.ROUNDABOUT_LEAVE
            maneuvers += Maneuver(
                geometryIndex = index,
                type = type,
                streetName = if (nameIdx >= 0) names[nameIdx].ifBlank { null } else null,
                roundaboutExit = if (exit >= 0 && isRoundabout) exit else null,
                lanes = lanes,
            )
        }

        val limits = ArrayList<SpeedLimit>(nLim)
        repeat(nLim) {
            val start = r.int("desde")
            val end = r.int("hasta")
            val kmh = r.int("kmh")
            require(start in 0 until geometrySize && end in start until geometrySize) {
                "tramo de limite invalido: $start..$end (puntos: $geometrySize)"
            }
            require(kmh == -1 || kmh in 1..400) { "limite de velocidad invalido: $kmh" }
            limits += SpeedLimit(start, end, if (kmh == -1) null else kmh)
        }
        require(r.exhausted()) { "guiado con datos sobrantes: ${raw.size - r.pos}" }
        return RouteGuidance(maneuvers, limits)
    }

    private class Reader(private val a: DoubleArray) {
        var pos = 0
            private set

        fun exhausted() = pos == a.size

        fun int(what: String): Int {
            require(pos < a.size) { "guiado truncado leyendo $what (posicion $pos de ${a.size})" }
            val v = a[pos++]
            require(v == Math.rint(v) && v >= Int.MIN_VALUE && v <= Int.MAX_VALUE) { "$what no es un entero: $v" }
            return v.toInt()
        }

        fun count(what: String, max: Int): Int {
            val n = int(what)
            require(n in 0..max) { "$what fuera de rango: $n" }
            return n
        }
    }

    /** Codigos de `um::WireTurn`. Explicitos a proposito: no dependen del orden del enum de Kotlin. */
    private fun turnOf(code: Int): TurnType = when (code) {
        0 -> TurnType.DEPART
        1 -> TurnType.STRAIGHT
        2 -> TurnType.SLIGHT_RIGHT
        3 -> TurnType.RIGHT
        4 -> TurnType.SHARP_RIGHT
        5 -> TurnType.SLIGHT_LEFT
        6 -> TurnType.LEFT
        7 -> TurnType.SHARP_LEFT
        8 -> TurnType.U_TURN_LEFT
        9 -> TurnType.U_TURN_RIGHT
        10 -> TurnType.ROUNDABOUT_ENTER
        11 -> TurnType.ROUNDABOUT_LEAVE
        12 -> TurnType.EXIT_LEFT
        13 -> TurnType.EXIT_RIGHT
        14 -> TurnType.MERGE
        15 -> TurnType.ARRIVE
        16 -> TurnType.ARRIVE_LEFT
        17 -> TurnType.ARRIVE_RIGHT
        else -> throw IllegalArgumentException("giro desconocido: $code")
    }

    /** Bit i de la mascara = `routing::turns::lanes::LaneWay` de valor i. El bit 0 (`None`) no aporta direccion. */
    private val laneBits = mapOf(
        1 to LaneDirection.U_TURN, // ReverseLeft
        2 to LaneDirection.SHARP_LEFT,
        3 to LaneDirection.LEFT,
        4 to LaneDirection.MERGE_LEFT,
        5 to LaneDirection.SLIGHT_LEFT,
        6 to LaneDirection.THROUGH,
        7 to LaneDirection.SLIGHT_RIGHT,
        8 to LaneDirection.MERGE_RIGHT,
        9 to LaneDirection.RIGHT,
        10 to LaneDirection.SHARP_RIGHT,
        11 to LaneDirection.U_TURN, // ReverseRight
    )

    private fun directionsOf(mask: Int): Set<LaneDirection> {
        require(mask >= 0 && mask < (1 shl 12)) { "mascara de carril invalida: $mask" }
        return laneBits.filterKeys { mask and (1 shl it) != 0 }.values.toSet()
    }
}
