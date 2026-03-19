package com.phoneclaw.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StructuredJsonContentParserTest {
    @Test
    fun parseJsonTextOrNull_acceptsPlainJsonObject() {
        val parsed = StructuredJsonContentParser.parseJsonTextOrNull(
            """
            {
              "action": {
                "action_id": "open_web_url"
              }
            }
            """.trimIndent(),
        )

        assertEquals(
            """
            {
              "action": {
                "action_id": "open_web_url"
              }
            }
            """.trimIndent(),
            parsed,
        )
    }

    @Test
    fun parseJsonTextOrNull_acceptsJsonCodeFence() {
        val parsed = StructuredJsonContentParser.parseJsonTextOrNull(
            """
            ```json
            {
              "error": "unsupported"
            }
            ```
            """.trimIndent(),
        )

        assertEquals(
            """
            {
              "error": "unsupported"
            }
            """.trimIndent(),
            parsed,
        )
    }

    @Test
    fun parseJsonTextOrNull_rejectsJsonWrappedInExtraExplanation() {
        val content = """
            Here is the action you requested:
            {"action":{"action_id":"open_web_url"}}
            Let me know if you need anything else.
        """.trimIndent()

        val parsed = StructuredJsonContentParser.parseJsonTextOrNull(content)

        assertNull(parsed)
    }
}
