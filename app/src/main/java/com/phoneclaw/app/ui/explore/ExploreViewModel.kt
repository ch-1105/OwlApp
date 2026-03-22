package com.phoneclaw.app.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.phoneclaw.app.contracts.PageGraph
import com.phoneclaw.app.contracts.SkillManifest
import com.phoneclaw.app.explorer.PageTreeSnapshot
import com.phoneclaw.app.learner.ExplorationAgent
import com.phoneclaw.app.learner.ExplorationProgress
import com.phoneclaw.app.learner.ExplorationStatus
import com.phoneclaw.app.learner.LearningEvidence
import com.phoneclaw.app.learner.LearningSessionManager
import com.phoneclaw.app.learner.LearningSessionState
import com.phoneclaw.app.scanner.AppScanner
import com.phoneclaw.app.skills.SkillActionBinding
import com.phoneclaw.app.store.SKILL_REVIEW_APPROVED
import com.phoneclaw.app.store.SKILL_REVIEW_PENDING
import com.phoneclaw.app.store.SKILL_REVIEW_REJECTED
import com.phoneclaw.app.store.SKILL_SOURCE_LEARNED
import com.phoneclaw.app.store.SkillRecord
import com.phoneclaw.app.store.SkillStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReviewSkillItem(
    val skillId: String,
    val displayName: String,
    val appPackage: String?,
    val reviewStatus: String,
    val actionCount: Int,
    val pageCount: Int,
    val transitionCount: Int,
    val evidenceCount: Int,
    val manifest: SkillManifest,
    val bindings: List<SkillActionBinding>,
    val pageGraph: PageGraph?,
    val evidence: List<LearningEvidence>,
)

data class ExploreUiState(
    val isLoading: Boolean = false,
    val activeSessionId: String? = null,
    val activeSession: LearningSessionState? = null,
    val explorationProgress: ExplorationProgress? = null,
    val reviewItems: List<ReviewSkillItem> = emptyList(),
    val draftNameEdits: Map<String, String> = emptyMap(),
    val infoMessage: String? = null,
    val errorMessage: String? = null,
)

class ExploreViewModel(
    private val appScanner: AppScanner,
    private val learningSessionManager: LearningSessionManager,
    private val skillStore: SkillStore,
    private val explorationAgent: ExplorationAgent? = null,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ExploreUiState())
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()

    init {
        refreshReviewQueue()
    }

    fun startLearning(snapshot: PageTreeSnapshot?) {
        if (snapshot == null) {
            showError("还没有可用页面快照，先刷新一次当前页面。")
            return
        }

        viewModelScope.launch {
            setLoading(true)

            runCatching {
                val appName = withContext(workDispatcher) {
                    resolveAppName(snapshot.packageName)
                }
                val sessionId = withContext(workDispatcher) {
                    learningSessionManager.startLearning(
                        appPackage = snapshot.packageName,
                        appName = appName,
                    )
                }
                val session = learningSessionManager.getSessionState(sessionId)
                requireNotNull(session) {
                    "学习会话创建成功，但未能读取会话状态。"
                }
                sessionId to session
            }.onSuccess { (sessionId, session) ->
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        activeSessionId = sessionId,
                        activeSession = session,
                        infoMessage = "已经开始学习 `${session.appName}`，现在可以采集页面了。",
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                showError(error.message ?: "开始学习失败。")
            }
        }
    }

    fun startAutonomousExploration(snapshot: PageTreeSnapshot?) {
        if (snapshot == null) {
            showError("还没有可用页面快照，先刷新一次当前页面。")
            return
        }
        if (explorationAgent == null) {
            showError("自主探索功能尚未启用。")
            return
        }
        if (uiState.value.explorationProgress != null) {
            showError("已有探索任务正在进行中。")
            return
        }

        viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    isLoading = true,
                    explorationProgress = ExplorationProgress(
                        pagesDiscovered = 0,
                        currentPageName = snapshot.packageName,
                        transitionsRecorded = 0,
                        stepsUsed = 0,
                        stepsTotal = 30,
                        status = ExplorationStatus.LAUNCHING,
                    ),
                    infoMessage = null,
                    errorMessage = null,
                )
            }

            runCatching {
                val appName = withContext(workDispatcher) {
                    resolveAppName(snapshot.packageName)
                }
                withContext(workDispatcher) {
                    explorationAgent.explore(
                        appPackage = snapshot.packageName,
                        appName = appName,
                        onProgress = { progress ->
                            _uiState.update { current ->
                                current.copy(explorationProgress = progress)
                            }
                        },
                    )
                }
            }.onSuccess { outcome ->
                // Save all generated drafts
                withContext(workDispatcher) {
                    outcome.drafts.forEach { draft ->
                        runCatching {
                            skillStore.saveLearnedSkill(
                                manifest = draft.manifest,
                                bindings = draft.bindings,
                                pageGraph = draft.pageGraph,
                                evidence = draft.evidence,
                            )
                            skillStore.setSkillEnabled(draft.manifest.skillId, enabled = false)
                            skillStore.updateReviewStatus(draft.manifest.skillId, SKILL_REVIEW_PENDING)
                        }
                    }
                }
                val reviewItems = withContext(workDispatcher) { loadReviewItems() }
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        explorationProgress = null,
                        reviewItems = reviewItems,
                        draftNameEdits = mergeDraftNameEdits(reviewItems),
                        infoMessage = "自主探索完成！发现 ${outcome.pagesDiscovered} 个页面，" +
                            "记录 ${outcome.transitionsRecorded} 条跳转，" +
                            "生成 ${outcome.drafts.size} 个 Skill 草稿。",
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        explorationProgress = null,
                        errorMessage = error.message ?: "自主探索失败。",
                        infoMessage = null,
                    )
                }
            }
        }
    }

    fun captureCurrentPage() {
        val sessionId = uiState.value.activeSessionId
        if (sessionId == null) {
            showError("还没有学习会话，先开始学习当前应用。")
            return
        }

        viewModelScope.launch {
            setLoading(true)

            runCatching {
                withContext(workDispatcher) {
                    learningSessionManager.captureAndAnalyze(sessionId)
                }
                requireNotNull(learningSessionManager.getSessionState(sessionId))
            }.onSuccess { session ->
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        activeSession = session,
                        infoMessage = "已采集页面：${session.exploredPages.lastOrNull()?.analysis?.suggestedPageSpec?.pageName ?: "未知页面"}",
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                updateSessionError(sessionId, error.message ?: "采集页面失败。")
            }
        }
    }

    fun tapAndCapture(nodeId: String, nodeDescription: String) {
        val sessionId = uiState.value.activeSessionId
        if (sessionId == null) {
            showError("还没有学习会话，先开始学习当前应用。")
            return
        }

        viewModelScope.launch {
            setLoading(true)

            runCatching {
                withContext(workDispatcher) {
                    learningSessionManager.tapAndCapture(
                        sessionId = sessionId,
                        nodeId = nodeId,
                        nodeDescription = nodeDescription,
                    )
                }
                requireNotNull(learningSessionManager.getSessionState(sessionId))
            }.onSuccess { session ->
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        activeSession = session,
                        infoMessage = "已学习节点 `$nodeDescription`，当前累计 ${session.exploredPages.size} 个页面。",
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                updateSessionError(sessionId, error.message ?: "点击学习失败。")
            }
        }
    }

    fun finishExploration() {
        val sessionId = uiState.value.activeSessionId
        if (sessionId == null) {
            showError("还没有学习会话，先开始学习当前应用。")
            return
        }

        viewModelScope.launch {
            setLoading(true)

            val draft = runCatching {
                withContext(workDispatcher) {
                    learningSessionManager.finishExploration(sessionId)
                }
            }.getOrElse { error ->
                updateSessionError(sessionId, error.message ?: "生成 Skill 草稿失败。")
                return@launch
            }

            runCatching {
                withContext(workDispatcher) {
                    skillStore.saveLearnedSkill(
                        manifest = draft.manifest,
                        bindings = draft.bindings,
                        pageGraph = draft.pageGraph,
                        evidence = draft.evidence,
                    )
                    skillStore.setSkillEnabled(draft.manifest.skillId, enabled = false)
                    skillStore.updateReviewStatus(draft.manifest.skillId, SKILL_REVIEW_PENDING)
                    val reviewItems = loadReviewItems()
                    learningSessionManager.discardSession(sessionId)
                    reviewItems
                }
            }.onSuccess { reviewItems ->
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        activeSessionId = null,
                        activeSession = null,
                        reviewItems = reviewItems,
                        draftNameEdits = mergeDraftNameEdits(reviewItems),
                        infoMessage = "Skill 草稿已经进入审核列表，批准后才会正式生效。",
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                runCatching {
                    learningSessionManager.discardSession(sessionId)
                }
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        activeSessionId = null,
                        activeSession = null,
                        errorMessage = error.message ?: "Skill 草稿保存失败，请重新生成。",
                        infoMessage = null,
                    )
                }
            }
        }
    }

    fun approveSkill(skillId: String) {
        reviewSkill(skillId = skillId, approved = true)
    }

    fun rejectSkill(skillId: String) {
        reviewSkill(skillId = skillId, approved = false)
    }

    fun onDraftDisplayNameChange(skillId: String, value: String) {
        _uiState.update { current ->
            current.copy(
                draftNameEdits = current.draftNameEdits + (skillId to value),
            )
        }
    }

    fun refreshReviewQueue() {
        viewModelScope.launch {
            setLoading(true)

            runCatching {
                withContext(workDispatcher) {
                    loadReviewItems()
                }
            }.onSuccess { reviewItems ->
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        reviewItems = reviewItems,
                        draftNameEdits = mergeDraftNameEdits(reviewItems),
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                showError(error.message ?: "加载审核列表失败。")
            }
        }
    }

    fun clearMessage() {
        _uiState.update { current ->
            current.copy(
                infoMessage = null,
                errorMessage = null,
            )
        }
    }

    private fun reviewSkill(skillId: String, approved: Boolean) {
        viewModelScope.launch {
            setLoading(true)

            runCatching {
                withContext(workDispatcher) {
                    val existing = requireNotNull(skillStore.loadAllSkills().firstOrNull { record -> record.skillId == skillId }) {
                        "未找到待审核 Skill：$skillId"
                    }

                    val updatedManifest = existing.manifest.copy(
                        displayName = normalizedDisplayName(skillId, existing.manifest.displayName),
                    )

                    skillStore.saveLearnedSkill(
                        manifest = updatedManifest,
                        bindings = existing.bindings,
                        pageGraph = existing.pageGraph,
                        evidence = existing.evidence,
                    )
                    skillStore.setSkillEnabled(skillId, enabled = approved)
                    skillStore.updateReviewStatus(
                        skillId = skillId,
                        status = if (approved) SKILL_REVIEW_APPROVED else SKILL_REVIEW_REJECTED,
                    )
                    loadReviewItems()
                }
            }.onSuccess { reviewItems ->
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        reviewItems = reviewItems,
                        draftNameEdits = mergeDraftNameEdits(reviewItems),
                        infoMessage = if (approved) {
                            "Skill 已批准，现在会进入运行时技能表。"
                        } else {
                            "Skill 已驳回，不会进入运行时。"
                        },
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                showError(error.message ?: "审核 Skill 失败。")
            }
        }
    }

    private suspend fun resolveAppName(packageName: String): String {
        return appScanner.scanInstalledApps()
            .firstOrNull { app -> app.packageName == packageName }
            ?.appName
            ?: packageName
    }

    private suspend fun loadReviewItems(): List<ReviewSkillItem> {
        return skillStore.loadAllSkills()
            .asSequence()
            .filter { record -> record.source == SKILL_SOURCE_LEARNED }
            .sortedWith(
                compareBy<SkillRecord> { record -> reviewRank(record.reviewStatus) }
                    .thenByDescending { record -> record.updatedAt },
            )
            .map { record -> record.toReviewItem() }
            .toList()
    }

    private fun updateSessionError(sessionId: String, message: String) {
        _uiState.update { current ->
            current.copy(
                isLoading = false,
                activeSession = learningSessionManager.getSessionState(sessionId),
                errorMessage = message,
                infoMessage = null,
            )
        }
    }

    private fun showError(message: String) {
        _uiState.update { current ->
            current.copy(
                isLoading = false,
                errorMessage = message,
                infoMessage = null,
            )
        }
    }

    private fun setLoading(loading: Boolean) {
        _uiState.update { current ->
            current.copy(
                isLoading = loading,
                infoMessage = null,
                errorMessage = null,
            )
        }
    }

    private fun normalizedDisplayName(
        skillId: String,
        fallback: String,
    ): String {
        val editedValue = uiState.value.draftNameEdits[skillId]?.trim()
        if (editedValue.isNullOrBlank()) {
            return fallback
        }
        return editedValue
    }
}

private fun reviewRank(status: String): Int {
    return when (status) {
        SKILL_REVIEW_PENDING -> 0
        SKILL_REVIEW_REJECTED -> 1
        SKILL_REVIEW_APPROVED -> 2
        else -> 3
    }
}

private fun SkillRecord.toReviewItem(): ReviewSkillItem {
    return ReviewSkillItem(
        skillId = skillId,
        displayName = manifest.displayName,
        appPackage = manifest.appPackage,
        reviewStatus = reviewStatus,
        actionCount = manifest.actions.size,
        pageCount = pageGraph?.pages?.size ?: 0,
        transitionCount = pageGraph?.transitions?.size ?: 0,
        evidenceCount = evidence.size,
        manifest = manifest,
        bindings = bindings,
        pageGraph = pageGraph,
        evidence = evidence,
    )
}

private fun mergeDraftNameEdits(
    reviewItems: List<ReviewSkillItem>,
): Map<String, String> {
    return reviewItems.associate { item ->
        item.skillId to item.displayName
    }
}

class ExploreViewModelFactory(
    private val appScanner: AppScanner,
    private val learningSessionManager: LearningSessionManager,
    private val skillStore: SkillStore,
    private val explorationAgent: ExplorationAgent? = null,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ExploreViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ExploreViewModel(
                appScanner = appScanner,
                learningSessionManager = learningSessionManager,
                skillStore = skillStore,
                explorationAgent = explorationAgent,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}



