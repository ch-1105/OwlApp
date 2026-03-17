package com.phoneclaw.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.phoneclaw.app.contracts.TaskSnapshot
import com.phoneclaw.app.contracts.TaskState
import com.phoneclaw.app.gateway.Gateway
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
                我已经准备好了。你可以直接让我执行当前已接入的能力，比如：
                - 打开系统设置
                - 打开 Wi-Fi 设置
                - 打开蓝牙设置
            """.trimIndent(),
        ),
    ),
    val lastTask: TaskSnapshot? = null,
)

class MainViewModel(
    private val gateway: Gateway,
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

        viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    prompt = "",
                    isRunning = true,
                    messages = current.messages + userMessage,
                )
            }

            val result = gateway.submitUserMessage(prompt)
            _uiState.update { current ->
                current.copy(
                    isRunning = false,
                    lastTask = result,
                    messages = current.messages + result.toAssistantMessage(),
                )
            }
        }
    }

    fun usePromptSuggestion(prompt: String) {
        _uiState.update { current -> current.copy(prompt = prompt) }
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

                        val pageTitle = result.outputData["page_title"]
                        if (!pageTitle.isNullOrBlank()) {
                            append("\n页面标题：$pageTitle")
                        }

                        val pageContent = result.outputData["page_content"]
                        if (!pageContent.isNullOrBlank()) {
                            append("\n网页内容摘要：\n$pageContent")
                        }

                        val openedUrl = result.outputData["opened_url"]
                        if (!openedUrl.isNullOrBlank()) {
                            append("\n地址：$openedUrl")
                        }
                    }
                }

                TaskState.REFUSED -> {
                    append("这次请求我没有执行。")
                    errorMessage?.let { error ->
                        append("\n原因：$error")
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
            id = taskId,
            role = ChatRole.ASSISTANT,
            text = content,
            taskState = state,
        )
    }
}

class MainViewModelFactory(
    private val gateway: Gateway,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(gateway) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
