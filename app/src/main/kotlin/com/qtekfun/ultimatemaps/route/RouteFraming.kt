package com.qtekfun.ultimatemaps.route

import com.qtekfun.ultimatemaps.core.geo.LatLon
import com.qtekfun.ultimatemaps.core.map.CameraPadding
import com.qtekfun.ultimatemaps.ui.sheet.SheetDetent
import kotlin.math.roundToInt

/** What covers the map while a route is on screen, in pixels, for one screen. Pure: tested on the JVM. */
object RoutePadding {
    /** Margin kept around the route on the free sides, dp. */
    const val EDGE_DP = 32f

    /** The top bar area (attribution, scale bar) under the status bar, dp. */
    const val TOP_BAR_DP = 56f

    /** The right-hand button column (settings, compass, locate): its width plus the screen margin, dp. */
    const val RIGHT_COLUMN_DP = 72f

    /** Same numbers as `BottomSheet`: collapsed height in dp and the medium share of the screen height. */
    const val SHEET_COLLAPSED_DP = 107f
    const val SHEET_MEDIUM_FRACTION = 0.46f

    /** Visible height of the sheet at [detent] on a screen [heightPx] tall. */
    fun sheetHeightPx(detent: SheetDetent, heightPx: Int, density: Float): Int {
        val collapsed = SHEET_COLLAPSED_DP * density
        return when (detent) {
            SheetDetent.COLLAPSED -> collapsed
            SheetDetent.MEDIUM -> (heightPx * SHEET_MEDIUM_FRACTION).coerceAtLeast(collapsed)
            SheetDetent.FULL -> heightPx.toFloat()
        }.roundToInt()
    }

    fun compute(detent: SheetDetent, heightPx: Int, density: Float, statusBarPx: Int): CameraPadding {
        val edge = (EDGE_DP * density).roundToInt()
        return CameraPadding(
            left = edge,
            top = statusBarPx.coerceAtLeast(0) + (TOP_BAR_DP * density).roundToInt(),
            right = (RIGHT_COLUMN_DP * density).roundToInt(),
            bottom = sheetHeightPx(detent, heightPx, density) + edge,
        )
    }
}

/**
 * Decides when the camera frames the whole route (origin and destination included). Frames when a route or itinerary
 * is shown or replaced (first result, profile, alternative), and again when the sheet changes height, but then only
 * while the user has not moved the map since, and never during a navigation (its following camera owns the view).
 */
class RouteFraming(
    private val navigating: () -> Boolean,
    private val detent: () -> SheetDetent,
    /** Animates the camera so [points] fit inside the screen minus the given padding. */
    private val frame: (List<LatLon>, SheetDetent) -> Unit,
) {
    private var points: List<LatLon> = emptyList()
    private var userMoved = false
    private var lastFramedDetent: SheetDetent? = null

    /** A route (or itinerary) was drawn or replaced: always frames it, and forgets earlier map moves. */
    fun show(route: List<LatLon>) {
        points = route
        userMoved = false
        lastFramedDetent = null
        if (route.isEmpty()) return
        doFrame()
    }

    /** The route was removed. */
    fun clear() {
        points = emptyList()
        userMoved = false
        lastFramedDetent = null
    }

    /** The sheet settled at a new detent. */
    fun onDetentChanged() {
        if (points.isEmpty() || userMoved) return
        val d = detent()
        if (d == SheetDetent.FULL || d == lastFramedDetent) return // the sheet covers the map: nothing to look at
        doFrame()
    }

    /** The user dragged or pinched the map: stop adjusting the view on their behalf. */
    fun onUserMovedMap() {
        if (points.isNotEmpty()) userMoved = true
    }

    private fun doFrame() {
        if (navigating()) return
        val d = detent()
        lastFramedDetent = d
        frame(points, d)
    }
}
