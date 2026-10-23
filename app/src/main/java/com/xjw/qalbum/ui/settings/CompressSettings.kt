package com.xjw.qalbum.ui.settings

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.data.preferences.SettingsRepository
import com.xjw.qalbum.ui.components.ConnectedChoiceRow
import com.xjw.qalbum.ui.components.SegmentedCard
import com.xjw.qalbum.ui.components.SegmentedGroup
import com.xjw.qalbum.ui.components.SegmentedSwitchItem
import org.koin.androidx.compose.koinViewModel

/** 原图压缩：质量（附示例照片前后对比）、长边、先对比再替换、保留原图 */
@Composable
fun CompressSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
) {
    val quality by viewModel.compressQuality.collectAsState()
    val maxSide by viewModel.compressMaxSide.collectAsState()
    val keepOriginal by viewModel.compressKeepOriginal.collectAsState()
    val compare by viewModel.compressCompare.collectAsState()
    val demo by viewModel.compressDemoResult.collectAsState()

    SettingsPageScaffold(SettingsCategory.COMPRESS, onBack, sharedScope, animatedScope) { notify ->
        SegmentedGroup(header = "质量") {
            item("quality") { position ->
                SegmentedCard(position, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("压缩质量", style = MaterialTheme.typography.bodyLarge)
                    ConnectedChoiceRow(
                        options = SettingsRepository.COMPRESS_QUALITY_OPTIONS,
                        selected = quality.takeIf { it in SettingsRepository.COMPRESS_QUALITY_OPTIONS },
                        label = { "$it%" },
                        onSelect = { value ->
                            viewModel.setCompressQuality(value)
                            notify("压缩质量 $value%")
                        },
                    )
                    CompressQualityDemo(demo?.takeIf { it.quality == quality })
                }
            }
        }
        SettingsNote("越低越省空间，也越模糊。推荐 80%，60% 以下放大能看出色块。")

        SegmentedGroup(header = "方式") {
            item("max-side") { position ->
                ChoiceCard(
                    position = position,
                    title = "压缩后的长边",
                    description = "2560 足够看清手机照片",
                    icon = Icons.Outlined.PhotoSizeSelectLarge,
                    options = MAX_SIDE_OPTIONS,
                    selected = maxSide.takeIf { it in MAX_SIDE_OPTIONS },
                    label = { if (it == 0) "不缩小" else "$it" },
                    onSelect = { side ->
                        viewModel.setCompressMaxSide(side)
                        notify(if (side == 0) "只重新编码，不缩小" else "长边缩到 $side 像素")
                    },
                )
            }
            item("compare") { position ->
                SegmentedSwitchItem(
                    position = position,
                    icon = Icons.AutoMirrored.Outlined.CompareArrows,
                    title = "先对比再替换",
                    subtitle = if (compare) "看过前后对比和省下的空间再替换" else "压缩后直接替换，省不到一成的不换",
                    checked = compare,
                    onCheckedChange = viewModel::setCompressCompare,
                )
            }
            item("keep") { position ->
                SegmentedSwitchItem(
                    position = position,
                    icon = Icons.Outlined.Restore,
                    title = "保留原图",
                    subtitle = if (keepOriginal) "原图暂存回收站，到期前能还原" else "压缩后马上释放空间，不能还原",
                    checked = keepOriginal,
                    onCheckedChange = viewModel::setCompressKeepOriginal,
                )
            }
        }
    }
}

private val MAX_SIDE_OPTIONS = listOf(2560, 3840, 0)
