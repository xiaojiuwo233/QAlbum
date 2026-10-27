package com.xjw.qalbum.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * 简单的 Markdown（更新说明用）：标题、段落（换行照原样）、无序 / 有序列表（可缩进）、引用、代码块、分隔线，
 * 行内粗体、斜体、删除线、行内代码、链接与网址；图片显示成链接。点链接调 [onOpenUrl]。
 */
@Composable
fun MarkdownText(markdown: String, onOpenUrl: (String) -> Unit, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { Markdown.parse(markdown) }
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    fun annotated(text: String): AnnotatedString = Markdown.inline(text).toAnnotated(colors.primary, colors.surfaceContainerHighest, onOpenUrl)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Markdown.Block.Heading -> Text(
                    annotated(block.text),
                    style = when (block.level) {
                        1 -> typography.titleLarge
                        2 -> typography.titleMedium
                        else -> typography.titleSmall
                    },
                    modifier = Modifier.padding(top = if (block.level <= 2) 4.dp else 0.dp),
                )
                is Markdown.Block.Paragraph -> Text(annotated(block.text), style = typography.bodyMedium)
                is Markdown.Block.Item -> Row {
                    Spacer(Modifier.width((block.depth * 16).dp))
                    Text(block.marker, style = typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.widthIn(min = 20.dp))
                    Text(annotated(block.text), style = typography.bodyMedium)
                }
                is Markdown.Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(colors.outlineVariant, MaterialTheme.shapes.extraSmall))
                    Spacer(Modifier.width(10.dp))
                    Text(annotated(block.text), style = typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                is Markdown.Block.Code -> Surface(color = colors.surfaceContainerHighest, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        block.text,
                        style = typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
                    )
                }
                Markdown.Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

private fun List<Markdown.Span>.toAnnotated(linkColor: Color, codeBackground: Color, onOpenUrl: (String) -> Unit): AnnotatedString =
    buildAnnotatedString {
        forEach { span ->
            when (span) {
                is Markdown.Span.Text -> withStyle(
                    SpanStyle(
                        fontWeight = if (span.bold) FontWeight.SemiBold else null,
                        fontStyle = if (span.italic) FontStyle.Italic else null,
                        textDecoration = if (span.strike) TextDecoration.LineThrough else null,
                        fontFamily = if (span.code) FontFamily.Monospace else null,
                        background = if (span.code) codeBackground else Color.Unspecified,
                    ),
                ) { append(span.text) }
                is Markdown.Span.Link -> withLink(
                    LinkAnnotation.Url(
                        span.url,
                        TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    ) { onOpenUrl(span.url) },
                ) { append(span.text) }
            }
        }
    }

/** Markdown 的解析（纯计算，见 [MarkdownText]） */
internal object Markdown {
    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Paragraph(val text: String) : Block
        /** 列表项：[marker] 是「•」或「1.」，[depth] 缩进层级 */
        data class Item(val depth: Int, val marker: String, val text: String) : Block
        data class Quote(val text: String) : Block
        data class Code(val text: String) : Block
        data object Rule : Block
    }

    sealed interface Span {
        data class Text(val text: String, val bold: Boolean = false, val italic: Boolean = false, val strike: Boolean = false, val code: Boolean = false) : Span
        data class Link(val text: String, val url: String) : Span
    }

    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val RULE = Regex("^([-*_])(\\s*\\1){2,}\\s*$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val ORDERED = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val QUOTE = Regex("^\\s*>\\s?(.*)$")

    fun parse(markdown: String): List<Block> {
        val blocks = ArrayList<Block>()
        val paragraph = ArrayList<String>()
        val quote = ArrayList<String>()
        var code: StringBuilder? = null
        fun flush() {
            if (paragraph.isNotEmpty()) blocks += Block.Paragraph(paragraph.joinToString("\n"))
            paragraph.clear()
            if (quote.isNotEmpty()) blocks += Block.Quote(quote.joinToString("\n"))
            quote.clear()
        }
        for (raw in markdown.replace("\r\n", "\n").split('\n')) {
            val line = raw.trimEnd()
            val fenced = line.trimStart().startsWith("```")
            val block = code
            if (block != null) {
                if (fenced) {
                    blocks += Block.Code(block.toString().trimEnd('\n'))
                    code = null
                } else {
                    block.append(raw).append('\n')
                }
                continue
            }
            if (fenced) {
                flush()
                code = StringBuilder()
                continue
            }
            if (line.isBlank()) {
                flush()
                continue
            }
            val quoted = QUOTE.matchEntire(line)
            if (quoted != null) {
                if (paragraph.isNotEmpty()) flush()
                quote += quoted.groupValues[1]
                continue
            }
            if (quote.isNotEmpty()) flush()
            val heading = HEADING.matchEntire(line)
            val bullet = BULLET.matchEntire(line)
            val ordered = ORDERED.matchEntire(line)
            when {
                heading != null -> {
                    flush()
                    blocks += Block.Heading(heading.groupValues[1].length, heading.groupValues[2])
                }
                RULE.matches(line) -> {
                    flush()
                    blocks += Block.Rule
                }
                bullet != null -> {
                    flush()
                    blocks += Block.Item(depthOf(bullet.groupValues[1]), "•", bullet.groupValues[2])
                }
                ordered != null -> {
                    flush()
                    blocks += Block.Item(depthOf(ordered.groupValues[1]), "${ordered.groupValues[2]}.", ordered.groupValues[3])
                }
                else -> paragraph += line.trimStart()
            }
        }
        code?.let { blocks += Block.Code(it.toString().trimEnd('\n')) }
        flush()
        return blocks
    }

    /** 每两个空格（或一个制表符）缩进一层，最多三层 */
    private fun depthOf(indent: String): Int = (indent.replace("\t", "  ").length / 2).coerceAtMost(3)

    private val INLINE = Regex(
        "`([^`]+)`" +
            "|!\\[([^\\]]*)]\\(([^)\\s]+)\\)" +
            "|\\[([^\\]]+)]\\(([^)\\s]+)\\)" +
            "|\\*\\*(.+?)\\*\\*|__(.+?)__" +
            "|~~(.+?)~~" +
            "|\\*([^*\\s](?:[^*]*?[^*\\s])?)\\*" +
            "|(?<![\\w])_([^_\\s](?:[^_]*?[^_\\s])?)_(?![\\w])" +
            "|(https?://[^\\s<>()（）]+)",
    )

    /** 转义（\* 等）先换成私用区字符，解析完再换回来 */
    private const val ESCAPABLE = "\\`*_{}[]()#+-.!~>"

    fun inline(text: String): List<Span> {
        val escaped = buildString {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE) {
                    append((0xE000 + ESCAPABLE.indexOf(text[i + 1])).toChar())
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }
        return spans(escaped, Span.Text("")).map { span ->
            when (span) {
                is Span.Text -> span.copy(text = unescape(span.text))
                is Span.Link -> span.copy(text = unescape(span.text), url = unescape(span.url))
            }
        }.filterNot { it is Span.Text && it.text.isEmpty() }
    }

    private fun unescape(text: String): String = buildString {
        text.forEach { c -> append(if (c.code in 0xE000 until 0xE000 + ESCAPABLE.length) ESCAPABLE[c.code - 0xE000] else c) }
    }

    private fun spans(text: String, style: Span.Text): List<Span> {
        val out = ArrayList<Span>()
        var position = 0
        for (match in INLINE.findAll(text)) {
            if (match.range.first > position) out += style.copy(text = text.substring(position, match.range.first))
            val g = match.groups
            when {
                g[1] != null -> out += style.copy(text = g[1]!!.value, code = true)
                g[3] != null -> out += Span.Link(g[2]?.value?.ifBlank { null } ?: "图片", g[3]!!.value)
                g[5] != null -> out += Span.Link(g[4]!!.value, g[5]!!.value)
                g[6] != null || g[7] != null -> out += spans((g[6] ?: g[7])!!.value, style.copy(bold = true))
                g[8] != null -> out += spans(g[8]!!.value, style.copy(strike = true))
                g[9] != null || g[10] != null -> out += spans((g[9] ?: g[10])!!.value, style.copy(italic = true))
                g[11] != null -> out += Span.Link(g[11]!!.value, g[11]!!.value)
            }
            position = match.range.last + 1
        }
        if (position < text.length) out += style.copy(text = text.substring(position))
        return out
    }
}
