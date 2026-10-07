package com.qtekfun.ultimatemaps.settings.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Why a settings file was refused. Nothing is changed on the device when one of these is thrown. */
enum class RejectReason { NOT_A_SETTINGS_FILE, CORRUPT, NEWER_SCHEMA, TOO_LARGE }

class SettingsBackupException(val reason: RejectReason, message: String, val fileSchema: Int? = null) : Exception(message)

/** Where the current values live (and where restored ones go). The Android one is [AndroidSettingsStorage]. */
interface SettingsStorage {
    /** The current value of [spec], or its default when it was never set or holds something of another type. */
    fun read(spec: SettingSpec): Any

    fun write(spec: SettingSpec, value: Any)

    /** Called once after the last [write] of a restore (flush, tell live objects to re-read). */
    fun finish() {}
}

/**
 * A parsed settings file: only whitelisted keys with acceptable values ([values], by [SettingSpec.id]), the ids of the
 * installed regions, and how many entries were [skipped] (wrong type or unacceptable value) or [ignored] (unknown).
 */
class SettingsSnapshot(
    val schema: Int,
    val appVersion: String,
    val createdAt: String,
    val values: Map<String, Any>,
    val regions: List<String>,
    val skipped: Int,
    val ignored: Int,
)

class Change(val spec: SettingSpec, val from: Any, val to: Any)

/** What an import would do, shown to the owner before anything is written. */
class RestorePlan(
    /** Values that differ from the current ones and will be written. */
    val changes: List<Change>,
    /** Switches that were on in the file and need the owner's acceptance again: nothing is turned on for them. */
    val consent: List<SettingSpec>,
    /** Accepted values equal to the current ones. */
    val unchanged: Int,
    val skipped: Int,
    val ignored: Int,
    val regions: List<String>,
)

class RestoreResult(
    /** Values that now equal the file's (written or already equal). */
    val restored: Int,
    /** Switches left off until the owner accepts them again. */
    val needConsent: Int,
    val skipped: Int,
    val ignored: Int,
    val regions: Int,
)

/** Export, parse, plan and apply of the settings file. Pure over [SettingsStorage]: no Android types. */
object SettingsBackup {
    const val FORMAT = "ultimatemaps-settings"
    const val MAX_BYTES = 1024 * 1024
    private const val MAX_REGIONS = 5000
    private val REGION_ID = Regex("[A-Za-z0-9_.-]{1,100}")

    // ------------------------------------------------------------ Export

    /**
     * The file text. Order is deterministic (groups and keys sorted, sets sorted), so the same settings always give
     * the same text apart from [nowMillis]. A switch that still waits for the owner's acceptance ([pendingConsent])
     * is exported as on, so exporting twice never loses the intent.
     */
    fun export(
        storage: SettingsStorage,
        installedRegions: Collection<String>,
        appVersion: String,
        nowMillis: Long,
        pendingConsent: Set<String> = emptySet(),
    ): String {
        val values = SettingsSchema.specs.associateWith { spec ->
            if (spec.policy == RestorePolicy.NEEDS_CONSENT && spec.id in pendingConsent) true else storage.read(spec)
        }
        val created = Instant.ofEpochMilli(nowMillis).truncatedTo(ChronoUnit.SECONDS).toString()
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"format\": ").append(quote(FORMAT)).append(",\n")
        sb.append("  \"schema\": ").append(SettingsSchema.VERSION).append(",\n")
        sb.append("  \"app_version\": ").append(quote(appVersion)).append(",\n")
        sb.append("  \"created_at\": ").append(quote(created)).append(",\n")
        sb.append("  \"settings\": {\n")
        val groups = values.keys.groupBy { it.group }
        groups.entries.forEachIndexed { gi, (group, specs) ->
            sb.append("    ").append(quote(group)).append(": {\n")
            specs.forEachIndexed { si, spec ->
                sb.append("      ").append(quote(spec.key)).append(": {\"type\": ").append(quote(spec.type.json))
                    .append(", \"value\": ").append(valueJson(values.getValue(spec))).append("}")
                sb.append(if (si < specs.lastIndex) ",\n" else "\n")
            }
            sb.append("    }").append(if (gi < groups.size - 1) ",\n" else "\n")
        }
        sb.append("  },\n")
        sb.append("  \"installed_regions\": ").append(
            installedRegions.filter { REGION_ID.matches(it) }.toSortedSet().joinToString(", ", "[", "]") { quote(it) },
        ).append("\n}\n")
        return sb.toString()
    }

    private fun valueJson(v: Any): String = when (v) {
        is Boolean, is Int -> v.toString()
        is String -> quote(v)
        is Set<*> -> v.filterIsInstance<String>().sorted().joinToString(", ", "[", "]") { quote(it) }
        else -> error("unsupported value type ${v::class}")
    }

    private fun quote(s: String): String = JSONObject.quote(s)

    // ------------------------------------------------------------ Parse

    /**
     * Parses [text]. Throws [SettingsBackupException] for a file that is not a settings file, is corrupt, or comes
     * from a newer format; never for unknown keys (ignored) or wrong types (skipped and counted).
     */
    fun parse(text: String): SettingsSnapshot {
        if (text.length > MAX_BYTES) throw SettingsBackupException(RejectReason.TOO_LARGE, "settings file too large")
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw SettingsBackupException(RejectReason.CORRUPT, "not valid JSON: ${e.message}")
        }
        if (root.optString("format") != FORMAT) throw SettingsBackupException(RejectReason.NOT_A_SETTINGS_FILE, "not a settings file")
        val schema = root.opt("schema") as? Int ?: throw SettingsBackupException(RejectReason.CORRUPT, "missing schema number")
        if (schema > SettingsSchema.VERSION) {
            throw SettingsBackupException(
                RejectReason.NEWER_SCHEMA,
                "settings file schema $schema is newer than the supported ${SettingsSchema.VERSION}; update the app",
                fileSchema = schema,
            )
        }
        if (schema < 1) throw SettingsBackupException(RejectReason.CORRUPT, "invalid schema number $schema")

        var skipped = 0
        var ignored = 0
        val values = LinkedHashMap<String, Any>()
        val settings = root.optJSONObject("settings") ?: throw SettingsBackupException(RejectReason.CORRUPT, "missing settings")
        for (group in settings.keys()) {
            val entries = settings.optJSONObject(group)
            if (entries == null) { ignored++; continue }
            for (key in entries.keys()) {
                val spec = SettingsSchema.find(group, key)
                if (spec == null) { ignored++; continue }
                val value = decode(spec, entries.optJSONObject(key))?.let(spec.sanitize)
                if (value == null || !spec.type.matches(value)) skipped++ else values[spec.id] = value
            }
        }

        val regions = LinkedHashSet<String>()
        root.optJSONArray("installed_regions")?.let { array ->
            for (i in 0 until array.length()) {
                val id = array.opt(i) as? String
                if (id != null && REGION_ID.matches(id) && regions.size < MAX_REGIONS) regions += id else skipped++
            }
        }
        return SettingsSnapshot(
            schema = schema,
            appVersion = root.optString("app_version"),
            createdAt = root.optString("created_at"),
            values = values,
            regions = regions.toList(),
            skipped = skipped,
            ignored = ignored,
        )
    }

    /** The typed value of an entry, or null when the entry is malformed or its type is not the key's type. */
    private fun decode(spec: SettingSpec, entry: JSONObject?): Any? {
        if (entry == null || entry.opt("type") != spec.type.json) return null
        val raw = entry.opt("value")
        return when (spec.type) {
            SettingType.BOOLEAN -> raw as? Boolean
            SettingType.INT -> when (raw) {
                is Int -> raw
                is Long -> raw.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
                else -> null
            }
            SettingType.STRING -> raw as? String
            SettingType.STRING_SET -> (raw as? JSONArray)?.let { array ->
                val out = LinkedHashSet<String>()
                for (i in 0 until array.length()) out += array.opt(i) as? String ?: return null
                out
            }
        }
    }

    // ------------------------------------------------------------ Plan and apply

    fun plan(snapshot: SettingsSnapshot, storage: SettingsStorage): RestorePlan {
        val changes = ArrayList<Change>()
        val consent = ArrayList<SettingSpec>()
        var unchanged = 0
        for (spec in SettingsSchema.specs) {
            val value = snapshot.values[spec.id] ?: continue
            val current = storage.read(spec)
            when {
                spec.policy == RestorePolicy.NEEDS_CONSENT && value == true -> if (current == true) unchanged++ else consent += spec
                value == current -> unchanged++
                else -> changes += Change(spec, current, value)
            }
        }
        return RestorePlan(changes, consent, unchanged, snapshot.skipped, snapshot.ignored, snapshot.regions)
    }

    /**
     * Writes the plan: direct values are written, "on" values that need consent are only remembered in [pending]
     * (nothing is turned on, so nothing starts a connection), and the regions of the file are remembered for the Maps
     * screen to offer. Nothing downloads here.
     */
    fun apply(snapshot: SettingsSnapshot, storage: SettingsStorage, pending: PendingRestore): RestoreResult {
        val plan = plan(snapshot, storage)
        try {
            plan.changes.forEach { storage.write(it.spec, it.to) }
        } finally {
            storage.finish()
        }
        val consentSpecsInFile = SettingsSchema.specs.filter { it.policy == RestorePolicy.NEEDS_CONSENT && it.id in snapshot.values }
        pending.consent = (pending.consent - consentSpecsInFile.map { it.id }.toSet()) + plan.consent.map { it.id }
        if (snapshot.regions.isNotEmpty()) pending.regions = snapshot.regions.toSet()
        return RestoreResult(
            restored = plan.changes.size + plan.unchanged,
            needConsent = plan.consent.size,
            skipped = plan.skipped,
            ignored = plan.ignored,
            regions = snapshot.regions.size,
        )
    }
}
