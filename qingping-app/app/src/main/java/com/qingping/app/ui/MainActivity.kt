package com.qingping.app.ui

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.qingping.app.QingPingApp
import com.qingping.app.R
import com.qingping.app.data.StatEntity
import com.qingping.app.databinding.ActivityMainBinding
import com.qingping.app.service.KeepAliveService
import com.qingping.app.util.AppInfoUtil
import com.qingping.app.util.OemUtil
import com.qingping.app.util.PermissionUtil
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(QingPingApp.PREFS, MODE_PRIVATE)
        val app = application as QingPingApp

        // —— 开关初始化 ——
        binding.switchStrict.isChecked = prefs.getBoolean(QingPingApp.KEY_STRICT_MODE, true)
        binding.switchAutoBack.isChecked = prefs.getBoolean(QingPingApp.KEY_AUTO_BACK, false)
        binding.switchToast.isChecked = prefs.getBoolean(QingPingApp.KEY_SHOW_TOAST, false)

        binding.switchStrict.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(QingPingApp.KEY_STRICT_MODE, checked).apply()
        }
        binding.switchAutoBack.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(QingPingApp.KEY_AUTO_BACK, checked).apply()
        }
        binding.switchToast.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(QingPingApp.KEY_SHOW_TOAST, checked).apply()
        }

        // —— 按钮 ——
        binding.btnAccessibility.setOnClickListener { PermissionUtil.openAccessibilitySettings(this) }
        binding.btnBattery.setOnClickListener { PermissionUtil.requestIgnoreBatteryOptimizations(this) }
        binding.btnNotif.setOnClickListener { requestNotifPermission() }
        binding.btnOem.setOnClickListener {
            if (!OemUtil.openAutoStartSettings(this)) {
                Toast.makeText(this, "无法自动跳转，请在系统设置中搜索“自启动”", Toast.LENGTH_LONG).show()
            }
        }
        binding.btnClear.setOnClickListener { confirmClear() }

        // —— 统计订阅 ——
        lifecycleScope.launch {
            combine(
                app.repository.observeAll(),
                app.repository.observeTotalSkip(),
                app.repository.observeTotalBack()
            ) { list, totalSkip, totalBack -> Triple(list, totalSkip, totalBack) }
                .collect { (list, totalSkip, totalBack) ->
                    binding.tvTotalSkip.text = totalSkip.toString()
                    binding.tvTotalBack.text = totalBack.toString()
                    renderStats(list)
                }
        }

        // 启动保活前台服务（需通知权限，否则 Android 13+ 可能无法显示通知）
        try {
            KeepAliveService.start(this)
        } catch (_: Exception) { }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        // 无障碍
        val accOk = PermissionUtil.isAccessibilityEnabled(this)
        binding.tvAccessibilityStatus.text = if (accOk) getString(R.string.enabled) else getString(R.string.disabled)
        binding.tvAccessibilityStatus.setTextColor(
            ContextCompat.getColor(this, if (accOk) R.color.green else R.color.red)
        )
        binding.btnAccessibility.text = if (accOk) "已开启" else getString(R.string.go_enable)
        binding.btnAccessibility.isEnabled = !accOk

        // 电池白名单
        val batOk = PermissionUtil.isIgnoringBatteryOptimizations(this)
        binding.tvBatteryStatus.text = if (batOk) getString(R.string.enabled) else getString(R.string.disabled)
        binding.tvBatteryStatus.setTextColor(
            ContextCompat.getColor(this, if (batOk) R.color.green else R.color.red)
        )
        binding.btnBattery.isEnabled = !batOk

        // 通知
        val notOk = PermissionUtil.isNotificationEnabled(this)
        binding.tvNotifStatus.text = if (notOk) getString(R.string.enabled) else getString(R.string.disabled)
        binding.tvNotifStatus.setTextColor(
            ContextCompat.getColor(this, if (notOk) R.color.green else R.color.red)
        )
        binding.btnNotif.isEnabled = !notOk
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                PermissionUtil.openNotificationSettings(this)
            } else {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            PermissionUtil.openNotificationSettings(this)
        }
    }

    private fun renderStats(list: List<StatEntity>) {
        binding.tvEmpty.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.statsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        // 最多展示 100 条，防止界面过长
        for (stat in list.take(100)) {
            val view = inflater.inflate(R.layout.item_stat, binding.statsContainer, false)
            val info = AppInfoUtil.get(this, stat.packageName)
            view.findViewById<TextView>(R.id.tvAppName).text = info.name
            view.findViewById<TextView>(R.id.tvPackage).text = stat.packageName
            view.findViewById<TextView>(R.id.tvSkipCount).text = "跳过 ${stat.skipCount} 次"
            view.findViewById<TextView>(R.id.tvBackCount).text = "拦截跳转 ${stat.backCount} 次"
            val iv = view.findViewById<ImageView>(R.id.ivIcon)
            if (info.icon != null) iv.setImageDrawable(info.icon)
            binding.statsContainer.addView(view)
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_clear))
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                lifecycleScope.launch {
                    (application as QingPingApp).repository.clearAll()
                    Toast.makeText(this@MainActivity, "已清空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}
