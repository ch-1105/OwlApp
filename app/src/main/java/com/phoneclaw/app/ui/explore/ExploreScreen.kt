package com.phoneclaw.app.ui.explore

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phoneclaw.app.explorer.AccessibilityNodeSnapshot
import com.phoneclaw.app.explorer.PageTreeSnapshot
import com.phoneclaw.app.explorer.totalNodeCount
import com.phoneclaw.app.learner.ExplorationProgress
import com.phoneclaw.app.learner.ExplorationStatus
import com.phoneclaw.app.learner.LearningSessionState
import com.phoneclaw.app.learner.LearningStatus
import com.phoneclaw.app.store.SKILL_REVIEW_APPROVED
import com.phoneclaw.app.store.SKILL_REVIEW_PENDING
import com.phoneclaw.app.store.SKILL_REVIEW_REJECTED

@Composable
fun ExploreScreen(
    serviceConnected: Boolean,
    latestSnapshot: PageTreeSnapshot?,
    uiState: ExploreUiState,
    onRefreshSnapshot: () -> Unit,
    onStartLearning: (PageTreeSnapshot?) -> Unit,
    onStartAutonomousExploration: (PageTreeSnapshot?) -> Unit,
    onCaptureCurrentPage: () -> Unit,
    onTapAndCapture: (String, String) -> Unit,
    onFinishExploration: () -> Unit,
    onApproveSkill: (String) -> Unit,
    onRejectSkill: (String) -> Unit,
    onDraftDisplayNameChange: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clickableNodes = learningNodeSourceSnapshot(uiState).toClickableNodes()
    val hasActiveSession = uiState.activeSessionId != null
    val isExploring = uiState.explorationProgress != null
    val canOperateSession = hasActiveSession && !uiState.isLoading

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ServiceCard(
                serviceConnected = serviceConnected || latestSnapshot != null,
                onOpenAccessibilitySettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                onRefreshSnapshot = onRefreshSnapshot,
                refreshEnabled = serviceConnected || latestSnapshot != null,
            )
        }

        uiState.errorMessage?.let { message ->
            item {
                MessageCard(
                    title = "当前操作失败",
                    message = message,
                    isError = true,
                )
            }
        }

        uiState.infoMessage?.let { message ->
            item {
                MessageCard(
                    title = "当前进展",
                    message = message,
                    isError = false,
                )
            }
        }

        item {
            SnapshotCard(
                latestSnapshot = latestSnapshot,
                onStartLearning = { onStartLearning(latestSnapshot) },
                onStartAutonomousExploration = { onStartAutonomousExploration(latestSnapshot) },
                startEnabled = latestSnapshot != null && !uiState.isLoading && !hasActiveSession && !isExploring,
                isLearning = hasActiveSession,
                isExploring = isExploring,
            )
        }

        uiState.explorationProgress?.let { progress ->
            item {
                ExplorationProgressCard(progress = progress)
            }
        }

        item {
            LearningSessionCard(
                session = uiState.activeSession,
                isLoading = uiState.isLoading,
                clickableNodes = clickableNodes,
                onCaptureCurrentPage = onCaptureCurrentPage,
                onTapAndCapture = onTapAndCapture,
                onFinishExploration = onFinishExploration,
                canOperateSession = canOperateSession,
            )
        }

        item {
            ReviewQueueHeader(
                pendingCount = uiState.reviewItems.count { item -> item.reviewStatus == SKILL_REVIEW_PENDING },
                totalCount = uiState.reviewItems.size,
            )
        }

        if (uiState.reviewItems.isEmpty()) {
            item {
                EmptyReviewCard()
            }
        } else {
            items(
                items = uiState.reviewItems,
                key = { item -> item.skillId },
            ) { item ->
                ReviewSkillCard(
                    item = item,
                    editedDisplayName = uiState.draftNameEdits[item.skillId] ?: item.displayName,
                    onDraftDisplayNameChange = { value ->
                        onDraftDisplayNameChange(item.skillId, value)
                    },
                    onApprove = { onApproveSkill(item.skillId) },
                    onReject = { onRejectSkill(item.skillId) },
                    isLoading = uiState.isLoading,
                )
            }
        }
    }
}

@Composable
private fun ServiceCard(
    serviceConnected: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onRefreshSnapshot: () -> Unit,
    refreshEnabled: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "页面学习",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "先打开无障碍服务，再切到目标 App。这里可以直接采集页面、生成 Skill 草稿，并完成审核。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (serviceConnected) "服务状态：已连接" else "服务状态：未连接",
                style = MaterialTheme.typography.titleMedium,
                color = if (serviceConnected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("打开无障碍设置")
                }
                Button(
                    onClick = onRefreshSnapshot,
                    enabled = refreshEnabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("刷新当前页面")
                }
            }
        }
    }
}

@Composable
private fun MessageCard(
    title: String,
    message: String,
    isError: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SnapshotCard(
    latestSnapshot: PageTreeSnapshot?,
    onStartLearning: () -> Unit,
    onStartAutonomousExploration: () -> Unit,
    startEnabled: Boolean,
    isLearning: Boolean,
    isExploring: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "最近一次页面快照",
                style = MaterialTheme.typography.titleMedium,
            )

            if (latestSnapshot == null) {
                Text(
                    text = "还没有可用快照。先连接服务，然后切到目标 App，再刷新一次当前页面。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            Text("包名：${latestSnapshot.packageName}")
            Text("页面：${latestSnapshot.activityName ?: "未知 Activity"}")
            Text("节点数：${latestSnapshot.totalNodeCount()}")
            Text("采集时间：${latestSnapshot.timestamp}")

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onStartAutonomousExploration,
                enabled = startEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (isExploring) "正在自主探索..." else "自主学习当前应用",
                )
            }

            Button(
                onClick = onStartLearning,
                enabled = startEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isLearning) "手动学习已开始" else "手动学习当前应用")
            }
        }
    }
}

@Composable
private fun LearningSessionCard(
    session: LearningSessionState?,
    isLoading: Boolean,
    clickableNodes: List<ClickableNodeItem>,
    onCaptureCurrentPage: () -> Unit,
    onTapAndCapture: (String, String) -> Unit,
    onFinishExploration: () -> Unit,
    canOperateSession: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "当前学习会话",
                style = MaterialTheme.typography.titleMedium,
            )

            if (session == null) {
                Text(
                    text = "还没有活跃学习会话。先在上面开始学习当前应用。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            Text("目标应用：${session.appName} (${session.appPackage})")
            Text("状态：${session.status.toDisplayText()}")
            Text("已采集页面：${session.exploredPages.size}")
            Text("已记录跳转：${session.transitions.size}")

            session.exploredPages.lastOrNull()?.let { latestPage ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "最近一个页面：${latestPage.analysis.suggestedPageSpec.pageName}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text("Page ID：${latestPage.analysis.suggestedPageSpec.pageId}")
                if (latestPage.analysis.navigationHints.isNotEmpty()) {
                    Text("导航提示：${latestPage.analysis.navigationHints.joinToString(" / ")}")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onCaptureCurrentPage,
                    enabled = canOperateSession,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (isLoading) "处理中..." else "采集当前页")
                }
                Button(
                    onClick = onFinishExploration,
                    enabled = canOperateSession && session.exploredPages.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("生成草稿")
                }
            }

            if (clickableNodes.isEmpty()) {
                Text(
                    text = "当前快照里还没有发现可点击节点。先采集页面，或者切回目标 App 再刷新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Text(
                text = "从当前快照继续学习",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "下面这些按钮对应当前页面里可点击的节点。点一个后，会尝试执行点击并采集新页面。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            clickableNodes.forEach { node ->
                Button(
                    onClick = {
                        onTapAndCapture(node.nodeId, node.label)
                    },
                    enabled = canOperateSession,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(node.label)
                }
            }
        }
    }
}

@Composable
private fun ExplorationProgressCard(progress: ExplorationProgress) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "自主探索进度",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = when (progress.status) {
                    ExplorationStatus.LAUNCHING -> "正在启动应用..."
                    ExplorationStatus.EXPLORING -> "正在探索：${progress.currentPageName}"
                    ExplorationStatus.GENERATING -> "正在生成 Skill 草稿..."
                    ExplorationStatus.COMPLETED -> "探索完成"
                    ExplorationStatus.FAILED -> "探索失败"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when (progress.status) {
                    ExplorationStatus.FAILED -> MaterialTheme.colorScheme.error
                    ExplorationStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                },
            )
            Text("已发现页面：${progress.pagesDiscovered}")
            Text("已记录跳转：${progress.transitionsRecorded}")
            Text("操作步数：${progress.stepsUsed} / ${progress.stepsTotal}")
        }
    }
}

@Composable
private fun ReviewQueueHeader(
    pendingCount: Int,
    totalCount: Int,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Skill 审核列表",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "待审核 $pendingCount 个，历史草稿 $totalCount 个。只有批准后的 Skill 才会进入运行时。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EmptyReviewCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "还没有待审核 Skill",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "先开始一个学习会话，采集页面后生成草稿，它就会出现在这里。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ReviewSkillCard(
    item: ReviewSkillItem,
    editedDisplayName: String,
    onDraftDisplayNameChange: (String) -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    isLoading: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "状态：${item.reviewStatus.toReviewDisplayText()}",
                style = MaterialTheme.typography.bodyMedium,
                color = item.reviewStatus.toReviewColor(),
            )
            Text("Skill ID：${item.skillId}")
            Text("应用：${item.appPackage ?: "未知"}")
            Text("动作：${item.actionCount} · 页面：${item.pageCount} · 跳转：${item.transitionCount} · 证据：${item.evidenceCount}")

            OutlinedTextField(
                value = editedDisplayName,
                onValueChange = onDraftDisplayNameChange,
                label = { Text("Skill 显示名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            if (item.pageGraph != null) {
                Text(
                    text = "页面：${item.pageGraph.pages.joinToString(" / ") { page -> page.pageName }}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = "动作 ID：${item.manifest.actions.joinToString(" / ") { action -> action.actionId }}",
                style = MaterialTheme.typography.bodySmall,
            )

            if (item.reviewStatus != SKILL_REVIEW_APPROVED) {
                Button(
                    onClick = onApprove,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("批准生效")
                }
            }

            if (item.reviewStatus != SKILL_REVIEW_REJECTED) {
                Button(
                    onClick = onReject,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("驳回草稿")
                }
            }
        }
    }
}

internal fun learningNodeSourceSnapshot(uiState: ExploreUiState): PageTreeSnapshot? {
    return uiState.activeSession?.exploredPages?.lastOrNull()?.pageTree
}

private fun PageTreeSnapshot?.toClickableNodes(): List<ClickableNodeItem> {
    val snapshot = this ?: return emptyList()
    return snapshot.nodes
        .flatMap { node -> node.toClickableNodes() }
        .distinctBy { node -> node.nodeId }
        .take(12)
}

private fun AccessibilityNodeSnapshot.toClickableNodes(): List<ClickableNodeItem> {
    val current = if (isClickable) {
        listOf(
            ClickableNodeItem(
                nodeId = nodeId,
                label = bestLabel(),
            ),
        )
    } else {
        emptyList()
    }

    return current + children.flatMap { child -> child.toClickableNodes() }
}

private fun AccessibilityNodeSnapshot.bestLabel(): String {
    val textValue = text?.trim().orEmpty()
    if (textValue.isNotEmpty()) {
        return textValue
    }

    val contentValue = contentDescription?.trim().orEmpty()
    if (contentValue.isNotEmpty()) {
        return contentValue
    }

    val resourceValue = resourceId?.substringAfterLast("/")?.trim().orEmpty()
    if (resourceValue.isNotEmpty()) {
        return resourceValue
    }

    val classValue = className?.substringAfterLast(".")?.trim().orEmpty()
    if (classValue.isNotEmpty()) {
        return "$classValue ($nodeId)"
    }

    return nodeId
}

private fun LearningStatus.toDisplayText(): String {
    return when (this) {
        LearningStatus.EXPLORING -> "可继续探索"
        LearningStatus.ANALYZING -> "正在分析页面"
        LearningStatus.GENERATING -> "正在生成草稿"
        LearningStatus.REVIEW_PENDING -> "等待审核"
        LearningStatus.COMPLETED -> "已完成"
        LearningStatus.FAILED -> "已失败"
    }
}

private fun String.toReviewDisplayText(): String {
    return when (this) {
        SKILL_REVIEW_PENDING -> "待审核"
        SKILL_REVIEW_APPROVED -> "已批准"
        SKILL_REVIEW_REJECTED -> "已驳回"
        else -> this
    }
}

@Composable
private fun String.toReviewColor(): Color {
    return when (this) {
        SKILL_REVIEW_PENDING -> MaterialTheme.colorScheme.primary
        SKILL_REVIEW_APPROVED -> MaterialTheme.colorScheme.tertiary
        SKILL_REVIEW_REJECTED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private data class ClickableNodeItem(
    val nodeId: String,
    val label: String,
)



