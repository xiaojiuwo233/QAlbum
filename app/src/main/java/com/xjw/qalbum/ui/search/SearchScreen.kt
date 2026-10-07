package com.xjw.qalbum.ui.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.xjw.qalbum.data.db.dao.OcrDao
import com.xjw.qalbum.service.ai.SemanticHit
import com.xjw.qalbum.ui.anim.sharedContainer
import com.xjw.qalbum.ui.common.formatDateTime
import com.xjw.qalbum.ui.common.sourceLabel
import com.xjw.qalbum.ui.components.BackButton
import com.xjw.qalbum.ui.components.ConnectedChoiceRow
import com.xjw.qalbum.ui.components.EmptyState
import com.xjw.qalbum.ui.components.LoadingState
import com.xjw.qalbum.ui.components.SearchField
import com.xjw.qalbum.ui.components.SegmentPosition
import com.xjw.qalbum.ui.components.SegmentedDefaults
import com.xjw.qalbum.ui.components.SegmentedListItem
import com.xjw.qalbum.ui.imageloading.FileAccessPath
import com.xjw.qalbum.ui.imageloading.ImageEntityModel
import org.koin.androidx.compose.koinViewModel

/**
 * 搜索页：顶部胶囊搜索框 + 「文字 / 语义」连接式切换。
 *
 * - 文字：在 OCR 识别结果中全文搜索（FTS4），命中片段高亮；
 * - 语义：把查询转成向量，与图片描述、标签、OCR 文本的向量比对（需启用 AI 服务）。
 */
@Composable
fun SearchScreen(
    contentPadding: PaddingValues,
    onResultClick: (Long) -> Unit = {},
    onBack: (() -> Unit)? = null,
    onOpenAiSettings: () -> Unit = {},
    sharedScope: SharedTransitionScope? = null,
    animatedScope: AnimatedVisibilityScope? = null,
    viewModel: SearchViewModel = koinViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val aiEnabled by viewModel.aiEnabled.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val enterSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val exitSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    // 重新搜索时保留上一批结果并在顶部显示进度，而不是整页切成加载态
    val lastResults = remember { ResultHolder() }
    when (state) {
        is SearchUiState.OcrResult, is SearchUiState.SemanticResult -> lastResults.value = state
        SearchUiState.Loading -> Unit
        else -> lastResults.value = null
    }
    val display = if (state == SearchUiState.Loading) lastResults.value ?: state else state
    val refreshing = state == SearchUiState.Loading && display != state

    Scaffold(
        modifier = Modifier.sharedContainer("search-bar", sharedScope, animatedScope),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    onBack?.let { BackButton(it) }
                    SearchField(
                        value = query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = if (mode == SearchMode.OCR) "搜索图片里的文字" else "描述你想找的图片",
                        onSearch = viewModel::submitSearch,
                        modifier = Modifier.weight(1f),
                    )
                }
                ConnectedChoiceRow(
                    options = SearchMode.entries.toList(),
                    selected = mode,
                    label = { if (it == SearchMode.OCR) "文字" else "语义" },
                    onSelect = viewModel::setMode,
                    enabled = { it == SearchMode.OCR || aiEnabled },
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topBarPadding ->
        AnimatedVisibility(
            visible = refreshing,
            enter = fadeIn(enterSpec),
            exit = fadeOut(exitSpec),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topBarPadding.calculateTopPadding())
                .zIndex(1f),
        ) {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        AnimatedContent(
            targetState = display,
            contentKey = { it::class },
            transitionSpec = { fadeIn(enterSpec) togetherWith fadeOut(exitSpec) },
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topBarPadding.calculateTopPadding())
                .imePadding(),
            label = "searchState",
        ) { s ->
            when (s) {
                SearchUiState.Idle -> EmptyState(
                    icon = if (mode == SearchMode.OCR) Icons.Outlined.ImageSearch else Icons.Outlined.AutoAwesome,
                    title = if (mode == SearchMode.OCR) "搜索图片里的文字" else "按意思搜索图片",
                    message = when {
                        mode == SearchMode.Semantic -> "输入描述后点键盘上的「搜索」，会在图片描述、标签和文字中按意思查找。"
                        aiEnabled -> "输入至少两个字，在已识别的文字中查找；切换到「语义」可以按意思搜索。"
                        else -> "输入至少两个字，在已识别的文字中查找。启用 AI 服务后还可以按意思搜索。"
                    },
                    action = if (!aiEnabled) {
                        {
                            FilledTonalButton(onClick = onOpenAiSettings, shapes = ButtonDefaults.shapes()) {
                                Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text("设置 AI 服务")
                            }
                        }
                    } else {
                        null
                    },
                )
                SearchUiState.Loading -> LoadingState(label = "正在搜索…")
                SearchUiState.Empty -> EmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = "没有匹配的图片",
                    message = if (mode == SearchMode.OCR) "换个关键词试试，或先在「清理 → 分析」中完成文字识别。" else "换一种描述试试。",
                )
                is SearchUiState.Failed -> EmptyState(
                    icon = Icons.Outlined.ErrorOutline,
                    title = "搜索失败",
                    message = s.reason,
                )
                is SearchUiState.OcrResult -> ResultList(contentPadding) {
                    itemsIndexed(s.hits, key = { _, hit -> hit.image_id }) { index, hit ->
                        OcrHitRow(hit, SegmentPosition(index, s.hits.size), onResultClick, Modifier.animateItem())
                    }
                }
                is SearchUiState.SemanticResult -> ResultList(contentPadding) {
                    itemsIndexed(s.hits, key = { _, hit -> hit.image.id }) { index, hit ->
                        SemanticHitRow(hit, SegmentPosition(index, s.hits.size), onResultClick, Modifier.animateItem())
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultList(
    contentPadding: PaddingValues,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(SegmentedDefaults.Gap),
        content = content,
    )
}

@Composable
private fun ResultThumbnail(model: Any, description: String) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(model).crossfade(true).build(),
            contentDescription = description,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun OcrHitRow(hit: OcrDao.SearchHit, position: SegmentPosition, onClick: (Long) -> Unit, modifier: Modifier) {
    SegmentedListItem(
        position = position,
        modifier = modifier,
        onClick = { onClick(hit.image_id) },
        leading = { ResultThumbnail(FileAccessPath(hit.path), hit.filename) },
        headline = { Text(parseSnippet(hit.snippet), maxLines = 3, overflow = TextOverflow.Ellipsis) },
        supporting = { Text(formatDateTime(hit.mtime), maxLines = 1) },
    )
}

@Composable
private fun SemanticHitRow(hit: SemanticHit, position: SegmentPosition, onClick: (Long) -> Unit, modifier: Modifier) {
    SegmentedListItem(
        position = position,
        modifier = modifier,
        onClick = { onClick(hit.image.id) },
        leading = { ResultThumbnail(ImageEntityModel(hit.image), hit.image.filename) },
        headline = { Text(formatDateTime(hit.image.mtime), maxLines = 1) },
        supporting = { Text("${sourceLabel(hit.image.sourceType)} · ${hit.image.filename}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailing = {
            Text(
                "${(hit.similarity * 100).toInt()}%",
                style = MaterialTheme.typography.labelLargeEmphasized,
                color = MaterialTheme.colorScheme.primary,
            )
        },
    )
}

// ---- 工具 ----

/** 上一批搜索结果（非快照状态：只在同一次组合中读写） */
private class ResultHolder {
    var value: SearchUiState? = null
}

/** 把 SQLite snippet() 返回的 "<b>关键字</b>" 转成高亮（主色加粗 + 色调底） */
@Composable
private fun parseSnippet(text: String): AnnotatedString {
    val highlight = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.primaryContainer
    return buildAnnotatedString {
        val pattern = Regex("<b>(.*?)</b>")
        var lastEnd = 0
        for (match in pattern.findAll(text)) {
            append(text.substring(lastEnd, match.range.first))
            withStyle(SpanStyle(color = highlight, fontWeight = FontWeight.SemiBold, background = background)) {
                append(match.groupValues[1])
            }
            lastEnd = match.range.last + 1
        }
        if (lastEnd < text.length) append(text.substring(lastEnd))
    }
}
