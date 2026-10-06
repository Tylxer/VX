plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lx.wxgroup"
    compileSdk = 35
    // 显式指定本地已装的 build-tools。AGP 8.7.3 默认要 34.0.0，
    // 本机只有 35.0.0/35.0.1/36.0.0，不写这行会报 "Failed to find Build Tools revision 34.0.0"
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.lx.wxgroup"
        minSdk = 26
        // 刻意压低 targetSdk：模块 APK 不是普通应用，跟到最新 target 只会引来
        // 一堆与自己无关的行为变更（包可见性、后台启动限制等）
        targetSdk = 33
        versionCode = 1
        versionName = "0.7.0-m3b"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // DexKit 自带 so，必须走 legacy packaging，否则 Android 6+ 上不解压，运行时崩溃
        jniLibs.useLegacyPackaging = true
        resources.excludes.add("/META-INF/*")
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

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // 框架在运行时提供实现类，绝不能用 implementation 打进 APK
    compileOnly("de.robv.android.xposed:api:82")

    // M0 暂不调用，M1 接入 DexKit 时直接开写，不用再改依赖
    implementation("org.luckypray:dexkit:2.2.0")
    implementation("com.google.code.gson:gson:2.11.0")
}
