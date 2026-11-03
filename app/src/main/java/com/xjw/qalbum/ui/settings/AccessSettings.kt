package com.xjw.qalbum.ui.settings

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.ui.common.openUrl
import com.xjw.qalbum.ui.components.SegmentPosition
import com.xjw.qalbum.ui.components.SegmentedGroup
import com.xjw.qalbum.ui.components.MarkdownText
import com.xjw.qalbum.ui.components.SegmentedGroupScope
import com.xjw.qalbum.ui.components.SegmentedSwitchItem
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

/**
 * 文件访问：选 Shizuku 或 Root，下面只显示所选方式的状态与下一步（安装、启动、授权），以及能不能读到图片目录。
 * 进来和从别的应用（Shizuku、Root 管理器）回来时自动重新检测。选了 Root 时最下面可以开启高级功能
 * （读取并解密 QQ 数据库，见 ChatFeatures；只能在这里开），第一次开要看满 10 秒风险提示。
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
    val advanced by viewModel.advancedFeatures.collectAsState()
    val acknowledged by viewModel.advancedRiskAcknowledged.collectAsState()
    var confirmAdvanced by rememberSaveable { mutableStateOf(false) }
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
                    subtitle = "还可以开启高级功能，按群聊和发送人整理图片",
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
        // 只能在 Root 下开；切到 Shizuku 后开着的也留着开关，好关掉
        if (mode == AccessMode.ROOT || advanced) {
            SegmentedGroup(header = "高级功能") {
                item("advanced") { position ->
                    SegmentedSwitchItem(
                        position = position,
                        icon = Icons.Outlined.Forum,
                        title = "高级功能",
                        subtitle = "读取 QQ 聊天记录，按群聊和发送人整理、清理图片",
                        checked = advanced,
                        onCheckedChange = { on ->
                            when {
                                !on -> viewModel.setAdvancedFeatures(false)
                                acknowledged == true -> viewModel.setAdvancedFeatures(true)
                                acknowledged == false -> confirmAdvanced = true
                            }
                        },
                    )
                }
            }
        }
    }
    if (confirmAdvanced) {
        AdvancedRiskDialog(
            onConfirm = {
                confirmAdvanced = false
                viewModel.enableAdvancedFeatures()
            },
            onDismiss = {
                confirmAdvanced = false
                viewModel.cancelAdvancedFeatures()
            },
        )
    }
}

/** 第一次开启高级功能前的风险提示 */
private val ADVANCED_RISK = """
    高级功能会在 Root 权限下复制、读取并解密 QQ 的本地数据库，用于按群聊、私聊和发送人整理图片。开启前，请您知悉：

    - 数据库的读取与解密方法来源于互联网公开资料，并非腾讯公司提供或授权，本应用与腾讯公司不存在任何关联。
    - **该操作可能违反《腾讯 QQ 软件许可及服务协议》，存在账号被限制或封禁等风险。**
    - QQ 版本更新可能导致相关功能失效或结果不准确。
    - 本功能仅供学习与研究使用，**因使用本功能产生的一切后果由您自行承担。**
""".trimIndent()

/** 风险提示至少要看这么久才能开启 */
private const val ADVANCED_WAIT_SECONDS = 10

@Composable
private fun AdvancedRiskDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var remaining by rememberSaveable { mutableIntStateOf(ADVANCED_WAIT_SECONDS) }
    LaunchedEffect(remaining) {
        if (remaining > 0) {
            delay(1_000)
            remaining--
        }
    }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
        title = { Text("开启高级功能前请注意") },
        text = {
            MarkdownText(ADVANCED_RISK, onOpenUrl = { openUrl(context, it) }, modifier = Modifier.verticalScroll(rememberScrollState()))
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = remaining == 0, shapes = ButtonDefaults.shapes()) {
                // 两种文字叠在一起占同样的宽度：倒数结束时按钮不变宽，「取消」不会挪到它原来的位置下面
                Box(contentAlignment = Alignment.Center) {
                    Text("我已了解风险，开启", modifier = Modifier.alpha(if (remaining > 0) 0f else 1f))
                    if (remaining > 0) Text("请阅读（$remaining 秒）")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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
            when {
                state.folderFound != true -> StatusItem(position, StatusTone.WARNING, "找不到 QQ 图片目录", "确认装了 QQ，或在「扫描」里改目录")
                state.folderReadable == true -> StatusItem(position, StatusTone.GOOD, "能读取 QQ 图片目录", shortPath(path))
                else -> StatusItem(
                    position, StatusTone.ERROR, "没有图片目录的读取权限",
                    if (state.mode == AccessMode.SHIZUKU) "Shizuku 已授权，但 QQ 目录拒绝访问；已 Root 的设备可切换到 Root 方式"
                    else "当前权限无法读取目录，请检查目录权限与路径",
                )
            }
        }
    }
}

/** 长路径只留最后几段 */
internal fun shortPath(path: String): String {
    val parts = path.trimEnd('/').split('/')
    return if (parts.size <= 4) path else "…/" + parts.takeLast(3).joinToString("/")
}
