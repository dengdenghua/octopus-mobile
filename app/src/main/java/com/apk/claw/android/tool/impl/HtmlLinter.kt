package com.apk.claw.android.tool.impl

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * HTML 无头体检 —— 离屏 WebView 加载生成的 HTML,捕获 JS 控制台 **ERROR**(含未捕获异常),
 * 给 [GenerateAppTool] 的「渲染 → 抓错 → 自动修复」闭环提供反馈信号。
 *
 * best-effort:WebView 创建失败 / 超时 / 任何异常都返回空列表(不阻断生成,fail-open)。
 * 过滤掉外部资源加载失败(net::/ERR_/Failed to load resource)——无头无网环境下那是噪音,
 * 不是生成代码本身的 bug(生成 prompt 也要求默认内联)。
 */
object HtmlLinter {

    /** 离屏渲染 [html],返回去重截断后的控制台 ERROR 文本列表。 */
    fun lint(context: Context, html: String, settleMs: Long = 1200, timeoutMs: Long = 6000): List<String> {
        val latch = CountDownLatch(1)
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val main = Handler(Looper.getMainLooper())
        var webView: WebView? = null

        main.post {
            try {
                val wv = WebView(context)
                webView = wv
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                        if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            val m = msg.message().orEmpty()
                            val isResourceNoise = m.contains("Failed to load resource") ||
                                m.contains("net::") || m.contains("ERR_")
                            if (!isResourceNoise) {
                                val line = (if (msg.lineNumber() > 0) "$m (line ${msg.lineNumber()})" else m).take(300)
                                synchronized(errors) {
                                    if (errors.none { it == line } && errors.size < 12) errors.add(line)
                                }
                            }
                        }
                        return true
                    }
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        // 页面加载完再等 settle,让 onload/DOMContentLoaded 里的 JS 有机会抛错
                        main.postDelayed({ latch.countDown() }, settleMs)
                    }
                }
                // baseURL=null:内联 JS 照跑;外部资源不加载(无网),其失败已在上面过滤
                wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            } catch (e: Throwable) {
                latch.countDown()
            }
        }

        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
        main.post { runCatching { webView?.destroy() } }  // WebView 销毁必须在主线程
        return synchronized(errors) { errors.toList() }
    }
}
