package com.qtekfun.ultimatemaps.gms

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import com.qtekfun.ultimatemaps.core.map.MovementHint

/**
 * Google's activity recognition as a [MovementHint]: "in a vehicle" when the most probable activity is IN_VEHICLE with at
 * least [minConfidence] percent, "not" for ON_FOOT, WALKING, RUNNING or ON_BICYCLE, unknown for STILL, TILTING and UNKNOWN.
 * The answer expires after [maxAgeMillis] without an update. Needs the ACTIVITY_RECOGNITION runtime permission ([permission]);
 * without it [start] does nothing. Only runs while a public-transport trip is followed. Nothing is stored or sent by us.
 */
class GmsMovementHint(
    context: Context,
    private val minConfidence: Int = 70,
    private val detectionIntervalMillis: Long = 10_000L,
    private val maxAgeMillis: Long = 60_000L,
) : MovementHint {
    private val app = context.applicationContext
    private val client = ActivityRecognition.getClient(app)

    @Volatile private var vehicle: Boolean? = null
    @Volatile private var atMs = 0L
    private var receiver: BroadcastReceiver? = null
    private var pending: PendingIntent? = null

    override val permission: String? get() = if (Build.VERSION.SDK_INT >= 29) Manifest.permission.ACTIVITY_RECOGNITION else null

    private fun granted(): Boolean = permission?.let { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } ?: true

    override fun inVehicle(): Boolean? = if (SystemClock.elapsedRealtime() - atMs <= maxAgeMillis) vehicle else null

    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    override fun start() {
        stop()
        if (!GmsAvailability.isAvailable(app) || !granted()) return
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (!ActivityRecognitionResult.hasResult(intent)) return
                val top = ActivityRecognitionResult.extractResult(intent)?.mostProbableActivity ?: return
                vehicle = classify(top.type, top.confidence)
                atMs = SystemClock.elapsedRealtime()
            }
        }
        val action = "${app.packageName}.ACTIVITY_HINT"
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(app, 0, Intent(action).setPackage(app.packageName), flags)
        try {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(rx, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(rx, IntentFilter(action))
            client.requestActivityUpdates(detectionIntervalMillis, pi)
            receiver = rx
            pending = pi
        } catch (_: Exception) {
            runCatching { app.unregisterReceiver(rx) }
        }
    }

    override fun stop() {
        pending?.let { runCatching { client.removeActivityUpdates(it) } }
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        pending = null
        receiver = null
        vehicle = null
    }

    internal fun classify(type: Int, confidence: Int): Boolean? = when {
        confidence < minConfidence -> null
        type == DetectedActivity.IN_VEHICLE -> true
        type == DetectedActivity.ON_FOOT || type == DetectedActivity.WALKING || type == DetectedActivity.RUNNING ||
            type == DetectedActivity.ON_BICYCLE -> false
        else -> null
    }
}
