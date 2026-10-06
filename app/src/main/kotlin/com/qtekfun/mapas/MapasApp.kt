package com.qtekfun.mapas

import android.app.Application
import com.qtekfun.mapas.core.net.AllowedEndpoint
import com.qtekfun.mapas.core.net.ConnectionPurpose
import com.qtekfun.mapas.core.net.DefaultNetworkPolicy
import com.qtekfun.mapas.core.net.NetworkPolicy

class MapasApp : Application() {
    /**
     * The single exit to the network. Every optional connection is listed (RF-12) but starts disabled.
     * The app manifest does not even request INTERNET yet: no component can open a connection.
     */
    val networkPolicy: NetworkPolicy = DefaultNetworkPolicy(
        endpoints = listOf(
            AllowedEndpoint("tile.openstreetmap.org", ConnectionPurpose.ONLINE_TILES),
            AllowedEndpoint("maps.app.goo.gl", ConnectionPurpose.SHORT_LINK_RESOLVE),
            AllowedEndpoint("goo.gl", ConnectionPurpose.SHORT_LINK_RESOLVE),
        ),
    )
}
