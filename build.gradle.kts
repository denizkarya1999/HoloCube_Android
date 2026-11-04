// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.7.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    // Embeds Python, PyTorch, and the original yolo26n.pt checkpoint in the APK.
    id("com.chaquo.python") version "16.1.0" apply false
}
