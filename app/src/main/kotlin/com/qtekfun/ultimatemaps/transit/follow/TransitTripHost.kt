package com.qtekfun.ultimatemaps.transit.follow

import android.content.Context
import android.content.Intent
import com.qtekfun.ultimatemaps.core.transit.Itinerary
import com.qtekfun.ultimatemaps.core.transit.follow.TransitTripController
import com.qtekfun.ultimatemaps.core.transit.follow.TransitTripState
import com.qtekfun.ultimatemaps.core.voice.NavSettingsStore
import com.qtekfun.ultimatemaps.nav.NavUiPrefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Starts and stops the foreground service of the trip (the Android one, or a fake in tests). */
interface TripServiceControl {
    fun start()
    fun resume()
    fun stop()
}

/** What the transit trip screen draws. */
data class TransitTripUi(
    val trip: TransitTripState? = null,
    val glove: Boolean = false,
    val voiceOn: Boolean = true,
    /** A saved trip from before the process died could be resumed. */
    val resumable: Boolean = false,
) {
    val active: Boolean get() = trip != null
}

/**
 * The model of the transit trip screen, living with the application like `NavScreenController`: start (from the itinerary
 * card), stop, re-plan, resume after the process died, glove mode (the navigation's own switch) and mute (the navigation's
 * voice switch). It also feeds the controller's prompts to [speaker]. Every method may be called from the main thread.
 */
class TransitTripHost(
    private val scope: CoroutineScope,
    val controller: TransitTripController,
    private val service: TripServiceControl,
    private val prefs: NavUiPrefs,
    private val settings: NavSettingsStore,
    private val speaker: TransitTripSpeaker,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val glove = MutableStateFlow(prefs.glove)
    private val resumable = MutableStateFlow(false)

    val ui: StateFlow<TransitTripUi> = combine(controller.state, glove, settings.settings, resumable) { trip, g, nav, r ->
        TransitTripUi(trip, g, nav.voiceEnabled, r && trip == null)
    }.stateIn(scope, SharingStarted.Eagerly, TransitTripUi(glove = prefs.glove, voiceOn = settings.settings.value.voiceEnabled))

    init {
        scope.launch { controller.prompts.collect { speaker.onPrompt(it) } }
    }

    val active: Boolean get() = controller.isActive

    /** Starts following [itinerary]; false when it has no vehicle leg. Needs a visible activity (the service starts in the foreground). */
    fun start(itinerary: Itinerary, zone: ZoneId): Boolean {
        if (!controller.start(itinerary, zone.id)) return false
        resumable.value = false
        speaker.prepare()
        service.start()
        return true
    }

    fun stop() {
        controller.stop()
        service.stop()
        resumable.value = false
    }

    fun replan() = controller.replan()

    /** The service resumed a saved trip by itself (process death): get the voice ready. */
    fun onResumedByService() = speaker.prepare()

    fun resume() {
        if (controller.resume()) {
            resumable.value = false
            speaker.prepare()
            service.resume()
        }
    }

    fun discard() {
        controller.stop()
        resumable.value = false
    }

    /** Looks for a trip interrupted by the process dying (a small file read, off the main thread). */
    fun refreshResumable() {
        if (controller.isActive) return
        scope.launch {
            val found = withContext(io) { controller.hasResumable() }
            resumable.value = found
        }
    }

    fun setGlove(on: Boolean) {
        prefs.glove = on
        glove.value = on
    }

    fun setVoice(on: Boolean) = settings.update { it.copy(voiceEnabled = on) }
}

/** [TripServiceControl] on the real [TransitTripService]; a refused foreground start is not an error (the trip goes on in the app). */
class AndroidTripServiceControl(context: Context) : TripServiceControl {
    private val app = context.applicationContext

    override fun start() {
        runCatching { TransitTripService.start(app) }
    }

    override fun resume() {
        runCatching { TransitTripService.resume(app) }
    }

    override fun stop() {
        runCatching { app.stopService(Intent(app, TransitTripService::class.java)) }
    }
}
