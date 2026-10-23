package ai.zcode.mobile.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ai.zcode.mobile.MainActivity
import ai.zcode.mobile.R

/**
 * Bugfix: 原实现把远控地址原样塞进 intent extra，令牌会随通知与任务恢复链路外泄。
 * 现在只带端点 id、名称与脱敏地址，回跳时按 id 还原会话，令牌仍从配置里取。
 */
class RemoteControlKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val endpointId = intent?.getStringExtra(EXTRA_ENDPOINT_ID).orEmpty()
        val name = intent?.getStringExtra(EXTRA_ENDPOINT_NAME)
        val displayUrl = intent?.getStringExtra(EXTRA_REMOTE_URL).orEmpty()
        ensureNotificationChannel()

        // Bugfix: 纯浏览器后台后 WebView/JS/WebSocket 容易被系统暂停或回收。
        // 前台服务不能保证 WebView socket 永不断，但能给远控会话一个用户可见的保活边界，
        // 降低进程被杀概率；真正的状态恢复仍交给 web 端 replayable snapshot 协议完成。
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_remote_control)
            .setContentTitle(getString(R.string.remote_keep_alive_title))
            .setContentText(
                listOfNotNull(name?.takeIf { it.isNotBlank() }, displayUrl.takeIf { it.isNotBlank() })
                    .joinToString(" · ")
                    .ifBlank { getString(R.string.remote_keep_alive_text) },
            )
            .setContentIntent(createContentIntent(endpointId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.remote_keep_alive_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.remote_keep_alive_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun createContentIntent(endpointId: String): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(ACTION_RESTORE)
            .putExtra(EXTRA_ENDPOINT_ID, endpointId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val CHANNEL_ID = "zcode_remote_control"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_RESTORE = "ai.zcode.mobile.action.RESTORE_ENDPOINT"
        const val EXTRA_ENDPOINT_ID = "ai.zcode.mobile.extra.ENDPOINT_ID"
        const val EXTRA_ENDPOINT_NAME = "ai.zcode.mobile.extra.ENDPOINT_NAME"
        const val EXTRA_REMOTE_URL = "ai.zcode.mobile.extra.REMOTE_URL"

        fun start(context: Context, endpointId: String, name: String, displayUrl: String) {
            val intent = Intent(context, RemoteControlKeepAliveService::class.java)
                .putExtra(EXTRA_ENDPOINT_ID, endpointId)
                .putExtra(EXTRA_ENDPOINT_NAME, name)
                .putExtra(EXTRA_REMOTE_URL, displayUrl)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteControlKeepAliveService::class.java))
        }
    }
}
