package com.qtekfun.ultimatemaps.nav

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
import com.qtekfun.ultimatemaps.core.nav.NavProblem
import com.qtekfun.ultimatemaps.core.nav.NavState
import com.qtekfun.ultimatemaps.core.nav.NavStatus
import com.qtekfun.ultimatemaps.voice.VoiceModule
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and allowed to read the location while the user navigates with the screen off or the
 * app in the background: a foreground service of type `location` (Android 14 needs `FOREGROUND_SERVICE_LOCATION`
 * and the runtime location permission at the moment it starts). It owns no logic: the [NavigationController] of
 * the application does the following, this service shows the notification (next maneuver, remaining distance and
 * time, Stop and Open) and ends itself when the navigation ends.
 *
 * - [ACTION_START]: a navigation was just started from the UI. [ACTION_RESUME] (and a restart by the system with a
 *   null intent, after it killed the process): continue the navigation saved on disk, or stop at once if there is
 *   none or it expired.
 * - If the system refuses the foreground start (Android 12+ forbids it from the background; Android 14 without
 *   the location permission throws), the service gives up quietly: the saved state stays on disk and the UI
 *   offers to resume when the user comes back.
 * - Battery saver: positions are still read at full rate (guidance is the reason the user asked for the
 *   service) but the notification is refreshed at most every 5 s instead of 1 s.
 * - Android 15 `location` services have no time limit, so there is no `onTimeout` handling to do.
 */
class NavigationService : Service() {
    private val app get() = application as MapasApp
    private val controller get() = app.navigation
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null
    private var inForeground = false
    private val throttle = NavNotificationThrottle()
    private val dedupe = NotificationDedupe()
    private val environment by lazy { AndroidNavEnvironment(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            controller.stop()
            shutDown()
            return START_NOT_STICKY
        }
        if (!enterForeground(NavNotificationTexts.of(this, controller.state.value, controller.problem.value))) {
            stopSelf()
            return START_NOT_STICKY
        }
        // A null intent means the system restarted us after killing the process; ACTION_RESUME is the explicit form.
        if ((intent == null || intent.action == ACTION_RESUME) && !controller.isActive && !controller.resume()) {
            shutDown()
            return START_NOT_STICKY
        }
        watch()
        return START_STICKY
    }

    private fun watch() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            combine(controller.state, controller.problem) { s, p -> s to p }.collect { (state, problem) ->
                if (state == null) {
                    shutDown()
                    return@collect
                }
                val now = System.currentTimeMillis()
                val arrived = state.status == NavStatus.ARRIVED
                val minGap = if (environment.isPowerSaveMode()) 5_000L else 1_000L
                if (throttle.shouldPost(now, minGap, key = "${state.status}|$problem|${state.nextManeuver?.maneuver?.geometryIndex}", force = arrived)) {
                    val content = NavNotificationTexts.of(this@NavigationService, state, problem)
                    val plan = liveUpdatePlan(state, problem)
                    // Redraw only when something shown changed (the chip text in particular).
                    if (dedupe.shouldPost(content.title, content.text, plan, force = arrived)) {
                        val n = build(content, ongoing = !arrived, plan = plan)
                        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n)
                    }
                }
                if (arrived) {
                    // Leave the final "you have arrived" notification, drop the foreground state and end.
                    ServiceCompat.stopForeground(this@NavigationService, ServiceCompat.STOP_FOREGROUND_DETACH)
                    inForeground = false
                    controller.stop()
                    stopSelf()
                }
            }
        }
    }

    private fun enterForeground(content: NavNotificationContent): Boolean {
        if (inForeground) return true
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(content, ongoing = true, plan = liveUpdatePlan(controller.state.value, controller.problem.value)), type)
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
    private fun liveUpdatePlan(state: NavState?, problem: NavProblem?): LiveUpdatePlan? {
        val settings = VoiceModule.settings(this).settings.value
        val locale = Locale.getDefault()
        return LiveUpdatePolicy.plan(
            settings.liveUpdateChip, Build.VERSION.SDK_INT, state, problem, settings.units.resolve(locale), locale,
            ChipWords(getString(R.string.live_update_chip_reroute), getString(R.string.live_update_chip_no_signal)),
        )
    }

    private fun build(content: NavNotificationContent, ongoing: Boolean, plan: LiveUpdatePlan? = null): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val stop = PendingIntent.getService(this, 1, Intent(this, NavigationService::class.java).setAction(ACTION_STOP), flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(true) // voice guidance is its own thing; the notification must never beep
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
            NotificationChannel(CHANNEL_ID, getString(R.string.nav_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val ACTION_START = "com.qtekfun.ultimatemaps.nav.START"
        const val ACTION_RESUME = "com.qtekfun.ultimatemaps.nav.RESUME"
        const val ACTION_STOP = "com.qtekfun.ultimatemaps.nav.STOP"
        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 42

        /** Starts the service for a navigation already started on the controller (call from a visible activity). */
        fun start(context: Context) = ctx(context, ACTION_START)

        /** Asks the service to continue the saved navigation (the UI offers this after the process was killed). */
        fun resume(context: Context) = ctx(context, ACTION_RESUME)

        fun stop(context: Context) = ctx(context, ACTION_STOP)

        private fun ctx(context: Context, action: String) {
            val intent = Intent(context, NavigationService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
