package com.apk.claw.android.utils

import android.content.Context
import com.tencent.mmkv.MMKV

/**
 * MMKV 键值存储工具类
 *
 * 使用方式：
 *   // 在 Application.onCreate 中初始化
 *   KVUtils.init(context)
 *
 *   // 存取数据
 *   KVUtils.putString("key", "value")
 *   val value = KVUtils.getString("key", "default")
 */
object KVUtils {


    // 钉钉配置
    const val KEY_DINGTALK_APP_KEY = "DEFAULT_DINGTALK_APP_KEY"
    const val KEY_DINGTALK_APP_SECRET = "DEFAULT_DINGTALK_APP_SECRET"
    // 飞书配置
    const val KEY_FEISHU_APP_ID = "DEFAULT_FEISHU_APP_ID"
    const val KEY_FEISHU_APP_SECRET = "DEFAULT_FEISHU_APP_SECRET"
    // QQ 机器人配置
    const val KEY_QQ_APP_ID = "DEFAULT_QQ_APP_ID"
    const val KEY_QQ_APP_SECRET = "DEFAULT_QQ_APP_SECRET"
    // Discord 机器人配置
    const val KEY_DISCORD_BOT_TOKEN = "DEFAULT_DISCORD_BOT_TOKEN"
    // Telegram 机器人配置
    const val KEY_TELEGRAM_BOT_TOKEN = "DEFAULT_TELEGRAM_BOT_TOKEN"
    // 微信 iLink Bot 配置
    const val KEY_WECHAT_BOT_TOKEN = "DEFAULT_WECHAT_BOT_TOKEN"
    const val KEY_WECHAT_API_BASE_URL = "DEFAULT_WECHAT_API_BASE_URL"
    const val KEY_WECHAT_UPDATES_CURSOR = "DEFAULT_WECHAT_UPDATES_CURSOR"

    // ── Octopus Mobile 方案 F 配置 ──
    /** octopus-agent Runtime WebSocket URL —— 例 ws://192.168.1.10:8765 */
    const val KEY_OCTOPUS_RPC_URL = "DEFAULT_OCTOPUS_RPC_URL"
    /** 母体认证 token（可选，留空则无认证） */
    const val KEY_OCTOPUS_AUTH_TOKEN = "DEFAULT_OCTOPUS_AUTH_TOKEN"
    /** 决策层强制模式：空=自动 / EXECUTOR_ONLY / LOCAL_FALLBACK */
    const val KEY_OCTOPUS_BRAIN_MODE = "DEFAULT_OCTOPUS_BRAIN_MODE"
    /** 启动时是否自动连接 Runtime */
    const val KEY_OCTOPUS_AUTO_CONNECT = "DEFAULT_OCTOPUS_AUTO_CONNECT"

    private lateinit var mmkv: MMKV
    private val disabledToolsFallback = mutableSetOf<String>()
    private val stringFallback = mutableMapOf<String, String>()

    private const val DEFAULT_INT = 0
    private const val DEFAULT_LONG = 0L
    private const val DEFAULT_BOOL = false
    private const val DEFAULT_FLOAT = 0f
    private const val DEFAULT_DOUBLE = 0.0

    /**
     * 在 Application.onCreate 中调用初始化
     */
    fun init(context: Context) {
        MMKV.initialize(context)
        mmkv = MMKV.defaultMMKV()
    }

    // ==================== String ====================
    fun putString(key: String, value: String?): Boolean {
        if (!::mmkv.isInitialized) {
            if (value == null) stringFallback.remove(key) else stringFallback[key] = value
            return true
        }
        return mmkv.encode(key, value)
    }

    fun getString(key: String, defaultValue: String = ""): String {
        if (!::mmkv.isInitialized) {
            return stringFallback[key] ?: defaultValue
        }
        return mmkv.decodeString(key, defaultValue) ?: defaultValue
    }

    // ==================== Int ====================
    fun putInt(key: String, value: Int): Boolean {
        return mmkv.encode(key, value)
    }

    fun getInt(key: String, defaultValue: Int = DEFAULT_INT): Int {
        return mmkv.decodeInt(key, defaultValue)
    }

    // ==================== Long ====================
    fun putLong(key: String, value: Long): Boolean {
        return mmkv.encode(key, value)
    }

    fun getLong(key: String, defaultValue: Long = DEFAULT_LONG): Long {
        return mmkv.decodeLong(key, defaultValue)
    }

    // ==================== Boolean ====================
    fun putBoolean(key: String, value: Boolean): Boolean {
        return mmkv.encode(key, value)
    }

    fun getBoolean(key: String, defaultValue: Boolean = DEFAULT_BOOL): Boolean {
        return mmkv.decodeBool(key, defaultValue)
    }

    // ==================== Float ====================
    fun putFloat(key: String, value: Float): Boolean {
        return mmkv.encode(key, value)
    }

    fun getFloat(key: String, defaultValue: Float = DEFAULT_FLOAT): Float {
        return mmkv.decodeFloat(key, defaultValue)
    }

    // ==================== Double ====================
    fun putDouble(key: String, value: Double): Boolean {
        return mmkv.encode(key, value)
    }

    fun getDouble(key: String, defaultValue: Double = DEFAULT_DOUBLE): Double {
        return mmkv.decodeDouble(key, defaultValue)
    }

    // ==================== Bytes ====================
    fun putBytes(key: String, value: ByteArray?): Boolean {
        return mmkv.encode(key, value)
    }

    fun getBytes(key: String): ByteArray? {
        return mmkv.decodeBytes(key)
    }

    // ==================== 常用操作 ====================
    fun contains(key: String): Boolean {
        return mmkv.containsKey(key)
    }

    fun remove(key: String) {
        mmkv.removeValueForKey(key)
    }

    fun remove(vararg keys: String) {
        mmkv.removeValuesForKeys(keys)
    }

    fun clear() {
        mmkv.clearAll()
    }

    fun getAllKeys(): Array<String> {
        return mmkv.allKeys() ?: emptyArray()
    }

    /**
     * 同步写入磁盘（默认是异步的）
     */
    fun sync() {
        mmkv.sync()
    }


    // ==================== 引导页 ====================
    private const val KEY_GUIDE_SHOWN = "KEY_GUIDE_SHOWN"

    fun isGuideShown(): Boolean = getBoolean(KEY_GUIDE_SHOWN, false)

    fun setGuideShown(shown: Boolean) = putBoolean(KEY_GUIDE_SHOWN, shown)

    // ==================== 局域网远程控制 ====================
    // 是否允许本机被局域网其他设备控制（开启后才在 beacon 广播控制 token）。默认关闭，更安全。
    private const val KEY_LAN_CONTROL = "KEY_LAN_CONTROL_ENABLED"

    fun isLanControlEnabled(): Boolean = getBoolean(KEY_LAN_CONTROL, false)

    fun setLanControlEnabled(enabled: Boolean) = putBoolean(KEY_LAN_CONTROL, enabled)

    // ==================== 搜索引擎 ====================
    private const val KEY_SEARCH_ENGINE = "KEY_SEARCH_ENGINE"

    fun getSearchEngine(): String = getString(KEY_SEARCH_ENGINE, "google")

    fun setSearchEngine(id: String) = putString(KEY_SEARCH_ENGINE, id)

    /** 明亮主题开关(默认 false=深色)。OctopusColors.isLight 启动时据此初始化。 */
    fun isLightTheme(): Boolean = getBoolean("KEY_LIGHT_THEME", false)
    fun setLightTheme(light: Boolean) = putBoolean("KEY_LIGHT_THEME", light)

    /**
     * 主题模式（三态，统一 Compose 与 XML 的暗色模式触发源）。
     *
     * - null  ：跟随系统（默认，新行为）
     * - true  ：强制亮色
     * - false ：强制暗色
     *
     * 迁移策略：若旧的 KEY_LIGHT_THEME 已被用户设置过，首次读取时迁移为对应的强制模式；
     * 未设置过则返回 null（跟随系统）。
     */
    fun getThemeMode(): Boolean? {
        // 使用 contains 区分"未设置"和"设置为 false"
        if (contains("KEY_THEME_MODE")) {
            return getBoolean("KEY_THEME_MODE", true)
        }
        // 迁移旧偏好
        if (contains("KEY_LIGHT_THEME")) {
            return getBoolean("KEY_LIGHT_THEME", false)
        }
        return null
    }

    fun setThemeMode(light: Boolean?) {
        if (light == null) {
            remove("KEY_THEME_MODE")
        } else {
            putBoolean("KEY_THEME_MODE", light)
        }
    }

    // ==================== 钉钉配置 ====================
    fun getDingtalkAppKey(): String = getString(KEY_DINGTALK_APP_KEY, "")
    fun setDingtalkAppKey(value: String) = putString(KEY_DINGTALK_APP_KEY, value)
    fun getDingtalkAppSecret(): String = getString(KEY_DINGTALK_APP_SECRET, "")
    fun setDingtalkAppSecret(value: String) = putString(KEY_DINGTALK_APP_SECRET, value)

    // ==================== 飞书配置 ====================
    fun getFeishuAppId(): String = getString(KEY_FEISHU_APP_ID, "")
    fun setFeishuAppId(value: String) = putString(KEY_FEISHU_APP_ID, value)
    fun getFeishuAppSecret(): String = getString(KEY_FEISHU_APP_SECRET, "")
    fun setFeishuAppSecret(value: String) = putString(KEY_FEISHU_APP_SECRET, value)

    // ==================== QQ 机器人配置 ====================
    fun getQqAppId(): String = getString(KEY_QQ_APP_ID, "")
    fun setQqAppId(value: String) = putString(KEY_QQ_APP_ID, value)
    fun getQqAppSecret(): String = getString(KEY_QQ_APP_SECRET, "")
    fun setQqAppSecret(value: String) = putString(KEY_QQ_APP_SECRET, value)

    // ==================== Discord 机器人配置 ====================
    fun getDiscordBotToken(): String = getString(KEY_DISCORD_BOT_TOKEN, "")
    fun setDiscordBotToken(value: String) = putString(KEY_DISCORD_BOT_TOKEN, value)

    // ==================== Telegram 机器人配置 ====================
    fun getTelegramBotToken(): String = getString(KEY_TELEGRAM_BOT_TOKEN, "")
    fun setTelegramBotToken(value: String) = putString(KEY_TELEGRAM_BOT_TOKEN, value)

    // ==================== 微信 iLink Bot 配置 ====================
    fun getWechatBotToken(): String = getString(KEY_WECHAT_BOT_TOKEN, "")
    fun setWechatBotToken(value: String) = putString(KEY_WECHAT_BOT_TOKEN, value)
    fun getWechatApiBaseUrl(): String = getString(KEY_WECHAT_API_BASE_URL, "")
    fun setWechatApiBaseUrl(value: String) = putString(KEY_WECHAT_API_BASE_URL, value)
    fun getWechatUpdatesCursor(): String = getString(KEY_WECHAT_UPDATES_CURSOR, "")
    fun setWechatUpdatesCursor(value: String) = putString(KEY_WECHAT_UPDATES_CURSOR, value)

    // ==================== 通道发送者鉴权(ACL) ====================
    // 安全:仅授权用户可驱动 Agent 控制设备。默认启用 + TOFU(首个发送者自动绑定为该通道 owner)。
    private const val KEY_CHANNEL_ACL_ENABLED = "KEY_CHANNEL_ACL_ENABLED"
    fun isChannelAclEnabled(): Boolean = getBoolean(KEY_CHANNEL_ACL_ENABLED, true)
    fun setChannelAclEnabled(enabled: Boolean) = putBoolean(KEY_CHANNEL_ACL_ENABLED, enabled)

    private fun channelAclKey(channel: String) = "KEY_CHANNEL_ACL_$channel"

    /** 指定通道的授权发送者白名单（空=尚未配对）。 */
    fun getChannelAllowedSenders(channel: String): Set<String> =
        getString(channelAclKey(channel), "").split(",").filter { it.isNotBlank() }.toSet()

    fun addChannelAllowedSender(channel: String, senderId: String) {
        if (senderId.isBlank()) return
        val s = getChannelAllowedSenders(channel).toMutableSet()
        if (s.add(senderId)) putString(channelAclKey(channel), s.joinToString(","))
    }

    fun removeChannelAllowedSender(channel: String, senderId: String) {
        val s = getChannelAllowedSenders(channel).toMutableSet()
        if (s.remove(senderId)) putString(channelAclKey(channel), s.joinToString(","))
    }

    /** 清空某通道白名单 —— 让下一个发送者重新成为 owner（重新配对）。 */
    fun clearChannelAllowedSenders(channel: String) = remove(channelAclKey(channel))

    // 是否允许"远程/自动来源"(母体 WS、LAN HTTP、主动规则)调用高危工具。默认 false=拦截(安全)。
    private const val KEY_REMOTE_HIGH_RISK = "KEY_REMOTE_HIGH_RISK_ALLOWED"
    fun isRemoteHighRiskAllowed(): Boolean = getBoolean(KEY_REMOTE_HIGH_RISK, false)
    fun setRemoteHighRiskAllowed(enabled: Boolean) = putBoolean(KEY_REMOTE_HIGH_RISK, enabled)

    // ── 高级自动化模式(满血) ──
    // 专用自动化设备总开关：解除「高危工具来源闸门 + 主动规则高危限制 + 文件工具 /sdcard 沙箱」，
    // 让母体/LAN/主动规则可无确认执行全部高危工具、访问完整文件系统(仍受 shell UID 与注入校验约束)。
    // 默认 false。仅用于你完全掌控的闲置/专用自动化设备。不影响"防外部攻击"类加固(发送者 ACL、密钥脱敏等)。
    private const val KEY_ADVANCED_AUTOMATION = "KEY_ADVANCED_AUTOMATION_MODE"
    fun isAdvancedAutomationMode(): Boolean {
        if (!::mmkv.isInitialized) return false
        return mmkv.decodeBool(KEY_ADVANCED_AUTOMATION, false)
    }
    fun setAdvancedAutomationMode(enabled: Boolean) = putBoolean(KEY_ADVANCED_AUTOMATION, enabled)

    // ==================== 技能(工具)启停 ====================
    private const val KEY_DISABLED_TOOLS = "KEY_DISABLED_TOOLS"
    fun getDisabledTools(): Set<String> {
        if (!::mmkv.isInitialized) return disabledToolsFallback.toSet()
        return getString(KEY_DISABLED_TOOLS, "").split(",").filter { it.isNotBlank() }.toSet()
    }
    fun setToolDisabled(name: String, disabled: Boolean) {
        if (!::mmkv.isInitialized) {
            if (disabled) disabledToolsFallback.add(name) else disabledToolsFallback.remove(name)
            return
        }
        val s = getDisabledTools().toMutableSet()
        if (disabled) s.add(name) else s.remove(name)
        putString(KEY_DISABLED_TOOLS, s.joinToString(","))
    }

    // ==================== 局域网配置服务 ====================
    private const val KEY_CONFIG_SERVER_ENABLED = "KEY_CONFIG_SERVER_ENABLED"
    fun isConfigServerEnabled(): Boolean = getBoolean(KEY_CONFIG_SERVER_ENABLED, false)
    fun setConfigServerEnabled(enabled: Boolean) = putBoolean(KEY_CONFIG_SERVER_ENABLED, enabled)

    private const val KEY_LLM_API_KEY = "KEY_LLM_API_KEY"
    private const val KEY_LLM_BASE_URL = "KEY_LLM_BASE_URL"
    private const val KEY_LLM_MODEL_NAME = "KEY_LLM_MODEL_NAME"

    fun getLlmApiKey(): String = getString(KEY_LLM_API_KEY, "")
    fun setLlmApiKey(value: String) = putString(KEY_LLM_API_KEY, value)
    fun getLlmBaseUrl(): String = getString(KEY_LLM_BASE_URL, "")
    fun setLlmBaseUrl(value: String) = putString(KEY_LLM_BASE_URL, value)
    fun getLlmModelName(): String = getString(KEY_LLM_MODEL_NAME, "")
    fun setLlmModelName(value: String) = putString(KEY_LLM_MODEL_NAME, value)

    /** 是否已配置 LLM（API Key 非空即视为已配置） */
    fun hasLlmConfig(): Boolean = getLlmApiKey().isNotEmpty()

    // ==================== 视觉模型配置（look_at_screen 用） ====================
    // 与主对话模型分开：主模型(deepseek-chat)纯文本省钱，看屏时才走视觉模型(Qwen-VL/GPT-4o/GLM-4V)。
    // 三项留空则回退复用主模型配置（需主模型本身支持图片输入）。
    private const val KEY_VISION_API_KEY = "KEY_VISION_API_KEY"
    private const val KEY_VISION_BASE_URL = "KEY_VISION_BASE_URL"
    private const val KEY_VISION_MODEL_NAME = "KEY_VISION_MODEL_NAME"

    fun getVisionApiKey(): String = getString(KEY_VISION_API_KEY, "")
    fun setVisionApiKey(value: String) = putString(KEY_VISION_API_KEY, value)
    fun getVisionBaseUrl(): String = getString(KEY_VISION_BASE_URL, "")
    fun setVisionBaseUrl(value: String) = putString(KEY_VISION_BASE_URL, value)
    fun getVisionModelName(): String = getString(KEY_VISION_MODEL_NAME, "")
    fun setVisionModelName(value: String) = putString(KEY_VISION_MODEL_NAME, value)

    // ── Octopus Mobile 方案 F 便捷方法 ──
    fun getOctopusRpcUrl(): String = getString(KEY_OCTOPUS_RPC_URL, "")
    fun setOctopusRpcUrl(value: String) = putString(KEY_OCTOPUS_RPC_URL, value)
    fun getOctopusAuthToken(): String = getString(KEY_OCTOPUS_AUTH_TOKEN, "")
    fun setOctopusAuthToken(value: String) = putString(KEY_OCTOPUS_AUTH_TOKEN, value)
    fun getOctopusBrainMode(): String = getString(KEY_OCTOPUS_BRAIN_MODE, "")
    fun setOctopusBrainMode(value: String) = putString(KEY_OCTOPUS_BRAIN_MODE, value)
    fun isOctopusAutoConnect(): Boolean = getBoolean(KEY_OCTOPUS_AUTO_CONNECT, true)
    fun setOctopusAutoConnect(value: Boolean) = putBoolean(KEY_OCTOPUS_AUTO_CONNECT, value)
}
