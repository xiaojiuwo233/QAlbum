import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
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
val hasKeystoreFile = releaseStorePath != null && rootProject.file(releaseStorePath).exists()
val hasReleaseSigning = hasKeystoreFile && listOf(
    signingProp("storePassword", "KEYSTORE_PASSWORD"),
    signingProp("keyAlias", "KEY_ALIAS"),
    signingProp("keyPassword", "KEY_PASSWORD"),
).all { it != null }

// keystore 文件在但口令/别名不全：配置期立即失败，避免拖到签名任务晚期报难懂错误
if (hasKeystoreFile && !hasReleaseSigning) {
    throw GradleException(
        "找到 release keystore（$releaseStorePath）但 storePassword/keyAlias/keyPassword 不全，" +
            "请补齐 keystore.properties 或 KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD 环境变量"
    )
}
// CI 配置了签名 Secret 时传 -PrequireReleaseSigning=true：签名信息缺失直接失败，
// 防止静默产出 debug 签名的「release」APK（真机覆盖安装 INSTALL_FAILED_UPDATE_INCOMPATIBLE）
if (providers.gradleProperty("requireReleaseSigning").getOrElse("false").toBoolean() && !hasReleaseSigning) {
    throw GradleException("requireReleaseSigning=true 但未配置完整的 release 签名信息")
}

// ===== 版本号：基于 git =====
// versionName = <baseVersionName>.<短hash>（如 0.2.0.a1b2c3d）；
// versionCode = HEAD 提交时间戳的分钟数（%ct/60）。
// 项目开启了 configuration cache，用 providers.exec 取 git 信息（缓存友好）；
// 并读取 .git/HEAD 与 reflog 作为配置输入，使每次提交后缓存失效、hash/时间戳重新计算
// （否则命中旧缓存会导致 hash 不刷新）。git worktree/submodule 下 .git 为文件、
// 此锚点会失效，属可接受退化；CI 全新 checkout 不受影响。
val baseVersionName = "0.4.0"

@Suppress("UNUSED_VARIABLE")
val gitStateKey = listOf(".git/HEAD", ".git/logs/HEAD").joinToString("|") {
    providers.fileContents(rootProject.layout.projectDirectory.file(it)).asText.orNull.orEmpty()
}

fun gitText(vararg args: String): String? = try {
    val out = providers.exec {
        workingDir = rootDir
        isIgnoreExitValue = true
        commandLine("git", *args)
    }
    if (out.result.get().exitValue == 0) out.standardOutput.asText.get().trim().ifBlank { null } else null
} catch (_: Exception) {
    null // git 不在 PATH 或非 git 仓库：回退默认值
}

val gitShortHash: String = gitText("rev-parse", "HEAD")?.take(7) ?: "nogit"

// versionCode 用提交时间戳而非 rev-list count：count 在分支/PR/浅克隆/换机器间不单调，
// 用户装过分支构建后再装 main 构建会 INSTALL_FAILED_VERSION_DOWNGRADE，被迫卸载重装丢库。
// 时间戳分钟数跨分支单调且与代码新旧一致，浅克隆下同样可得；
// git 不可用时回退提交总数（再退 1），仅保住可构建性，不保证单调。
val gitCommitCount: Int = gitText("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
val gitVersionCode: Int = gitText("log", "-1", "--format=%ct")?.toLongOrNull()
    ?.let { (it / 60L).toInt() } ?: gitCommitCount

android {
    namespace = "com.xjw.qalbum"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.xjw.qalbum"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = gitVersionCode
        versionName = "$baseVersionName.$gitShortHash"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        ksp {
            arg("room.incremental", "true")
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
            // 有正式密钥时用 release 签名（CI 每次签名一致、可覆盖升级）；否则回退 debug，
            // 并在 versionName 上标注，让产物自证签名来源
            signingConfig = signingConfigs.getByName(if (hasReleaseSigning) "release" else "debug")
            if (!hasReleaseSigning) versionNameSuffix = "-debugsigned"
            // 仅 release 收窄到 arm64（面向 Shizuku/Root 真机 sideload，控制包体）；
            // debug 不过滤 ABI，保证 x86_64 模拟器可安装
            ndk { abiFilters += "arm64-v8a" }
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

    sourceSets.getByName("androidTest") {
        assets.srcDir("$projectDir/schemas")
    }
}

// Room schema 导出目录（写显式 Migration 依赖这些 JSON）。走官方插件把 schemas 注册为
// 受管任务输出——裸 KSP arg 在 build cache 命中时不会重放导出，DB 升级后新 schema 可能不落盘。
room {
    schemaDirectory("$projectDir/schemas")
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

    // QQ 加密库仅在应用私有的临时副本上读取，不接入 QAlbum 的 Room 数据库。
    implementation("net.zetetic:sqlcipher-android:4.10.0@aar")
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")

    // --- 图片加载 ---
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    // --- Shizuku（必选权限模式）---
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // --- libsu（Root 高级模式）---
    implementation(libs.libsu.core)
    implementation(libs.libsu.io)
    // RootService：扫描用的 root 进程常驻文件服务（原生 IO 替代逐条 shell 命令）
    implementation(libs.libsu.service)

    // --- 序列化 & 协程 ---
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

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
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
