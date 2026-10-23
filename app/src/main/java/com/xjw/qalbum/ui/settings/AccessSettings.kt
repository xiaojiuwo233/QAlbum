package com.xjw.qalbum.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.ui.components.SegmentPosition
import com.xjw.qalbum.ui.components.SegmentedGroup
import com.xjw.qalbum.ui.components.SegmentedGroupScope
import org.koin.androidx.compose.koinViewModel

/**
 * 文件访问：选 Shizuku 或 Root，下面只显示所选方式的状态与下一步（安装、启动、授权），以及能不能读到图片目录。
 * 进来和从别的应用（Shizuku、Root 管理器）回来时自动重新检测。
 */
@Composable
fun AccessSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val mode by viewModel.accessMode.collectAsState()
    val access by viewModel.access.collectAsState()
    val path by viewModel.chatpicPath.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(mode) { viewModel.checkAccess(mode) }
    LifecycleResumeEffect(Unit) {
        viewModel.checkAccess()
        onPauseOrDispose { }
    }

    SettingsPageScaffold(
        SettingsCategory.ACCESS, onBack, sharedScope, animatedScope,
        actions = {
            IconButton(onClick = { viewModel.checkAccess(showProgress = true) }, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Outlined.Refresh, contentDescription = "重新检测")
            }
        },
    ) { _ ->
        SegmentedGroup(header = "访问方式") {
            item("shizuku") { position ->
                RadioOptionItem(
                    position = position,
                    selected = mode == AccessMode.SHIZUKU,
                    title = "Shizuku",
                    subtitle = "不需要 Root，授权一次就能用",
                    onClick = { viewModel.setAccessMode(AccessMode.SHIZUKU) },
                )
            }
            item("root") { position ->
                RadioOptionItem(
                    position = position,
                    selected = mode == AccessMode.ROOT,
                    title = "Root",
                    subtitle = "还能读取聊天记录，按群聊和发送人整理图片",
                    onClick = { viewModel.setAccessMode(AccessMode.ROOT) },
                )
            }
        }
        SegmentedGroup(header = "状态") {
            accessStatusItems(
                state = access?.takeIf { it.mode == mode },
                path = path,
                onRetry = { viewModel.checkAccess(showProgress = true) },
                onRequestShizuku = viewModel::requestShizuku,
                onOpenShizuku = { openShizuku(context) },
                onInstallShizuku = { openUrl(context, SHIZUKU_SITE) },
            )
        }
        SettingsNote(
            if (mode == AccessMode.SHIZUKU) "手机重启后要在 Shizuku 里重新启动服务。"
            else "在 Root 管理器（Magisk、KernelSU 等）里允许 QAlbum。",
        )
    }
}

/** 所选访问方式的状态行（设置页与首次引导共用） */
internal fun SegmentedGroupScope.accessStatusItems(
    state: AccessState?,
    path: String,
    onRetry: () -> Unit,
    onRequestShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
    onInstallShizuku: () -> Unit,
) {
    if (state == null || state.checking) {
        item("checking") { position -> StatusItem(position, StatusTone.BUSY, "检测中…") }
        return
    }
    val shizuku = state.shizuku
    when {
        shizuku?.state == ShizukuState.NotInstalled -> item("permission") { position ->
            StatusItem(position, StatusTone.ERROR, "没有安装 Shizuku", "安装后启动服务，再回来授权", "去下载", onInstallShizuku)
        }
        shizuku?.state == ShizukuState.NotRunning -> item("permission") { position ->
            StatusItem(position, StatusTone.WARNING, "Shizuku 没有运行", "在 Shizuku 里用无线调试或 Root 启动服务", "打开", onOpenShizuku)
        }
        shizuku?.state == ShizukuState.PermissionDenied -> item("permission") { position ->
            StatusItem(position, StatusTone.WARNING, "还没有授权", "允许 QAlbum 使用 Shizuku", "授权", onRequestShizuku)
        }
        state.granted == false -> item("permission") { position ->
            if (state.mode == AccessMode.ROOT) {
                StatusItem(position, StatusTone.ERROR, "没有 Root 权限", "在 Root 管理器里允许 QAlbum 后重试", "重试", onRetry)
            } else {
                StatusItem(position, StatusTone.ERROR, "连不上 Shizuku", "在 Shizuku 里重新启动服务后重试", "重试", onRetry)
            }
        }
        else -> item("permission") { position ->
            StatusItem(position, StatusTone.GOOD, if (state.mode == AccessMode.ROOT) "已获得 Root 权限" else "已授权 Shizuku")
        }
    }
    if (state.granted == true) {
        item("folder") { position: SegmentPosition ->
            if (state.folderFound == true) {
                StatusItem(position, StatusTone.GOOD, "能读取 QQ 图片目录", shortPath(path))
            } else {
                StatusItem(position, StatusTone.WARNING, "找不到 QQ 图片目录", "确认装了 QQ，或在「扫描」里改目录")
            }
        }
    }
}

/** 长路径只留最后几段 */
internal fun shortPath(path: String): String {
    val parts = path.trimEnd('/').split('/')
    return if (parts.size <= 4) path else "…/" + parts.takeLast(3).joinToString("/")
}

internal fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
