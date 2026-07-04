# ============================================================
# 通用配置
# ============================================================
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# 保留枚举
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留 Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}


# Agent 相关（反射/SPI）
-keep class com.apk.claw.android.agent.langchain.http.** { *; }
-keep class com.apk.claw.android.agent.** { *; }

# Tool 注册（反射）
-keep class com.apk.claw.android.tool.** { *; }

# 插件清单 Gson DTO（PluginManifest/PluginInfo/PluginToolParam/HttpRecipe）：字段名 == JSON 键、
# 多数无 @SerializedName,release 下 R8 改名会让 type/js/page/http 等解析为默认值 → 插件加载失效
# (browser-script/tool/mini-app 全部读不出)。整包保留字段名。详见 PLUGIN_ECOSYSTEM.md。
-keep class com.apk.claw.android.plugin.PluginManifest { *; }
-keep class com.apk.claw.android.plugin.PluginInfo { *; }
-keep class com.apk.claw.android.plugin.PluginToolParam { *; }
-keep class com.apk.claw.android.plugin.HttpRecipe { *; }

# Channel（钉钉/飞书回调，保留泛型签名）
-keep class com.apk.claw.android.channel.** { *; }

# 账号/计费 wire DTO：经 Gson 反射收发,且多数字段无 @SerializedName(靠字段名 == JSON 键),
# release 下 R8 会改名导致 token/credits 等解析成 null → 登录/积分/邀请全坏。整包保留字段名。
-keep class com.apk.claw.android.account.** { *; }

# 小程序 registry / 广场社区小程序 wire DTO：RegistryClient（RegistryListResponse 等）、
# PluginRegistryStore（InstalledPluginManifest）、CommunitySquareApi（CommunityMiniApp/
# CommunityMiniAppDownload，仅 MiniAppTags 的 snake_case 字段有 @SerializedName）均靠
# 字段名 == JSON 键。不保留则 release 下广场浏览/安装、registry 安装清单读取静默变空。
-keep class com.apk.claw.android.registry.** { *; }

# 广场/技能中心 + 宇宙(ECHO Universe) wire DTO：同样经 Gson 反射、字段名 == JSON 键、
# 仅部分字段有 @SerializedName。不保留则 release 下 R8 改名导致未注解字段解析为空：
#   /square/feed、/square/discovery、/config 全空 → 广场只剩种子、club 域名派生失效；
#   /api/universe/feed 的 day/beliefs/goals/friends/memory/diary/growth 等全空。
# 该包内所有 *Dto 一律整类保留字段名；非 Dto 命名的 wire 类显式列出。
-keep class com.apk.claw.android.ui.compose.screen.**Dto { *; }
-keep class com.apk.claw.android.ui.compose.screen.RemoteConfig$AppConfigDto { *; }
-keep class com.apk.claw.android.ui.compose.screen.EchoCharacterOption { *; }
-keep class com.apk.claw.android.ui.compose.screen.GhostChatMessage { *; }
-keep class com.apk.claw.android.ui.compose.screen.OpenAiChatResponse { *; }
-keep class com.apk.claw.android.ui.compose.screen.OpenAiChoice { *; }
-keep class com.apk.claw.android.ui.compose.screen.OpenAiMessage { *; }
# 会话索引(SessionMeta)经 Gson 落盘(含 character 角色隔离字段):R8 改名会让
# 升级换 mapping 后旧索引读不回来 → 历史会话/角色空间丢失。ChatStore$Dto 已被上面 **Dto 覆盖。
-keep class com.apk.claw.android.ui.compose.screen.SessionStore$SessionMeta { *; }

# ============================================================
# Gson
# ============================================================
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# Gson 使用 TypeToken 泛型
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ============================================================
# OkHttp
# ============================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ============================================================
# Retrofit
# ============================================================
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# ============================================================
# LangChain4j
# ============================================================
-dontwarn dev.langchain4j.**
-keep class dev.langchain4j.** { *; }
-keep interface dev.langchain4j.** { *; }

# ============================================================
# Jackson (LangChain4j 内部依赖，序列化需要保留构造器和字段)
# ============================================================
-dontwarn com.fasterxml.jackson.**
-keep class com.fasterxml.jackson.** { *; }
-keep interface com.fasterxml.jackson.** { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* <fields>;
    @com.fasterxml.jackson.annotation.* <init>(...);
}

# ============================================================
# Jackson（LangChain4j OpenAI 内部 JSON 序列化依赖）
# 缺少此规则会导致 R8 混淆 Jackson 内部类，运行时报
# "Class xxx has no default (no arg) constructor"
# ============================================================
-dontwarn com.fasterxml.jackson.**
-keep class com.fasterxml.jackson.** { *; }
-keep interface com.fasterxml.jackson.** { *; }
-keepnames class com.fasterxml.jackson.** { *; }
# 保留带 Jackson 注解的类成员（字段/方法）
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* *;
    @com.fasterxml.jackson.databind.annotation.* *;
}
# 保留 Jackson 需要通过反射创建的类的无参构造函数
-keepclassmembers,allowobfuscation class * {
    @com.fasterxml.jackson.annotation.JsonCreator <init>(...);
}

# ============================================================
# MMKV
# ============================================================
-keep class com.tencent.mmkv.** { *; }

# ============================================================
# Glide
# ============================================================
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder {
    *** rewind();
}
-dontwarn com.bumptech.glide.**


# ============================================================
# 飞书 Lark OAPI SDK
# ============================================================
-dontwarn com.lark.oapi.**
-keep class com.lark.oapi.** { *; }

# ============================================================
# 钉钉 DingTalk Stream SDK
# ============================================================
-dontwarn com.dingtalk.**
-keep class com.dingtalk.** { *; }
-keep interface com.dingtalk.** { *; }
# 保留 callback 泛型签名（SDK 通过反射检查泛型参数）
-keep,allowobfuscation,allowshrinking class * implements com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener
-keepattributes Signature

# ============================================================
# 飞书/钉钉 SDK 依赖的服务端类（Android 不存在，忽略即可）
# ============================================================
# javax.naming (LDAP/JNDI - Apache HttpClient HostnameVerifier)
-dontwarn javax.naming.**

# Apache HttpClient
-dontwarn org.apache.http.**
-dontwarn org.apache.commons.**

# Log4j / Log4j2
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**

# Netty (shade 包 + 原始包)
-dontwarn shade.io.netty.**
-dontwarn io.netty.**
-keep class shade.io.netty.** { *; }
-keep class io.netty.** { *; }

# Netty tcnative (OpenSSL 绑定)
-dontwarn shade.io.netty.internal.tcnative.**
-dontwarn io.netty.internal.tcnative.**

# Jetty ALPN / NPN
-dontwarn org.eclipse.jetty.alpn.**
-dontwarn org.eclipse.jetty.npn.**

# JetBrains Annotations
-dontwarn org.jetbrains.annotations.**

# ============================================================
# ZXing
# ============================================================
-dontwarn com.google.zxing.**
-keep class com.google.zxing.** { *; }

# ============================================================
# MultiType (drakeet)
# ============================================================
-dontwarn com.drakeet.multitype.**
-keep class com.drakeet.multitype.** { *; }

# ============================================================
# BlankJ UtilCode
# ============================================================
-dontwarn com.blankj.**
-keep class com.blankj.utilcode.** { *; }
-keep public class com.blankj.utilcode.util.** { *; }

# ============================================================
# EasyFloat
# ============================================================
-dontwarn com.lzf.easyfloat.**
-keep class com.lzf.easyfloat.** { *; }

# ============================================================
# ok2curl
# ============================================================
-dontwarn com.moczul.ok2curl.**
-keep class com.moczul.ok2curl.** { *; }

# ============================================================
# Kotlin / Coroutines
# ============================================================
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlin.**

# ============================================================
# AndroidX
# ============================================================
# 不再整包保活 androidx.**:此前的 `-keep class androidx.** { *; }` 是初始提交
# 带入的祖传通配规则(非为修某个具体崩溃所加),它把 R8 对整个 AndroidX/Compose
# 的收缩全部关闭 —— 实测 release 里保留了 9905 个 Material 图标 getter 类,而 app
# 只用 71 个;未使用的 Compose 代码也无法裁剪。AndroidX 各 AAR 自带 consumer
# proguard 规则(已覆盖 ViewModel 反射、Compose 运行时等必要 keep),无需再整包保活。
# 收窄后让 R8 裁掉未用图标/Compose,功能不变、dex 显著变小。
# 如 release 烟测发现某个 androidx 类被误裁,按需补“精确到类”的 -keep,切勿恢复通配。
-dontwarn androidx.**

# ============================================================
# glide-transformations (wasabeef)
# ============================================================
-dontwarn jp.wasabeef.glide.**
-keep class jp.wasabeef.glide.** { *; }

# ============================================================
# Shizuku API
# ============================================================
-dontwarn rikka.shizuku.**
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-keep class com.apk.claw.android.shizuku.** { *; }

# ============================================================
# mpv-android-lib (FFmpeg + libplacebo + libass)
# ============================================================
-keep class is.xyz.mpv.** { *; }
-keep class com.apk.claw.android.media.** { *; }
-keep class com.apk.claw.android.navigation.** { *; }

# 保留原生方法（JNI 调用）
-keepclasseswithmembernames class * {
    native <methods>;
}

# ============================================================
# Markwon（Markdown 渲染）
# ============================================================
# markwon-image 的 Svg/Gif 解码器引用了可选依赖 androidsvg / android-gif-drawable,
# 二者本项目未声明(不渲染 SVG/GIF 图,decoder 运行时优雅降级)。收窄 androidx 通配
# keep 后 R8 全程序分析会把这些悬空引用当“缺失类”错误中断构建,这里按 R8 建议忽略。
-dontwarn com.caverock.androidsvg.**
-dontwarn pl.droidsonroids.gif.**

# Coil（图片加载:发现页收藏 favicon / 搜索引擎图标用 coil.compose.AsyncImage）
# release 下 R8 会裁掉 Coil 的 fetcher/decoder 导致图片不显示,这里保活。
-keep class coil.** { *; }
-keep interface coil.** { *; }
-dontwarn coil.**

# Rhino JS 引擎（run_code 沙箱）
# 解释器模式下不生成 JVM 字节码,但 Rhino 用反射加载内部类需全量保留
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# Shizuku 全自动配置(libadb-android + sun-security 重定位包 + spake2 JNI)
# X509CertInfo.set 靠字段名反射生成证书;libadb 无自带 consumer 规则;spake2 走 native —— 都要保活,
# 否则 release 下配对/拉起 Shizuku 静默失败。conscrypt 自带 proguard.txt,无需再 keep。
-keep class android.sun.security.** { *; }
-dontwarn android.sun.security.**
-keep class io.github.muntashirakon.adb.** { *; }
-dontwarn io.github.muntashirakon.adb.**
-keep class io.github.muntashirakon.crypto.spake2.** { *; }
-dontwarn io.github.muntashirakon.crypto.spake2.**
-dontwarn org.conscrypt.**
