plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.mirrordrive"
    compileSdk = 34
    ndkVersion = "26.3.11579264"
    defaultConfig {
        applicationId = "app.carmirror"
        minSdk = 29
        targetSdk = 32
        // CI passes CARMIRROR_VERSION_CODE (100 + run number) so every build is newer than the
        // last and the in-app updater can tell them apart. Local builds keep the base number.
        versionCode = System.getenv("CARMIRROR_VERSION_CODE")?.toIntOrNull() ?: 17
        versionName = "0.93-car8" + (System.getenv("CARMIRROR_VERSION_SUFFIX") ?: "")
        // Where the in-app updater looks for new builds (GitHub Release of the main branch).
        buildConfigField("String", "UPDATE_REPO", "\"baeksh100/car-mirror\"")
        buildConfigField("String", "UPDATE_TAG", "\"build-main\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    // One fixed key for every build, so a new APK installs over the old one (the default debug
    // key is random per CI machine, which made updates fail with "앱이 설치되지 않았습니다").
    signingConfigs {
        create("shared") {
            storeFile = file("signing/carmirror-shared.keystore")
            storePassword = "carmirror"
            keyAlias = "carmirror"
            keyPassword = "carmirror"
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }
}
dependencies {
    implementation("org.jmdns:jmdns:3.6.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
}
