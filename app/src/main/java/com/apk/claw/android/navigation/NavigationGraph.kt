package com.apk.claw.android.navigation

import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV

/**
 * UI 导航知识图谱 —— 核心数据结构。
 *
 * 节点（NavNode）= 一个 UI 屏幕状态
 * 边（NavEdge）= 一次遥控操作引起的状态转移
 *
 * 持久化到 MMKV，键前缀 "nav."。
 */
class NavigationGraph {

    companion object {
        private const val TAG = "NavigationGraph"
        private const val MMKV_ID = "nav_graph"
        private const val KEY_NODES = "nav.nodes"
        private const val KEY_EDGES_PREFIX = "nav.edges."
        private const val KEY_GOALS_PREFIX = "nav.goals."
        private const val KEY_META = "nav.meta"
        private const val NODE_MERGE_THRESHOLD = 0.85  // 指纹相似度 >= 此值视为同一节点
    }

    private val gson = Gson()
    private val mmkv: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID, MMKV.SINGLE_PROCESS_MODE) }

    // 内存中的图数据
    private val nodes = mutableMapOf<String, NavNode>()
    private val edges = mutableMapOf<String, MutableList<NavEdge>>()  // nodeId -> outgoing

    init {
        loadFromStorage()
    }

    // ======================== 节点操作 ========================

    /**
     * 添加或合并节点。
     * 如果已有相似度 >= 0.85 的节点，合并到已有节点（更新访问计数）。
     * 否则创建新节点。
     *
     * @return 实际使用的节点 ID
     */
    fun addOrMergeNode(fingerprint: StateFingerprint, label: String = ""): String {
        val newId = fingerprint.id

        // 查找是否有可合并的已有节点
        for ((existingId, existingNode) in nodes) {
            val sim = StateDetector.similarity(existingNode.fingerprint, fingerprint)
            if (sim >= NODE_MERGE_THRESHOLD && existingId != newId) {
                // 合并：更新访问计数，保留已有标签
                val merged = existingNode.copy(
                    visitCount = existingNode.visitCount + 1,
                    label = existingNode.label.ifEmpty { label }
                )
                nodes[existingId] = merged
                saveNode(existingId, merged)
                XLog.d(TAG, "Merged node $newId -> $existingId (sim=$sim)")
                return existingId
            }
        }

        // 创建新节点
        val node = NavNode(
            id = newId,
            fingerprint = fingerprint,
            label = label.ifEmpty { fingerprint.packageActivity.substringAfterLast('.') },
            visitCount = 1,
            isGoal = false,
            appPackage = fingerprint.packageActivity.substringBefore('/')
        )
        nodes[newId] = node
        saveNode(newId, node)
        XLog.d(TAG, "New node: $newId (${node.label})")
        return newId
    }

    /**
     * 标记节点为目标节点（如"搜索结果页"）。
     */
    fun markAsGoal(nodeId: String, goalLabel: String) {
        val node = nodes[nodeId] ?: return
        val updated = node.copy(isGoal = true, label = goalLabel)
        nodes[nodeId] = updated
        saveNode(nodeId, updated)
        // 存储 goal 映射
        mmkv.putString("${KEY_GOALS_PREFIX}${node.appPackage}.$goalLabel", nodeId)
    }

    /**
     * 查找目标节点 ID。
     */
    fun findGoalNode(appPackage: String, goalLabel: String): String? {
        return mmkv.getString("${KEY_GOALS_PREFIX}${appPackage}.$goalLabel", null)
    }

    fun getNode(nodeId: String): NavNode? = nodes[nodeId]

    fun getAllNodes(): List<NavNode> = nodes.values.toList()

    // ======================== 边操作 ========================

    /**
     * 添加或更新一条边（状态转移）。
     */
    fun addEdge(fromNodeId: String, action: RemoteAction, toNodeId: String, durationMs: Long) {
        val existingEdges = edges.getOrPut(fromNodeId) { mutableListOf() }

        // 查找是否有同 action 同目标的已有边
        val existing = existingEdges.find { it.action == action && it.to == toNodeId }
        if (existing != null) {
            // 更新权重
            val idx = existingEdges.indexOf(existing)
            val updated = existing.copy(
                successCount = existing.successCount + 1,
                avgDurationMs = (existing.avgDurationMs + durationMs) / 2,
                weight = computeWeight(existing.successCount + 1, existing.failCount, (existing.avgDurationMs + durationMs) / 2)
            )
            existingEdges[idx] = updated
        } else {
            // 新边
            val edge = NavEdge(
                from = fromNodeId,
                to = toNodeId,
                action = action,
                weight = computeWeight(1, 0, durationMs),
                successCount = 1,
                failCount = 0,
                avgDurationMs = durationMs,
                userVariants = setOf("default")
            )
            existingEdges.add(edge)
        }

        saveEdges(fromNodeId, existingEdges)
    }

    /**
     * 标记一条边为失败（checkpoint 校验不通过）。
     */
    fun markEdgeFailed(fromNodeId: String, action: RemoteAction, toNodeId: String) {
        val existingEdges = edges[fromNodeId] ?: return
        val idx = existingEdges.indexOfFirst { it.action == action && it.to == toNodeId }
        if (idx >= 0) {
            val edge = existingEdges[idx]
            existingEdges[idx] = edge.copy(
                failCount = edge.failCount + 1,
                weight = computeWeight(edge.successCount, edge.failCount + 1, edge.avgDurationMs)
            )
            saveEdges(fromNodeId, existingEdges)
        }
    }

    /**
     * 获取一个节点的所有出边。
     */
    fun getOutgoingEdges(nodeId: String): List<NavEdge> {
        return edges[nodeId] ?: emptyList()
    }

    // ======================== 权重计算 ========================

    /**
     * 边的综合权重 = 耗时 × 失败惩罚。
     * 权重越低 = 路线越好。
     *
     * 公式：weight = avgDurationMs * (1 + failCount / max(successCount, 1))
     */
    private fun computeWeight(successCount: Int, failCount: Int, avgDurationMs: Long): Double {
        val failPenalty = 1.0 + failCount.toDouble() / maxOf(successCount, 1)
        return avgDurationMs.toDouble() * failPenalty
    }

    // ======================== 持久化 ========================

    private fun loadFromStorage() {
        try {
            // 加载所有节点
            val nodesJson = mmkv.getString(KEY_NODES, null)
            if (nodesJson != null) {
                val type = object : TypeToken<Map<String, NavNode>>() {}.type
                val loaded: Map<String, NavNode> = gson.fromJson(nodesJson, type)
                nodes.putAll(loaded)
            }

            // 加载所有边
            for (nodeId in nodes.keys) {
                val edgesJson = mmkv.getString("${KEY_EDGES_PREFIX}$nodeId", null)
                if (edgesJson != null) {
                    val type = object : TypeToken<List<NavEdge>>() {}.type
                    val loaded: List<NavEdge> = gson.fromJson(edgesJson, type)
                    edges[nodeId] = loaded.toMutableList()
                }
            }

            XLog.i(TAG, "Graph loaded: ${nodes.size} nodes, ${edges.values.sumOf { it.size }} edges")
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to load graph: ${e.message}")
        }
    }

    private fun saveNode(nodeId: String, node: NavNode) {
        // 保存整个 nodes map
        mmkv.putString(KEY_NODES, gson.toJson(nodes))
    }

    private fun saveEdges(nodeId: String, edgeList: List<NavEdge>) {
        mmkv.putString("${KEY_EDGES_PREFIX}$nodeId", gson.toJson(edgeList))
    }

    fun saveAll() {
        mmkv.putString(KEY_NODES, gson.toJson(nodes))
        for ((nodeId, edgeList) in edges) {
            mmkv.putString("${KEY_EDGES_PREFIX}$nodeId", gson.toJson(edgeList))
        }
    }

    // ======================== 统计 ========================

    fun getNodeCount(): Int = nodes.size
    fun getEdgeCount(): Int = edges.values.sumOf { it.size }

    fun getStats(): Map<String, Any> = mapOf(
        "nodes" to nodes.size,
        "edges" to edges.values.sumOf { it.size },
        "goals" to nodes.values.count { it.isGoal },
        "apps" to nodes.values.map { it.appPackage }.distinct().size
    )
}

// ======================== 数据类 ========================

/**
 * 导航图节点 —— 一个 UI 屏幕状态。
 */
data class NavNode(
    val id: String,
    val fingerprint: StateFingerprint,
    val label: String,
    val visitCount: Int,
    val isGoal: Boolean,
    val appPackage: String
)

/**
 * 导航图边 —— 一次遥控操作引起的状态转移。
 */
data class NavEdge(
    val from: String,
    val to: String,
    val action: RemoteAction,
    val weight: Double,
    val successCount: Int,
    val failCount: Int,
    val avgDurationMs: Long,
    val userVariants: Set<String>
)

/**
 * UI 动作（D-pad 遥控器 + 触屏）。
 */
data class RemoteAction(
    val type: String,       // "dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center", "input_text", "system_key", "tap", "long_press", "swipe"
    val params: Map<String, Any> = emptyMap()  // repeat, text, keycode, x, y, start_x, start_y, end_x, end_y, duration_ms 等
) {
    companion object {
        // D-pad（TV）
        fun dpadUp(repeat: Int = 1) = RemoteAction("dpad_up", mapOf("repeat" to repeat))
        fun dpadDown(repeat: Int = 1) = RemoteAction("dpad_down", mapOf("repeat" to repeat))
        fun dpadLeft(repeat: Int = 1) = RemoteAction("dpad_left", mapOf("repeat" to repeat))
        fun dpadRight(repeat: Int = 1) = RemoteAction("dpad_right", mapOf("repeat" to repeat))
        fun dpadCenter(repeat: Int = 1) = RemoteAction("dpad_center", mapOf("repeat" to repeat))
        fun inputText(text: String) = RemoteAction("input_text", mapOf("text" to text))
        fun systemKey(keycode: Int) = RemoteAction("system_key", mapOf("keycode" to keycode))
        fun back() = RemoteAction("system_key", mapOf("keycode" to 4)) // KEYCODE_BACK
        fun home() = RemoteAction("system_key", mapOf("keycode" to 3)) // KEYCODE_HOME

        // 触屏（Mobile）
        fun tap(x: Int, y: Int) = RemoteAction("tap", mapOf("x" to x, "y" to y))
        fun longPress(x: Int, y: Int) = RemoteAction("long_press", mapOf("x" to x, "y" to y))
        fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int = 500) =
            RemoteAction("swipe", mapOf("start_x" to startX, "start_y" to startY, "end_x" to endX, "end_y" to endY, "duration_ms" to durationMs))
    }

    /** 转换为 ToolRegistry 的工具名和参数 */
    fun toToolCall(): Pair<String, Map<String, Any>> {
        return when (type) {
            "dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center" -> type to params
            "input_text" -> "input_text" to params
            "system_key" -> "system_key" to params
            "tap", "long_press", "swipe" -> type to params
            else -> type to params
        }
    }
}
