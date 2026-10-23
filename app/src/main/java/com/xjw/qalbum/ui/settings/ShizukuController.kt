package com.xjw.qalbum.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku 状态（文件访问页与首次引导检测时用）。
 *
 * 状态机：
 * - NotInstalled  ：当前设备没有装 Shizuku 应用（按包可见性探测）
 * - NotRunning    ：装了 Shizuku，但还没启动服务
 * - PermissionDenied ：服务在跑，但用户拒绝了授权
 * - Granted       ：已授权，可以使用
 */
enum class ShizukuState { NotInstalled, NotRunning, PermissionDenied, Granted }

data class ShizukuStatus(
    val state: ShizukuState,
    val versionCode: Int = 0,
)

/** 探测一次 Shizuku 当前状态，不依赖回调 */
fun probeShizuku(context: Context): ShizukuStatus {
    // pingBinder 在未安装时只是返回 false（不抛异常），无法区分「没装」和「装了没启动」：
    // 必须先查包是否存在，否则新用户永远看不到「前往安装」入口（Manifest 已声明
    // moe.shizuku.privileged.api 的 <queries> 可见性）
    val installed = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
    }.isSuccess
    if (!installed) return ShizukuStatus(ShizukuState.NotInstalled)
    return try {
        if (!Shizuku.pingBinder()) {
            ShizukuStatus(ShizukuState.NotRunning)
        } else {
            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            val version = runCatching { Shizuku.getVersion() }.getOrDefault(0)
            ShizukuStatus(
                state = if (granted) ShizukuState.Granted else ShizukuState.PermissionDenied,
                versionCode = version,
            )
        }
    } catch (t: Throwable) {
        // 已确认安装：API 异常按「服务未启动」归类
        ShizukuStatus(ShizukuState.NotRunning)
    }
}

/** 主动向 Shizuku 申请权限。结果通过 [Shizuku.OnRequestPermissionResultListener] 回调，不阻塞调用方 */
fun requestShizukuPermission(requestCode: Int = REQ_CODE) {
    try {
        if (Shizuku.isPreV11()) {
            // 兼容老版本：使用 ContextCompat 模式
            // 这里只是简单调用，老版本场景下用户量很少
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(requestCode)
        }
    } catch (_: Throwable) {
        // 没装/没起服务时 Shizuku.requestPermission 可能抛 IllegalStateException
    }
}

/** 打开 Shizuku 应用（去启动服务） */
internal fun openShizuku(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private const val REQ_CODE = 0x51AB1

/** Shizuku 官网（没装时去下载） */
internal const val SHIZUKU_SITE = "https://shizuku.rikka.app/"

/** Shizuku 管理端应用包名（与 AndroidManifest 的 <queries> 声明一致） */
private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
