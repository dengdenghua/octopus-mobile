package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persistent audit log for powerful HTTP/LAN entry points.
 *
 * Strong remote capabilities stay available. This records who called them and
 * what surface was touched, without storing bulky payloads or secrets.
 */
object RemoteAccessLog {

    private const val KEY = "remote_access_log"
    private const val MAX_KEEP = 300
    private val gson = Gson()

    data class Entry(
        val id: String,
        val ts: Long,
        val method: String,
        val uri: String,
        val source: String,
        val action: String,
        val success: Boolean,
        val summary: String,
        val durationMs: Long,
    )

    fun record(entry: Entry) {
        runCatching {
            val list = all().toMutableList()
            list.add(0, entry)
            val trimmed = if (list.size > MAX_KEEP) list.subList(0, MAX_KEEP) else list
            KVUtils.putString(KEY, gson.toJson(trimmed))
        }
    }

    fun all(): List<Entry> {
        return runCatching {
            val json = KVUtils.getString(KEY, "")
            if (json.isEmpty()) return emptyList()
            val type = object : TypeToken<List<Entry>>() {}.type
            gson.fromJson<List<Entry>>(json, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun clear() {
        runCatching { KVUtils.putString(KEY, "") }
    }
}
