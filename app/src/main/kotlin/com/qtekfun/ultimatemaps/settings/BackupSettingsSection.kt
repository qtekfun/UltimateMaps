package com.qtekfun.ultimatemaps.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.data.ImportResult
import com.qtekfun.ultimatemaps.settings.backup.RejectReason
import com.qtekfun.ultimatemaps.settings.backup.RestorePlan
import com.qtekfun.ultimatemaps.settings.backup.RestoreResult
import com.qtekfun.ultimatemaps.settings.backup.SettingSpec
import com.qtekfun.ultimatemaps.settings.backup.SettingsSchema
import com.qtekfun.ultimatemaps.ui.theme.Mapas

/** What an import would do, shown in the confirmation dialog. [plan] is null when the file has no settings (places only). */
class ImportPreview(val plan: RestorePlan?, val hasPlaces: Boolean)

/** The last thing that happened, as one message under the buttons. */
sealed interface BackupOutcome {
    data class Exported(val everything: Boolean) : BackupOutcome
    data class Restored(val settings: RestoreResult?, val places: ImportResult?) : BackupOutcome
    data class Rejected(val reason: RejectReason, val fileSchema: Int?) : BackupOutcome
    data object Failed : BackupOutcome
}

/**
 * The state of the section. It lives in the activity (not in the composition) so the result survives the
 * recomposition caused by [revision], which makes the other sections re-read their settings after a restore.
 */
class BackupUiState {
    var preview by mutableStateOf<ImportPreview?>(null)
    var outcome by mutableStateOf<BackupOutcome?>(null)
    var busy by mutableStateOf(false)

    /** Bumped after a restore: the Settings screen rebuilds its other sections so they show the restored values. */
    var revision by mutableIntStateOf(0)
}

/** What the "Backup and restore" section calls. The activity builds it (file pickers, background work). */
class BackupSettingsEnv(
    val state: BackupUiState,
    val onExportSettings: () -> Unit,
    val onExportEverything: () -> Unit,
    val onImport: () -> Unit,
    val onConfirmImport: () -> Unit,
    val onCancelImport: () -> Unit,
    /** Switches restored as intents that the owner still has to accept (and that are still off). */
    val pendingConsent: () -> List<SettingSpec> = { emptyList() },
    val onDismissPending: () -> Unit = {},
)

/**
 * Section "Backup and restore": export the settings to a file the owner chooses, import them from one (after a
 * confirmation that shows what will change), or export everything (places and settings in one ZIP). Nothing here
 * uses the network.
 */
@Composable
fun BackupSection(env: BackupSettingsEnv) {
    val state = env.state
    SectionTitle(stringResource(R.string.backup_title))
    Card("backup_card") {
        BasicText(stringResource(R.string.backup_body), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
        Spacer(Modifier.height(4.dp))
        TextButton(stringResource(R.string.backup_export), "backup_export", enabled = !state.busy, onClick = env.onExportSettings)
        TextButton(stringResource(R.string.backup_import), "backup_import", enabled = !state.busy, onClick = env.onImport)
        BasicText(stringResource(R.string.backup_all_body), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
        TextButton(stringResource(R.string.backup_export_all), "backup_export_all", enabled = !state.busy, onClick = env.onExportEverything)
        state.outcome?.let { outcome ->
            Spacer(Modifier.height(6.dp))
            BasicText(
                outcomeText(outcome),
                style = Mapas.typography.callout.copy(color = if (outcome is BackupOutcome.Rejected || outcome is BackupOutcome.Failed) Mapas.colors.warning else Mapas.colors.label),
                modifier = Modifier.testTag("backup_outcome"),
            )
        }
    }
    val pending = env.pendingConsent()
    if (pending.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Card("backup_pending_card") {
            BasicText(stringResource(R.string.backup_pending_title), style = Mapas.typography.body.copy(color = Mapas.colors.label))
            BasicText(stringResource(R.string.backup_pending_body), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
            pending.forEach { spec ->
                BasicText(
                    "• " + stringResource(pendingLabel(spec)),
                    style = Mapas.typography.callout.copy(color = Mapas.colors.label),
                    modifier = Modifier.testTag("backup_pending_item"),
                )
            }
            TextButton(stringResource(R.string.backup_pending_dismiss), "backup_pending_dismiss", onClick = env.onDismissPending)
        }
    }
    state.preview?.let { preview -> ImportDialog(preview, env) }
}

@Composable
private fun outcomeText(outcome: BackupOutcome): String = when (outcome) {
    is BackupOutcome.Exported -> stringResource(if (outcome.everything) R.string.backup_exported_all else R.string.backup_exported)
    BackupOutcome.Failed -> stringResource(R.string.backup_failed)
    is BackupOutcome.Rejected -> when (outcome.reason) {
        RejectReason.NOT_A_SETTINGS_FILE -> stringResource(R.string.backup_err_not_settings)
        RejectReason.CORRUPT -> stringResource(R.string.backup_err_corrupt)
        RejectReason.TOO_LARGE -> stringResource(R.string.backup_err_too_large)
        RejectReason.NEWER_SCHEMA -> stringResource(R.string.backup_err_newer, outcome.fileSchema ?: 0)
    }
    is BackupOutcome.Restored -> buildString {
        outcome.settings?.let { r ->
            append(stringResource(R.string.backup_restored, r.restored, r.skipped))
            if (r.needConsent > 0) append(' ').append(stringResource(R.string.backup_restored_consent, r.needConsent))
            if (r.regions > 0) append(' ').append(stringResource(R.string.backup_restored_regions, r.regions))
        }
        outcome.places?.let { p ->
            if (isNotEmpty()) append(' ')
            append(stringResource(R.string.backup_restored_places, p.placesAdded, p.placesDuplicate, p.tracksAdded))
        }
    }
}

private fun pendingLabel(spec: SettingSpec): Int = when (spec.id) {
    "cameras/fixed" -> R.string.backup_item_cameras_fixed
    "cameras/mobile_zones" -> R.string.backup_item_cameras_mobile
    "cameras/incidents" -> R.string.backup_item_incidents
    "cameras/v16" -> R.string.backup_item_v16
    "cameras/roadworks" -> R.string.backup_item_roadworks
    "fuel/enabled" -> R.string.backup_item_fuel
    "chargers/enabled" -> R.string.backup_item_chargers
    else -> R.string.backup_item_other
}

private fun groupLabel(group: String): Int = when (group) {
    SettingsSchema.GROUP_NAVIGATION -> R.string.backup_group_navigation
    SettingsSchema.GROUP_NAVIGATION_UI -> R.string.backup_group_navigation_ui
    SettingsSchema.GROUP_FUEL -> R.string.backup_group_fuel
    SettingsSchema.GROUP_CAMERAS -> R.string.backup_group_cameras
    SettingsSchema.GROUP_CHARGERS -> R.string.backup_group_chargers
    SettingsSchema.GROUP_HISTORY -> R.string.backup_group_history
    SettingsSchema.GROUP_RECORDING -> R.string.backup_group_recording
    SettingsSchema.GROUP_REGIONS -> R.string.backup_group_regions
    else -> R.string.backup_item_other
}

@Composable
private fun ImportDialog(preview: ImportPreview, env: BackupSettingsEnv) {
    Dialog(onDismissRequest = env.onCancelImport) {
        Column(Modifier.clip(Mapas.shapes.control).background(Mapas.colors.sheet).padding(20.dp).testTag("backup_confirm_dialog")) {
            BasicText(stringResource(R.string.backup_confirm_title), style = Mapas.typography.title.copy(color = Mapas.colors.label))
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                val plan = preview.plan
                if (plan != null) {
                    if (plan.changes.isEmpty()) {
                        BasicText(stringResource(R.string.backup_confirm_nothing), style = Mapas.typography.body.copy(color = Mapas.colors.label), modifier = Modifier.testTag("backup_confirm_nothing"))
                    } else {
                        BasicText(stringResource(R.string.backup_confirm_body, plan.changes.size), style = Mapas.typography.body.copy(color = Mapas.colors.label))
                        plan.changes.groupBy { it.spec.group }.forEach { (group, changes) ->
                            BasicText(
                                stringResource(R.string.backup_confirm_group, stringResource(groupLabel(group)), changes.size),
                                style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
                                modifier = Modifier.testTag("backup_confirm_group"),
                            )
                        }
                    }
                    if (plan.consent.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        BasicText(stringResource(R.string.backup_confirm_consent), style = Mapas.typography.callout.copy(color = Mapas.colors.label), modifier = Modifier.testTag("backup_confirm_consent"))
                        plan.consent.forEach { BasicText("• " + stringResource(pendingLabel(it)), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel)) }
                    }
                    if (plan.regions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        BasicText(stringResource(R.string.backup_confirm_regions, plan.regions.size), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("backup_confirm_regions"))
                    }
                    if (plan.skipped > 0) {
                        Spacer(Modifier.height(8.dp))
                        BasicText(stringResource(R.string.backup_confirm_skipped, plan.skipped), style = Mapas.typography.callout.copy(color = Mapas.colors.warning), modifier = Modifier.testTag("backup_confirm_skipped"))
                    }
                }
                if (preview.hasPlaces) {
                    Spacer(Modifier.height(8.dp))
                    BasicText(stringResource(R.string.backup_confirm_places), style = Mapas.typography.callout.copy(color = Mapas.colors.label), modifier = Modifier.testTag("backup_confirm_places"))
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.align(Alignment.End)) {
                TextButton(stringResource(R.string.backup_confirm_no), "backup_confirm_no", onClick = env.onCancelImport)
                TextButton(stringResource(R.string.backup_confirm_yes), "backup_confirm_yes", onClick = env.onConfirmImport)
            }
        }
    }
}
