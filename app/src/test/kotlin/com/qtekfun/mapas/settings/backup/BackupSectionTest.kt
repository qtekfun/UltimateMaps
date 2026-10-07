package com.qtekfun.mapas.settings.backup

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.qtekfun.mapas.settings.BackupOutcome
import com.qtekfun.mapas.settings.BackupSection
import com.qtekfun.mapas.settings.BackupSettingsEnv
import com.qtekfun.mapas.settings.BackupUiState
import com.qtekfun.mapas.settings.ImportPreview
import com.qtekfun.mapas.ui.theme.MapasTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BackupSectionTest {
    @get:Rule
    val rule = createComposeRule()

    private val state = BackupUiState()
    private val log = ArrayList<String>()
    private var pendingItems: List<SettingSpec> = emptyList()

    private val env = BackupSettingsEnv(
        state = state,
        onExportSettings = { log += "export" },
        onExportEverything = { log += "everything" },
        onImport = { log += "import" },
        onConfirmImport = { log += "confirm" },
        onCancelImport = { log += "cancel"; state.preview = null },
        pendingConsent = { pendingItems },
        onDismissPending = { log += "dismiss"; pendingItems = emptyList(); state.revision++ },
    )

    private fun show() = rule.setContent {
        MapasTheme(darkTheme = false) {
            Column(Modifier.verticalScroll(rememberScrollState())) { BackupSection(env) }
        }
    }

    private fun plan(storage: MemoryStorage, fileValues: Map<String, Any>, regions: List<String> = emptyList()): RestorePlan {
        val file = SettingsBackup.parse(SettingsBackup.export(MemoryStorage(fileValues), regions, "1", 0L))
        return SettingsBackup.plan(file, storage)
    }

    @Test fun theThreeButtonsCallTheirActions() {
        show()
        rule.onNodeWithTag("backup_export").performScrollTo().performClick()
        rule.onNodeWithTag("backup_import").performScrollTo().performClick()
        rule.onNodeWithTag("backup_export_all").performScrollTo().performClick()
        assertEquals(listOf("export", "import", "everything"), log)
    }

    @Test fun buttonsAreDisabledWhileBusy() {
        state.busy = true
        show()
        rule.onNodeWithTag("backup_export").performScrollTo().performClick()
        assertTrue(log.isEmpty())
    }

    @Test fun theConfirmationShowsWhatWillChangeAndWhatNeedsConsent() {
        val here = MemoryStorage()
        state.preview = ImportPreview(
            plan(here, mapOf("navigation/units" to "IMPERIAL", "navigation/voice_volume" to 50, "cameras/fixed" to true), listOf("a", "b")),
            hasPlaces = true,
        )
        show()
        rule.onNodeWithTag("backup_confirm_dialog").assertIsDisplayed()
        rule.onNodeWithTag("backup_confirm_group").assertIsDisplayed() // "Navigation: 2"
        rule.onNodeWithTag("backup_confirm_consent").assertIsDisplayed()
        rule.onNodeWithTag("backup_confirm_regions").assertIsDisplayed()
        rule.onNodeWithTag("backup_confirm_places").assertIsDisplayed()
        rule.onNodeWithTag("backup_confirm_yes").performClick()
        assertEquals(listOf("confirm"), log)
    }

    @Test fun cancellingChangesNothing() {
        state.preview = ImportPreview(plan(MemoryStorage(), mapOf("navigation/units" to "IMPERIAL")), hasPlaces = false)
        show()
        rule.onNodeWithTag("backup_confirm_no").performClick()
        assertEquals(listOf("cancel"), log)
        assertNull(state.preview)
        rule.onAllNodesWithTag("backup_confirm_dialog").assertCountEquals0()
    }

    @Test fun aFileThatMatchesShowsThatNothingWillChange() {
        state.preview = ImportPreview(plan(MemoryStorage(), emptyMap()), hasPlaces = false)
        show()
        rule.onNodeWithTag("backup_confirm_nothing").assertIsDisplayed()
    }

    @Test fun skippedEntriesAreMentioned() {
        val doc = """{"format":"ultimatemaps-settings","schema":1,"settings":{"navigation":{"voice_volume":{"type":"int","value":1}}}}"""
        state.preview = ImportPreview(SettingsBackup.plan(SettingsBackup.parse(doc), MemoryStorage()), hasPlaces = false)
        show()
        rule.onNodeWithTag("backup_confirm_skipped").assertIsDisplayed()
    }

    @Test fun theResultSummaryShowsRestoredAndSkipped() {
        state.outcome = BackupOutcome.Restored(RestoreResult(restored = 12, needConsent = 2, skipped = 3, ignored = 1, regions = 4), null)
        show()
        rule.onNodeWithTag("backup_outcome").assertIsDisplayed()
        val text = rule.onNodeWithTag("backup_outcome").fetchSemanticsNode().config
            .first { it.key.name == "Text" }.value.toString()
        assertTrue("12" in text && "3" in text, text)
    }

    @Test fun aRejectedFileShowsAClearMessage() {
        state.outcome = BackupOutcome.Rejected(RejectReason.NEWER_SCHEMA, 5)
        show()
        val text = rule.onNodeWithTag("backup_outcome").fetchSemanticsNode().config.first { it.key.name == "Text" }.value.toString()
        assertTrue("5" in text, text)
    }

    @Test fun pendingSwitchesAreListedUntilDismissed() {
        pendingItems = listOf(SettingsSchema.byId("cameras/fixed")!!, SettingsSchema.byId("fuel/enabled")!!)
        show()
        assertEquals(2, rule.onAllNodesWithTag("backup_pending_item").fetchSemanticsNodes().size)
        rule.onNodeWithTag("backup_pending_dismiss").performScrollTo().performClick()
        assertEquals(listOf("dismiss"), log)
    }

    @Test fun noPendingCardWhenNothingIsPending() {
        show()
        rule.onAllNodesWithTag("backup_pending_card").assertCountEquals0()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals0() =
        assertEquals(0, fetchSemanticsNodes().size)
}

/** The flows behind the section, run in line (no threads, no clock). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsBackupCoordinatorTest {
    private class FakePlaces(var failOnRestore: Boolean = false) : PlacesBackupAccess {
        var written: Map<String, ByteArray>? = null
        var restored = 0
        override fun write(out: java.io.OutputStream, extraEntries: Map<String, ByteArray>) {
            written = extraEntries
            out.write("PLACES".toByteArray())
        }
        override fun restore(input: java.io.InputStream): com.qtekfun.mapas.core.data.ImportResult {
            if (failOnRestore) error("boom")
            restored++
            return com.qtekfun.mapas.core.data.ImportResult(2, 1, 0, 0, 0)
        }
    }

    private val storage = MemoryStorage(mapOf("navigation/voice_volume" to 50))
    private val pending = InMemoryPendingRestore()
    private val places = FakePlaces()
    private fun coordinator(p: PlacesBackupAccess? = places, state: BackupUiState = BackupUiState()) = SettingsBackupCoordinator(
        state, storage, pending, installedRegions = { listOf("spain-madrid") }, appVersion = "1.0", nowMillis = { 0L },
        places = p, background = { it() }, foreground = { it() },
    )

    @Test fun exportWritesTheSettingsFileAndReportsIt() {
        val c = coordinator()
        val out = ByteArrayOutputStream()
        c.exportSettings { out }
        assertEquals(BackupOutcome.Exported(everything = false), c.state.outcome)
        val parsed = SettingsBackup.parse(out.toString())
        assertEquals(50, parsed.values["navigation/voice_volume"])
        assertEquals(listOf("spain-madrid"), parsed.regions)
        assertEquals(false, c.state.busy)
    }

    @Test fun exportFailureIsReportedWithoutThrowing() {
        val c = coordinator()
        c.exportSettings { error("no file") }
        assertEquals(BackupOutcome.Failed, c.state.outcome)
    }

    @Test fun exportEverythingAddsTheSettingsEntryToThePlacesZip() {
        val c = coordinator()
        c.exportEverything { ByteArrayOutputStream() }
        assertEquals(BackupOutcome.Exported(everything = true), c.state.outcome)
        val extra = assertNotNull(places.written)
        assertEquals(setOf(SettingsFile.ENTRY), extra.keys)
        assertEquals(50, SettingsBackup.parse(String(extra.getValue(SettingsFile.ENTRY))).values["navigation/voice_volume"])
    }

    @Test fun loadShowsAPreviewAndChangesNothingUntilConfirmed() {
        val file = SettingsBackup.export(MemoryStorage(mapOf("navigation/voice_volume" to 25, "cameras/fixed" to true)), listOf("r1"), "1", 0L)
        val c = coordinator()
        c.load { ByteArrayInputStream(file.toByteArray()) }
        val preview = assertNotNull(c.state.preview)
        assertEquals(listOf("navigation/voice_volume"), preview.plan!!.changes.map { it.spec.id })
        assertEquals(50, storage.values["navigation/voice_volume"])
        assertEquals(0, storage.writes)

        c.confirm()
        assertNull(c.state.preview)
        assertEquals(25, storage.values["navigation/voice_volume"])
        assertEquals(setOf("cameras/fixed"), pending.consent)
        assertEquals(setOf("r1"), pending.regions)
        val outcome = c.state.outcome as BackupOutcome.Restored
        assertEquals(1, outcome.settings!!.needConsent)
        assertEquals(1, c.state.revision)
    }

    @Test fun cancelDropsTheFile() {
        val file = SettingsBackup.export(MemoryStorage(mapOf("navigation/voice_volume" to 25)), emptyList(), "1", 0L)
        val c = coordinator()
        c.load { ByteArrayInputStream(file.toByteArray()) }
        c.cancel()
        c.confirm() // nothing loaded: nothing happens
        assertEquals(50, storage.values["navigation/voice_volume"])
        assertEquals(0, storage.writes)
    }

    @Test fun aBadFileIsRejectedAndNothingChanges() {
        val c = coordinator()
        c.load { ByteArrayInputStream("not json at all".toByteArray()) }
        assertEquals(BackupOutcome.Rejected(RejectReason.CORRUPT, null), c.state.outcome)
        assertNull(c.state.preview)
        val newer = SettingsBackup.export(MemoryStorage(), emptyList(), "1", 0L).replace("\"schema\": 1", "\"schema\": 9")
        c.load { ByteArrayInputStream(newer.toByteArray()) }
        assertEquals(BackupOutcome.Rejected(RejectReason.NEWER_SCHEMA, 9), c.state.outcome)
        assertEquals(0, storage.writes)
    }

    @Test fun aCombinedFileRestoresPlacesAndSettings() {
        val settings = SettingsBackup.export(MemoryStorage(mapOf("navigation/voice_volume" to 25)), emptyList(), "1", 0L)
        val zipBytes = ByteArrayOutputStream().also { o ->
            java.util.zip.ZipOutputStream(o).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry(SettingsFile.PLACES_ENTRY)); z.write("{}".toByteArray()); z.closeEntry()
                z.putNextEntry(java.util.zip.ZipEntry(SettingsFile.ENTRY)); z.write(settings.toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        val c = coordinator()
        c.load { ByteArrayInputStream(zipBytes) }
        assertTrue(c.state.preview!!.hasPlaces)
        c.confirm()
        assertEquals(1, places.restored)
        assertEquals(25, storage.values["navigation/voice_volume"])
        val outcome = c.state.outcome as BackupOutcome.Restored
        assertEquals(2, outcome.places!!.placesAdded)
    }

    @Test fun ifThePlacesPartFailsNoSettingChanges() {
        val settings = SettingsBackup.export(MemoryStorage(mapOf("navigation/voice_volume" to 25)), emptyList(), "1", 0L)
        val zipBytes = ByteArrayOutputStream().also { o ->
            java.util.zip.ZipOutputStream(o).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry(SettingsFile.PLACES_ENTRY)); z.write("{}".toByteArray()); z.closeEntry()
                z.putNextEntry(java.util.zip.ZipEntry(SettingsFile.ENTRY)); z.write(settings.toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        places.failOnRestore = true
        val c = coordinator()
        c.load { ByteArrayInputStream(zipBytes) }
        c.confirm()
        assertEquals(BackupOutcome.Failed, c.state.outcome)
        assertEquals(50, storage.values["navigation/voice_volume"])
        assertTrue(pending.consent.isEmpty())
    }

    @Test fun pendingConsentIsHiddenOnceTheSwitchIsOn() {
        pending.consent = setOf("cameras/fixed", "fuel/enabled")
        val c = coordinator()
        assertEquals(listOf("cameras/fixed", "fuel/enabled"), c.pendingConsent().map { it.id })
        storage.values["cameras/fixed"] = true // the owner accepted the notice and turned it on
        assertEquals(listOf("fuel/enabled"), c.pendingConsent().map { it.id })
        c.dismissPending()
        assertTrue(c.pendingConsent().isEmpty())
        assertTrue(pending.consent.isEmpty())
    }
}
