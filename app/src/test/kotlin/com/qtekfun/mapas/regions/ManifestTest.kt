package com.qtekfun.mapas.regions

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The network permission exists for the download flow only, and the download service is a `dataSync` FGS. */
class ManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun internetIsRequestedAndNotRemoved() {
        assertTrue(manifest.contains("""<uses-permission android:name="android.permission.INTERNET" />"""))
        assertFalse(manifest.contains("tools:node=\"remove\""))
    }

    @Test
    fun downloadServiceIsADataSyncForegroundService() {
        val service = Regex("<service[^>]*RegionDownloadService[^>]*/>", RegexOption.DOT_MATCHES_ALL).find(manifest)?.value
        assertTrue(service != null)
        assertTrue(service.contains("android:foregroundServiceType=\"dataSync\""))
        assertTrue(service.contains("android:exported=\"false\""))
        for (p in listOf("FOREGROUND_SERVICE", "FOREGROUND_SERVICE_DATA_SYNC", "POST_NOTIFICATIONS")) {
            assertTrue(manifest.contains("android.permission.$p\""), "missing $p")
        }
    }

    @Test
    fun noOtherComponentTouchesTheNetworkOrIsExported() {
        // only the launcher activity (links) is exported
        val exported = Regex("android:exported=\"true\"").findAll(manifest).count()
        assertTrue(exported == 1, "exported components: $exported")
    }
}
