package com.xjw.qalbum.access

import rikka.shizuku.Shizuku
import timber.log.Timber
import java.lang.reflect.Method

/**
 * `Shizuku.newProcess` 的反射封装（13.x 之后是 hidden API，@RestrictTo）。
 * 供 [ShizukuFileAccess]（cat / head 等单文件操作）与扫描兜底的
 * ShizukuStreamingShell 共用。扫描主路径已改走 UserService binder，不依赖它。
 */
internal object ShizukuShell {

    /** 反射 Method 只解析一次后缓存：原先每次调用都 getDeclaredMethod，逐文件操作时纯属浪费。 */
    private val newProcessMethod: Method? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
        } catch (t: Throwable) {
            Timber.w(t, "Shizuku newProcess reflection resolve failed")
            null
        }
    }

    fun newProcess(cmd: Array<String>): Process? = try {
        newProcessMethod?.invoke(null, cmd, null, null) as? Process
    } catch (t: Throwable) {
        Timber.w(t, "Shizuku newProcess invoke failed")
        null
    }
}
