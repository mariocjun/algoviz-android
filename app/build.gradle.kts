import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mariocjun.algoviz"
    compileSdk = 36
    ndkVersion = "26.1.10909125"

    val secretsProps = rootProject.file("secrets.properties")
    val googleApiKey = if (secretsProps.exists()) {
        Properties().apply { secretsProps.inputStream().use { load(it) } }.getProperty("GOOGLE_AI_API_KEY") ?: ""
    } else {
        ""
    }

    defaultConfig {
        applicationId = "com.mariocjun.algoviz"
        // minSdk 29 (Android 10) because:
        //   - ASensor_getHandle was added in API 29 (NDK marks it
        //     __INTRODUCED_IN(29); we use it in bench/sensors).
        //   - ASensorManager_getInstanceForPackage requires API 26.
        // All target devices (Note10+ Exynos/Snapdragon, S24 Ultra, any
        // 2020+ flagship) are >= API 29, so this is no practical loss.
        minSdk = 29
        targetSdk = 35
        versionCode = 17
        versionName = "0.6.5"

        buildConfigField("String", "GOOGLE_AI_API_KEY", "\"$googleApiKey\"")

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                // Don't hard-code -std= here: NDK r26b ships Clang 17 which
                // only knows '-std=c++2b' for C++23, while Clang 18+ uses
                // '-std=c++23'. CMakeLists's CMAKE_CXX_STANDARD 23 lets CMake
                // emit the right flag for whichever compiler the NDK ships.
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Release signing reads from an untracked keystore.properties (gitignored)
    // so the keystore path + passwords never enter version control. Without it
    // (CI, fresh clones) the release build falls back to debug signing below.
    val keystoreProps = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystoreProps.exists()) {
            create("release") {
                val props = Properties().apply { keystoreProps.inputStream().use { load(it) } }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
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
            signingConfig = if (keystoreProps.exists())
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileSdkMinor = 1
    buildToolsVersion = "37.0.0"
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Jetpack Compose (Material 3) — the native visualizer UI replacing ImGui.
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")

    // Unit tests for pure-Kotlin modules (no Android dependencies), run on the
    // host JVM via `./gradlew test`. Currently covers the Splitwise ledger.
    testImplementation("junit:junit:4.13.2")

    // Google AI (Gemini)
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")
}
