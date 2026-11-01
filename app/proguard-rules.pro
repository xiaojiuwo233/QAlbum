# 保留行号信息以便崩溃定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Kotlin / Coroutines ---
-keepclassmembers class kotlinx.coroutines.internal.MainDispatcherFactory {
    *;
}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler

# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.xjw.qalbum.**$$serializer { *; }
-keepclassmembers class com.xjw.qalbum.** {
    *** Companion;
}
-keepclasseswithmembers class com.xjw.qalbum.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Room ---
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# --- Shizuku ---
-keep class moe.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }
-keep class dev.rikka.shizuku.** { *; }

# --- libsu ---
-keep class com.topjohnwu.superuser.** { *; }

# --- 扫描 AIDL 文件服务 ---
# Root（libsu RootService）/ Shizuku（UserService）的远程进程按类名反射实例化
# 服务类，且 AIDL Stub 的 binder 协议依赖完整类结构，必须整体保留。
-keep class com.xjw.qalbum.fileops.** { *; }

# --- Coil ---
-dontwarn coil3.**

# --- 统计 ---
# 统计事件里的 error 参数是异常类名：自己的异常类保留名字（崩溃报告由 Crashlytics 按映射文件还原，不受影响）
-keepnames class com.xjw.qalbum.** extends java.lang.Throwable

# Compose 不需要任何手写 keep（各库自带 consumer 规则、无反射依赖）；
# 全量 keep 会让 material-icons-extended 的数千个未用图标类无法被 R8 裁剪，dex 膨胀数 MB。
