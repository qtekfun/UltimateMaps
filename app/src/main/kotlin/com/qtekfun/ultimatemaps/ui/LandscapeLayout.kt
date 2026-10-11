@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.qtekfun.ultimatemaps.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * One place for the landscape rules (full-width, portrait-sized banners and sheets used to pile up on a 360 dp high
 * screen). The rule is geometric, not a device class: whenever the window is wider than it is tall the search sheet
 * and the navigation panels become a column on the start side and the map stays visible beside it. Portrait is
 * untouched.
 */
object LandscapeLayout {
    /** Widest a side panel (search sheet, navigation banner and panel) gets: a phone-width column. */
    val MAX_PANEL_WIDTH: Dp = 380.dp

    /** A side panel never takes more than this share of the window width, so the map keeps a usable part. */
    const val MAX_PANEL_SHARE = 0.46f

    /** Margin between the side panel and the map controls drawn beside it. */
    val GAP: Dp = 12.dp

    fun isLandscape(width: Dp, height: Dp): Boolean = width > height

    fun isLandscapePx(widthPx: Int, heightPx: Int): Boolean = widthPx > heightPx

    /** Width of the side panel in a window [windowWidth] wide. */
    fun panelWidth(windowWidth: Dp): Dp = minOf(MAX_PANEL_WIDTH, windowWidth * MAX_PANEL_SHARE)

    /** Same as [panelWidth] in pixels (for the camera padding, which works in px). */
    fun panelWidthPx(windowWidthPx: Int, density: Float): Int =
        minOf(MAX_PANEL_WIDTH.value * density, windowWidthPx * MAX_PANEL_SHARE).roundToInt()
}

/**
 * Width the side panel takes at the start of the map screen, start system inset included (0 in portrait or while no
 * panel is shown). Overlays drawn over the map (weather chip, alert banner) start after it.
 */
val LocalSidePanelReserve = compositionLocalOf { 0.dp }

/** The left and right system bars and display cutout: what landscape content must keep clear of. */
@Composable
fun horizontalSafeInsets(): WindowInsets =
    WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)

/** Same, start side only. */
@Composable
fun startSafeInsets(): WindowInsets =
    WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Start)

/** Keeps content clear of the side bars and cutouts. A no-op in portrait (no horizontal insets there). */
@Composable
fun Modifier.horizontalSafePadding(): Modifier = windowInsetsPadding(horizontalSafeInsets())

/** A dialog body that scrolls when it does not fit (a 360 dp high window cannot hold a portrait-sized dialog). */
@Composable
fun Modifier.dialogScroll(): Modifier = verticalScroll(rememberScrollState())

/** Widest a centred settings-like column gets: wider lines are unreadable and put the switches far from their labels. */
val MAX_CONTENT_WIDTH: Dp = 640.dp
