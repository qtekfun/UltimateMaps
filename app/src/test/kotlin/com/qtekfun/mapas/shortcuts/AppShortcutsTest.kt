package com.qtekfun.mapas.shortcuts

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.qtekfun.mapas.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppShortcutsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class Entry(val id: String, val shortLabel: Int, val longLabel: Int, val action: String, val pkg: String, val cls: String)

    private fun entries(): List<Entry> {
        val ns = "http://schemas.android.com/apk/res/android"
        val xml = context.resources.getXml(R.xml.shortcuts)
        val out = ArrayList<Entry>()
        var id = ""; var short = 0; var long = 0
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            if (xml.eventType != XmlPullParser.START_TAG) continue
            when (xml.name) {
                "shortcut" -> {
                    id = xml.getAttributeValue(ns, "shortcutId")
                    short = xml.getAttributeResourceValue(ns, "shortcutShortLabel", 0)
                    long = xml.getAttributeResourceValue(ns, "shortcutLongLabel", 0)
                }
                "intent" -> out += Entry(
                    id, short, long, xml.getAttributeValue(ns, "action"),
                    xml.getAttributeValue(ns, "targetPackage"), xml.getAttributeValue(ns, "targetClass"),
                )
            }
        }
        return out
    }

    private fun localized(tag: String): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).also { it.setLocale(Locale.forLanguageTag(tag)) })

    @Test fun everyActionMapsToATargetAndBack() {
        for (t in ShortcutTarget.entries) assertEquals(t, AppShortcuts.parse(t.action))
        assertEquals(ShortcutTarget.entries.size, ShortcutTarget.entries.map { it.action }.toSet().size)
    }

    @Test fun otherActionsAreIgnored() {
        assertNull(AppShortcuts.parse(null))
        assertNull(AppShortcuts.parse("android.intent.action.MAIN"))
        assertNull(AppShortcuts.parse("android.intent.action.VIEW"))
        assertNull(AppShortcuts.parse(""))
    }

    @Test fun theManifestDeclaresTheShortcutsOnTheLauncherActivity() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("android.app.shortcuts" in manifest && "@xml/shortcuts" in manifest)
    }

    @Test fun shortcutsXmlCoversEveryTargetWithAppIntents() {
        val list = entries()
        assertEquals(ShortcutTarget.entries.map { it.action }.toSet(), list.map { it.action }.toSet())
        assertEquals(list.size, list.map { it.id }.toSet().size)
        for (e in list) {
            assertEquals(context.packageName, e.pkg) // the XML repeats the application id: it must not drift
            assertEquals("com.qtekfun.mapas.MainActivity", e.cls)
            assertTrue(AppShortcuts.parse(e.action) != null, e.action)
        }
        assertTrue(list.size <= 4, "launchers show at most four static shortcuts")
    }

    @Test fun labelsExistInBothLanguagesAndAreShortEnough() {
        val en = localized("en")
        val es = localized("es")
        for (e in entries()) {
            for ((res, max) in listOf(e.shortLabel to 10, e.longLabel to 25)) {
                assertTrue(res != 0, "${e.id}: label is not a string resource")
                val a = en.getString(res); val b = es.getString(res)
                assertTrue(a.isNotBlank() && b.isNotBlank())
                assertTrue(a.length <= max && b.length <= max, "${e.id}: '$a' / '$b' longer than $max")
            }
            assertNotEquals(en.getString(e.shortLabel), es.getString(e.shortLabel), "${e.id}: short label not translated")
        }
    }
}
