package com.qtekfun.mapas.recording

import android.content.Context
import com.qtekfun.mapas.core.data.record.RecordingState
import com.qtekfun.mapas.core.data.record.StopResult
import com.qtekfun.mapas.core.data.record.TrackRecorder
import com.qtekfun.mapas.core.map.LocationFix
import com.qtekfun.mapas.core.map.LocationSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Whether the "record my trip" feature is available. Off by default; changing it never sends anything anywhere. */
interface RecordingSettings {
    var enabled: Boolean
}

class InMemoryRecordingSettings(override var enabled: Boolean = false) : RecordingSettings

/** [RecordingSettings] over SharedPreferences (`mapas_recording`). */
class PrefsRecordingSettings(context: Context) : RecordingSettings {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    companion object {
        const val PREFS = "mapas_recording"
        const val KEY_ENABLED = "enabled"
    }
}

/** What happened to the last recording, for a one-line message. */
enum class RecordingNotice { SAVED, TOO_SHORT, FAILED, COULD_NOT_START }

/** The stored tracks the recording feature may delete. Blocking: called off the main thread. */
fun interface RecordedTracksAdmin {
    /** Deletes every recorded track (imported ones are never touched); returns how many. */
    fun deleteRecordedTracks(): Int
}

/**
 * The model of track recording, shared by the activity, the navigation and the Settings screen (it lives with the
 * application). Fixes ([onFix]) and commands go through ONE queue consumed on [serial], so the recorder sees them in
 * order and the journal is written off the main thread; the UI reads [state], [enabled] and [notice].
 *
 * Fixes are only queued while a recording is active, so the idle cost is one flag read per fix. Positions are never
 * logged and never leave the device.
 */
class RecordingController(
    scope: CoroutineScope,
    serial: CoroutineDispatcher,
    private val recorder: TrackRecorder,
    private val settings: RecordingSettings,
    private val admin: RecordedTracksAdmin,
) {
    private sealed interface Command {
        data object Start : Command
        data object Stop : Command
        data object Recover : Command
        data object DeleteRecorded : Command
        data class Fix(val fix: LocationFix) : Command
    }

    private val inbox = Channel<Command>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(recorder.current)
    private val _enabled = MutableStateFlow(settings.enabled)
    private val _notice = MutableStateFlow<RecordingNotice?>(null)
    private val _stored = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    val state: StateFlow<RecordingState> = _state.asStateFlow()
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val notice: StateFlow<RecordingNotice?> = _notice.asStateFlow()

    /** Emits when the stored tracks changed (a recording was saved, tracks were deleted): refresh the lists. */
    val stored: SharedFlow<Unit> = _stored.asSharedFlow()

    init {
        scope.launch(serial) {
            for (command in inbox) {
                try {
                    handle(command)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A failing disk must not end the loop; the next command tries again.
                }
                _state.value = recorder.current
            }
        }
    }

    /** Turns the feature on or off (Settings). Turning it off while recording stops and saves the recording. */
    fun setEnabled(on: Boolean) {
        settings.enabled = on
        _enabled.value = on
        if (!on) inbox.trySend(Command.Stop)
    }

    fun start() {
        if (!_enabled.value) return
        _notice.value = null
        inbox.trySend(Command.Start)
    }

    fun stop() {
        inbox.trySend(Command.Stop)
    }

    /** Stores what an interrupted recording left behind (call once when the app starts). */
    fun recoverInterrupted() {
        inbox.trySend(Command.Recover)
    }

    /** One tap: deletes every recorded track. */
    fun deleteRecorded() {
        inbox.trySend(Command.DeleteRecorded)
    }

    fun dismissNotice() {
        _notice.value = null
    }

    /** A position fix from any source (the screen's, or the navigation's). Cheap when not recording; any thread. */
    fun onFix(fix: LocationFix) {
        if (_state.value.active) inbox.trySend(Command.Fix(fix))
    }

    private fun handle(command: Command) {
        when (command) {
            is Command.Fix -> recorder.onFix(command.fix.point, command.fix.accuracyMeters, command.fix.speedMps, command.fix.timeMillis)
            Command.Start -> {
                if (!recorder.start() && !recorder.current.active) _notice.value = RecordingNotice.COULD_NOT_START
                _stored.tryEmit(Unit) // starting stores the points an interrupted recording left behind
            }
            Command.Stop -> when (recorder.stop()) {
                is StopResult.Saved -> { _notice.value = RecordingNotice.SAVED; _stored.tryEmit(Unit) }
                StopResult.TooShort -> _notice.value = RecordingNotice.TOO_SHORT
                StopResult.Failed -> _notice.value = RecordingNotice.FAILED
                StopResult.NotRecording -> Unit
            }
            Command.Recover -> if (recorder.recoverInterrupted() != null) _stored.tryEmit(Unit)
            Command.DeleteRecorded -> {
                admin.deleteRecordedTracks()
                _stored.tryEmit(Unit)
            }
        }
    }
}

/**
 * Passes every fix of [inner] to [tap] (the recorder) before the listener sees it. A failure in [tap] never reaches
 * the navigation that owns the listener.
 */
class TapLocationSource(private val inner: LocationSource, private val tap: (LocationFix) -> Unit) : LocationSource {
    override fun lastKnown(): LocationFix? = inner.lastKnown()

    override fun start(listener: LocationSource.Listener) {
        inner.start { fix ->
            try {
                tap(fix)
            } catch (_: Exception) {
            }
            listener.onFix(fix)
        }
    }

    override fun stop() = inner.stop()
}
