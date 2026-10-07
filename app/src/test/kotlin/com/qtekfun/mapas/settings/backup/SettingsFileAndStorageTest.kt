package com.qtekfun.mapas.settings.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.mapas.cameras.PrefsCameraSettingsStore
import com.qtekfun.mapas.core.cameras.CameraSettings
import com.qtekfun.mapas.core.fuel.FuelSettings
import com.qtekfun.mapas.core.voice.NavSettings
import com.qtekfun.mapas.core.voice.UnitsPref
import com.qtekfun.mapas.fuel.PrefsFuelSettingsStore
import com.qtekfun.mapas.settings.PrefsNavSettingsStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun zip(vararg entries: Pair<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
        for ((name, body) in entries) {
            z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry()
        }
    }
    return out.toByteArray()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsFileTest {
    private val json = SettingsBackup.export(MemoryStorage(mapOf("history/enabled" to false)), listOf("r1"), "1.0", 0L)

    @Test fun plainJsonIsASettingsFileWithoutPlaces() {
        val c = SettingsFile.read(ByteArrayInputStream(json.toByteArray()))
        assertFalse(c.hasPlaces)
        assertEquals(false, c.snapshot!!.values["history/enabled"])
    }

    @Test fun theCombinedZipHasBothHalves() {
        val c = SettingsFile.read(ByteArrayInputStream(zip(SettingsFile.PLACES_ENTRY to "{}", SettingsFile.ENTRY to json)))
        assertTrue(c.hasPlaces)
        assertEquals(listOf("r1"), c.snapshot!!.regions)
    }

    @Test fun aPlacesOnlyZipHasNoSettings() {
        val c = SettingsFile.read(ByteArrayInputStream(zip(SettingsFile.PLACES_ENTRY to "{}")))
        assertTrue(c.hasPlaces)
        assertNull(c.snapshot)
    }

    @Test fun aZipWithNeitherIsRejected() {
        val e = assertFailsWith<SettingsBackupException> { SettingsFile.read(ByteArrayInputStream(zip("other.txt" to "x"))) }
        assertEquals(RejectReason.NOT_A_SETTINGS_FILE, e.reason)
    }

    @Test fun aBrokenZipOrBrokenSettingsEntryIsRejected() {
        val bytes = zip(SettingsFile.ENTRY to json)
        val cut = bytes.copyOf(bytes.size / 2)
        assertFailsWith<SettingsBackupException> { SettingsFile.read(ByteArrayInputStream(cut)) }
        val bad = zip(SettingsFile.ENTRY to "{ not json")
        assertEquals(RejectReason.CORRUPT, assertFailsWith<SettingsBackupException> { SettingsFile.read(ByteArrayInputStream(bad)) }.reason)
    }

    @Test fun aNewerSchemaInsideTheZipIsRejected() {
        val newer = json.replace("\"schema\": 1", "\"schema\": 7")
        val e = assertFailsWith<SettingsBackupException> { SettingsFile.read(ByteArrayInputStream(zip(SettingsFile.ENTRY to newer))) }
        assertEquals(RejectReason.NEWER_SCHEMA, e.reason)
    }

    @Test fun anEmptyStreamIsRejected() {
        assertFailsWith<SettingsBackupException> { SettingsFile.read(ByteArrayInputStream(ByteArray(0))) }
    }

    @Test fun fileNamesCarryTheDate() {
        assertEquals("ultimatemaps-settings-20261007.json", SettingsFile.settingsName("20261007"))
        assertEquals("ultimatemaps-backup-20261007.zip", SettingsFile.everythingName("20261007"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSettingsStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Each test run gets its own preference files, named after [tag]. */
    private fun storage(tag: String, overrides: Map<String, (Any) -> Unit> = emptyMap(), onFinish: () -> Unit = {}) =
        AndroidSettingsStorage({ name -> context.getSharedPreferences("$tag-$name", Context.MODE_PRIVATE) }, overrides, onFinish)

    @Test fun everyTypeRoundTripsThroughRealPreferences() {
        val a = storage("rt-a")
        SettingsSchema.specs.forEach { a.write(it, nonDefaultValue(it, consentOn = true)) }
        a.finish()
        SettingsSchema.specs.forEach { assertEquals(nonDefaultValue(it, true), a.read(it), it.id) }

        val file = SettingsBackup.parse(SettingsBackup.export(a, emptyList(), "1", 0L))
        val b = storage("rt-b")
        SettingsBackup.apply(file, b, InMemoryPendingRestore())
        SettingsSchema.specs.filter { it.policy == RestorePolicy.DIRECT }.forEach { assertEquals(a.read(it), b.read(it), it.id) }
    }

    @Test fun unsetAndDamagedValuesReadAsTheDefault() {
        val s = storage("damaged")
        val spec = SettingsSchema.find("navigation", "voice_volume")!!
        assertEquals(spec.default, s.read(spec))
        context.getSharedPreferences("damaged-${spec.prefsName}", Context.MODE_PRIVATE).edit().putString(spec.key, "loud").commit()
        assertEquals(spec.default, s.read(spec)) // a String where an Int is expected
    }

    @Test fun overridesReceiveTheValueAndNothingIsWrittenToThePreferences() {
        val seen = ArrayList<Any>()
        val s = storage("override", overrides = mapOf("regions/offline_mode" to { v: Any -> seen += v; Unit }))
        val spec = SettingsSchema.find("regions", "offline_mode")!!
        s.write(spec, true)
        s.finish()
        assertEquals(listOf<Any>(true), seen)
        assertFalse(context.getSharedPreferences("override-${spec.prefsName}", Context.MODE_PRIVATE).contains(spec.key))
    }

    @Test fun finishFlushesAndNotifiesOnce() {
        var finished = 0
        val s = storage("finish", onFinish = { finished++ })
        s.write(SettingsSchema.find("history", "enabled")!!, false)
        s.finish()
        assertEquals(1, finished)
        assertEquals(false, s.read(SettingsSchema.find("history", "enabled")!!))
    }

    @Test fun theLiveStoresRereadTheFilesAfterARestore() {
        fun prefs(tag: String, name: String) = context.getSharedPreferences("$tag-$name", Context.MODE_PRIVATE)
        // The old phone: stores written through their own API.
        val oldNav = PrefsNavSettingsStore(prefs("old", PrefsNavSettingsStore.PREFS))
        oldNav.update { it.copy(units = UnitsPref.IMPERIAL, volumePercent = 50, avoidTolls = true) }
        val oldFuel = PrefsFuelSettingsStore(prefs("old", PrefsFuelSettingsStore.PREFS))
        oldFuel.update { it.copy(enabled = true, downloadedFuels = setOf("g95e5"), refreshMinutes = 180) }
        val oldCameras = PrefsCameraSettingsStore(prefs("old", PrefsCameraSettingsStore.PREFS))
        oldCameras.update { it.copy(acknowledged = true, fixedEnabled = true, warnOnlyIfSpeeding = true, incidentRefreshMinutes = 30) }
        val exported = SettingsBackup.export(storage("old"), emptyList(), "1", 0L)

        // The new phone: stores created BEFORE the restore (as the app does), and re-read by onFinish.
        val nav = PrefsNavSettingsStore(prefs("new", PrefsNavSettingsStore.PREFS))
        val fuel = PrefsFuelSettingsStore(prefs("new", PrefsFuelSettingsStore.PREFS))
        val cameras = PrefsCameraSettingsStore(prefs("new", PrefsCameraSettingsStore.PREFS))
        assertEquals(NavSettings(), nav.settings.value)
        val pending = InMemoryPendingRestore()
        val target = storage("new", onFinish = { listOf(nav, fuel, cameras).forEach { it.reload() } })
        val result = SettingsBackup.apply(SettingsBackup.parse(exported), target, pending)

        assertEquals(NavSettings(units = UnitsPref.IMPERIAL, volumePercent = 50, avoidTolls = true), nav.settings.value)
        // Preferences restored; the consent-gated switch stays off and the notice stays unaccepted.
        assertEquals(FuelSettings(enabled = false, downloadedFuels = setOf("g95e5"), mapFuel = "g95e5", refreshMinutes = 180), fuel.settings.value)
        assertEquals(CameraSettings(warnOnlyIfSpeeding = true, incidentRefreshMinutes = 30), cameras.settings.value)
        assertFalse(cameras.settings.value.acknowledged)
        assertEquals(setOf("fuel/enabled", "cameras/fixed"), pending.consent)
        assertEquals(2, result.needConsent)
        assertNotNull(pending)
    }
}
