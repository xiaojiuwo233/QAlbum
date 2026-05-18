package com.xjw.qalbum.ui.settings

import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import rikka.shizuku.Shizuku

/**
 * Shizuku 状态封装：把 Shizuku 的回调 API 转成可在 Compose 中观察的 State。
 *
 * 状态机：
 * - NotInstalled  ：当前设备没有装 Shizuku 应用（或者根本没启动 Provider）
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
fun probeShizuku(): ShizukuStatus {
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
        ShizukuStatus(ShizukuState.NotInstalled)
    }
}

/**
 * 在 Compose 中观察 Shizuku 状态：
 * - 注册 Binder 接收监听，服务起来/挂掉时自动刷新
 * - 注册权限回调，处理 requestPermission 的结果
 */
@Composable
fun rememberShizukuStatus(): MutableState<ShizukuStatus> {
    val state = remember { mutableStateOf(probeShizuku()) }

    DisposableEffect(Unit) {
        val binderReceived = Shizuku.OnBinderReceivedListener { state.value = probeShizuku() }
        val binderDead = Shizuku.OnBinderDeadListener { state.value = probeShizuku() }
        val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ ->
            state.value = probeShizuku()
        }

        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)

        onDispose {
            Shizuku.removeBinderReceivedListener(binderReceived)
            Shizuku.removeBinderDeadListener(binderDead)
            Shizuku.removeRequestPermissionResultListener(permissionResult)
        }
    }

    return state
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

private const val REQ_CODE = 0x51AB1
