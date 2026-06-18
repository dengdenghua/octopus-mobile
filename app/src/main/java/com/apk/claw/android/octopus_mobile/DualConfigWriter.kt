package com.apk.claw.android.octopus_mobile

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.*

/**
 * 方案 F · MMKV ↔ Runtime 配置双写.
 *
 * 同步策略：
 *  - **本地写 → 推远程**：本地 set() 后异步推 config/sync_push 到 Runtime
 *  - **远程变更 → 拉本地**：Runtime 主动推 config/sync_pull_response 时，本地接收并应用
 *  - **冲突解决**：remote_version > local_version → 以远程为准；否则保留本地
 *  - **网络断开缓冲**：离线时的变更暂存到 pendingPushes，连接恢复后批量推送
 *
 * 集成：
 *  - 通过 OctopusMobileClient.onMessage 接收远程配置变更
 *  - 启动时调用 initialSync() 做一次双向同步
 */
class DualConfigWriter(
    context: Context,
    private val client: OctopusMobileClient,
    private val tentacleId: String,
) {
    private val tag = "DualConfigWriter"
    private val gson = Gson()

    private val kv: MMKV = run {
        MMKV.initialize(context)
        MMKV.defaultMMKV()
    }

    /** 配置版本号（每次写入 +1） */
    private val configVersionKey = "__config_version__"

    /** 离线期间累积的待推送变更（key → (value, version)） */
    private val pendingPushes = mutableMapOf<String, ConfigChange>()

    /** 协程作用域 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 接收远程推来的配置变更
        client.onConfigChange = { rawJson ->
            handleIncomingMessage(rawJson)
        }
    }

    // ==================== 本地读写 ====================

    /**
     * 读取配置.
     */
    fun get(key: String): String? = kv.decodeString(key, null)

    /**
     * 写入配置（本地 + 异步推送远程）.
     */
    fun set(key: String, value: String) {
        kv.encode(key, value)
        val newVersion = incrementVersion()
        val change = ConfigChange(key, value, newVersion, System.currentTimeMillis())
        scope.launch {
            pushChange(change)
        }
    }

    /**
     * 获取当前版本号.
     */
    fun getVersion(): Int = kv.decodeInt(configVersionKey, 0)

    /**
     * 获取所有键.
     */
    fun allKeys(): Set<String> = (kv.allKeys() ?: emptyArray())
        .filter { !it.startsWith("__") }
        .toSet()

    /**
     * 清除所有配置.
     */
    fun clearAll() {
        kv.clearAll()
        Log.w(tag, "all config cleared")
    }

    // ==================== 同步 ====================

    /**
     * 启动同步 —— 拉取远程 + 推送本地.
     */
    fun initialSync() {
        scope.launch {
            // 先拉取远程最新配置
            syncPull()
            // 再把本地变更推过去（包括离线期间累积的）
            flushPending()
        }
    }

    /**
     * 拉取远程配置.
     */
    suspend fun syncPull() {
        if (client.currentState() != ConnectionState.ONLINE) return
        val request = Envelope.Request(
            method = "config/sync_pull",
            params = mapOf(
                "tentacle_id" to tentacleId,
                "since_version" to getVersion(),
            ),
            id = "cfg-pull-${java.util.UUID.randomUUID()}",
        )
        try {
            client.send(request)
            Log.d(tag, "config pull request sent (since_version=${getVersion()})")
        } catch (e: Exception) {
            Log.w(tag, "config pull failed: ${e.message}")
        }
    }

    /**
     * 推送所有本地变更.
     */
    suspend fun syncPush() {
        if (client.currentState() != ConnectionState.ONLINE) return
        val changes = allKeys().map { key ->
            ConfigChange(
                key = key,
                value = get(key) ?: "",
                version = getVersion(),
                ts = System.currentTimeMillis(),
            )
        }
        if (changes.isEmpty()) return
        pushAll(changes)
    }

    // ==================== 内部 ====================

    private suspend fun pushChange(change: ConfigChange) {
        if (client.currentState() == ConnectionState.ONLINE) {
            pushAll(listOf(change))
        } else {
            // 离线 → 缓冲
            synchronized(pendingPushes) { pendingPushes[change.key] = change }
        }
    }

    private suspend fun pushAll(changes: List<ConfigChange>) {
        if (changes.isEmpty()) return
        val changesList = changes.map { c ->
            mapOf(
                "key" to c.key,
                "value" to c.value,
                "version" to c.version,
                "ts" to c.ts,
            )
        }
        val request = Envelope.Request(
            method = "config/sync_push",
            params = mapOf(
                "tentacle_id" to tentacleId,
                "changes" to changesList,
            ),
            id = "cfg-push-${java.util.UUID.randomUUID()}",
        )
        try {
            client.send(request)
            Log.d(tag, "config push sent: ${changes.size} changes")
        } catch (e: Exception) {
            Log.w(tag, "config push failed: ${e.message}")
            // 失败也缓冲
            synchronized(pendingPushes) {
                for (c in changes) pendingPushes[c.key] = c
            }
        }
    }

    private fun flushPending() {
        val pending = synchronized(pendingPushes) {
            val list = pendingPushes.values.toList()
            pendingPushes.clear()
            list
        }
        if (pending.isNotEmpty()) {
            scope.launch { pushAll(pending) }
        }
    }

    private fun handleIncomingMessage(rawJson: String) {
        try {
            val root = JsonParser.parseString(rawJson).asJsonObject
            val method = root.get("method")?.asString ?: return
            if (method != "config/sync_pull_response") return

            // 用 Gson 解析 changes 数组，替代手写正则解析
            val changesElement = root.get("changes") ?: return
            if (!changesElement.isJsonArray) return

            val changesType = object : TypeToken<List<ConfigChange>>() {}.type
            val changes: List<ConfigChange> = try {
                gson.fromJson(changesElement, changesType) ?: emptyList()
            } catch (e: Exception) {
                Log.w(tag, "parse changes failed: ${e.message}")
                emptyList()
            }

            val localVersion = getVersion()
            var maxRemoteVersion = localVersion
            var appliedCount = 0

            for (change in changes) {
                // 冲突解决：remote_version > local_version → 以远程为准
                if (change.version > localVersion) {
                    kv.encode(change.key, change.value)
                    appliedCount++
                }
                if (change.version > maxRemoteVersion) {
                    maxRemoteVersion = change.version
                }
            }
            // 更新本地版本号
            if (maxRemoteVersion > localVersion) {
                kv.encode(configVersionKey, maxRemoteVersion)
            }
            if (appliedCount > 0) {
                Log.i(tag, "applied $appliedCount remote config changes (version=$localVersion→$maxRemoteVersion)")
            }
        } catch (e: Exception) {
            Log.w(tag, "handleIncomingMessage error: ${e.message}")
        }
    }

    private fun incrementVersion(): Int {
        val newVersion = getVersion() + 1
        kv.encode(configVersionKey, newVersion)
        return newVersion
    }
}

/** 配置变更条目 */
data class ConfigChange(
    val key: String,
    val value: String,
    val version: Int,
    val ts: Long,
)
