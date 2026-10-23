package com.qingping.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.qingping.app.R
import com.qingping.app.ui.MainActivity

/**
 * 前台保活服务。
 *
 * 真相说明（必须告诉用户）：
 * - 真正干活的是 SkipAccessibilityService（系统级，不怕清理后台）；
 * - 本服务的作用是：① 常驻通知提示用户服务在运行；② 开机后被 BootReceiver 拉起，
 *   顺带检查无障碍开关状态；③ 提高本 App 进程优先级，让统计写入更及时。
 * - 用户“清理后台”会杀掉本服务，但无障碍跳过不受影响；下次打开清屏或重启后本服务会恢复。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        // START_STICKY：被杀后系统会尝试重建（OEM 省电策略可能阻止，这是系统限制）
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keepalive_channel),
                NotificationManager.IMPORTANCE_MIN // 最低重要性，不响铃不震动
            )
            nm.createNotificationChannel(channel)
        }
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.keepalive_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "qingping_keepalive"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
