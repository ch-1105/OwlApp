package com.phoneclaw.app.ui

import androidx.lifecycle.ViewModel
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.phoneclaw.app.contracts.TaskSnapshot
import com.phoneclaw.app.contracts.TaskState
import com.phoneclaw.app.explorer.AccessibilityCaptureBridge
import com.phoneclaw.app.gateway.Gateway
import com.phoneclaw.app.learner.ExplorationAgent
import com.phoneclaw.app.learner.ExplorationStatus
import com.phoneclaw.app.notification.ExplorationNotifier
import com.phoneclaw.app.scanner.AppScanner
import com.phoneclaw.app.scanner.InstalledApp
import com.phoneclaw.app.store.SKILL_REVIEW_PENDING
import com.phoneclaw.app.store.SkillStore
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LEARNING_LOG_TAG = "PhoneClawLearn"

enum class ChatRole {
    USER,
    ASSISTANT,
}

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val taskState: TaskState? = null,
)

data class MainUiState(
    val prompt: String = "",
    val isRunning: Boolean = false,
    val messages: List<ChatMessage> = listOf(
        ChatMessage(
            id = "welcome",
            role = ChatRole.ASSISTANT,
            text = """
                我已经准备好了。你现在可以直接让我：
                - 打开系统设置
                - 打开某个网页
                - 读取某个网页的内容并返回给你
                - 学习某个应用（例如"学习微信"）
            """.trimIndent(),
        ),
    ),
    val lastTask: TaskSnapshot? = null,
)

class MainViewModel(
    private val gateway: Gateway,
    private val explorationAgent: ExplorationAgent? = null,
    private val appScanner: AppScanner? = null,
    private val skillStore: SkillStore? = null,
    private val explorationNotifier: ExplorationNotifier? = null,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val isAccessibilityServiceConnected: () -> Boolean = {
        AccessibilityCaptureBridge.serviceConnected.value
    },
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun onPromptChange(value: String) {
        _uiState.update { current -> current.copy(prompt = value) }
    }

    fun submit() {
        val prompt = uiState.value.prompt.trim()
        if (prompt.isEmpty()) return

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.USER,
            text = prompt,
        )

        _uiState.update { current ->
            current.copy(
                prompt = "",
                isRunning = true,
                messages = current.messages + userMessage,
            )
        }

        val explorationTarget = detectExplorationIntent(prompt)
        if (explorationTarget != null && explorationAgent != null) {
            startChatExploration(explorationTarget)
        } else {
            viewModelScope.launch {
                val result = gateway.submitUserMessage(prompt)
                publishTaskResult(result)
            }
        }
    }

    fun confirmTask(taskId: String) {
        resolveConfirmation(taskId, approved = true)
    }

    fun cancelTask(taskId: String) {
        resolveConfirmation(taskId, approved = false)
    }

    fun usePromptSuggestion(prompt: String) {
        _uiState.update { current -> current.copy(prompt = prompt) }
    }

    private fun detectExplorationIntent(message: String): ExplorationTarget? {
        val scanner = appScanner ?: return null
        val normalized = message.trim()

        val appNameCandidate = EXPLORATION_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.find(normalized)?.groupValues?.getOrNull(1)?.trim()
        } ?: return null

        if (appNameCandidate.isBlank()) return null

        val apps = runCatching { scanner.scanInstalledApps() }.getOrNull().orEmpty()
        val matched = findMatchingApp(appNameCandidate, apps) ?: return null

        return ExplorationTarget(
            packageName = matched.packageName,
            appName = matched.appName,
        )
    }

    private fun startChatExploration(target: ExplorationTarget) {
        val agent = explorationAgent ?: return
        if (!isAccessibilityServiceConnected()) {
            debugLog(
                "Chat exploration blocked app=${target.appName} connected=${AccessibilityCaptureBridge.serviceConnected.value} latestPackage=${AccessibilityCaptureBridge.latestSnapshot.value?.packageName}",
            )
            _uiState.update { current -> current.copy(isRunning = false) }
            postAssistantMessage(accessibilityDisconnectedMessage(target.appName))
            return
        }

        postAssistantMessage("好的，我开始自主学习「${target.appName}」。探索过程中会持续反馈进度。")

        viewModelScope.launch {
            runCatching {
                withContext(workDispatcher) {
                    agent.explore(
                        appPackage = target.packageName,
                        appName = target.appName,
                        onProgress = { progress ->
                            explorationNotifier?.showProgress(progress)
                            if (progress.status == ExplorationStatus.EXPLORING &&
                                progress.pagesDiscovered > 0 &&
                                progress.pagesDiscovered % 3 == 0
                            ) {
                                postAssistantMessage(
                                    "探索进行中：已发现 ${progress.pagesDiscovered} 个页面，" +
                                        "记录 ${progress.transitionsRecorded} 条跳转，" +
                                        "步骤 ${progress.stepsUsed}/${progress.stepsTotal}。"
                                )
                            }
                        },
                    )
                }
            }.onSuccess { outcome ->
                // Save drafts
                withContext(workDispatcher) {
                    outcome.drafts.forEach { draft ->
                        runCatching {
                            skillStore?.saveLearnedSkill(
                                manifest = draft.manifest,
                                bindings = draft.bindings,
                                pageGraph = draft.pageGraph,
                                evidence = draft.evidence,
                            )
                            skillStore?.setSkillEnabled(draft.manifest.skillId, enabled = false)
                            skillStore?.updateReviewStatus(draft.manifest.skillId, SKILL_REVIEW_PENDING)
                        }
                    }
                }

                explorationNotifier?.showCompleted(
                    pagesDiscovered = outcome.pagesDiscovered,
                    draftsGenerated = outcome.drafts.size,
                )

                postAssistantMessage(
                    "「${target.appName}」学习完成！\n" +
                        "发现 ${outcome.pagesDiscovered} 个页面，" +
                        "记录 ${outcome.transitionsRecorded} 条跳转，" +
                        "生成 ${outcome.drafts.size} 个 Skill 草稿。\n" +
                        "草稿已进入审核列表，你可以到「学习」页签查看和批准。"
                )

                _uiState.update { current -> current.copy(isRunning = false) }
            }.onFailure { error ->
                explorationNotifier?.showFailed(error.message ?: "探索失败")

                postAssistantMessage(
                    "学习「${target.appName}」失败了。\n错误：${error.message ?: "未知错误"}"
                )

                _uiState.update { current -> current.copy(isRunning = false) }
            }
        }
    }

    private fun postAssistantMessage(text: String) {
        _uiState.update { current ->
            current.copy(
                messages = current.messages + ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = ChatRole.ASSISTANT,
                    text = text,
                ),
            )
        }
    }

    private fun resolveConfirmation(taskId: String, approved: Boolean) {
        viewModelScope.launch {
            _uiState.update { current -> current.copy(isRunning = true) }

            val result = gateway.confirmAction(taskId, approved)
            publishTaskResult(result)
        }
    }

    private fun publishTaskResult(result: TaskSnapshot) {
        _uiState.update { current ->
            current.copy(
                isRunning = false,
                lastTask = result,
                messages = current.messages + result.toAssistantMessage(),
            )
        }
    }

    private fun TaskSnapshot.toAssistantMessage(): ChatMessage {
        val content = buildString {
            when (state) {
                TaskState.SUCCEEDED -> {
                    append("我已经完成这次请求。")
                    actionSpec?.let { action ->
                        append("\n动作：${action.actionId}")
                    }
                    executionResult?.let { result ->
                        append("\n结果：${result.resultSummary}")

                        val surfacedUrl = result.outputData["page_url"] ?: result.outputData["opened_url"]
                        if (!surfacedUrl.isNullOrBlank()) {
                            append("\n地址：$surfacedUrl")
                        }

                        val pageTitle = result.outputData["page_title"]
                        if (!pageTitle.isNullOrBlank()) {
                            append("\n页面标题：$pageTitle")
                        }

                        val aiSummary = result.outputData["ai_summary"]
                        if (!aiSummary.isNullOrBlank()) {
                            append("\n网页总结：\n$aiSummary")
                        } else {
                            val pageContent = result.outputData["page_content"]
                            if (!pageContent.isNullOrBlank()) {
                                append("\n网页内容摘要：\n$pageContent")
                            }
                        }
                    }
                }

                TaskState.NEEDS_CLARIFICATION -> {
                    append("还需要你补充一点信息，我才能继续。")
                    errorMessage?.let { error ->
                        append("\n需要补充：$error")
                    }
                }

                TaskState.AWAITING_CONFIRMATION -> {
                    append("这次操作需要你确认后我才会继续执行。")
                    actionSpec?.let { action ->
                        append("\n动作：${action.actionId}")
                        append("\n说明：${action.intentSummary}")
                    }
                }

                TaskState.REFUSED -> {
                    append("这次请求我没有执行。")
                    errorMessage?.let { error ->
                        append("\n原因：$error")
                    }
                }

                TaskState.CANCELLED -> {
                    append("这次请求已经取消。")
                    errorMessage?.let { error ->
                        append("\n说明：$error")
                    }
                }

                TaskState.FAILED -> {
                    append("这次请求执行失败了。")
                    errorMessage?.let { error ->
                        append("\n错误：$error")
                    }
                    executionResult?.errorMessage?.let { error ->
                        if (errorMessage != error) {
                            append("\n执行错误：$error")
                        }
                    }
                }

                else -> {
                    append("任务已更新。当前状态：$state")
                }
            }

            planningTrace?.let { trace ->
                if (trace.outputText.isNotBlank()) {
                    append("\n\nAI 原始回复：\n${trace.outputText}")
                }
            }
        }

        return ChatMessage(
            id = "${taskId}-${state.name.lowercase()}",
            role = ChatRole.ASSISTANT,
            text = content,
            taskState = state,
        )
    }
}

private data class ExplorationTarget(
    val packageName: String,
    val appName: String,
)

private fun debugLog(message: String) {
    runCatching {
        Log.d(LEARNING_LOG_TAG, message)
    }
}

private fun accessibilityDisconnectedMessage(appName: String): String {
    return "现在还不能学习「$appName」。\n请先到「学习」页重新开启 PhoneClaw 无障碍服务。"
}

private val EXPLORATION_PATTERNS = listOf(
    Regex("^(?:学习|探索|学会?用?)\\s*[「「【]?(.+?)[」」】]?$"),
    Regex("^(?:帮我)?(?:学习|探索|学会?用?)\\s*(.+)$"),
    Regex("^(?:teach|learn|explore)\\s+(.+)$", RegexOption.IGNORE_CASE),
)

private fun findMatchingApp(query: String, apps: List<InstalledApp>): InstalledApp? {
    val q = query.lowercase().trim()

    // Exact app name match
    apps.firstOrNull { it.appName.lowercase() == q }?.let { return it }

    // App name contains query
    apps.firstOrNull { it.appName.lowercase().contains(q) }?.let { return it }

    // Query contains app name
    apps.firstOrNull { q.contains(it.appName.lowercase()) }?.let { return it }

    // Package name match
    apps.firstOrNull { it.packageName.lowercase().contains(q) }?.let { return it }

    return null
}

class MainViewModelFactory(
    private val gateway: Gateway,
    private val explorationAgent: ExplorationAgent? = null,
    private val appScanner: AppScanner? = null,
    private val skillStore: SkillStore? = null,
    private val explorationNotifier: ExplorationNotifier? = null,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(
                gateway = gateway,
                explorationAgent = explorationAgent,
                appScanner = appScanner,
                skillStore = skillStore,
                explorationNotifier = explorationNotifier,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}





