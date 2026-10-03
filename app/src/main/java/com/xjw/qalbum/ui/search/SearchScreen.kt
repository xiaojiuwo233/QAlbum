package com.xjw.qalbum.ui.search

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.xjw.qalbum.data.db.dao.OcrDao
import com.xjw.qalbum.data.db.entity.ImageEntity
import com.xjw.qalbum.service.ai.SemanticHit
import com.xjw.qalbum.ui.anim.sharedContainer
import com.xjw.qalbum.ui.imageloading.FileAccessPath
import com.xjw.qalbum.ui.imageloading.ImageEntityModel
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 搜索页（Phase 3）
 *
 * - 顶部固定搜索框（OutlinedTextField）
 * - FTS5/FTS4 全文搜索，命中后展示缩略图 + snippet 高亮
 * - 点击命中跳到图片详情
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
    val focusManager = LocalFocusManager.current

    Scaffold(
        modifier = Modifier.sharedContainer("search-bar", sharedScope, animatedScope),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    onBack?.let {
                        IconButton(onClick = it) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::onQueryChange,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.submitSearch(); focusManager.clearFocus() }),
                        placeholder = {
                            Text(
                                if (mode == SearchMode.OCR) "搜索文字"
                                else "搜索图片内容"
                            )
                        },
                        leadingIcon = {
                            IconButton(onClick = { viewModel.submitSearch(); focusManager.clearFocus() }) {
                                Icon(Icons.Outlined.Search, contentDescription = "搜索")
                            }
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onQueryChange("") }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "清空")
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                        SegmentedButton(
                            selected = mode == SearchMode.OCR,
                            onClick = { viewModel.setMode(SearchMode.OCR) },
                            label = { Text("文字") },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            icon = {
                                Icon(Icons.Outlined.TextFields, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                        )
                        SegmentedButton(
                            selected = mode == SearchMode.Semantic,
                            onClick = { viewModel.setMode(SearchMode.Semantic) },
                            enabled = aiEnabled,
                            label = { Text("语义") },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            icon = {
                                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                        )
                    }
                    if (!aiEnabled) {
                        IconButton(onClick = onOpenAiSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = "AI 设置")
                        }
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { topBarPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topBarPadding.calculateTopPadding())
                .padding(contentPadding)
                .imePadding(),
        ) {
            when (val s = state) {
                SearchUiState.Idle -> IdleHint()
                SearchUiState.Loading -> Centered { CircularProgressIndicator() }
                SearchUiState.Empty -> Centered {
                    Text("没有匹配结果", style = MaterialTheme.typography.bodyMedium)
                }
                is SearchUiState.Failed -> Centered {
                    Text("查询失败：${s.reason}", color = MaterialTheme.colorScheme.error)
                }
                is SearchUiState.OcrResult -> OcrResultList(s.hits, onResultClick)
                is SearchUiState.SemanticResult -> SemanticResultList(s.hits, onResultClick)
            }
        }
    }
}

@Composable
private fun OcrResultList(hits: List<OcrDao.SearchHit>, onClick: (Long) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(count = hits.size, key = { hits[it].image_id }) { idx ->
            val hit = hits[idx]
            OcrHitRow(hit, onClick)
        }
    }
}

@Composable
private fun SemanticResultList(hits: List<SemanticHit>, onClick: (Long) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(count = hits.size, key = { hits[it].image.id }) { idx ->
            val hit = hits[idx]
            SemanticHitRow(hit, onClick)
        }
    }
}

@Composable
private fun SemanticHitRow(hit: SemanticHit, onClick: (Long) -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick(hit.image.id) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(ImageEntityModel(hit.image)).crossfade(true).build(),
                contentDescription = hit.image.filename,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                hit.image.filename,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${hit.image.sourceType} · ${formatTime(hit.image.mtime)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "相似度 ${(hit.similarity * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun OcrHitRow(hit: OcrDao.SearchHit, onClick: (Long) -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick(hit.image_id) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(FileAccessPath(hit.path)).crossfade(true).build(),
                contentDescription = hit.filename,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                hit.filename,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                parseSnippet(hit.snippet),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                formatTime(hit.mtime),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun IdleHint() {
    Centered {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "暂无搜索结果",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

// ---- 工具 ----

/** 把 SQLite snippet() 返回的 "<b>关键字</b>" 转成 AnnotatedString 高亮 */
private fun parseSnippet(text: String): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("<b>(.*?)</b>")
    var lastEnd = 0
    for (match in pattern.findAll(text)) {
        append(text.substring(lastEnd, match.range.first))
        withStyle(
            SpanStyle(
                color = Color(0xFFE65100),
                fontWeight = FontWeight.SemiBold,
                background = Color(0x33E65100),
            )
        ) {
            append(match.groupValues[1])
        }
        lastEnd = match.range.last + 1
    }
    if (lastEnd < text.length) append(text.substring(lastEnd))
}

private val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
private fun formatTime(ts: Long): String = timeFormatter.format(Date(ts))
