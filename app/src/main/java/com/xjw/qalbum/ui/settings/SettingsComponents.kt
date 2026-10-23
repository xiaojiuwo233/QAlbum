package com.xjw.qalbum.ui.settings

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.ui.components.BackButton
import com.xjw.qalbum.ui.components.ConnectedChoiceRow
import com.xjw.qalbum.ui.components.SegmentPosition
import com.xjw.qalbum.ui.components.SegmentedCard
import com.xjw.qalbum.ui.components.SegmentedListItem
import com.xjw.qalbum.ui.components.ShapeIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 容器变换共享元素的 key：首页入口与对应二级页根容器共用 */
internal fun settingsSharedKey(route: String): String = "settings-container-$route"

/** Container Transform 共享元素：scope 为空时（如预览）不做动画 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.settingsContainer(
    key: String,
    sharedScope: SharedTransitionScope?,
    animatedScope: AnimatedVisibilityScope?,
): Modifier {
    if (sharedScope == null || animatedScope == null) return this
    val boundsSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Rect>()
    return with(sharedScope) {
        this@settingsContainer.sharedBounds(
            sharedContentState = rememberSharedContentState(key = key),
            animatedVisibilityScope = animatedScope,
            boundsTransform = { _, _ -> boundsSpec },
        )
    }
}

/** 页面里发提示：新的提示顶掉还在显示的旧提示 */
internal class SettingsNotifier(private val host: SnackbarHostState, private val scope: CoroutineScope) {
    operator fun invoke(message: String) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(message)
        }
    }
}

/**
 * 二级页脚手架：大标题栏（滚动收起）+ 可滚动内容 + 提示条。根容器从设置首页的入口「长大」而来（容器变换）。
 */
@Composable
internal fun SettingsPageScaffold(
    category: SettingsCategory,
    onBack: () -> Unit,
    sharedScope: SharedTransitionScope?,
    animatedScope: AnimatedVisibilityScope?,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.(notify: SettingsNotifier) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify = remember(snackbar, scope) { SettingsNotifier(snackbar, scope) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier
            .settingsContainer(settingsSharedKey(category.route), sharedScope, animatedScope)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(category.title) },
                navigationIcon = { BackButton(onBack) },
                actions = actions,
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            content(notify)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 一组下面的说明文字（不放进卡片，免得每张卡都一大段字） */
@Composable
internal fun SettingsNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

/** 卡片里的说明文字 */
@Composable
internal fun SettingsBlurb(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 带标题的单选按钮组卡片：[options] 连成一排（M3E 连接式按钮组） */
@Composable
internal fun <T> ChoiceCard(
    position: SegmentPosition,
    title: String,
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    icon: ImageVector? = null,
    description: String? = null,
    optionIcon: ((T) -> ImageVector)? = null,
) {
    SegmentedCard(position, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Spacer(Modifier.size(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (description != null) {
                    Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        ConnectedChoiceRow(options = options, selected = selected, label = label, onSelect = onSelect, icon = optionIcon)
    }
}

/** 单选项（如访问方式）：整行可点，前面是单选按钮，选中时换成强调色 */
@Composable
internal fun RadioOptionItem(
    position: SegmentPosition,
    selected: Boolean,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    SegmentedListItem(
        position = position,
        selected = selected,
        onClick = onClick,
        leading = { RadioButton(selected = selected, onClick = null) },
        headline = { Text(title) },
        supporting = subtitle?.let { { Text(it) } },
        trailing = trailing,
    )
}

/** 状态的语气：决定图标与颜色 */
internal enum class StatusTone { GOOD, WARNING, ERROR, NEUTRAL, BUSY }

/** 状态行：前面一个带色调的状态图标，后面可放一个操作按钮 */
@Composable
internal fun StatusItem(
    position: SegmentPosition,
    tone: StatusTone,
    title: String,
    subtitle: String? = null,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        StatusTone.GOOD -> colors.primaryContainer to colors.onPrimaryContainer
        StatusTone.WARNING -> colors.tertiaryContainer to colors.onTertiaryContainer
        StatusTone.ERROR -> colors.errorContainer to colors.onErrorContainer
        StatusTone.NEUTRAL, StatusTone.BUSY -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    }
    SegmentedListItem(
        position = position,
        leading = {
            if (tone == StatusTone.BUSY) {
                LoadingIndicator(Modifier.size(40.dp), color = colors.primary)
            } else {
                ShapeIcon(
                    when (tone) {
                        StatusTone.GOOD -> Icons.Outlined.CheckCircle
                        StatusTone.WARNING -> Icons.Outlined.WarningAmber
                        StatusTone.ERROR -> Icons.Outlined.ErrorOutline
                        else -> Icons.Outlined.Info
                    },
                    containerColor = container,
                    contentColor = content,
                )
            }
        },
        headline = { Text(title) },
        supporting = subtitle?.let { { Text(it) } },
        trailing = action?.let { label ->
            { FilledTonalButton(onClick = onAction, shapes = ButtonDefaults.shapes()) { Text(label) } }
        },
    )
}

/** 设置首页入口的图标颜色：每组一种色调 */
@Composable
internal fun groupColors(group: SettingsGroup): Pair<Color, Color> {
    val colors = MaterialTheme.colorScheme
    return when (group) {
        SettingsGroup.FILES -> colors.primaryContainer to colors.onPrimaryContainer
        SettingsGroup.DISPLAY -> colors.tertiaryContainer to colors.onTertiaryContainer
        SettingsGroup.APP -> colors.secondaryContainer to colors.onSecondaryContainer
    }
}
