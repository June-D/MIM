import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
}

// 把 MIMCoreRust 的手写 JNI 绑定编译成 jar，供桌面端直接调用。
// 运行时仍需指向 Rust 动态库（MIM_NATIVE_LIB 或 -Dmim.native.lib）。
val nativeBindingsClasses = layout.buildDirectory.dir("native-bindings")
val compileNativeBindings = tasks.register<JavaCompile>("compileNativeBindings") {
    source = fileTree(rootProject.file("../MIMCoreRust/java")) { include("**/*.java") }
    classpath = files()
    destinationDirectory.set(nativeBindingsClasses)
    options.encoding = "UTF-8"
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
val nativeBindingsJar = tasks.register<Jar>("nativeBindingsJar") {
    dependsOn(compileNativeBindings)
    archiveFileName.set("mim-native-bindings.jar")
    destinationDirectory.set(layout.buildDirectory.dir("native-bindings-jar"))
    from(nativeBindingsClasses)
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
                implementation("io.ktor:ktor-client-core:2.3.12")
                implementation("io.ktor:ktor-client-cio:2.3.12")
                implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
                implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
                implementation(files(nativeBindingsJar.map { it.archiveFile }))
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
