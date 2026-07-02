package com.apk.claw.android.tool.impl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * HTML 无头体检 —— 离屏 WebView 加载生成的 HTML,捕获:
 *  - JS 控制台 **ERROR**(含未捕获异常)——给「渲染 → 抓错 → 自动修复」闭环的信号;
 *  - 可选**截图**([capture]=true)——给 VLM 做「界面是否实现了用户需求」的视觉校验。
 *
 * best-effort:WebView 创建失败 / 超时 / 截图失败一律降级(空错误 / null 截图),绝不阻断生成。
 */
object HtmlLinter {

    data class LintResult(val errors: List<String>, val screenshot: Bitmap?)

    /** 离屏渲染 [html]。[capture]=true 时额外抓一张截图供 VLM 判定。 */
    fun lint(
        context: Context,
        html: String,
        capture: Boolean = false,
        settleMs: Long = 1200,
        timeoutMs: Long = 6000,
    ): LintResult {
        val latch = CountDownLatch(1)
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val shot = AtomicReference<Bitmap?>(null)
        val main = Handler(Looper.getMainLooper())
        var webView: WebView? = null

        main.post {
            try {
                val wv = WebView(context)
                webView = wv
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                // 截图走软件渲染:未 attach 到 window 的 WebView 用 draw() 抓图更可靠
                if (capture) wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                        if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            val m = msg.message().orEmpty()
                            val noise = m.contains("Failed to load resource") || m.contains("net::") || m.contains("ERR_")
                            if (!noise) {
                                val line = (if (msg.lineNumber() > 0) "$m (line ${msg.lineNumber()})" else m).take(300)
                                synchronized(errors) { if (errors.none { it == line } && errors.size < 12) errors.add(line) }
                            }
                        }
                        return true
                    }
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        main.postDelayed({
                            if (capture) shot.set(runCatching { snapshot(wv) }.getOrNull())
                            latch.countDown()
                        }, settleMs)
                    }
                }
                wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            } catch (e: Throwable) {
                latch.countDown()
            }
        }

        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
        main.post { runCatching { webView?.destroy() } }  // 销毁必须在主线程
        return LintResult(synchronized(errors) { errors.toList() }, shot.get())
    }

    /** 手动 measure/layout/draw 抓离屏 WebView 首屏(固定竖屏视口)。 */
    private fun snapshot(wv: WebView): Bitmap? {
        val w = 720
        val h = 1280
        wv.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        wv.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(Color.WHITE); wv.draw(this) }
        return bmp
    }
}
