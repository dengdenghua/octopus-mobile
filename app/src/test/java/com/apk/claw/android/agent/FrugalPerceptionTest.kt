package com.apk.claw.android.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FrugalPerception 测试 —— 纯 JVM,验证树富度阈值判定。
 * 富树→可用文字替代截图;稀疏树/空/null→回退截图。
 */
class FrugalPerceptionTest {

    @Test
    fun `null or blank tree is not rich enough`() {
        assertFalse(FrugalPerception.isTreeRichEnough(null))
        assertFalse(FrugalPerception.isTreeRichEnough(""))
        assertFalse(FrugalPerception.isTreeRichEnough("   \n  "))
    }

    @Test
    fun `sparse tree below threshold falls back to screenshot`() {
        // 游戏/Canvas 界面:无障碍树几乎为空
        val sparse = "FrameLayout\n  View"
        assertFalse(FrugalPerception.isTreeRichEnough(sparse))
    }

    @Test
    fun `rich tree at or above threshold is sufficient`() {
        val rich = buildString {
            repeat(20) { i ->
                appendLine("Button[$i] text=\"操作项 $i\" clickable=true bounds=[0,$i,100,${i + 40}]")
            }
        }
        assertTrue(rich.length >= FrugalPerception.MIN_TREE_CHARS)
        assertTrue(FrugalPerception.isTreeRichEnough(rich))
    }

    @Test
    fun `threshold boundary`() {
        val justUnder = "x".repeat(FrugalPerception.MIN_TREE_CHARS - 1)
        val exact = "x".repeat(FrugalPerception.MIN_TREE_CHARS)
        assertFalse(FrugalPerception.isTreeRichEnough(justUnder))
        assertTrue(FrugalPerception.isTreeRichEnough(exact))
    }
}
