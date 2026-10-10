package com.qtekfun.ultimatemaps.gms

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.qtekfun.ultimatemaps.core.map.GeofenceTarget
import com.qtekfun.ultimatemaps.core.map.Geofencer

/**
 * Google's geofencing as a [Geofencer]: the system watches the circles itself (cheaply, with the screen off) and a broadcast
 * to a receiver registered while a trip runs says which one was entered. Needs the location permission; without it, or
 * without Play Services, [set] does nothing. The trip runs in a foreground service, so the process is alive to get the
 * broadcast; the background-location permission is deliberately not requested (privacy), so a geofence is a help, never a
 * promise. Nothing is stored or sent by us.
 */
class GmsGeofencer(context: Context) : Geofencer {
    private val app = context.applicationContext
    private val client = LocationServices.getGeofencingClient(app)
    private var receiver: BroadcastReceiver? = null
    private var pending: PendingIntent? = null

    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    override fun set(targets: List<GeofenceTarget>, onEnter: (id: String) -> Unit) {
        clear()
        if (targets.isEmpty() || !GmsAvailability.isAvailable(app)) return
        val action = "${app.packageName}.GEOFENCE_ENTER"
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val event = GeofencingEvent.fromIntent(intent) ?: return
                if (event.hasError() || event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
                event.triggeringGeofences?.forEach { onEnter(it.requestId) }
            }
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(app, 1, Intent(action).setPackage(app.packageName), flags)
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(
                targets.map {
                    Geofence.Builder()
                        .setRequestId(it.id)
                        .setCircularRegion(it.point.lat, it.point.lon, it.radiusMeters)
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                        .build()
                },
            )
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(rx, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(rx, IntentFilter(action))
            client.addGeofences(request, pi)
            receiver = rx
            pending = pi
        } catch (_: SecurityException) {
            runCatching { app.unregisterReceiver(rx) }
        }
    }

    override fun clear() {
        pending?.let { runCatching { client.removeGeofences(it) } }
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        pending = null
        receiver = null
    }
}
