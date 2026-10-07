package com.qtekfun.ultimatemaps.nav

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Draws the system status-bar icons (clock, battery, signal) in white while this composable is on screen.
 *
 * The navigation banner is always dark, whatever the map theme, but `enableEdgeToEdge()` picks the icon color from
 * the map theme: with the light map the icons were dark gray on the dark banner and could not be read (reported on a
 * real device). The previous appearance is restored when the navigation screen leaves the composition.
 */
@Composable
fun LightStatusBarIcons() {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false // false = light (white) icons, for a dark background
        onDispose {
            if (controller != null && previous != null) controller.isAppearanceLightStatusBars = previous
        }
    }
}
