package com.phoneclaw.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSupportTest {
    @Test
    fun normalizesBareDomainToHttps() {
        assertEquals("https://openai.com", normalizeWebUrl("openai.com"))
    }

    @Test
    fun extractsFirstWebTargetFromSentence() {
        assertEquals("https://example.com/docs", extractFirstWebTarget("帮我读取 https://example.com/docs 的内容"))
    }

    @Test
    fun extractsReadableTextAndTitleFromHtml() {
        val html = """
            <html>
              <head><title>Example Page</title></head>
              <body>
                <article>
                  <h1>Hello</h1>
                  <p>This is a simple web page.</p>
                </article>
              </body>
            </html>
        """.trimIndent()

        assertEquals("Example Page", extractHtmlTitle(html))
        val text = extractReadableText(html)
        assertTrue(text.contains("Hello"))
        assertTrue(text.contains("This is a simple web page."))
    }
}
