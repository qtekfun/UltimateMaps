package com.qtekfun.ultimatemaps.zbe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.route.RouteFormat
import com.qtekfun.ultimatemaps.ui.theme.Mapas
import kotlinx.coroutines.flow.StateFlow

/** The state of the "Low-emission zone ahead" banner, provided by the activity; absent in previews and screens without it. */
val LocalZbeBanner = staticCompositionLocalOf<StateFlow<ZbeBannerState?>?> { null }

private val ZoneAmber = Color(0xFF8A4B00) // white text on it: contrast above 6:1

/**
 * "Low-emission zone ahead" with the distance counting down. Draws nothing when there is no prompt; announced politely to
 * screen readers. It never says whether the vehicle is allowed: the text asks the driver to check the access rules.
 */
@Composable
fun ZbeAheadBanner(modifier: Modifier = Modifier, glove: Boolean = false) {
    val flow = LocalZbeBanner.current ?: return
    val state by flow.collectAsState()
    ZbeAheadChip(state, modifier, glove)
}

@Composable
fun ZbeAheadChip(state: ZbeBannerState?, modifier: Modifier = Modifier, glove: Boolean = false) {
    val s = state ?: return
    val locale = LocalConfiguration.current.locales[0]
    val title = stringResource(R.string.zbe_ahead_title)
    val distance = RouteFormat.distance(s.distanceMeters.toDouble(), locale)
    val hint = stringResource(R.string.zbe_ahead_hint)
    val description = stringResource(R.string.zbe_ahead_description, title, distance, hint)
    Row(
        modifier
            .testTag("zbe_ahead")
            .defaultMinSize(minHeight = if (glove) 72.dp else 52.dp)
            .background(ZoneAmber, RoundedCornerShape(if (glove) 20.dp else 16.dp))
            .padding(horizontal = if (glove) 18.dp else 14.dp, vertical = 8.dp)
            .clearAndSetSemantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Column {
            BasicText(
                title,
                style = Mapas.typography.callout.copy(color = Color.White, fontSize = if (glove) 20.sp else 15.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.testTag("zbe_ahead_title"),
            )
            BasicText(
                distance,
                style = Mapas.typography.title.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (glove) 32.sp else 22.sp),
                modifier = Modifier.testTag("zbe_ahead_distance"),
            )
            BasicText(
                hint,
                style = Mapas.typography.callout.copy(color = Color.White, fontSize = if (glove) 18.sp else 13.sp),
                modifier = Modifier.testTag("zbe_ahead_hint"),
            )
        }
    }
}
