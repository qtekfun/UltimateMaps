package com.qtekfun.ultimatemaps.regions

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
import com.qtekfun.ultimatemaps.MapasApp
import com.qtekfun.ultimatemaps.R

/**
 * Keeps the process alive while regions download (foreground service of type `dataSync`, Android 14+).
 * The work itself runs in [RegionsController]; this service only shows the notification, forwards "pause"
 * and stops itself when nothing is queued. Android 15 limits `dataSync` to about 6 h: [onTimeout] pauses
 * every download (partial files stay, the user can resume).
 */
class RegionDownloadService : Service() {
    private val controller get() = (application as MapasApp).regions
    private val onChange = { refresh() }
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        controller.addListener(onChange)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE_ALL) controller.pauseAll()
        enterForeground()
        if (!controller.isDownloading) stop()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        controller.pauseAll()
        stop()
    }

    override fun onDestroy() {
        controller.removeListener(onChange)
        super.onDestroy()
    }

    private fun enterForeground() {
        val n = notification()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type) }
            .onSuccess { foregroundStarted = true }
    }

    private fun refresh() {
        if (!controller.isDownloading) {
            stop()
        } else if (foregroundStarted) {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification())
        }
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val states = controller.downloadStates
        val running = states.values.filterIsInstance<DownloadState.Running>()
        val queued = states.values.count { it is DownloadState.Queued }
        val done = running.sumOf { it.done }
        val total = running.sumOf { it.total }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, RegionsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pause = PendingIntent.getService(
            this, 1, Intent(this, RegionDownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (total > 0) {
            getString(R.string.regions_notification_progress, RegionsModel.percent(done, total), RegionsModel.formatBytes(total))
        } else {
            getString(R.string.regions_notification_waiting)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.regions_notification_title))
            .setContentText(if (queued > 0) "$text · " + getString(R.string.regions_notification_queued, queued) else text)
            .setProgress(100, RegionsModel.percent(done, total), total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.regions_pause), pause)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.regions_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.qtekfun.ultimatemaps.regions.PAUSE_ALL"
        private const val CHANNEL_ID = "region-downloads"
        private const val NOTIFICATION_ID = 41

        /** Convenience for tests and the UI. */
        fun start(context: Context) = RegionsController.startForegroundDownloads(context)
    }
}
