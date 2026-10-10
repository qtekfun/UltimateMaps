package com.qtekfun.ultimatemaps.core.nav

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavDiagnosticsTest {
    @AfterTest fun reset() = NavDiagnostics.clear()

    @Test fun `nothing yet`() {
        NavDiagnostics.clear()
        assertEquals(listOf("No reroute since the app started"), NavDiagnostics.describe())
    }

    @Test fun `a reroute reports where the time went`() {
        NavDiagnostics.clear()
        NavDiagnostics.rerouteStarted(1_000)
        assertTrue("calculating" in NavDiagnostics.describe().single())
        NavDiagnostics.rerouteFinished(5_200, found = true)
        NavDiagnostics.routeAdopted(3, 5_210)
        NavDiagnostics.routePainted(3, 5_900, 40)
        val line = NavDiagnostics.describe().single()
        assertTrue("calculated in 4200 ms" in line, line)
        assertTrue("690 ms later" in line && "took 40 ms" in line, line)
    }

    @Test fun `only the last few are kept and a failed one says so`() {
        NavDiagnostics.clear()
        repeat(8) { NavDiagnostics.rerouteStarted(it * 1000L); NavDiagnostics.rerouteFinished(it * 1000L + 100, found = it != 7) }
        val lines = NavDiagnostics.describe()
        assertEquals(NavDiagnostics.KEEP, lines.size)
        assertTrue("(no route)" in lines.first(), "newest first: ${lines.first()}")
    }
}
