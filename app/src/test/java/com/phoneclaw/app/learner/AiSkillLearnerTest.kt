package com.phoneclaw.app.learner

import com.phoneclaw.app.contracts.ModelRequest
import com.phoneclaw.app.contracts.ModelResponse
import com.phoneclaw.app.contracts.PageMatchRule
import com.phoneclaw.app.contracts.PageSpec
import com.phoneclaw.app.explorer.AccessibilityNodeSnapshot
import com.phoneclaw.app.explorer.PageTreeSnapshot
import com.phoneclaw.app.gateway.ports.ClickableElementSuggestion
import com.phoneclaw.app.gateway.ports.ModelPort
import com.phoneclaw.app.gateway.ports.PageAnalysisResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSkillLearnerTest {
    @Test
    fun generateSkillDraft_returnsStructuredDraftWhenModelReturnsJson() = runTest {
        val modelPort = RecordingModelPort(
            response = ModelResponse(
                requestId = "model-1",
                provider = "stub",
                modelId = "stub",
                outputText = validModelDraftJson(),
            ),
        )
        val learner = AiSkillLearner(
            modelPort = modelPort,
            allowCloud = true,
            preferredProvider = "stub",
        )

        val draft = learner.generateSkillDraft(
            appPackage = "com.example.settings",
            appName = "Settings",
            pages = listOf(sampleLearningInput()),
        )

        assertEquals("skill_generation", modelPort.lastRequest?.taskType)
        assertTrue(
            modelPort.lastRequest?.inputMessages.orEmpty()
                .any { it.contains("Explored page count: 1") },
        )
        assertEquals("learned.com.example.settings", draft.manifest.skillId)
        assertEquals("0.2.0", draft.manifest.skillVersion)
        assertEquals("tap_wifi", draft.manifest.actions.single().actionId)
        assertEquals("wifi_settings", draft.pageGraph.pages.single().pageId)
        assertEquals("tap_wifi", draft.bindings.single().actionId)
        assertEquals(123L, draft.evidence.single().capturedAt)
        assertTrue(draft.evidence.single().snapshotJson.contains("wifi_row"))
    }

    @Test
    fun generateSkillDraft_fallsBackToLocalDraftWhenModelOutputIsInvalid() = runTest {
        val modelPort = RecordingModelPort(
            response = ModelResponse(
                requestId = "model-2",
                provider = "stub",
                modelId = "stub",
                outputText = "This is not JSON.",
            ),
        )
        val learner = AiSkillLearner(
            modelPort = modelPort,
            allowCloud = true,
            preferredProvider = "stub",
        )

        val draft = learner.generateSkillDraft(
            appPackage = "com.example.settings",
            appName = "Settings",
            pages = listOf(sampleLearningInput()),
        )

        assertEquals("learned.com.example.settings", draft.manifest.skillId)
        assertEquals("Settings Learned Skill", draft.manifest.displayName)
        assertFalse(draft.manifest.enabled)
        assertEquals("accessibility", draft.manifest.actions.first().executorType)
        assertTrue(draft.manifest.actions.first().requiresConfirmation)
        assertEquals(draft.manifest.actions.size, draft.bindings.size)
        assertEquals("wifi_settings", draft.pageGraph.pages.single().pageId)
        assertEquals("Wi-Fi", draft.pageGraph.pages.single().pageName)
        assertTrue(draft.evidence.single().snapshotJson.contains("com.example.settings"))
    }

    @Test
    fun generateSkillDraft_fallsBackWhenModelRenamesPageIds() = runTest {
        val modelPort = RecordingModelPort(
            response = ModelResponse(
                requestId = "model-3",
                provider = "stub",
                modelId = "stub",
                outputText = validModelDraftJson().replace("wifi_settings", "wifi_detail"),
            ),
        )
        val learner = AiSkillLearner(
            modelPort = modelPort,
            allowCloud = true,
            preferredProvider = "stub",
        )

        val draft = learner.generateSkillDraft(
            appPackage = "com.example.settings",
            appName = "Settings",
            pages = listOf(sampleLearningInput()),
        )

        assertEquals("0.1.0", draft.manifest.skillVersion)
        assertEquals("wifi_settings", draft.pageGraph.pages.single().pageId)
        assertEquals("wifi_settings", draft.evidence.single().pageId)
    }

    @Test
    fun generateSkillDraft_fallsBackWhenPageGraphReferencesUnknownAction() = runTest {
        val invalidGraphJson = validModelDraftJson()
            .replace("\"available_actions\": [\"tap_wifi\"]", "\"available_actions\": [\"tap_unknown\"]")
            .replace(
                "\"transitions\": []",
                """
                "transitions": [
                  {
                    "from_page_id": "wifi_settings",
                    "to_page_id": "missing_page",
                    "trigger_action_id": "tap_unknown",
                    "trigger_node_description": "Tap something"
                  }
                ]
                """.trimIndent(),
            )
        val modelPort = RecordingModelPort(
            response = ModelResponse(
                requestId = "model-4",
                provider = "stub",
                modelId = "stub",
                outputText = invalidGraphJson,
            ),
        )
        val learner = AiSkillLearner(
            modelPort = modelPort,
            allowCloud = true,
            preferredProvider = "stub",
        )

        val draft = learner.generateSkillDraft(
            appPackage = "com.example.settings",
            appName = "Settings",
            pages = listOf(sampleLearningInput()),
        )

        assertEquals("0.1.0", draft.manifest.skillVersion)
        assertEquals(listOf("tap_wifi"), draft.pageGraph.pages.single().availableActions)
        assertTrue(draft.pageGraph.transitions.isEmpty())
    }

    @Test
    fun generateSkillDraft_fallbackKeepsActionIdsUniqueAcrossPages() = runTest {
        val modelPort = RecordingModelPort(
            response = ModelResponse(
                requestId = "model-5",
                provider = "stub",
                modelId = "stub",
                outputText = "not json",
            ),
        )
        val learner = AiSkillLearner(
            modelPort = modelPort,
            allowCloud = true,
            preferredProvider = "stub",
        )

        val draft = learner.generateSkillDraft(
            appPackage = "com.example.settings",
            appName = "Settings",
            pages = listOf(
                sampleLearningInput(),
                sampleLearningInput(
                    pageId = "advanced_settings",
                    pageName = "Advanced",
                    actionName = "tap_wifi",
                    actionDescription = "Tap Wi-Fi",
                ),
            ),
        )

        val actionIds = draft.manifest.actions.map { it.actionId }

        assertEquals(2, actionIds.size)
        assertEquals(2, actionIds.distinct().size)
        assertEquals(2, draft.pageGraph.pages.size)
        assertTrue(draft.pageGraph.pages.all { it.availableActions.single() in actionIds })
    }

    @Test
    fun generateSkillDraft_fallbackCarriesTransitionAndScreenshotEvidence() = runTest {
        val modelPort = RecordingModelPort(
            response = ModelResponse(
                requestId = "model-6",
                provider = "stub",
                modelId = "stub",
                outputText = "not json",
            ),
        )
        val learner = AiSkillLearner(
            modelPort = modelPort,
            allowCloud = true,
            preferredProvider = "stub",
        )
        val transition = ExplorationTransition(
            fromPageId = "wifi_settings",
            toPageId = "advanced_settings",
            triggerNodeId = "0/0",
            triggerNodeDescription = "Wi-Fi",
        )

        val draft = learner.generateSkillDraft(
            appPackage = "com.example.settings",
            appName = "Settings",
            pages = listOf(
                sampleLearningInput(
                    screenshotBytes = byteArrayOf(1),
                ),
                sampleLearningInput(
                    pageId = "advanced_settings",
                    pageName = "Advanced",
                    actionName = "tap_advanced",
                    actionDescription = "Tap Advanced",
                    screenshotBytes = byteArrayOf(2),
                    arrivedBy = transition,
                ),
            ),
        )

        val pageTransition = draft.pageGraph.transitions.single()
        val lastEvidence = draft.evidence.last()

        assertEquals("wifi_settings", pageTransition.fromPageId)
        assertEquals("advanced_settings", pageTransition.toPageId)
        assertEquals("tap_wifi", pageTransition.triggerActionId)
        assertEquals("Wi-Fi", pageTransition.triggerNodeDescription)
        assertEquals(transition, lastEvidence.arrivedBy)
        assertArrayEquals(byteArrayOf(2), lastEvidence.screenshotBytes)
    }

    @Test
    fun generateSkillDraft_fallsBackWhenModelDropsObservedTransition() = runTest {
        val modelPort = RecordingModelPort(
            response = ModelResponse(
                requestId = "model-7",
                provider = "stub",
                modelId = "stub",
                outputText = validTwoPageModelDraftJson(includeTransitions = false),
            ),
        )
        val learner = AiSkillLearner(
            modelPort = modelPort,
            allowCloud = true,
            preferredProvider = "stub",
        )
        val transition = ExplorationTransition(
            fromPageId = "wifi_settings",
            toPageId = "advanced_settings",
            triggerNodeId = "0/0",
            triggerNodeDescription = "Wi-Fi",
        )

        val draft = learner.generateSkillDraft(
            appPackage = "com.example.settings",
            appName = "Settings",
            pages = listOf(
                sampleLearningInput(),
                sampleLearningInput(
                    pageId = "advanced_settings",
                    pageName = "Advanced",
                    actionName = "tap_advanced",
                    actionDescription = "Tap Advanced",
                    arrivedBy = transition,
                ),
            ),
        )

        assertEquals("0.1.0", draft.manifest.skillVersion)
        assertEquals(1, draft.pageGraph.transitions.size)
        assertEquals("wifi_settings", draft.pageGraph.transitions.single().fromPageId)
        assertEquals("advanced_settings", draft.pageGraph.transitions.single().toPageId)
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

private fun sampleLearningInput(
    pageId: String = "wifi_settings",
    pageName: String = "Wi-Fi",
    actionName: String = "tap_wifi",
    actionDescription: String = "Tap Wi-Fi",
    screenshotBytes: ByteArray? = null,
    arrivedBy: ExplorationTransition? = null,
): PageLearningInput {
    return PageLearningInput(
        pageTree = PageTreeSnapshot(
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
                            text = pageName,
                            contentDescription = "$pageName entry",
                            resourceId = "com.example.settings:id/wifi_row",
                            isClickable = true,
                            isScrollable = false,
                            isEditable = false,
                            bounds = "0,200,1080,320",
                            children = emptyList(),
                        ),
                    ),
                ),
            ),
        ),
        analysis = PageAnalysisResult(
            suggestedPageSpec = PageSpec(
                pageId = pageId,
                pageName = pageName,
                appPackage = "com.example.settings",
                activityName = "SettingsActivity",
                matchRules = listOf(
                    PageMatchRule(type = "activity_name", value = "SettingsActivity"),
                    PageMatchRule(type = "text_contains", value = pageName),
                ),
                availableActions = listOf(actionName),
                evidenceFields = mapOf("primary_signal" to "wifi row visible"),
            ),
            clickableElements = listOf(
                ClickableElementSuggestion(
                    resourceId = "com.example.settings:id/wifi_row",
                    text = pageName,
                    contentDescription = "$pageName entry",
                    suggestedActionName = actionName,
                    suggestedDescription = actionDescription,
                ),
            ),
            navigationHints = listOf("Tap Wi-Fi to open the next page."),
        ),
        screenshotBytes = screenshotBytes,
        arrivedBy = arrivedBy,
        capturedAt = 123L,
    )
}

private fun validTwoPageModelDraftJson(includeTransitions: Boolean): String {
    val transitions = if (includeTransitions) {
        """
            "transitions": [
              {
                "from_page_id": "wifi_settings",
                "to_page_id": "advanced_settings",
                "trigger_action_id": "tap_wifi",
                "trigger_node_description": "Wi-Fi"
              }
            ]
        """.trimIndent()
    } else {
        """
            "transitions": []
        """.trimIndent()
    }

    return """
        {
          "manifest": {
            "schema_version": "v1alpha1",
            "skill_id": "learned.com.example.settings",
            "skill_version": "0.2.0",
            "skill_type": "app",
            "display_name": "Settings Learned Skill",
            "owner": "learner",
            "platform": "android",
            "app_package": "com.example.settings",
            "default_risk_level": "guarded",
            "enabled": false,
            "actions": [
              {
                "action_id": "tap_wifi",
                "display_name": "Tap Wi-Fi",
                "description": "Open the Wi-Fi page",
                "executor_type": "accessibility",
                "risk_level": "guarded",
                "requires_confirmation": true,
                "expected_outcome": "The app navigates to Wi-Fi settings",
                "enabled": true,
                "example_utterances": ["Tap Wi-Fi"],
                "match_keywords": ["wifi", "settings"]
              },
              {
                "action_id": "tap_advanced",
                "display_name": "Tap Advanced",
                "description": "Open the advanced page",
                "executor_type": "accessibility",
                "risk_level": "guarded",
                "requires_confirmation": true,
                "expected_outcome": "The app navigates to advanced settings",
                "enabled": true,
                "example_utterances": ["Tap Advanced"],
                "match_keywords": ["advanced", "settings"]
              }
            ]
          },
          "page_graph": {
            "schema_version": "v1alpha1",
            "app_package": "com.example.settings",
            "pages": [
              {
                "page_id": "wifi_settings",
                "page_name": "Wi-Fi Settings",
                "app_package": "com.example.settings",
                "activity_name": "SettingsActivity",
                "match_rules": [
                  { "type": "activity_name", "value": "SettingsActivity" }
                ],
                "available_actions": ["tap_wifi"],
                "evidence_fields": {
                  "primary_signal": "wifi row visible"
                }
              },
              {
                "page_id": "advanced_settings",
                "page_name": "Advanced Settings",
                "app_package": "com.example.settings",
                "activity_name": "SettingsActivity",
                "match_rules": [
                  { "type": "text_contains", "value": "Advanced" }
                ],
                "available_actions": ["tap_advanced"],
                "evidence_fields": {
                  "primary_signal": "advanced row visible"
                }
              }
            ],
            $transitions
          },
          "action_bindings": [
            {
              "action_id": "tap_wifi",
              "intent_action": ""
            },
            {
              "action_id": "tap_advanced",
              "intent_action": ""
            }
          ]
        }
    """.trimIndent()
}

private fun validModelDraftJson(): String {
    return """
        {
          "manifest": {
            "schema_version": "v1alpha1",
            "skill_id": "learned.com.example.settings",
            "skill_version": "0.2.0",
            "skill_type": "app",
            "display_name": "Settings Learned Skill",
            "owner": "learner",
            "platform": "android",
            "app_package": "com.example.settings",
            "default_risk_level": "guarded",
            "enabled": false,
            "actions": [
              {
                "action_id": "tap_wifi",
                "display_name": "Tap Wi-Fi",
                "description": "Open the Wi-Fi page",
                "executor_type": "accessibility",
                "risk_level": "guarded",
                "requires_confirmation": true,
                "expected_outcome": "The app navigates to Wi-Fi settings",
                "enabled": true,
                "example_utterances": ["Tap Wi-Fi"],
                "match_keywords": ["wifi", "settings"]
              }
            ]
          },
          "page_graph": {
            "schema_version": "v1alpha1",
            "app_package": "com.example.settings",
            "pages": [
              {
                "page_id": "wifi_settings",
                "page_name": "Wi-Fi Settings",
                "app_package": "com.example.settings",
                "activity_name": "SettingsActivity",
                "match_rules": [
                  { "type": "activity_name", "value": "SettingsActivity" }
                ],
                "available_actions": ["tap_wifi"],
                "evidence_fields": {
                  "primary_signal": "wifi row visible"
                }
              }
            ],
            "transitions": []
          },
          "action_bindings": [
            {
              "action_id": "tap_wifi",
              "intent_action": ""
            }
          ]
        }
    """.trimIndent()
}

