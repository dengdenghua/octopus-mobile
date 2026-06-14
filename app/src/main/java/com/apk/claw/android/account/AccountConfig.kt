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

    /** e.g. https://account.octopus.example/api/v1 — empty means "use mock". */
    var baseUrl: String
        get() = KVUtils.getString(KEY_BASE_URL, "")
        set(v) {
            KVUtils.putString(KEY_BASE_URL, v.trim())
        }

    /** Mock when explicitly enabled OR when no base URL is configured yet. */
    val mockMode: Boolean
        get() = KVUtils.getString(KEY_MOCK, "1") == "1" || baseUrl.isBlank()

    fun setMockMode(on: Boolean) {
        KVUtils.putString(KEY_MOCK, if (on) "1" else "0")
    }
}
