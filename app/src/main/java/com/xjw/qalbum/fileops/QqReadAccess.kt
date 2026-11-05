package com.xjw.qalbum.fileops

import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Root 侧只有目录枚举、lstat、O_RDONLY 和共享锁（外加用户确认后强制停止 QQ）；SQLCipher 永远在普通应用进程读取私有副本。
 */
internal object QqReadAccess {
    private val accountPattern = Regex("[0-9a-f]{32}")
    private val uidPattern = Regex("[0-9]+###(u_[A-Za-z0-9_-]{8,64})")
    private val databases = listOf("nt_msg.db", "group_info.db", "profile_info.db")
    private val files = databases.flatMap { listOf(it, "$it-wal", "$it-shm") }

    private fun root(userId: Int): File {
        check(Process.myUid() == 0) { "图片来源需要 Root 权限" }
        require(userId in 0..999)
        return File("/data/user/$userId/com.tencent.mobileqq")
    }

    fun catalog(userId: Int): String {
        val base = root(userId)
        val directory = File(base, "files/uid")
        val entries = directory.listFiles() ?: error("QQ 账号目录不可读")
        val accounts = JSONArray()
        entries.sortedBy { it.name }.forEach { file ->
            val uid = uidPattern.matchEntire(file.name)?.groupValues?.get(1) ?: return@forEach
            fun md5(value: String) = MessageDigest.getInstance("MD5").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
            val account = md5(md5(uid) + "nt_kernel")
            if (File(base, "databases/nt_db/nt_qq_$account/nt_msg.db").isFile) {
                accounts.put(JSONObject().put("account", account).put("uid", uid))
            }
        }
        return accounts.toString()
    }

    private fun source(userId: Int, account: String, name: String): File {
        val base = root(userId)
        require(accountPattern.matches(account) && name in files)
        val expected = File(base.canonicalFile, "databases/nt_db/nt_qq_$account/$name")
        require(expected.canonicalPath == expected.absolutePath) { "QQ 数据库路径包含符号链接" }
        return expected
    }

    fun state(userId: Int, account: String): String {
        val result = JSONObject()
        files.forEach { name ->
            val file = source(userId, account, name)
            val stat = try {
                Os.lstat(file.path)
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.ENOENT) throw e
                null
            }
            if (stat == null) result.put(name, JSONObject.NULL) else {
                require(OsConstants.S_ISREG(stat.st_mode)) { "QQ 数据库不是普通文件" }
                val modified = if (Build.VERSION.SDK_INT >= 27) "${stat.st_mtim.tv_sec}:${stat.st_mtim.tv_nsec}" else stat.st_mtime.toString()
                val changed = if (Build.VERSION.SDK_INT >= 27) "${stat.st_ctim.tv_sec}:${stat.st_ctim.tv_nsec}" else stat.st_ctime.toString()
                val state = JSONObject().put("size", stat.st_size).put("inode", stat.st_ino)
                    .put("device", stat.st_dev).put("mtime", modified).put("ctime", changed)
                if (Build.VERSION.SDK_INT < 27) {
                    // Android 8.0 的 stat 只有秒精度，需要内容摘要防止同秒写入造成混合副本。
                    val digest = MessageDigest.getInstance("SHA-256")
                    val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
                    FileInputStream(fd).use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                        }
                    }
                    state.put("fingerprint", digest.digest().joinToString("") { "%02x".format(it) })
                }
                result.put(name, state)
            }
        }
        return result.toString()
    }

    // ---- 读取期间暂停 QQ 的 checkpoint（见 WalCheckpointPin） ----

    private val pins = ConcurrentHashMap<Int, Pair<String, WalCheckpointPin>>()
    private val nextPin = AtomicInteger(1)
    private val expiry by lazy { Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "qq-pin-expiry").apply { isDaemon = true } } }

    /**
     * QQ 在运行且 [name] 有 `-shm` 时，对它加共享锁暂停 checkpoint，返回句柄（> 0）；QQ 没在运行、没有 `-shm`
     * 时返回 [PIN_NOT_NEEDED]；QQ 一直占着锁（正在 checkpoint）返回 [PIN_BUSY]；出错返回 [PIN_FAILED]。
     * 句柄 10 分钟后自动释放，防止客户端没来得及释放。
     */
    fun pin(userId: Int, account: String, name: String): Int = try {
        pinChecked(userId, account, name)
    } catch (e: Exception) {
        // 加不上锁照样能读（客户端按状态核对），只是 QQ 写得勤时容易对不上
        PIN_FAILED
    }

    private fun pinChecked(userId: Int, account: String, name: String): Int {
        require(name in databases)
        val shm = source(userId, account, "$name-shm")
        if (!shm.isFile || !qqRunning()) return PIN_NOT_NEEDED
        // 同一个文件上次没释放的先放掉（同一进程内 fcntl 锁不能重叠）
        pins.entries.filter { it.value.first == shm.path }.forEach { unpin(it.key) }
        val pin = WalCheckpointPin.acquire(shm, PIN_TIMEOUT_MS) ?: return PIN_BUSY
        val handle = nextPin.getAndIncrement()
        pins[handle] = shm.path to pin
        expiry.schedule({ unpin(handle) }, PIN_LIFETIME_MINUTES, TimeUnit.MINUTES)
        return handle
    }

    fun unpin(handle: Int) {
        pins.remove(handle)?.second?.close()
    }

    /** 有 QQ 的进程（主进程或 :MSF 等子进程）在运行 */
    private fun qqRunning(): Boolean = File("/proc").listFiles()?.any { dir ->
        if (!dir.name.all(Char::isDigit)) return@any false
        val name = runCatching { File(dir, "cmdline").readBytes() }.getOrNull()
            ?.let { String(it).substringBefore('\u0000') } ?: return@any false
        name == QQ_PACKAGE || name.startsWith("$QQ_PACKAGE:")
    } == true

    /**
     * 强制停止 QQ（用户在来源页确认后：QQ 一直在写数据库，读不到一致的副本）。返回 1 停下了、0 本来就没在运行、
     * -1 停不下来（命令失败或进程还在）。
     */
    fun forceStop(userId: Int): Int {
        root(userId)
        if (!qqRunning()) return 0
        val process = ProcessBuilder("/system/bin/cmd", "activity", "force-stop", "--user", userId.toString(), QQ_PACKAGE)
            .redirectErrorStream(true)
            .start()
        try {
            // 正常没有输出（出错也只有一行），不会写满管道
            if (!process.waitFor(FORCE_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroy()
        } finally {
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
        }
        // 进程收到 SIGKILL 后很快退出，稍等一下再看
        repeat(20) {
            if (!qqRunning()) return 1
            Thread.sleep(100)
        }
        return -1
    }

    /** [pin] 的结果：QQ 没在运行或没有 `-shm`，不需要 */
    const val PIN_NOT_NEEDED = 0

    /** [pin] 的结果：QQ 一直占着锁（正在 checkpoint） */
    const val PIN_BUSY = -1

    /** [pin] 的结果：出错没加上 */
    const val PIN_FAILED = -2

    private const val QQ_PACKAGE = "com.tencent.mobileqq"
    private const val PIN_TIMEOUT_MS = 10_000L
    private const val PIN_LIFETIME_MINUTES = 10L
    private const val FORCE_STOP_TIMEOUT_SECONDS = 10L

    fun openRead(userId: Int, account: String, name: String): ParcelFileDescriptor {
        require(!name.endsWith("-shm"))
        val file = source(userId, account, name)
        // ErrnoException 是受检异常，穿过 binder 会让服务进程崩溃；换成能回传给客户端的异常（如 WAL 刚被删掉）
        val fd = try {
            Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        } catch (e: ErrnoException) {
            throw IllegalStateException("QQ 数据库文件无法打开：errno=${e.errno}")
        }
        return try {
            require(OsConstants.S_ISREG(Os.fstat(fd).st_mode))
            ParcelFileDescriptor.dup(fd)
        } finally {
            Os.close(fd)
        }
    }
}
