package com.apk.claw.android.octopus_mobile

/**
 * 连接状态枚举.
 *
 * 扩展为 7 状态，兼容原有 3 状态（OFFLINE, CONNECTING, ONLINE）：
 * DISCONNECTED → CONNECTING → CONNECTED → HELLO_SENT → ONLINE
 * ONLINE → RECONNECTING → CONNECTING → ...
 * 任何状态 → OFFLINE（用户主动断开）
 */
enum class ConnectionState {
    /** 未连接 */
    DISCONNECTED,
    /** 正在连接 */
    CONNECTING,
    /** WebSocket 已连接，尚未握手 */
    CONNECTED,
    /** 已发送 device/hello，等待响应 */
    HELLO_SENT,
    /** 握手完成，设备在线 */
    ONLINE,
    /** 断线重连中 */
    RECONNECTING,
    /** 用户主动离线 */
    OFFLINE,
}
