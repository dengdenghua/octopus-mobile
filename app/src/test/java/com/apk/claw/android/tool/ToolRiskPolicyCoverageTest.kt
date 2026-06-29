package com.apk.claw.android.tool

import com.apk.claw.android.TestClawApplication
import com.apk.claw.android.octopus_mobile.browser.BrowserEngine
import com.apk.claw.android.octopus_mobile.browser.EngineEvent
import com.apk.claw.android.octopus_mobile.browser.EngineInfo
import com.apk.claw.android.octopus_mobile.safety.ToolRiskPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 工具风险分类「漂移守护」测试。
 *
 * 背景：[ToolRiskPolicy.riskOf] 对任何未列入 HIGH/MEDIUM 的工具静默返回 LOW，而 LOW 工具
 * 既不进审计（[ToolRiskPolicy.shouldAudit] 为 false）也不过高危来源闸门。于是新增一个工具
 * 却忘了归类，它会「悄悄地变成低风险」——这是「因遗漏而不安全」的典型漂移。
 *
 * 本测试把三份硬编码名单与**实际注册的工具集**对齐，任何漂移都会让 CI 失败：
 *  1. 每个已注册工具必须显式出现在 HIGH ∪ MEDIUM ∪ LOW（强制新工具被有意识地分类）。
 *  2. 名单里不得有未注册的死条目（typo / 已删工具），[ToolRiskPolicy.INTENTIONAL_UNREGISTERED] 除外。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestClawApplication::class)
class ToolRiskPolicyCoverageTest {

    /** 测试用浏览器引擎桩，仅为让 browser_* 工具能注册。 */
    private class StubBrowserEngine : BrowserEngine {
        override val name = "Stub"
        override val antiBotScore = 50
        override val supportsExtensions = false
        override val supportsEval = true
        override fun createView(context: android.content.Context): android.view.View = throw NotImplementedError()
        override fun isAvailable(): Boolean = true
        override fun describe(): EngineInfo = EngineInfo(name, "1.0", "Stub", false, 50)
        override fun events(): Flow<EngineEvent> = emptyFlow()
        override fun navigate(url: String) {}
        override fun currentUrl(): String = ""
        override fun evaluateJs(script: String, callback: ((String?) -> Unit)?) { callback?.invoke(null) }
        override fun screenshot(): String? = null
    }

    /** TV + MOBILE 两种设备类型 + 浏览器引擎下，所有可注册工具名的并集。 */
    private fun allRegisteredToolNames(): Set<String> {
        val names = sortedSetOf<String>()
        for (type in listOf(ToolRegistry.DeviceType.TV, ToolRegistry.DeviceType.MOBILE)) {
            ToolRegistry.registerAllTools(type)
            ToolRegistry.setBrowserEngine(StubBrowserEngine())
            names += ToolRegistry.getAllTools().map { it.getName() }
        }
        ToolRegistry.clearBrowserEngine()
        return names
    }

    private val classified: Set<String>
        get() = ToolRiskPolicy.HIGH_RISK_TOOLS +
            ToolRiskPolicy.MEDIUM_RISK_TOOLS +
            ToolRiskPolicy.KNOWN_LOW_RISK_TOOLS

    @Test
    fun `every registered tool has an explicit risk classification`() {
        val unclassified = (allRegisteredToolNames() - classified).sorted()
        assertTrue(
            "以下已注册工具未在 ToolRiskPolicy 中显式分类，会静默默认 LOW（绕过审计与高危来源闸门）。" +
                "请将其加入 HIGH/MEDIUM_RISK_TOOLS，或经评审后加入 KNOWN_LOW_RISK_TOOLS：$unclassified",
            unclassified.isEmpty()
        )
    }

    @Test
    fun `risk lists contain no stale unregistered entries`() {
        val registered = allRegisteredToolNames()
        val stale = (classified - registered - ToolRiskPolicy.INTENTIONAL_UNREGISTERED).sorted()
        assertTrue(
            "以下工具名出现在风险名单中但并无对应的已注册工具（typo 或已删工具）。" +
                "请修正名称或删除死条目（确为有意前向兼容则加入 INTENTIONAL_UNREGISTERED）：$stale",
            stale.isEmpty()
        )
    }
}
