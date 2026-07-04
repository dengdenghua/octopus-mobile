import org.jetbrains.kotlin.konan.properties.hasProperty
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
    jacoco
}

// 静态门禁:detekt 只对「新增」问题失败。存量问题记录在 detekt-baseline.xml,
// 与 lint-baseline.xml 的棘轮策略一致 —— 见 README「构建/发布」。
detekt {
    buildUponDefaultConfig = true
    baseline = file("detekt-baseline.xml")
    parallel = true
    // 覆盖配置(合并到默认之上):豁免 @Composable 的 PascalCase 命名。
    config.setFrom(files("detekt.yml"))
    // Kotlin 源码放在 src/main/java 下(非默认 src/main/kotlin),需显式指向。
    source.setFrom(files("src/main/java", "src/test/java"))
}


android {
    namespace = "com.apk.claw.android"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    signingConfigs {
        create("release") {
            val props = Properties().apply {
                rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
            }
            // 优先从环境变量读取(CI 注入),其次 local.properties(本地开发)。
            // 这样 CI 无需把密钥写进文件,本地开发者仍可用 local.properties。
            // Only configure storeFile when a keystore path is actually provided.
            // Otherwise debug builds fail at configuration time with
            //   "Cannot convert '' to File."
            // because Gradle eagerly resolves signingConfig.storeFile.
            val keystorePath = (System.getenv("KEYSTORE_FILE") ?: props.getProperty("KEYSTORE_FILE", "")).trim()
            if (keystorePath.isNotEmpty()) {
                storeFile = file(keystorePath)
            }
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: props.getProperty("KEYSTORE_PASSWORD", "")
            keyAlias = System.getenv("KEY_ALIAS") ?: props.getProperty("KEY_ALIAS", "")
            keyPassword = System.getenv("KEY_PASSWORD") ?: props.getProperty("KEY_PASSWORD", "")
        }
    }

    defaultConfig {
        applicationId = "com.octopus.mobile"
        minSdk = 28
        targetSdk = 36
        versionCode = 7
        versionName = "0.0.7"
        buildConfigField("String", "VERSION_INFO", getVersionGit())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 只保留 app 实际支持的语言资源(默认/英文 + 中文 values-zh + 日文 values-ja)。
        // AndroidX/Material/Compose 等库自带数十种语言的字符串,这里过滤掉未支持的语言,
        // 缩减 resources.arsc / res。注意必须含 ja,否则会误删 app 自带的日语翻译。
        resourceConfigurations += setOf("en", "zh", "ja")

        // ABI 由下方 splits 块按架构分包(每个 APK 只带自己架构),这里不再用
        // abiFilters 限制,否则会与 splits 冲突、把 32 位过滤掉。
    }

    // 按 ABI 分包:arm64-v8a / armeabi-v7a 各生成一个独立 APK,只含自身架构;另出一个
    // universal 通用包(含两套 .so,一个包 32/64 位都能装,省得分辨给哪台)。GeckoView 已移除,
    // 通用包也就 ~两套 .so 的体积(几十 MB),不像当年 370MB 那么夸张。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            isShrinkResources = false
            // 启用 JaCoCo 字节码插桩,供 jacocoTestReport 生成覆盖率报告。
            // 仅 debug 开启,避免影响 release 性能/包体。
            enableUnitTestCoverage = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

    }

    // 单元测试：让 android.* 的桩方法返回默认值（避免 android.util.Log not mocked）
    // includeAndroidResources: Robolectric 测试需要真实资源（如工具 getDisplayName 的字符串资源）
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

    // Lint 基线棘轮：存量问题记录在 lint-baseline.xml，只有“新增”错误才会使构建失败。
    // 偿还存量后运行 ./gradlew updateLintBaseline 收紧基线。
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.gson)


    implementation(libs.oapi.sdk)
    implementation(libs.dingtalk)


    // LangChain4j (exclude JDK http-client, use OkHttp adapter for Android)
    implementation(libs.langchain4j.core)
    implementation(libs.langchain4j.openai) {
        exclude(group = "dev.langchain4j", module = "langchain4j-http-client-jdk")
    }
    implementation(libs.langchain4j.anthropic) {
        exclude(group = "dev.langchain4j", module = "langchain4j-http-client-jdk")
    }
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    // androidx.webkit —— WebViewCompat.addDocumentStartJavaScript(文档开始前注入,做反检测)
    // + WebViewFeature 能力探测 + ProxyController(浏览器代理)。系统 WebView 增强能力的入口。
    implementation(libs.androidx.webkit)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.utilcode)
    implementation(libs.ok2curl)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.mmkv)
    implementation(libs.security.crypto)
    implementation(libs.adapter)
    implementation(libs.glide)
    implementation(libs.glide.transformations)
    implementation(libs.coil.compose)
    implementation(libs.markwon.core)
    implementation(libs.markwon.image)
    implementation(libs.easyfloat)


    // ZXing 二维码/条形码扫描
    implementation(libs.zxing)

    // NanoHTTPD 嵌入式 HTTP 服务器（局域网配置服务）
    implementation(libs.nanohttpd)

    // Rhino — Mozilla 纯 Java JS 引擎，用于 run_code 沙箱（无需 Shizuku，JVM 内执行）
    implementation(libs.rhino)

    // GeckoView(Firefox 内核)已移除以瘦身 APK(约 -180MB:libxul.so 144MB + omni.ja
    // 13MB + 一众 mozilla .so)。浏览器统一用系统 WebView(SystemWebViewEngine,0 包体)。
    // 扩展能力改由自建注入式插件生态承载;反爬靠 document-start 注入 + 服务端兜底。

    // Shizuku API —— shell 级权限增强（触控注入 / 截屏 / 按键 / 系统设置）
    // 用户需安装 Shizuku App 并通过无线调试授权一次
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Shizuku 全自动配置(可选增强)—— 纯 Java ADB 客户端:连本机无线调试自动 pair + 跑 shell
    // 拉起 Shizuku,配合无障碍读配对码,逼近「一键」。libadb-android 实现 Android 11 无线配对
    // (SPAKE2 + TLS),conscrypt 提供 TLS,sun-security-android 提供 X509 证书生成。仅 11+ 可单机配对。
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    // mpv-android-lib —— FFmpeg + libplacebo + libass 播放引擎（MIT 协议）
    // 已移除以瘦身 APK(约 -25MB):其原生库 libmpv/libav*/libplacebo/libass 占 ~25MB,
    // 而 MpvController 目前是 stub(播放未接通),这些 .so 是纯死重。
    // 视频库改为「按需」:真正接通播放时,改为进入视频库时从远端下载该 .so 集合后
    //   System.load 动态加载(需一个托管 .so 的下载地址);在那之前先不打进基础包。
    // implementation(libs.mpv.android.lib)

    // Jetpack Compose
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.navigation)
    implementation(libs.compose.activity)
    implementation(libs.compose.lifecycle)
    implementation(libs.compose.lifecycle.runtime)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)


    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockito.core)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                val versionName = android.defaultConfig.versionName ?: "0.0.0"
                // ABI 分包后每个 output 带不同架构 → 文件名必须含 ABI,否则同名冲突打包失败
                val abi = output.variantOutputConfiguration.filters
                    .find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                    ?.identifier
                // 分包带具体 ABI;universal 通用包无 ABI 过滤 → 标成 _universal 以区分
                val abiTag = if (abi != null) "_$abi" else "_universal"
                val fileName = "OctopusMobile_v${versionName}${abiTag}_${getDateTime()}.apk"
                println("output file name: $fileName")
                output.outputFileName.set(fileName)
            }
        }
    }
}

fun getVersionGit(): String {
    return try {
        val process1 = Runtime.getRuntime().exec("git rev-parse --abbrev-ref HEAD")
        val reader1 = BufferedReader(InputStreamReader(process1.inputStream))
        val branch = reader1.readLine()?.trim()
        reader1.close()
        process1.waitFor()

        val process2 = Runtime.getRuntime().exec("git rev-parse HEAD")
        val reader2 = BufferedReader(InputStreamReader(process2.inputStream))
        val sha1 = reader2.readLine()?.trim()
        reader2.close()
        process2.waitFor()

        "\"${branch ?: "unknown"}_${sha1 ?: "unknown"}\""
    } catch (e: Exception) {
        "\"unknown_unknown\""
    }
}

fun getDateTime(): String {
    val df = SimpleDateFormat("yyyyMMdd_HHmmss");
    return df.format(Date());
}

fun getParameter(key: String, defaultValue: String): String {
    var value = defaultValue
    val hasProperty = project.hasProperty(key)
    if (hasProperty) {
        val property = project.properties[key] as String?
        if (!property.isNullOrEmpty()) {
            value = property
            println("get property[$key]from project:$value")
            return value
        }
    }
    val localPropertiesFile = project.rootProject.file("local.properties")
    val localProperties = Properties()
    if (localPropertiesFile.exists()) {
        localProperties.load(localPropertiesFile.inputStream())
        val hasLocalProperty = localProperties.hasProperty(key)
        if (hasLocalProperty) {
            val property = localProperties[key] as String?
            if (!property.isNullOrEmpty()) {
                value = property
                println("get property[$key]from local:$value")
                return value
            }
        }
    }
    println("get property[$key] from default:$value")
    return value
}

// ── JaCoCo 覆盖率报告 ──────────────────────────────────────────────
// 用法:./gradlew :app:jacocoTestReport
// 报告:app/build/reports/jacoco/jacocoTestReport/html/index.html
// 排除:生成的 BuildConfig、R 类、Compose UI、Activity 等(无 JVM 单测价值)
val jacocoExcludes = listOf(
    "**/BuildConfig.*",
    "**/R.class",
    "**/R\$*",
    "**/*\$Companion*",
    "**/*\$Lambda*",
    "**/com/apk/claw/android/ui/compose/**",  // Compose UI 无 JVM 单测
    "**/*Activity*",                            // Activity 需 instrumentation
    "**/*Application*",                         // Application 启动需 Android 环境
)

tasks.register<JacocoReport>("jacocoTestReport") {
    group = "verification"
    description = "Generates JaCoCo coverage report for debug unit tests"
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    sourceDirectories.setFrom(files("${project.projectDir}/src/main/java"))

    classDirectories.setFrom(
        fileTree("${project.layout.buildDirectory.get()}/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes") {
            exclude(jacocoExcludes)
        }
    )

    executionData.setFrom(
        fileTree("${project.layout.buildDirectory.get()}/outputs/unit_test_code_coverage").include("**/*.exec")
    )
}
