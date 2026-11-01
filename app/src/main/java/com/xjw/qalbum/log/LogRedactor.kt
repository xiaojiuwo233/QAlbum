package com.xjw.qalbum.log

/**
 * 日志脱敏：没开「详细日志」时，写进日志、查看和导出的内容先去掉能认出用户的部分 —— QQ 的 uid、QQ 号与群号
 * （路径里的、`uin=` 这类的）、账号目录名（由 uid 算出）、收到的文件的名字、MD5 这类长哈希和邮箱。
 * 路径的其余部分、大小、耗时和次数照常保留，反馈问题时附上的日志仍能看出哪一步、哪个目录出了问题。
 */
object LogRedactor {

    private val rules = listOf(
        // QQ NT 的 uid：u_ 加 22 位
        Regex("""(?<![A-Za-z0-9])u_[A-Za-z0-9_-]{8,}""") to "u_***",
        // 账号目录：nt_qq_ 加 uid 的 MD5
        Regex("""nt_qq_[0-9A-Fa-f]{8,}""") to "nt_qq_***",
        // QQ 私有目录里的账号文件：files/uid/<QQ 号>###<uid>
        Regex("""\d{5,12}###""") to "***###",
        // 路径里整段是 5～12 位数字的（QQ 号、群号命名的目录）
        Regex("""(?<=/)\d{5,12}(?=/|$|[\s:'"),;])""") to "***",
        // 收到的文件（多是群文件）的名字
        Regex("""(QQfile_recv/)[^\s:'"(),;]+""") to "$1***",
        // QQ 号、uid 写成键值的
        Regex("""(?i)\b(uin|peer_?uid|sender_?uid|self_?uid|group_?code|troop_?uin)(\s*[=:]\s*)[^\s,;)\]}]+""") to "$1$2***",
        // MD5、SHA、安装 ID 这类长哈希
        Regex("""(?<![0-9A-Fa-f])[0-9A-Fa-f]{32,}(?![0-9A-Fa-f])""") to "<hash>",
        Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""") to "<email>",
    )

    fun redact(text: String): String =
        if (text.length < MIN_LENGTH) text else rules.fold(text) { current, (regex, replacement) -> regex.replace(current, replacement) }

    /** 再短的也不会有要去掉的内容 */
    private const val MIN_LENGTH = 7
}
