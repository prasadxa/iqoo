package com.aasra.companion.ui.reminders

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderStringsTest {
    @Test fun englishAndHindiHaveMatchingNonEmptyStringsAndFormatArguments() {
        fun strings(directory: String): Map<String, String> {
            val file = File("src/main/res/$directory/reminders.xml")
            val elements = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                .getElementsByTagName("string")
            return (0 until elements.length).associate { index ->
                val element = elements.item(index)
                element.attributes.getNamedItem("name").nodeValue to element.textContent
            }.also { assertEquals("Duplicate resource names", elements.length, it.size) }
        }
        val english = strings("values")
        val hindi = strings("values-hi")
        assertEquals(english.keys, hindi.keys)
        val arguments = Regex("%\\d+\\$[sd]")
        english.forEach { (name, text) ->
            assertTrue(name, text.isNotBlank())
            assertTrue(name, hindi.getValue(name).isNotBlank())
            assertEquals(name, arguments.findAll(text).map { it.value }.toList(),
                arguments.findAll(hindi.getValue(name)).map { it.value }.toList())
        }
    }
}
