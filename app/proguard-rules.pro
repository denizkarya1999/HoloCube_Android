# Add project specific ProGuard rules here.
# Minification is disabled for this app (see build.gradle.kts); these rules are
# kept so a future release build keeps the Python bridge and OpenCV JNI classes.
-keep class com.chaquo.python.** { *; }
-keep class org.opencv.** { *; }
