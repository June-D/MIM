// 根构建脚本：只声明插件版本，实际配置在各模块。
//
// Kotlin 2.0.21 + Compose Multiplatform 1.7.3 是当前稳定组合。
// Android 目标暂不声明：本机没有 Android SDK，加上会直接让配置失败。
// 有 SDK 后只需在 composeApp/build.gradle.kts 里加 androidTarget()。
plugins {
    kotlin("multiplatform") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
}
