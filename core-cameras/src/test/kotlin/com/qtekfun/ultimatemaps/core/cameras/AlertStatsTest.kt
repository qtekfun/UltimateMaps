package com.qtekfun.ultimatemaps.core.cameras

import kotlin.test.Test
import kotlin.test.assertTrue

/** The counters behind the diagnostics lines: they must say why alerts did not go off. */
class AlertStatsTest {
    private val allOn = CameraSettings(fixedEnabled = true)
    private val perMeter = 1.0 / 111_320.0

    private fun camera(northMeters: Double) = AlertTarget("c", "c", AlertCategory.FIXED_CAMERA, 40.0 + northMeters * perMeter, -3.0, 0, AxisSense.BOTH, 50, 120)

    @Test
    fun `a fix too slow, one without direction and one that finds a camera are told apart`() {
        val stats = AlertStats()
        val alerts = mutableListOf<AlertEvent>()
        val w = AlertWarner(listOf(TargetGrid(listOf(camera(600.0)))), { allOn }, stats) { alerts += it }
        w.onFreeFix(40.0, -3.0, 0f, 0.5f, 1_000) // walking
        w.onFreeFix(40.0, -3.0, null, 20f, 2_000) // no bearing and nothing to derive it from yet
        w.onFreeFix(40.0, -3.0, 0f, 20f, 10_000) // driving north at 72 km/h, 600 m from the camera
        val text = stats.describe(12_000).joinToString("\n")
        assertTrue("1 too slow" in text, text)
        assertTrue("1 no direction" in text, text)
        assertTrue("Targets ahead at the last fix: 1" in text, text)
        assertTrue("Alerts raised: 1" in text, text)
        assertTrue("free driving" in text && "with bearing" in text && "72 km/h" in text, text)
    }

    @Test
    fun `with every switch off the fixes are counted as skipped for that reason`() {
        val stats = AlertStats()
        val w = AlertWarner(listOf(TargetGrid(listOf(camera(600.0)))), { CameraSettings() }, stats) { }
        w.onFreeFix(40.0, -3.0, 0f, 20f, 1_000)
        assertTrue("1 everything off" in stats.describe(2_000).joinToString("\n"))
    }

    @Test
    fun `nothing seen yet reads as never`() {
        val text = AlertStats().describe(5_000).joinToString("\n")
        assertTrue("last never" in text && "Free-driving feed: not evaluated yet" in text, text)
    }
}
