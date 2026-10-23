package com.qingping.app

import kotlin.math.max

/** 无障碍节点的纯数据快照，便于在 JVM 单元测试中验证规则。 */
data class NodeInfo(
    val text: String?,
    val desc: String?,
    val viewId: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val visible: Boolean,
    val width: Int,
    val height: Int,
    val centerX: Int,
    val centerY: Int
)

data class ScreenSnapshot(
    val pkg: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val nodes: List<NodeInfo>
)

enum class ActionType { NONE, CLICK }

data class Decision(val action: ActionType, val nodeIndex: Int = -1, val reason: String = "")

data class RuleConfig(
    /** 应用启动后多久内才允许介入（开屏广告通常出现在这个窗口内）。 */
    val launchWindowMs: Long = 6_000L,
    /** 两次点击之间的最短间隔。 */
    val cooldownMs: Long = 1_500L,
    /** 每次启动最多点击次数，防止误伤。 */
    val maxClicksPerLaunch: Int = 1,
    /** 候选按钮面积不能超过屏幕面积的该比例，避免把大块内容当作按钮。 */
    val maxAreaRatio: Double = 0.20,
    val blockedPackages: Set<String> = emptySet(),
    val blockedPackagePrefixes: List<String> = listOf(
        "com.android.systemui",
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.launcher",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.bbk.launcher"
    ),
    /** 整段文本必须完整匹配（去首尾空白）。刻意不包含“关闭”“知道了”等易误伤词。 */
    val skipTextRegex: Regex = DEFAULT_SKIP_REGEX,
    /** 页面出现这些文字时视为登录/注册页，直接放弃。 */
    val guardTexts: Set<String> = setOf("登录", "注册", "Log in", "Sign in", "Sign up")
) {
    companion object {
        val DEFAULT_SKIP_REGEX = Regex(
            "^(跳过|跳过广告|关闭广告|skip|skip ad|skip ads)( ?\\d+ ?s?)?$",
            RegexOption.IGNORE_CASE
        )
    }
}

class SkipEngine(private val cfg: RuleConfig = RuleConfig()) {

    fun decide(
        snap: ScreenSnapshot,
        msSinceLaunch: Long,
        clicksThisLaunch: Int,
        msSinceLastClick: Long
    ): Decision {
        if (isBlocked(snap.pkg)) return Decision(ActionType.NONE, reason = "blocked_pkg")
        if (msSinceLaunch < 0 || msSinceLaunch > cfg.launchWindowMs) {
            return Decision(ActionType.NONE, reason = "outside_window")
        }
        if (clicksThisLaunch >= cfg.maxClicksPerLaunch) return Decision(ActionType.NONE, reason = "click_limit")
        if (msSinceLastClick < cfg.cooldownMs) return Decision(ActionType.NONE, reason = "cooldown")
        if (snap.nodes.any { it.visible && it.editable }) return Decision(ActionType.NONE, reason = "input_page")
        if (snap.nodes.any { n ->
                n.visible && n.text?.trim()?.let { cfg.guardTexts.contains(it) } == true
            }
        ) return Decision(ActionType.NONE, reason = "guard_text")

        val screenArea = max(1, snap.screenWidth).toLong() * max(1, snap.screenHeight).toLong()
        var best = -1
        var bestArea = Long.MAX_VALUE
        snap.nodes.forEachIndexed { i, n ->
            if (!n.visible || n.width <= 0 || n.height <= 0) return@forEachIndexed
            val label = n.text?.trim()?.takeIf { it.isNotEmpty() } ?: n.desc?.trim().orEmpty()
            if (label.isEmpty() || label.length > 12) return@forEachIndexed
            if (!cfg.skipTextRegex.matches(label)) return@forEachIndexed
            val area = n.width.toLong() * n.height.toLong()
            if (area.toDouble() / screenArea > cfg.maxAreaRatio) return@forEachIndexed
            if (area < bestArea) {
                bestArea = area
                best = i
            }
        }
        return if (best >= 0) Decision(ActionType.CLICK, best, "skip_text")
        else Decision(ActionType.NONE, reason = "no_target")
    }

    private fun isBlocked(pkg: String): Boolean =
        pkg in cfg.blockedPackages || cfg.blockedPackagePrefixes.any { pkg.startsWith(it) }
}

/** 误触跳转（广告把页面带到浏览器或应用商店）的启发式判断。 */
object RedirectRules {
    private val JUMP_PREFIXES = listOf(
        "com.android.chrome", "com.android.browser", "com.UCMobile", "com.tencent.mtt",
        "com.heytap.browser", "com.huawei.browser", "com.miui.browser", "com.xiaomi.browser",
        "com.quark.browser", "com.baidu.browser",
        "com.android.vending", "com.xiaomi.market", "com.huawei.appmarket",
        "com.oppo.market", "com.bbk.appstore", "com.tencent.android.qqdownloader"
    )

    fun isJumpTarget(pkg: String): Boolean = JUMP_PREFIXES.any { pkg.startsWith(it) }

    /**
     * 当前 App 刚启动不久（3 秒内）就被带到浏览器/商店，且不是由用户主动打开时，返回 true。
     * 启发式：无法区分“用户刚点了链接”和“广告自动跳转”，默认关闭，由用户开启。
     */
    fun shouldBack(
        fromPkg: String,
        toPkg: String,
        msSinceFromLaunch: Long,
        msSinceLastBack: Long,
        enabled: Boolean
    ): Boolean {
        return enabled &&
            fromPkg.isNotEmpty() &&
            fromPkg != toPkg &&
            !isJumpTarget(fromPkg) &&
            isJumpTarget(toPkg) &&
            msSinceFromLaunch in 0..3_000L &&
            msSinceLastBack > 3_000L
    }
}
