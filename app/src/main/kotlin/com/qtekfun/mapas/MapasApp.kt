package com.qtekfun.mapas

import android.app.Application
import com.qtekfun.mapas.core.nav.NavStateStore
import com.qtekfun.mapas.core.nav.NavigationController
import com.qtekfun.mapas.core.net.AllowedEndpoint
import com.qtekfun.mapas.core.net.ConnectionPurpose
import com.qtekfun.mapas.core.net.DefaultNetworkPolicy
import com.qtekfun.mapas.core.net.NetworkPolicy
import com.qtekfun.mapas.location.AndroidLocationSource
import com.qtekfun.mapas.nav.AndroidNavEnvironment
import com.qtekfun.mapas.nav.CoreRouteProvider
import com.qtekfun.mapas.regions.CoreLinks
import com.qtekfun.mapas.regions.RegionsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

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

    /**
     * The one navigation in progress (see `docs/phase2/robustness.md`). It lives in the main process, outlives the
     * activity, and is kept running by [com.qtekfun.mapas.nav.NavigationService]. Its saved state is private and
     * expires on its own, so a process killed by the system can resume.
     */
    val navigation: NavigationController by lazy {
        NavigationController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            location = AndroidLocationSource(this),
            store = NavStateStore(File(noBackupFilesDir, "navigation/state.bin")),
            environment = AndroidNavEnvironment(this),
            routes = CoreRouteProvider(this),
        )
    }

    override fun onCreate() {
        super.onCreate()
        // The native core runs in its own process (`:core`), which also creates an Application: it must not start
        // the main process's housekeeping.
        if (!isMainProcess()) return
        // Offline mode is a persisted privacy setting: it must hold before anything can connect.
        policy.offlineMode = getSharedPreferences(RegionsController.PREFS, MODE_PRIVATE).getBoolean(RegionsController.KEY_OFFLINE, false)
        // Rebuild maps-core/<version>/ (links to the installed .mwm) for the search core; files only, off the main thread.
        Thread({ runCatching { CoreLinks.sync(this) } }, "mapas-core-links").start()
    }

    private fun isMainProcess(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName() == packageName else true
}
