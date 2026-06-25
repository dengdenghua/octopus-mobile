package com.apk.claw.android.tool.impl

import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.XLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 企业版项目管理(PM)工具 —— D① 编程接入。
 *
 * PM 已从 OS/agent 移除(交企业版),mobile 这边给 Agent 加工具:收到「建个
 * 任务/记到项目里」类指令时,直接调企业版 PM API。依赖方向:mobile →(HTTP)
 * → 企业版 PM 服务(见 octopus-enterprise/docs/PM_INTERFACE.md)。
 *
 * 配置经 MMKV 三个键:octopus.pm.url / octopus.pm.token / octopus.pm.tenant。
 * 未配置 url 时工具返回错误(不静默)。端到端需真机 + 运行的企业版;此处为工具
 * 实现 + JVM 单测(MockWebServer)。
 */
object PmConfig {
    fun url(): String = read("octopus.pm.url")
    fun token(): String = read("octopus.pm.token")
    fun tenant(): String = read("octopus.pm.tenant")

    private fun read(key: String): String =
        try {
            com.tencent.mmkv.MMKV.defaultMMKV()?.decodeString(key, "") ?: ""
        } catch (e: Throwable) {
            XLog.w("PmConfig", "read $key failed", e)
            "" // MMKV 未初始化(如单测环境)→ 视为未配置
        }
}

private fun pmRequest(
    base: String,
    token: String,
    tenant: String,
    builder: Request.Builder,
): Request {
    builder.addHeader("Content-Type", "application/json")
    if (token.isNotBlank()) builder.addHeader("Authorization", "Bearer $token")
    if (tenant.isNotBlank()) builder.addHeader("X-Tenant-ID", tenant)
    return builder.build()
}

/** create_pm_task —— 在企业版某项目下新建任务。 */
class CreatePmTaskTool(
    private val baseUrl: () -> String = { PmConfig.url() },
    private val token: () -> String = { PmConfig.token() },
    private val tenant: () -> String = { PmConfig.tenant() },
    private val client: OkHttpClient = OkHttpClient(),
) : BaseTool() {

    override fun getName(): String = "create_pm_task"

    override fun getDisplayName(): String = "建项目任务"

    override fun getDescriptionEN(): String =
        "Create a task in an Octopus Enterprise project. Use when the user wants to turn a " +
            "todo or a breakdown into a project task. Get project_id from list_pm_projects first."

    override fun getDescriptionCN(): String =
        "在企业版某项目下新建任务。用户说「把这个建成任务/记到项目里」时用;先用 list_pm_projects 拿 project_id。"

    override fun getParameters(): List<ToolParameter> =
        listOf(
            ToolParameter("project_id", "string", "企业版项目 ID(先用 list_pm_projects 获取)", true),
            ToolParameter("title", "string", "任务标题", true),
            ToolParameter(
                "role",
                "string",
                "负责岗位:硬件/固件/软件/结构/AI/测试/PM/供应链,默认 PM",
                false,
            ),
        )

    override fun execute(params: @JvmSuppressWildcards Map<String, Any>): ToolResult {
        val base = baseUrl().trimEnd('/')
        if (base.isBlank()) return ToolResult.error("企业版未配置(octopus.pm.url)")
        val projectId = requireString(params, "project_id")
        val title = requireString(params, "title")
        val role = optionalString(params, "role", "PM")

        val payload =
            JSONObject()
                .put("title", title)
                .put("role", role)
                .put("priority", "p2")
                .toString()
                .toRequestBody("application/json".toMediaType())

        val request =
            pmRequest(
                base,
                token(),
                tenant(),
                Request.Builder()
                    .url("$base/api/v1/projects/$projectId/tasks")
                    .post(payload),
            )

        return try {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    ToolResult.success(text.ifBlank { "{}" })
                } else {
                    ToolResult.error("企业版返回 ${resp.code}: ${text.take(200)}")
                }
            }
        } catch (e: Exception) {
            ToolResult.error("调用企业版失败: ${e.message}")
        }
    }
}

/** list_pm_projects —— 列出企业版的项目(建任务前拿 project_id)。 */
class ListPmProjectsTool(
    private val baseUrl: () -> String = { PmConfig.url() },
    private val token: () -> String = { PmConfig.token() },
    private val tenant: () -> String = { PmConfig.tenant() },
    private val client: OkHttpClient = OkHttpClient(),
) : BaseTool() {

    override fun getName(): String = "list_pm_projects"

    override fun getDisplayName(): String = "列项目"

    override fun getDescriptionEN(): String =
        "List projects in Octopus Enterprise PM. Use to see projects, or to get a " +
            "project_id before creating a task with create_pm_task."

    override fun getDescriptionCN(): String =
        "列出企业版的项目。查看项目、或在 create_pm_task 建任务前拿 project_id 时用。"

    override fun getParameters(): List<ToolParameter> = emptyList()

    override fun execute(params: @JvmSuppressWildcards Map<String, Any>): ToolResult {
        val base = baseUrl().trimEnd('/')
        if (base.isBlank()) return ToolResult.error("企业版未配置(octopus.pm.url)")

        val request =
            pmRequest(
                base,
                token(),
                tenant(),
                Request.Builder().url("$base/api/v1/projects").get(),
            )

        return try {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    ToolResult.success(text.ifBlank { "[]" })
                } else {
                    ToolResult.error("企业版返回 ${resp.code}: ${text.take(200)}")
                }
            }
        } catch (e: Exception) {
            ToolResult.error("调用企业版失败: ${e.message}")
        }
    }
}
