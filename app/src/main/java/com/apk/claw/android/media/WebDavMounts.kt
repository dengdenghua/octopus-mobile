package com.apk.claw.android.media

import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 原生 WebDAV 挂载点 —— App 自带的 NAS/网盘挂载，不依赖 CloudDrive2。
 *
 * 任何提供 WebDAV 的服务都可直接挂载并浏览/播放：群晖/威联通 WebDAV、Nextcloud、
 * 坚果云、AList、CloudDrive2 等。播放走 http(s) URL，mpv 直接可放。
 */
object WebDavMounts {

    private const val KEY = "webdav_mounts"
    private val gson = Gson()

    data class Mount(
        val id: String,
        val name: String,
        /** 服务器基址，如 http://192.168.1.10:5005（不含路径） */
        val baseUrl: String,
        /** 起始路径，如 / 或 /dav */
        val rootPath: String = "/",
        val username: String = "",
        val password: String = "",
    )

    fun all(): List<Mount> {
        val json = KVUtils.getString(KEY, "")
        if (json.isEmpty()) return emptyList()
        return try {
            gson.fromJson<List<Mount>>(json, object : TypeToken<List<Mount>>() {}.type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(m: Mount) {
        val list = all().toMutableList()
        list.removeAll { it.id == m.id }
        list.add(m)
        KVUtils.putString(KEY, gson.toJson(list))
    }

    fun remove(id: String) {
        KVUtils.putString(KEY, gson.toJson(all().filterNot { it.id == id }))
    }

    /** 构建带凭据的播放/访问 URL（basic auth 走 URL userinfo，mpv/ffmpeg 可直接用）。 */
    fun playUrl(m: Mount, href: String): String {
        val base = m.baseUrl.trimEnd('/')
        if (m.username.isEmpty()) return base + href
        val scheme = base.substringBefore("://")
        val rest = base.substringAfter("://")
        val u = java.net.URLEncoder.encode(m.username, "UTF-8")
        val p = java.net.URLEncoder.encode(m.password, "UTF-8")
        return "$scheme://$u:$p@$rest$href"
    }
}
