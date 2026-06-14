package com.apk.claw.android

import com.apk.claw.android.agent.DefaultAgentService
import com.apk.claw.android.base.BaseApp
import com.apk.claw.android.channel.ChannelManager
import com.apk.claw.android.octopus_mobile.BrainModeSelector
import com.apk.claw.android.octopus_mobile.ConnectionState
import com.apk.claw.android.octopus_mobile.DeviceDiscoveryManager
import com.apk.claw.android.octopus_mobile.DeviceRegistry
import com.apk.claw.android.octopus_mobile.SkillManifest
import com.apk.claw.android.octopus_mobile.nerves.EventBus
import com.apk.claw.android.plugin.PluginManager
import com.apk.claw.android.service.ForegroundService
import com.apk.claw.android.shizuku.ShizukuManager
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.utils.DeviceUtils
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.blankj.utilcode.util.NetworkUtils

/**
 * Application 入口
 */

val appViewModel: AppViewModel by lazy { ClawApplication.appViewModelInstance }
open class ClawApplication : BaseApp() {

    companion object {
        private const val TAG = "ClawApplication"
        lateinit var instance: ClawApplication
            private set
        lateinit var appViewModelInstance: AppViewModel

        /**
         * 方案 F 决策层切换器 —— 全局单例.
         * 委托给 AppViewModel.brainSelector
         */
        @JvmStatic
        val brainSelector: BrainModeSelector
            get() = appViewModelInstance.brainSelector!!

        /** 当前加载的 SKILL.md（启动时一次性加载，缓存用） */
        @JvmStatic
        val skills: List<com.apk.claw.android.octopus_mobile.SkillSpec>
            get() {
                val mobileSkills = SkillManifest.loadFromAssets(instance, "skills/mobile")
                // TV 设备额外加载 TV 专属 Skill（D-pad 按键等）
                return if (DeviceUtils.isTvDevice(instance)) {
                    val tvSkills = SkillManifest.loadFromAssets(instance, "skills/tv")
                    mobileSkills + tvSkills
                } else {
                    mobileSkills
                }
            }
    }

    /** Application 级单例：设备注册表 + 设备发现管理器 */
    val deviceRegistry = DeviceRegistry()
    lateinit var deviceDiscoveryManager: DeviceDiscoveryManager
        private set
    /** 插件管理器 */
    lateinit var pluginManager: com.apk.claw.android.plugin.PluginManager
        private set

    /** 事件总线（模块间解耦通知） */
    val eventBus = EventBus()

    override fun onCreate() {
        super.onCreate()
        instance = this
        initializeApp()
    }

    /**
     * 全量初始化（MMKV 原生库、前台服务、Shizuku、通道等）。
     * 单元测试用 TestClawApplication 覆写为空实现，跳过 JVM 上不可用的原生依赖。
     */
    protected open fun initializeApp() {
        XLog.setDEBUG(BuildConfig.DEBUG)
        registerNetworkCallback()
        appViewModelInstance = getAppViewModelProvider()[AppViewModel::class.java]
        KVUtils.init(this)
        // 主题:在任何 UI 类加载前,据持久化偏好设好深色/明亮(其余 UI 的颜色 getter 据此取值)
        com.apk.claw.android.ui.compose.theme.OctopusColors.isLight = KVUtils.isLightTheme()
        deviceDiscoveryManager = DeviceDiscoveryManager(this, deviceRegistry)
        pluginManager = com.apk.claw.android.plugin.PluginManager(this)

        // 设备类型自动检测：TV 盒子 vs 手机
        val deviceType = if (DeviceUtils.isTvDevice(this)) {
            ToolRegistry.DeviceType.TV
        } else {
            ToolRegistry.DeviceType.MOBILE
        }
        ToolRegistry.getInstance().registerAllTools(deviceType)
        XLog.e(TAG, "ClawApplication initialized | device=${DeviceUtils.getDeviceDescription(this)} | tools=${ToolRegistry.getInstance().getAllTools().size}")

        // Shizuku 增强层初始化（监听 Binder 到达/死亡）
        ShizukuManager.init()
        XLog.i(TAG, "Shizuku initialized: installed=${ShizukuManager.isShizukuInstalled(packageManager)}")

        // 网络日志输出到文件（调试时设为 true）
        DefaultAgentService.FILE_LOGGING_ENABLED = BuildConfig.DEBUG
        DefaultAgentService.FILE_LOGGING_CACHE_DIR = cacheDir

        // 轻量初始化（主线程）
        appViewModelInstance.initCommon()
        if (!ForegroundService.isRunning()) {
            val started = ForegroundService.start(this)
            if (!started) {
                XLog.e(TAG, "ForegroundService start failed: notification permission not granted")
            }
        }

        // ── 方案 F · 启动 Octopus Mobile 决策层 ──
        initOctopusMobile()

        Thread({
            if (KVUtils.hasLlmConfig()) {
                appViewModelInstance.initAgent()
                appViewModelInstance.afterInit()
            }
        }, "app-async-init").start()
    }

    /**
     * 初始化 Octopus Mobile 模块 —— 委托给 AppViewModel.
     */
    private fun initOctopusMobile() {
        appViewModelInstance.initOctopusMobile()
    }

    /**
     * 探测 Runtime 连通性（用于 StartupModeResolver 决策）.
     */
    fun isRuntimeReachable(): Boolean {
        val url = KVUtils.getOctopusRpcUrl().ifEmpty { return false }
        val httpUrl = url.replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")
            .substringBeforeLast("/")
        return try {
            val conn = java.net.URL(httpUrl).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
            conn.requestMethod = "HEAD"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..499
        } catch (e: Exception) {
            XLog.d(TAG, "Runtime not reachable: ${e.message}")
            false
        }
    }

    private var networkListener: NetworkUtils.OnNetworkStatusChangedListener? = null

    /**
     * 监听网络恢复，自动重新初始化通道。
     * 解决开机自启动时无网络导致通道初始化失败的问题，以及运行中断网恢复后通道重连。
     */
    private fun registerNetworkCallback() {
        networkListener = object : NetworkUtils.OnNetworkStatusChangedListener {
            override fun onConnected(networkType: NetworkUtils.NetworkType?) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (KVUtils.hasLlmConfig()) {
                        XLog.i(TAG, "网络恢复(${networkType?.name})，检查并重连断开的通道")
                        ChannelManager.reconnectIfNeeded()
                    }
                }, 2000)
            }

            override fun onDisconnected() {
                XLog.w(TAG, "网络断开")
            }
        }
        NetworkUtils.registerNetworkStatusChangedListener(networkListener)
    }

}
