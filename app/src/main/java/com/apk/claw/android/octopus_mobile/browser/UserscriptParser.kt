package com.apk.claw.android.octopus_mobile.browser

import android.util.Log

data class Userscript(
    val id: String = "",
    val name: String = "",
    val version: String = "1.0.0",
    val description: String = "",
    val author: String = "",
    val namespace: String = "",
    val match: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val include: List<String> = emptyList(),
    val runAt: RunAt = RunAt.DOCUMENT_END,
    val grants: List<String> = emptyList(),
    val requires: List<String> = emptyList(),
    val resources: Map<String, String> = emptyMap(),
    val icon: String = "",
    val updateURL: String = "",
    val downloadURL: String = "",
    val js: String = "",
    val css: String = "",
    val source: String = "",
) {
    enum class RunAt {
        DOCUMENT_START,
        DOCUMENT_END,
        DOCUMENT_IDLE;

        companion object {
            fun parse(s: String): RunAt = when (s.trim().lowercase()) {
                "document-start" -> DOCUMENT_START
                "document-body", "document-end" -> DOCUMENT_END
                "document-idle", "idle", "" -> DOCUMENT_IDLE
                else -> DOCUMENT_IDLE
            }
        }
    }
}

object UserscriptParser {

    private const val TAG = "UserscriptParser"

    private val HEADER_RE = Regex("""//\s*==UserScript==\s*\n([\s\S]*?)\n\s*//\s*==/UserScript==""")
    private val META_RE = Regex("""^\s*//\s*@(\S+)(?:\s+(.*))?$""", RegexOption.MULTILINE)

    fun parse(source: String, fallbackId: String = ""): Userscript? {
        val headerMatch = HEADER_RE.find(source) ?: return null
        val headerBlock = headerMatch.groupValues[1]

        val meta = mutableMapOf<String, MutableList<String>>()
        META_RE.findAll(headerBlock).forEach { m ->
            val key = m.groupValues[1].trim()
            val value = m.groupValues[2].trim()
            meta.getOrPut(key) { mutableListOf() }.add(value)
        }

        fun single(key: String): String = meta[key]?.firstOrNull() ?: ""
        fun multi(key: String): List<String> = meta[key] ?: emptyList()

        val js = source.substring(headerMatch.range.last + 1).trim()

        val id = single("name").ifBlank { fallbackId }.let { slugify(it) }
        val name = single("name").ifBlank { fallbackId }

        return Userscript(
            id = id.ifBlank { fallbackId },
            name = name.ifBlank { "Unnamed Script" },
            version = single("version").ifBlank { "1.0.0" },
            description = single("description"),
            author = single("author"),
            namespace = single("namespace"),
            match = multi("match"),
            exclude = multi("exclude"),
            include = multi("include"),
            runAt = Userscript.RunAt.parse(single("run-at")),
            grants = multi("grant").filter { it.isNotBlank() && it != "none" },
            requires = multi("require").filter { it.isNotBlank() },
            resources = parseResources(multi("resource")),
            icon = single("icon"),
            updateURL = single("updateURL").ifBlank { single("updateurl") },
            downloadURL = single("downloadURL").ifBlank { single("downloadurl") },
            js = js,
            source = source,
        )
    }

    private fun parseResources(lines: List<String>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (line in lines) {
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                map[parts[0]] = parts[1]
            }
        }
        return map
    }

    fun matchPatternToRegex(pattern: String): Regex? {
        if (pattern.isBlank() || pattern == "*" || pattern == "*://*/*") return null

        val p = pattern.trim()
        val schemeIdx = p.indexOf("://")
        val scheme: String
        val rest: String
        if (schemeIdx >= 0) {
            scheme = p.substring(0, schemeIdx)
            rest = p.substring(schemeIdx + 3)
        } else {
            scheme = "*"
            rest = p
        }

        val slashIdx = rest.indexOf('/')
        val host: String
        val path: String
        if (slashIdx >= 0) {
            host = rest.substring(0, slashIdx)
            path = rest.substring(slashIdx)
        } else {
            host = rest
            path = "/*"
        }

        val sb = StringBuilder("^")

        when {
            scheme == "*" -> sb.append("https?://")
            scheme == "http" -> sb.append("http://")
            scheme == "https" -> sb.append("https://")
            scheme == "file" -> sb.append("file://")
            else -> sb.append(Regex.escape(scheme)).append("://")
        }

        if (host == "*") {
            sb.append("[^/]+")
        } else if (host.startsWith("*.")) {
            sb.append("(?:[^/]+\\.)?").append(Regex.escape(host.removePrefix("*.")))
        } else if (host.contains("*")) {
            val hostRegex = host.split("*").joinToString("") { Regex.escape(it) }
                .let { it.replace("\\*", "[^/]*") }
            sb.append(hostRegex)
        } else {
            sb.append(Regex.escape(host))
        }

        val portIdx = sb.indexOf(':')
        if (path == "/*" || path == "/" || path == "/**" || path == "*") {
            if (portIdx < 0) {
                sb.append("(?::\\d+)?")
            }
            sb.append("(?:/.*)?")
        } else {
            val pathRegex = path.split("*").joinToString("") { Regex.escape(it) }
                .replace("\\*", ".*")
            sb.append(pathRegex)
        }

        sb.append("$")
        return runCatching { Regex(sb.toString(), RegexOption.IGNORE_CASE) }
            .onFailure { Log.w(TAG, "bad match pattern: $pattern -> $sb") }
            .getOrNull()
    }

    fun matchesUrl(script: Userscript, url: String): Boolean {
        if (script.match.isEmpty() && script.include.isEmpty()) return false

        for (ex in script.exclude) {
            val r = matchPatternToRegex(ex) ?: continue
            if (r.containsMatchIn(url)) return false
        }

        for (m in script.match) {
            val r = matchPatternToRegex(m)
            if (r == null || r.containsMatchIn(url)) return true
        }
        for (inc in script.include) {
            val r = matchPatternToRegex(inc)
            if (r == null || r.containsMatchIn(url)) return true
        }

        return false
    }

    private fun slugify(s: String): String {
        return s.lowercase()
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fa5]+"), "-")
            .trim('-')
            .ifBlank { "script" }
    }
}
