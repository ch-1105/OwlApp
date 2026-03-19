package com.phoneclaw.app.learner

import com.phoneclaw.app.contracts.ModelRequest
import com.phoneclaw.app.contracts.ModelResponse
import com.phoneclaw.app.explorer.AccessibilityNodeSnapshot
import com.phoneclaw.app.explorer.PageTreeSnapshot
import com.phoneclaw.app.gateway.ports.ModelPort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPageAnalyzerTest {
    @Test
    fun analyzePage_returnsStructuredResultWhenModelReturnsJson() = runTest {
        val modelPort = RecordingModelPort(
            response = ModelResponse(
                requestId = "model-1",
                provider = "stub",
                modelId = "stub",
                outputText =
                    """
                    {
                      "suggested_page_spec": {
                        "page_id": "wifi_settings",
                        "page_name": "Wi-Fi Settings",
                        "activity_name": "SettingsActivity",
                        "match_rules": [
                          { "type": "activity_name", "value": "SettingsActivity" },
                          { "type": "text_contains", "value": "Wi-Fi" }
                        ],
                        "available_actions": ["tap_wifi"],
                        "evidence_fields": {
                          "primary_signal": "wifi row visible"
                        }
                      },
                      "clickable_elements": [
                        {
                          "resource_id": "com.example.settings:id/wifi_row",
                          "text": "Wi-Fi",
                          "content_description": "Wi-Fi entry",
                          "suggested_action_name": "tap_wifi",
                          "suggested_description": "Tap the Wi-Fi row"
                        }
                      ],
                      "navigation_hints": [
                        "Tap Wi-Fi to open the next page."
                      ]
                    }
                    """.trimIndent(),
            ),
        )
        val analyzer = AiPageAnalyzer(
            modelPort = modelPort,
            allowCloud = true,
            preferredProvider = "stub",
        )

        val result = analyzer.analyzePage(
            appPackage = "com.example.settings",
            pageTree = samplePageTree(),
            screenshot = byteArrayOf(1, 2, 3),
        )

        assertEquals("page_analysis", modelPort.lastRequest?.taskType)
        assertTrue(
            modelPort.lastRequest?.inputMessages.orEmpty()
                .any { it.contains("Screenshot metadata: present (3 bytes)") },
        )
        assertEquals("wifi_settings", result.suggestedPageSpec.pageId)
        assertEquals("Wi-Fi Settings", result.suggestedPageSpec.pageName)
        assertEquals(listOf("tap_wifi"), result.suggestedPageSpec.availableActions)
        assertEquals("tap_wifi", result.clickableElements.single().suggestedActionName)
        assertEquals("Tap Wi-Fi to open the next page.", result.navigationHints.single())
    }

    @Test
    fun analyzePage_fallsBackToLocalHeuristicsWhenModelOutputIsNotJson() = runTest {
        val modelPort = RecordingModelPort(
            response = ModelResponse(
                requestId = "model-2",
                provider = "stub",
                modelId = "stub",
                outputText = "This is not structured JSON.",
            ),
        )
        val analyzer = AiPageAnalyzer(
            modelPort = modelPort,
            allowCloud = true,
            preferredProvider = "stub",
        )

        val result = analyzer.analyzePage(
            appPackage = "com.example.settings",
            pageTree = samplePageTree(),
        )

        val suggestedActionName = result.clickableElements.first().suggestedActionName

        assertEquals("Wi-Fi", result.suggestedPageSpec.pageName)
        assertEquals("com.example.settings", result.suggestedPageSpec.appPackage)
        assertTrue(suggestedActionName.startsWith("tap_"))
        assertTrue(result.suggestedPageSpec.availableActions.contains(suggestedActionName))
        assertTrue(result.navigationHints.any { it.contains("clickable elements") })
        assertTrue(result.navigationHints.any { it.contains("scrollable content") })
        assertTrue(result.navigationHints.any { it.contains("editable fields") })
    }

    private class RecordingModelPort(
        private val response: ModelResponse,
    ) : ModelPort {
        var lastRequest: ModelRequest? = null

        override suspend fun infer(request: ModelRequest): ModelResponse {
            lastRequest = request
            return response.copy(requestId = request.requestId)
        }
    }
}

private fun samplePageTree(): PageTreeSnapshot {
    return PageTreeSnapshot(
        packageName = "com.example.settings",
        activityName = "SettingsActivity",
        timestamp = 123L,
        nodes = listOf(
            AccessibilityNodeSnapshot(
                nodeId = "0",
                className = "android.widget.FrameLayout",
                text = null,
                contentDescription = null,
                resourceId = "com.example.settings:id/root",
                isClickable = false,
                isScrollable = true,
                isEditable = false,
                bounds = "0,0,1080,2400",
                children = listOf(
                    AccessibilityNodeSnapshot(
                        nodeId = "0/0",
                        className = "android.widget.TextView",
                        text = "Wi-Fi",
                        contentDescription = "Wi-Fi entry",
                        resourceId = "com.example.settings:id/wifi_row",
                        isClickable = true,
                        isScrollable = false,
                        isEditable = false,
                        bounds = "0,200,1080,320",
                        children = emptyList(),
                    ),
                    AccessibilityNodeSnapshot(
                        nodeId = "0/1",
                        className = "android.widget.EditText",
                        text = null,
                        contentDescription = "Search settings",
                        resourceId = "com.example.settings:id/search",
                        isClickable = false,
                        isScrollable = false,
                        isEditable = true,
                        bounds = "0,80,1080,180",
                        children = emptyList(),
                    ),
                ),
            ),
        ),
    )
}

