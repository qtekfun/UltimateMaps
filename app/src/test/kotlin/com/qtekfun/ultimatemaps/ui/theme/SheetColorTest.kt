package com.qtekfun.ultimatemaps.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class SheetColorTest {
    /** Map labels and the buttons behind the sheet must not show through card text (device test rc.6). */
    @Test fun theSheetSurfaceIsOpaqueInLightAndDark() {
        assertEquals(1f, LightMapasColors.sheet.alpha, 0f)
        assertEquals(1f, DarkMapasColors.sheet.alpha, 0f)
    }
}
