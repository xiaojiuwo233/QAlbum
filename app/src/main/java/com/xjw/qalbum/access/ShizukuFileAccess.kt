package com.xjw.qalbum.access

import android.content.pm.PackageManager
import com.xjw.qalbum.domain.model.AccessMode
import com.xjw.qalbum.domain.model.FileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Shizuku 模式文件访问实现（Phase 0 骨架版）
 *
 * 真正的命令执行通过 Shizuku.newProcess(...) 派发到 shell 用户（UID 2000）。
 * Phase 1 会接入完整的 `find` / `cat` / `mv` / `rm` 实现；
 * 当前版本仅实现 [isAvailable] 和基础探测，使授权流程可以跑通。
 */
class ShizukuFileAccess : FileAccessProvider {

    override fun getAccessMode(): AccessMode = AccessMode.SHIZUKU

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            // pingBinder = true 表示 Shizuku 服务已经在跑
            // 同时需要 App 已经被授权（PERMISSION_GRANTED）
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            Timber.w(t, "Shizuku isAvailable() failed")
            false
        }
    }

    override suspend fun exists(path: String): Boolean {
        if (!isAvailable()) return false
        return runShell("test -e '${path.escape()}' && echo 1 || echo 0").trim() == "1"
    }

    override fun listFiles(dir: String): Flow<FileInfo> = flow {
        if (!isAvailable()) return@flow
        // 用 find + stat-like 格式输出，便于一行解析
        // Phase 1 中会替换为更可靠的 AIDL 服务，避免 shell 解析的边界问题
        val out = runShell(
            "find '${dir.escape()}' -mindepth 1 -maxdepth 1 -printf '%p\\t%s\\t%T@\\t%y\\n' 2>/dev/null"
        )
        out.lineSequence().forEach { line ->
            val info = parseFindLine(line) ?: return@forEach
            emit(info)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun readFile(path: String): InputStream {
        // 占位：Phase 1 中通过 Shizuku 提供的 ParcelFileDescriptor 流式读取
        if (!isAvailable()) throw AccessException("Shizuku 不可用")
        Timber.w("ShizukuFileAccess.readFile 尚未实现，path=$path")
        return ByteArrayInputStream(ByteArray(0))
    }

    override suspend fun getFileInfo(path: String): FileInfo? {
        if (!isAvailable()) return null
        val out = runShell(
            "find '${path.escape()}' -maxdepth 0 -printf '%p\\t%s\\t%T@\\t%y\\n' 2>/dev/null"
        ).lineSequence().firstOrNull() ?: return null
        return parseFindLine(out)
    }

    override suspend fun deleteFile(path: String): Boolean {
        if (!isAvailable()) return false
        return runShellExit("rm -f '${path.escape()}'") == 0
    }

    override suspend fun moveFile(src: String, dst: String): Boolean {
        if (!isAvailable()) return false
        return runShellExit("mv '${src.escape()}' '${dst.escape()}'") == 0
    }

    // ---- 内部实现 ----

    /**
     * 在 Shizuku 派发的 shell 进程中执行命令，返回 stdout 字符串
     *
     * 注意：[Shizuku.newProcess] 在 13.x 之后被标记为 hidden API（@RestrictTo），
     * 长期来看应该改用自定义 AIDL UserService（[Shizuku.bindUserService]）。
     * Phase 1 会切换到 UserService；当前先用反射临时调用，保证 Phase 0 可跑通。
     */
    private suspend fun runShell(cmd: String): String = withContext(Dispatchers.IO) {
        try {
            val process = invokeNewProcess(arrayOf("sh", "-c", cmd)) ?: return@withContext ""
            val out = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            out
        } catch (t: Throwable) {
            Timber.w(t, "Shizuku shell exec failed: $cmd")
            ""
        }
    }

    private suspend fun runShellExit(cmd: String): Int = withContext(Dispatchers.IO) {
        try {
            val process = invokeNewProcess(arrayOf("sh", "-c", cmd)) ?: return@withContext -1
            process.waitFor()
        } catch (t: Throwable) {
            Timber.w(t, "Shizuku shell exec failed: $cmd")
            -1
        }
    }

    /** 反射调用 Shizuku.newProcess(String[], String[], String) */
    private fun invokeNewProcess(cmd: Array<String>): Process? = try {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        method.invoke(null, cmd, null, null) as? Process
    } catch (t: Throwable) {
        Timber.w(t, "Shizuku newProcess reflection failed")
        null
    }

    private fun parseFindLine(line: String): FileInfo? {
        if (line.isBlank()) return null
        val parts = line.split('\t')
        if (parts.size < 4) return null
        val path = parts[0]
        val size = parts[1].toLongOrNull() ?: 0L
        // %T@ 是带小数的秒数时间戳
        val mtime = (parts[2].toDoubleOrNull() ?: 0.0).times(1000).toLong()
        val type = parts[3]
        return FileInfo(
            path = path,
            name = path.substringAfterLast('/'),
            size = size,
            lastModified = mtime,
            isDirectory = type == "d",
        )
    }

    /** 对 shell 字符串中的特殊字符做简单转义。后续会切换到 ParcelFileDescriptor API */
    private fun String.escape(): String = replace("'", "'\\''")
}
