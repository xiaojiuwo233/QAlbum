package com.xjw.qalbum.service.indexer.transport

import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.xjw.qalbum.access.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.Executor

/**
 * 流式 shell：执行一条命令，stdout **逐行**发射。
 *
 * 旧实现把完整 stdout 读成一个 String（200w 行 ≈ 150MB 字符串驻留内存），
 * 这里改为边产出边消费，内存占用 O(1)。
 */
fun interface StreamingShell {
    fun stream(cmd: String): Flow<String>
}

/** Root：libsu 常驻 shell + CallbackList 逐行回调 */
class RootStreamingShell : StreamingShell {

    override fun stream(cmd: String): Flow<String> = channelFlow {
        val callback = object : CallbackList<String>(Executor { it.run() }) {
            override fun onAddElement(line: String) {
                // 缓冲满则阻塞 gobbler 线程形成背压；collector 取消后发送失败，忽略即可
                trySendBlocking(line)
            }
        }
        // exec 阻塞至命令结束且输出收集完毕（运行在 IO dispatcher）
        Shell.cmd(cmd).to(callback).exec()
    }.flowOn(Dispatchers.IO)
}

/** Shizuku：newProcess 起 sh，BufferedReader 逐行读 stdout */
class ShizukuStreamingShell : StreamingShell {

    override fun stream(cmd: String): Flow<String> = flow {
        val process = ShizukuShell.newProcess(arrayOf("sh", "-c", cmd)) ?: return@flow
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) emit(line)
            }
            process.waitFor()
        } finally {
            try {
                process.destroy()
            } catch (_: Throwable) {
            }
        }
    }.flowOn(Dispatchers.IO)
}
