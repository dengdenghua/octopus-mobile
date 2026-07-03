package com.apk.claw.android.tool.impl

import com.apk.claw.android.agent.CancellationToken
import com.apk.claw.android.octopus_mobile.safety.SsrfSafeHttp
import com.apk.claw.android.tool.ToolErr
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
import java.util.concurrent.locks.LockSupport

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

    /** 事件循环里的一个定时任务。[intervalMs] >= 0 表示 setInterval,需重复。 */
    private class Timer(
        val id: Long,
        var dueAt: Long,
        val intervalMs: Long,
        val fn: org.mozilla.javascript.Function,
        val args: Array<Any>,
    )

    fun execute(
        code: String,
        timeoutMs: Long,
        cancellationToken: CancellationToken? = null,
    ): ToolResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        val factory = TimedContextFactory(deadline)
        val output = StringBuilder()

        // 事件循环状态:setTimeout/setInterval 注册到 timers,clearTimeout/clearInterval 记到 clearedTimers。
        val timers = mutableListOf<Timer>()
        val clearedTimers = mutableSetOf<Long>()
        var nextTimerId = 1L

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

            // --- setTimeout / setInterval / clearTimeout / clearInterval ---
            // Rhino 1.7.15 有原生 Promise 但没有事件循环:setTimeout 未定义、定时/轮询代码直接
            // ReferenceError。这里补一个单线程事件循环(见 execute() 尾部 drainEventLoop):
            // 宿主收集定时任务,主脚本跑完后按到期时间依次执行,每次回调后 processMicrotasks()
            // 把 Promise 的 .then 也带动起来。注:async/await 语法 Rhino 1.7.15 解析不了(引擎限制),
            // 但 Promise + .then + setTimeout 这套足够覆盖绝大多数异步代码。
            val registerTimer: (Array<Any>, Boolean) -> Any = { args, repeating ->
                val fn = args.getOrNull(0) as? org.mozilla.javascript.Function
                    ?: throw EvaluatorException("setTimeout/setInterval: first argument must be a function")
                val delay = args.getOrNull(1)?.let { Context.toNumber(it).toLong() }?.coerceAtLeast(0L) ?: 0L
                val extra = if (args.size > 2) args.copyOfRange(2, args.size) else emptyArray()
                val id = nextTimerId++
                timers.add(Timer(id, System.currentTimeMillis() + delay, if (repeating) delay else -1L, fn, extra))
                id.toDouble()
            }
            ScriptableObject.putProperty(scope, "setTimeout", jsFunc(scope) { registerTimer(it, false) })
            ScriptableObject.putProperty(scope, "setInterval", jsFunc(scope) { registerTimer(it, true) })
            val clearTimer: (Array<Any>) -> Any = { args ->
                args.getOrNull(0)?.let { clearedTimers.add(Context.toNumber(it).toLong()) }
                Undefined.instance
            }
            ScriptableObject.putProperty(scope, "clearTimeout", jsFunc(scope, clearTimer))
            ScriptableObject.putProperty(scope, "clearInterval", jsFunc(scope, clearTimer))
            // queueMicrotask(fn):把回调塞进 Promise 微任务队列,drainEventLoop 会带动。
            ScriptableObject.putProperty(scope, "queueMicrotask", jsFunc(scope) { args ->
                val fn = args.getOrNull(0) as? org.mozilla.javascript.Function
                    ?: throw EvaluatorException("queueMicrotask: argument must be a function")
                cx.enqueueMicrotask { fn.call(cx, scope, scope, emptyArray()) }
                Undefined.instance
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

            // 主脚本跑完 → 驱动事件循环:先把已就绪的 Promise 微任务清空,再按到期时间执行定时器,
            // 每个回调后再清一遍微任务。整体受同一 deadline 约束(异步等待也算进 timeout)。
            drainEventLoop(cx, scope, timers, clearedTimers, deadline, cancellationToken)

            val out = output.toString().trimEnd()
            ToolResult.success(if (out.isBlank()) "(执行成功，无输出)" else out)

        } catch (e: EvaluatorException) {
            if (e.message?.contains("timed out") == true)
                ToolResult.error("执行超时（>${timeoutMs}ms）", ToolErr.TIMEOUT)
            else
                // EvaluatorException 也带行号(宿主 API 抛的 readFile/fetch 等错误亦经此)。
                ToolResult.error("脚本错误 [行${e.lineNumber()}]: ${e.message}", ToolErr.SCRIPT_ERROR, e.lineNumber().takeIf { it > 0 })
        } catch (e: RhinoException) {
            ToolResult.error("JS错误 [行${e.lineNumber()}]: ${e.details()}", ToolErr.SCRIPT_ERROR, e.lineNumber().takeIf { it > 0 })
        } catch (e: InterruptedException) {
            // 由 CancellationToken 取消或线程 interrupt 触发,统一视为执行被终止。
            ToolResult.error("执行被中断", ToolErr.TIMEOUT)
        } catch (e: Exception) {
            ToolResult.error("执行异常: ${e.message}", ToolErr.INTERNAL)
        } finally {
            Context.exit()
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * 单线程事件循环:主脚本 evaluateString 之后驱动 Promise 微任务 + setTimeout/setInterval。
     * 每轮取最早到期的定时器;未到期则 sleep 到到期(不超过 deadline)。回调执行后 processMicrotasks()
     * 把 .then 链带动。全程受 [deadline] 约束——异步等待也计入工具 timeout,超时即停,绝不挂死。
     * setInterval 到期后按 intervalMs 重排;clearTimeout/clearInterval 命中即丢弃。
     *
     * 等待使用 [interruptibleSleep],优先响应 [cancellationToken] 取消,无 token 时响应线程 interrupt,
     * 避免 Thread.sleep 阻塞且无法取消的问题。
     */
    private fun drainEventLoop(
        cx: Context,
        scope: Scriptable,
        timers: MutableList<Timer>,
        cleared: MutableSet<Long>,
        deadline: Long,
        cancellationToken: CancellationToken?,
    ) {
        runCatching { cx.processMicrotasks() }  // 主脚本遗留的已就绪微任务
        while (System.currentTimeMillis() < deadline) {
            cancellationToken?.checkCancelled()
            timers.removeAll { it.id in cleared }
            if (timers.isEmpty()) break
            val next = timers.minByOrNull { it.dueAt } ?: break
            val now = System.currentTimeMillis()
            if (next.dueAt > now) {
                val wait = (next.dueAt - now).coerceAtMost(deadline - now)
                if (wait > 0 && interruptibleSleep(wait, cancellationToken)) {
                    throw InterruptedException("Event loop interrupted while waiting for timer")
                }
                continue
            }
            timers.remove(next)
            if (next.id in cleared) continue
            next.fn.call(cx, scope, scope, next.args)          // 回调内异常向上冒泡 → 外层归类 SCRIPT_ERROR
            if (next.intervalMs >= 0 && next.id !in cleared) {  // setInterval:重排下一次
                next.dueAt = System.currentTimeMillis() + next.intervalMs
                timers.add(next)
            }
            runCatching { cx.processMicrotasks() }
        }
    }

    /**
     * 可中断等待 [ms] 毫秒。
     * @return true=被中断/取消,调用方应终止事件循环;false=正常到期。
     */
    private fun interruptibleSleep(ms: Long, token: CancellationToken?): Boolean {
        if (ms <= 0) return false
        // 优先用 CancellationToken:它内部用 Object.wait,响应取消且不会阻塞到无法唤醒。
        if (token != null) {
            val completed = token.sleepInterruptible(ms)
            token.checkCancelled()
            return !completed
        }
        // 无 token 时:用 LockSupport.parkNanos 替代 Thread.sleep,可被 Thread.interrupt() 唤醒。
        val deadlineNs = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < deadlineNs) {
            if (Thread.interrupted()) return true
            val remainingNs = deadlineNs - System.nanoTime()
            if (remainingNs <= 0) break
            LockSupport.parkNanos(remainingNs)
        }
        return Thread.interrupted()
    }

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
