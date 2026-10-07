package com.qtekfun.mapas.emergency

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.qtekfun.mapas.R
import com.qtekfun.mapas.places.PanelButton
import com.qtekfun.mapas.ui.theme.Mapas

/**
 * The emergency screen: the current coordinates (large, on screen only), a button that opens the dialer with 112
 * and a button that shares the coordinates as text and a `geo:` link through the system share sheet.
 */
@Composable
fun EmergencyScreen(
    state: EmergencyState,
    onDial: () -> Unit,
    onShare: () -> Unit,
    onGrantLocation: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(Mapas.colors.sheet)
            .windowInsetsPadding(WindowInsets.statusBars)
            .testTag("emergency_screen"),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                "‹ " + stringResource(R.string.settings_back),
                style = Mapas.typography.body.copy(color = Mapas.colors.accent),
                modifier = Modifier.clickable(role = Role.Button, onClick = onBack).padding(12.dp).testTag("emergency_back"),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = Mapas.dimens.screenMargin),
        ) {
            BasicText(stringResource(R.string.emergency_title), style = Mapas.typography.largeTitle.copy(color = Mapas.colors.label))
            Spacer(Modifier.height(16.dp))
            PanelButton(
                stringResource(R.string.emergency_call, EmergencyIntents.NUMBER), onDial,
                Modifier.fillMaxWidth(), primary = true, tag = "emergency_call",
            )
            if (state.dialFailed) {
                BasicText(
                    stringResource(R.string.emergency_dial_failed),
                    style = Mapas.typography.callout.copy(color = Mapas.colors.warning),
                    modifier = Modifier.padding(top = 6.dp).testTag("emergency_dial_failed"),
                )
            }
            Spacer(Modifier.height(24.dp))
            BasicText(stringResource(R.string.emergency_position_title), style = Mapas.typography.title.copy(color = Mapas.colors.label))
            Spacer(Modifier.height(8.dp))
            val position = state.position
            when {
                position != null -> {
                    BasicText(
                        EmergencyText.coordinates(position),
                        style = Mapas.typography.largeTitle.copy(color = Mapas.colors.label),
                        modifier = Modifier.testTag("emergency_coords"),
                    )
                    state.accuracyMeters?.let { m ->
                        BasicText(
                            stringResource(R.string.emergency_accuracy, Math.round(m)),
                            style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
                            modifier = Modifier.testTag("emergency_accuracy"),
                        )
                    }
                }
                !state.providerAvailable -> Note(R.string.emergency_no_provider, "emergency_status")
                !state.permissionGranted -> {
                    Note(R.string.emergency_no_permission, "emergency_status")
                    Spacer(Modifier.height(8.dp))
                    PanelButton(stringResource(R.string.emergency_grant), onGrantLocation, Modifier.fillMaxWidth(), tag = "emergency_grant")
                }
                else -> Note(R.string.emergency_waiting, "emergency_status")
            }
            Spacer(Modifier.height(16.dp))
            PanelButton(
                stringResource(R.string.emergency_share), onShare, Modifier.fillMaxWidth(),
                enabled = position != null, tag = "emergency_share",
            )
            Spacer(Modifier.height(16.dp))
            BasicText(
                stringResource(R.string.emergency_privacy_note),
                style = Mapas.typography.caption.copy(color = Mapas.colors.secondaryLabel),
                modifier = Modifier.testTag("emergency_note"),
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Note(text: Int, tag: String) {
    BasicText(stringResource(text), style = Mapas.typography.body.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag(tag))
}
