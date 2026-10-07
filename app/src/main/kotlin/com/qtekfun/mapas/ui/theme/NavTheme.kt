package com.qtekfun.mapas.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Colours and sizes of the navigation screen, on top of the base design system ([Mapas]). The banner is dark in
 * every theme (it sits over a bright map and must be read at a glance); glove mode (RF-06) pushes everything to
 * the highest contrast (pure black and white, a yellow highlight) and to touch targets of at least 56 dp.
 */
@Immutable
data class NavColors(
    val banner: Color,
    val onBanner: Color,
    val onBannerSecondary: Color,
    val laneIdle: Color,
    val onLaneIdle: Color,
    val laneRecommended: Color,
    val onLaneRecommended: Color,
    val panel: Color,
    val onPanel: Color,
    val onPanelSecondary: Color,
    val stopButton: Color,
    val onStopButton: Color,
    val statusInfo: Color,
    val statusWarning: Color,
    val statusDanger: Color,
    val onStatus: Color,
    val limitRing: Color,
    val limitFace: Color,
    val onLimitFace: Color,
    val overLimit: Color,
    val onOverLimit: Color,
)

@Immutable
data class NavDimens(
    /** Smallest touch target of the screen: 48 dp, 56 dp in glove mode. */
    val touchTarget: Dp,
    val bannerIcon: Dp,
    val laneBox: Dp,
    val speedSign: Dp,
)

fun navColors(dark: Boolean, glove: Boolean): NavColors =
    if (glove) {
        NavColors(
            banner = Color(0xFF000000), onBanner = Color(0xFFFFFFFF), onBannerSecondary = Color(0xFFFFFFFF),
            laneIdle = Color(0xFF3A3A3A), onLaneIdle = Color(0xFFFFFFFF),
            laneRecommended = Color(0xFFFFD60A), onLaneRecommended = Color(0xFF000000),
            panel = Color(0xFF000000), onPanel = Color(0xFFFFFFFF), onPanelSecondary = Color(0xFFFFFFFF),
            stopButton = Color(0xFFFF3B30), onStopButton = Color(0xFF000000),
            statusInfo = Color(0xFFFFFFFF), statusWarning = Color(0xFFFFD60A), statusDanger = Color(0xFFFF3B30), onStatus = Color(0xFF000000),
            limitRing = Color(0xFFFF0000), limitFace = Color(0xFFFFFFFF), onLimitFace = Color(0xFF000000),
            overLimit = Color(0xFFFF0000), onOverLimit = Color(0xFFFFFFFF),
        )
    } else {
        NavColors(
            banner = Color(0xFF1C1C1E), onBanner = Color(0xFFFFFFFF), onBannerSecondary = Color(0xFFD1D1D6),
            laneIdle = Color(0xFF3A3A3C), onLaneIdle = Color(0xFFAEAEB2),
            laneRecommended = Color(0xFFFFFFFF), onLaneRecommended = Color(0xFF1C1C1E),
            panel = if (dark) Color(0xF21C1C1E) else Color(0xF2FFFFFF),
            onPanel = if (dark) Color(0xFFFFFFFF) else Color(0xFF000000),
            onPanelSecondary = if (dark) Color(0x99EBEBF5) else Color(0x993C3C43),
            stopButton = Color(0xFFFF3B30), onStopButton = Color(0xFFFFFFFF),
            statusInfo = Color(0xFF0A84FF), statusWarning = Color(0xFFFF9F0A), statusDanger = Color(0xFFFF453A), onStatus = Color(0xFFFFFFFF),
            limitRing = Color(0xFFE5231B), limitFace = Color(0xFFFFFFFF), onLimitFace = Color(0xFF000000),
            overLimit = Color(0xFFE5231B), onOverLimit = Color(0xFFFFFFFF),
        )
    }

fun navDimens(glove: Boolean): NavDimens =
    if (glove) NavDimens(touchTarget = 56.dp, bannerIcon = 72.dp, laneBox = 52.dp, speedSign = 76.dp)
    else NavDimens(touchTarget = 48.dp, bannerIcon = 60.dp, laneBox = 40.dp, speedSign = 64.dp)

private val LocalNavColors = staticCompositionLocalOf { navColors(dark = false, glove = false) }
private val LocalNavDimens = staticCompositionLocalOf { navDimens(glove = false) }

object NavTheme {
    val colors: NavColors @Composable @ReadOnlyComposable get() = LocalNavColors.current
    val dimens: NavDimens @Composable @ReadOnlyComposable get() = LocalNavDimens.current
}

/** The navigation theme: night follows [dark] (the system's dark mode), [glove] is glove mode. */
@Composable
fun NavigationTheme(dark: Boolean, glove: Boolean, content: @Composable () -> Unit) {
    MapasTheme(darkTheme = dark, gloveMode = glove) {
        CompositionLocalProvider(
            LocalNavColors provides navColors(dark, glove),
            LocalNavDimens provides navDimens(glove),
            content = content,
        )
    }
}
