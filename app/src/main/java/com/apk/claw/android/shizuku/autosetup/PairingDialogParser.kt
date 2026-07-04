package com.apk.claw.android.shizuku.autosetup

/**
 * 从系统「无线调试 · 使用配对码配对设备」弹窗里刮出来的配对信息。
 *
 * @param host 本机回环/局域网 IP（弹窗里的 IP 段，通常 127.0.0.1 或 10.x/192.168.x）
 * @param port 配对端口（注意：是「配对」子弹窗的端口，不是无线调试主页那个连接端口）
 * @param code 6 位数字配对码
 */
data class PairingInfo(val host: String, val port: Int, val code: String)

/**
 * 无线调试配对弹窗解析器。
 *
 * 输入 = 无障碍服务读到的一屏文本节点（各家 ROM 文案不同，顺序也不保证）；
 * 输出 = 结构化的 [PairingInfo]。做成**纯函数、零依赖、零 Android API**，
 * 好让「各家 ROM 文案」用单测覆盖，而不是靠真机碰运气。
 *
 * 解析策略（避开数字互相打架）：
 *  1. 先抠 `ip:port`（IP 每段 ≤3 位，port 2-5 位）——这是弹窗里唯一的「点分四段+冒号」形态。
 *  2. 把 `ip:port` 片段从文本里挖掉，再在剩下的文本里找**孤立的 6 位数字**当配对码，
 *     避免把 port 或 IP 段误当配对码。
 */
object PairingDialogParser {

    // 10.0.2.16:37755 / 192.168.1.7：41234（兼容中英文冒号、冒号旁空格）
    private val IP_PORT = Regex("""(\d{1,3}(?:\.\d{1,3}){3})\s*[:：]\s*(\d{2,5})""")

    // 孤立 6 位数字（前后不接其它数字），配对码形态；兼容「123 456 / 123-456」中缝
    private val CODE_PLAIN = Regex("""(?<!\d)(\d{6})(?!\d)""")
    private val CODE_SPACED = Regex("""(?<!\d)(\d{3})[ \-](\d{3})(?!\d)""")

    /** 合法 TCP 端口上界,用于校验从弹窗文本里抠出的 port 字段。 */
    private const val MAX_TCP_PORT = 65535

    /**
     * 从一屏文本节点解析配对信息；任一要素缺失（无 ip:port 或无配对码）返回 null，
     * 由上层决定回退到「让用户手填配对码」。
     */
    fun parse(texts: List<String>): PairingInfo? {
        if (texts.isEmpty()) return null
        val joined = texts.joinToString("\n")

        val ipPort = IP_PORT.find(joined)
        val port = ipPort?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it in 1..MAX_TCP_PORT }

        // 关键：先挖掉 ip:port，剩下的文本里再找 6 位码，防止把 port/IP 段当配对码
        val code = ipPort?.let {
            val rest = joined.replace(it.value, " ")
            CODE_PLAIN.find(rest)?.groupValues?.get(1)
                ?: CODE_SPACED.find(rest)?.let { m -> m.groupValues[1] + m.groupValues[2] }
        }

        return if (ipPort != null && port != null && code != null) {
            PairingInfo(host = ipPort.groupValues[1], port = port, code = code)
        } else null
    }
}
