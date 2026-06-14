package com.apk.claw.android.account

import com.apk.claw.android.utils.KVUtils

/**
 * Account/billing backend configuration.
 *
 * Defaults to MOCK so the whole login + wallet + recharge flow runs end-to-end
 * with no server. Point [baseUrl] at octopus's own account server later — which
 * SMS / payment provider sits behind it is a server-side concern, invisible to
 * the app.
 */
object AccountConfig {
    private const val KEY_BASE_URL = "ACCOUNT_BASE_URL"
    private const val KEY_MOCK = "ACCOUNT_MOCK_MODE"
    private const val KEY_MODEL_SOURCE = "ACCOUNT_MODEL_SOURCE"
    private const val KEY_MODEL_TIER = "ACCOUNT_MODEL_TIER"

    /** 用户可见的对话档位(不暴露底层模型名)。fast=极速档(便宜·快)/ premium=高级档(更强·更耗积分)。 */
    const val TIER_FAST = "fast"
    const val TIER_PREMIUM = "premium"

    /** Account/relay backend. Defaults to the live server; empty → mock. */
    var baseUrl: String
        get() = KVUtils.getString(KEY_BASE_URL, "https://api.octoapk.com")
        set(v) {
            KVUtils.putString(KEY_BASE_URL, v.trim())
        }

    /** Mock only when explicitly enabled OR when no base URL is configured. */
    val mockMode: Boolean
        get() = KVUtils.getString(KEY_MOCK, "0") == "1" || baseUrl.isBlank()

    fun setMockMode(on: Boolean) {
        KVUtils.putString(KEY_MOCK, if (on) "1" else "0")
    }

    /**
     * The account/LLM-routing policy ("platform MiMo by default, BYO only for
     * members") only takes effect once a real relay server is configured. Before
     * that the app keeps its legacy local-LLM behavior so it never bricks.
     */
    val relayConfigured: Boolean
        get() = baseUrl.isNotBlank()

    /** OpenAI-compatible base of the relay (where the shared MiMo key lives). */
    fun platformLlmBaseUrl(): String = baseUrl.trimEnd('/') + "/v1"

    /** "platform" (relay + MiMo + credits, default) or "byo" (user's own model). */
    var modelSource: String
        get() = KVUtils.getString(KEY_MODEL_SOURCE, "platform")
        set(v) {
            KVUtils.putString(KEY_MODEL_SOURCE, v)
        }

    /** 用户选的对话档位(默认极速)。只存档位,不存模型名。 */
    var modelTier: String
        get() = KVUtils.getString(KEY_MODEL_TIER, TIER_FAST)
        set(v) {
            KVUtils.putString(KEY_MODEL_TIER, if (v == TIER_PREMIUM) TIER_PREMIUM else TIER_FAST)
        }

    /** 档位 → 实际平台模型 id(仅内部用,UI 永不暴露)。fast=极速(对平台零成本上游)/ premium=高级。
     *  服务端目录若调整模型,只需改这里映射。 */
    val platformModel: String
        get() = if (modelTier == TIER_PREMIUM) "mimo-v2.5-pro" else "agnes-2.0-flash"
}
