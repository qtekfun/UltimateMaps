package com.qtekfun.mapas

import android.app.Application
import com.qtekfun.mapas.core.net.AllowedEndpoint
import com.qtekfun.mapas.core.net.ConnectionPurpose
import com.qtekfun.mapas.core.net.DefaultNetworkPolicy
import com.qtekfun.mapas.core.net.NetworkPolicy
import com.qtekfun.mapas.regions.CoreLinks
import com.qtekfun.mapas.regions.RegionsController

class MapasApp : Application() {
    private val policy = DefaultNetworkPolicy(
        endpoints = listOf(
            AllowedEndpoint("tile.openstreetmap.org", ConnectionPurpose.ONLINE_TILES),
            AllowedEndpoint("maps.app.goo.gl", ConnectionPurpose.SHORT_LINK_RESOLVE),
            AllowedEndpoint("goo.gl", ConnectionPurpose.SHORT_LINK_RESOLVE),
        ),
    )

    /**
     * The single exit to the network. Every optional connection is listed (RF-12) but starts disabled, except
     * the map server the user typed in (and the hosts its catalog names), which is added for map downloads
     * only. The manifest requests INTERNET for that flow alone; with offline mode on, nothing connects.
     */
    val networkPolicy: NetworkPolicy = policy

    /** Region catalog, downloads and storage (the "Maps" screen and its foreground service). */
    val regions: RegionsController by lazy {
        RegionsController(this, policy, addEndpoint = policy::addEndpoint).also { it.restore() }
    }

    override fun onCreate() {
        super.onCreate()
        // Offline mode is a persisted privacy setting: it must hold before anything can connect.
        policy.offlineMode = getSharedPreferences(RegionsController.PREFS, MODE_PRIVATE).getBoolean(RegionsController.KEY_OFFLINE, false)
        // Rebuild maps-core/<version>/ (links to the installed .mwm) for the search core; files only, off the main thread.
        Thread({ runCatching { CoreLinks.sync(this) } }, "mapas-core-links").start()
    }
}
