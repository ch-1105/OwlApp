package com.phoneclaw.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.phoneclaw.app.learner.ExplorationProgress
import com.phoneclaw.app.learner.ExplorationStatus

const val EXPLORATION_CHANNEL_ID = "phoneclaw_exploration"
private const val EXPLORATION_NOTIFICATION_ID = 9001

class AndroidExplorationNotifier(
    private val context: Context,
) : ExplorationNotifier {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun showProgress(progress: ExplorationProgress) {
        val title = "PhoneClaw 自主探索"
        val text = when (progress.status) {
            ExplorationStatus.LAUNCHING -> "正在启动应用..."
            ExplorationStatus.EXPLORING ->
                "探索中：${progress.currentPageName} | 页面 ${progress.pagesDiscovered} | 步骤 ${progress.stepsUsed}/${progress.stepsTotal}"
            ExplorationStatus.GENERATING -> "正在生成 Skill 草稿..."
            ExplorationStatus.COMPLETED -> "探索完成"
            ExplorationStatus.FAILED -> "探索失败"
        }

        val ongoing = progress.status == ExplorationStatus.LAUNCHING ||
            progress.status == ExplorationStatus.EXPLORING ||
            progress.status == ExplorationStatus.GENERATING

        val builder = NotificationCompat.Builder(context, EXPLORATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setSilent(true)

        if (ongoing && progress.stepsTotal > 0) {
            builder.setProgress(progress.stepsTotal, progress.stepsUsed, false)
        }

        notificationManager.notify(EXPLORATION_NOTIFICATION_ID, builder.build())
    }

    override fun showCompleted(pagesDiscovered: Int, draftsGenerated: Int) {
        val builder = NotificationCompat.Builder(context, EXPLORATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("PhoneClaw 探索完成")
            .setContentText("发现 $pagesDiscovered 个页面，生成 $draftsGenerated 个 Skill 草稿")
            .setOngoing(false)
            .setAutoCancel(true)

        notificationManager.notify(EXPLORATION_NOTIFICATION_ID, builder.build())
    }

    override fun showFailed(errorMessage: String) {
        val builder = NotificationCompat.Builder(context, EXPLORATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("PhoneClaw 探索失败")
            .setContentText(errorMessage)
            .setOngoing(false)
            .setAutoCancel(true)

        notificationManager.notify(EXPLORATION_NOTIFICATION_ID, builder.build())
    }

    override fun dismiss() {
        notificationManager.cancel(EXPLORATION_NOTIFICATION_ID)
    }

    companion object {
        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    EXPLORATION_CHANNEL_ID,
                    "探索进度",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "自主探索过程中的实时进度通知"
                }
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
            }
        }
    }
}
