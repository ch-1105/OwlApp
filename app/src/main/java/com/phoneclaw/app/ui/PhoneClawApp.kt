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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phoneclaw.app.contracts.TaskSnapshot
import com.phoneclaw.app.di.AppGraph

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneClawApp(
    appGraph: AppGraph,
) {
    val supportedActions = appGraph.skillRegistry.allActions()
    val promptSuggestions = listOf(
        "打开系统设置",
        "打开 Wi-Fi 设置",
        "打开蓝牙设置",
    )

    PhoneClawTheme {
        val viewModel: MainViewModel = viewModel(
            factory = MainViewModelFactory(appGraph.gateway),
        )
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text("PhoneClaw")
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        CapabilityCard(
                            supportedActionIds = supportedActions.map { it.actionId },
                        )
                    }

                    item {
                        PromptSuggestionCard(
                            suggestions = promptSuggestions,
                            onSuggestionClick = viewModel::usePromptSuggestion,
                        )
                    }

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
                            TaskDebugCard(task = task)
                        }
                    }
                }

                ComposerCard(
                    prompt = uiState.prompt,
                    isRunning = uiState.isRunning,
                    onPromptChange = viewModel::onPromptChange,
                    onSubmit = viewModel::submit,
                )
            }
        }
    }
}

@Composable
private fun CapabilityCard(
    supportedActionIds: List<String>,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "当前能力",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "现在的主链路已经接通到云端规划、策略审核和动作执行。当前先验证低风险动作能力。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            supportedActionIds.forEach { actionId ->
                Text("- $actionId")
            }
        }
    }
}

@Composable
private fun PromptSuggestionCard(
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "快速示例",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            suggestions.forEach { suggestion ->
                OutlinedButton(
                    onClick = { onSuggestionClick(suggestion) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(suggestion)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
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
                    Text("例如：打开系统设置")
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
