package com.apk.claw.android.tool.impl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * shell_exec 的 find 动作谓词守卫断言。
 *
 * shell_exec 声明「只读查询」。find 是白名单里唯一带动作谓词的命令,-delete/-exec 等
 * 能删文件/执行外部命令,且 -delete 不含 shell 元字符、骗得过注入检测。
 * 本测试锁死这条守卫:危险谓词必拒、正常只读 find 必放行。
 */
class ShellExecFindGuardTest {

    @Test
    fun `find -delete is rejected`() {
        assertTrue(
            ShellExecTool.containsDangerousFindPredicate("find /sdcard/Download -name \"*.jpg\" -delete"),
        )
    }

    @Test
    fun `find -exec and variants are rejected`() {
        assertTrue(ShellExecTool.containsDangerousFindPredicate("find /sdcard -exec rm {} ;"))
        assertTrue(ShellExecTool.containsDangerousFindPredicate("find /sdcard -execdir sh {} ;"))
        assertTrue(ShellExecTool.containsDangerousFindPredicate("find /sdcard -ok rm {} ;"))
        assertTrue(ShellExecTool.containsDangerousFindPredicate("find /sdcard -okdir rm {} ;"))
    }

    @Test
    fun `find -fprintf -fls (write to file) are rejected`() {
        assertTrue(ShellExecTool.containsDangerousFindPredicate("find /sdcard -fprintf /sdcard/out.txt %p"))
        assertTrue(ShellExecTool.containsDangerousFindPredicate("find /sdcard -fls /sdcard/out.txt"))
    }

    @Test
    fun `leading whitespace does not evade the guard`() {
        assertTrue(ShellExecTool.containsDangerousFindPredicate("   find /sdcard -delete"))
    }

    @Test
    fun `read-only find is allowed`() {
        assertFalse(ShellExecTool.containsDangerousFindPredicate("find /sdcard/Download -name \"*.jpg\""))
        assertFalse(ShellExecTool.containsDangerousFindPredicate("find /sdcard -type d -maxdepth 2"))
        assertFalse(ShellExecTool.containsDangerousFindPredicate("find /sdcard -newer /sdcard/x -print"))
    }

    @Test
    fun `predicate substring in non-find command is not misflagged`() {
        // 只对 find 生效:grep 搜索字面量 "-delete" 不该被拦
        assertFalse(ShellExecTool.containsDangerousFindPredicate("grep -- -delete /sdcard/Download/notes.txt"))
        assertFalse(ShellExecTool.containsDangerousFindPredicate("cat /sdcard/Download/find-delete-notes.txt"))
    }

    @Test
    fun `predicate as substring of a token is not matched`() {
        // -deleted 不是 -delete;精确按词匹配,避免误伤
        assertFalse(ShellExecTool.containsDangerousFindPredicate("find /sdcard -name \"-deleted-files\""))
    }
}
