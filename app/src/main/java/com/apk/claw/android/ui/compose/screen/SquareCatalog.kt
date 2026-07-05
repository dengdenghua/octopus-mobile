package com.apk.claw.android.ui.compose.screen
import com.apk.claw.android.utils.OctoHttp

import androidx.compose.ui.graphics.Color
import com.apk.claw.android.R
import com.apk.claw.android.account.AccountConfig
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.ui.compose.theme.OctopusTints
import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 广场数据层。
 *
 * 设计目标（按需求）：
 *  - **广场目录走服务端 API**（`<baseUrl>/square/feed`），后台可随意增删改，App 不用发版；
 *    网络失败时回退到本地缓存，再回退到内置种子，保证永远有内容、不空屏。
 *  - **与本机强相关的技能/插件走本地注册**（[LocalSkillRegistry]），不依赖服务端目录。
 *
 * UI（[AgentSquareScreen]）只消费 [AgentPost]，不关心来源。
 */

/** 广场卡片领域模型（颜色已解析为 Compose Color，UI 直接用）。 */
internal data class AgentPost(
    val id: String,
    val title: String,
    val author: String,
    val authorInitial: String,
    val authorColor: Color,
    val likes: String,
    val tag: String,
    val tagColor: Color,
    val coverHeightDp: Int,
    val coverGradient: List<Color>,
    /** true = 本地注册的技能/插件（非服务端目录）。 */
    val local: Boolean = false,
    /** 帖子类型:"post"=图文帖(小红书式), "mini-app"=小程序分享帖, ""=旧式种子/静态示例。 */
    val kind: String = "",
    /** 图文帖正文(仅 kind="post" 有)。 */
    val content: String = "",
    /** 图文帖封面图 URL(优先于 coverGradient 展示)。 */
    val coverUrl: String = "",
    /** 图文帖图片列表(详情页用)。 */
    val images: List<String> = emptyList(),
    /** 作者 opaque uid(点击作者头像跳主页用)。 */
    val authorId: String = "",
    /** 点赞数(数值形式,便于展示与排序;旧式卡片用 likes 字符串)。 */
    val likesCount: Int = 0,
    /** 评论数。 */
    val commentsCount: Int = 0,
    /** 收藏数。 */
    val favoritesCount: Int = 0,
    /** 当前用户是否已点赞。 */
    val liked: Boolean = false,
    /** 当前用户是否已收藏。 */
    val favorited: Boolean = false,
    /** 创建时间(epoch millis)。 */
    val createdAt: Long = 0,
)

/** 服务端下发的广场卡片：颜色用 "#RRGGBB" 字符串，方便后台随意编辑。 */
internal data class SquarePostDto(
    val id: String = "",
    val kind: String = "",
    val title: String = "",
    val content: String = "",
    val coverUrl: String = "",
    val images: List<String> = emptyList(),
    val author: String = "",
    val authorId: String = "",
    val authorInitial: String = "",
    val authorColor: String = "#7C6FF0",
    val likes: String = "",
    @com.google.gson.annotations.SerializedName("likesCount") val likesCount: Int = 0,
    @com.google.gson.annotations.SerializedName("commentsCount") val commentsCount: Int = 0,
    @com.google.gson.annotations.SerializedName("favoritesCount") val favoritesCount: Int = 0,
    val liked: Boolean = false,
    val favorited: Boolean = false,
    val tag: String = "",
    val tagColor: String = "#7C6FF0",
    val coverHeightDp: Int = 160,
    val coverGradient: List<String> = emptyList(),
    @com.google.gson.annotations.SerializedName("createdAt") val createdAt: Long = 0,
)

internal data class SquareFeedDto(
    val posts: List<SquarePostDto> = emptyList(),
    @com.google.gson.annotations.SerializedName("has_more") val hasMore: Boolean = false,
)

private fun parseColor(hex: String, fallback: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex.trim())) }.getOrDefault(fallback)

private val BrowserTint get() = OctopusTints.Browser
private val MemoryTint get() = OctopusTints.Memory
private val SkillTint get() = OctopusTints.Skill
private val RoutineTint get() = OctopusTints.Routine
private val CloudTint get() = OctopusTints.Cloud
private val WindowTint get() = OctopusTints.Window

internal fun SquarePostDto.toAgentPost(): AgentPost {
    val grad = coverGradient
        .mapNotNull { runCatching { Color(android.graphics.Color.parseColor(it.trim())) }.getOrNull() }
        .ifEmpty { listOf(Color(0xFF667EEA), Color(0xFF764BA2)) }
    return AgentPost(
        id = id.ifBlank { title.hashCode().toString() },
        title = title,
        author = author,
        authorInitial = authorInitial.ifBlank { author.take(1) },
        authorColor = parseColor(authorColor, OctopusTints.Skill),
        likes = likes,
        tag = tag,
        tagColor = parseColor(tagColor, OctopusTints.Routine),
        coverHeightDp = coverHeightDp.coerceIn(120, 240),
        coverGradient = grad,
        kind = kind,
        content = content,
        coverUrl = coverUrl,
        images = images,
        authorId = authorId,
        likesCount = likesCount,
        commentsCount = commentsCount,
        favoritesCount = favoritesCount,
        liked = liked,
        favorited = favorited,
        createdAt = createdAt,
    )
}

/**
 * 本地技能/插件注册表：与本机能力强相关的条目在本地注册，不走服务端目录。
 * 各本地插件可在初始化时调用 [register] 把自己挂上来。
 */
internal object LocalSkillRegistry {
    private val items = linkedMapOf<String, AgentPost>()

    fun register(post: AgentPost) { items[post.id] = post.copy(local = true) }
    fun unregister(id: String) { items.remove(id) }
    fun all(): List<AgentPost> = items.values.toList()

    init {
        // 内置示例：本地相关技能（演示本地注册机制；真实插件可各自 register）
        register(
            AgentPost(
                id = "local.notify", title = "通知巡检 · 重要消息汇总给我",
                author = "本地技能", authorInitial = "本", authorColor = OctopusTints.Skill,
                likes = "", tag = "本地", tagColor = OctopusTints.Window,
                coverHeightDp = 140, coverGradient = listOf(Color(0xFF11998E), Color(0xFF38EF7D)),
            )
        )
        register(
            AgentPost(
                id = "local.files", title = "文件整理 · 截图自动归档到文件夹",
                author = "本地技能", authorInitial = "本", authorColor = OctopusTints.Cloud,
                likes = "", tag = "本地", tagColor = OctopusTints.Window,
                coverHeightDp = 150, coverGradient = listOf(Color(0xFF667EEA), Color(0xFF764BA2)),
            )
        )
    }
}

/** 广场目录仓库：服务端 → 缓存 → 内置种子，三级回退。 */
internal object SquareRepository {
    private const val CACHE_KEY = "SQUARE_FEED_CACHE_JSON"
    private val gson = Gson()
    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 服务端目录（后台可随意改）。失败回退缓存，再回退内置种子。 */
    suspend fun remoteFeed(): List<AgentPost> = withContext(Dispatchers.IO) {
        RemoteConfig.refresh()  // 先取服务端下发的技能中心域名（拿不到则用缓存/默认）
        val base = AccountConfig.squareBaseUrl.trim().trimEnd('/')
        if (base.isNotBlank()) {
            runCatching {
                val builder = Request.Builder().url("$base/square/feed").get()
                // 携带登录态:让服务端能返回 liked/favorited 当前用户态(未登录时服务端忽略)
                val tok = AccountStore.token
                if (tok.isNotBlank()) builder.header("Authorization", "Bearer $tok")
                http.newCall(builder.build()).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful && body.isNotBlank()) {
                        val feed = gson.fromJson(body, SquareFeedDto::class.java)
                        if (feed?.posts?.isNotEmpty() == true) {
                            KVUtils.putString(CACHE_KEY, body)            // 缓存成功结果
                            return@withContext feed.posts.map { it.toAgentPost() }
                        }
                    }
                }
            }
        }
        cachedOrSeed()
    }

    private fun cachedOrSeed(): List<AgentPost> {
        val cached = KVUtils.getString(CACHE_KEY, "")
        if (cached.isNotBlank()) {
            runCatching {
                val feed = gson.fromJson(cached, SquareFeedDto::class.java)
                if (feed?.posts?.isNotEmpty() == true) return feed.posts.map { it.toAgentPost() }
            }
        }
        return SEED
    }

    /** 本地技能/插件（本地注册，不依赖服务端）。 */
    fun localFeed(): List<AgentPost> = LocalSkillRegistry.all()

    /** 离线兜底种子：服务端与缓存都不可用时仍有内容可展示。 */
    private val SEED: List<AgentPost> = listOf(
        AgentPost("seed.1", "让 AI 每天自动整理手机相册，生成回忆视频", "影像助手", "影", OctopusTints.Video, "1.2k", "自动化", OctopusTints.Routine, 180, listOf(Color(0xFF667EEA), Color(0xFF764BA2))),
        AgentPost("seed.2", "3 步搭一个会订外卖的助手", "效率玩家", "效", OctopusTints.Skill, "856", "教程", OctopusTints.Browser, 140, listOf(Color(0xFF11998E), Color(0xFF38EF7D))),
        AgentPost("seed.3", "自动写一周周报，老板直呼专业", "打工侠", "打", OctopusTints.Window, "2.3k", "职场", OctopusTints.Memory, 200, listOf(Color(0xFFFC466B), Color(0xFF3F5EFB))),
        AgentPost("seed.4", "用语音唤醒助手，开车时也能回消息", "车载达人", "车", OctopusTints.Plugin, "634", "语音", OctopusTints.Trust, 160, listOf(Color(0xFFF2994A), Color(0xFFF2C94C))),
        AgentPost("seed.5", "自动比价，618 我省了 2000+", "省钱 Bot", "省", OctopusTints.Cloud, "3.1k", "购物", OctopusTints.Hot, 170, listOf(Color(0xFF00C6FF), Color(0xFF0072FF))),
        AgentPost("seed.6", "接入智能家居，一句话控制全屋", "极客居", "极", OctopusTints.Evolve, "1.5k", "IoT", OctopusTints.Plugin, 150, listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0))),
        AgentPost("seed.7", "生成旅行攻略，细到每天照着走", "旅行灵感", "旅", OctopusTints.Browser, "987", "生活", OctopusTints.Routine, 190, listOf(Color(0xFFEE9CA7), Color(0xFFFFDDE1))),
        AgentPost("seed.8", "让助手帮你读论文，10 分钟抓重点", "学术喵", "学", OctopusTints.Memory, "742", "学习", OctopusTints.Skill, 145, listOf(Color(0xFF134E5E), Color(0xFF71B280))),
    )
}

// ───────────────────────── 灵感发现流（FeatureHub「灵感」tab）─────────────────────────

/**
 * 灵感发现卡片（富模型：封面/作者/用量/成功率/权限/详情）。
 * 内容文本走服务端，可后台随意改；[topicRes] 仍是本地化分类资源，保留 App 内分类胶囊/筛选/封面图标逻辑。
 * [topicKey] 是服务端 topic key（automation/efficiency/life/learning/device），用于远端 topics 标签匹配。
 */
internal data class AgentDiscoveryPost(
    val id: String,
    val title: String,
    val desc: String,
    val author: String,
    val authorInitial: String,
    val likes: String,
    val topicRes: Int,
    val topicKey: String,
    val tag: String,
    val tagColor: Color,
    val coverHeight: Int,
    val cover: List<Color>,
    val usage: String,
    val successRate: String,
    val duration: String,
    val permissions: List<String>,
)

/** 服务端下发的灵感卡片（颜色 "#RRGGBB"、topic 用 key、文本与权限直给字符串）。 */
internal data class DiscoveryPostDto(
    val id: String = "",
    val title: String = "",
    val desc: String = "",
    val author: String = "",
    val authorInitial: String = "",
    val likes: String = "",
    val topic: String = "",
    val tag: String = "",
    val tagColor: String = "#7C6FF0",
    val coverHeight: Int = 160,
    val cover: List<String> = emptyList(),
    val usage: String = "",
    val successRate: String = "",
    val duration: String = "",
    val permissions: List<String> = emptyList(),
)

/** 服务端下发的顶部引导动作（icon/action 用 key 字符串，App 端映射）。 */
internal data class DiscoveryActionDto(
    val icon: String = "",
    val text: String = "",
    val action: String = "",
    val tint: String = "",
)

/** 服务端下发的顶部引导头（title/desc/icon/tint/actions）。 */
internal data class DiscoveryHeaderDto(
    val title: String = "",
    val desc: String = "",
    val icon: String = "",
    val tint: String = "",
    val actions: List<DiscoveryActionDto> = emptyList(),
)

/** 服务端下发的主题标签（key 用于筛选，label/tint 可选，留空时 App 回退本地化资源）。 */
internal data class DiscoveryTopicDto(
    val key: String = "",
    val label: String = "",
    val tint: String = "",
)

internal data class DiscoveryFeedDto(
    val posts: List<DiscoveryPostDto> = emptyList(),
    val header: DiscoveryHeaderDto = DiscoveryHeaderDto(),
    val topics: List<DiscoveryTopicDto> = emptyList(),
)

// ── 灵感流 domain（颜色已解析为 Compose Color，UI 直接用） ──

/** 顶部引导动作。text 为空时 UI 用 stringResource 兜底。 */
internal data class DiscoveryAction(
    val icon: String,
    val text: String,
    val action: String,
    val tint: Color,
)

/** 顶部引导头。title/desc 为空时 UI 用 stringResource 兜底。 */
internal data class DiscoveryHeader(
    val title: String,
    val desc: String,
    val icon: String,
    val tint: Color,
    val actions: List<DiscoveryAction>,
)

/** 主题标签。label 为空时 UI 用 stringResource 兜底。 */
internal data class DiscoveryTopic(
    val key: String,
    val label: String,
    val tint: Color,
)

/** 灵感流整体：header + topics + posts 三段。 */
internal data class DiscoveryFeed(
    val header: DiscoveryHeader,
    val topics: List<DiscoveryTopic>,
    val posts: List<AgentDiscoveryPost>,
)

/** 服务端 topic key → 本地化分类资源（沿用 App 内已有分类胶囊/筛选/图标）。 */
private fun topicKeyToRes(key: String): Int = when (key.trim().lowercase()) {
    "automation" -> R.string.agent_topic_automation
    "efficiency" -> R.string.agent_topic_efficiency
    "life", "lifestyle" -> R.string.agent_topic_life
    "learning" -> R.string.agent_topic_learning
    "device" -> R.string.agent_topic_device
    else -> R.string.agent_topic_automation
}

private fun DiscoveryPostDto.toPost(): AgentDiscoveryPost {
    val cov = cover
        .mapNotNull { runCatching { Color(android.graphics.Color.parseColor(it.trim())) }.getOrNull() }
        .ifEmpty { listOf(Color(0xFF667EEA), Color(0xFF764BA2)) }
    return AgentDiscoveryPost(
        id = id.ifBlank { title.hashCode().toString() },
        title = title, desc = desc, author = author,
        authorInitial = authorInitial.ifBlank { author.take(1) },
        likes = likes,
        topicRes = topicKeyToRes(topic),
        topicKey = topic.trim().lowercase(),
        tag = tag.ifBlank { topic },
        tagColor = parseColor(tagColor, OctopusTints.Routine),
        coverHeight = coverHeight.coerceIn(120, 240),
        cover = cov,
        usage = usage, successRate = successRate, duration = duration,
        permissions = permissions,
    )
}

private fun DiscoveryActionDto.toAction(): DiscoveryAction = DiscoveryAction(
    icon = icon,
    text = text,
    action = action,
    tint = parseColor(tint, OctopusTints.Browser),
)

private fun DiscoveryHeaderDto.toHeader(): DiscoveryHeader = DiscoveryHeader(
    title = title,
    desc = desc,
    icon = icon,
    tint = parseColor(tint, OctopusTints.Browser),
    actions = actions.map { it.toAction() },
)

private fun DiscoveryTopicDto.toTopic(): DiscoveryTopic = DiscoveryTopic(
    key = key.trim().lowercase(),
    label = label,
    tint = parseColor(tint, OctopusTints.Hot),
)

private fun DiscoveryFeedDto.toFeed(): DiscoveryFeed = DiscoveryFeed(
    header = header.toHeader(),
    topics = topics.map { it.toTopic() },
    posts = posts.map { it.toPost() },
)

/** 灵感发现流仓库：服务端 `/square/discovery` → 缓存 → 内置种子。 */
internal object DiscoveryRepository {
    private const val CACHE_KEY = "SQUARE_DISCOVERY_CACHE_JSON"
    private val gson = Gson()
    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun feed(): DiscoveryFeed = withContext(Dispatchers.IO) {
        RemoteConfig.refresh()  // 先取服务端下发的技能中心域名（拿不到则用缓存/默认）
        val base = AccountConfig.squareBaseUrl.trim().trimEnd('/')
        if (base.isNotBlank()) {
            runCatching {
                val req = Request.Builder().url("$base/square/discovery").get().build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful && body.isNotBlank()) {
                        val dto = gson.fromJson(body, DiscoveryFeedDto::class.java)
                        if (dto != null && dto.posts.isNotEmpty()) {
                            KVUtils.putString(CACHE_KEY, body)
                            return@withContext dto.toFeed()
                        }
                    }
                }
            }
        }
        cachedOrSeed()
    }

    private fun cachedOrSeed(): DiscoveryFeed {
        val cached = KVUtils.getString(CACHE_KEY, "")
        if (cached.isNotBlank()) {
            runCatching {
                val dto = gson.fromJson(cached, DiscoveryFeedDto::class.java)
                if (dto != null && dto.posts.isNotEmpty()) return dto.toFeed()
            }
        }
        return SEED
    }

    /** 离线兜底种子（header/topics 字段留空 → UI 用 stringResource 兜底）。 */
    private val SEED: DiscoveryFeed = DiscoveryFeed(
        header = DiscoveryHeader(
            title = "", desc = "", icon = "AutoAwesome", tint = OctopusTints.Browser,
            actions = listOf(
                DiscoveryAction("Search", "", "search", OctopusTints.Browser),
                DiscoveryAction("Psychology", "", "universe", OctopusTints.Memory),
                DiscoveryAction("Add", "", "publish", OctopusTints.Skill),
            ),
        ),
        topics = listOf(
            DiscoveryTopic("recommend", "", OctopusTints.Hot),
            DiscoveryTopic("automation", "", OctopusTints.Routine),
            DiscoveryTopic("efficiency", "", OctopusTints.Skill),
            DiscoveryTopic("life", "", OctopusTints.Cloud),
            DiscoveryTopic("learning", "", OctopusTints.Memory),
            DiscoveryTopic("device", "", OctopusTints.Window),
        ),
        posts = listOf(
            AgentDiscoveryPost("agent-travel", "旅行规划:一键搞定航班到行程", "输入目的地和预算,自动搜索景点、规划路线,并生成可分享的清单。", "旅行灵感", "旅", "3.2k", R.string.agent_topic_life, "life", "生活", OctopusTints.Routine, 168, listOf(Color(0xFFFFB199), Color(0xFFFF0844)), "18.6k", "92%", "约 4 分钟", listOf("浏览器", "定位", "截图")),
            AgentDiscoveryPost("agent-weekly", "周报自动归档模板", "汇总聊天记录、任务清单和日程,自动写出老板点赞的周报。", "效率玩家", "效", "2.8k", R.string.agent_topic_efficiency, "efficiency", "效率", OctopusTints.Skill, 138, listOf(Color(0xFF667EEA), Color(0xFF764BA2)), "12.4k", "95%", "约 2 分钟", listOf("日历", "剪贴板", "文档")),
            AgentDiscoveryPost("agent-phone", "旧手机变 7×24 执行器", "让备用机代收消息、截图、转发和定时任务,主力机保持安静。", "极客据点", "极", "1.7k", R.string.agent_topic_device, "device", "设备", OctopusTints.Window, 190, listOf(Color(0xFF134E5E), Color(0xFF71B280)), "8.1k", "89%", "约 6 分钟", listOf("无障碍", "通知", "后台")),
            AgentDiscoveryPost("agent-shopping", "比价助手帮我省了 2000+", "监控历史价格、优惠券和平台活动,降价即提醒。", "省钱 Bot", "省", "4.6k", R.string.agent_topic_automation, "automation", "自动化", OctopusTints.Hot, 156, listOf(Color(0xFFFFD194), Color(0xFFD1913C)), "23.9k", "91%", "约 3 分钟", listOf("浏览器", "通知", "定时器")),
            AgentDiscoveryPost("agent-paper", "论文阅读器:10 分钟抓重点", "读取 PDF、网页和截图,自动抽取结论与可引用的观点。", "学术助手", "学", "986", R.string.agent_topic_learning, "learning", "学习", OctopusTints.Memory, 176, listOf(Color(0xFF00C6FF), Color(0xFF0072FF)), "6.5k", "94%", "约 5 分钟", listOf("文件", "浏览器", "剪贴板")),
            AgentDiscoveryPost("agent-voice", "语音助手:开车时替你回消息", "按下说话,自动识别收件人、调整语气并发送。", "车载达人", "车", "742", R.string.agent_topic_automation, "automation", "语音", OctopusTints.Trust, 146, listOf(Color(0xFFF2994A), Color(0xFFF2C94C)), "5.7k", "88%", "约 2 分钟", listOf("麦克风", "通知", "无障碍")),
        ),
    )
}

/**
 * App 远程配置：从主 API `<baseUrl>/config` 拉取技能中心域名等并缓存进 [AccountConfig]。
 * 即「子域名由服务端生成/控制」——后台改一处，全网 App 下次进广场即生效，无需发版。
 */
internal object RemoteConfig {
    private val gson = Gson()
    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private data class AppConfigDto(val squareBaseUrl: String? = null)

    /** 拉一次远程配置；失败静默（保留上次缓存/默认）。 */
    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            val base = AccountConfig.baseUrl.trim().trimEnd('/')
            if (base.isBlank()) return@withContext
            runCatching {
                val req = Request.Builder().url("$base/config").get().build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val cfg = gson.fromJson(resp.body?.string().orEmpty(), AppConfigDto::class.java)
                        cfg?.squareBaseUrl?.let { AccountConfig.setRemoteSquareBaseUrl(it) }
                    }
                }
            }
        }
    }
}
