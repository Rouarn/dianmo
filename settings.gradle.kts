pluginManagement {
    repositories {
        // 国内镜像优先，避免 dl.google.com 直连被阻断
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/public")
        // libsu
        maven("https://jitpack.io")
        google()
        mavenCentral()
    }
}

rootProject.name = "DianMo"
include(":app")
