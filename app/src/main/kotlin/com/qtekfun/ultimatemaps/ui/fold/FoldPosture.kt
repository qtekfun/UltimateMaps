package com.qtekfun.ultimatemaps.ui.fold

import android.app.Activity
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The fold or hinge of the device as the screens need it, in window pixels. A plain value (no Jetpack types) so the layout
 * rules and the tests do not depend on WindowManager. [vertical] is the orientation of the crease itself: a vertical crease
 * puts the two halves side by side (book), a horizontal one puts them one above the other (tabletop).
 */
@Immutable
data class FoldFeatureInfo(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val vertical: Boolean,
    /** Partly open (not flat). */
    val halfOpened: Boolean,
    /** The feature splits the window into two logical areas (always on a dual-screen device, half-open on a foldable). */
    val separating: Boolean,
    /** The hinge hides part of the display (dual-screen devices); nothing may be drawn there. */
    val occluding: Boolean = false,
) {
    /** Whether content has to keep to one side of the crease: half-open, separating or hiding part of the screen. */
    val splits: Boolean get() = halfOpened || separating || occluding
}

/** Where the device is folded. One implementation reads WindowManager, tests use [StaticFoldPosture]. */
interface FoldPosture {
    /** The fold that crosses the window now, or null (no fold, or the window does not touch it). */
    val feature: StateFlow<FoldFeatureInfo?>
}

/** A posture set by hand: the fake for tests and previews, and the "no fold" default. */
class StaticFoldPosture(initial: FoldFeatureInfo? = null) : FoldPosture {
    private val state = MutableStateFlow(initial)
    override val feature: StateFlow<FoldFeatureInfo?> = state.asStateFlow()
    fun set(value: FoldFeatureInfo?) {
        state.value = value
    }
}

/**
 * Reads the fold from AndroidX WindowManager (`androidx.window`, Apache-2.0, no Google Play Services: it talks to the
 * platform's window extensions) while [owner] is started. One value per change of posture, nothing per frame.
 */
class WindowManagerFoldPosture(activity: Activity, owner: LifecycleOwner) : FoldPosture {
    private val state = MutableStateFlow<FoldFeatureInfo?>(null)
    override val feature: StateFlow<FoldFeatureInfo?> = state.asStateFlow()

    init {
        owner.lifecycleScope.launch {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
                    state.value = pick(info.displayFeatures.filterIsInstance<FoldingFeature>())
                }
            }
        }
    }

    private companion object {
        /** The feature that matters: one that splits the window, else the first. */
        fun pick(features: List<FoldingFeature>): FoldFeatureInfo? {
            val f = features.firstOrNull { it.isSeparating || it.state == FoldingFeature.State.HALF_OPENED } ?: features.firstOrNull()
            return f?.let {
                val b = it.bounds
                FoldFeatureInfo(
                    left = b.left, top = b.top, right = b.right, bottom = b.bottom,
                    vertical = it.orientation == FoldingFeature.Orientation.VERTICAL,
                    halfOpened = it.state == FoldingFeature.State.HALF_OPENED,
                    separating = it.isSeparating,
                    occluding = it.occlusionType == FoldingFeature.OcclusionType.FULL,
                )
            }
        }
    }
}

/** The fold crossing the window, provided by the activity (null: none). Tests provide fakes. */
val LocalFoldFeature = compositionLocalOf<FoldFeatureInfo?> { null }
