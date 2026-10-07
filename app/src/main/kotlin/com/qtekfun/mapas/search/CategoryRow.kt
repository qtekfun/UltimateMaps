package com.qtekfun.mapas.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.mapas.core.search.SearchResult
import com.qtekfun.mapas.places.PanelButton
import com.qtekfun.mapas.route.RouteFormat

/**
 * One chip per [PlaceCategory] under the search field. Tapping one lists the nearest places of that category; tapping
 * the selected one again clears the list. [onOpened] lets the host raise the sheet so the list is visible.
 */
@Composable
fun CategoryRow(search: SearchCoordinator, modifier: Modifier = Modifier, onOpened: () -> Unit = {}) {
    val selected = search.state.category
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("category_row"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (category in PlaceCategory.entries) {
            val name = stringResource(category.query)
            PanelButton(
                stringResource(category.label),
                {
                    if (selected == category) {
                        search.clearCategory()
                    } else {
                        search.browseCategory(category, name)
                        onOpened()
                    }
                },
                primary = selected == category,
                tag = "category_${category.id}",
            )
        }
    }
}

/** The distance column of a category result ("350 m", "1.2 km"); null when the distance is unknown. */
@Composable
fun distanceText(result: SearchResult): String? {
    val meters = result.distanceMeters ?: return null
    return RouteFormat.distance(meters, LocalConfiguration.current.locales[0])
}
