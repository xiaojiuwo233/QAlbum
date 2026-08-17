package com.xjw.qalbum.service.indexer.transport

import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.xjw.qalbum.access.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.util.concurrent.Executor

/**
 * 流式 shell：执行一条命令，stdout **逐行**发射。
 *
 * 旧实现把完整 stdout 读成一个 String（200w 行 ≈ 150MB 字符串驻留内存），
 * 这里改为边产出边消费，内存占用 O(1)。
 *
 * 失败语义：命令「无法执行 / 进程被杀」时流以 [IOException] 终止，绝不把失败
 * 静默当成空输出 —— 空输出会被扫描层误读为「目录已全部消失」，触发全库标 missing。
 */
fun interface StreamingShell {
    fun stream(cmd: String): Flow<String>
}

/**
 * 退出码判定：`find` 对「路径参数不存在 / 个别项不可读」返回 1 且属常态
 * （temp 等源目录可能天然不存在），已产出的部分是有效结果，不视为失败；
 * 只把「无法执行（126/127）/ 被信号杀死（128+）/ 未执行（-1）」当传输失败抛出。
 * 「目录不可访问导致空输出 + 退出码 1」无法在此区分，由 ScanPipeline 的
 * 枚举空结果校验与消失目录熔断兜底。
 */
private fun checkShellExit(code: Int, cmd: String) {
    if (code == 0 || code == 1) return
    throw IOException("shell 命令异常退出(code=$code): ${cmd.take(160)}")
}

/** Root：libsu 常驻 shell + CallbackList 逐行回调 */
class RootStreamingShell : StreamingShell {

    override fun stream(cmd: String): Flow<String> = callbackFlow {
        val callback = object : CallbackList<String>(Executor { it.run() }) {
            override fun onAddElement(line: String) {
                // 缓冲满则阻塞 gobbler 线程形成背压；collector 取消后发送失败，忽略即可
                trySendBlocking(line)
            }
        }
        // submit 异步执行：生产者协程可立即响应取消（释放 scanMutex，不再阻塞到整条
        // find 跑完）。已提交进常驻 shell 的命令本身无法中途终止，取消后其输出被丢弃。
        Shell.cmd(cmd).to(callback).submit { result ->
            val failure = runCatching { checkShellExit(result.code, cmd) }.exceptionOrNull()
            close(failure)
        }
        awaitClose()
    }.flowOn(Dispatchers.IO)
}

/** Shizuku：newProcess 起 sh，BufferedReader 逐行读 stdout */
class ShizukuStreamingShell : StreamingShell {

    override fun stream(cmd: String): Flow<String> = flow {
        val process = ShizukuShell.newProcess(arrayOf("sh", "-c", cmd))
            ?: throw IOException("Shizuku newProcess 不可用，无法执行 shell 命令")
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) emit(line)
            }
            checkShellExit(process.waitFor(), cmd)
        } finally {
            try {
                process.destroy()
            } catch (_: Throwable) {
            }
        }
    }.flowOn(Dispatchers.IO)
}
