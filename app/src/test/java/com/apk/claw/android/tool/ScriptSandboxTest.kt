package com.apk.claw.android.tool

import com.apk.claw.android.tool.impl.ScriptSandbox
import org.junit.Assert.*
import org.junit.Test

/**
 * 验证 Rhino 沙箱核心行为（纯 JVM，无 Android 依赖）。
 * readFile/writeFile/callTool 不在 JVM 测试中验证（需要 Android 路径和工具注册）。
 */
class ScriptSandboxTest {

    private val sandbox = ScriptSandbox()

    @Test
    fun `basic computation and print`() {
        val result = sandbox.execute(
            "var x = 2 + 2; print(x);",
            timeoutMs = 5_000
        )
        assertTrue("Expected success, got: ${result.error}", result.isSuccess)
        assertEquals("4", result.data?.trim())
    }

    @Test
    fun `console log works`() {
        val result = sandbox.execute(
            """console.log("hello", "world");""",
            timeoutMs = 5_000
        )
        assertTrue(result.isSuccess)
        assertEquals("hello world", result.data?.trim())
    }

    @Test
    fun `json parse and stringify`() {
        val result = sandbox.execute(
            """
            var obj = JSON.parse('{"name":"octopus","score":99}');
            print(obj.name + " " + obj.score);
            """.trimIndent(),
            timeoutMs = 5_000
        )
        assertTrue(result.isSuccess)
        assertEquals("octopus 99", result.data?.trim())
    }

    @Test
    fun `array operations`() {
        val result = sandbox.execute(
            """
            var arr = [3,1,4,1,5,9,2,6];
            arr.sort(function(a,b){return a-b;});
            print(JSON.stringify(arr));
            """.trimIndent(),
            timeoutMs = 5_000
        )
        assertTrue(result.isSuccess)
        assertEquals("[1,1,2,3,4,5,6,9]", result.data?.trim())
    }

    @Test
    fun `es6 arrow functions and let const`() {
        val result = sandbox.execute(
            """
            const add = (a, b) => a + b;
            let sum = [1,2,3,4,5].reduce((acc, n) => acc + n, 0);
            print(add(sum, 10));
            """.trimIndent(),
            timeoutMs = 5_000
        )
        assertTrue(result.isSuccess)
        assertEquals("25", result.data?.trim())
    }

    @Test
    fun `template literals`() {
        val result = sandbox.execute(
            """
            const name = "Octopus";
            print(`Hello, ${"\${name}"}!`);
            """.trimIndent(),
            timeoutMs = 5_000
        )
        assertTrue(result.isSuccess)
        assertEquals("Hello, Octopus!", result.data?.trim())
    }

    @Test
    fun `multiline output`() {
        val result = sandbox.execute(
            """
            for (var i = 1; i <= 3; i++) {
                print("line " + i);
            }
            """.trimIndent(),
            timeoutMs = 5_000
        )
        assertTrue(result.isSuccess)
        val lines = result.data?.trim()?.lines() ?: emptyList()
        assertEquals(3, lines.size)
        assertEquals("line 1", lines[0])
        assertEquals("line 3", lines[2])
    }

    @Test
    fun `syntax error returns error result`() {
        val result = sandbox.execute(
            "var x = ((( broken syntax !!!",
            timeoutMs = 5_000
        )
        assertFalse(result.isSuccess)
        assertNotNull(result.error)
    }

    @Test
    fun `runtime error returns error result`() {
        val result = sandbox.execute(
            "undeclaredFn();",
            timeoutMs = 5_000
        )
        assertFalse(result.isSuccess)
        assertNotNull(result.error)
    }

    @Test
    fun `infinite loop triggers timeout`() {
        val start = System.currentTimeMillis()
        val result = sandbox.execute(
            "while(true){}",
            timeoutMs = 2_000
        )
        val elapsed = System.currentTimeMillis() - start
        assertFalse("Should not succeed on infinite loop", result.isSuccess)
        assertTrue("Should have timed out, but elapsed=${elapsed}ms", elapsed < 10_000)
        val err = result.error ?: ""
        assertTrue("Error should mention timeout/超时: $err",
            err.contains("timeout", ignoreCase = true) || err.contains("超时"))
    }

    @Test
    fun `no output returns placeholder`() {
        val result = sandbox.execute(
            "var x = 1 + 1;",
            timeoutMs = 5_000
        )
        assertTrue(result.isSuccess)
        assertTrue("Expected placeholder, got: ${result.data}", result.data?.contains("无输出") == true)
    }

    @Test
    fun `WORKSPACE global is injected`() {
        val result = sandbox.execute(
            "print(typeof WORKSPACE + ':' + WORKSPACE);",
            timeoutMs = 5_000
        )
        assertTrue(result.isSuccess)
        val out = result.data?.trim() ?: ""
        assertTrue("WORKSPACE should be a string: $out", out.startsWith("string:"))
        assertTrue("WORKSPACE should be a path: $out", out.contains("/"))
    }

    @Test
    fun `data processing pipeline`() {
        val result = sandbox.execute("""
            var sales = [
              {name:"Alice", amount:5200},
              {name:"Bob",   amount:3800},
              {name:"Carol", amount:6100},
              {name:"Dave",  amount:4500}
            ];
            var total = sales.reduce(function(s,r){ return s + r.amount; }, 0);
            var avg   = (total / sales.length).toFixed(0);
            sales.sort(function(a,b){ return b.amount - a.amount; });
            print("总销售额: " + total);
            print("平均: " + avg);
            print("排行榜:");
            sales.forEach(function(r,i){ print((i+1)+". "+r.name+" - "+r.amount); });
        """.trimIndent(), timeoutMs = 5_000)
        assertTrue(result.isSuccess)
        assertTrue(result.data?.contains("总销售额: 19600") == true)
        assertTrue(result.data?.contains("Carol") == true)
    }

    @Test
    fun `fetch-like simulation with JSON`() {
        // 模拟 API 响应处理（不实际发请求，测试 JSON 解析链路）
        val result = sandbox.execute("""
            var mockResponse = '{"code":0,"data":{"users":[{"id":1,"name":"张三"},{"id":2,"name":"李四"}]}}';
            var resp = JSON.parse(mockResponse);
            if (resp.code !== 0) { print("error: " + resp.code); }
            else {
              var users = resp.data.users;
              print("共 " + users.length + " 个用户");
              users.forEach(function(u){ print(u.id + ": " + u.name); });
            }
        """.trimIndent(), timeoutMs = 5_000)
        assertTrue(result.isSuccess)
        assertTrue(result.data?.contains("共 2 个用户") == true)
        assertTrue(result.data?.contains("张三") == true)
    }

    @Test
    fun `es6 map and filter chaining`() {
        val result = sandbox.execute("""
            const nums = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
            const evensDoubled = nums.filter(n => n % 2 === 0).map(n => n * 2);
            print(JSON.stringify(evensDoubled));
            print("sum=" + evensDoubled.reduce((a,b) => a+b, 0));
        """.trimIndent(), timeoutMs = 5_000)
        assertTrue(result.isSuccess)
        assertTrue(result.data?.contains("[4,8,12,16,20]") == true)
        assertTrue(result.data?.contains("sum=60") == true)
    }
}
