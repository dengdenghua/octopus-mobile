package com.apk.claw.android.ui.plugin

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.plugin.PluginInfo
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton
import com.apk.claw.android.widget.MenuGroup
import java.io.File

/**
 * 插件管理页面
 *
 * - 已加载插件列表（带卸载按钮）
 * - 可用插件列表（带加载按钮）
 * - "从文件安装" 按钮（文件选择器选 .dex + manifest.json）
 */
class PluginActivity : BaseActivity() {

    companion object {
        private const val TAG = "PluginActivity"
    }

    private val pluginManager get() = ClawApplication.instance.pluginManager

    private lateinit var loadedGroup: MenuGroup
    private lateinit var availableGroup: MenuGroup
    private lateinit var tvEmpty: TextView

    // 文件选择器
    private val dexFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        // 用户选择了 dex 文件，接下来选择 manifest
        try {
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream != null) {
                // 复制到临时文件
                val tempDex = File(cacheDir, "temp_plugin.dex")
                tempDex.outputStream().use { out -> inputStream.copyTo(out) }
                inputStream.close()

                // 提示选择 manifest
                selectManifestForDex(tempDex)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "读取文件失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private var pendingDexFile: File? = null

    private val manifestFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        val dexFile = pendingDexFile ?: return@registerForActivityResult
        try {
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream != null) {
                val tempManifest = File(cacheDir, "temp_manifest.json")
                tempManifest.outputStream().use { out -> inputStream.copyTo(out) }
                inputStream.close()

                installPlugin(dexFile, tempManifest)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "读取 manifest 失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = buildLayout()
        setContentView(root)

        // 确保插件已发现
        if (pluginManager.getAllPlugins().isEmpty()) {
            pluginManager.discoverPlugins()
        }

        refreshPluginList()
    }

    override fun onResume() {
        super.onResume()
        refreshPluginList()
    }

    // ── 布局构建 ─────────────────────────────────────

    private fun buildLayout(): LinearLayout {
        val dp16 = dp(16)
        val dp8 = dp(8)
        val dp12 = dp(12)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)

            // Toolbar
            addView(CommonToolbar(this@PluginActivity).apply {
                setTitle("插件管理")
                showBackButton(true) { finish() }
            })

            // ScrollView
            val scrollView = ScrollView(this@PluginActivity).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
                isFillViewport = true
            }

            val scrollContent = LinearLayout(this@PluginActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                setPadding(dp16, dp8, dp16, dp16)
            }

            // 已加载插件
            loadedGroup = MenuGroup(this@PluginActivity).apply {
                setTitle("已加载插件")
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp12 }
            }
            scrollContent.addView(loadedGroup)

            // 可用插件
            availableGroup = MenuGroup(this@PluginActivity).apply {
                setTitle("可用插件")
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp12 }
            }
            scrollContent.addView(availableGroup)

            // 空状态
            tvEmpty = TextView(this@PluginActivity).apply {
                text = "暂无可用插件"
                textSize = 14f
                setTextColor(Color.GRAY)
                android.view.Gravity.CENTER
                setPadding(0, dp(40), 0, dp(40))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
            scrollContent.addView(tvEmpty)

            // 从文件安装按钮
            val btnInstall = KButton(this@PluginActivity).apply {
                text = "从文件安装"
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp8 }
                setOnClickListener {
                    dexFileLauncher.launch("*/*")
                }
            }
            scrollContent.addView(btnInstall)

            // 说明
            val tvNote = TextView(this@PluginActivity).apply {
                text = "提示: 插件需要 .dex 文件 + manifest.json。" +
                       "插件工具将注册到 ToolRegistry 供 Agent 使用。"
                textSize = 12f
                setTextColor(Color.parseColor("#999999"))
                setPadding(0, dp8, 0, dp8)
            }
            scrollContent.addView(tvNote)

            scrollView.addView(scrollContent)
            addView(scrollView)
        }
    }

    // ── 刷新列表 ─────────────────────────────────────

    private fun refreshPluginList() {
        loadedGroup.clearMenuItems()
        availableGroup.clearMenuItems()

        val loaded = pluginManager.getLoadedPlugins()
        val available = pluginManager.getAvailablePlugins()

        for (info in loaded) {
            loadedGroup.addMenuItem(
                leadingIcon = R.drawable.ic_plugin,
                title = "${info.manifest.name} v${info.manifest.version}",
                onClick = { unloadPlugin(info.manifest.id) },
                showDivider = true
            ).apply {
                setTrailingText("卸载")
            }
        }

        for (info in available) {
            availableGroup.addMenuItem(
                leadingIcon = R.drawable.ic_plugin,
                title = "${info.manifest.name} v${info.manifest.version}",
                onClick = { loadPlugin(info.manifest.id) },
                showDivider = true
            ).apply {
                val errorText = info.error
                setTrailingText(if (errorText != null) "错误" else "加载")
            }
        }

        tvEmpty.visibility = if (loaded.isEmpty() && available.isEmpty()) View.VISIBLE else View.GONE
    }

    // ── 插件操作 ─────────────────────────────────────

    private fun loadPlugin(pluginId: String) {
        val ok = pluginManager.loadAndRegister(pluginId)
        if (ok) {
            Toast.makeText(this, "插件加载成功", Toast.LENGTH_SHORT).show()
        } else {
            val info = pluginManager.getAllPlugins().find { it.manifest.id == pluginId }
            Toast.makeText(this, "加载失败: ${info?.error ?: "未知错误"}", Toast.LENGTH_LONG).show()
        }
        refreshPluginList()
    }

    private fun unloadPlugin(pluginId: String) {
        val ok = pluginManager.unload(pluginId)
        if (ok) {
            Toast.makeText(this, "插件已卸载", Toast.LENGTH_SHORT).show()
        }
        refreshPluginList()
    }

    private fun selectManifestForDex(dexFile: File) {
        pendingDexFile = dexFile
        Toast.makeText(this, "请选择 manifest.json 文件", Toast.LENGTH_LONG).show()
        manifestFileLauncher.launch("*/*")
    }

    private fun installPlugin(dexFile: File, manifestFile: File) {
        val info = pluginManager.installFromFile(dexFile, manifestFile)
        if (info != null) {
            Toast.makeText(this, "插件安装成功: ${info.manifest.name}", Toast.LENGTH_SHORT).show()
            // 自动加载
            pluginManager.loadAndRegister(info.manifest.id)
        } else {
            Toast.makeText(this, "插件安装失败", Toast.LENGTH_LONG).show()
        }
        refreshPluginList()
        pendingDexFile = null
    }

    // ── 辅助 ─────────────────────────────────────────

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
