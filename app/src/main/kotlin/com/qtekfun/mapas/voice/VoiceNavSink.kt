package com.qtekfun.mapas.voice

import com.qtekfun.mapas.MapasApp
import com.qtekfun.mapas.nav.NavEventSink

/**
 * Connects the voice to the navigation screen: the voice listens to the prompts of [MapasApp.navigation] while a
 * navigation (real or simulated) is running and is silenced when it ends. The screen emits [onNavigationStarted]
 * right after the session starts, before the first fix can produce a prompt.
 */
class VoiceNavSink(private val app: MapasApp) : NavEventSink {
    override fun onNavigationStarted(simulated: Boolean) = VoiceModule.attach(app)

    override fun onNavigationEnded(arrived: Boolean) = VoiceModule.detach()
}
