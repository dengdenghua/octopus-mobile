package com.apk.claw.android.navigation

import com.apk.claw.android.utils.XLog
import java.util.PriorityQueue

/**
 * A* 寻路算法 —— 在导航图中找到从起点到目标的最优路径。
 *
 * 启发函数 h(n) = 0（退化为 Dijkstra），因为无法预知 UI 状态间的"距离"。
 * 实际效果等同于 Dijkstra 最短路径，但保留了 A* 的扩展性。
 *
 * 路径权重 = 边的综合权重（耗时 × 失败惩罚），越低越好。
 */
object PathFinder {

    private const val TAG = "PathFinder"

    /**
     * 在图中搜索从 startNodeId 到 goalNodeId 的最优路径。
     *
     * @param graph 导航图
     * @param startNodeId 起始节点 ID
     * @param goalNodeId 目标节点 ID
     * @return 最优路径（节点 ID 列表 + 边列表），不可达时返回 null
     */
    fun findPath(
        graph: NavigationGraph,
        startNodeId: String,
        goalNodeId: String
    ): NavigationPath? {
        if (startNodeId == goalNodeId) {
            return NavigationPath(listOf(startNodeId), emptyList(), 0.0)
        }

        // A* 开放列表（优先队列，按 f 值排序）
        val openSet = PriorityQueue<AStarNode>(compareBy { it.f })
        val closedSet = mutableSetOf<String>()
        val cameFrom = mutableMapOf<String, String>()      // nodeId -> previous nodeId
        val cameFromEdge = mutableMapOf<String, NavEdge>() // nodeId -> edge that led here
        val gScore = mutableMapOf<String, Double>()         // nodeId -> best known cost from start

        gScore[startNodeId] = 0.0
        openSet.add(AStarNode(startNodeId, f = 0.0, g = 0.0))

        var iterations = 0
        val maxIterations = 10000  // 防止无限循环

        while (openSet.isNotEmpty() && iterations < maxIterations) {
            iterations++
            val current = openSet.poll()

            if (current.nodeId == goalNodeId) {
                // 回溯路径
                return reconstructPath(cameFrom, cameFromEdge, goalNodeId, gScore[goalNodeId] ?: 0.0)
            }

            if (current.nodeId in closedSet) continue
            closedSet.add(current.nodeId)

            // 遍历邻居
            for (edge in graph.getOutgoingEdges(current.nodeId)) {
                if (edge.to in closedSet) continue
                if (edge.failCount > edge.successCount * 2) continue  // 跳过失败率过高的边

                val tentativeG = (gScore[current.nodeId] ?: Double.MAX_VALUE) + edge.weight

                if (tentativeG < (gScore[edge.to] ?: Double.MAX_VALUE)) {
                    gScore[edge.to] = tentativeG
                    cameFrom[edge.to] = current.nodeId
                    cameFromEdge[edge.to] = edge
                    val f = tentativeG  // h(n) = 0
                    openSet.add(AStarNode(edge.to, f = f, g = tentativeG))
                }
            }
        }

        XLog.w(TAG, "No path found from $startNodeId to $goalNodeId (iterations=$iterations)")
        return null
    }

    /**
     * 搜索到指定 App 的任意目标节点的路径。
     *
     * @param graph 导航图
     * @param startNodeId 起始节点 ID
     * @param appPackage 目标 App 包名
     * @param goalLabel 目标标签（如"搜索结果页"），null 表示任意目标
     */
    fun findPathToApp(
        graph: NavigationGraph,
        startNodeId: String,
        appPackage: String,
        goalLabel: String? = null
    ): NavigationPath? {
        // 如果有精确的 goal 标签，先找精确匹配
        if (goalLabel != null) {
            val goalNodeId = graph.findGoalNode(appPackage, goalLabel)
            if (goalNodeId != null) {
                findPath(graph, startNodeId, goalNodeId)?.let { return it }
            }
        }

        // 否则找该 App 的所有 goal 节点，取最短路径
        val appGoals = graph.getAllNodes().filter { it.appPackage == appPackage && it.isGoal }
        var bestPath: NavigationPath? = null
        for (goal in appGoals) {
            val path = findPath(graph, startNodeId, goal.id)
            if (path != null && (bestPath == null || path.totalWeight < bestPath.totalWeight)) {
                bestPath = path
            }
        }

        // 如果没有 goal 节点，找该 App 访问量最高的节点
        if (bestPath == null) {
            val appNodes = graph.getAllNodes()
                .filter { it.appPackage == appPackage }
                .sortedByDescending { it.visitCount }
            for (node in appNodes.take(5)) {
                val path = findPath(graph, startNodeId, node.id)
                if (path != null) {
                    bestPath = path
                    break
                }
            }
        }

        return bestPath
    }

    /**
     * 回溯路径。
     */
    private fun reconstructPath(
        cameFrom: Map<String, String>,
        cameFromEdge: Map<String, NavEdge>,
        goalNodeId: String,
        totalWeight: Double
    ): NavigationPath {
        val nodeIds = mutableListOf<String>()
        val edgeList = mutableListOf<NavEdge>()
        var current = goalNodeId

        while (current in cameFrom) {
            nodeIds.add(0, current)
            cameFromEdge[current]?.let { edgeList.add(0, it) }
            current = cameFrom[current]!!
        }
        nodeIds.add(0, current)  // 起始节点

        return NavigationPath(nodeIds, edgeList, totalWeight)
    }
}

/**
 * A* 搜索中的节点。
 */
private data class AStarNode(
    val nodeId: String,
    val f: Double,  // f = g + h
    val g: Double   // g = 从起点到此的实际代价
)

/**
 * 导航路径 —— A* 搜索的结果。
 */
data class NavigationPath(
    val nodeIds: List<String>,
    val edges: List<NavEdge>,
    val totalWeight: Double
) {
    /** 路径步数 */
    val stepCount: Int get() = edges.size

    /** 预估总耗时（毫秒） */
    val estimatedDurationMs: Long get() = edges.sumOf { it.avgDurationMs }

    /** 路径描述（用于 Agent 返回给用户） */
    fun describe(): String {
        if (edges.isEmpty()) return "Already at destination"
        val steps = edges.mapIndexed { i, edge ->
            "${i + 1}. ${edge.action.type}${edge.action.params}"
        }
        return "Path (${edges.size} steps, ~${estimatedDurationMs}ms):\n${steps.joinToString("\n")}"
    }
}
