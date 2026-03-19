package com.phoneclaw.app.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AppsScreen(
    uiState: AppsUiState,
    onSearchQueryChange: (String) -> Unit,
    onAuthorizationToggle: (AppDisplayItem, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSearchQueryChange,
            label = { Text("搜索应用") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = {
                Text("输入应用名或包名")
            },
        )

        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.apps.isEmpty() -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "没有匹配的应用",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "可以尝试换个关键词，或者先检查设备里是否安装了目标应用。",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        items = uiState.apps,
                        key = { app -> app.packageName },
                    ) { app ->
                        AppRow(
                            app = app,
                            onAuthorizationToggle = { checked ->
                                onAuthorizationToggle(app, checked)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppDisplayItem,
    onAuthorizationToggle: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = buildString {
                        if (!app.versionName.isNullOrBlank()) {
                            append("版本 ${app.versionName}")
                        } else {
                            append("版本未知")
                        }
                        append(if (app.isSystemApp) " · 系统应用" else " · 第三方应用")
                        append(if (app.authorized) " · 已授权" else " · 未授权")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Switch(
                    checked = app.authorized,
                    onCheckedChange = onAuthorizationToggle,
                )
                Text(
                    text = if (app.authorized) "允许" else "关闭",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
