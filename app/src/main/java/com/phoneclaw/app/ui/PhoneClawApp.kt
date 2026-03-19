package com.phoneclaw.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phoneclaw.app.contracts.TaskSnapshot
import com.phoneclaw.app.contracts.TaskState
import com.phoneclaw.app.di.AppGraph
import com.phoneclaw.app.explorer.AccessibilityCaptureBridge
import com.phoneclaw.app.ui.apps.AppsScreen
import com.phoneclaw.app.ui.apps.AppsViewModel
import com.phoneclaw.app.ui.apps.AppsViewModelFactory
import com.phoneclaw.app.ui.settings.AccessibilityGuideScreen

enum class PhoneClawTab(
    val title: String,
    val label: String,
    val marker: String,
) {
    CHAT(title = "PhoneClaw", label = "对话", marker = "聊"),
    APPS(title = "应用管理", label = "应用", marker = "应"),
    EXPLORE(title = "无障碍探索", label = "探索", marker = "探"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneClawApp(
    appGraph: AppGraph,
) {
    PhoneClawTheme {
        val chatViewModel: MainViewModel = viewModel(
            factory = MainViewModelFactory(appGraph.gateway),
        )
        val appsViewModel: AppsViewModel = viewModel(
            factory = AppsViewModelFactory(appGraph.appScanner, appGraph.authorizationManager),
        )
        val chatUiState by chatViewModel.uiState.collectAsStateWithLifecycle()
        val appsUiState by appsViewModel.uiState.collectAsStateWithLifecycle()
        val serviceConnected by AccessibilityCaptureBridge.serviceConnected.collectAsStateWithLifecycle()
        val latestSnapshot by AccessibilityCaptureBridge.latestSnapshot.collectAsStateWithLifecycle()
        var selectedTab by rememberSaveable { mutableStateOf(PhoneClawTab.CHAT) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(selectedTab.title)
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    PhoneClawTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = {
                                Text(tab.marker)
                            },
                            label = {
                                Text(tab.label)
                            },
                        )
                    }
                }
            },
        ) { innerPadding ->
            when (selectedTab) {
                PhoneClawTab.CHAT -> ChatTabContent(
                    uiState = chatUiState,
                    onPromptChange = chatViewModel::onPromptChange,
                    onSubmit = chatViewModel::submit,
                    onConfirmTask = chatViewModel::confirmTask,
                    onCancelTask = chatViewModel::cancelTask,
                    isRunning = chatUiState.isRunning,
                    modifier = Modifier.padding(innerPadding),
                )

                PhoneClawTab.APPS -> AppsScreen(
                    uiState = appsUiState,
                    onSearchQueryChange = appsViewModel::onSearchQueryChange,
                    onAuthorizationToggle = appsViewModel::onAuthorizationToggle,
                    modifier = Modifier.padding(innerPadding),
                )

                PhoneClawTab.EXPLORE -> AccessibilityGuideScreen(
                    serviceConnected = serviceConnected,
                    latestSnapshot = latestSnapshot,
                    onRefreshSnapshot = {
                        AccessibilityCaptureBridge.captureCurrentPageTree()
                    },
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

@Composable
private fun ChatTabContent(
    uiState: MainUiState,
    onPromptChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onConfirmTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(
                items = uiState.messages,
                key = { message -> message.id },
            ) { message ->
                ChatBubble(message = message)
            }

            if (uiState.isRunning) {
                item {
                    RunningCard()
                }
            }

            uiState.lastTask?.let { task ->
                item {
                    TaskDebugCard(
                        task = task,
                        onConfirmTask = onConfirmTask,
                        onCancelTask = onCancelTask,
                        isRunning = isRunning,
                    )
                }
            }
        }

        ComposerCard(
            prompt = uiState.prompt,
            isRunning = uiState.isRunning,
            onPromptChange = onPromptChange,
            onSubmit = onSubmit,
        )
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.role == ChatRole.USER) {
            Arrangement.End
        } else {
            Arrangement.Start
        },
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = RoundedCornerShape(20.dp),
            color = if (message.role == ChatRole.USER) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            tonalElevation = 2.dp,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (message.role == ChatRole.USER) "你" else "PhoneClaw",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                message.taskState?.let { taskState ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "状态：$taskState",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun RunningCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.height(24.dp))
            Column {
                Text(
                    text = "AI 正在处理中",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "正在进行规划、审核和执行，请稍等。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun TaskDebugCard(
    task: TaskSnapshot,
    onConfirmTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    isRunning: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "最近一次任务详情",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("Task ID: ${task.taskId}")
            Text("User request: ${task.userMessage}")
            Text("State: ${task.state}")
            task.actionSpec?.let { action ->
                Text("Action: ${action.actionId}")
                Text("Skill: ${action.skillId}")
            }
            if (task.state == TaskState.AWAITING_CONFIRMATION) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "这次操作已经规划完成，确认后才会真正执行。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { onCancelTask(task.taskId) },
                        enabled = !isRunning,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = { onConfirmTask(task.taskId) },
                        enabled = !isRunning,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("确认执行")
                    }
                }
            }
            task.planningTrace?.let { trace ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Model trace",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text("Source: ${if (trace.usedRemote) "remote" else "stub"}")
                Text("Provider: ${trace.provider.ifBlank { "unknown" }}")
                Text("Model: ${trace.modelId.ifBlank { "unknown" }}")
                trace.errorKind?.let { errorKind ->
                    Text("Model error kind: $errorKind")
                }
                trace.errorMessage?.let { error ->
                    Text("Model error: $error")
                }
            }
            task.executionResult?.let { result ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Execution trace",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text("Execution: ${result.status}")
                Text("Summary: ${result.resultSummary}")
                if (result.outputData.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    result.outputData.forEach { (key, value) ->
                        Text("$key: $value")
                    }
                }
            }
            task.errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text("Error: $error")
            }
        }
    }
}

@Composable
private fun ComposerCard(
    prompt: String,
    isRunning: Boolean,
    onPromptChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                label = { Text("输入你的请求") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                placeholder = {
                    Text("例如：打开 https://openai.com")
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onSubmit,
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isRunning) "处理中..." else "发送")
            }
        }
    }
}
