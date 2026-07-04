package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.utils.KVUtils
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OctopusMobileClientHandshakeTest {

    private lateinit var server: MockWebServer
    private val clients = mutableListOf<OctopusMobileClient>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        KVUtils.setInsecureOctopusRuntimeAllowed(false)
    }

    @After
    fun tearDown() {
        clients.forEach { it.disconnect() }
        clients.clear()
        server.shutdown()
        KVUtils.setInsecureOctopusRuntimeAllowed(false)
        KVUtils.resetForTest()
    }

    @Test
    fun `client only becomes online after explicit hello ack`() {
        val online = CountDownLatch(1)
        val helloSeen = CountDownLatch(1)
        val socketClosed = enqueueRuntimeSocket { webSocket, text ->
            val hello = JSONObject(text)
            helloSeen.countDown()
            webSocket.send("""{"jsonrpc":"2.0","method":"noise","id":"noise-1"}""")
            webSocket.send(
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", hello.getString("id"))
                    .put("result", JSONObject().put("registered", true))
                    .toString(),
            )
        }

        val client = newClient()
        client.onStateChanged = { if (it == ConnectionState.ONLINE) online.countDown() }
        client.connect()

        assertTrue(helloSeen.await(2, TimeUnit.SECONDS))
        assertTrue(online.await(2, TimeUnit.SECONDS))
        assertEquals(ConnectionState.ONLINE, client.currentState())
        client.disconnect()
        assertTrue(socketClosed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `client stays handshaking when server sends unrelated message`() {
        val helloSeen = CountDownLatch(1)
        val socketClosed = enqueueRuntimeSocket { webSocket, _ ->
            helloSeen.countDown()
            webSocket.send("""{"jsonrpc":"2.0","method":"noise","id":"noise-1"}""")
        }

        val client = newClient()
        client.connect()

        assertTrue(helloSeen.await(2, TimeUnit.SECONDS))
        Thread.sleep(150)
        assertEquals(ConnectionState.HELLO_SENT, client.currentState())
        client.disconnect()
        assertTrue(socketClosed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `tool execute before hello ack is rejected`() {
        val helloSeen = CountDownLatch(1)
        // 服务端在回 hello ack 之前就抢发 tool/execute
        val socketClosed = enqueueRuntimeSocket { webSocket, _ ->
            helloSeen.countDown()
            webSocket.send(
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("method", "tool/execute")
                    .put("id", "evil-1")
                    .put("tool", "run_code")
                    .put("args", JSONObject().put("code", "1"))
                    .toString(),
            )
        }

        val client = newClient()
        val dispatched = CountDownLatch(1)
        client.onToolExecute = { dispatched.countDown() }
        client.connect()

        assertTrue(helloSeen.await(2, TimeUnit.SECONDS))
        // 握手未确认 → tool/execute 不应被派发
        assertEquals(false, dispatched.await(500, TimeUnit.MILLISECONDS))
        assertEquals(ConnectionState.HELLO_SENT, client.currentState())
        client.disconnect()
        assertTrue(socketClosed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `remote cleartext runtime is blocked before websocket opens`() {
        // 公网明文 ws:// 必须在握手前被 MobileRuntimeSecurity 拦下。
        // 203.0.113.0/24 是 RFC 5737 文档示例段,代表"非私有、非环回"的公网地址。
        // LAN(192.168.x.x 等)已被放行用于本地开发,不能用作"被拦截"用例。
        val client = OctopusMobileClient("ws://203.0.113.42:8765", "test-device")
        client.connect()
        assertEquals(ConnectionState.DISCONNECTED, client.currentState())
    }

    private fun newClient(): OctopusMobileClient =
        OctopusMobileClient(server.url("/ws").toString().replace("http://", "ws://"), "test-device")
            .also { clients.add(it) }

    private fun enqueueRuntimeSocket(
        onText: (WebSocket, String) -> Unit,
    ): CountDownLatch {
        val socketClosed = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    onText(webSocket, text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    socketClosed.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    socketClosed.countDown()
                }
            }),
        )
        return socketClosed
    }
}
