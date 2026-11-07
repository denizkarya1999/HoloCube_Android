plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// Chaquopy runs pip with a Python 3.8 on the build computer (the embedded Python
// version). Tools/setup-python.sh creates one under .build-tools; a different
// interpreter can be passed with -Pholocube.buildPython=/path/to/python3.8.
val buildPythonPath: String = providers.gradleProperty("holocube.buildPython").orNull
    ?: rootProject.file(".build-tools/pt/bin/python").takeIf { it.canExecute() }?.absolutePath
    ?: "python3.8"

android {
    namespace = "com.developer27.holocube.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.developer27.holocube.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        ndk {
            // Phones and the Quest share the ARM64 PyTorch wheel.
            // Pass -PholocubeAbi=x86_64 to build for an emulator instead.
            abiFilters += providers.gradleProperty("holocubeAbi").orNull
                ?.let { listOf(it) } ?: listOf("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    androidResources {
        // The label list and provenance record are read as plain text.
        noCompress += "txt"
    }

    lint {
        // Keep the verified toolchain pinned; available upgrades are not app defects.
        disable += listOf("GradleDependency", "OldTargetApi")
        // The prebuilt Android PyTorch wheel targets ARM; the x86_64 build is emulator-only.
        disable += "ChromeOsAbiSupport"
    }
}

chaquopy {
    defaultConfig {
        // Python 3.8 is the newest version with a prebuilt Android PyTorch 1.8.1 wheel,
        // which can load the original checkpoint without export or conversion.
        version = "3.8"
        buildPython(buildPythonPath)
        pip {
            install("torch==1.8.1")
            install("numpy==1.19.5")
        }
    }
}

dependencies {
    // Native runtime with 16 KB page-size support; used for letterboxing and drawing.
    implementation("org.opencv:opencv:4.14.0")

    // Kotlin & Android core libs
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Preferences
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Splash screen
    implementation("androidx.core:core-splashscreen:1.0.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
