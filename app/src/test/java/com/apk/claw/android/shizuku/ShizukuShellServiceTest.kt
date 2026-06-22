package com.apk.claw.android.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShizukuShellServiceTest {

    @Test
    fun `sanitizeShellArg escapes single quotes and wraps with single quotes`() {
        assertEquals("'hello'", ShizukuShellService.sanitizeShellArg("hello"))
        assertEquals("'it'\\''s'", ShizukuShellService.sanitizeShellArg("it's"))
    }

    @Test
    fun `isValidPackageName accepts normal package names`() {
        assertTrue(ShizukuShellService.isValidPackageName("com.example.app"))
        assertTrue(ShizukuShellService.isValidPackageName("a.b"))
    }

    @Test
    fun `isValidPackageName rejects invalid package names`() {
        assertFalse(ShizukuShellService.isValidPackageName("com")) // single segment
        assertFalse(ShizukuShellService.isValidPackageName("com..example"))
        assertFalse(ShizukuShellService.isValidPackageName("com/example/app"))
        assertFalse(ShizukuShellService.isValidPackageName("com.example.app;rm -rf"))
    }

    @Test
    fun `isValidPath accepts safe sdcard paths`() {
        assertTrue(ShizukuShellService.isValidPath("/sdcard"))
        assertTrue(ShizukuShellService.isValidPath("/sdcard/Download"))
        assertTrue(ShizukuShellService.isValidPath("/sdcard/Download/file.txt"))
        assertTrue(ShizukuShellService.isValidPath("/storage/emulated/0/Download"))
        assertTrue(ShizukuShellService.isValidPath("/data/local/tmp/shell_work"))
    }

    @Test
    fun `isValidPath rejects path traversal and unsafe chars`() {
        assertFalse(ShizukuShellService.isValidPath("/sdcard/../data"))
        assertFalse(ShizukuShellService.isValidPath("/data/data/com.example"))
        assertFalse(ShizukuShellService.isValidPath("/system/etc/hosts"))
        assertFalse(ShizukuShellService.isValidPath("/sdcard/Download;rm -rf"))
        assertFalse(ShizukuShellService.isValidPath("/sdcard/Download/$(id)"))
    }

    @Test
    fun `isCommandAllowed allows whitelisted prefixes`() {
        assertTrue(invokeIsCommandAllowed("input tap 100 200"))
        assertTrue(invokeIsCommandAllowed("find /sdcard -name '*.jpg'"))
        assertTrue(invokeIsCommandAllowed("cp '/sdcard/a' '/sdcard/b'"))
        assertTrue(invokeIsCommandAllowed("cat /sdcard/Download/file.txt"))
    }

    @Test
    fun `isCommandAllowed rejects non-whitelisted commands`() {
        assertFalse(invokeIsCommandAllowed("reboot"))
        assertFalse(invokeIsCommandAllowed("/system/bin/su"))
        assertFalse(invokeIsCommandAllowed("cat /data/data/com.example/files/secrets"))
    }

    @Test
    fun `hasInjectionPattern detects common injection patterns`() {
        assertTrue(invokeHasInjectionPattern("input tap 100 200; rm -rf /"))
        assertTrue(invokeHasInjectionPattern("input tap 100 200 && rm -rf /"))
        assertTrue(invokeHasInjectionPattern("input tap 100 200 || rm -rf /"))
        assertTrue(invokeHasInjectionPattern("input tap `id`"))
        assertTrue(invokeHasInjectionPattern("input tap \$(id)"))
    }

    @Test
    fun `hasInjectionPattern does not flag quoted metacharacters`() {
        assertFalse(invokeHasInjectionPattern("echo 'a;b'"))
        assertFalse(invokeHasInjectionPattern("cp 'file&&name' /sdcard/"))
    }

    private fun invokeIsCommandAllowed(command: String): Boolean {
        val method = ShizukuShellService::class.java.getDeclaredMethod("isCommandAllowed", String::class.java)
        method.isAccessible = true
        return method.invoke(ShizukuShellService, command) as Boolean
    }

    private fun invokeHasInjectionPattern(command: String): Boolean {
        val method = ShizukuShellService::class.java.getDeclaredMethod("hasInjectionPattern", String::class.java)
        method.isAccessible = true
        return method.invoke(ShizukuShellService, command) as Boolean
    }
}
