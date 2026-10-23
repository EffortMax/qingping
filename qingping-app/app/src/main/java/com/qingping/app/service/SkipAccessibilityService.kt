package com.qingping.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.qingping.app.QingPingApp
import com.qingping.app.data.AppDatabase
import com.qingping.app.data.StatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 清屏核心：无障碍自动跳过服务（v1.1 严格模式）
 *
 * ===== v1.1 修复的误点问题 =====
 * v1.0 事故：默认关键词含“关闭”“知道了”，且在任何界面任何时候都扫描，
 *          导致用户正常切屏、弹窗、列表页都会被乱点，甚至会误触发“返回”。
 * v1.1 规则（四重闸门，全部满足才点击）：
 *   ① 时间闸门：只在新应用启动后的 SAFE_WINDOW_MS（默认 6 秒）内工作，
 *      平时浏览、切屏、聊天一律不扫描 → 不会乱点；
 *   ② 关键词闸门：不再用“包含”匹配，改为归一化后【完全相等】匹配白名单，
 *      “关闭”“知道了”“取消”“退出”全部排除，只有“跳过/跳过广告/关闭广告/Skip”这类才算；
 *   ③ 形态闸门：节点必须可见、面积小于屏幕 20%、且不是输入框/滚动列表里的文字
 *      （跳过按钮都是小按钮，正常正文/大容器直接排除）；
 *   ④ 频次闸门：同一次开屏最多点 1 次，全局 1.5 秒冷却，避免连点。
 *   另外：系统界面、桌面、输入法、权限弹窗、设置页一律不进入（黑名单）。
 *
 * 误触返回：只有当【我们自己刚点了一下】且 1.2 秒内跳到别的应用时才自动返回，
 *           不再把用户主动切屏误判成误触（这是 v1.0 第二个事故点）。
 *
 * 保活原理：无障碍服务由 system_server 调度，用户“清理后台”杀掉的是
 * com.qingping.app 进程，不会关闭本服务。
 */
class SkipAccessibilityService : AccessibilityService() {

    companion object {
        /** 开屏安全窗口：应用启动后多少毫秒内允许扫描（严格模式） */
        private const val SAFE_WINDOW_MS = 6000L
        /** 全局点击冷却 */
        private const val CLICK_COOLDOWN_MS = 1500L
        /** 同一次开屏最多点击次数 */
        private const val MAX_CLICKS_PER_WINDOW = 1
        /** 误触返回：必须在“我们自己点击”后多少毫秒内的跳转才算数 */
        private const val MISCLICK_BACK_WINDOW_MS = 1200L

        /**
         * 严格模式关键词白名单（归一化后完全相等才算命中）。
         * 注意：绝对不要加“关闭”“知道了”“取消”“确定”“X”，
         * 这些词在正常界面里遍地都是，一加就会乱点。
         */
        private val STRICT_TARGETS = setOf(
            "跳过", "跳过广告", "跳过此广告", "跳过该广告", "跳过开屏广告", "跳过开屏",
            "跳过视频广告", "跳过片头广告", "关闭广告", "关闭此广告", "关闭该广告",
            "广告关闭", "跳過", "跳過廣告", "略过", "略過",
            "skip", "skipad", "skipads", "skipthisad", "skipvideo"
        )

        /** 即使在开屏窗口内也绝不能碰的包（系统界面/桌面/输入法/权限弹窗等） */
        private val PKG_BLACKLIST = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.launcher3",
            "com.miui.home", "com.miui.securitycenter",
            "com.huawei.android.launcher",
            "com.oppo.launcher", "com.bbk.launcher2",
            "com.sec.android.app.launcher",
            "com.google.android.inputmethod.latin",
            "com.sohu.inputmethod.sogou", "com.tencent.qqpinyin",
            "com.baidu.input", "com.iflytek.inputmethod"
        )

        /** 包名里出现这些片段也跳过（各家输入法/桌面变体太多，用规则兜住） */
        private val PKG_BLACKLIST_KEYWORDS = listOf(
            "inputmethod", "launcher", "systemui", "permissioncontroller",
            "packageinstaller", "settings", "keyboard"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: StatRepository
    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    // —— 开屏安全窗口状态 ——
    private var windowPkg: String = ""
    private var windowStart: Long = 0L
    private var clicksInWindow: Int = 0

    // —— 限流 ——
    private var lastClickTime = 0L

    // —— 误触返回：只认“我们自己点击”引发的跳转 ——
    @Volatile private var ourLastClickTime: Long = 0L
    @Volatile private var ourLastClickPkg: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = StatRepository(AppDatabase.getInstance(this).statDao())
        prefs = getSharedPreferences(QingPingApp.PREFS, MODE_PRIVATE)
        try {
            KeepAliveService.start(this)
        } catch (_: Exception) { }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (isBlockedPackage(pkg)) return
        if (!isScreenInteractive()) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                onWindowChanged(pkg)
                scanAndSkip(pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 只有处在“开屏安全窗口”内才扫描内容变化，平时浏览完全不介入
                if (!inSafeWindow(pkg)) return
                if (System.currentTimeMillis() - lastClickTime < CLICK_COOLDOWN_MS) return
                scanAndSkip(pkg)
            }
        }
    }

    override fun onInterrupt() { }

    // ================= 闸门 ①：开屏安全窗口 =================

    /** 前台应用变了 → 开启一个新的安全窗口；同一个应用内的窗口切换不重置计时 */
    private fun onWindowChanged(newPkg: String) {
        val now = System.currentTimeMillis()
        if (newPkg != windowPkg) {
            windowPkg = newPkg
            windowStart = now
            clicksInWindow = 0
            handlePossibleMisclick(newPkg, now)
        }
    }

    private fun inSafeWindow(pkg: String): Boolean {
        val now = System.currentTimeMillis()
        if (pkg != windowPkg) return false
        if (clicksInWindow >= MAX_CLICKS_PER_WINDOW) return false
        return now - windowStart <= SAFE_WINDOW_MS
    }

    // ================= 误触返回（严格条件） =================

    /**
     * 只有满足以下全部条件才返回上一页：
     *  - 用户开启了“误触返回”开关；
     *  - 我们自己在 MISCLICK_BACK_WINDOW_MS 内刚点击过；
     *  - 新应用不是我们刚点击的那个应用（说明被广告带走了）。
     * 用户自己主动切屏时 ourLastClickTime 早已过期 → 绝不会触发返回。
     */
    private fun handlePossibleMisclick(newPkg: String, now: Long) {
        if (!prefs.getBoolean(QingPingApp.KEY_AUTO_BACK, false)) return
        if (ourLastClickTime == 0L) return
        if (newPkg == ourLastClickPkg) return
        if (now - ourLastClickTime > MISCLICK_BACK_WINDOW_MS) return

        ourLastClickTime = 0L // 只拦一次，避免循环返回
        mainHandler.postDelayed({
            try {
                if (performGlobalAction(GLOBAL_ACTION_BACK)) {
                    scope.launch { repository.recordBack(newPkg) }
                    showToastIfEnabled("已拦截误触跳转")
                }
            } catch (_: Exception) { }
        }, 400)
    }

    // ================= 扫描并跳过 =================

    private fun scanAndSkip(pkg: String) {
        if (!inSafeWindow(pkg)) return
        val root = rootInActiveWindow ?: return
        try {
            val strict = prefs.getBoolean(QingPingApp.KEY_STRICT_MODE, true)
            val hit = findSkipNode(root, strict) ?: return
            val now = System.currentTimeMillis()
            if (now - lastClickTime < CLICK_COOLDOWN_MS) return

            // 记录“我们自己这次点击”，供误触返回判断使用
            ourLastClickTime = now
            ourLastClickPkg = pkg

            if (clickNode(hit)) {
                lastClickTime = now
                clicksInWindow++
                scope.launch { repository.recordSkip(pkg) }
                showToastIfEnabled("已跳过开屏广告")
            } else {
                ourLastClickTime = 0L
            }
        } catch (_: Exception) {
            // 单次扫描异常不影响服务存活
        }
    }

    /**
     * 查找跳过按钮。
     * strict = true  → 白名单归一化完全相等 + 形态校验（默认，安全）
     * strict = false → 退化为“包含关键词”匹配（老行为，容易误点，仅供调试）
     */
    private fun findSkipNode(root: AccessibilityNodeInfo, strict: Boolean): AccessibilityNodeInfo? {
        val looseKeywords = if (strict) emptyList() else getLooseKeywords()
        val screenArea = screenArea()

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val maxVisit = 200
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        while (queue.isNotEmpty() && visited < maxVisit) {
            val node = queue.removeFirst()
            visited++
            try {
                // 闸门 ③-a：输入框里的文字一律不碰（用户正在打字/搜索）
                if (node.isEditable) continue
                if (node.className?.toString()?.contains("EditText") == true) continue

                val text = node.text?.toString()?.trim() ?: ""
                val desc = node.contentDescription?.toString()?.trim() ?: ""

                val matchedText = matchesTarget(text, desc, strict, looseKeywords)
                if (matchedText) {
                    // 闸门 ③-b：形态校验 —— 必须可见、可点、面积不能太大
                    if (node.isVisibleToUser &&
                        passesShapeCheck(node, screenArea) &&
                        (node.isClickable || hasClickableAncestor(node))
                    ) {
                        candidates.add(node)
                        if (candidates.size >= 3) break
                    }
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            } catch (_: Exception) {
                continue
            }
        }
        if (candidates.isEmpty()) return null
        // 多个候选时，选面积最小的（越小越像跳过按钮）
        return candidates.minByOrNull { boundsArea(it) }
    }

    /** 闸门 ②：文本归一化后完全相等匹配白名单 */
    private fun matchesTarget(text: String, desc: String, strict: Boolean, loose: List<String>): Boolean {
        if (strict) {
            val t = normalize(text)
            val d = normalize(desc)
            return (t.isNotEmpty() && STRICT_TARGETS.contains(t)) ||
                   (d.isNotEmpty() && STRICT_TARGETS.contains(d))
        }
        val combined = "$text $desc"
        if (combined.isBlank()) return false
        return combined.length <= 12 && loose.any { combined.contains(it, ignoreCase = true) }
    }

    /**
     * 归一化：“跳过 3s” / “跳过|5” / “5s 跳过” / “skip 3” 都归成 “跳过” / “skip”。
     * 只做保守处理，不做模糊包含判断——宁可漏点，也不乱点。
     */
    private fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""
        var s = raw.trim().lowercase()
        s = s.replace(Regex("[\\s\\u00A0\\u3000]+"), "")
        // 去掉前置倒计时：3s / 3 / (3s)
        s = s.replace(Regex("^\\(?\\d+(s|sec|秒|s后)?\\)?"), "")
        // 去掉后置倒计时：|3 / | 3s / -5s / ·3
        s = s.replace(Regex("[|｜·•\\-—_/\\\\]?\\d+(s|sec|秒)?$"), "")
        // 去掉残留分隔符
        s = s.replace(Regex("^[|｜·•\\-—_/\\\\]+"), "")
        s = s.replace(Regex("[|｜·•\\-—_/\\\\]+$"), "")
        return s
    }

    /** 闸门 ③-b：跳过按钮都是小控件，面积过大的一定不是 */
    private fun passesShapeCheck(node: AccessibilityNodeInfo, screenArea: Int): Boolean {
        return try {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.isEmpty) return false
            val area = r.width().toLong() * r.height().toLong()
            if (screenArea > 0 && area > screenArea * 0.20) return false
            r.width() > 0 && r.height() > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        return try {
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 3) {
                if (parent.isClickable && parent.isEnabled) return true
                parent = parent.parent
                depth++
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun screenArea(): Int {
        return try {
            val dm = resources.displayMetrics
            dm.widthPixels * dm.heightPixels
        } catch (_: Exception) {
            0
        }
    }

    private fun boundsArea(node: AccessibilityNodeInfo): Int {
        return try {
            val r = Rect()
            node.getBoundsInScreen(r)
            r.width() * r.height()
        } catch (_: Exception) {
            Int.MAX_VALUE
        }
    }

    /** 三级点击：自身可点 → 可点祖先 → 手势点中心 */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        try {
            if (node.isClickable && node.isEnabled) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
        } catch (_: Exception) { }

        try {
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 3) {
                if (parent.isClickable && parent.isEnabled) {
                    if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    break
                }
                parent = parent.parent
                depth++
            }
        } catch (_: Exception) { }

        return try {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.isEmpty) return false
            tapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun tapAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ================= 工具 =================

    private fun isBlockedPackage(pkg: String): Boolean {
        if (PKG_BLACKLIST.contains(pkg)) return true
        val lower = pkg.lowercase()
        return PKG_BLACKLIST_KEYWORDS.any { lower.contains(it) }
    }

    private fun isScreenInteractive(): Boolean {
        return try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            pm.isInteractive
        } catch (_: Exception) {
            true
        }
    }

    /** 仅调试用的宽松关键词（严格模式关闭时才生效） */
    private fun getLooseKeywords(): List<String> {
        val raw = prefs.getString(QingPingApp.KEY_KEYWORDS, "跳过,Skip") ?: "跳过,Skip"
        return raw.split(",", "，", "|").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun showToastIfEnabled(msg: String) {
        if (!prefs.getBoolean(QingPingApp.KEY_SHOW_TOAST, false)) return
        mainHandler.post {
            try {
                Toast.makeText(this, "清屏 · $msg", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) { }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
