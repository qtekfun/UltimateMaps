package com.qtekfun.mapas.core.nav

import com.qtekfun.mapas.core.routing.Lane
import com.qtekfun.mapas.core.routing.LaneDirection
import com.qtekfun.mapas.core.routing.Maneuver
import com.qtekfun.mapas.core.routing.RouteGuidance
import com.qtekfun.mapas.core.routing.RoutePlan
import com.qtekfun.mapas.core.routing.RoutePlanCodec
import com.qtekfun.mapas.core.routing.SpeedLimit
import com.qtekfun.mapas.core.routing.TurnType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavStateStoreTest {
    private fun rich(): RoutePlan {
        val b = RouteBuilder().lineTo(0.0, 500.0)
        val m = listOf(
            Maneuver(0, TurnType.DEPART),
            Maneuver(b.lastIndex, TurnType.ROUNDABOUT_ENTER, "Calle ñandú", 2, listOf(Lane(setOf(LaneDirection.THROUGH, LaneDirection.RIGHT), true), Lane(setOf(LaneDirection.LEFT), false))),
        )
        return RoutePlan(b.points.toList(), 500.0, 60.0, RouteGuidance(m, listOf(SpeedLimit(0, 5, 50), SpeedLimit(5, 9, null)), listOf(10)))
    }

    private fun tmp(): File = File.createTempFile("nav", ".bin").also { it.delete() }.also { it.deleteOnExit() }

    @Test fun codecRoundTripsEverything() {
        val plan = rich()
        val bytes = ByteArrayOutputStream().also { RoutePlanCodec.write(DataOutputStream(it), plan) }.toByteArray()
        assertEquals(plan, RoutePlanCodec.read(DataInputStream(ByteArrayInputStream(bytes))))
    }

    @Test fun corruptedOrTruncatedBytesOnlyEverRaiseIOException() {
        val bytes = ByteArrayOutputStream().also { RoutePlanCodec.write(DataOutputStream(it), rich()) }.toByteArray()
        val random = Random(3)
        repeat(400) { n ->
            val copy = if (n % 2 == 0) bytes.copyOf(random.nextInt(bytes.size)) else bytes.clone().also { b -> repeat(1 + random.nextInt(6)) { b[random.nextInt(b.size)] = random.nextInt().toByte() } }
            try {
                RoutePlanCodec.read(DataInputStream(ByteArrayInputStream(copy)))
            } catch (_: IOException) {
            }
        }
    }

    @Test fun savesAndLoadsAndClears() {
        val f = tmp()
        val store = NavStateStore(f, clock = { 1_000L })
        assertNull(store.load())
        assertTrue(store.save(rich(), 123.5))
        val loaded = assertNotNull(store.load())
        assertEquals(rich(), loaded.plan)
        assertEquals(123.5, loaded.progressMeters)
        store.clear()
        assertNull(store.load())
        assertFalse(f.exists())
    }

    @Test fun staleStateExpiresAndIsDeleted() {
        val f = tmp()
        var now = 0L
        val store = NavStateStore(f, { now }, maxAgeMillis = 10_000)
        store.save(rich(), 10.0)
        now = 9_000
        assertNotNull(store.load())
        now = 11_000
        assertNull(store.load())
        assertFalse(f.exists())
    }

    @Test fun garbageIsDiscardedNotThrown() {
        val f = tmp()
        val store = NavStateStore(f)
        f.writeBytes(ByteArray(5000) { it.toByte() })
        assertNull(store.load())
        assertFalse(f.exists())
        store.save(rich(), 1.0)
        f.writeBytes(f.readBytes().copyOf(40)) // truncated
        assertNull(store.load())
    }

    @Test fun aFailedWriteKeepsThePreviousFile() {
        val f = tmp()
        val store = NavStateStore(f, clock = { 5L })
        store.save(rich(), 7.0)
        // A route no codec can write (index count mismatch is fine; make the write fail with a directory in the way).
        val blocked = File(f.absolutePath + ".tmp")
        blocked.mkdirs()
        File(blocked, "x").writeText("x")
        assertFalse(store.save(rich(), 99.0))
        assertEquals(7.0, store.load()!!.progressMeters)
        blocked.deleteRecursively()
    }
}
