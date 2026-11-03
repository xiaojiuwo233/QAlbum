package com.xjw.qalbum.ui.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.xjw.qalbum.analytics.Analytics
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.ui.common.openUrl
import com.xjw.qalbum.ui.components.SegmentedGroup
import com.xjw.qalbum.ui.components.SegmentedListItem
import com.xjw.qalbum.ui.components.ShapeIcon
import com.xjw.qalbum.ui.settings.PathDialog
import com.xjw.qalbum.ui.settings.RadioOptionItem
import com.xjw.qalbum.ui.settings.SHIZUKU_SITE
import com.xjw.qalbum.ui.settings.SettingsViewModel
import com.xjw.qalbum.ui.settings.accessStatusItems
import com.xjw.qalbum.ui.settings.openShizuku
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * 首次打开的引导：认识 QAlbum → 用户协议与免责声明（看满 10 秒才能同意）→ 选访问方式并授权 → 确认图片目录、开始扫描。
 * 不能跳过；之后在「设置 → 关于」里能再看。
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    // 协议页还要等几秒、有没有拉到底：放在这里，翻回上一页再回来不重新计时
    var agreementWait by rememberSaveable { mutableIntStateOf(AGREEMENT_WAIT_SECONDS) }
    var agreementRead by rememberSaveable { mutableStateOf(false) }
    val finish: (scan: Boolean) -> Unit = { scan ->
        if (scan) viewModel.startFullScan()
        viewModel.finishOnboarding(scan)
        onDone()
    }
    BackHandler(enabled = page > 0) { page-- }
    val analytics = koinInject<Analytics>()
    LaunchedEffect(page) { analytics.screen("onboarding_" + ONBOARDING_PAGES[page]) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            PageDots(
                count = PAGES,
                current = page,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
            )
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
                },
                label = "onboardingPage",
                modifier = Modifier.weight(1f),
            ) { current ->
                when (current) {
                    0 -> WelcomePage(onNext = { page = 1 })
                    1 -> AgreementPage(
                        remaining = agreementWait,
                        read = agreementRead,
                        onTick = { agreementWait = (agreementWait - 1).coerceAtLeast(0) },
                        onReadToEnd = { agreementRead = true },
                        onAgree = {
                            viewModel.acceptAgreement()
                            page = 2
                        },
                    )
                    2 -> AccessPage(viewModel, onBack = { page = 1 }, onNext = { page = 3 })
                    else -> ScanPage(viewModel, onBack = { page = 2 }, onFinish = finish)
                }
            }
        }
    }
}

private const val PAGES = 4

/** 各页的名字（统计用） */
private val ONBOARDING_PAGES = listOf("welcome", "agreement", "access", "scan")

/** 协议页至少要看这么久才能同意 */
private const val AGREEMENT_WAIT_SECONDS = 10

/** 页码：当前页是拉长的胶囊 */
@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            val width by animateDpAsState(if (index == current) 24.dp else 8.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "dot")
            Box(
                Modifier
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
    }
}

/** 每页的骨架：可滚动的正文 + 底部按钮 */
@Composable
private fun PageFrame(
    buttons: @Composable () -> Unit,
    scroll: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(scroll)
                .padding(horizontal = 24.dp),
            content = content,
        )
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) { buttons() }
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineMediumEmphasized)
    Spacer(Modifier.height(8.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun WelcomePage(onNext: () -> Unit) {
    PageFrame(buttons = {
        Button(onClick = onNext, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text("开始设置")
        }
    }) {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { HeroBadge() }
        Spacer(Modifier.height(32.dp))
        Text(
            "欢迎使用 QAlbum",
            style = MaterialTheme.typography.displaySmallEmphasized,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "像系统相册一样清理与查看 QQ 图片缓存",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(32.dp))
        Feature(Icons.Outlined.PhotoLibrary, "看遍缓存里的图", "按时间浏览 QQ 存下的所有图片")
        Feature(Icons.Outlined.CleaningServices, "成批清理", "缩略图、旧图、表情包按规则一键处理，原图还能压缩")
        Feature(Icons.Outlined.RestoreFromTrash, "删错能恢复", "删除先进回收站，到期才真正删掉")
    }
}

/** 欢迎页的大图标：慢慢转动的曲奇形 */
@Composable
private fun HeroBadge() {
    val rotation by rememberInfiniteTransition(label = "hero").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart),
        label = "heroRotation",
    )
    Box(contentAlignment = Alignment.Center) {
        Surface(
            shape = MaterialShapes.Cookie12Sided.toShape(),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(160.dp).graphicsLayer { rotationZ = rotation },
        ) {}
        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(64.dp))
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ShapeIcon(icon, containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, size = 48.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 协议更新后（老用户）再同意一次：同样要看满 10 秒、拉到底，不同意就退出。同意之前不收集任何统计。
 */
@Composable
fun AgreementUpdateScreen(onAgreed: () -> Unit, viewModel: SettingsViewModel = koinViewModel()) {
    var wait by rememberSaveable { mutableIntStateOf(AGREEMENT_WAIT_SECONDS) }
    var read by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(top = 24.dp),
        ) {
            AgreementPage(
                remaining = wait,
                read = read,
                onTick = { wait = (wait - 1).coerceAtLeast(0) },
                onReadToEnd = { read = true },
                onAgree = {
                    viewModel.acceptAgreement()
                    onAgreed()
                },
                title = "用户协议已更新",
                subtitle = "新增了匿名统计与崩溃日志的说明，可随时在「设置 → 关于」里关闭。",
            )
        }
    }
}

/** 用户协议与免责声明：倒数 [remaining] 秒、并且拉到底看完（[read]）才能同意；不同意就退出 */
@Composable
private fun AgreementPage(
    remaining: Int,
    read: Boolean,
    onTick: () -> Unit,
    onReadToEnd: () -> Unit,
    onAgree: () -> Unit,
    title: String = "用户协议与免责声明",
    subtitle: String = "使用前请仔细阅读。删除的文件无法恢复，请先备份重要数据。",
) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    LaunchedEffect(remaining) {
        if (remaining > 0) {
            delay(1_000)
            onTick()
        }
    }
    // 拉到离底部不到 24dp（或者一屏就放得下）算看完；量出高度之前 maxValue 是 Int.MAX_VALUE
    val slop = with(LocalDensity.current) { 24.dp.toPx() }
    LaunchedEffect(scroll, read) {
        if (read) return@LaunchedEffect
        snapshotFlow { scroll.maxValue != Int.MAX_VALUE && scroll.value >= scroll.maxValue - slop }.first { it }
        onReadToEnd()
    }
    PageFrame(scroll = scroll, buttons = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { context.findActivity()?.finishAffinity() }) { Text("不同意") }
            Spacer(Modifier.weight(1f))
            Button(onClick = onAgree, enabled = remaining == 0 && read, shapes = ButtonDefaults.shapes()) {
                // 最长的两种文字垫在下面：换文字时按钮宽度不变
                Box(contentAlignment = Alignment.Center) {
                    Text("同意并继续（10 秒）", modifier = Modifier.alpha(0f))
                    Text("请完整阅读后继续", modifier = Modifier.alpha(0f))
                    Text(
                        when {
                            remaining > 0 -> "同意并继续（$remaining 秒）"
                            !read -> "请完整阅读后继续"
                            else -> "同意并继续"
                        },
                    )
                }
            }
        }
    }) {
        Spacer(Modifier.height(8.dp))
        ShapeIcon(
            Icons.Outlined.Gavel,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialShapes.Cookie9Sided.toShape(),
            size = 64.dp,
            iconSize = 30.dp,
        )
        Spacer(Modifier.height(20.dp))
        PageTitle(title, subtitle)
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
            AgreementText(Modifier.padding(20.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun AccessPage(viewModel: SettingsViewModel, onBack: () -> Unit, onNext: () -> Unit) {
    val mode by viewModel.accessMode.collectAsState()
    val access by viewModel.access.collectAsState()
    val path by viewModel.chatpicPath.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(mode) { viewModel.checkAccess(mode) }
    LifecycleResumeEffect(Unit) {
        viewModel.checkAccess()
        onPauseOrDispose { }
    }
    val ready = access?.takeIf { it.mode == mode }?.granted == true
    PageFrame(buttons = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("上一步") }
            Spacer(Modifier.weight(1f))
            Button(onClick = onNext, shapes = ButtonDefaults.shapes()) { Text(if (ready) "下一步" else "稍后再授权") }
        }
    }) {
        Spacer(Modifier.height(8.dp))
        ShapeIcon(
            Icons.Outlined.Key,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            shape = MaterialShapes.Cookie9Sided.toShape(),
            size = 64.dp,
            iconSize = 30.dp,
        )
        Spacer(Modifier.height(20.dp))
        PageTitle("文件访问", "QQ 的图片存在它自己的目录里，要通过 Shizuku 或 Root 才能读取。")
        SegmentedGroup {
            item("shizuku") { position ->
                RadioOptionItem(
                    position = position,
                    selected = mode == AccessMode.SHIZUKU,
                    title = "Shizuku（推荐）",
                    subtitle = "不需要 Root，授权一次就能用",
                    onClick = { viewModel.setAccessMode(AccessMode.SHIZUKU) },
                )
            }
            item("root") { position ->
                RadioOptionItem(
                    position = position,
                    selected = mode == AccessMode.ROOT,
                    title = "Root",
                    subtitle = "之后可在设置里开启高级功能，按聊天整理图片",
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
    }
}

@Composable
private fun ScanPage(viewModel: SettingsViewModel, onBack: () -> Unit, onFinish: (scan: Boolean) -> Unit) {
    val path by viewModel.chatpicPath.collectAsState()
    val access by viewModel.access.collectAsState()
    var editing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.checkAccess() }
    val granted = access?.granted == true
    val ready = access?.ready == true
    PageFrame(buttons = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onFinish(true) },
                enabled = ready,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Icon(Icons.Outlined.TravelExplore, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(8.dp))
                Text("开始扫描")
            }
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = onBack) { Text("上一步") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onFinish(false) }) { Text("以后再说") }
            }
        }
    }) {
        Spacer(Modifier.height(8.dp))
        ShapeIcon(
            Icons.Outlined.TravelExplore,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialShapes.Cookie9Sided.toShape(),
            size = 64.dp,
            iconSize = 30.dp,
        )
        Spacer(Modifier.height(20.dp))
        PageTitle("建立索引", "扫描一遍 QQ 图片目录。图片多的话要几分钟，在后台进行，可以先去别的页面。")
        SegmentedGroup(header = "图片目录") {
            item("path") { position ->
                SegmentedListItem(
                    position = position,
                    onClick = { editing = true },
                    leading = { ShapeIcon(Icons.Outlined.FolderOpen) },
                    headline = { Text(if (path == SettingsRepository.DEFAULT_CHATPIC_PATH) "QQ 默认目录" else "自定义目录") },
                    supporting = { Text(path) },
                    trailing = { Icon(Icons.Outlined.Edit, contentDescription = "修改") },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        val checking = access == null || access?.checking == true
        Text(
            when {
                checking -> "正在检查图片目录…"
                ready -> "TIM、国际版 QQ 的目录不一样，点上面修改。"
                access?.folderFound == true && access?.folderReadable == false -> "目录存在，但当前方式没有读取权限。请回上一步更换访问方式。"
                granted -> "找不到这个目录。确认装了 QQ，TIM、国际版 QQ 点上面修改。"
                else -> "还不能读取图片目录，先回上一步授权。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (checking || ready) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    if (editing) {
        PathDialog(
            current = path,
            onDismiss = { editing = false },
            onSave = { value ->
                editing = false
                if (viewModel.setChatpicPath(value)) viewModel.checkAccess(path = value.trim())
            },
            onReset = {
                editing = false
                viewModel.setChatpicPath(SettingsRepository.DEFAULT_CHATPIC_PATH)
                viewModel.checkAccess(path = SettingsRepository.DEFAULT_CHATPIC_PATH)
            },
        )
    }
}
