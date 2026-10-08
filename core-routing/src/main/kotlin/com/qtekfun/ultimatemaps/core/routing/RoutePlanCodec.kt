package com.qtekfun.ultimatemaps.core.routing

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.io.DataInput
import java.io.DataOutput
import java.io.IOException

/**
 * Compact binary form of a [RoutePlan], shared by the two places that must move a route out of memory: the
 * navigation state saved to disk (to resume after the system kills the process) and the isolated native core
 * (the route crosses the process boundary). Pure Kotlin on `java.io`, deterministic, versioned.
 *
 * Reading never trusts the input: every count is bounded before anything is allocated and a bad value raises an
 * [IOException], so a corrupt or truncated file or message can never cause an out-of-memory or an endless loop.
 */
object RoutePlanCodec {
    /** 2 adds the tunnel ranges after the stops; version 1 (older saved states) is still read, with no tunnels. */
    const val VERSION = 2
    const val MAX_POINTS = 4_000_000
    private const val MAX_ITEMS = 1_000_000
    private const val MAX_LANES = 64
    private const val MAX_TEXT = 4_096

    fun write(out: DataOutput, plan: RoutePlan) {
        out.writeByte(VERSION)
        out.writeDouble(plan.distanceMeters)
        out.writeDouble(plan.durationSeconds)
        out.writeInt(plan.geometry.size)
        for (p in plan.geometry) {
            out.writeDouble(p.lat)
            out.writeDouble(p.lon)
        }
        val g = plan.guidance
        out.writeInt(g.maneuvers.size)
        for (m in g.maneuvers) {
            out.writeInt(m.geometryIndex)
            out.writeByte(m.type.ordinal)
            writeText(out, m.streetName)
            out.writeInt(m.roundaboutExit ?: -1)
            out.writeByte(m.lanes.size)
            for (lane in m.lanes) {
                out.writeBoolean(lane.recommended)
                out.writeByte(lane.directions.size)
                for (d in lane.directions) out.writeByte(d.ordinal)
            }
        }
        out.writeInt(g.speedLimits.size)
        for (s in g.speedLimits) {
            out.writeInt(s.startIndex)
            out.writeInt(s.endIndex)
            out.writeInt(s.kmh ?: -1)
        }
        out.writeInt(g.stops.size)
        for (i in g.stops) out.writeInt(i)
        out.writeInt(g.tunnels.size)
        for (t in g.tunnels) {
            out.writeInt(t.startIndex)
            out.writeInt(t.endIndex)
        }
    }

    @Throws(IOException::class)
    fun read(input: DataInput): RoutePlan {
        val version = input.readUnsignedByte()
        if (version != 1 && version != VERSION) throw IOException("unsupported route version $version")
        val distance = input.readDouble()
        val duration = input.readDouble()
        val count = bounded(input.readInt(), MAX_POINTS)
        val geometry = ArrayList<LatLon>(count)
        repeat(count) {
            val lat = input.readDouble()
            val lon = input.readDouble()
            geometry += LatLon.ofOrNull(lat, lon) ?: throw IOException("point out of range")
        }
        val maneuvers = ArrayList<Maneuver>()
        repeat(bounded(input.readInt(), MAX_ITEMS)) {
            val index = input.readInt()
            val type = TurnType.entries.getOrNull(input.readUnsignedByte()) ?: throw IOException("bad turn type")
            val street = readText(input)
            val exit = input.readInt().takeIf { it >= 0 }
            val lanes = ArrayList<Lane>()
            repeat(bounded(input.readUnsignedByte(), MAX_LANES)) {
                val recommended = input.readBoolean()
                val dirs = LinkedHashSet<LaneDirection>()
                repeat(bounded(input.readUnsignedByte(), MAX_LANES)) {
                    dirs += LaneDirection.entries.getOrNull(input.readUnsignedByte()) ?: throw IOException("bad lane direction")
                }
                lanes += Lane(dirs, recommended)
            }
            maneuvers += Maneuver(index, type, street, exit, lanes)
        }
        val limits = ArrayList<SpeedLimit>()
        repeat(bounded(input.readInt(), MAX_ITEMS)) {
            limits += SpeedLimit(input.readInt(), input.readInt(), input.readInt().takeIf { it >= 0 })
        }
        val stops = ArrayList<Int>()
        repeat(bounded(input.readInt(), MAX_ITEMS)) { stops += input.readInt() }
        val tunnels = ArrayList<TunnelRange>()
        if (version >= 2) repeat(bounded(input.readInt(), MAX_ITEMS)) { tunnels += TunnelRange(input.readInt(), input.readInt()) }
        return RoutePlan(geometry, distance, duration, RouteGuidance(maneuvers, limits, stops, tunnels))
    }

    private fun bounded(n: Int, max: Int): Int {
        if (n < 0 || n > max) throw IOException("count $n out of bounds")
        return n
    }

    private fun writeText(out: DataOutput, text: String?) {
        if (text == null) {
            out.writeShort(0xFFFF)
            return
        }
        val bytes = text.toByteArray(Charsets.UTF_8).let { if (it.size > MAX_TEXT) it.copyOf(MAX_TEXT) else it }
        out.writeShort(bytes.size)
        out.write(bytes)
    }

    private fun readText(input: DataInput): String? {
        val n = input.readUnsignedShort()
        if (n == 0xFFFF) return null
        if (n > MAX_TEXT) throw IOException("text too long")
        val bytes = ByteArray(n)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
