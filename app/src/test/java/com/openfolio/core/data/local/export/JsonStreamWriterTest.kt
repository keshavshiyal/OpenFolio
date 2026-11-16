package com.openfolio.core.data.local.export

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class JsonStreamWriterTest {

    @Test
    fun writeEmptyObject() {
        val stringWriter = StringWriter()
        val writer = JsonStreamWriter(stringWriter, indent = "")
        writer.use {
            it.beginObject().endObject()
        }
        assertEquals("{}", stringWriter.toString().trim())
    }

    @Test
    fun writeSimpleObjectWithPrimitiveValues() {
        val stringWriter = StringWriter()
        val writer = JsonStreamWriter(stringWriter, indent = "")
        writer.use {
            it.beginObject()
                .name("name").value("OpenFolio")
                .name("version").value(9L)
                .name("active").value(true)
                .name("ratio").value(1.25)
                .name("emptyField").nullValue()
                .endObject()
        }

        val json = JSONObject(stringWriter.toString())
        assertEquals("OpenFolio", json.getString("name"))
        assertEquals(9, json.getInt("version"))
        assertEquals(true, json.getBoolean("active"))
        assertEquals(1.25, json.getDouble("ratio"), 0.001)
        assertTrue(json.isNull("emptyField"))
    }

    @Test
    fun writeArrayOfObjects() {
        val stringWriter = StringWriter()
        val writer = JsonStreamWriter(stringWriter, indent = "")
        writer.use {
            it.beginObject()
                .name("items").beginArray()
                .beginObject().name("id").value("1").endObject()
                .beginObject().name("id").value("2").endObject()
                .endArray()
                .endObject()
        }

        val json = JSONObject(stringWriter.toString())
        val items = json.getJSONArray("items")
        assertEquals(2, items.length())
        assertEquals("1", items.getJSONObject(0).getString("id"))
        assertEquals("2", items.getJSONObject(1).getString("id"))
    }

    @Test
    fun writeEscapedStrings() {
        val stringWriter = StringWriter()
        val writer = JsonStreamWriter(stringWriter, indent = "")
        val specialString = "Line1\nLine2\tTabbed \"Quoted\" \\Backslash\\ \r\b"
        writer.use {
            it.beginObject()
                .name("text").value(specialString)
                .endObject()
        }

        val json = JSONObject(stringWriter.toString())
        assertEquals(specialString, json.getString("text"))
    }

    @Test
    fun writePrettyFormattedJson() {
        val stringWriter = StringWriter()
        val writer = JsonStreamWriter(stringWriter, indent = "  ")
        writer.use {
            it.beginObject()
                .name("title").value("Test")
                .endObject()
        }
        val expected = "{\n  \"title\": \"Test\"\n}"
        assertEquals(expected, stringWriter.toString().trim())
    }
}
