package com.qtekfun.mapas.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultNetworkPolicyTest {
    private val tiles = AllowedEndpoint("tile.openstreetmap.org", ConnectionPurpose.ONLINE_TILES, enabled = true)
    private var now = 1_000L

    private fun policy(vararg e: AllowedEndpoint, offline: Boolean = false, max: Int = 200) =
        DefaultNetworkPolicy(e.toList(), offline, max) { now++ }

    @Test fun emptyWhitelistDeniesEverything() {
        val d = policy().authorize("example.com", ConnectionPurpose.OTHER)
        assertEquals(NetworkDecision.Denied(DenyReason.NOT_WHITELISTED), d)
    }

    @Test fun whitelistedEnabledHostIsAllowed() {
        assertTrue(policy(tiles).authorize("tile.openstreetmap.org", ConnectionPurpose.ONLINE_TILES).isAllowed)
    }

    @Test fun purposeMustMatch() {
        val d = policy(tiles).authorize("tile.openstreetmap.org", ConnectionPurpose.SYNC_WEBDAV)
        assertEquals(NetworkDecision.Denied(DenyReason.NOT_WHITELISTED), d)
    }

    @Test fun hostMatchIsCaseInsensitive() {
        assertTrue(policy(tiles).authorize("Tile.OpenStreetMap.org ", ConnectionPurpose.ONLINE_TILES).isAllowed)
    }

    @Test fun disabledEntryIsListedButDenied() {
        val p = policy(tiles.copy(enabled = false))
        assertEquals(1, p.possibleConnections().size)
        assertEquals(
            NetworkDecision.Denied(DenyReason.DISABLED_BY_USER),
            p.authorize("tile.openstreetmap.org", ConnectionPurpose.ONLINE_TILES),
        )
    }

    @Test fun userCanEnableAndDisable() {
        val p = policy(tiles.copy(enabled = false))
        p.setEndpointEnabled("tile.openstreetmap.org", ConnectionPurpose.ONLINE_TILES, true)
        assertTrue(p.authorize("tile.openstreetmap.org", ConnectionPurpose.ONLINE_TILES).isAllowed)
        p.setEndpointEnabled("tile.openstreetmap.org", ConnectionPurpose.ONLINE_TILES, false)
        assertFalse(p.authorize("tile.openstreetmap.org", ConnectionPurpose.ONLINE_TILES).isAllowed)
    }

    @Test fun enablingUnknownEndpointFails() {
        assertFailsWith<IllegalArgumentException> {
            policy().setEndpointEnabled("nope.org", ConnectionPurpose.OTHER, true)
        }
    }

    @Test fun offlineModeDeniesEvenWhitelistedHosts() {
        val p = policy(tiles)
        p.offlineMode = true
        assertEquals(
            NetworkDecision.Denied(DenyReason.OFFLINE_MODE),
            p.authorize("tile.openstreetmap.org", ConnectionPurpose.ONLINE_TILES),
        )
        p.offlineMode = false
        assertTrue(p.authorize("tile.openstreetmap.org", ConnectionPurpose.ONLINE_TILES).isAllowed)
    }

    @Test fun wildcardMatchesSubdomainsOnly() {
        val p = policy(AllowedEndpoint("*.example.org", ConnectionPurpose.MAP_DOWNLOAD, true))
        assertTrue(p.authorize("a.example.org", ConnectionPurpose.MAP_DOWNLOAD).isAllowed)
        assertTrue(p.authorize("a.b.example.org", ConnectionPurpose.MAP_DOWNLOAD).isAllowed)
        assertFalse(p.authorize("example.org", ConnectionPurpose.MAP_DOWNLOAD).isAllowed)
        assertFalse(p.authorize("evilexample.org", ConnectionPurpose.MAP_DOWNLOAD).isAllowed)
    }

    @Test fun logRecordsAttemptsNewestFirstWithOutcome() {
        val p = policy(tiles)
        p.authorize("tile.openstreetmap.org", ConnectionPurpose.ONLINE_TILES)
        p.authorize("other.com", ConnectionPurpose.OTHER)
        val log = p.recentConnections()
        assertEquals(listOf("other.com", "tile.openstreetmap.org"), log.map { it.host })
        assertEquals(listOf(false, true), log.map { it.allowed })
        assertTrue(log[0].timestampMillis > log[1].timestampMillis)
    }

    @Test fun logIsBoundedAndClearable() {
        val p = policy(max = 3)
        repeat(10) { p.authorize("h$it.com", ConnectionPurpose.OTHER) }
        assertEquals(listOf("h9.com", "h8.com", "h7.com"), p.recentConnections().map { it.host })
        p.clearLog()
        assertTrue(p.recentConnections().isEmpty())
    }

    @Test fun offlineAttemptsAreLoggedAsDenied() {
        val p = policy(tiles, offline = true)
        p.authorize("tile.openstreetmap.org", ConnectionPurpose.ONLINE_TILES)
        assertFalse(p.recentConnections().single().allowed)
    }

    @Test fun recordHoldsNoFieldsBeyondHostPurposeOutcome() {
        val fields = ConnectionRecord::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(setOf("timestampMillis", "host", "purpose", "allowed"), fields)
    }

    @Test fun endpointAddedAtRunTimeIsAllowedOnlyForItsPurposeAndListed() {
        val p = policy()
        assertFalse(p.authorize("Maps.Example.org", ConnectionPurpose.MAP_DOWNLOAD).isAllowed)
        p.addEndpoint(AllowedEndpoint("Maps.Example.org", ConnectionPurpose.MAP_DOWNLOAD, enabled = true))
        assertTrue(p.authorize("maps.example.org", ConnectionPurpose.MAP_DOWNLOAD).isAllowed)
        assertFalse(p.authorize("maps.example.org", ConnectionPurpose.OTHER).isAllowed)
        assertEquals(1, p.possibleConnections().count { it.host == "maps.example.org" })
        p.offlineMode = true
        assertFalse(p.authorize("maps.example.org", ConnectionPurpose.MAP_DOWNLOAD).isAllowed)
    }
}
