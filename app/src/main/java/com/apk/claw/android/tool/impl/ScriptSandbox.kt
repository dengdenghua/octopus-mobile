package com.apk.claw.android.tool.impl

import com.apk.claw.android.octopus_mobile.safety.SsrfSafeHttp
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.KVUtils
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.*
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Rhino JS 沙箱 —— 在 JVM 内执行 Agent 生成的脚本，无需 Shizuku。
 *
 * 宿主 API（脚本可调用）:
 *  - print(…) / console.log(…)   — 输出捕获，即工具返回值
 *  - readFile(path)               — 读文件，限 Download/Documents 目录
 *  - writeFile(path, content)     — 写文件，同上
 *  - fetch(url, options?)         — 同步 HTTP 请求(过 UrlGuard 防 SSRF)，返回 {status, ok, body}
 *  - callTool(name, params?)      — 调用已注册 Tool，返回 data 字符串或抛 JS 错误
 *
 * 安全模型:
 *  - 文件访问限 SAFE_PATH_PREFIXES（Download/Documents）
 *  - callTool 走 ToolRegistry 正常策略（高危工具仍受其自身守门逻辑约束）
 *  - 超时通过 Rhino 指令计数器强制中断，防无限循环
 *  - optimizationLevel=-1：纯解释器，不生成 JVM 字节码（Android ART 兼容）
 */
class ScriptSandbox {

    companion object {
        private const val MAX_OUTPUT_CHARS = 65_536
        // 禁用自动重定向:由 SsrfSafeHttp 逐跳 UrlGuard 校验后手动跟随,防 302→内网/元数据 SSRF。
        // dns(SsrfSafeDns):在连接期对实际解析结果再校验,消除 DNS rebinding 窗口。
        // callTimeout 为整次调用(含所有重定向跳)的硬上限:指令观察器超时无法中断阻塞的 host
        // 调用(fetch),这里给 fetch 一个绝对天花板,避免慢速滴流响应把脚本挂死超过 timeoutMs。
        private val HTTP = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .dns(com.apk.claw.android.octopus_mobile.safety.SsrfSafeDns)
            .build()

        private val BASE_SAFE_PREFIXES = listOf(
            "/sdcard/Download/",
            "/sdcard/Documents/",
            "/storage/emulated/0/Download/",
            "/storage/emulated/0/Documents/",
        )

        /**
         * 危险的工作空间根:即便被写入 KEY_SCRIPT_WORKSPACE 也不接受为安全前缀。
         * 防御纵深:配合 DualConfigWriter 的 config-sync 黑名单,双保险防止把沙箱白名单
         * 放大到 "/"、/data、/sdcard 根等,导致越权读写 app 私有目录。
         * (比较对象为 canonicalPath,/sdcard 会被解析为 /storage/emulated/0。)
         */
        private val FORBIDDEN_WORKSPACE_ROOTS = setOf(
            "/", "/data", "/data/data", "/data/local", "/data/local/tmp",
            "/system", "/sdcard", "/storage", "/storage/emulated", "/storage/emulated/0",
        )

        /** 返回经规范化 + 危险根拦截的工作空间前缀(带尾分隔符),不合法则 null。 */
        private fun sanitizedWorkspacePrefix(): String? {
            val ws = KVUtils.getScriptWorkspace()
            if (ws.isBlank()) return null
            val canon = try { File(ws).canonicalPath } catch (_: Exception) { return null }
            if (canon in FORBIDDEN_WORKSPACE_ROOTS) return null
            return if (canon.endsWith("/")) canon else "$canon/"
        }

        private fun safePrefixes(): List<String> {
            val ws = sanitizedWorkspacePrefix()
            return if (ws != null) BASE_SAFE_PREFIXES + ws else BASE_SAFE_PREFIXES
        }

        fun isSafePath(path: String): Boolean {
            val normalized = try { File(path).canonicalPath } catch (_: Exception) { return false }
            return safePrefixes().any { prefix ->
                // 前缀恒带尾分隔符 → startsWith 具备路径边界,避免 /a/Download 命中 /a/Download_evil
                val p = if (prefix.endsWith("/")) prefix else "$prefix/"
                normalized == p.trimEnd('/') || normalized.startsWith(p)
            }
        }
    }

    /** 每次执行创建一个带截止时间的 ContextFactory，避免全局污染。 */
    private class TimedContextFactory(private val deadlineMs: Long) : ContextFactory() {
        override fun makeContext(): Context = super.makeContext().also { cx ->
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            cx.instructionObserverThreshold = 5_000
            // 沙箱隔离核心:拒绝脚本访问任何 Java 类。
            // Rhino 默认允许 java.lang.Runtime.exec / Class.forName 等反射逃逸,
            // 装 ClassShutter 后所有 Java 类访问(含 LiveConnect)都被拒,
            // 脚本只能用我们显式注入的宿主 API(print/readFile/writeFile/fetch/callTool)。
            // 注:用 setClassShutter() 方法调用而非属性赋值——Context 内部同名私有字段会让
            // Kotlin 的属性语法糖误解析到那个私有字段上,编译不过。
            cx.setClassShutter(ClassShutter { _ -> false })
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            if (System.currentTimeMillis() > deadlineMs) {
                throw EvaluatorException("Script timed out")
            }
        }
    }

    fun execute(code: String, timeoutMs: Long): ToolResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        val factory = TimedContextFactory(deadline)
        val output = StringBuilder()

        val cx = factory.enterContext()
        return try {
            val scope = cx.initStandardObjects()

            // --- console.log / print ---
            val printImpl: (Array<Any>) -> Any = { args ->
                val line = args.joinToString(" ") { jsValueToString(it) }
                if (output.length + line.length + 1 > MAX_OUTPUT_CHARS)
                    throw EvaluatorException("Output exceeded $MAX_OUTPUT_CHARS chars")
                output.appendLine(line)
                Undefined.instance
            }
            val printFn = jsFunc(scope, printImpl)
            ScriptableObject.putProperty(scope, "print", printFn)

            val console = cx.newObject(scope)
            ScriptableObject.putProperty(console, "log", printFn)
            ScriptableObject.putProperty(console, "warn", printFn)
            ScriptableObject.putProperty(console, "error", printFn)
            ScriptableObject.putProperty(scope, "console", console)

            // --- WORKSPACE global constant ---
            val workspace = KVUtils.getScriptWorkspace()
            File(workspace).mkdirs()  // 确保目录存在
            ScriptableObject.putProperty(scope, "WORKSPACE", workspace)

            // --- readFile(path) → string ---
            ScriptableObject.putProperty(scope, "readFile", jsFunc(scope) { args ->
                val path = args.getOrNull(0)?.let { Context.toString(it) }
                    ?: throw EvaluatorException("readFile: path required")
                if (!isSafePath(path))
                    throw EvaluatorException("readFile: '$path' not in allowed directory (Download/Documents)")
                try { File(path).readText() }
                catch (e: Exception) { throw EvaluatorException("readFile: ${e.message}") }
            })

            // --- writeFile(path, content) → "ok" ---
            ScriptableObject.putProperty(scope, "writeFile", jsFunc(scope) { args ->
                val path = args.getOrNull(0)?.let { Context.toString(it) }
                    ?: throw EvaluatorException("writeFile: path required")
                val content = args.getOrNull(1)?.let { Context.toString(it) } ?: ""
                if (!isSafePath(path))
                    throw EvaluatorException("writeFile: '$path' not in allowed directory (Download/Documents)")
                try {
                    val f = File(path)
                    f.parentFile?.mkdirs()
                    f.writeText(content)
                    "ok"
                } catch (e: Exception) { throw EvaluatorException("writeFile: ${e.message}") }
            })

            // --- fetch(url, options?) → {status, ok, body} ---
            ScriptableObject.putProperty(scope, "fetch", jsFunc(scope) { args ->
                val url = args.getOrNull(0)?.let { Context.toString(it) }
                    ?: throw EvaluatorException("fetch: url required")
                val opts = args.getOrNull(1) as? Scriptable
                val method = opts?.let { prop(it, "method") as? String }?.uppercase() ?: "GET"
                val bodyStr = opts?.let { prop(it, "body") as? String }
                val headersObj = opts?.let { prop(it, "headers") as? Scriptable }

                val reqBuilder = Request.Builder().url(url)
                if (headersObj != null) {
                    for (id in headersObj.ids) {
                        val k = id.toString()
                        val v = prop(headersObj, k)?.let { Context.toString(it) } ?: continue
                        reqBuilder.addHeader(k, v)
                    }
                }
                when (method) {
                    "POST", "PUT", "PATCH" ->
                        reqBuilder.method(method, (bodyStr ?: "").toRequestBody("application/json".toMediaType()))
                    "DELETE" -> reqBuilder.delete()
                    else -> reqBuilder.get()
                }

                // 安全(SSRF):走 SsrfSafeHttp,对初始 URL 与每一跳重定向都过 UrlGuard,
                // 拒绝 http(s) 以外协议、内网/回环/link-local/云元数据目标。沙箱脚本是
                // Agent/远端生成的不可信代码,不能让它 fetch 到 127.0.0.1/169.254.169.254/内网。
                val resp = try { SsrfSafeHttp.execute(HTTP, reqBuilder.build()) }
                catch (e: SecurityException) { throw EvaluatorException("fetch blocked: ${e.message}") }
                catch (e: Exception) { throw EvaluatorException("fetch: ${e.message}") }

                val result = cx.newObject(scope)
                ScriptableObject.putProperty(result, "status", resp.code.toDouble())
                ScriptableObject.putProperty(result, "ok", resp.isSuccessful)
                ScriptableObject.putProperty(result, "body", resp.body?.string() ?: "")
                result
            })

            // --- callTool(name, params?) → data string or throws ---
            ScriptableObject.putProperty(scope, "callTool", jsFunc(scope) { args ->
                val name = args.getOrNull(0)?.let { Context.toString(it) }
                    ?: throw EvaluatorException("callTool: tool name required")
                val paramsJs = args.getOrNull(1)
                val params = jsToParams(paramsJs)

                // JS 沙箱代码视为不可信来源:即使 run_code 本身已被来源闸门放行,
                // 沙箱内调高危工具(send_sms / file_ops / browser_evaluate 等)仍要
                // 走来源闸门,防止「批准一次 run_code = 解锁全部高危工具」的 launderer。
                val result = ToolRegistry.withUntrustedSource {
                    ToolRegistry.getInstance().executeTool(name, params, null)
                }
                if (result.isSuccess) {
                    result.data ?: "ok"
                } else {
                    throw EvaluatorException("callTool '$name' failed: ${result.error}")
                }
            })

            cx.evaluateString(scope, code, "<script>", 1, null)

            val out = output.toString().trimEnd()
            ToolResult.success(if (out.isBlank()) "(执行成功，无输出)" else out)

        } catch (e: EvaluatorException) {
            if (e.message?.contains("timed out") == true)
                ToolResult.error("执行超时（>${timeoutMs}ms）")
            else
                ToolResult.error("脚本错误: ${e.message}")
        } catch (e: RhinoException) {
            ToolResult.error("JS错误 [行${e.lineNumber()}]: ${e.details()}")
        } catch (e: Exception) {
            ToolResult.error("执行异常: ${e.message}")
        } finally {
            Context.exit()
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun jsFunc(scope: Scriptable, block: (Array<Any>) -> Any?): BaseFunction =
        object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>): Any =
                block(args) ?: Undefined.instance
            override fun getFunctionName() = ""
            override fun getArity() = 0
        }

    private fun prop(obj: Scriptable, key: String): Any? {
        val v = ScriptableObject.getProperty(obj, key)
        return if (v == Scriptable.NOT_FOUND || v is Undefined) null else v
    }

    private fun jsValueToString(v: Any?): String = when (v) {
        null, is Undefined -> "undefined"
        is NativeArray -> {
            val sb = StringBuilder("[")
            for (i in 0 until v.length.toInt()) {
                if (i > 0) sb.append(", ")
                sb.append(jsValueToString(v.get(i, v)))
            }
            sb.append("]").toString()
        }
        is Scriptable -> {
            // JSON.stringify-like for plain objects
            val sb = StringBuilder("{")
            var first = true
            for (id in v.ids) {
                if (!first) sb.append(", ")
                first = false
                val key = id.toString()
                sb.append("\"$key\": ${jsValueToString(prop(v, key))}")
            }
            sb.append("}").toString()
        }
        is Double -> if (v == kotlin.math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun jsToParams(value: Any?): Map<String, Any> {
        if (value == null || value is Undefined) return emptyMap()
        if (value is Scriptable) {
            val map = mutableMapOf<String, Any>()
            for (id in value.ids) {
                val k = id.toString()
                val v = prop(value, k) ?: continue
                map[k] = jsToJava(v)
            }
            return map
        }
        if (value is String) {
            return try {
                com.google.gson.Gson().fromJson(value, Map::class.java) as Map<String, Any>
            } catch (_: Exception) { emptyMap() }
        }
        return emptyMap()
    }

    private fun jsToJava(v: Any?): Any = when (v) {
        null, is Undefined -> ""
        is Boolean -> v
        is Double -> if (v == kotlin.math.floor(v) && !v.isInfinite()) v.toInt() else v
        is String -> v
        is NativeArray -> (0 until v.length.toInt()).map { jsToJava(v.get(it, v)) }
        is Scriptable -> {
            val map = mutableMapOf<String, Any>()
            for (id in v.ids) {
                val k = id.toString()
                val item = prop(v, k) ?: continue
                map[k] = jsToJava(item)
            }
            map
        }
        else -> v.toString()
    }
}
