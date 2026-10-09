package com.qtekfun.ultimatemaps.i18n

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Keeps every translation in step with the English source (`values/`), so a string added later cannot be forgotten
 * and a format argument cannot be lost or reordered in a translation. Pure JVM: it reads the XML files directly.
 *
 * Rules checked for each `values-<language>` folder:
 * - the same string and plurals names as `values/`, none missing and none extra (a `translatable="false"` string
 *   exists only in `values/`);
 * - the same format arguments (`%1$s`, `%2$d`, `%%`) in each string, and in each plural item compared with the
 *   English "other" item (a "one" item may leave the number out: "one place" instead of "%d place");
 * - apostrophes and double quotes escaped the way aapt wants;
 * - every language has a `plurals` "other" item and only known quantities;
 * - `res/xml/locales_config.xml` (Android 13+ per-app language) lists exactly the English default plus these folders.
 */
class LocaleResourcesTest {
    private val res = File("src/main/res")

    private class Entry(val text: String, val translatable: Boolean)

    /** name -> entry for strings, and "name#quantity" -> entry for plural items, of every strings*.xml in [dir]. */
    private fun load(dir: File): Map<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        val files = dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }?.sortedBy { it.name }.orEmpty()
        for (file in files) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                val translatable = e.getAttribute("translatable") != "false"
                when (e.tagName) {
                    "string" -> {
                        val name = e.getAttribute("name")
                        if (name in out) fail("${file.name}: string '$name' is defined twice")
                        out[name] = Entry(e.textContent, translatable)
                    }
                    "plurals" -> {
                        val name = e.getAttribute("name")
                        val items = e.getElementsByTagName("item")
                        for (j in 0 until items.length) {
                            val item = items.item(j) as Element
                            val key = name + "#" + item.getAttribute("quantity")
                            if (key in out) fail("${file.name}: plural item '$key' is defined twice")
                            out[key] = Entry(item.textContent, translatable)
                        }
                    }
                    "string-array", "integer-array" -> fail("${file.name}: arrays are not covered by this test; add them to it")
                }
            }
        }
        return out
    }

    private val baseline: Map<String, Entry> by lazy { load(File(res, "values")) }

    /** The folders of the translated languages: `values-ca`, `values-es`, ... (not `values-night`, `values-v31`). */
    private fun translationDirs(): List<File> =
        res.listFiles { f -> f.isDirectory && Regex("values-[a-z]{2,3}").matches(f.name) }.orEmpty().sortedBy { it.name }

    private val specs = Regex("%(?:\\d+\\$)?[-#+0,(]*\\d*(?:\\.\\d+)?[sdfxXcb%]")

    private fun placeholders(text: String): List<String> = specs.findAll(text).map { it.value }.sorted().toList()

    private fun baseKeyOf(key: String): String = key

    private fun isPluralKey(key: String) = '#' in key

    @Test
    fun theBaselineIsEnglishAndHasStrings() {
        assertTrue(baseline.size > 500, "values/ has only ${baseline.size} entries; is the working directory the app module?")
    }

    @Test
    fun atLeastTheAnnouncedLanguagesAreTranslated() {
        val have = translationDirs().map { it.name.removePrefix("values-") }.toSet()
        val required = setOf("es", "ca", "gl", "eu", "fr", "de", "pt", "it")
        assertTrue(have.containsAll(required), "Missing translations for ${required - have}")
    }

    @Test
    fun everyLanguageHasTheSameKeysAsTheEnglishSource() {
        // Plural items are checked by pluralsKeepTheirNamesAndHaveOther: a language may need other quantities than English.
        val expected = baseline.filterValues { it.translatable }.keys.filterNot(::isPluralKey).toSet()
        for (dir in translationDirs()) {
            val translated = load(dir).filterKeys { !isPluralKey(it) }
            val missing = expected - translated.keys
            val extra = translated.keys - expected
            assertTrue(missing.isEmpty(), "${dir.name}: missing strings ${missing.take(10)} (${missing.size} in total)")
            assertTrue(extra.isEmpty(), "${dir.name}: strings that are not in values/ or are translatable=false: ${extra.take(10)}")
            for ((key, entry) in translated) assertTrue(entry.translatable, "${dir.name}: '$key' must not be translatable=false")
        }
    }

    @Test
    fun formatArgumentsMatchTheEnglishSource() {
        for (dir in translationDirs()) {
            for ((key, entry) in load(dir)) {
                val base = (if (isPluralKey(key)) baseline[key.substringBefore('#') + "#other"] else baseline[key])?.text ?: continue
                val want = placeholders(base)
                val have = placeholders(entry.text)
                if (isPluralKey(key) && key.endsWith("#one") && have.isEmpty()) continue // "one place": the number may be written out
                assertEquals(want, have, "${dir.name}: '$key' has other format arguments than the English '$base'")
            }
        }
    }

    @Test
    fun severalArgumentsAreAlwaysPositional() {
        for ((key, entry) in baseline) {
            val args = placeholders(entry.text).filter { it != "%%" }
            if (args.size > 1) {
                assertTrue(args.all { Regex("%\\d+\\$.*").matches(it) }, "values/: '$key' uses several arguments that are not positional (%1\$s)")
            }
        }
    }

    @Test
    fun apostrophesAndQuotesAreEscaped() {
        val raw = Regex("(?<!\\\\)['\"]")
        for (dir in translationDirs()) {
            for ((key, entry) in load(dir)) {
                assertTrue(!raw.containsMatchIn(entry.text), "${dir.name}: '$key' has an unescaped quote or apostrophe: ${entry.text}")
            }
        }
    }

    @Test
    fun pluralsKeepTheirNamesAndHaveOther() {
        val known = setOf("zero", "one", "two", "few", "many", "other")
        val basePlurals = baseline.keys.filter(::isPluralKey).map { it.substringBefore('#') }.toSet()
        for (dir in translationDirs()) {
            val translated = load(dir).keys.filter(::isPluralKey)
            assertEquals(basePlurals, translated.map { it.substringBefore('#') }.toSet(), "${dir.name}: plurals differ from values/")
            for (name in basePlurals) {
                val quantities = translated.filter { it.substringBefore('#') == name }.map { it.substringAfter('#') }
                assertTrue("other" in quantities, "${dir.name}: plurals '$name' has no 'other' item")
                assertTrue(known.containsAll(quantities), "${dir.name}: plurals '$name' has an unknown quantity $quantities")
            }
        }
    }

    @Test
    fun localesConfigListsEnglishAndEveryTranslatedFolder() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "xml/locales_config.xml"))
        val nodes = doc.getElementsByTagName("locale")
        val declared = (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("android:name") }
        assertEquals(declared.size, declared.toSet().size, "locales_config.xml lists a language twice: $declared")
        val folders = translationDirs().map { it.name.removePrefix("values-") }.toSet() + "en"
        assertEquals(folders, declared.toSet(), "locales_config.xml and the values-<language> folders differ")
    }

    @Test
    fun theManifestPointsAtTheLocalesConfig() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("android:localeConfig=\"@xml/locales_config\"" in manifest, "AndroidManifest.xml must declare android:localeConfig")
    }
}
