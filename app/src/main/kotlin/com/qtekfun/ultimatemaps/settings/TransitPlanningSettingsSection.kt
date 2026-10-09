package com.qtekfun.ultimatemaps.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.transit.follow.TransitPlanningDefaults
import com.qtekfun.ultimatemaps.transit.follow.TransitTripSettingsStore
import com.qtekfun.ultimatemaps.ui.theme.Mapas
import kotlinx.coroutines.flow.StateFlow

/**
 * "Public transport planning" cards of the Navigation category: when walking is listed next to the transit options, how
 * much time a transit option must save to beat it, and the cap on walking per trip. Radio choices like the rest of
 * Settings; the values are minutes and live in the transit prefs file (all in the backup whitelist).
 */
@Composable
internal fun TransitPlanningCards(store: TransitTripSettingsStore) {
    Spacer(Modifier.height(10.dp))
    SectionTitle(stringResource(R.string.transit_plan_title))
    MinutesCard(
        "transit_plan_walk", R.string.transit_plan_walk_title, R.string.transit_plan_walk_body,
        TransitPlanningDefaults.WALK_ALTERNATIVE_CHOICES, store.walkAlternativeMin, store::setWalkAlternativeMin,
    )
    Spacer(Modifier.height(10.dp))
    MinutesCard(
        "transit_plan_saving", R.string.transit_plan_saving_title, R.string.transit_plan_saving_body,
        TransitPlanningDefaults.MIN_SAVING_CHOICES, store.minSavingMin, store::setMinSavingMin,
    )
    Spacer(Modifier.height(10.dp))
    MinutesCard(
        "transit_plan_maxwalk", R.string.transit_plan_maxwalk_title, R.string.transit_plan_maxwalk_body,
        TransitPlanningDefaults.MAX_WALK_CHOICES, store.maxWalkMin, store::setMaxWalkMin,
    )
    Spacer(Modifier.height(10.dp))
    ChangesCard(store)
}

/** The vehicle changes a trip may have: any, at most one, none. */
@Composable
private fun ChangesCard(store: TransitTripSettingsStore) {
    val value by store.maxChanges.collectAsState()
    Card("transit_plan_changes_card") {
        BasicText(stringResource(R.string.transit_plan_changes_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
        BasicText(stringResource(R.string.transit_plan_changes_body), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
        TransitPlanningDefaults.CHANGES_CHOICES.forEach { choice ->
            ChoiceRow(
                stringResource(com.qtekfun.ultimatemaps.transit.changesLabel(choice)), value == choice, radio = true,
                tag = "transit_plan_changes_$choice",
            ) { store.setMaxChanges(choice) }
        }
    }
}

/** A card with one radio per choice; 0 minutes is shown as "No limit" only for the walking cap. */
@Composable
private fun MinutesCard(
    tag: String,
    @androidx.annotation.StringRes title: Int,
    @androidx.annotation.StringRes body: Int,
    choices: List<Int>,
    current: StateFlow<Int>,
    set: (Int) -> Unit,
) {
    val value by current.collectAsState()
    // A restored value that is not one of the offered choices is still shown, as an extra row.
    val shown = if (value in choices) choices else (choices + value).sorted()
    Card("${tag}_card") {
        BasicText(stringResource(title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
        BasicText(stringResource(body), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
        shown.forEach { m ->
            val label = if (m == 0 && tag == "transit_plan_maxwalk") stringResource(R.string.transit_plan_maxwalk_none) else stringResource(R.string.transit_plan_minutes, m)
            ChoiceRow(label, value == m, radio = true, tag = "${tag}_$m") { set(m) }
        }
    }
}
