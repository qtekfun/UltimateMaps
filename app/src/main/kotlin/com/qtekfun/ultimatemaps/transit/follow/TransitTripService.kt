package com.qtekfun.ultimatemaps.transit.follow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.qtekfun.ultimatemaps.MainActivity
import com.qtekfun.ultimatemaps.MapasApp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.core.nav.NavNotificationThrottle
import com.qtekfun.ultimatemaps.core.transit.follow.FollowPhase
import com.qtekfun.ultimatemaps.core.transit.follow.TransitTripState
import com.qtekfun.ultimatemaps.nav.LiveUpdateNotification
import com.qtekfun.ultimatemaps.nav.LiveUpdatePlan
import com.qtekfun.ultimatemaps.nav.LiveUpdatePolicy
import com.qtekfun.ultimatemaps.nav.NavNotificationContent
import com.qtekfun.ultimatemaps.nav.NotificationDedupe
import com.qtekfun.ultimatemaps.voice.VoiceModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Keeps the process alive and allowed to read the location while the transit trip runs with the screen off or the app in
 * the background: a foreground service of type `location`, modelled on `NavigationService` (a separate service because a
 * transit trip has its own controller, state file and notification; the car navigation is not touched). It owns no logic: the
 * [com.qtekfun.ultimatemaps.core.transit.follow.TransitTripController] of the application follows the trip; this service shows
 * the notification (next instruction and plan chip, Open and Stop) and ends itself when the trip ends.
 *
 * - [ACTION_START]: a trip was just started from the UI. [ACTION_RESUME] (and a restart by the system with a null intent,
 *   after it killed the process): continue the trip saved on disk (3 h expiry), or stop at once if there is none.
 * - If the system refuses the foreground start the service gives up quietly: the saved state stays on disk and the UI
 *   offers to resume when the user comes back.
 * - Battery saver: the notification is refreshed at most every 5 s instead of 1 s.
 * - On Android 16+ the notification is promoted to a status-bar chip when the navigation's chip switch is on; the chip text is
 *   built by [TransitTripTexts.chip] ("2 stops", "Get off", "4 min").
 */
class TransitTripService : Service() {
    private val app get() = application as MapasApp
    private val host get() = app.transitTrip
    private val controller get() = host.controller
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null
    private var inForeground = false
    private val throttle = NavNotificationThrottle()
    private val dedupe = NotificationDedupe()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            host.stop()
            shutDown()
            return START_NOT_STICKY
        }
        if (!enterForeground(content(controller.state.value))) {
            stopSelf()
            return START_NOT_STICKY
        }
        // A null intent means the system restarted us after killing the process; ACTION_RESUME is the explicit form.
        if ((intent == null || intent.action == ACTION_RESUME) && !controller.isActive) {
            if (!controller.resume()) {
                shutDown()
                return START_NOT_STICKY
            }
            host.onResumedByService()
        }
        watch()
        return START_STICKY
    }

    private fun watch() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            controller.state.collect { trip ->
                if (trip == null) {
                    shutDown()
                    return@collect
                }
                val arrived = trip.follow.phase == FollowPhase.ARRIVED
                val minGap = if (powerSave()) 5_000L else 1_000L
                if (throttle.shouldPost(System.currentTimeMillis(), minGap, key = TransitTripNotificationTexts.key(trip), force = arrived)) {
                    val content = content(trip)
                    val plan = liveUpdatePlan(trip)
                    if (dedupe.shouldPost(content.title, content.text, plan, force = arrived)) {
                        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build(content, ongoing = !arrived, plan = plan))
                    }
                }
                if (arrived) {
                    // Leave the final "you have arrived" notification, drop the foreground state and end the service.
                    // The trip state stays until the user closes the screen (Done), so the screen can say so.
                    ServiceCompat.stopForeground(this@TransitTripService, ServiceCompat.STOP_FOREGROUND_DETACH)
                    inForeground = false
                    stopSelf()
                }
            }
        }
    }

    private fun powerSave(): Boolean = getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true

    private fun content(trip: TransitTripState?): NavNotificationContent =
        TransitTripNotificationTexts.of(resources, trip, Locale.getDefault())

    private fun enterForeground(content: NavNotificationContent): Boolean {
        if (inForeground) return true
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(content, ongoing = true, plan = liveUpdatePlan(controller.state.value)), type)
            inForeground = true
            true
        } catch (_: SecurityException) {
            false // Android 14: no location permission yet
        } catch (_: IllegalStateException) {
            false // ForegroundServiceStartNotAllowedException: started from the background
        }
    }

    private fun shutDown() {
        watcher?.cancel()
        if (inForeground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        inForeground = false
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** The chip and progress for the promoted (Android 16+) notification, or null to keep it a normal one. */
    private fun liveUpdatePlan(trip: TransitTripState?): LiveUpdatePlan? {
        val settings = VoiceModule.settings(this).settings.value
        val locale = Locale.getDefault()
        return TransitTripLiveUpdate.plan(
            settings.liveUpdateChip, Build.VERSION.SDK_INT, trip, resources, settings.units.resolve(locale), locale,
            System.currentTimeMillis() / 1000,
        )
    }

    private fun build(content: NavNotificationContent, ongoing: Boolean, plan: LiveUpdatePlan? = null): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, TransitTripService::class.java).setAction(ACTION_STOP), flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(true) // the prompts have their own voice and chime; the notification must never beep
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(0, getString(R.string.nav_notification_open), open)
            .addAction(0, getString(R.string.nav_notification_stop), stop)
            .build()
            .let { n -> if (plan != null && ongoing && Build.VERSION.SDK_INT >= LiveUpdatePolicy.MIN_SDK) LiveUpdateNotification.promote(this, n, plan, R.drawable.ic_launcher) else n }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.trip_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val ACTION_START = "com.qtekfun.ultimatemaps.transit.START"
        const val ACTION_RESUME = "com.qtekfun.ultimatemaps.transit.RESUME"
        const val ACTION_STOP = "com.qtekfun.ultimatemaps.transit.STOP"
        private const val CHANNEL_ID = "transit_trip"
        private const val NOTIFICATION_ID = 43

        /** Starts the service for a trip already started on the controller (call from a visible activity). */
        fun start(context: Context) = ctx(context, ACTION_START)

        /** Asks the service to continue the saved trip (the UI offers this after the process was killed). */
        fun resume(context: Context) = ctx(context, ACTION_RESUME)

        fun stop(context: Context) = ctx(context, ACTION_STOP)

        private fun ctx(context: Context, action: String) {
            val intent = Intent(context, TransitTripService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
