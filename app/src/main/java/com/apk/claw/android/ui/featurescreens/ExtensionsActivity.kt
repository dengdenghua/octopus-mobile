package com.apk.claw.android.ui.featurescreens

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.apk.claw.android.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * 浏览器扩展 —— 本地管理已安装的 Firefox(GeckoView)WebExtension:列出 / 启停 / 卸载,
 * 并提供"去 AMO 安装"入口。能力来自 [com.apk.claw.android.octopus_mobile.browser.GeckoViewEngine]
 * 用的同一个 GeckoRuntime(单例)。
 */
class ExtensionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { ExtensionsScreen(onBack = { finish() }) }
    }
}

private data class ExtItem(val ext: WebExtension, val id: String, val name: String, val desc: String, val enabled: Boolean)

@Composable
private fun ExtensionsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<ExtItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    fun controller(): WebExtensionController? =
        runCatching { GeckoRuntime.getDefault(ctx).webExtensionController }.getOrNull()

    fun refresh() {
        loading = true
        val c = controller()
        if (c == null) { loading = false; items = emptyList(); return }
        c.list().accept({ list ->
            val mapped = (list ?: emptyList()).map {
                val m = it.metaData
                ExtItem(it, it.id, m?.name ?: it.id, m?.description ?: "", m?.enabled ?: true)
            }
            (ctx as? ComponentActivity)?.runOnUiThread { items = mapped; loading = false }
        }, { _ -> (ctx as? ComponentActivity)?.runOnUiThread { loading = false } })
    }

    LaunchedEffect(Unit) { refresh() }

    FeatureScaffold(title = stringResource(R.string.extensions_title), onBack = onBack) {
        // 安装入口
        FCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.extensions_install_from_amo), color = FText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.extensions_install_instructions), color = FMuted, fontSize = 11.sp)
                }
                Text(
                    stringResource(R.string.extensions_go_install_button), color = FPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { openAmo(ctx) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.extensions_installed_section), color = FMuted, fontSize = 11.sp, modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp))

        when {
            loading -> FEmpty(stringResource(R.string.browser_loading_text))
            items.isEmpty() -> FEmpty(stringResource(R.string.extensions_empty_state))
            else -> LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(items, key = { it.id }) { e ->
                    FCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(e.name, color = FText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                if (e.desc.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(e.desc, color = FSub, fontSize = 12.sp, maxLines = 2)
                                }
                                Text(if (e.enabled) stringResource(R.string.extensions_enabled_status) else stringResource(R.string.extensions_disabled_status), color = if (e.enabled) FPrimary else FMuted, fontSize = 10.sp)
                            }
                            Text(
                                if (e.enabled) stringResource(R.string.extensions_disable_button) else stringResource(R.string.extensions_enable_button), color = FPrimary, fontSize = 14.sp,
                                modifier = Modifier
                                    .clickable { toggle(controller(), e) { refresh() } }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                            Text(
                                stringResource(R.string.extensions_uninstall_button), color = FMuted, fontSize = 14.sp,
                                modifier = Modifier
                                    .clickable { uninstall(controller(), e, ctx) { refresh() } }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun openAmo(ctx: android.content.Context) {
    // 应用内 GeckoView 浏览器(支持「添加到 Firefox」),失败回退系统浏览器
    val url = "https://addons.mozilla.org/zh-CN/android/"
    val ok = runCatching {
        val i = Intent(ctx, com.apk.claw.android.ui.browser.BrowserActivity::class.java)
        i.putExtra(com.apk.claw.android.ui.browser.BrowserActivity.EXTRA_URL, url)
        ctx.startActivity(i); true
    }.getOrDefault(false)
    if (!ok) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private fun toggle(c: WebExtensionController?, e: ExtItem, done: () -> Unit) {
    c ?: return
    val r = if (e.enabled) c.disable(e.ext, WebExtensionController.EnableSource.USER)
    else c.enable(e.ext, WebExtensionController.EnableSource.USER)
    r.accept({ done() }, { done() })
}

private fun uninstall(c: WebExtensionController?, e: ExtItem, ctx: android.content.Context, done: () -> Unit) {
    c ?: return
    c.uninstall(e.ext).accept({
        (ctx as? ComponentActivity)?.runOnUiThread { Toast.makeText(ctx, ctx.getString(R.string.extensions_uninstalled_toast, e.name), Toast.LENGTH_SHORT).show() }
        done()
    }, { done() })
}
