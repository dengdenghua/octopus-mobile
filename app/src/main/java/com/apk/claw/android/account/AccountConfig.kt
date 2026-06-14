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
    private const val KEY_PLATFORM_MODEL = "ACCOUNT_PLATFORM_MODEL"

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

    /** Default model name the platform relay serves. */
    var platformModel: String
        get() = KVUtils.getString(KEY_PLATFORM_MODEL, "mimo-v2.5")
        set(v) {
            KVUtils.putString(KEY_PLATFORM_MODEL, v)
        }
}
