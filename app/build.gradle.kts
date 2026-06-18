import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// release 签名来源：本地优先 keystore.properties，CI 用环境变量；
// 两者都缺失（或密钥文件不存在）时回退 debug 签名，保证任何人 clone 后都能构建。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingProp(key: String, env: String): String? =
    (keystoreProps.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val releaseStorePath = signingProp("storeFile", "KEYSTORE_FILE")
val hasReleaseSigning = releaseStorePath != null && rootProject.file(releaseStorePath).exists()

android {
    namespace = "com.xjw.qalbum"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.xjw.qalbum"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 2
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // Room schema 导出位置，用于版本迁移
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStorePath!!)
                storePassword = signingProp("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingProp("keyAlias", "KEY_ALIAS")
                keyPassword = signingProp("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有正式密钥时用 release 签名（CI 每次签名一致、可覆盖升级）；否则回退 debug
            signingConfig = signingConfigs.getByName(if (hasReleaseSigning) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            // Material 3 Expressive 仍在实验阶段（MaterialExpressiveTheme、MotionScheme 等）
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi",
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // 扫描传输层的 IFileOps.aidl（Root/Shizuku 常驻文件服务）
        aidl = true
    }

    lint {
        disable += "NullSafeMutableLiveData"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    sourceSets.getByName("main") {
        // 后续 Phase 4 引入 sqlite-vec 时把预编译的 .so 放在这里
        jniLibs.srcDirs("src/main/jniLibs")
    }
}

dependencies {

    // --- AndroidX core ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.paging.runtime.ktx)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.datastore.preferences)

    // --- Compose BOM 统一版本 ---
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.compose.material.icons.extended)
    // XML 主题需要 Material Components 库（Theme.Material3.* 来自这里）
    implementation(libs.google.material)

    // --- Room 数据库 ---
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    // --- 图片加载 ---
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)

    // --- ML Kit （Phase 3 OCR 使用，先引入但不强制初始化）---
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.mlkit.image.labeling)
    implementation(libs.mlkit.barcode.scanning)

    // --- Shizuku（必选权限模式）---
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // --- libsu（Root 高级模式）---
    implementation(libs.libsu.core)
    implementation(libs.libsu.io)
    // RootService：扫描用的 root 进程常驻文件服务（原生 IO 替代逐条 shell 命令）
    implementation(libs.libsu.service)

    // --- 网络 & 序列化 ---
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // ML Kit Task 转 Kotlin suspend 的桥接（Task.await()）
    implementation(libs.kotlinx.coroutines.play.services)

    // --- 哈希 ---
    implementation(libs.zero.allocation.hashing)

    // --- DI (Koin) ---
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.koin.androidx.workmanager)

    // --- 日志 ---
    implementation(libs.timber)

    // --- 调试工具 ---
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // --- 测试 ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
