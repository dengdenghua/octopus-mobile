package com.apk.claw.android.ui.compose.screen

import com.apk.claw.android.account.AccountConfig
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.utils.OctoHttp
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 广场图文帖(小红书式)客户端 API。
 *
 * 与 [SquareRepository](只读 feed)分文件:本文件只负责"写"侧 —— 发帖、上传图片、
 * 点赞/评论/收藏/关注。所有方法要求登录态(读 AccountStore.token),失败抛异常,
 * 调用方自行 try/catch 或包 Result。
 *
 * 服务端契约见 server/app.py 的 /square/posts/... 端点。
 */
internal object SquarePostApi {

    private val gson = Gson()
    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)  // 图片上传/发帖审核可能稍慢
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun base(): String =
        AccountConfig.squareBaseUrl.trim().trimEnd('/')

    private fun authedBuilder(path: String): Request.Builder {
        val tok = AccountStore.token
        val b = Request.Builder().url(base() + path)
        if (tok.isNotBlank()) b.header("Authorization", "Bearer $tok")
        return b
    }

    // ── 响应 DTO ──────────────────────────────────────────────

    data class UploadImageResult(
        val url: String = "",
        val width: Int = 0,
        val height: Int = 0,
    )

    data class PublishPostResult(
        val ok: Boolean = false,
        val status: String = "",
        val postId: String = "",
        val message: String = "",
        val reason: String = "",
    )

    data class LikeResult(
        val ok: Boolean = false,
        val liked: Boolean = false,
        @SerializedName("likesCount") val likesCount: Int = 0,
    )

    data class FavoriteResult(
        val ok: Boolean = false,
        val favorited: Boolean = false,
        @SerializedName("favoritesCount") val favoritesCount: Int = 0,
    )

    data class CommentDto(
        val id: String = "",
        @SerializedName("postId") val postId: String = "",
        val author: String = "",
        @SerializedName("authorId") val authorId: String = "",
        val content: String = "",
        @SerializedName("parentId") val parentId: String = "",
        @SerializedName("createdAt") val createdAt: Long = 0,
    )

    data class CommentsResult(
        val comments: List<CommentDto> = emptyList(),
        @SerializedName("has_more") val hasMore: Boolean = false,
    )

    data class FollowResult(
        val ok: Boolean = false,
        val following: Boolean = false,
        @SerializedName("followersCount") val followersCount: Int = 0,
    )

    data class UserProfileDto(
        val userId: String = "",
        val nickname: String = "",
        @SerializedName("followingCount") val followingCount: Int = 0,
        @SerializedName("followersCount") val followersCount: Int = 0,
        @SerializedName("isFollowing") val isFollowing: Boolean = false,
    )

    data class UserProfileResult(
        val user: UserProfileDto = UserProfileDto(),
        val posts: List<SquarePostDto> = emptyList(),
    )

    data class PostDetailResult(
        val post: SquarePostDto = SquarePostDto(),
    )

    /** 复刻/下载响应。app = 关联应用载荷(与社区小程序 download 的 data 同 shape),
     *  交由 CommunitySquareApi.parseDownloadPayload + CommunityMiniAppInstaller 安装。 */
    data class AcquireResult(
        val ok: Boolean = false,
        val owned: Boolean = false,
        @SerializedName("appKind") val appKind: String = "",
        @SerializedName("appRef") val appRef: String = "",
        @SerializedName("creatorEarned") val creatorEarned: Int = 0,
        val balance: Int = 0,
        val app: com.google.gson.JsonObject? = null,
    )

    // ── API 方法 ──────────────────────────────────────────────

    /** 上传单张图片(已压缩的 File)。返回服务端 URL。 */
    suspend fun uploadImage(file: File, mime: String = "image/jpeg"): UploadImageResult =
        withContext(Dispatchers.IO) {
            val mediaType = mime.toMediaType()
            val body = file.asRequestBody(mediaType)
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, body)
                .build()
            val req = authedBuilder("/square/upload-image").post(multipart).build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw RuntimeException(serverDetail(text) ?: "HTTP ${resp.code}")
                gson.fromJson(text, UploadImageResult::class.java)
            }
        }

    /** 发布图文帖。images 是上传后拿到的 URL 列表。 */
    suspend fun publishPost(
        title: String,
        content: String,
        images: List<String>,
        tag: String,
    ): PublishPostResult = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "title" to title,
            "content" to content,
            "images" to images,
            "tag" to tag,
        )
        val req = authedBuilder("/square/posts/publish")
            .post(gson.toJson(payload).toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException(serverDetail(text) ?: "HTTP ${resp.code}")
            gson.fromJson(text, PublishPostResult::class.java)
        }
    }

    /** 帖子详情。 */
    suspend fun postDetail(postId: String): PostDetailResult = withContext(Dispatchers.IO) {
        val req = authedBuilder("/square/posts/$postId").get().build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException(serverDetail(text) ?: "HTTP ${resp.code}")
            gson.fromJson(text, PostDetailResult::class.java)
        }
    }

    /** 复刻/下载帖子关联应用。付费帖首次调用扣积分(服务端原子扣款+分成),已复刻则免费重取;
     *  返回含 app 载荷(mini-app 走 CommunityMiniAppInstaller 安装)。余额不足服务端返回 402。 */
    suspend fun acquire(postId: String): AcquireResult = withContext(Dispatchers.IO) {
        val req = authedBuilder("/square/posts/$postId/acquire").post("{}".toRequestBody(JSON)).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException(serverDetail(text) ?: "HTTP ${resp.code}")
            gson.fromJson(text, AcquireResult::class.java)
        }
    }

    /** 点赞(幂等)。 */
    suspend fun like(postId: String): LikeResult = withContext(Dispatchers.IO) {
        val req = authedBuilder("/square/posts/$postId/like").post("{}".toRequestBody(JSON)).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException(serverDetail(text) ?: "HTTP ${resp.code}")
            gson.fromJson(text, LikeResult::class.java)
        }
    }

    /** 取消点赞(幂等)。 */
    suspend fun unlike(postId: String): LikeResult = withContext(Dispatchers.IO) {
        val req = authedBuilder("/square/posts/$postId/like").delete().build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException(serverDetail(text) ?: "HTTP ${resp.code}")
            gson.fromJson(text, LikeResult::class.java)
        }
    }

    /** 收藏(幂等)。 */
    suspend fun favorite(postId: String): FavoriteResult = withContext(Dispatchers.IO) {
        val req = authedBuilder("/square/posts/$postId/favorite").post("{}".toRequestBody(JSON)).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException(serverDetail(text) ?: "HTTP ${resp.code}")
            gson.fromJson(text, FavoriteResult::class.java)
        }
    }

    /** 取消收藏(幂等)。 */
    suspend fun unfavorite(postId: String): FavoriteResult = withContext(Dispatchers.IO) {
        val req = authedBuilder("/square/posts/$postId/favorite").delete().build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException(serverDetail(text) ?: "HTTP ${resp.code}")
            gson.fromJson(text, FavoriteResult::class.java)
        }
    }

    /** 评论列表。 */
    suspend fun listComments(postId: String): CommentsResult = withContext(Dispatchers.IO) {
        val req = authedBuilder("/square/posts/$postId/comments?limit=100").get().build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException(serverDetail(text) ?: "HTTP ${resp.code}")
            gson.fromJson(text, CommentsResult::class.java)
        }
    }

    /** 发评论。 */
    suspend fun postComment(postId: String, content: String, parentId: String = ""): CommentDto =
        withContext(Dispatchers.IO) {
            val payload = mapOf(
                "content" to content,
                "parentId" to parentId,
            )
            val req = authedBuilder("/square/posts/$postId/comments")
                .post(gson.toJson(payload).toRequestBody(JSON))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw RuntimeException(serverDetail(text) ?: "HTTP ${resp.code}")
                val r = gson.fromJson(text, CommentPostResult::class.java)
                r.comment ?: throw RuntimeException("评论失败")
            }
        }

    private data class CommentPostResult(val ok: Boolean = false, val comment: CommentDto? = null)

    /** 关注用户(用 opaque uid)。 */
    suspend fun follow(opaqueUserId: String): FollowResult = withContext(Dispatchers.IO) {
        val req = authedBuilder("/square/users/$opaqueUserId/follow").post("{}".toRequestBody(JSON)).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException(serverDetail(text) ?: "HTTP ${resp.code}")
            gson.fromJson(text, FollowResult::class.java)
        }
    }

    /** 取消关注(幂等)。 */
    suspend fun unfollow(opaqueUserId: String): FollowResult = withContext(Dispatchers.IO) {
        val req = authedBuilder("/square/users/$opaqueUserId/follow").delete().build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException(serverDetail(text) ?: "HTTP ${resp.code}")
            gson.fromJson(text, FollowResult::class.java)
        }
    }

    /** 用户主页。 */
    suspend fun userProfile(opaqueUserId: String): UserProfileResult = withContext(Dispatchers.IO) {
        val req = authedBuilder("/square/users/$opaqueUserId").get().build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException(serverDetail(text) ?: "HTTP ${resp.code}")
            gson.fromJson(text, UserProfileResult::class.java)
        }
    }

    /** 搜索广场帖(q 关键词)。返回混合 feed(图文 + 小程序)。 */
    suspend fun search(q: String): List<SquarePostDto> = withContext(Dispatchers.IO) {
        val req = authedBuilder("/square/feed?q=${java.net.URLEncoder.encode(q, "UTF-8")}&limit=50").get().build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException(serverDetail(text) ?: "HTTP ${resp.code}")
            gson.fromJson(text, SquareFeedDto::class.java).posts
        }
    }

    /** 拉服务端 detail 文本里的友好错误信息(FastAPI {"detail":"..."})。 */
    private fun serverDetail(body: String): String? = runCatching {
        gson.fromJson(body, com.google.gson.JsonObject::class.java)
            ?.get("detail")?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull()
}
