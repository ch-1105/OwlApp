package com.phoneclaw.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phoneclaw.app.contracts.TaskState
import com.phoneclaw.app.di.AppGraph

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneClawApp(
    appGraph: AppGraph,
) {
    val supportedActions = appGraph.skillRegistry.allActions()

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
                    .padding(innerPadding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Milestone 2 system skills",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "This build wires Gateway -> Planning -> Policy -> Executor through a static Skill Registry. It can use a real cloud provider and now supports multiple low-risk system settings actions.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Registered actions",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        supportedActions.forEach { action ->
                            Text("- ${action.actionId}: ${action.action.displayName}")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Try: open wifi settings / 打开蓝牙设置 / 打开系统设置",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = uiState.prompt,
                            onValueChange = viewModel::onPromptChange,
                            label = { Text("User request") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = viewModel::submit,
                                enabled = !uiState.isRunning,
                            ) {
                                Text(if (uiState.isRunning) "Running..." else "Submit")
                            }
                        }
                    }
                }

                uiState.lastTask?.let { task ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Latest task",
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
                                if (trace.outputText.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("AI reply:")
                                    Spacer(modifier = Modifier.height(4.dp))
                                    SelectionContainer {
                                        Text(
                                            text = trace.outputText,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
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
                            }
                            task.errorMessage?.let { error ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Error: $error")
                            }
                            if (task.state == TaskState.SUCCEEDED) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "If you are running on a device, the requested settings screen should open. Return to PhoneClaw to inspect the model reply above.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
