package com.apk.claw.android.navigation

import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.utils.XLog

/**
 * 导航执行器 —— 按路径执行 UI 操作（D-pad / 触屏），带 checkpoint 校验和自适应修正。
 *
 * 执行流程：
 * 1. 逐步执行路径中的边（D-pad 动作或 tap/swipe 等触屏动作）
 * 2. 每步执行后：检测当前 UI 状态 → 与预期节点指纹比对
 * 3. 匹配（相似度 >= 0.7）→ 继续下一步
 * 4. 不匹配 → 重新 A* 搜索（从当前节点到目标节点）
 * 5. 重搜索成功 → 用新路径替换剩余步骤
 * 6. 重搜索失败 → 报告失败
 */
class NavigationExecutor(
    private val graph: NavigationGraph,
    private val toolRegistry: ToolRegistry
) {

    companion object {
        private const val TAG = "NavigationExecutor"
        private const val CHECKPOINT_THRESHOLD = 0.70  // 状态相似度阈值
        private const val MAX_REPLANS = 3              // 最大重规划次数
        private const val ACTION_DELAY_MS = 300L       // 步骤间最小间隔
    }

    /** 执行回调 */
    var onStepExecuted: ((stepIndex: Int, totalSteps: Int, action: RemoteAction, success: Boolean) -> Unit)? = null
    var onReplan: ((replanCount: Int, reason: String) -> Unit)? = null

    /**
     * 执行一条导航路径。
     *
     * @param path A* 搜索得到的路径
     * @return 执行结果
     */
    fun execute(path: NavigationPath): ExecutionResult {
        if (path.edges.isEmpty()) {
            return ExecutionResult(true, 0, 0, "Already at destination")
        }

        var replanCount = 0
        var currentEdges = path.edges.toMutableList()
        var executedSteps = 0

        while (currentEdges.isNotEmpty()) {
            val edge = currentEdges.first()

            // 执行动作
            val actionSuccess = executeAction(edge.action)
            executedSteps++

            if (!actionSuccess) {
                XLog.w(TAG, "Action failed: ${edge.action.type}")
                onStepExecuted?.invoke(executedSteps, path.edges.size, edge.action, false)
                return ExecutionResult(false, executedSteps, replanCount, "Action execution failed: ${edge.action.type}")
            }

            onStepExecuted?.invoke(executedSteps, path.edges.size, edge.action, true)

            // 等待 UI 稳定
            Thread.sleep(ACTION_DELAY_MS)

            // Checkpoint 校验：检测当前状态
            val currentState = StateDetector.detectCurrentState()
            if (currentState != null) {
                val expectedNode = graph.getNode(edge.to)
                if (expectedNode != null) {
                    val similarity = StateDetector.similarity(currentState, expectedNode.fingerprint)
                    if (similarity < CHECKPOINT_THRESHOLD) {
                        XLog.w(TAG, "Checkpoint mismatch at step $executedSteps: expected=${expectedNode.label}, similarity=$similarity")

                        // 尝试重新规划
                        if (replanCount < MAX_REPLANS) {
                            replanCount++
                            val currentNodeId = graph.addOrMergeNode(currentState)
                            val goalNodeId = path.nodeIds.last()

                            onReplan?.invoke(replanCount, "Checkpoint mismatch (similarity=$similarity)")

                            val newPath = PathFinder.findPath(graph, currentNodeId, goalNodeId)
                            if (newPath != null) {
                                XLog.i(TAG, "Replan success: new path with ${newPath.edges.size} steps")
                                currentEdges = newPath.edges.toMutableList()
                                continue
                            } else {
                                XLog.w(TAG, "Replan failed: no path from current to goal")
                                return ExecutionResult(false, executedSteps, replanCount,
                                    "Replan failed: cannot find path from current state to goal")
                            }
                        } else {
                            return ExecutionResult(false, executedSteps, replanCount,
                                "Max replans ($MAX_REPLANS) reached, giving up")
                        }
                    }
                }
            }

            // 标记边成功
            graph.addEdge(edge.from, edge.action, edge.to, ACTION_DELAY_MS)

            // 移除已执行的边
            currentEdges.removeAt(0)
        }

        graph.saveAll()
        return ExecutionResult(true, executedSteps, replanCount, "Navigation completed successfully")
    }

    /**
     * 执行单个遥控动作。
     */
    private fun executeAction(action: RemoteAction): Boolean {
        val (toolName, params) = action.toToolCall()
        val result = toolRegistry.executeTool(toolName, params)
        if (!result.isSuccess) {
            XLog.e(TAG, "Tool $toolName failed: ${result.error}")
        }
        return result.isSuccess
    }
}

/**
 * 导航执行结果。
 */
data class ExecutionResult(
    val success: Boolean,
    val stepsExecuted: Int,
    val replanCount: Int,
    val message: String
)
