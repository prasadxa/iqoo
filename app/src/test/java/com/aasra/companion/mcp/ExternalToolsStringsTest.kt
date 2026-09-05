package com.aasra.companion.mcp

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalToolsStringsTest {
    @Test fun englishAndHindiStringsHaveMatchingKeysAndArguments() {
        fun strings(directory: String): Map<String, String> {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/$directory/external_tools.xml"))
            val elements = document.getElementsByTagName("string")
            return (0 until elements.length).associate { index ->
                val element = elements.item(index)
                element.attributes.getNamedItem("name").nodeValue to element.textContent
            }.also { assertEquals(elements.length, it.size) }
        }
        val english = strings("values")
        val hindi = strings("values-hi")
        assertEquals(english.keys, hindi.keys)
        val arguments = Regex("%\\d+\\$[sd]")
        english.forEach { (name, text) ->
            assertTrue(name, text.isNotBlank() && hindi.getValue(name).isNotBlank())
            assertEquals(name, arguments.findAll(text).map { it.value }.toList(),
                arguments.findAll(hindi.getValue(name)).map { it.value }.toList())
        }
    }
}
