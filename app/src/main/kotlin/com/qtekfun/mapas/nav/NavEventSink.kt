package com.qtekfun.mapas.nav

import com.qtekfun.mapas.core.nav.Announcement
import com.qtekfun.mapas.core.nav.NavEvent

/**
 * The hook for everything that reacts to the navigation without drawing it: first of all the voice (phrases, TTS),
 * which is somebody else's work. Register one with [NavScreenController.addSink] (the application keeps the
 * controller in `MapasApp.navScreen`); remove it with [NavScreenController.removeSink].
 *
 * Every method has an empty default, so a sink implements only what it needs. All of them are called from a
 * background thread of the controller's scope, one at a time and in order; they must return quickly (hand the work
 * to your own thread) and must not log positions. [Announcement] is delivered exactly as the follower emitted it:
 * each prompt once, never repeated.
 */
interface NavEventSink {
    /** A navigation just started ([simulated]: a route simulation, which is not a real trip). */
    fun onNavigationStarted(simulated: Boolean) {}

    /** A prompt to speak: [Announcement.kind] FAR, NEAR or NOW for [Announcement.maneuver]. */
    fun onAnnouncement(announcement: Announcement) {}

    /** An intermediate stop was reached or skipped. */
    fun onEvent(event: NavEvent) {}

    /** The navigation ended: [arrived] is true when the destination was reached, false when it was stopped. */
    fun onNavigationEnded(arrived: Boolean) {}
}
