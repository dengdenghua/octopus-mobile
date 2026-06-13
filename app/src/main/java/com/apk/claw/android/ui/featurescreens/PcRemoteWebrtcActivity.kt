package com.apk.claw.android.ui.featurescreens

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import com.apk.claw.android.appViewModel
import com.apk.claw.android.octopus_mobile.Envelope
import com.apk.claw.android.utils.XLog
import org.json.JSONObject

/**
 * 母体远程桌面（WebRTC 版）—— 用系统 WebView 的 Chromium WebRTC,绕开 GeckoView 的 org.webrtc 冲突。
 *
 * 一个本地 HTML 页(assets/pc_remote.html)跑 JS RTCPeerConnection:收视频轨→<video>,
 * 触摸→DataChannel。信令经 JS 桥转给现有 [com.apk.claw.android.octopus_mobile.OctopusMobileClient] WS:
 *   JS Android.request() → 原生发 webrtc/request
 *   母体 webrtc/offer(经 WS) → 原生 evaluateJavascript window.onOffer(sdp)
 *   JS Android.answer(sdp) → 原生发 webrtc/answer
 * 媒体+输入随后走 WebRTC P2P(STUN 打洞)。前提:母体侧运行 pc_remote_webrtc.py。
 */
class PcRemoteWebrtcActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var wsListener: ((String) -> Unit)? = null
    private val client get() = appViewModel.octopusClient

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { window.statusBarColor = android.graphics.Color.BLACK }
        // 部分设备的系统 WebView 组件缺失/正在更新时,WebView(this) 会抛异常。
        // 捕获后优雅退出(提示用户),而不是让 Activity 崩溃。
        webView = try {
            WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false  // 自动播放远端视频
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                        XLog.i("PcWebRTC-JS", "${m.message()} @${m.lineNumber()}")
                        return true
                    }
                }
                addJavascriptInterface(Bridge(), "Android")
                loadUrl("file:///android_asset/pc_remote.html")
            }
        } catch (e: Throwable) {
            XLog.e("PcWebRTC", "WebView 初始化失败", e)
            android.widget.Toast.makeText(this, "系统 WebView 不可用，无法启动远程桌面", android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(webView)

        // WS → JS：把母体的 webrtc/offer 喂给页面
        val c = client
        if (c != null) {
            val l: (String) -> Unit = { text ->
                if (text.contains("\"webrtc/offer\"")) {
                    runCatching {
                        val sdp = JSONObject(text).getJSONObject("params").getString("sdp")
                        runOnUiThread {
                            webView.evaluateJavascript("window.onOffer(${JSONObject.quote(sdp)})", null)
                        }
                    }
                }
            }
            wsListener = l
            c.addMessageListener(l)
        }
    }

    /** JS → 原生：信令出口（在 WebRTC/JS 线程被调用，转发到 WS）。 */
    inner class Bridge {
        @JavascriptInterface
        fun request() {
            client?.send(Envelope.Request(method = "webrtc/request", params = emptyMap()))
        }

        @JavascriptInterface
        fun answer(sdp: String) {
            client?.send(Envelope.Request(method = "webrtc/answer", params = mapOf("sdp" to sdp, "type" to "answer")))
        }

        @JavascriptInterface
        fun reconnect() {
            // 重试时先把底层 tentacle WS 拉起来(母体曾不可达时它可能已停止重连)
            runCatching { com.apk.claw.android.appViewModel.connectRuntime() }
        }

        @JavascriptInterface
        fun back() {
            runOnUiThread { finish() }
        }
    }

    override fun onDestroy() {
        wsListener?.let { client?.removeMessageListener(it) }
        runCatching { webView.destroy() }
        super.onDestroy()
    }
}
