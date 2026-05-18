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

# --- ML Kit ---
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# --- Coil ---
-dontwarn coil3.**

# --- Compose ---
-keep class androidx.compose.** { *; }
