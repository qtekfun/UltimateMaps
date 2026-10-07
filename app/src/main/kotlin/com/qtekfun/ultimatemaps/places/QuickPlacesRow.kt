package com.qtekfun.ultimatemaps.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.data.SpecialSlot

/**
 * Home, Work and the parked car as one-tap chips, plus the emergency screen. A filled chip routes there;
 * an empty Home or Work explains how to set it; the parking chip marks the current position when it is empty.
 */
@Composable
fun QuickPlacesRow(quick: QuickPlacesController, onGo: (PlaceInfo) -> Unit, onEmergency: () -> Unit, modifier: Modifier = Modifier) {
    val state = quick.state
    Column(modifier.fillMaxWidth().testTag("quick_places")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton(
                stringResource(R.string.quick_home),
                { quick.dismissMessage(); quick.destination(SpecialSlot.HOME)?.let(onGo) ?: quick.hintUnset(SpecialSlot.HOME) },
                Modifier.weight(1f), primary = state.home != null, tag = "quick_home", horizontalPadding = 6.dp, singleLine = true,
            )
            PanelButton(
                stringResource(R.string.quick_work),
                { quick.dismissMessage(); quick.destination(SpecialSlot.WORK)?.let(onGo) ?: quick.hintUnset(SpecialSlot.WORK) },
                Modifier.weight(1f), primary = state.work != null, tag = "quick_work", horizontalPadding = 6.dp, singleLine = true,
            )
            PanelButton(
                stringResource(if (state.parking != null) R.string.quick_parked else R.string.quick_park),
                { quick.dismissMessage(); quick.destination(SpecialSlot.PARKING)?.let(onGo) ?: quick.parkHere() },
                Modifier.weight(1f), primary = state.parking != null, tag = "quick_parking", horizontalPadding = 6.dp, singleLine = true,
            )
            PanelButton(stringResource(R.string.quick_sos), onEmergency, Modifier.weight(0.7f), tag = "quick_sos", horizontalPadding = 6.dp, singleLine = true)
        }
        if (state.parking != null) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelButton(stringResource(R.string.quick_park_again), quick::parkHere, Modifier.weight(1f), tag = "quick_park_again")
                PanelButton(stringResource(R.string.quick_park_clear), quick::clearParking, Modifier.weight(1f), tag = "quick_park_clear")
            }
        }
    }
}

@Composable
fun quickMessageText(m: QuickMessage): String = stringResource(
    when (m) {
        QuickMessage.HOME_SET -> R.string.quick_msg_home_set
        QuickMessage.WORK_SET -> R.string.quick_msg_work_set
        QuickMessage.PARKING_SET -> R.string.quick_msg_parking_set
        QuickMessage.PARKING_CLEARED -> R.string.quick_msg_parking_cleared
        QuickMessage.WAITING_FOR_LOCATION -> R.string.quick_msg_waiting
        QuickMessage.HOME_UNSET -> R.string.quick_msg_home_unset
        QuickMessage.WORK_UNSET -> R.string.quick_msg_work_unset
    },
)
