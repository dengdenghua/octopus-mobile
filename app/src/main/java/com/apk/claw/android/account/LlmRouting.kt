package com.apk.claw.android.account

import com.apk.claw.android.utils.KVUtils

/** The LLM endpoint the agent should actually use this turn. */
data class EffectiveLlm(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** true = platform relay (shared MiMo key, deducts credits). */
    val platform: Boolean,
)

/**
 * Decides which model the agent runs on, implementing the product policy:
 *
 *  - **default = platform** relay (shared MiMo key, billed in credits) — "优先用付费体系";
 *  - **BYO own-model** is used only when the user is a member ([AccountStore.byoUnlocked])
 *    AND has explicitly chosen it ([AccountConfig.modelSource] == "byo") AND configured a key;
 *  - the whole policy only kicks in once a relay is configured
 *    ([AccountConfig.relayConfigured]) and the user is signed in — otherwise the app keeps
 *    its legacy local-LLM behavior so it never bricks before the backend exists.
 */
object LlmRouting {
    fun effective(): EffectiveLlm {
        val byoChosen = AccountConfig.modelSource == "byo" &&
            AccountStore.byoUnlocked &&
            KVUtils.hasLlmConfig()
        val usePlatform = AccountConfig.relayConfigured &&
            AccountStore.isLoggedIn &&
            !byoChosen
        return if (usePlatform) {
            EffectiveLlm(
                baseUrl = AccountConfig.platformLlmBaseUrl(),
                apiKey = AccountStore.token,
                model = AccountConfig.platformModel,
                platform = true,
            )
        } else {
            EffectiveLlm(
                baseUrl = KVUtils.getLlmBaseUrl().trim(),
                apiKey = KVUtils.getLlmApiKey(),
                model = KVUtils.getLlmModelName(),
                platform = false,
            )
        }
    }
}
