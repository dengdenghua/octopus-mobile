package com.apk.claw.android.octopus_mobile.safety

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * PathGuard 测试 —— 敏感文件保护 + 沙箱逃逸.
 */
class PathGuardTest {

    @Test
    fun `allows safe path`() {
        assertTrue(PathGuard.isSafePath("/data/data/com.app/files/config.json"))
    }

    @Test
    fun `blocks etc passwd`() {
        assertFalse(PathGuard.isSafePath("/etc/passwd"))
    }

    @Test
    fun `blocks etc shadow`() {
        assertFalse(PathGuard.isSafePath("/etc/shadow"))
    }

    @Test
    fun `blocks ssh keys`() {
        val home = System.getProperty("user.home") ?: "/home/test"
        assertFalse(PathGuard.isSafePath("$home/.ssh/id_rsa"))
        assertFalse(PathGuard.isSafePath("$home/.ssh/authorized_keys"))
    }

    @Test
    fun `blocks aws credentials`() {
        val home = System.getProperty("user.home") ?: "/home/test"
        assertFalse(PathGuard.isSafePath("$home/.aws/credentials"))
    }

    @Test
    fun `blocks docker config`() {
        val home = System.getProperty("user.home") ?: "/home/test"
        assertFalse(PathGuard.isSafePath("$home/.docker/config.json"))
    }

    @Test
    fun `blocks gpg keys`() {
        val home = System.getProperty("user.home") ?: "/home/test"
        assertFalse(PathGuard.isSafePath("$home/.gnupg/private-keys-v1.d/key.gpg"))
    }

    @Test
    fun `sandbox escape blocked`() {
        val sandbox = "/data/data/com.app/files"
        assertFalse(PathGuard.isSafePath("../../etc/passwd", sandboxDir = sandbox))
    }

    @Test
    fun `sandbox allows inside`() {
        val sandbox = "/data/data/com.app/files"
        assertTrue(PathGuard.isSafePath("subfolder/config.json", sandboxDir = sandbox))
    }

    @Test
    fun `mustExist checks existence`() {
        val verdict = PathGuard.check("/nonexistent/file.txt", mustExist = true)
        assertFalse(verdict.allow)
        assertEquals("not_found", verdict.reason)
    }

    @Test
    fun `empty path blocked`() {
        assertFalse(PathGuard.isSafePath(""))
    }

    @Test
    fun `resolved path returned`() {
        val verdict = PathGuard.check("/data/data/com.app/files/config.json")
        assertNotNull(verdict.resolved)
    }

    @Test
    fun `allowSensitive bypasses sensitive check`() {
        assertTrue(PathGuard.isSafePath("/etc/passwd", allowSensitive = true))
    }
}
