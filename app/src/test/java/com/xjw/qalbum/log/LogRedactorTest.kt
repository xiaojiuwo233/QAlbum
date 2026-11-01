package com.xjw.qalbum.log

import org.junit.Assert.assertEquals
import org.junit.Test

/** 没开详细日志时：uid、QQ 号、账号目录、收到的文件名、长哈希去掉，其余照常 */
class LogRedactorTest {

    @Test
    fun `uid 与账号目录、QQ 号去掉`() {
        assertEquals(
            "open failed: /data/user/0/com.tencent.mobileqq/files/uid/***###u_***: ENOENT",
            LogRedactor.redact("open failed: /data/user/0/com.tencent.mobileqq/files/uid/123456789###u_AbCdEfGhIjKlMnOpQrStUv: ENOENT"),
        )
        assertEquals(
            "/data/user/0/com.tencent.mobileqq/databases/nt_db/nt_qq_***/nt_msg.db",
            LogRedactor.redact("/data/user/0/com.tencent.mobileqq/databases/nt_db/nt_qq_0123456789abcdef0123456789abcdef/nt_msg.db"),
        )
        assertEquals("QQ accounts: nt_qq_*** uid=u_***", LogRedactor.redact("QQ accounts: nt_qq_0123456789abcdef uid=u_AbCdEfGhIjKl"))
    }

    @Test
    fun `路径里的 QQ 号目录、键值和收到的文件名去掉`() {
        assertEquals("/Tencent/MobileQQ/***/photo", LogRedactor.redact("/Tencent/MobileQQ/1234567890/photo"))
        assertEquals("peer uin=*** group_code: ***", LogRedactor.redact("peer uin=1234567890 group_code: 987654321"))
        assertEquals(
            "cannot read /storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/***",
            LogRedactor.redact("cannot read /storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/张三的简历.mp4"),
        )
    }

    @Test
    fun `长哈希与邮箱去掉`() {
        assertEquals("session <hash> mail <email>", LogRedactor.redact("session 6AE7A7B80221000123A78DE7F7E8C13E mail someone@example.com"))
    }

    @Test
    fun `排查用的内容不动`() {
        val lines = listOf(
            "Pictures trashed: 200 selected, 800 files in 1532 ms (move=12 copy=0)",
            "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic/chatimg/ed0/Cache_62cc7a0570179ed0",
            "QQ copied message scan: table=group_msg_table from=12345678 inspected=2230000 skipped=6 ms=74000",
            "Stats refreshed in 340 ms: rule:old:1700000000000",
            "at com.xjw.qalbum.qq.QqMessageReader.read(QqMessageReader.kt:171)",
            "android uid=10235 pid=9603",
        )
        lines.forEach { assertEquals(it, LogRedactor.redact(it)) }
    }
}
