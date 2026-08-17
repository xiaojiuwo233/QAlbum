package com.xjw.qalbum.data.db

/**
 * FTS4 中文检索的切分工具（纯函数，可单测）。
 *
 * 背景：ocr_fts 使用 FTS4 默认 simple 分词器，所有码点 ≥128 的字符（含全部汉字与全角标点）
 * 都被视为 token 字符——一行无 ASCII 分隔的中文会被索引成单个巨型 token，前缀查询
 * `发票*` 永远命中不了行中位置的「…增值税发票…」。
 *
 * 方案：索引期与查询期对 CJK 做**逐字空格切分**——
 * - 写入 [tokenizeCjkForIndex]：每个汉字独立成 token（「北京欢迎你」→「北 京 欢 迎 你」），
 *   全角标点归一为分隔符；ASCII 部分原样保留（simple 分词器本就按 ASCII 非字母数字切分）。
 * - 查询 [buildFtsMatchQuery]：中文关键词切成同样的逐字序列后用短语引号包裹
 *   （「发票」→ `"发 票"`），短语匹配连续相邻的单字 token，等价于子串命中；
 *   ASCII 词保持 `词*` 前缀匹配。
 *
 * 两端必须使用同一套切分规则，修改任一侧都需要重建 ocr_fts（见 AppDatabase v10 迁移）。
 */
object FtsTokenize {

    /** FTS4 查询语法元字符：引号/前缀星号/括号/列过滤冒号/排除连字符/NEAR 折线，统一归一为空格 */
    private const val FTS_META = "\"'*():^-"

    private val WHITESPACE = Regex("\\s+")

    /** CJK 表意文字（含扩展 A 与兼容区、补充平面扩展）。假名/谚文等保持整词，与索引侧行为一致。 */
    private fun isCjk(cp: Int): Boolean = when (cp) {
        in 0x4E00..0x9FFF,   // CJK 统一表意文字
        in 0x3400..0x4DBF,   // 扩展 A
        in 0xF900..0xFAFF,   // 兼容表意文字
        in 0x20000..0x2EBEF, // 扩展 B-F（补充平面，surrogate pair）
        -> true
        else -> false
    }

    /**
     * 索引期切分：CJK 逐字加空格、非 ASCII 标点（全角逗号等）归一为空格，其余原样。
     * 由 OcrDao 在写 ocr_fts 前调用；v10 迁移重建 FTS 内容时也用同一函数。
     */
    fun tokenizeCjkForIndex(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length * 2)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when {
                isCjk(cp) -> {
                    if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                    sb.appendCodePoint(cp)
                    sb.append(' ')
                }
                // 非 ASCII 且非字母数字（全角标点等）：simple 分词器会把它并进相邻 token，归一为分隔符
                cp >= 0x80 && !Character.isLetterOrDigit(cp) -> {
                    if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                }
                else -> sb.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return sb.toString().trim()
    }

    /**
     * 查询期构造 MATCH 表达式：接受**原始用户输入**，也兼容上游已做过 `词*`/`OR` 拼接的输入
     * （元字符与裸 OR/AND/NOT/NEAR 操作词会被剥掉后按同一规则重建）。
     * 各检索单元以 OR 相连（沿用原查询语义）；输入无有效词时返回空串，调用方需短路不下发 MATCH。
     */
    fun buildFtsMatchQuery(input: String): String {
        val cleaned = buildString(input.length) {
            for (ch in input) append(if (ch in FTS_META) ' ' else ch)
        }
        val units = ArrayList<String>()
        for (term in cleaned.split(WHITESPACE)) {
            if (term.isEmpty()) continue
            if (term == "OR" || term == "AND" || term == "NOT" || term == "NEAR") continue
            appendTermUnits(term, units)
        }
        return units.joinToString(" OR ")
    }

    /** 把一个词按 CJK 段/非 CJK 段拆成检索单元：CJK 段 → 逐字短语，其余段 → 前缀匹配 */
    private fun appendTermUnits(term: String, out: MutableList<String>) {
        val cjkRun = StringBuilder()
        val wordRun = StringBuilder()
        fun flushCjk() {
            if (cjkRun.isEmpty()) return
            out.add("\"$cjkRun\"")
            cjkRun.setLength(0)
        }
        fun flushWord() {
            if (wordRun.isEmpty()) return
            out.add("$wordRun*")
            wordRun.setLength(0)
        }
        var i = 0
        while (i < term.length) {
            val cp = term.codePointAt(i)
            when {
                isCjk(cp) -> {
                    flushWord()
                    if (cjkRun.isNotEmpty()) cjkRun.append(' ')
                    cjkRun.appendCodePoint(cp)
                }
                Character.isLetterOrDigit(cp) -> {
                    flushCjk()
                    wordRun.appendCodePoint(cp)
                }
                else -> {
                    flushCjk()
                    flushWord()
                }
            }
            i += Character.charCount(cp)
        }
        flushCjk()
        flushWord()
    }

    /**
     * snippet 展示还原：去掉索引期逐字切分引入的空格——仅当空格两侧（跳过 `<b>`/`</b>`
     * 等高亮标记）都是 CJK 时移除，ASCII 词间的空格保持不变。
     */
    fun collapseCjkSpacesInSnippet(snippet: String): String {
        if (snippet.isEmpty()) return snippet
        val sb = StringBuilder(snippet.length)
        var i = 0
        while (i < snippet.length) {
            val ch = snippet[i]
            if (ch == ' ') {
                val prev = prevVisibleCp(sb)
                val next = nextVisibleCp(snippet, i + 1)
                if (prev != null && next != null && isCjk(prev) && isCjk(next)) {
                    i++
                    continue
                }
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    /** 向前找最近的可见码点（跳过 `<…>` 标记）；无则 null */
    private fun prevVisibleCp(sb: CharSequence): Int? {
        var i = sb.length - 1
        while (i >= 0) {
            if (sb[i] == '>') {
                val open = sb.lastIndexOf('<', i)
                if (open < 0) return sb[i].code
                i = open - 1
                continue
            }
            val c = sb[i]
            return if (c.isLowSurrogate() && i > 0 && sb[i - 1].isHighSurrogate()) {
                Character.toCodePoint(sb[i - 1], c)
            } else {
                c.code
            }
        }
        return null
    }

    /** 向后找最近的可见码点（跳过 `<…>` 标记）；无则 null */
    private fun nextVisibleCp(s: String, from: Int): Int? {
        var i = from
        while (i < s.length) {
            if (s[i] == '<') {
                val close = s.indexOf('>', i)
                if (close < 0) return s[i].code
                i = close + 1
                continue
            }
            return s.codePointAt(i)
        }
        return null
    }
}
