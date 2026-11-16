plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.hoard.hoard"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.hoard.hoard"
        // API 29+: MediaStore can write to Movies/Hoard without any storage permission.
        minSdk = 29
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    // youtubedl-android ships python + yt-dlp + ffmpeg as native libs that must be extracted to disk.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // These .zip.so files are archives, not ELF libraries; stripping them errors out.
            keepDebugSymbols += "**/*.zip.so"
        }
    }

    buildTypes {
        release {
            // Keep R8 off: the yt-dlp wrapper uses reflection (Jackson) and breaks when shrunk.
            isMinifyEnabled = false
            isShrinkResources = false
            // TODO: Add your own signing config before publishing a release.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("androidx.core:core-ktx:1.15.0")
}

flutter {
    source = "../.."
}
