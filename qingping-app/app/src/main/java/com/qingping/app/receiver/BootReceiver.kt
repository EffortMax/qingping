package com.qingping.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.qingping.app.service.KeepAliveService

/**
 * 开机自启：监听 BOOT_COMPLETED，拉起保活服务。
 *
 * 系统限制（必须明确）：
 * 1. Android 3.1+：APP 安装后必须被用户手动打开过一次，BOOT_COMPLETED 才会生效；
 * 2. 小米/华为/OPPO/vivo 等：还需要在系统“自启动管理”里手动允许，否则收不到广播；
 * 3. 无障碍服务本身由系统在开机后自动恢复（无需本广播），本广播只是恢复前台通知和自检。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_REBOOT
        ) {
            try {
                KeepAliveService.start(context)
            } catch (_: Exception) {
                // Android 10+ 后台启动前台服务可能抛 IllegalStateException，
                // 此时只能等用户下次打开 APP 再启动，这是系统限制。
            }
        }
    }
}
