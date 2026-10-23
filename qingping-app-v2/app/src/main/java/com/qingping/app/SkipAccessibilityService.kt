package com.qingping.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SkipAccessibilityService : AccessibilityService() {

    private lateinit var engine: SkipEngine
    private lateinit var stats: StatsStore

    private var currentPkg = ""
    private var launchStartAt = 0L
    private var clicksThisLaunch = 0
    private var lastClickAt = 0L
    private var lastBackAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        engine = SkipEngine(RuleConfig(blockedPackages = setOf(packageName)))
        stats = StatsStore(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        val pkg = event.packageName?.toString() ?: return

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != currentPkg) {
            onAppSwitch(pkg)
        }
        if (pkg != currentPkg) return
        if (!isEnabled()) return
        scanAndSkip(pkg)
    }

    override fun onInterrupt() {
        // no-op
    }

    private fun onAppSwitch(newPkg: String) {
        val now = System.currentTimeMillis()
        val prev = currentPkg
        val prevAge = now - launchStartAt
        currentPkg = newPkg
        launchStartAt = now
        clicksThisLaunch = 0

        val enabled = prefs().getBoolean(Prefs.KEY_REDIRECT_BACK, false)
        if (RedirectRules.shouldBack(prev, newPkg, prevAge, now - lastBackAt, enabled)) {
            if (performGlobalAction(GLOBAL_ACTION_BACK)) {
                lastBackAt = now
                stats.record(prev, "redirect_back")
            }
        }
    }

    private fun scanAndSkip(pkg: String) {
        val root = rootInActiveWindow ?: return
        val infos = ArrayList<NodeInfo>()
        val refs = ArrayList<AccessibilityNodeInfo>()
        try {
            collect(root, 0, infos, refs)
            val dm = resources.displayMetrics
            val snap = ScreenSnapshot(pkg, dm.widthPixels, dm.heightPixels, infos)
            val now = System.currentTimeMillis()
            val decision = engine.decide(snap, now - launchStartAt, clicksThisLaunch, now - lastClickAt)
            if (decision.action == ActionType.CLICK && decision.nodeIndex in refs.indices) {
                if (clickNode(refs[decision.nodeIndex], infos[decision.nodeIndex])) {
                    clicksThisLaunch++
                    lastClickAt = now
                    stats.record(pkg, "skip")
                }
            }
        } finally {
            refs.forEach { recycleCompat(it) }
            recycleCompat(root)
        }
    }

    /** 深度优先收集节点；infos 与 refs 一一对应。 */
    private fun collect(
        node: AccessibilityNodeInfo?,
        depth: Int,
        out: MutableList<NodeInfo>,
        refs: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null || depth > MAX_DEPTH || out.size >= MAX_NODES) return
        val r = Rect()
        node.getBoundsInScreen(r)
        out += NodeInfo(
            text = node.text?.toString(),
            desc = node.contentDescription?.toString(),
            viewId = node.viewIdResourceName,
            clickable = node.isClickable,
            editable = node.className?.toString()?.contains("EditText") == true,
            visible = node.isVisibleToUser,
            width = r.width(),
            height = r.height(),
            centerX = r.centerX(),
            centerY = r.centerY()
        )
        refs += node
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), depth + 1, out, refs)
        }
    }

    /** 先尝试节点点击（向上找可点击祖先），失败再用手势点中心。 */
    private fun clickNode(node: AccessibilityNodeInfo, info: NodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < 4) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                if (cur !== node) recycleCompat(cur)
                return true
            }
            val parent = cur.parent
            if (cur !== node) recycleCompat(cur)
            cur = parent
            hops++
        }
        if (cur != null && cur !== node) recycleCompat(cur)
        return tapAt(info.centerX.toFloat(), info.centerY.toFloat())
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun isEnabled(): Boolean = prefs().getBoolean(Prefs.KEY_ENABLED, true)

    private fun prefs() = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

    @Suppress("DEPRECATION")
    private fun recycleCompat(n: AccessibilityNodeInfo) {
        try {
            n.recycle()
        } catch (_: Exception) {
            // 已回收或系统不支持，忽略
        }
    }

    companion object {
        const val MAX_NODES = 400
        const val MAX_DEPTH = 30
    }
}
