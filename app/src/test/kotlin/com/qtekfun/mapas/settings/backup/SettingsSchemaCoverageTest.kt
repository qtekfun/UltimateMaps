package com.qtekfun.mapas.settings.backup

import com.qtekfun.mapas.cameras.PrefsCameraSettingsStore
import com.qtekfun.mapas.fuel.PrefsFuelSettingsStore
import com.qtekfun.mapas.map.PrefsCameraStateStore
import com.qtekfun.mapas.nav.SharedNavUiPrefs
import com.qtekfun.mapas.recording.PrefsRecordingSettings
import com.qtekfun.mapas.regions.RegionsController
import com.qtekfun.mapas.search.CoMapsSearchBackend
import com.qtekfun.mapas.search.PrefsHistorySettings
import com.qtekfun.mapas.settings.PrefsNavSettingsStore
import com.qtekfun.mapas.transit.follow.PrefsTransitTripSettings
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A setting that is added later must not silently miss the backup: every preference key read by a store must be in
 * the [SettingsSchema] whitelist or in its explicit [SettingsSchema.excluded] allow-list (with a reason).
 */
class SettingsSchemaCoverageTest {
    private val keyName = Regex("KEY_.*|PREF_.*")

    /** `prefsName/key` for every String constant named KEY_* or PREF_* of [cls] (private ones included), plus [extraNames]. */
    private fun keysOf(cls: Class<*>, prefsName: String, extraNames: Set<String> = emptySet()): Set<String> =
        cls.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java && (keyName.matches(it.name) || it.name in extraNames) }
            .onEach { it.isAccessible = true }
            .map { "$prefsName/${it.get(null)}" }
            .toSet()

    private val knownKeys: Set<String> = buildSet {
        addAll(keysOf(PrefsNavSettingsStore::class.java, PrefsNavSettingsStore.PREFS))
        addAll(keysOf(PrefsFuelSettingsStore::class.java, PrefsFuelSettingsStore.PREFS))
        addAll(keysOf(PrefsTransitTripSettings::class.java, PrefsTransitTripSettings.PREFS))
        addAll(keysOf(PrefsCameraSettingsStore::class.java, PrefsCameraSettingsStore.PREFS))
        addAll(keysOf(PrefsHistorySettings::class.java, PrefsHistorySettings.PREFS))
        addAll(keysOf(PrefsRecordingSettings::class.java, PrefsRecordingSettings.PREFS))
        addAll(keysOf(SharedNavUiPrefs::class.java, SharedNavUiPrefs.PREFS))
        addAll(keysOf(RegionsController::class.java, RegionsController.PREFS))
        addAll(keysOf(PrefsPendingRestore::class.java, PrefsPendingRestore.PREFS))
        addAll(keysOf(CoMapsSearchBackend::class.java, CoMapsSearchBackend.PREFS))
        addAll(keysOf(PrefsCameraStateStore::class.java, "camera", setOf("LAT", "LON", "ZOOM", "BEARING", "TILT")).map { it.lowercase() }
            .map { it.substringBefore('/') + "/" + it.substringAfter('/') })
        addAll(keysOf(Class.forName("com.qtekfun.mapas.places.AndroidPlacesKt"), "places"))
    }

    private val covered: Set<String> =
        SettingsSchema.specs.map { "${it.prefsName}/${it.key}" }.toSet() + SettingsSchema.excluded.keys

    @Test fun everyKeyReadByAStoreIsExportedOrDeliberatelyExcluded() {
        // Sanity: the reflection really found the stores' keys (a rename of KEY_* would otherwise hide a gap).
        assertTrue(knownKeys.size >= 40, "only ${knownKeys.size} keys found: $knownKeys")
        val missing = knownKeys - covered
        assertTrue(missing.isEmpty(), "preference keys neither exported nor in SettingsSchema.excluded: $missing")
    }

    @Test fun theSchemaAndTheExclusionsDoNotOverlapAndHaveNoDeadEntries() {
        val exported = SettingsSchema.specs.map { "${it.prefsName}/${it.key}" }.toSet()
        assertTrue(exported.intersect(SettingsSchema.excluded.keys).isEmpty(), "a key cannot be both exported and excluded")
        assertEquals(emptySet(), exported - knownKeys, "schema entries that no store reads")
        assertEquals(emptySet(), SettingsSchema.excluded.keys - knownKeys, "excluded entries that no store reads")
        assertTrue(SettingsSchema.excluded.values.all { it.isNotBlank() }, "every exclusion needs a reason")
    }

    @Test fun theConsentAcknowledgementAndPositionsAreNeverExported() {
        val exportedKeys = SettingsSchema.specs.map { it.key }.toSet()
        assertTrue("acknowledged" !in exportedKeys)
        assertTrue(listOf("lat", "lon", "zoom", "bearing", "tilt").none { it in exportedKeys })
    }

    /** A preference file opened anywhere else in the code base must be known here, so it cannot be forgotten. */
    @Test fun noPreferenceFileIsOpenedOutsideTheKnownFiles() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val skip = setOf("build", "third_party", ".git", ".gradle", ".claude", "spike", "test", "androidTest")
        val found = root.walkTopDown()
            .onEnter { it.name !in skip }
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("getSharedPreferences(") }
            .map { it.name }
            .toSortedSet()
        val expected = sortedSetOf(
            "AndroidNavServiceControl.kt", "AndroidPlaces.kt", "AndroidSettingsStorage.kt", "CoMapsSearchBackend.kt", "MapasApp.kt",
            "PendingRestore.kt", "PrefsCameraSettingsStore.kt", "PrefsCameraStateStore.kt", "PrefsFuelSettingsStore.kt",
            "PrefsNavSettingsStore.kt", "RecordingController.kt", "RegionsController.kt", "SearchHistory.kt", "TransitTripSettings.kt",
        )
        assertEquals(expected, found, "a new SharedPreferences file needs a SettingsSchema entry or an exclusion, then add its file here")
    }
}
