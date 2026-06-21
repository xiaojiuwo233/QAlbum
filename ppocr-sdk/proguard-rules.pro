-keep class com.paddle.ocr.** { *; }
-keep class ai.onnxruntime.** { *; }
# OpenCV 的 Java 类由 native 经 JNI 按名访问，release minify 下必须保留（官方 AAR 不带 consumer 规则）
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
