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

# --- PaddleOCR / ONNX Runtime ---
-keep class com.paddle.ocr.** { *; }
-keep class ai.onnxruntime.** { *; }

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# --- Coil ---
-dontwarn coil3.**

# --- Compose ---
-keep class androidx.compose.** { *; }
