package com.aasra.cloud

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AasraToolsTest {

    @Test
    fun cloudSchemaHasCloudSearchButNotLocalEscalation() {
        val names = AasraTools.schemas().map { tool ->
            tool.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content
        }

        assertTrue("web_search" in names)
        assertFalse("escalate" in names)
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun everyFunctionParameterIsRequired() {
        AasraTools.schemas().forEach { tool ->
            val parameters = tool.jsonObject.getValue("function").jsonObject
                .getValue("parameters").jsonObject
            val properties = parameters.getValue("properties").jsonObject.keys
            val required = parameters.getValue("required").jsonArray
                .map { it.jsonPrimitive.content }.toSet()
            assertEquals(properties, required)
        }
    }
}
