package com.phoneclaw.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phoneclaw.app.explorer.PageTreeSnapshot
import com.phoneclaw.app.explorer.totalNodeCount

@Composable
fun AccessibilityGuideScreen(
    serviceConnected: Boolean,
    latestSnapshot: PageTreeSnapshot?,
    onRefreshSnapshot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val canCapture = serviceConnected || latestSnapshot != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "无障碍探索",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "先在系统无障碍设置里开启 PhoneClaw 的探索服务，之后切到其他 App，PhoneClaw 才能看到页面树。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = if (canCapture) {
                        "服务状态：已连接"
                    } else {
                        "服务状态：未连接"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (canCapture) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                ) {
                    Text("打开无障碍设置")
                }
                Button(
                    onClick = onRefreshSnapshot,
                    enabled = canCapture,
                ) {
                    Text("刷新当前页面快照")
                }
            }
        }

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
                        text = "还没有可用快照。开启服务后，切到其他 App 再回来，或者点击刷新。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text("Package: ${latestSnapshot.packageName}")
                    Text("Activity: ${latestSnapshot.activityName ?: "unknown"}")
                    Text("Node Count: ${latestSnapshot.totalNodeCount()}")
                    Text("Captured At: ${latestSnapshot.timestamp}")
                }
            }
        }
    }
}
