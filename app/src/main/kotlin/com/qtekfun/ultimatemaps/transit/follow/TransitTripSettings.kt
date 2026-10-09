package com.qtekfun.ultimatemaps.transit.follow

import android.content.Context
import android.content.SharedPreferences
import com.qtekfun.ultimatemaps.core.cameras.AlertSoundMode
import com.qtekfun.ultimatemaps.core.transit.PlanOptions
import com.qtekfun.ultimatemaps.core.transit.TransitMode
import com.qtekfun.ultimatemaps.settings.backup.SettingsReloadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How the step-by-step transit trip announces boarding, changes and getting off. The on-screen banner always shows. */
interface TransitTripSettingsStore {
    val promptMode: StateFlow<AlertSoundMode>
    fun setPromptMode(mode: AlertSoundMode)

    /**
     * "Cercanias real time" (opt-in, off by default): while on, the app asks Renfe's server for train delays while the user
     * uses public transport. Nothing is asked while it is off.
     */
    val realTimeEnabled: StateFlow<Boolean>
    fun setRealTimeEnabled(on: Boolean)

    /**
     * Planner choices (Settings and the mode chips of the route panel). [allowedModes] holds only [TransitMode.FILTERABLE]
     * modes (all on by default); [TransitMode.OTHER] is always allowed. The minutes default to [TransitPlanningDefaults].
     */
    val allowedModes: StateFlow<Set<TransitMode>>
    fun setAllowedModes(modes: Set<TransitMode>)

    /** Walking is offered as an option when it takes at most this many minutes. */
    val walkAlternativeMin: StateFlow<Int>
    fun setWalkAlternativeMin(minutes: Int)

    /** A vehicle journey that saves less than this many minutes over walking is dropped. */
    val minSavingMin: StateFlow<Int>
    fun setMinSavingMin(minutes: Int)

    /** Cap on the walking of one journey in minutes; 0 means no cap. */
    val maxWalkMin: StateFlow<Int>
    fun setMaxWalkMin(minutes: Int)
}

/** Defaults and offered choices of the planner settings. */
object TransitPlanningDefaults {
    const val WALK_ALTERNATIVE_MIN = 20
    const val MIN_SAVING_MIN = 5
    const val MAX_WALK_MIN = 60
    val ALL_MODES: Set<TransitMode> = TransitMode.FILTERABLE.toSet()
    val WALK_ALTERNATIVE_CHOICES = listOf(10, 15, 20, 30, 45)
    val MIN_SAVING_CHOICES = listOf(0, 2, 5, 10, 15)

    /** 0 = no cap. */
    val MAX_WALK_CHOICES = listOf(10, 15, 20, 30, 45, 60, 90, 0)
    const val MAX_MINUTES = 120

    /** Only the five switchable modes survive; anything else is dropped. */
    fun cleanModes(modes: Collection<TransitMode>): Set<TransitMode> = modes.filter { it in TransitMode.FILTERABLE }.toSet()
}

/** The request options the planner needs, from the settings. */
fun TransitTripSettingsStore.planOptions(): PlanOptions = PlanOptions(
    modes = allowedModes.value + TransitMode.OTHER,
    walkAlternativeMaxSec = walkAlternativeMin.value * 60,
    minTransitSavingSec = minSavingMin.value * 60,
    maxTotalWalkSec = maxWalkMin.value * 60,
)

class InMemoryTransitTripSettings(initial: AlertSoundMode = AlertSoundMode.VOICE, realTime: Boolean = false) : TransitTripSettingsStore {
    private val state = MutableStateFlow(initial)
    private val rt = MutableStateFlow(realTime)
    private val modes = MutableStateFlow(TransitPlanningDefaults.ALL_MODES)
    private val walkAlt = MutableStateFlow(TransitPlanningDefaults.WALK_ALTERNATIVE_MIN)
    private val saving = MutableStateFlow(TransitPlanningDefaults.MIN_SAVING_MIN)
    private val maxWalk = MutableStateFlow(TransitPlanningDefaults.MAX_WALK_MIN)
    override val allowedModes: StateFlow<Set<TransitMode>> = modes
    override fun setAllowedModes(modes: Set<TransitMode>) {
        this.modes.value = TransitPlanningDefaults.cleanModes(modes)
    }

    override val walkAlternativeMin: StateFlow<Int> = walkAlt
    override fun setWalkAlternativeMin(minutes: Int) {
        walkAlt.value = minutes.coerceIn(0, TransitPlanningDefaults.MAX_MINUTES)
    }

    override val minSavingMin: StateFlow<Int> = saving
    override fun setMinSavingMin(minutes: Int) {
        saving.value = minutes.coerceIn(0, TransitPlanningDefaults.MAX_MINUTES)
    }

    override val maxWalkMin: StateFlow<Int> = maxWalk
    override fun setMaxWalkMin(minutes: Int) {
        maxWalk.value = minutes.coerceIn(0, TransitPlanningDefaults.MAX_MINUTES)
    }
    override val promptMode: StateFlow<AlertSoundMode> = state
    override fun setPromptMode(mode: AlertSoundMode) {
        state.value = mode
    }

    override val realTimeEnabled: StateFlow<Boolean> = rt
    override fun setRealTimeEnabled(on: Boolean) {
        rt.value = on
    }
}

/** [TransitTripSettingsStore] over SharedPreferences (`mapas_transit_trip`). A damaged value falls back to the default. */
class PrefsTransitTripSettings(private val prefs: SharedPreferences) : TransitTripSettingsStore, SettingsReloadable {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private val state = MutableStateFlow(read())
    override val promptMode: StateFlow<AlertSoundMode> = state
    private val rt = MutableStateFlow(readRealTime())
    override val realTimeEnabled: StateFlow<Boolean> = rt
    private val modes = MutableStateFlow(readModes())
    private val walkAlt = MutableStateFlow(readMinutes(KEY_WALK_ALT, TransitPlanningDefaults.WALK_ALTERNATIVE_MIN))
    private val saving = MutableStateFlow(readMinutes(KEY_MIN_SAVING, TransitPlanningDefaults.MIN_SAVING_MIN))
    private val maxWalk = MutableStateFlow(readMinutes(KEY_MAX_WALK, TransitPlanningDefaults.MAX_WALK_MIN))
    override val allowedModes: StateFlow<Set<TransitMode>> = modes
    override val walkAlternativeMin: StateFlow<Int> = walkAlt
    override val minSavingMin: StateFlow<Int> = saving
    override val maxWalkMin: StateFlow<Int> = maxWalk

    @Synchronized
    override fun setAllowedModes(modes: Set<TransitMode>) {
        val clean = TransitPlanningDefaults.cleanModes(modes)
        if (clean == this.modes.value) return
        prefs.edit().putStringSet(KEY_MODES, clean.map { it.name }.toSet()).apply()
        this.modes.value = clean
    }

    @Synchronized
    override fun setWalkAlternativeMin(minutes: Int) = writeMinutes(KEY_WALK_ALT, walkAlt, minutes)

    @Synchronized
    override fun setMinSavingMin(minutes: Int) = writeMinutes(KEY_MIN_SAVING, saving, minutes)

    @Synchronized
    override fun setMaxWalkMin(minutes: Int) = writeMinutes(KEY_MAX_WALK, maxWalk, minutes)

    private fun writeMinutes(key: String, flow: MutableStateFlow<Int>, minutes: Int) {
        val v = minutes.coerceIn(0, TransitPlanningDefaults.MAX_MINUTES)
        if (v == flow.value) return
        prefs.edit().putInt(key, v).apply()
        flow.value = v
    }

    /** A stored set keeps only the known modes; a missing or damaged value means "all on". */
    private fun readModes(): Set<TransitMode> = try {
        prefs.getStringSet(KEY_MODES, null)?.let { stored -> TransitMode.FILTERABLE.filter { it.name in stored }.toSet() } ?: TransitPlanningDefaults.ALL_MODES
    } catch (_: ClassCastException) {
        TransitPlanningDefaults.ALL_MODES
    }

    private fun readMinutes(key: String, default: Int): Int =
        try { prefs.getInt(key, default).takeIf { it in 0..TransitPlanningDefaults.MAX_MINUTES } ?: default } catch (_: ClassCastException) { default }

    @Synchronized
    override fun setRealTimeEnabled(on: Boolean) {
        if (on == rt.value) return
        prefs.edit().putBoolean(KEY_REAL_TIME, on).apply()
        rt.value = on
    }

    @Synchronized
    override fun setPromptMode(mode: AlertSoundMode) {
        if (mode == state.value) return
        prefs.edit().putString(KEY_PROMPTS, mode.name).apply()
        state.value = mode
    }

    /** Re-reads the file (after a settings restore wrote to it behind this store's back). */
    @Synchronized
    override fun reload() {
        state.value = read()
        rt.value = readRealTime()
        modes.value = readModes()
        walkAlt.value = readMinutes(KEY_WALK_ALT, TransitPlanningDefaults.WALK_ALTERNATIVE_MIN)
        saving.value = readMinutes(KEY_MIN_SAVING, TransitPlanningDefaults.MIN_SAVING_MIN)
        maxWalk.value = readMinutes(KEY_MAX_WALK, TransitPlanningDefaults.MAX_WALK_MIN)
    }

    /** Only a stored boolean `true` turns it on; anything else (missing, damaged, another type) is off. */
    private fun readRealTime(): Boolean = try { prefs.getBoolean(KEY_REAL_TIME, false) } catch (_: ClassCastException) { false }

    private fun read(): AlertSoundMode =
        AlertSoundMode.entries.firstOrNull { it.name == prefs.getString(KEY_PROMPTS, null) } ?: DEFAULT

    companion object {
        const val PREFS = "mapas_transit_trip"
        const val KEY_PROMPTS = "prompts"
        const val KEY_REAL_TIME = "cercanias_real_time"
        const val KEY_MODES = "plan_modes"
        const val KEY_WALK_ALT = "plan_walk_alt_min"
        const val KEY_MIN_SAVING = "plan_min_saving_min"
        const val KEY_MAX_WALK = "plan_max_walk_min"
        val DEFAULT = AlertSoundMode.VOICE
    }
}
