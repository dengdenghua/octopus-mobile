package com.apk.claw.android.utils

import okhttp3.OkHttpClient

/**
 * 全局共享的 OkHttp 基础客户端。
 *
 * 背景：此前全项目 ~20 个文件各自 `OkHttpClient()` / `OkHttpClient.Builder().build()`，
 * 每个 client 都自带独立的 ConnectionPool + Dispatcher 线程池（默认最多 64 并发、
 * 5 个空闲连接常驻），白白多占内存与线程。
 *
 * 现在统一从这里派生：
 *  - 默认配置够用的，直接用 [shared]；
 *  - 需要自定义超时 / 拦截器 / 重定向的，用 `OctoHttp.shared.newBuilder()....build()` ——
 *    `newBuilder()` 会**复用**底层连接池与 Dispatcher 线程池，只覆盖你显式设置的项，
 *    所以各调用方原有的超时语义不变，但不再重复创建线程/连接池。
 *
 * [shared] 用 OkHttp 原生默认值（10s 连接/读/写），故原本裸 `OkHttpClient()` 的调用方
 * 换成 [shared] 行为完全一致。`@JvmField` 让 Java 端也能直接 `OctoHttp.shared` 取用。
 */
object OctoHttp {
    @JvmField
    val shared: OkHttpClient = OkHttpClient()
}
