package com.xjw.qalbum.ui.settings

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.android.material.color.utilities.Hct
import com.xjw.qalbum.domain.model.PaletteStyle
import com.xjw.qalbum.domain.model.ThemeMode
import com.xjw.qalbum.domain.model.ThemeSettings
import com.xjw.qalbum.ui.components.SegmentedCard
import com.xjw.qalbum.ui.components.SegmentedGroup
import com.xjw.qalbum.ui.components.SegmentedSwitchItem
import com.xjw.qalbum.ui.theme.seedColorScheme
import com.xjw.qalbum.ui.theme.supportsWallpaperColors
import org.koin.androidx.compose.koinViewModel

/**
 * 外观：深浅色（跟随系统 / 浅色 / 深色）、纯黑背景；配色跟随壁纸（Android 12+），或自选主题色与配色风格，改了立刻生效。
 */
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val theme by viewModel.theme.collectAsState()
    val wallpaper = theme.dynamicColor && supportsWallpaperColors
    var customizing by rememberSaveable { mutableStateOf(false) }

    SettingsPageScaffold(SettingsCategory.APPEARANCE, onBack, sharedScope, animatedScope) { _ ->
        SegmentedGroup(header = "主题") {
            item("mode") { position ->
                ChoiceCard(
                    position = position,
                    title = "深浅色",
                    options = ThemeMode.entries,
                    selected = theme.mode,
                    label = ::themeModeLabel,
                    optionIcon = { mode ->
                        when (mode) {
                            ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
                            ThemeMode.LIGHT -> Icons.Outlined.LightMode
                            ThemeMode.DARK -> Icons.Outlined.DarkMode
                        }
                    },
                    onSelect = viewModel::setThemeMode,
                )
            }
            item("black") { position ->
                SegmentedSwitchItem(
                    position = position,
                    icon = Icons.Outlined.Contrast,
                    title = "纯黑背景",
                    subtitle = if (theme.mode == ThemeMode.LIGHT) "深色时生效" else "深色时背景纯黑",
                    checked = theme.pureBlack,
                    onCheckedChange = viewModel::setPureBlack,
                )
            }
        }

        SegmentedGroup(header = "配色") {
            if (supportsWallpaperColors) {
                item("wallpaper") { position ->
                    SegmentedSwitchItem(
                        position = position,
                        icon = Icons.Outlined.Wallpaper,
                        title = "跟随壁纸",
                        subtitle = "用壁纸的颜色",
                        checked = theme.dynamicColor,
                        onCheckedChange = viewModel::setDynamicColor,
                    )
                }
            }
            if (!wallpaper) {
                item("seed") { position ->
                    SegmentedCard(position, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        TitleRow(Icons.Outlined.Palette, "主题色")
                        SeedPalette(theme, onSelect = viewModel::setSeedColor, onCustom = { customizing = true })
                    }
                }
                item("style") { position ->
                    ChoiceCard(
                        position = position,
                        title = "配色风格",
                        icon = Icons.Outlined.Style,
                        options = PaletteStyle.entries,
                        selected = theme.style,
                        label = ::paletteStyleLabel,
                        onSelect = viewModel::setPaletteStyle,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = !wallpaper,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            SettingsNote("「原色」最接近选的颜色；其他风格按 Material 规则调和出整套配色。")
        }
    }

    if (customizing) {
        CustomColorDialog(
            initial = theme.seedColor,
            style = theme.style,
            onDismiss = { customizing = false },
            onConfirm = { color ->
                customizing = false
                viewModel.setSeedColor(color)
            },
        )
    }
}

internal fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}

internal fun paletteStyleLabel(style: PaletteStyle): String = when (style) {
    PaletteStyle.VIBRANT -> "鲜明"
    PaletteStyle.TONAL_SPOT -> "柔和"
    PaletteStyle.EXPRESSIVE -> "多彩"
    PaletteStyle.FIDELITY -> "原色"
}

/** 预设的主题色：品牌蓝打头，色相大致均匀（加上自选正好两排） */
internal val SEED_PRESETS: List<Int> = listOf(
    ThemeSettings.DEFAULT_SEED_COLOR,
    0xFF00838F.toInt(),
    0xFF2E7D32.toInt(),
    0xFF9E9D24.toInt(),
    0xFFF9A825.toInt(),
    0xFFC62828.toInt(),
    0xFFAD1457.toInt(),
    0xFF6A1B9A.toInt(),
    0xFF4527A0.toInt(),
)

@Composable
private fun TitleRow(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Spacer(Modifier.size(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}

/** 主题色色板：每个色块显示这个颜色生成的主色 / 次要色 / 第三色；最后一个是自选颜色 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeedPalette(theme: ThemeSettings, onSelect: (Int) -> Unit, onCustom: () -> Unit) {
    val custom = theme.seedColor !in SEED_PRESETS
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        SEED_PRESETS.forEach { seed ->
            SeedSwatch(
                seed = seed,
                style = theme.style,
                selected = seed == theme.seedColor,
                label = if (seed == ThemeSettings.DEFAULT_SEED_COLOR) "默认蓝色" else "主题色",
                onClick = { onSelect(seed) },
            )
        }
        if (custom) {
            SeedSwatch(seed = theme.seedColor, style = theme.style, selected = true, label = "自选颜色", onClick = onCustom, editable = true)
        } else {
            CustomSwatch(onClick = onCustom)
        }
    }
}

/**
 * 色块：上半主色，左下次要色，右下第三色（与系统壁纸配色的色块一样取浅色调，深浅色下都看得清）；
 * 选中时外圈描边、中间打勾，并稍微放大
 */
@Composable
private fun SeedSwatch(
    seed: Int,
    style: PaletteStyle,
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    size: Dp = 52.dp,
    editable: Boolean = false,
) {
    val scheme = remember(seed, style) { seedColorScheme(seed, style, dark = true) }
    val scale by animateFloatAsState(if (selected) 1f else 0.88f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "swatchScale")
    val ring = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().scale(scale)) {
            drawArc(scheme.primary, 180f, 180f, useCenter = true)
            drawArc(scheme.secondary, 90f, 90f, useCenter = true)
            drawArc(scheme.tertiary, 0f, 90f, useCenter = true)
        }
        if (selected) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(ring, radius = this.size.minDimension / 2 - 1.5.dp.toPx(), style = Stroke(width = 2.dp.toPx()))
            }
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (editable) Icons.Outlined.Edit else Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 自选颜色的入口：彩虹色块 */
@Composable
private fun CustomSwatch(onClick: () -> Unit, size: Dp = 52.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "自选颜色"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().scale(0.88f)) {
            drawCircle(Brush.sweepGradient(HUE_STOPS))
        }
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
        }
    }
}

private val HUE_STOPS = listOf(
    Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00), Color(0xFF00FFFF),
    Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
)

/** 自选颜色：拖动色相，预览这个颜色生成的配色 */
@SuppressLint("RestrictedApi")
@Composable
private fun CustomColorDialog(initial: Int, style: PaletteStyle, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var hue by remember { mutableFloatStateOf(Hct.fromInt(initial).hue.toFloat()) }
    val seed = remember(hue) { hueSeed(hue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
        title = { Text("自选颜色") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                SeedSwatch(seed = seed, style = style, selected = false, label = "预览", onClick = {}, size = 72.dp)
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp)
                            .height(14.dp),
                    ) {
                        drawRoundRect(Brush.horizontalGradient(HUE_STOPS), cornerRadius = CornerRadius(size.height / 2))
                    }
                    Slider(
                        value = hue,
                        onValueChange = { hue = it },
                        valueRange = 0f..360f,
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color.Transparent,
                            inactiveTrackColor = Color.Transparent,
                            thumbColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(seed) }) { Text("使用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 色相对应的主题色：中等饱和、适中的明度，任何色相都能生成像样的配色 */
@SuppressLint("RestrictedApi")
internal fun hueSeed(hue: Float): Int = Hct.from(hue.toDouble(), 48.0, 50.0).toInt()
