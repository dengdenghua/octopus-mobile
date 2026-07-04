package com.apk.claw.android.registry

import com.apk.claw.android.account.AccountConfig
import com.apk.claw.android.utils.OctoHttp
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 广场社区小程序(community mini-app)消费端 —— 浏览 + 下载「审核通过」的用户投稿小程序。
 *
 * 契约(公开只读,无鉴权,详见 SquarePublisher.kt 顶部注释里的 POST /square/publish 对应关系):
 *   GET <squareBaseUrl>/square/assets?type=plugin&kind=mini-app
 *     列出 status='approved' 的小程序信封(不含 body)。
 *   GET <squareBaseUrl>/square/assets/plugin/{slug}/download
 *     取单个小程序完整 payload(信封 + body,body 是原始 HTML 字符串,非 base64)。
 *
 * 路径故意不用 /api/v1/registry/assets——那个前缀在服务端所在域名的 nginx 上被更早一条 location
 * 规则拦截转发去了另一个服务(enterprise 角色/技能 registry),会撞名到不了 mobile 服务器,
 * 真机实测踩过这个坑。/square/* 前缀没有这个冲突。
 *
 * 注意与 [RegistryAsset]/[RegistryClient] 的关键差异:mini-app 行的 `tags` 字段是**对象**
 * `{actions,allow_tools,allow_hosts,allow_device}`,不是普通 registry 资产那种字符串数组 ——
 * 不能直接复用 [RegistryAsset](其 `tags: List<String>?` 会让 Gson 在这种形状上解析失败/丢字段),
 * 这里用专属 DTO + 手动 [JsonObject] 取字段。
 */

/** mini-app 专属的 tags 对象形状(actions/allow_tools/allow_hosts/allow_device 均为字符串数组)。 */
internal data class MiniAppTags(
    val actions: List<String> = emptyList(),
    @com.google.gson.annotations.SerializedName("allow_tools")
    val allowTools: List<String> = emptyList(),
    @com.google.gson.annotations.SerializedName("allow_hosts")
    val allowHosts: List<String> = emptyList(),
    @com.google.gson.annotations.SerializedName("allow_device")
    val allowDevice: List<String> = emptyList(),
)

/** 广场社区小程序信封(列表项,不含 body)。 */
internal data class CommunityMiniApp(
    val id: String = "",            // "plugin/<slug>"
    val type: String = "",
    val kind: String = "",          // "mini-app"
    val slug: String = "",
    val version: String = "",
    val name: String = "",
    val description: String = "",
    val category: String? = null,
    val tags: MiniAppTags = MiniAppTags(),
    val platforms: List<String>? = null,
    val mode: String? = null,
    val content: RegistryContent? = null,
) {
    /** 兜底:服务端若未下发顶层 slug,从 id 尾段取。 */
    val effectiveSlug: String get() = slug.ifBlank { id.substringAfterLast('/') }
}

/** 下载响应:信封字段 + 原始 HTML body(非 base64,verbatim,sha256(utf-8) 对得上 content.checksum)。 */
internal data class CommunityMiniAppDownload(
    val id: String = "",
    val type: String = "",
    val kind: String = "",
    val slug: String = "",
    val version: String = "",
    val name: String = "",
    val description: String = "",
    val category: String? = null,
    val tags: MiniAppTags = MiniAppTags(),
    val platforms: List<String>? = null,
    val mode: String? = null,
    val content: RegistryContent? = null,
    val body: String = "",
) {
    val effectiveSlug: String get() = slug.ifBlank { id.substringAfterLast('/') }
}

internal object CommunitySquareApi {
    private const val API = "/square/assets"
    private const val ERROR_BODY_TAIL = 120

    private val gson = Gson()
    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 广场基址(与 SquarePublisher 投稿端保持一致来源)。 */
    private fun base(): String = AccountConfig.squareBaseUrl.trim().trimEnd('/')

    /**
     * 列出已审核通过的社区小程序。失败/网络错误返回空列表(不假装成功,由 UI 区分
     * “空列表”与“加载失败”两种状态,故用 [Result] 包一层而非直接吞异常返回空表)。
     */
    suspend fun list(): Result<List<CommunityMiniApp>> = withContext(Dispatchers.IO) {
        val b = base()
        if (b.isEmpty()) return@withContext Result.failure(IllegalStateException("广场服务地址未配置"))
        runCatching {
            val req = Request.Builder().url("$b$API?type=plugin&kind=mini-app").get().build()
            http.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code} ${respBody.take(ERROR_BODY_TAIL)}")
                if (respBody.isBlank()) error("响应为空")
                val root = gson.fromJson(respBody, JsonObject::class.java)
                val dataArr = root?.getAsJsonArray("data") ?: error("响应格式不对(缺 data)")
                dataArr.mapNotNull { el ->
                    runCatching { parseAsset(el.asJsonObject) }.getOrNull()
                }
            }
        }
    }

    /**
     * 下载单个社区小程序完整 payload(信封 + 原始 HTML body,body 非 base64)。
     * 服务端(server/app.py registry_download)返回 `{success, data:{...,body}}`,与
     * [RegistryClient.download] 的 [RegistryDownloadResponse] 同一层套壳,故这里也要
     * 从 `data` 里取,不能整体反序列化。失败抛出,由调用方转成用户可读提示。
     */
    suspend fun download(slug: String): Result<CommunityMiniAppDownload> = withContext(Dispatchers.IO) {
        val b = base()
        if (b.isEmpty()) return@withContext Result.failure(IllegalStateException("广场服务地址未配置"))
        runCatching {
            val req = Request.Builder().url("$b$API/plugin/$slug/download").get().build()
            http.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code} ${respBody.take(ERROR_BODY_TAIL)}")
                if (respBody.isBlank()) error("响应为空")
                val root = gson.fromJson(respBody, JsonObject::class.java) ?: error("响应格式不对")
                val data = root.getAsJsonObject("data") ?: error("响应格式不对(缺 data)")
                parseDownload(data)
            }
        }
    }

    /** tags 对象是两个 DTO 共有的“非常规形状”字段,抽出来共用,避免解析逻辑写两遍。 */
    private fun extractTags(obj: JsonObject): MiniAppTags {
        val tagsEl = obj.get("tags")
        return if (tagsEl != null && tagsEl.isJsonObject) {
            gson.fromJson(tagsEl, MiniAppTags::class.java) ?: MiniAppTags()
        } else {
            MiniAppTags()
        }
    }

    /** 从原始 JsonObject 解析列表项:tags 是对象而非数组,手动映射进 [MiniAppTags]。 */
    private fun parseAsset(obj: JsonObject): CommunityMiniApp {
        val tags = extractTags(obj)
        // 复用 Gson 解析除 tags 外的其余字段:先拷一份把 tags 摘掉(避免 Gson 用错误形状再报错),
        // 再把手动解析好的 tags 塞回去。
        val stripped = obj.deepCopy().apply { remove("tags") }
        val base = gson.fromJson(stripped, CommunityMiniApp::class.java) ?: CommunityMiniApp()
        return base.copy(tags = tags)
    }

    /** 从 data 对象解析下载 payload:tags 是对象而非数组,手动映射进 [MiniAppTags],body 原样读取字符串。 */
    private fun parseDownload(obj: JsonObject): CommunityMiniAppDownload {
        val tags = extractTags(obj)
        val stripped = obj.deepCopy().apply { remove("tags") }
        val base = gson.fromJson(stripped, CommunityMiniAppDownload::class.java) ?: CommunityMiniAppDownload()
        return base.copy(tags = tags)
    }
}
