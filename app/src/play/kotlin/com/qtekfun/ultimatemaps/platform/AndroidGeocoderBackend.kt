package com.qtekfun.ultimatemaps.platform

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.qtekfun.ultimatemaps.core.search.BackendUnavailableException
import com.qtekfun.ultimatemaps.core.search.Cancellable
import com.qtekfun.ultimatemaps.core.search.GeocodeBounds
import com.qtekfun.ultimatemaps.core.search.GeocodeScheduler
import com.qtekfun.ultimatemaps.core.search.GeocodedAddress
import com.qtekfun.ultimatemaps.core.search.GeocoderBackend
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

/**
 * [GeocoderBackend] over the framework `android.location.Geocoder` (`play` flavor only). The framework class is not a
 * Google API: on a phone with Play Services its backend is Google's, otherwise whatever the ROM ships, or nothing.
 * Uses the listener API on Android 13+ and one background thread with the deprecated blocking call before that.
 */
class AndroidGeocoderBackend(
    context: Context,
    private val locale: () -> Locale = Locale::getDefault,
) : GeocoderBackend {
    private val appContext = context.applicationContext
    private val legacyExecutor by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "um-geocoder").apply { isDaemon = true } } }

    override fun forwardGeocode(query: String, bounds: GeocodeBounds?, maxResults: Int, callback: GeocoderBackend.Callback) {
        if (!Geocoder.isPresent()) throw BackendUnavailableException("Geocoder.isPresent() is false")
        val geocoder = Geocoder(appContext, locale())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val listener = object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) = callback.onResults(addresses.map(::toRaw))
                override fun onError(errorMessage: String?) = callback.onError(errorMessage)
            }
            if (bounds == null) {
                geocoder.getFromLocationName(query, maxResults, listener)
            } else {
                geocoder.getFromLocationName(
                    query, maxResults, bounds.lowerLeft.lat, bounds.lowerLeft.lon, bounds.upperRight.lat, bounds.upperRight.lon, listener,
                )
            }
        } else {
            legacyExecutor.execute {
                try {
                    @Suppress("DEPRECATION")
                    val list = if (bounds == null) {
                        geocoder.getFromLocationName(query, maxResults)
                    } else {
                        geocoder.getFromLocationName(
                            query, maxResults, bounds.lowerLeft.lat, bounds.lowerLeft.lon, bounds.upperRight.lat, bounds.upperRight.lon,
                        )
                    }
                    callback.onResults(list.orEmpty().map(::toRaw))
                } catch (e: IOException) {
                    callback.onError(e.message ?: "IOException")
                } catch (e: IllegalArgumentException) {
                    callback.onError(e.message ?: "IllegalArgumentException")
                }
            }
        }
    }

    private fun toRaw(a: Address) = GeocodedAddress(
        featureName = a.featureName,
        addressLines = (0..a.maxAddressLineIndex).mapNotNull { a.getAddressLine(it) },
        thoroughfare = a.thoroughfare,
        subThoroughfare = a.subThoroughfare,
        locality = a.locality,
        adminArea = a.adminArea,
        postalCode = a.postalCode,
        countryName = a.countryName,
        latitude = if (a.hasLatitude()) a.latitude else null,
        longitude = if (a.hasLongitude()) a.longitude else null,
    )
}

/** [GeocodeScheduler] on the main looper: `now` is the monotonic clock, timeouts are posted delayed callbacks. */
class MainLooperScheduler : GeocodeScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun now(): Long = SystemClock.elapsedRealtime()

    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val r = Runnable(task)
        handler.postDelayed(r, delayMs)
        return Cancellable { handler.removeCallbacks(r) }
    }
}
