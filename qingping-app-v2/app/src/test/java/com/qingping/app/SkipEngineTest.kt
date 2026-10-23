package com.qingping.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SkipEngineTest {

    private val screenW = 1080
    private val screenH = 2400
    private val engine = SkipEngine(RuleConfig(blockedPackages = setOf("com.qingping.app")))

    private fun node(
        text: String?,
        x: Int = 900,
        y: Int = 100,
        w: Int = 120,
        h: Int = 80,
        clickable: Boolean = true,
        editable: Boolean = false
    ) = NodeInfo(
        text = text, desc = null, viewId = null, clickable = clickable,
        editable = editable, visible = true, width = w, height = h,
        centerX = x + w / 2, centerY = y + h / 2
    )

    private fun snap(vararg nodes: NodeInfo, pkg: String = "com.example.ad") =
        ScreenSnapshot(pkg, screenW, screenH, nodes.toList())

    private fun decide(s: ScreenSnapshot, launch: Long = 1_000, clicks: Int = 0, sinceClick: Long = 999_999) =
        engine.decide(s, launch, clicks, sinceClick)

    @Test
    fun clicksPlainSkipWithinWindow() {
        val d = decide(snap(node("跳过")))
        assertEquals(ActionType.CLICK, d.action)
        assertEquals(0, d.nodeIndex)
    }

    @Test
    fun matchesCountdownSkip() {
        assertEquals(ActionType.CLICK, decide(snap(node("跳过 3s"))).action)
        assertEquals(ActionType.CLICK, decide(snap(node("Skip 5"))).action)
    }

    @Test
    fun ignoresCloseButtonWhichIsTooRisky() {
        assertEquals(ActionType.NONE, decide(snap(node("关闭"))).action)
        assertEquals(ActionType.NONE, decide(snap(node("知道了"))).action)
    }

    @Test
    fun ignoresOutsideLaunchWindow() {
        assertEquals("outside_window", decide(snap(node("跳过")), launch = 7_000).reason)
    }

    @Test
    fun respectsPerLaunchClickLimit() {
        assertEquals("click_limit", decide(snap(node("跳过")), clicks = 1).reason)
    }

    @Test
    fun respectsCooldown() {
        assertEquals("cooldown", decide(snap(node("跳过")), sinceClick = 100).reason)
    }

    @Test
    fun skipsInputPages() {
        val s = snap(node("跳过"), node("", editable = true, clickable = true, w = 500, h = 100))
        assertEquals("input_page", decide(s).reason)
    }

    @Test
    fun skipsLoginPages() {
        val s = snap(node("跳过"), node("登录"))
        assertEquals("guard_text", decide(s).reason)
    }

    @Test
    fun ignoresSystemAndOwnPackages() {
        assertEquals("blocked_pkg", decide(snap(node("跳过"), pkg = "com.android.systemui")).reason)
        assertEquals("blocked_pkg", decide(snap(node("跳过"), pkg = "com.qingping.app")).reason)
    }

    @Test
    fun ignoresLargeAreas() {
        assertEquals("no_target", decide(snap(node("跳过", w = 1000, h = 1000))).reason)
    }

    @Test
    fun redirectRuleOnlyFiresForRecentNonUserJumps() {
        assertEquals(true, RedirectRules.shouldBack("com.example.ad", "com.android.chrome", 1_000, 99_999, true))
        assertEquals(false, RedirectRules.shouldBack("com.example.ad", "com.android.chrome", 1_000, 99_999, false))
        assertEquals(false, RedirectRules.shouldBack("com.android.chrome", "com.example.ad", 1_000, 99_999, true))
        assertEquals(false, RedirectRules.shouldBack("com.example.ad", "com.android.chrome", 10_000, 99_999, true))
    }
}
