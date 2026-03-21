package com.phoneclaw.app.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phoneclaw.app.contracts.CONTRACT_SCHEMA_VERSION
import com.phoneclaw.app.contracts.PageGraph
import com.phoneclaw.app.contracts.PageMatchRule
import com.phoneclaw.app.contracts.PageSpec
import com.phoneclaw.app.contracts.PageTransition
import com.phoneclaw.app.contracts.RiskLevel
import com.phoneclaw.app.contracts.SkillActionManifest
import com.phoneclaw.app.contracts.SkillManifest
import com.phoneclaw.app.data.db.PhoneClawDatabase
import com.phoneclaw.app.learner.ExplorationTransition
import com.phoneclaw.app.learner.LearningEvidence
import com.phoneclaw.app.skills.JsonSkillLoader
import com.phoneclaw.app.skills.SkillActionBinding
import com.phoneclaw.app.skills.StoreBackedSkillRegistry
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomSkillStoreTest {
    private lateinit var database: PhoneClawDatabase
    private lateinit var rootDir: File
    private lateinit var store: RoomSkillStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PhoneClawDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        rootDir = Files.createTempDirectory("phoneclaw-skill-store").toFile()
        writeBuiltinSkillPackage(rootDir)
        store = RoomSkillStore(
            skillDao = database.skillDao(),
            builtinLoader = JsonSkillLoader.fromDirectory(rootDir),
            clock = { 1234L },
        )
    }

    @After
    fun tearDown() {
        database.close()
        rootDir.deleteRecursively()
    }

    @Test
    fun loadAllEnabledActions_keepsPendingLearnedSkillOutOfRuntimeRegistry() {
        store.saveLearnedSkill(
            manifest = learnedManifest(enabled = true),
            bindings = listOf(
                SkillActionBinding(
                    actionId = "open_learned_page",
                    intentAction = "android.intent.action.VIEW",
                ),
            ),
        )

        assertEquals(
            setOf("open_builtin_settings"),
            store.loadAllEnabledActions().map { it.actionId }.toSet(),
        )

        val registry = StoreBackedSkillRegistry(store)
        assertNull(registry.findAction("open_learned_page"))

        val learnedRecord = store.loadAllSkills().first { it.skillId == "sample.learned" }
        assertEquals(SKILL_SOURCE_LEARNED, learnedRecord.source)
        assertEquals(SKILL_REVIEW_PENDING, learnedRecord.reviewStatus)
    }

    @Test
    fun updateReviewStatus_approvedSkillEntersRuntimeRegistry() {
        store.saveLearnedSkill(
            manifest = learnedManifest(enabled = true),
            bindings = listOf(
                SkillActionBinding(
                    actionId = "open_learned_page",
                    intentAction = "android.intent.action.VIEW",
                ),
            ),
        )

        store.updateReviewStatus("sample.learned", SKILL_REVIEW_APPROVED)

        assertEquals(
            setOf("open_builtin_settings", "open_learned_page"),
            store.loadAllEnabledActions().map { it.actionId }.toSet(),
        )

        val registry = StoreBackedSkillRegistry(store)
        assertNotNull(registry.findAction("open_learned_page"))

        val learnedRecord = store.loadAllSkills().first { it.skillId == "sample.learned" }
        assertEquals(SKILL_REVIEW_APPROVED, learnedRecord.reviewStatus)
    }

    @Test
    fun saveLearnedSkill_persistsPageGraphAndEvidence() {
        store.saveLearnedSkill(
            manifest = learnedManifest(enabled = true),
            bindings = listOf(
                SkillActionBinding(
                    actionId = "open_learned_page",
                    intentAction = "android.intent.action.VIEW",
                ),
            ),
            pageGraph = learnedPageGraph(),
            evidence = learnedEvidence(),
        )

        val learnedRecord = store.loadAllSkills().first { it.skillId == "sample.learned" }
        val evidence = learnedRecord.evidence.single()
        val transition = requireNotNull(evidence.arrivedBy)

        assertEquals("learned_home", learnedRecord.pageGraph?.pages?.single()?.pageId)
        assertEquals(1, learnedRecord.pageGraph?.transitions?.size)
        assertEquals("open_learned_page", learnedRecord.pageGraph?.transitions?.single()?.triggerActionId)
        assertEquals("learned_home", evidence.pageId)
        assertEquals("{\"page\":\"home\"}", evidence.snapshotJson)
        assertEquals("/tmp/screenshot.png", evidence.screenshotPath)
        assertArrayEquals(byteArrayOf(1, 2, 3), evidence.screenshotBytes)
        assertEquals("launch", transition.triggerNodeId)
        assertEquals("Launch button", transition.triggerNodeDescription)
    }

    @Test
    fun saveLearnedSkill_keepsExistingArtifactsWhenLaterSaveOmitsThem() {
        store.saveLearnedSkill(
            manifest = learnedManifest(enabled = true),
            bindings = listOf(
                SkillActionBinding(
                    actionId = "open_learned_page",
                    intentAction = "android.intent.action.VIEW",
                ),
            ),
            pageGraph = learnedPageGraph(),
            evidence = learnedEvidence(),
        )

        store.saveLearnedSkill(
            manifest = learnedManifest(enabled = false),
            bindings = listOf(
                SkillActionBinding(
                    actionId = "open_learned_page",
                    intentAction = "android.intent.action.MAIN",
                ),
            ),
        )

        val learnedRecord = store.loadAllSkills().first { it.skillId == "sample.learned" }

        assertEquals(true, learnedRecord.enabled)
        assertEquals("android.intent.action.MAIN", learnedRecord.bindings.single().intentAction)
        assertEquals("learned_home", learnedRecord.pageGraph?.pages?.single()?.pageId)
        assertEquals(1, learnedRecord.evidence.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), learnedRecord.evidence.single().screenshotBytes)
    }

    @Test
    fun setSkillEnabled_persistsBuiltinOverrides() {
        store.setSkillEnabled("sample.builtin", enabled = false)

        assertEquals(emptyList<String>(), store.loadAllEnabledActions().map { it.actionId })

        val builtinRecord = store.loadAllSkills().first { it.skillId == "sample.builtin" }
        assertEquals(false, builtinRecord.enabled)
        assertEquals(SKILL_SOURCE_BUILTIN, builtinRecord.source)
    }
}

private fun writeBuiltinSkillPackage(rootDir: File) {
    val skillsDir = File(rootDir, "skills").apply { mkdirs() }
    File(skillsDir, "builtin.json").writeText(
        """
        {
          "skill": {
            "schema_version": "v1alpha1",
            "skill_id": "sample.builtin",
            "skill_version": "0.1.0",
            "skill_type": "system",
            "display_name": "Builtin Settings Skill",
            "owner": "test",
            "platform": "android",
            "app_package": "com.android.settings",
            "default_risk_level": "safe",
            "enabled": true,
            "actions": [
              {
                "action_id": "open_builtin_settings",
                "display_name": "Open Builtin Settings",
                "description": "Open builtin settings",
                "executor_type": "intent",
                "risk_level": "safe",
                "requires_confirmation": false,
                "expected_outcome": "Builtin settings opens",
                "example_utterances": ["open builtin settings"],
                "match_keywords": ["builtin"]
              }
            ]
          },
          "action_bindings": [
            {
              "action_id": "open_builtin_settings",
              "intent_action": "android.settings.SETTINGS"
            }
          ]
        }
        """.trimIndent(),
    )
}

private fun learnedManifest(enabled: Boolean): SkillManifest {
    return SkillManifest(
        schemaVersion = CONTRACT_SCHEMA_VERSION,
        skillId = "sample.learned",
        skillVersion = "0.1.0",
        skillType = "single_app",
        displayName = "Learned Skill",
        owner = "phoneclaw",
        platform = "android",
        appPackage = "com.example.learned",
        defaultRiskLevel = RiskLevel.SAFE,
        enabled = enabled,
        actions = listOf(
            SkillActionManifest(
                actionId = "open_learned_page",
                displayName = "Open Learned Page",
                description = "Open the learned page",
                executorType = "intent",
                riskLevel = RiskLevel.SAFE,
                requiresConfirmation = false,
                expectedOutcome = "The learned page is foregrounded",
                exampleUtterances = listOf("open learned page"),
                matchKeywords = listOf("learned"),
            ),
        ),
    )
}

private fun learnedPageGraph(): PageGraph {
    return PageGraph(
        appPackage = "com.example.learned",
        pages = listOf(
            PageSpec(
                pageId = "learned_home",
                pageName = "Learned Home",
                appPackage = "com.example.learned",
                activityName = "LearnedActivity",
                matchRules = listOf(
                    PageMatchRule(type = "activity_name", value = "LearnedActivity"),
                ),
                availableActions = listOf("open_learned_page"),
                evidenceFields = mapOf("primary_signal" to "launch button visible"),
            ),
        ),
        transitions = listOf(
            PageTransition(
                fromPageId = "learned_home",
                toPageId = "learned_home",
                triggerActionId = "open_learned_page",
                triggerNodeDescription = "Launch button",
            ),
        ),
    )
}

private fun learnedEvidence(): List<LearningEvidence> {
    return listOf(
        LearningEvidence(
            pageId = "learned_home",
            snapshotJson = "{\"page\":\"home\"}",
            screenshotPath = "/tmp/screenshot.png",
            screenshotBytes = byteArrayOf(1, 2, 3),
            arrivedBy = ExplorationTransition(
                fromPageId = "learned_home",
                toPageId = "learned_home",
                triggerNodeId = "launch",
                triggerNodeDescription = "Launch button",
            ),
            capturedAt = 1234L,
        ),
    )
}
