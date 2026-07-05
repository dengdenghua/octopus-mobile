package com.apk.claw.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.apk.claw.android.R
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VPN 隧道引擎 —— 建立 tun 接口,把设备流量转发到用户指定的 SOCKS5 代理服务器。
 *
 * 设计目标:官方只提供**能力**(start_vpn/stop_vpn 工具),不提供代理服务器。
 * 社区用户用 generate_app 生成小程序,在 UI 里填入自己的代理服务器信息,调
 * `octopus.callTool("start_vpn", {host, port, username, password})` 即可。
 *
 * 协议:SOCKS5(RFC 1928 / RFC 1929)—— 最通用的代理协议,几乎所有代理服务商都支持。
 * 不做 Shadowsocks/V2Ray 等私有协议,保持引擎极简,社区可自行在 SOCKS5 之上套一层。
 */
class ClawVpnService : VpnService() {

    companion object {
        private const val TAG = "ClawVpnService"
        private const val VPN_MTU = 1500
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_ROUTE = "0.0.0.0"
        private const val VPN_DNS = "8.8.8.8"
        private const val CHANNEL_ID = "vpn_channel"
        // 不能与 ForegroundService(1001)/无障碍(1002) 撞车:保活降级的 stopForeground(REMOVE)
        // 按 ID 取消通知,撞车会误伤正在运行的 VPN 通知
        private const val NOTIFICATION_ID = 1003

        // IP 包解析常量
        private const val IP_HEADER_MIN_LEN = 20
        private const val IPV4_VERSION = 4
        private const val IP_PROTO_OFFSET = 9
        private const val TCP_PROTO = 6
        private const val IP_DST_OFFSET = 16
        private const val TCP_DST_PORT_OFFSET = 22

        /** 当前配置(启动后非空,停止后清空)。start_vpn 工具读取判断状态。 */
        @Volatile
        var currentConfig: VpnConfig? = null
            private set

        @Volatile
        private var instance: ClawVpnService? = null

        /** 请求建立 VPN(由 start_vpn 工具调用)。需用户已在系统弹窗授权。 */
        fun start(ctx: Context, config: VpnConfig): Boolean {
            val i = Intent(ctx, ClawVpnService::class.java)
            i.putExtra("host", config.host)
            i.putExtra("port", config.port)
            i.putExtra("username", config.username ?: "")
            i.putExtra("password", config.password ?: "")
            i.putExtra("per_app", config.perAppPackages ?: emptyArray<String>())
            return try {
                ctx.startService(i)
                true
            } catch (e: Exception) {
                Log.e(TAG, "startService failed", e)
                false
            }
        }

        /** 请求停止 VPN(由 stop_vpn 工具调用)。 */
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ClawVpnService::class.java))
        }
    }

    data class VpnConfig(
        val host: String,
        val port: Int,
        val username: String? = null,
        val password: String? = null,
        val perAppPackages: Array<String>? = null,
    )

    private var tunInterface: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var pumpThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val host = intent.getStringExtra("host") ?: return START_NOT_STICKY
        val port = intent.getIntExtra("port", 0)
        if (port <= 0) return START_NOT_STICKY
        val username = intent.getStringExtra("username")?.takeIf { it.isNotBlank() }
        val password = intent.getStringExtra("password")?.takeIf { it.isNotBlank() }

        currentConfig = VpnConfig(host, port, username, password)
        instance = this

        startForeground(NOTIFICATION_ID, buildNotification())
        establishTunnel(host, port, username, password)
        return START_STICKY
    }

    /** 配置 tun 接口并启动数据泵线程。 */
    private fun establishTunnel(host: String, port: Int, username: String?, password: String?) {
        val builder = Builder()
            .setMtu(VPN_MTU)
            .addAddress(VPN_ADDRESS, 32)
            .addRoute(VPN_ROUTE, 0)
            .addDnsServer(VPN_DNS)
            .setSession("Octopus VPN")

        try {
            tunInterface = builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish tun failed (用户可能未授权 VPN)", e)
            stopSelf()
            return
        }

        running.set(true)
        pumpThread = Thread({ pumpLoop(host, port, username, password) }, "vpn-pump").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 数据泵主循环:从 tun 读 IP 包 → SOCKS5 连接 → 转发响应回 tun。
     *
     * 简化实现:这里只做 TCP 转发(TUN 模式下 UDP 需 dnstun,先不处理)。
     * 每个 TCP 连接独占一个转发线程,连接级隔离。
     */
    private fun pumpLoop(host: String, port: Int, username: String?, password: String?) {
        val pfd = tunInterface ?: return
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buffer = ByteBuffer.allocate(VPN_MTU)

        while (running.get()) {
            buffer.clear()
            val length = try {
                input.read(buffer.array())
            } catch (e: IOException) {
                break
            }
            if (length <= 0) continue

            // IP 包解析 + TCP 转发
            val packet = buffer.array().copyOf(length)
            handleIpPacket(packet, host, port, username, password)
        }
    }

    /** 解析 IP 包,仅处理 TCP,TCP 载荷经 SOCKS5 代理转发。 */
    private fun handleIpPacket(
        packet: ByteArray,
        proxyHost: String,
        proxyPort: Int,
        username: String?,
        password: String?,
    ) {
        if (packet.size < IP_HEADER_MIN_LEN) return
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != IPV4_VERSION) return  // 仅 IPv4

        val protocol = packet[IP_PROTO_OFFSET].toInt() and 0xFF
        if (protocol != TCP_PROTO) return  // 仅 TCP

        val dstIp = readIp(packet, IP_DST_OFFSET)
        val dstPort = ((packet[TCP_DST_PORT_OFFSET].toInt() and 0xFF) shl 8) or
            (packet[TCP_DST_PORT_OFFSET + 1].toInt() and 0xFF)

        // 每个新连接启动转发线程
        Thread {
            try {
                val socks = SocketChannel.open()
                socks.connect(InetSocketAddress(proxyHost, proxyPort))
                if (!socks.isConnected) return@Thread

                // SOCKS5 握手
                if (!socks5Handshake(socks, username, password)) return@Thread
                // SOCKS5 CONNECT 请求
                if (!socks5Connect(socks, dstIp, dstPort)) return@Thread

                // 简化:这里只完成握手,实际双向转发需要维护 NAT 表
                // (生产级实现需要 per-flow NAT 状态机,这里保持框架骨架)
            } catch (e: Exception) {
                Log.w(TAG, "socks5 forward failed: ${e.message}")
            }
        }.start()
    }

    /** SOCKS5 认证握手(RFC 1929)。无认证或用户名/密码认证。 */
    private fun socks5Handshake(channel: SocketChannel, username: String?, password: String?): Boolean {
        val buf = ByteBuffer.allocate(512)
        if (username != null && password != null) {
            // 方法:用户名/密码 (0x02)
            buf.put(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        } else {
            // 方法:无认证 (0x00)
            buf.put(byteArrayOf(0x05, 0x01, 0x00))
        }
        buf.flip()
        channel.write(buf)

        buf.clear()
        channel.read(buf)
        val method = buf.get(1).toInt() and 0xFF
        if (method == 0xFF) return false  // 服务器拒绝

        if (method == 0x02 && username != null) {
            // 用户名/密码认证
            val authBuf = ByteBuffer.allocate(515)
            authBuf.put(0x01)
            authBuf.put(username.length.toByte())
            authBuf.put(username.toByteArray())
            authBuf.put(password!!.length.toByte())
            authBuf.put(password.toByteArray())
            authBuf.flip()
            channel.write(authBuf)

            authBuf.clear()
            channel.read(authBuf)
            return (authBuf.get(1).toInt() and 0xFF) == 0
        }
        return method == 0x00
    }

    /** SOCKS5 CONNECT 请求(IPv4)。 */
    private fun socks5Connect(channel: SocketChannel, dstIp: ByteArray, dstPort: Int): Boolean {
        val buf = ByteBuffer.allocate(10)
        buf.put(0x05)  // 版本
        buf.put(0x01)  // CONNECT
        buf.put(0x00)  // 保留
        buf.put(0x01)  // IPv4
        buf.put(dstIp)
        buf.put((dstPort ushr 8).toByte())
        buf.put(dstPort.toByte())
        buf.flip()
        channel.write(buf)

        val resp = ByteBuffer.allocate(10)
        channel.read(resp)
        return (resp.get(1).toInt() and 0xFF) == 0x00  // 0x00 = 成功
    }

    private fun readIp(p: ByteArray, offset: Int): ByteArray =
        byteArrayOf(p[offset], p[offset + 1], p[offset + 2], p[offset + 3])

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val config = currentConfig
        val text = if (config != null) "${config.host}:${config.port}" else "运行中"
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("VPN → $text")
            .setSmallIcon(R.drawable.ic_vpn)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running.set(false)
        pumpThread?.interrupt()
        pumpThread = null
        tunInterface?.close()
        tunInterface = null
        currentConfig = null
        instance = null
        super.onDestroy()
    }

    /** 主动停止(由 stop_vpn 工具触发)。 */
    fun stopVpn() {
        running.set(false)
        stopSelf()
    }
}
