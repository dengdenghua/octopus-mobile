package com.apk.claw.android.navigation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.utils.XLog

/**
 * 导航录制器 —— 被动学习引擎。
 *
 * 监听用户的遥控器按键事件，自动构建导航图：
 * 1. 每次按键前：计算当前 UI 状态指纹 → 查找/创建 NavNode
 * 2. 每次按键后：等待 UI 变化（500ms）→ 计算新状态 → 创建 NavEdge
 * 3. 连续相同按键自动压缩为一条边（repeat 参数）
 *
 * 两种模式：
 * - 被动模式（passive）：后台持续监听，自动建图
 * - 主动模式（explicit）：用户说"开始录制"后开始，说"停止"后保存
 */
class NavigationRecorder(
    private val graph: NavigationGraph,
    private val context: Context? = null
) {

    companion object {
        private const val TAG = "NavigationRecorder"
        private const val UI_SETTLE_DELAY_MS = 500L  // 按键后等待 UI 稳定的时间
        private const val MAX_RECORDING_STEPS = 200   // 单次录制最大步数
        private const val CHANNEL_ID = "nav_recorder"
        private const val NOTIFICATION_ID = 0x4E41    // "NA"
    }

    private var recording = false
    private var passiveMode = false
    private val steps = mutableListOf<RecordedStep>()
    private var lastStateId: String? = null
    private var lastStateTime: Long = 0

    /** 录制中的路线名称（主动模式） */
    private var routeName: String? = null

    /** 回调：录制状态变化 */
    var onStateChanged: ((isRecording: Boolean, stepCount: Int) -> Unit)? = null

    /** 单线程后台 Handler，避免每次按键都创建新 Thread */
    private val handlerThread = HandlerThread("nav-recorder").apply { start() }
    private val handler = Handler(handlerThread.looper)

    /**
     * 开始主动录制。
     *
     * @param name 路线名称（如"打开 Netflix 搜索"）
     */
    fun startRecording(name: String) {
        recording = true
        routeName = name
        steps.clear()
        lastStateId = null
        XLog.i(TAG, "Recording started: $name")
        onStateChanged?.invoke(true, 0)
    }

    /**
     * 停止录制，保存路线。
     *
     * @return 录制结果摘要
     */
    fun stopRecording(): RecordingResult {
        recording = false
        val savedSteps = steps.toList()
        steps.clear()

        if (savedSteps.isEmpty()) {
            XLog.w(TAG, "Recording stopped but no steps captured")
            onStateChanged?.invoke(false, 0)
            return RecordingResult(false, 0, 0, "No steps recorded")
        }

        // 将步骤转换为图中的节点和边
        val nodeCount = buildGraphFromSteps(savedSteps)

        val result = RecordingResult(
            success = true,
            stepCount = savedSteps.size,
            nodeCount = nodeCount,
            routeName = routeName ?: "unnamed"
        )
        XLog.i(TAG, "Recording stopped: ${result.stepCount} steps, ${result.nodeCount} nodes")
        onStateChanged?.invoke(false, 0)
        return result
    }

    /**
     * 启用/停用被动学习模式。
     * 被动模式下，后台持续监听按键事件并自动建图。
     */
    fun setPassiveMode(enabled: Boolean) {
        passiveMode = enabled
        if (enabled) {
            lastStateId = null
            XLog.i(TAG, "Passive learning enabled")
            showPassiveNotification()
        } else {
            XLog.i(TAG, "Passive learning disabled")
            cancelPassiveNotification()
        }
    }

    fun isPassiveMode(): Boolean = passiveMode
    fun isRecording(): Boolean = recording

    /**
     * 释放后台线程。应在 NavigationRecorder 生命周期结束时调用。
     */
    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        handlerThread.quitSafely()
        cancelPassiveNotification()
    }

    /** 被动录制时显示常驻通知,告知用户导航录制正在后台运行。 */
    private fun showPassiveNotification() {
        val ctx = context ?: return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "导航录制", NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "被动导航录制运行状态"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
            val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("导航录制运行中")
                .setContentText("正在后台学习你的操作路径")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            nm.notify(NOTIFICATION_ID, notif)
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to show passive notification: ${e.message}")
        }
    }

    private fun cancelPassiveNotification() {
        val ctx = context ?: return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * 处理按键事件 —— 核心方法。
     *
     * 由 ClawAccessibilityService 的 onKeyEvent 调用。
     *
     * @param keyCode Android KeyEvent keyCode
     * @return true 如果按键被录制器消费
     */
    fun onKeyEvent(keyCode: Int): Boolean {
        if (!recording && !passiveMode) return false

        val action = keyCodeToRemoteAction(keyCode) ?: return false

        // 丢到单线程后台 Handler 执行，避免每次按键都创建新 Thread
        handler.post { recordStep(action) }

        return false  // 不消费按键，让系统继续处理
    }

    /**
     * 录制一步操作。
     */
    private fun recordStep(action: RemoteAction) {
        try {
            // 1. 计算按键前的状态
            val beforeState = StateDetector.detectCurrentState() ?: return
            val beforeId = graph.addOrMergeNode(beforeState)

            // 2. 等待 UI 响应
            Thread.sleep(UI_SETTLE_DELAY_MS)

            // 3. 计算按键后的状态
            val afterState = StateDetector.detectCurrentState() ?: return
            val afterId = graph.addOrMergeNode(afterState)

            // 4. 如果状态没变（按键无效），跳过
            if (beforeId == afterId) {
                XLog.d(TAG, "State unchanged after $action, skipping")
                return
            }

            // 5. 记录步骤
            val elapsed = UI_SETTLE_DELAY_MS
            val step = RecordedStep(
                fromNodeId = beforeId,
                action = action,
                toNodeId = afterId,
                timestamp = System.currentTimeMillis(),
                durationMs = elapsed
            )
            steps.add(step)

            // 6. 添加到图
            graph.addEdge(beforeId, action, afterId, elapsed)

            // 7. 主动模式下通知回调
            if (recording) {
                onStateChanged?.invoke(true, steps.size)
            }

            XLog.d(TAG, "Recorded: $beforeId --[${action.type}]--> $afterId")

            // 防止录制过长
            if (steps.size >= MAX_RECORDING_STEPS) {
                XLog.w(TAG, "Max steps reached, auto-stopping recording")
                stopRecording()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            XLog.e(TAG, "Record step error: ${e.message}")
        }
    }

    /**
     * 从录制的步骤构建图（添加 goal 标记）。
     */
    private fun buildGraphFromSteps(recordedSteps: List<RecordedStep>): Int {
        if (recordedSteps.isEmpty()) return 0

        // 最终到达的节点标记为 goal
        val lastStep = recordedSteps.last()
        val name = routeName ?: "unnamed_route"
        graph.markAsGoal(lastStep.toNodeId, name)

        // 收集所有唯一节点
        val nodeIds = mutableSetOf<String>()
        for (step in recordedSteps) {
            nodeIds.add(step.fromNodeId)
            nodeIds.add(step.toNodeId)
        }
        return nodeIds.size
    }

    /**
     * Android keyCode 转 RemoteAction。
     * 只处理 D-pad 和常用系统键。
     */
    private fun keyCodeToRemoteAction(keyCode: Int): RemoteAction? {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> RemoteAction.dpadUp()
            KeyEvent.KEYCODE_DPAD_DOWN -> RemoteAction.dpadDown()
            KeyEvent.KEYCODE_DPAD_LEFT -> RemoteAction.dpadLeft()
            KeyEvent.KEYCODE_DPAD_RIGHT -> RemoteAction.dpadRight()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> RemoteAction.dpadCenter()
            KeyEvent.KEYCODE_BACK -> RemoteAction.back()
            KeyEvent.KEYCODE_HOME -> RemoteAction.home()
            else -> null
        }
    }
}

/**
 * 录制的单步操作。
 */
data class RecordedStep(
    val fromNodeId: String,
    val action: RemoteAction,
    val toNodeId: String,
    val timestamp: Long,
    val durationMs: Long
)

/**
 * 录制结果。
 */
data class RecordingResult(
    val success: Boolean,
    val stepCount: Int,
    val nodeCount: Int,
    val routeName: String
)
