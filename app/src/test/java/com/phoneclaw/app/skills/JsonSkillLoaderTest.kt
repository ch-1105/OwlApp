package com.phoneclaw.app.skills

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JsonSkillLoaderTest {
    @Test
    fun loadsRegisteredActionsFromJsonDirectory() {
        val rootDir = Files.createTempDirectory("phoneclaw-skills").toFile()

        try {
            val skillsDir = File(rootDir, "skills").apply { mkdirs() }
            File(skillsDir, "sample.json").writeText(
                """
                {
                  "skill": {
                    "schema_version": "v1alpha1",
                    "skill_id": "sample.system",
                    "skill_version": "0.1.0",
                    "skill_type": "system",
                    "display_name": "Sample System Skill",
                    "owner": "test",
                    "platform": "android",
                    "app_package": "com.example.test",
                    "default_risk_level": "safe",
                    "enabled": true,
                    "actions": [
                      {
                        "action_id": "open_sample",
                        "display_name": "Open Sample",
                        "description": "Open the sample page",
                        "executor_type": "intent",
                        "risk_level": "safe",
                        "requires_confirmation": false,
                        "expected_outcome": "Sample page opens",
                        "example_utterances": ["open sample"],
                        "match_keywords": ["sample"]
                      }
                    ]
                  },
                  "action_bindings": [
                    {
                      "action_id": "open_sample",
                      "intent_action": "android.settings.SETTINGS"
                    }
                  ]
                }
                """.trimIndent(),
            )

            val actions = JsonSkillLoader.fromDirectory(rootDir).loadRegisteredActions()
            val action = actions.single()

            assertEquals("sample.system", action.skill.skillId)
            assertEquals("open_sample", action.actionId)
            assertEquals("android.settings.SETTINGS", action.intentAction)
            assertEquals(listOf("open sample"), action.action.exampleUtterances)
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun loadsOptionalPageGraphAndEvidence() {
        val rootDir = Files.createTempDirectory("phoneclaw-skills").toFile()

        try {
            val skillsDir = File(rootDir, "skills").apply { mkdirs() }
            File(skillsDir, "learned.json").writeText(
                """
                {
                  "skill": {
                    "schema_version": "v1alpha1",
                    "skill_id": "sample.learned",
                    "skill_version": "0.1.0",
                    "skill_type": "single_app",
                    "display_name": "Sample Learned Skill",
                    "owner": "test",
                    "platform": "android",
                    "app_package": "com.example.test",
                    "default_risk_level": "safe",
                    "enabled": true,
                    "actions": [
                      {
                        "action_id": "open_sample",
                        "display_name": "Open Sample",
                        "description": "Open the sample page",
                        "executor_type": "intent",
                        "risk_level": "safe",
                        "requires_confirmation": false,
                        "expected_outcome": "Sample page opens"
                      }
                    ]
                  },
                  "action_bindings": [
                    {
                      "action_id": "open_sample",
                      "intent_action": "android.settings.SETTINGS"
                    }
                  ],
                  "page_graph": {
                    "schema_version": "v1alpha1",
                    "app_package": "com.example.test",
                    "pages": [
                      {
                        "schema_version": "v1alpha1",
                        "page_id": "sample_home",
                        "page_name": "Sample Home",
                        "app_package": "com.example.test",
                        "activity_name": "SampleActivity",
                        "match_rules": [
                          { "type": "activity_name", "value": "SampleActivity" }
                        ],
                        "available_actions": ["open_sample"],
                        "evidence_fields": {
                          "primary_signal": "sample row visible"
                        }
                      }
                    ],
                    "transitions": [
                      {
                        "from_page_id": "sample_home",
                        "to_page_id": "sample_home",
                        "trigger_action_id": "open_sample",
                        "trigger_node_description": "Sample row"
                      }
                    ]
                  },
                  "evidence": [
                    {
                      "page_id": "sample_home",
                      "snapshot_json": "{\"page\":\"home\"}",
                      "screenshot_path": "/tmp/sample.png",
                      "screenshot_base64": "AQID",
                      "captured_at": 123,
                      "arrived_by": {
                        "from_page_id": "sample_home",
                        "to_page_id": "sample_home",
                        "trigger_node_id": "launch",
                        "trigger_node_description": "Launch button"
                      }
                    }
                  ]
                }
                """.trimIndent(),
            )

            val skillPackage = JsonSkillLoader.fromDirectory(rootDir).loadSkillPackages().single()
            val evidence = skillPackage.evidence.single()

            assertEquals("sample_home", skillPackage.pageGraph?.pages?.single()?.pageId)
            assertEquals("open_sample", skillPackage.pageGraph?.transitions?.single()?.triggerActionId)
            assertEquals("sample_home", evidence.pageId)
            assertEquals("launch", evidence.arrivedBy?.triggerNodeId)
            assertArrayEquals(byteArrayOf(1, 2, 3), evidence.screenshotBytes)
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun rejectsMissingBindingForDeclaredAction() {
        val rootDir = Files.createTempDirectory("phoneclaw-skills").toFile()

        try {
            val skillsDir = File(rootDir, "skills").apply { mkdirs() }
            File(skillsDir, "broken.json").writeText(
                """
                {
                  "skill": {
                    "schema_version": "v1alpha1",
                    "skill_id": "sample.broken",
                    "skill_version": "0.1.0",
                    "skill_type": "system",
                    "display_name": "Broken Skill",
                    "owner": "test",
                    "platform": "android",
                    "app_package": "com.example.test",
                    "default_risk_level": "safe",
                    "enabled": true,
                    "actions": [
                      {
                        "action_id": "open_broken",
                        "display_name": "Open Broken",
                        "description": "Open the broken page",
                        "executor_type": "intent",
                        "risk_level": "safe",
                        "requires_confirmation": false,
                        "expected_outcome": "Broken page opens"
                      }
                    ]
                  },
                  "action_bindings": []
                }
                """.trimIndent(),
            )

            val error = runCatching {
                JsonSkillLoader.fromDirectory(rootDir).loadRegisteredActions()
            }.exceptionOrNull()

            assertNotNull(error)
            assertTrue(error?.message?.contains("missing action bindings") == true)
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun loadsBundledSkillAssets() {
        val assetsRoot = sequenceOf(
            File("src/main/assets"),
            File("app/src/main/assets"),
        ).firstOrNull { it.exists() }
            ?: error("Could not locate app/src/main/assets for JsonSkillLoaderTest.")

        val actions = JsonSkillLoader.fromDirectory(assetsRoot).loadRegisteredActions()
        val actionIds = actions.map { it.actionId }.toSet()

        assertEquals(
            setOf(
                "open_system_settings",
                "open_wifi_settings",
                "open_bluetooth_settings",
                "open_notification_settings",
                "open_web_url",
                "fetch_web_page_content",
                "open_clock_home",
            ),
            actionIds,
        )
    }
}
