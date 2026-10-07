package com.qtekfun.mapas.settings.backup

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** In-memory [SettingsStorage]: values by spec id, defaults when unset. */
class MemoryStorage(initial: Map<String, Any> = emptyMap()) : SettingsStorage {
    val values = LinkedHashMap(initial)
    var finished = 0
    var writes = 0

    override fun read(spec: SettingSpec): Any = values[spec.id] ?: spec.default

    override fun write(spec: SettingSpec, value: Any) {
        writes++
        values[spec.id] = value
    }

    override fun finish() { finished++ }

    fun snapshot(): Map<String, Any> = SettingsSchema.specs.associate { it.id to read(it) }
}

/** A value different from the default for every spec (consent switches are on only when [consentOn]). */
fun nonDefaultValue(spec: SettingSpec, consentOn: Boolean): Any = when (spec.type) {
    SettingType.BOOLEAN -> if (spec.policy == RestorePolicy.NEEDS_CONSENT) consentOn else !(spec.default as Boolean)
    SettingType.INT -> when (spec.key) {
        "voice_volume" -> 50
        "refresh_minutes" -> 180
        "incident_refresh_minutes" -> 30
        else -> error("no non-default value for ${spec.id}")
    }
    SettingType.STRING -> when (spec.key) {
        "units" -> "IMPERIAL"
        "voice_language" -> "EN"
        "bike_cycleways" -> "STRONGLY_PREFER"
        "map_fuel" -> "g95e5"
        "source_url" -> "https://fuel.example.org/api/"
        "catalog_url" -> "https://maps.example.org/catalog.json"
        else -> error("no non-default value for ${spec.id}")
    }
    SettingType.STRING_SET -> setOf("g95e5", "goa")
}

@RunWith(RobolectricTestRunner::class) // org.json needs the Android implementation
@Config(sdk = [34])
class SettingsBackupTest {
    private val now = 1_790_000_000_000L // a fixed instant: the tests never read the clock

    private fun everythingChanged(consentOn: Boolean) =
        SettingsSchema.specs.associate { it.id to nonDefaultValue(it, consentOn) }

    private fun text(storage: SettingsStorage, regions: Collection<String> = emptyList(), pending: Set<String> = emptySet()) =
        SettingsBackup.export(storage, regions, "0.1.0-rc.7", now, pending)

    @Test fun theNonDefaultFixtureCoversEveryKeyOfTheSchema() {
        // If a key is added to the schema without a fixture value, this fails and the round trip below would be weaker.
        SettingsSchema.specs.forEach { assertTrue(it.type.matches(nonDefaultValue(it, true)), it.id) }
    }

    @Test fun exportThenImportIntoAFreshStoreGivesIdenticalSettings() {
        val a = MemoryStorage(everythingChanged(consentOn = false))
        val file = SettingsBackup.parse(text(a, listOf("spain-madrid", "spain-galicia")))
        val b = MemoryStorage()
        val result = SettingsBackup.apply(file, b, InMemoryPendingRestore())
        assertEquals(a.snapshot(), b.snapshot())
        assertEquals(SettingsSchema.specs.size, result.restored)
        assertEquals(0, result.skipped)
        assertEquals(0, result.ignored)
        assertEquals(0, result.needConsent)
        assertEquals(1, b.finished)
    }

    @Test fun consentSwitchesAreRememberedAsIntentsNeverTurnedOn() {
        val a = MemoryStorage(everythingChanged(consentOn = true))
        val file = SettingsBackup.parse(text(a))
        val b = MemoryStorage()
        val pending = InMemoryPendingRestore()
        val result = SettingsBackup.apply(file, b, pending)

        val consentIds = SettingsSchema.specs.filter { it.policy == RestorePolicy.NEEDS_CONSENT }.map { it.id }.toSet()
        assertEquals(setOf("cameras/fixed", "cameras/mobile_zones", "cameras/incidents", "cameras/v16", "cameras/roadworks", "fuel/enabled"), consentIds)
        consentIds.forEach { assertEquals(false, b.read(SettingsSchema.byId(it)!!), "$it must stay off") }
        assertEquals(consentIds, pending.consent)
        assertEquals(consentIds.size, result.needConsent)
        // Everything else is restored exactly.
        val rest = SettingsSchema.specs.filter { it.id !in consentIds }
        rest.forEach { assertEquals(a.read(it), b.read(it), it.id) }
        // The acknowledgement is not a key of the schema at all, so it can never be restored.
        assertEquals(null, SettingsSchema.find("cameras", "acknowledged"))
    }

    @Test fun anOffSwitchInTheFileTurnsAnEnabledOneOff() {
        val file = SettingsBackup.parse(text(MemoryStorage()))
        val b = MemoryStorage(mapOf("cameras/fixed" to true, "fuel/enabled" to true))
        SettingsBackup.apply(file, b, InMemoryPendingRestore(setOf("cameras/fixed")))
        assertEquals(false, b.values["cameras/fixed"])
        assertEquals(false, b.values["fuel/enabled"])
    }

    @Test fun anOnSwitchThatIsAlreadyOnHereNeedsNoIntent() {
        val file = SettingsBackup.parse(text(MemoryStorage(mapOf("cameras/v16" to true))))
        val b = MemoryStorage(mapOf("cameras/v16" to true))
        val pending = InMemoryPendingRestore()
        val result = SettingsBackup.apply(file, b, pending)
        assertTrue(pending.consent.isEmpty())
        assertEquals(0, result.needConsent)
        assertEquals(true, b.values["cameras/v16"])
    }

    @Test fun exportingAgainBeforeAcceptingKeepsTheIntent() {
        val s = MemoryStorage()
        val again = SettingsBackup.parse(text(s, pending = setOf("cameras/fixed", "fuel/enabled")))
        assertEquals(true, again.values["cameras/fixed"])
        assertEquals(true, again.values["fuel/enabled"])
        assertEquals(false, again.values["cameras/v16"])
    }

    @Test fun theTextIsDeterministicAndSorted() {
        val values = everythingChanged(consentOn = false)
        val forward = text(MemoryStorage(values), listOf("b", "a"))
        val backward = text(MemoryStorage(LinkedHashMap(values.entries.reversed().associate { it.key to it.value })), listOf("a", "b"))
        assertEquals(forward, backward)
        val groups = Regex("^    \"(\\w+)\": \\{$", RegexOption.MULTILINE).findAll(forward).map { it.groupValues[1] }.toList()
        assertEquals(groups.sorted(), groups)
        assertEquals(SettingsSchema.specs.map { it.group }.distinct(), groups)
        assertTrue(forward.contains("\"created_at\": \"2026-09-21T"), forward.lines().first { "created_at" in it })
        assertTrue(forward.contains("\"installed_regions\": [\"a\", \"b\"]"))
    }

    @Test fun theFileCarriesNoLocationsAndNoSecrets() {
        val t = text(MemoryStorage(everythingChanged(consentOn = true)), listOf("spain-madrid"))
        for (banned in listOf("acknowledged", "lat", "\"lon\"", "zoom", "bearing", "tilt", "install_location", "default_list_id", "password", "token", "isolated")) {
            assertFalse(t.contains(banned), "the file must not mention $banned")
        }
        // Every key written is in the whitelist.
        val written = Regex("^      \"(\\w+)\": \\{\"type\"", RegexOption.MULTILINE).findAll(t).count()
        assertEquals(SettingsSchema.specs.size, written)
    }

    @Test fun unknownKeysAndGroupsAreIgnoredAndCounted() {
        val doc = """{"format":"ultimatemaps-settings","schema":1,"app_version":"9","created_at":"x",
            "settings":{"navigation":{"voice_enabled":{"type":"bool","value":false},"hologram":{"type":"bool","value":true}},
            "teleport":{"x":{"type":"int","value":1}},"stray":5},
            "extra_top_level":[1,2],"installed_regions":[]}"""
        val file = SettingsBackup.parse(doc)
        assertEquals(mapOf("navigation/voice_enabled" to false), file.values)
        assertEquals(3, file.ignored) // hologram, group teleport, group stray
        assertEquals(0, file.skipped)
        val s = MemoryStorage()
        val result = SettingsBackup.apply(file, s, InMemoryPendingRestore())
        assertEquals(1, result.restored)
        assertEquals(false, s.read(SettingsSchema.find("navigation", "voice_enabled")!!))
    }

    @Test fun wrongTypesAndUnacceptableValuesAreSkippedAndCountedNeverFatal() {
        val doc = """{"format":"ultimatemaps-settings","schema":1,"settings":{
            "navigation":{
              "voice_enabled":{"type":"int","value":1},
              "voice_important_only":{"type":"bool","value":"yes"},
              "voice_volume":{"type":"int","value":5},
              "units":{"type":"string","value":"FURLONGS"},
              "voice_language":{"type":"string","value":"EN"},
              "avoid_tolls":{"type":"bool"},
              "avoid_ferries":7,
              "avoid_unpaved":{"type":"bool","value":null},
              "view_3d":{"type":"bool","value":false}},
            "fuel":{
              "source_url":{"type":"string","value":"http://insecure.example.org/"},
              "fuels":{"type":"string_set","value":["g95e5",3]},
              "refresh_minutes":{"type":"int","value":2.5},
              "map_fuel":{"type":"string","value":"unobtainium"}},
            "regions":{"catalog_url":{"type":"string","value":"ftp://x"},"offline_mode":{"type":"bool","value":true}}},
            "installed_regions":["ok-id","../evil",12,""]}"""
        val file = SettingsBackup.parse(doc)
        assertEquals(
            mapOf("navigation/voice_language" to "EN", "navigation/view_3d" to false, "regions/offline_mode" to true),
            file.values,
        )
        // 9 bad settings entries (voice_enabled, important_only, volume, units, tolls, ferries, unpaved, url, fuels,
        // refresh, map_fuel, catalog_url = 12) plus 3 bad region ids.
        assertEquals(12 + 3, file.skipped)
        assertEquals(listOf("ok-id"), file.regions)
    }

    @Test fun fuelsKeepOnlyKnownFuelsAndAnEmptyCatalogUrlIsAccepted() {
        val doc = """{"format":"ultimatemaps-settings","schema":1,"settings":{
            "fuel":{"fuels":{"type":"string_set","value":["g95e5","unobtainium"]}},
            "regions":{"catalog_url":{"type":"string","value":""}}}}"""
        val file = SettingsBackup.parse(doc)
        assertEquals(setOf("g95e5"), file.values["fuel/fuels"])
        assertEquals("", file.values["regions/catalog_url"])
        assertEquals(0, file.skipped)
    }

    @Test fun aFileFromAnOlderOrSmallerSchemaOnlyTouchesWhatItHas() {
        val doc = """{"format":"ultimatemaps-settings","schema":1,"settings":{"history":{"enabled":{"type":"bool","value":false}}}}"""
        val s = MemoryStorage(mapOf("navigation/voice_volume" to 25))
        SettingsBackup.apply(SettingsBackup.parse(doc), s, InMemoryPendingRestore())
        assertEquals(false, s.values["history/enabled"])
        assertEquals(25, s.values["navigation/voice_volume"])
        assertEquals(1, s.writes)
    }

    @Test fun aNewerSchemaIsRejectedWithAClearMessageAndNothingChanges() {
        val doc = """{"format":"ultimatemaps-settings","schema":2,"settings":{"history":{"enabled":{"type":"bool","value":false}}}}"""
        val e = assertFailsWith<SettingsBackupException> { SettingsBackup.parse(doc) }
        assertEquals(RejectReason.NEWER_SCHEMA, e.reason)
        assertEquals(2, e.fileSchema)
        assertTrue("newer" in e.message!! && "update the app" in e.message!!, e.message)
    }

    @Test fun corruptAndForeignFilesAreRejectedWithoutTouchingAnything() {
        val cases = mapOf(
            "" to RejectReason.CORRUPT,
            "{" to RejectReason.CORRUPT,
            "[]" to RejectReason.CORRUPT,
            "PK\u0003\u0004 not json" to RejectReason.CORRUPT,
            """{"a":1}""" to RejectReason.NOT_A_SETTINGS_FILE,
            """{"format":"something-else","schema":1,"settings":{}}""" to RejectReason.NOT_A_SETTINGS_FILE,
            """{"format":"ultimatemaps-settings","settings":{}}""" to RejectReason.CORRUPT,
            """{"format":"ultimatemaps-settings","schema":"1","settings":{}}""" to RejectReason.CORRUPT,
            """{"format":"ultimatemaps-settings","schema":0,"settings":{}}""" to RejectReason.CORRUPT,
            """{"format":"ultimatemaps-settings","schema":1}""" to RejectReason.CORRUPT,
            """{"format":"ultimatemaps-settings","schema":1,"settings":{"history":{"enabled":""" to RejectReason.CORRUPT,
            "x".repeat(SettingsBackup.MAX_BYTES + 1) to RejectReason.TOO_LARGE,
        )
        for ((doc, reason) in cases) {
            val e = assertFailsWith<SettingsBackupException>("rejected: ${doc.take(40)}") { SettingsBackup.parse(doc) }
            assertEquals(reason, e.reason, doc.take(40))
        }
    }

    @Test fun planListsExactlyTheDifferences() {
        val file = SettingsBackup.parse(
            text(MemoryStorage(mapOf("navigation/voice_volume" to 50, "navigation/units" to "IMPERIAL", "cameras/fixed" to true, "history/enabled" to false)), listOf("r1")),
        )
        val here = MemoryStorage(mapOf("navigation/voice_volume" to 50, "history/enabled" to true))
        val plan = SettingsBackup.plan(file, here)
        assertEquals(setOf("navigation/units", "history/enabled"), plan.changes.map { it.spec.id }.toSet())
        assertEquals(listOf("cameras/fixed"), plan.consent.map { it.id })
        assertEquals(SettingsSchema.specs.size - 3, plan.unchanged)
        assertEquals(listOf("r1"), plan.regions)
        assertEquals(0, here.writes) // planning writes nothing
    }

    @Test fun regionsOfTheFileAreRememberedButNothingElseHappens() {
        val file = SettingsBackup.parse(text(MemoryStorage(), listOf("spain-madrid", "spain-galicia")))
        val pending = InMemoryPendingRestore(regions = setOf("old"))
        val result = SettingsBackup.apply(file, MemoryStorage(), pending)
        assertEquals(setOf("spain-madrid", "spain-galicia"), pending.regions)
        assertEquals(2, result.regions)
        // A file without regions leaves an earlier offer alone.
        val none = SettingsBackup.parse(text(MemoryStorage()))
        SettingsBackup.apply(none, MemoryStorage(), pending)
        assertEquals(setOf("spain-madrid", "spain-galicia"), pending.regions)
    }

    @Test fun everyDefaultMatchesItsType() {
        // SettingSpec checks this on construction; this keeps the intent visible.
        SettingsSchema.specs.forEach { assertTrue(it.type.matches(it.default), it.id) }
    }
}
