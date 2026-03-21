package com.phoneclaw.app.ui.explore

import com.phoneclaw.app.MainDispatcherRule
import com.phoneclaw.app.contracts.CONTRACT_SCHEMA_VERSION
import com.phoneclaw.app.contracts.PageGraph
import com.phoneclaw.app.contracts.PageMatchRule
import com.phoneclaw.app.contracts.PageSpec
import com.phoneclaw.app.contracts.RiskLevel
import com.phoneclaw.app.contracts.SkillActionManifest
import com.phoneclaw.app.contracts.SkillManifest
import com.phoneclaw.app.explorer.AccessibilityNodeSnapshot
import com.phoneclaw.app.explorer.PageTreeSnapshot
import com.phoneclaw.app.gateway.ports.ClickableElementSuggestion
import com.phoneclaw.app.gateway.ports.PageAnalysisResult
import com.phoneclaw.app.learner.ExplorationTransition
import com.phoneclaw.app.learner.ExploredPage
import com.phoneclaw.app.learner.LearnedSkillDraft
import com.phoneclaw.app.learner.LearningEvidence
import com.phoneclaw.app.learner.LearningSessionManager
import com.phoneclaw.app.learner.LearningSessionState
import com.phoneclaw.app.learner.LearningStatus
import com.phoneclaw.app.scanner.AppScanner
import com.phoneclaw.app.scanner.InstalledApp
import com.phoneclaw.app.skills.RegisteredSkillAction
import com.phoneclaw.app.skills.SkillActionBinding
import com.phoneclaw.app.store.SKILL_REVIEW_APPROVED
import com.phoneclaw.app.store.SKILL_REVIEW_PENDING
import com.phoneclaw.app.store.SKILL_REVIEW_REJECTED
import com.phoneclaw.app.store.SKILL_SOURCE_LEARNED
import com.phoneclaw.app.store.SkillRecord
import com.phoneclaw.app.store.SkillStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun finishExploration_savesDraftIntoReviewQueue() = runTest {
        val skillStore = FakeSkillStore()
        val learningSessionManager = FakeLearningSessionManager(sampleDraft())
        val viewModel = ExploreViewModel(
            appScanner = FakeAppScanner(
                listOf(installedApp(packageName = "com.example.settings", appName = "Settings")),
            ),
            learningSessionManager = learningSessionManager,
            skillStore = skillStore,
            workDispatcher = mainDispatcherRule.dispatcher,
        )

        advanceUntilIdle()
        viewModel.startLearning(sampleSnapshot())
        advanceUntilIdle()
        viewModel.captureCurrentPage()
        advanceUntilIdle()
        viewModel.finishExploration()
        advanceUntilIdle()

        val savedRecord = skillStore.loadAllSkills().single()
        val reviewItem = viewModel.uiState.value.reviewItems.single()

        assertNull(viewModel.uiState.value.activeSessionId)
        assertEquals(SKILL_REVIEW_PENDING, savedRecord.reviewStatus)
        assertTrue(!savedRecord.enabled)
        assertEquals("learned.com.example.settings", savedRecord.skillId)
        assertEquals(1, savedRecord.pageGraph?.pages?.size)
        assertEquals(1, savedRecord.evidence.size)
        assertEquals(SKILL_REVIEW_PENDING, reviewItem.reviewStatus)
        assertTrue("session-1" in learningSessionManager.discardedSessionIds)
    }

    @Test
    fun finishExploration_relearningApprovedSkillResetsToPendingAndDisabled() = runTest {
        val existingRecord = sampleRecord(
            enabled = true,
            reviewStatus = SKILL_REVIEW_APPROVED,
        )
        val skillStore = FakeSkillStore(
            mutableMapOf(existingRecord.skillId to existingRecord),
        )
        val viewModel = ExploreViewModel(
            appScanner = FakeAppScanner(emptyList()),
            learningSessionManager = FakeLearningSessionManager(sampleDraft()),
            skillStore = skillStore,
            workDispatcher = mainDispatcherRule.dispatcher,
        )

        advanceUntilIdle()
        viewModel.startLearning(sampleSnapshot())
        advanceUntilIdle()
        viewModel.captureCurrentPage()
        advanceUntilIdle()
        viewModel.finishExploration()
        advanceUntilIdle()

        val savedRecord = skillStore.loadAllSkills().single()

        assertEquals(SKILL_REVIEW_PENDING, savedRecord.reviewStatus)
        assertTrue(!savedRecord.enabled)
    }

    @Test
    fun finishExploration_clearsSessionWhenDraftPersistenceFails() = runTest {
        val learningSessionManager = FakeLearningSessionManager(sampleDraft())
        val viewModel = ExploreViewModel(
            appScanner = FakeAppScanner(emptyList()),
            learningSessionManager = learningSessionManager,
            skillStore = FakeSkillStore(saveError = IllegalStateException("db down")),
            workDispatcher = mainDispatcherRule.dispatcher,
        )

        advanceUntilIdle()
        viewModel.startLearning(sampleSnapshot())
        advanceUntilIdle()
        viewModel.captureCurrentPage()
        advanceUntilIdle()
        viewModel.finishExploration()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.activeSessionId)
        assertTrue(viewModel.uiState.value.errorMessage.orEmpty().contains("db down"))
        assertTrue("session-1" in learningSessionManager.discardedSessionIds)
    }

    @Test
    fun approveSkill_updatesDisplayNameEnablesSkillAndReviewStatus() = runTest {
        val skillStore = FakeSkillStore(
            mutableMapOf(sampleRecord().skillId to sampleRecord()),
        )
        val viewModel = ExploreViewModel(
            appScanner = FakeAppScanner(emptyList()),
            learningSessionManager = FakeLearningSessionManager(sampleDraft()),
            skillStore = skillStore,
            workDispatcher = mainDispatcherRule.dispatcher,
        )

        advanceUntilIdle()
        viewModel.onDraftDisplayNameChange("learned.com.example.settings", "Reviewed Settings Skill")
        viewModel.approveSkill("learned.com.example.settings")
        advanceUntilIdle()

        val savedRecord = skillStore.loadAllSkills().single()

        assertEquals(SKILL_REVIEW_APPROVED, savedRecord.reviewStatus)
        assertTrue(savedRecord.enabled)
        assertEquals("Reviewed Settings Skill", savedRecord.manifest.displayName)
        assertEquals(SKILL_REVIEW_APPROVED, viewModel.uiState.value.reviewItems.single().reviewStatus)
    }

    @Test
    fun approveSkill_resetsBlankDraftNameEditBackToPersistedValue() = runTest {
        val skillStore = FakeSkillStore(
            mutableMapOf(sampleRecord().skillId to sampleRecord()),
        )
        val viewModel = ExploreViewModel(
            appScanner = FakeAppScanner(emptyList()),
            learningSessionManager = FakeLearningSessionManager(sampleDraft()),
            skillStore = skillStore,
            workDispatcher = mainDispatcherRule.dispatcher,
        )

        advanceUntilIdle()
        viewModel.onDraftDisplayNameChange("learned.com.example.settings", "   ")
        viewModel.approveSkill("learned.com.example.settings")
        advanceUntilIdle()

        assertEquals(
            "Settings Learned Skill",
            viewModel.uiState.value.draftNameEdits["learned.com.example.settings"],
        )
    }
    @Test
    fun rejectSkill_marksDraftRejectedAndDisabled() = runTest {
        val skillStore = FakeSkillStore(
            mutableMapOf(
                sampleRecord(enabled = true).skillId to sampleRecord(enabled = true),
            ),
        )
        val viewModel = ExploreViewModel(
            appScanner = FakeAppScanner(emptyList()),
            learningSessionManager = FakeLearningSessionManager(sampleDraft()),
            skillStore = skillStore,
            workDispatcher = mainDispatcherRule.dispatcher,
        )

        advanceUntilIdle()
        viewModel.rejectSkill("learned.com.example.settings")
        advanceUntilIdle()

        val savedRecord = skillStore.loadAllSkills().single()

        assertEquals(SKILL_REVIEW_REJECTED, savedRecord.reviewStatus)
        assertTrue(!savedRecord.enabled)
        assertEquals(SKILL_REVIEW_REJECTED, viewModel.uiState.value.reviewItems.single().reviewStatus)
        assertTrue(viewModel.uiState.value.infoMessage.orEmpty().contains("驳回"))
    }
}

private class FakeAppScanner(
    private val apps: List<InstalledApp>,
) : AppScanner {
    override fun scanInstalledApps(): List<InstalledApp> = apps
}

private class FakeLearningSessionManager(
    private val draft: LearnedSkillDraft,
) : LearningSessionManager {
    private val sessions = linkedMapOf<String, LearningSessionState>()
    val discardedSessionIds = mutableListOf<String>()

    override suspend fun startLearning(appPackage: String, appName: String): String {
        val sessionId = "session-1"
        sessions[sessionId] = LearningSessionState(
            appPackage = appPackage,
            appName = appName,
            exploredPages = emptyList(),
            transitions = emptyList(),
            status = LearningStatus.EXPLORING,
        )
        return sessionId
    }

    override suspend fun captureAndAnalyze(sessionId: String): ExploredPage {
        val session = requireNotNull(sessions[sessionId])
        val page = sampleExploredPage()
        sessions[sessionId] = session.copy(
            exploredPages = session.exploredPages + page,
            status = LearningStatus.EXPLORING,
        )
        return page
    }

    override suspend fun tapAndCapture(
        sessionId: String,
        nodeId: String,
        nodeDescription: String?,
    ): ExploredPage {
        return captureAndAnalyze(sessionId)
    }

    override suspend fun finishExploration(sessionId: String): LearnedSkillDraft {
        val session = requireNotNull(sessions[sessionId])
        sessions[sessionId] = session.copy(
            status = LearningStatus.REVIEW_PENDING,
            draft = draft,
        )
        return draft
    }

    override fun getSessionState(sessionId: String): LearningSessionState? {
        return sessions[sessionId]
    }

    override fun discardSession(sessionId: String) {
        discardedSessionIds += sessionId
        sessions.remove(sessionId)
    }
}

private class FakeSkillStore(
    private val recordsById: MutableMap<String, SkillRecord> = linkedMapOf(),
    private val saveError: Throwable? = null,
) : SkillStore {
    override fun loadAllEnabledActions(): List<RegisteredSkillAction> = emptyList()

    override fun saveLearnedSkill(
        manifest: SkillManifest,
        bindings: List<SkillActionBinding>,
        pageGraph: PageGraph?,
        evidence: List<LearningEvidence>,
    ) {
        saveError?.let { error -> throw error }

        val existing = recordsById[manifest.skillId]
        val updatedRecord = SkillRecord(
            manifest = manifest,
            bindings = bindings,
            pageGraph = pageGraph,
            evidence = evidence,
            source = existing?.source ?: SKILL_SOURCE_LEARNED,
            enabled = existing?.enabled ?: manifest.enabled,
            reviewStatus = existing?.reviewStatus ?: SKILL_REVIEW_PENDING,
            learnedAt = existing?.learnedAt,
            appVersion = existing?.appVersion,
            createdAt = existing?.createdAt ?: 1L,
            updatedAt = (existing?.updatedAt ?: 0L) + 1L,
        )
        recordsById[manifest.skillId] = updatedRecord
    }

    override fun updateReviewStatus(skillId: String, status: String) {
        val existing = requireNotNull(recordsById[skillId])
        recordsById[skillId] = existing.copy(
            reviewStatus = status,
            updatedAt = existing.updatedAt + 1L,
        )
    }

    override fun setSkillEnabled(skillId: String, enabled: Boolean) {
        val existing = requireNotNull(recordsById[skillId])
        recordsById[skillId] = existing.copy(enabled = enabled)
    }

    override fun loadAllSkills(): List<SkillRecord> {
        return recordsById.values.toList()
    }
}

private fun sampleRecord(
    enabled: Boolean = false,
    reviewStatus: String = SKILL_REVIEW_PENDING,
): SkillRecord {
    val draft = sampleDraft()
    return SkillRecord(
        manifest = draft.manifest,
        bindings = draft.bindings,
        pageGraph = draft.pageGraph,
        evidence = draft.evidence,
        source = SKILL_SOURCE_LEARNED,
        enabled = enabled,
        reviewStatus = reviewStatus,
        createdAt = 1L,
        updatedAt = 1L,
    )
}

private fun sampleDraft(): LearnedSkillDraft {
    return LearnedSkillDraft(
        manifest = SkillManifest(
            schemaVersion = CONTRACT_SCHEMA_VERSION,
            skillId = "learned.com.example.settings",
            skillVersion = "0.1.0",
            skillType = "single_app",
            displayName = "Settings Learned Skill",
            owner = "phoneclaw",
            platform = "android",
            appPackage = "com.example.settings",
            defaultRiskLevel = RiskLevel.SAFE,
            enabled = false,
            actions = listOf(
                SkillActionManifest(
                    actionId = "tap_wifi",
                    displayName = "Tap Wi-Fi",
                    description = "Open Wi-Fi settings",
                    executorType = "accessibility",
                    riskLevel = RiskLevel.SAFE,
                    requiresConfirmation = true,
                    expectedOutcome = "Wi-Fi settings opens",
                ),
            ),
        ),
        pageGraph = PageGraph(
            appPackage = "com.example.settings",
            pages = listOf(
                PageSpec(
                    pageId = "wifi_settings",
                    pageName = "Wi-Fi",
                    appPackage = "com.example.settings",
                    activityName = "WifiSettingsActivity",
                    matchRules = listOf(
                        PageMatchRule(type = "activity_name", value = "WifiSettingsActivity"),
                    ),
                    availableActions = listOf("tap_wifi"),
                ),
            ),
            transitions = emptyList(),
        ),
        bindings = listOf(
            SkillActionBinding(
                actionId = "tap_wifi",
                intentAction = "android.settings.WIFI_SETTINGS",
            ),
        ),
        evidence = listOf(
            LearningEvidence(
                pageId = "wifi_settings",
                snapshotJson = "{\"page\":\"wifi\"}",
                screenshotPath = null,
                screenshotBytes = null,
                arrivedBy = ExplorationTransition(
                    fromPageId = "wifi_settings",
                    toPageId = "wifi_settings",
                    triggerNodeId = "wifi_row",
                    triggerNodeDescription = "Wi-Fi row",
                ),
                capturedAt = 123L,
            ),
        ),
    )
}

private fun sampleExploredPage(): ExploredPage {
    return ExploredPage(
        pageTree = sampleSnapshot(),
        analysis = PageAnalysisResult(
            suggestedPageSpec = PageSpec(
                pageId = "wifi_settings",
                pageName = "Wi-Fi",
                appPackage = "com.example.settings",
                activityName = "WifiSettingsActivity",
                matchRules = listOf(
                    PageMatchRule(type = "activity_name", value = "WifiSettingsActivity"),
                ),
                availableActions = listOf("tap_wifi"),
            ),
            clickableElements = listOf(
                ClickableElementSuggestion(
                    resourceId = "wifi_row",
                    text = "Wi-Fi",
                    contentDescription = null,
                    suggestedActionName = "Tap Wi-Fi",
                    suggestedDescription = "Open Wi-Fi settings",
                ),
            ),
            navigationHints = listOf("Tap Wi-Fi"),
        ),
        capturedAt = 123L,
        screenshot = null,
        screenshotPath = null,
        arrivedBy = null,
    )
}

private fun sampleSnapshot(): PageTreeSnapshot {
    return PageTreeSnapshot(
        packageName = "com.example.settings",
        activityName = "WifiSettingsActivity",
        timestamp = 123L,
        nodes = listOf(
            AccessibilityNodeSnapshot(
                nodeId = "wifi_row",
                className = "android.widget.TextView",
                text = "Wi-Fi",
                contentDescription = null,
                resourceId = "com.example.settings:id/wifi_row",
                isClickable = true,
                isScrollable = false,
                isEditable = false,
                bounds = "0,0,100,100",
                children = emptyList(),
            ),
        ),
    )
}

private fun installedApp(
    packageName: String,
    appName: String,
): InstalledApp {
    return InstalledApp(
        packageName = packageName,
        appName = appName,
        versionName = "1.0.0",
        versionCode = 1L,
        isSystemApp = false,
        iconDrawable = null,
    )
}

