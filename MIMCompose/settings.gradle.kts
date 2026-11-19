rootProject.name = "MIMCompose"

// 国内网络下 Maven Central 明显偏慢，可用 MIM_GRADLE_MIRRORS=1 启用镜像。
// 默认仍走官方仓库，保证 CI 与他人机器行为一致。
pluginManagement {
    repositories {
        if (System.getenv("MIM_GRADLE_MIRRORS") == "1") {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/google")
        }
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        if (System.getenv("MIM_GRADLE_MIRRORS") == "1") {
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/google")
        }
        google()
        mavenCentral()
    }
}

include(":composeApp")
