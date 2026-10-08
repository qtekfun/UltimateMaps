package com.qtekfun.ultimatemaps.core.routing

import com.qtekfun.ultimatemaps.core.geo.LatLon
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExitSignTest {
    private fun exit(ref: String? = null, road: String? = null, place: String? = null, type: TurnType = TurnType.EXIT_RIGHT) =
        Maneuver(3, type, "Autovía del Este", exitRef = ref, towardRef = road, towardName = place)

    @Test fun `label joins the road and the place`() {
        assertEquals("A-2 · Alcalá de Henares", ExitSign.label(exit("23", "A-2", "Alcalá de Henares")))
        assertEquals("A-2", ExitSign.label(exit("23", "A-2")))
        assertEquals("Alcalá de Henares", ExitSign.label(exit(place = "Alcalá de Henares")))
    }

    @Test fun `no data means no sign and nothing is invented`() {
        val m = exit()
        assertNull(ExitSign.label(m))
        assertNull(ExitSign.ref(m))
        assertFalse(ExitSign.has(m))
        assertNull(ExitSign.spokenLabel(m, "and"))
        assertNull(ExitSign.label(exit(ref = "  ", road = "", place = "null")))
    }

    @Test fun `several roads and places are cut to two`() {
        val m = exit("5", "M-40;A-2;R-3", "Torrejón; Alcalá de Henares; Guadalajara")
        assertEquals("M-40 / A-2 · Torrejón, Alcalá de Henares", ExitSign.label(m))
        assertEquals("M 40 and A 2, Torrejón and Alcalá de Henares", ExitSign.spokenLabel(m, "and"))
    }

    @Test fun `exit numbers are spoken naturally`() {
        assertEquals("23", ExitSign.spokenExitRef(exit("23")))
        assertEquals("12 A", ExitSign.spokenExitRef(exit("12A")))
        assertEquals("A 2", ExitSign.spokenRef("A-2"))
    }

    @Test fun `roundabouts, the start and the arrival never show an exit sign`() {
        for (t in listOf(TurnType.ROUNDABOUT_ENTER, TurnType.ROUNDABOUT_LEAVE, TurnType.DEPART, TurnType.ARRIVE, TurnType.ARRIVE_LEFT, TurnType.ARRIVE_RIGHT)) {
            assertFalse(ExitSign.has(exit("23", "A-2", type = t)), "$t")
        }
    }

    @Test fun `isExit is true for exit types and for any turn that has an exit number`() {
        assertTrue(ExitSign.isExit(exit()))
        assertTrue(ExitSign.isExit(exit("23", type = TurnType.SLIGHT_RIGHT)))
        assertFalse(ExitSign.isExit(exit(road = "A-2", type = TurnType.SLIGHT_RIGHT)))
    }

    // ---- RoutePlanCodec (version 4 carries the exit fields)

    private fun plan(vararg ms: Maneuver) =
        RoutePlan(List(5) { LatLon(40.0 + it * 1e-3, -3.0) }, 100.0, 10.0, RouteGuidance(ms.toList()))

    private fun encode(p: RoutePlan) = ByteArrayOutputStream().also { RoutePlanCodec.write(DataOutputStream(it), p) }.toByteArray()
    private fun decode(b: ByteArray) = RoutePlanCodec.read(DataInputStream(b.inputStream()))

    @Test fun `codec round trip keeps the exit fields of the right maneuvers`() {
        val a = Maneuver(1, TurnType.LEFT, "Calle A")
        val b = exit("23", "A-2", "Alcalá de Henares")
        val c = Maneuver(4, TurnType.ARRIVE)
        val d = exit(road = "M-40")
        assertEquals(listOf(a, b, c, d), decode(encode(plan(a, b, c, d))).guidance.maneuvers)
    }

    @Test fun `a plan without exit data round trips unchanged`() {
        val p = plan(Maneuver(1, TurnType.LEFT, "Calle A"), Maneuver(4, TurnType.ARRIVE))
        assertEquals(p.guidance.maneuvers, decode(encode(p)).guidance.maneuvers)
    }

    @Test fun `a version 3 file is still read and has no exit data`() {
        val p = plan(Maneuver(1, TurnType.LEFT, "Calle A"))
        val bytes = encode(p)
        // Version 3 = the same bytes without the trailing exit count (4 bytes), with the version byte set to 3.
        val v3 = bytes.copyOf(bytes.size - 4).also { it[0] = 3 }
        val back = decode(v3)
        assertEquals(p.guidance.maneuvers, back.guidance.maneuvers)
        assertNull(back.guidance.maneuvers.single().exitRef)
    }

    @Test fun `exit data for a maneuver that does not exist is rejected`() {
        val bytes = encode(plan(exit("23", "A-2")))
        // Tail = count(4) + ordinal(4) + "23"(2+2) + "A-2"(2+3) + null(2) = 19 bytes: point the ordinal at maneuver 7.
        bytes[bytes.size - 19 + 7] = 7
        assertFailsWith<IOException> { decode(bytes) }
    }
}
