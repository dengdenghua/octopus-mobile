package com.apk.claw.android.tool.impl

import com.apk.claw.android.navigation.*
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * 导航图谱工具 —— AI Agent 操作 UI 导航知识图谱。
 *
 * 支持操作：
 * - record_start: 开始录制操作路线
 * - record_stop: 停止录制并保存路线
 * - navigate: 自动导航到目标（A* 寻路 + 执行）
 * - list_nodes: 列出图谱中已知的所有节点
 * - graph_stats: 获取图谱统计信息
 * - passive_on / passive_off: 开启/关闭被动学习
 * - current_state: 检测当前 UI 状态指纹
 */
class NavigateTool : BaseTool() {

    companion object {
        /** 全局单例的导航图谱 */
        @Volatile
        private var _graph: NavigationGraph? = null
        val graph: NavigationGraph
            get() = _graph ?: synchronized(this) {
                _graph ?: NavigationGraph().also { _graph = it }
            }

        @Volatile
        private var _recorder: NavigationRecorder? = null
        val recorder: NavigationRecorder
            get() = _recorder ?: synchronized(this) {
                _recorder ?: NavigationRecorder(graph).also { _recorder = it }
            }
    }

    override fun getName(): String = "navigate"

    override fun getDisplayName(): String = if (useChineseDescription) "智能导航" else "Smart Navigate"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "action",
            "string",
            "Action: 'record_start', 'record_stop', 'navigate', 'list_nodes', 'graph_stats', 'passive_on', 'passive_off', 'current_state'.",
            true
        ),
        ToolParameter(
            "name",
            "string",
            "Route name for record_start, or goal label for navigate (e.g. 'Netflix search').",
            false
        ),
        ToolParameter(
            "app_package",
            "string",
            "Target app package for navigate (e.g. 'com.netflix.ninja').",
            false
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val action = requireString(params, "action")

        return when (action) {
            "record_start" -> {
                val name = optionalString(params, "name", "route_${System.currentTimeMillis()}")
                recorder.startRecording(name)
                ToolResult.success("Recording started: $name. Use remote control normally, then call record_stop.")
            }

            "record_stop" -> {
                if (!recorder.isRecording()) {
                    return ToolResult.error("No recording in progress.")
                }
                val result = recorder.stopRecording()
                if (result.success) {
                    ToolResult.success("Route saved: '${result.routeName}' (${result.stepCount} steps, ${result.nodeCount} nodes)")
                } else {
                    ToolResult.error(result.routeName)
                }
            }

            "navigate" -> {
                val appPackage = optionalString(params, "app_package", "")
                val goalLabel = optionalString(params, "name", "")

                if (appPackage.isEmpty() && goalLabel.isEmpty()) {
                    return ToolResult.error("Either 'app_package' or 'name' (goal label) is required for navigate.")
                }

                // 1. 检测当前状态
                val currentState = StateDetector.detectCurrentState()
                    ?: return ToolResult.error("Cannot detect current UI state. Is AccessibilityService running?")
                val startNodeId = graph.addOrMergeNode(currentState)

                // 2. A* 寻路
                val path = if (appPackage.isNotEmpty()) {
                    PathFinder.findPathToApp(graph, startNodeId, appPackage, goalLabel.ifEmpty { null })
                } else {
                    // 按 goal 标签在所有节点中搜索
                    val goalNodes = graph.getAllNodes().filter {
                        it.isGoal && (goalLabel.isEmpty() || it.label.contains(goalLabel, ignoreCase = true))
                    }
                    var best: NavigationPath? = null
                    for (goal in goalNodes) {
                        val p = PathFinder.findPath(graph, startNodeId, goal.id)
                        if (p != null && (best == null || p.totalWeight < best.totalWeight)) {
                            best = p
                        }
                    }
                    best
                }

                if (path == null) {
                    return ToolResult.error("No path found to ${appPackage.ifEmpty { goalLabel }}. The navigation graph may not have learned this route yet. Try record_start to teach it.")
                }

                // 3. 执行路径
                val executor = NavigationExecutor(graph, com.apk.claw.android.tool.ToolRegistry.getInstance())
                val result = executor.execute(path)
                if (result.success) {
                    ToolResult.success("Navigation completed: ${result.stepsExecuted} steps, ${result.replanCount} replans. ${result.message}")
                } else {
                    ToolResult.error("Navigation failed at step ${result.stepsExecuted}: ${result.message}")
                }
            }

            "list_nodes" -> {
                val nodes = graph.getAllNodes()
                if (nodes.isEmpty()) {
                    return ToolResult.success("Navigation graph is empty. Use record_start to teach routes, or passive_on to learn from daily usage.")
                }
                val lines = nodes.sortedByDescending { it.visitCount }.take(30).map { node ->
                    val goalMark = if (node.isGoal) " ★" else ""
                    "  ${node.label}$goalMark (${node.appPackage}, visits=${node.visitCount}, id=${node.id.take(8)})"
                }
                ToolResult.success("Navigation graph (${graph.getNodeCount()} nodes, ${graph.getEdgeCount()} edges):\n${lines.joinToString("\n")}")
            }

            "graph_stats" -> {
                val stats = graph.getStats()
                ToolResult.success("Navigation graph statistics:\n" +
                    "  Nodes: ${stats["nodes"]}\n" +
                    "  Edges: ${stats["edges"]}\n" +
                    "  Goal nodes: ${stats["goals"]}\n" +
                    "  Apps: ${stats["apps"]}\n" +
                    "  Passive learning: ${if (recorder.isPassiveMode()) "ON" else "OFF"}\n" +
                    "  Recording: ${if (recorder.isRecording()) "YES" else "NO"}")
            }

            "passive_on" -> {
                recorder.setPassiveMode(true)
                ToolResult.success("Passive learning enabled. The agent will automatically learn from your remote control usage.")
            }

            "passive_off" -> {
                recorder.setPassiveMode(false)
                ToolResult.success("Passive learning disabled.")
            }

            "current_state" -> {
                val state = StateDetector.detectCurrentState()
                    ?: return ToolResult.error("Cannot detect current state. Is AccessibilityService running?")
                ToolResult.success("Current UI state:\n" +
                    "  App: ${state.packageActivity}\n" +
                    "  Tree hash: ${state.uiTreeHash}\n" +
                    "  Key elements: ${state.keyElements.joinToString(", ")}\n" +
                    "  Fingerprint ID: ${state.id}")
            }

            else -> ToolResult.error("Unknown action: $action. Use 'record_start', 'record_stop', 'navigate', 'list_nodes', 'graph_stats', 'passive_on', 'passive_off', or 'current_state'.")
        }
    }

    override fun getDescriptionEN(): String = """
        UI Navigation Knowledge Graph — learn, store, and auto-navigate TV and mobile interfaces.

        Supports both D-pad (TV) and touch (mobile) navigation:
        - TV: D-pad operations (dpad_up/down/left/right/center) recorded from remote key events
        - Mobile: Touch operations (tap/swipe/long_press) recorded from agent tool calls

        Actions:
        - record_start + record_stop: Teach the agent a navigation route
        - navigate: Auto-navigate to a target app/page using A* pathfinding
        - list_nodes: List all known UI states in the graph
        - graph_stats: Show graph statistics
        - passive_on/passive_off: Enable/disable background learning from daily usage
        - current_state: Detect current UI state fingerprint

        Example workflow:
        1. passive_on (start learning from daily usage)
        2. After some days: navigate(app_package="com.tencent.mm", name="chat")
    """.trimIndent()

    override fun getDescriptionCN(): String = """
        UI 导航知识图谱 —— 学习、存储和自动导航 TV 和手机界面。

        同时支持 D-pad（TV）和触屏（手机）导航：
        - TV: 遥控器 D-pad 操作，从按键事件录制
        - 手机: 触屏操作（tap/swipe/long_press），从 Agent 工具调用录制

        操作：
        - record_start + record_stop: 录制操作路线
        - navigate: 自动导航到目标 App/页面（A* 寻路 + checkpoint 校验）
        - list_nodes: 列出图谱中已知的所有 UI 状态
        - graph_stats: 图谱统计信息
        - passive_on/passive_off: 开启/关闭被动学习（后台自动从日常使用中学习）
        - current_state: 检测当前 UI 状态指纹

        示例流程：
        1. passive_on（开始被动学习）
        2. 日常使用几天后：navigate(app_package="com.tencent.mm", name="聊天")
    """.trimIndent()
}
