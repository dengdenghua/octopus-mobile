pluginManagement {
    repositories {
        maven(url = "https://maven.aliyun.com/repository/public")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven(url = "https://jitpack.io")
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // mavenCentral/google first — some artifacts (e.g. mpv-android-lib)
        // are not mirrored by aliyun and would fail to resolve otherwise.
        mavenCentral()
        google()
        maven(url = "https://maven.aliyun.com/repository/public")
        maven(url = "https://jitpack.io")
        // Mozilla GeckoView is hosted on Mozilla's own Maven, not on
        // Central. Without this, processDebugNavigationResources fails
        // with "Could not find org.mozilla.geckoview:geckoview:..."
        maven(url = "https://maven.mozilla.org/maven2")
    }
}

rootProject.name = "OctopusMobile"
include(":app")
