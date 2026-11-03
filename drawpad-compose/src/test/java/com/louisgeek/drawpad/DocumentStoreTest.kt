package com.louisgeek.drawpad

import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class DocumentStoreTest {
    @Test
    fun unsupportedSchemaIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            com.louisgeek.drawpad.storage.DocumentCodec.decode(JSONObject().put("schema", 999).toString().toByteArray())
        }
    }
}
