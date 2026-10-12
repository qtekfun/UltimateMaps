package com.qtekfun.ultimatemaps.nav

import com.qtekfun.ultimatemaps.core.map.CameraPadding
import kotlin.test.Test
import kotlin.test.assertEquals

/** The follow camera centres in the part of the map a side column or the bottom half does not cover. */
class NavCameraCoveredTest {
    @Test fun `without covered parts the plan is unchanged`() {
        val cam = NavCamera()
        val plain = cam.target(navState(), mode3d = true, screenHeightPx = 2400)
        assertEquals(plain, cam.target(navState(), mode3d = true, screenHeightPx = 2400, covered = CameraPadding.NONE))
    }

    @Test fun `a side column pushes the centre to the free side`() {
        val cam = NavCamera()
        val plain = cam.target(navState(), mode3d = true, screenHeightPx = 2400)
        val covered = cam.target(navState(), mode3d = true, screenHeightPx = 2400, covered = CameraPadding(left = 1100))
        assertEquals(1100, covered.padding.left)
        assertEquals(plain.padding.top, covered.padding.top)
        assertEquals(plain.zoom, covered.zoom)
    }

    @Test fun `the bottom half in tabletop moves the marker inside the top half`() {
        val cam = NavCamera()
        // 1840 px high window, 920 px bottom pane: the marker position is planned on the 920 px that are free.
        val covered = cam.target(navState(), mode3d = true, screenHeightPx = 1840, covered = CameraPadding(bottom = 920))
        val freeOnly = cam.target(navState(), mode3d = true, screenHeightPx = 920)
        assertEquals(920, covered.padding.bottom)
        assertEquals(freeOnly.padding.top, covered.padding.top)
    }
}
