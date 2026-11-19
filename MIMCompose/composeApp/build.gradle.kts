import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    // 只有一个桌面目标：本机没有 Android SDK，先验证 UI 与状态架构。
    // 有 SDK 后在此处加 androidTarget()，并把源集挪到 commonMain 即可。
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.junesoon.mim.app.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "MiM"
            packageVersion = "1.0.0"
        }
    }
}
