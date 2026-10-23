package com.qingping.app.util

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

object AppInfoUtil {

    data class AppInfo(val name: String, val icon: Drawable?)

    fun get(context: Context, packageName: String): AppInfo {
        return try {
            val pm: PackageManager = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            AppInfo(
                name = pm.getApplicationLabel(info).toString(),
                icon = pm.getApplicationIcon(info)
            )
        } catch (_: Exception) {
            AppInfo(name = packageName, icon = null)
        }
    }
}
