package com.qingping.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 国产 OEM 自启动/后台设置跳转。
 * 注意：各厂商 ROM 的 Activity 路径随时可能变更，跳转失败时引导用户手动搜索“自启动”即可。
 */
object OemUtil {

    fun openAutoStartSettings(context: Context): Boolean {
        val brand = Build.MANUFACTURER.lowercase()
        val candidates: List<Intent> = when {
            brand.contains("xiaomi") || brand.contains("redmi") -> listOf(
                intent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                intent("com.miui.securitycenter", "com.miui.permcenter.powercenter.PowerCenterActivity")
            )
            brand.contains("huawei") || brand.contains("honor") -> listOf(
                intent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                intent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            )
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") -> listOf(
                intent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                intent("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
                intent("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity")
            )
            brand.contains("vivo") || brand.contains("iqoo") -> listOf(
                intent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                intent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
            )
            brand.contains("samsung") -> listOf(
                Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)
            )
            else -> emptyList()
        }
        for (i in candidates) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                return true
            } catch (_: Exception) { }
        }
        // 兜底：打开本应用详情页
        return try {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${context.packageName}")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun intent(pkg: String, cls: String): Intent {
        return Intent().apply { component = ComponentName(pkg, cls) }
    }
}
