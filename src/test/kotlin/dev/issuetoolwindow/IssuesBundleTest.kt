package dev.issuetoolwindow

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class IssuesBundleTest {
    private val english = load("messages/IssuesBundle.properties")
    private val german = load("messages/IssuesBundle_de.properties")

    @Test
    fun `english and german bundles contain the same keys`() {
        assertEquals(emptySet(), english.keys - german.keys, "Keys missing in IssuesBundle_de.properties")
        assertEquals(emptySet(), german.keys - english.keys, "Keys missing in IssuesBundle.properties")
    }

    @Test
    fun `translations keep the placeholders of the english text`() {
        for ((key, text) in english) {
            val translation = german[key] ?: continue
            assertEquals(placeholders(text), placeholders(translation), "Placeholders of $key differ")
        }
    }

    @Test
    fun `no text is empty`() {
        for ((name, bundle) in listOf("english" to english, "german" to german)) {
            val empty = bundle.filterValues { it.isBlank() }.keys
            assertTrue(empty.isEmpty(), "Empty $name texts: $empty")
        }
    }

    private fun placeholders(text: String): Set<String> =
        Regex("""\{(\d+)[^}]*}""").findAll(text).map { it.groupValues[1] }.toSet()

    private fun load(path: String): Map<String, String> {
        val stream = javaClass.classLoader.getResourceAsStream(path) ?: fail("$path not found")
        val properties = Properties()
        stream.reader(Charsets.UTF_8).use(properties::load)
        return properties.stringPropertyNames().associateWith(properties::getProperty)
    }
}
