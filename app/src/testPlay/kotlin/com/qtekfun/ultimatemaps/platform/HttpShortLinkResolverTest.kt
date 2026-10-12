package com.qtekfun.ultimatemaps.platform

import com.qtekfun.ultimatemaps.core.net.AllowedEndpoint
import com.qtekfun.ultimatemaps.core.net.ConnectionPurpose
import com.qtekfun.ultimatemaps.core.net.DefaultNetworkPolicy
import com.qtekfun.ultimatemaps.link.ShortLinkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpShortLinkResolverTest {
    private val long = "https://www.google.com/maps/place/Plaza+Mayor/@40.4155,-3.7074,17z/data=!3d40.4155!4d-3.7074?entry=tts"

    private fun policy(enabled: Boolean = true, offline: Boolean = false) = DefaultNetworkPolicy(
        endpoints = listOf(
            AllowedEndpoint("maps.app.goo.gl", ConnectionPurpose.SHORT_LINK_RESOLVE, enabled),
            AllowedEndpoint("goo.gl", ConnectionPurpose.SHORT_LINK_RESOLVE, enabled),
        ),
        offlineMode = offline,
    )

    private class FakeProbe(private val replies: Map<String, RedirectProbe.Reply>) : RedirectProbe {
        val requested = mutableListOf<String>()
        override fun probe(url: String): RedirectProbe.Reply {
            requested += url
            return replies[url] ?: throw IOException("no route to $url")
        }
    }

    private fun expand(policy: DefaultNetworkPolicy, probe: RedirectProbe, url: String = "https://maps.app.goo.gl/AbCd") =
        runBlocking { HttpShortLinkResolver(policy, Dispatchers.Unconfined, probe).expand(url) }

    @Test
    fun theRedirectTargetIsReturnedWithoutFetchingIt() {
        val probe = FakeProbe(mapOf("https://maps.app.goo.gl/AbCd" to RedirectProbe.Reply(302, long)))
        assertEquals(ShortLinkResult.Expanded(long), expand(policy(), probe))
        // Exactly one request, to the short URL itself: the long Google page is never fetched.
        assertEquals(listOf("https://maps.app.goo.gl/AbCd"), probe.requested)
    }

    @Test
    fun aRedirectBetweenShortHostsIsFollowedAndEveryHopIsAuthorised() {
        val p = policy()
        val probe = FakeProbe(
            mapOf(
                "https://maps.app.goo.gl/AbCd" to RedirectProbe.Reply(301, "https://goo.gl/maps/xyz"),
                "https://goo.gl/maps/xyz" to RedirectProbe.Reply(302, long),
            ),
        )
        assertEquals(ShortLinkResult.Expanded(long), expand(p, probe))
        assertEquals(setOf("goo.gl", "maps.app.goo.gl"), p.recentConnections().map { it.host }.toSet())
        assertTrue(p.recentConnections().all { it.purpose == ConnectionPurpose.SHORT_LINK_RESOLVE && it.allowed })
    }

    @Test
    fun offlineModeSendsNothing() {
        val probe = FakeProbe(emptyMap())
        assertEquals(ShortLinkResult.Offline, expand(policy(offline = true), probe))
        assertTrue(probe.requested.isEmpty())
    }

    @Test
    fun aDisabledEndpointSendsNothing() {
        val probe = FakeProbe(emptyMap())
        assertEquals(ShortLinkResult.Unavailable, expand(policy(enabled = false), probe))
        assertTrue(probe.requested.isEmpty())
    }

    @Test
    fun onlyShortLinkHostsAreEverAsked() {
        val probe = FakeProbe(emptyMap())
        assertEquals(ShortLinkResult.Failed, expand(policy(), probe, "https://evil.example.com/x"))
        assertEquals(ShortLinkResult.Failed, expand(policy(), probe, "http://maps.app.goo.gl/AbCd"))
        assertTrue(probe.requested.isEmpty())
    }

    @Test
    fun networkErrorsAndNonRedirectsFail() {
        assertEquals(ShortLinkResult.Failed, expand(policy(), FakeProbe(emptyMap())))
        val ok = FakeProbe(mapOf("https://maps.app.goo.gl/AbCd" to RedirectProbe.Reply(200, null)))
        assertEquals(ShortLinkResult.Failed, expand(policy(), ok))
    }

    @Test
    fun aRedirectLoopBetweenShortHostsStops() {
        val probe = FakeProbe(
            mapOf(
                "https://maps.app.goo.gl/AbCd" to RedirectProbe.Reply(302, "https://goo.gl/a"),
                "https://goo.gl/a" to RedirectProbe.Reply(302, "https://maps.app.goo.gl/AbCd"),
            ),
        )
        assertEquals(ShortLinkResult.Failed, expand(policy(), probe))
        assertTrue(probe.requested.size <= 4)
    }

    @Test
    fun aConsentRedirectIsUnwrappedToItsContinueTarget() {
        val consent = "https://consent.google.com/m?continue=" + java.net.URLEncoder.encode(long, "UTF-8") + "&gl=ES"
        val probe = FakeProbe(mapOf("https://maps.app.goo.gl/AbCd" to RedirectProbe.Reply(302, consent)))
        assertEquals(ShortLinkResult.Expanded(long), expand(policy(), probe))
    }
}
