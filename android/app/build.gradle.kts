plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.miaotiao.adskip"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.miaotiao.adskip"
        minSdk = 30
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
        ndk {
            // 只保留 arm64，减小 APK 体积（Z50U / 小新 Pad 均为 arm64）
            abiFilters += listOf("arm64-v8a")
        }
    }

    flavorDimensions += "device"
    productFlavors {
        // 普通手机：自动跳过 / 拦截摇一摇跳转 / 振动提示 / 被关恢复 / 红果自动切集
        create("phone") {
            dimension = "device"
            applicationId = "com.miaotiao.adskip"
            versionName = "0.2"
            buildConfigField("boolean", "SHAKE_GUARD", "false")
        }
        // 平板：与手机版功能一致，界面按大屏适配
        create("pad") {
            dimension = "device"
            applicationId = "com.miaotiao.adskip.pad"
            versionName = "0.2-pad"
            buildConfigField("boolean", "SHAKE_GUARD", "false")
        }
        // 努比亚 Z50 Ultra（MyOS）：额外带摇一摇防护（加速度权限检测 + 自动导航）
        create("z50u") {
            dimension = "device"
            applicationId = "com.miaotiao.adskip.z50u"
            versionName = "0.2-z50u"
            buildConfigField("boolean", "SHAKE_GUARD", "true")
        }
    }

    buildTypes {
        release {
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
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
