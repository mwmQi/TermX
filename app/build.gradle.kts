plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.termx.app"
    compileSdk = 34

    // Signing configuration for CI/CD release builds
    // Set these environment variables in GitHub Actions:
    //   RELEASE_KEYSTORE_PATH - path to keystore file
    //   RELEASE_KEYSTORE_ALIAS - key alias
    //   RELEASE_KEYSTORE_PASSWORD - keystore password
    //   RELEASE_KEY_PASSWORD - key password
    val ksPath = System.getenv("RELEASE_KEYSTORE_PATH")
    val ksAlias = System.getenv("RELEASE_KEYSTORE_ALIAS")
    val ksPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
    val ksKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")

    signingConfigs {
        if (!ksPath.isNullOrBlank() && File(ksPath).exists()) {
            create("release") {
                storeFile = File(ksPath)
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.termx.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "3.0.0-power"

        // NDK configuration for native PTY + X11 support
        ndkVersion = "26.1.10909125"
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        // CMake arguments
        externalNativeBuild {
            cmake {
                cppFlags += ""
                arguments += listOf(
                    "-DANDROID_STL=none",
                    "-DANDROID_PLATFORM=android-24"
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (!ksPath.isNullOrBlank() && File(ksPath).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isDebuggable = true
            isMinifyEnabled = false
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

    // Native PTY + X11 library build configuration
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Packaging options to handle potential conflicts
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0"
            )
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Fragment & ViewPager2 (for session swipe)
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.viewpager2:viewpager2:1.0.0")

    // Preferences
    implementation("androidx.preference:preference-ktx:1.2.1")

    // RecyclerView (for session tabs)
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // JSON parsing for package repository and config files
    implementation("com.google.code.gson:gson:2.10.1")

    // Biometric authentication
    implementation("androidx.biometric:biometric:1.1.0")

    // Security crypto for encryption API
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // WorkManager for cron-like scheduling
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Lifecycle components for service integration
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
}
