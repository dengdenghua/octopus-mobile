package com.apk.claw.android.account

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Talks to octopus's own account server ([AccountConfig.baseUrl]) over JSON.
 * The bearer token (from login) is attached to authenticated calls. This class
 * makes no assumptions about which SMS / payment vendor the server uses — it
 * only speaks the [AccountGateway] JSON contract.
 */
class HttpAccountGateway(baseUrl: String) : AccountGateway {

    private val base = baseUrl.trimEnd('/')
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun url(path: String) = base + path

    private suspend fun <T> post(
        path: String,
        body: Any?,
        token: String?,
        clazz: Class<T>,
    ): T = withContext(Dispatchers.IO) {
        val json = gson.toJson(body ?: emptyMap<String, Any>())
        val builder = Request.Builder()
            .url(url(path))
            .post(json.toRequestBody(JSON))
        if (!token.isNullOrEmpty()) builder.header("Authorization", "Bearer $token")
        exec(builder.build(), clazz)
    }

    private suspend fun <T> get(
        path: String,
        token: String?,
        clazz: Class<T>,
    ): T = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url(path)).get()
        if (!token.isNullOrEmpty()) builder.header("Authorization", "Bearer $token")
        exec(builder.build(), clazz)
    }

    private fun <T> exec(req: Request, clazz: Class<T>): T {
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("HTTP ${resp.code}: ${text.take(200)}")
            }
            return gson.fromJson(text, clazz)
        }
    }

    override suspend fun sendSmsCode(mobile: String): SmsSendResult =
        post("/auth/sms/send", mapOf("mobile" to mobile), null, SmsSendResult::class.java)

    override suspend fun login(mobile: String, code: String): LoginResult =
        post("/auth/sms/login", mapOf("mobile" to mobile, "code" to code), null, LoginResult::class.java)

    override suspend fun sendEmailCode(email: String): SmsSendResult =
        post("/auth/email/send", mapOf("email" to email), null, SmsSendResult::class.java)

    override suspend fun loginEmail(email: String, code: String): LoginResult =
        post("/auth/email/login", mapOf("email" to email, "code" to code), null, LoginResult::class.java)

    override suspend fun profile(token: String): AccountProfile =
        get("/account/profile", token, AccountProfile::class.java)

    override suspend fun balance(token: String): BalanceResult =
        get("/account/balance", token, BalanceResult::class.java)

    override suspend fun goods(token: String): GoodsList =
        get("/billing/goods", token, GoodsList::class.java)

    override suspend fun createOrder(token: String, goodsId: String): CreateOrderResult =
        post("/billing/orders", mapOf("goodsId" to goodsId), token, CreateOrderResult::class.java)

    override suspend fun queryOrder(token: String, orderNo: String): OrderStatusResult =
        get("/billing/orders/$orderNo", token, OrderStatusResult::class.java)

    override suspend fun dailyClaim(token: String): DailyClaimResult =
        post("/account/daily-claim", null, token, DailyClaimResult::class.java)

    override suspend fun inviteInfo(token: String): InviteInfo =
        get("/invite/info", token, InviteInfo::class.java)

    override suspend fun redeemInvite(token: String, code: String): RedeemResult =
        post("/invite/redeem", mapOf("code" to code), token, RedeemResult::class.java)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaTypeOrNull()
    }
}
