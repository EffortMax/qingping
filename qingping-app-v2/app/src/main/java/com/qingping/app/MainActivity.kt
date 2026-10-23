package com.qingping.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var store: StatsStore
    private lateinit var statusView: TextView
    private lateinit var statsView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = StatsStore(this)
        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        statusView = TextView(this).apply { textSize = 16f }
        column.addView(statusView)

        column.addView(button("打开无障碍设置") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        column.addView(button("申请电池白名单") {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        })
        column.addView(CheckBox(this).apply {
            text = "启用自动跳过"
            isChecked = prefs.getBoolean(Prefs.KEY_ENABLED, true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(Prefs.KEY_ENABLED, checked).apply()
            }
        })
        column.addView(CheckBox(this).apply {
            text = "误触跳转时自动返回（实验性，默认关闭）"
            isChecked = prefs.getBoolean(Prefs.KEY_REDIRECT_BACK, false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(Prefs.KEY_REDIRECT_BACK, checked).apply()
            }
        })

        column.addView(TextView(this).apply {
            text = "\n按应用统计："
            textSize = 15f
        })
        statsView = TextView(this).apply { textSize = 14f }
        column.addView(statsView)

        column.addView(button("清空统计") {
            store.clear()
            refresh()
        })

        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** 应用已在后台时，am start / 点击图标会走 onNewIntent，这里也要刷新。 */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        refresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refresh()
    }

    private fun button(label: String, onClick: () -> Unit): View =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun refresh() {
        statusView.text = if (isServiceEnabled()) {
            "无障碍服务：已开启"
        } else {
            "无障碍服务：未开启（请点击下方“打开无障碍设置”）"
        }
        val list = store.summary()
        val total = list.sumOf { it.skips }
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())
        val sb = StringBuilder("累计跳过：$total 次（更新于 $time）\n\n")
        list.forEach { s ->
            sb.append(labelOf(s.pkg))
                .append("\n  跳过 ${s.skips} 次 · 误触返回 ${s.redirects} 次\n\n")
        }
        if (list.isEmpty()) sb.append("暂无记录")
        statsView.text = sb.toString()
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, SkipAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}
