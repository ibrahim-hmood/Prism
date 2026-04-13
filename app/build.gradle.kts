plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.prism.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.prism.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Room — local DB for installed app list
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // WorkManager — background initial app-list sync
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Lifecycle — coroutineScope inside views (lifecycleScope via ViewTreeLifecycleOwner)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Biometric authentication
    implementation("androidx.biometric:biometric:1.1.0")


    // MediaPipe Vision & GenAI — for Local LLM and Diffusion
    implementation("com.google.mediapipe:tasks-genai:0.10.14")
    implementation("com.google.mediapipe:tasks-vision-image-generator:0.10.14")

    // WireGuard Tunnel JNI wrapper
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // OkHttp for mesh proxying
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

