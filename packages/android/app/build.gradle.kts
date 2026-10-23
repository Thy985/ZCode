plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ai.zcode.mobile"
    buildToolsVersion = "36.1.0"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "ai.zcode.mobile"
        minSdk = 24
        targetSdk = 36
        // 跟上游 ZCode 版本走：3.16.2 内核 + 第 1 版安卓壳。
        versionCode = 3_16_02_03
        versionName = "3.16.2-android.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "REMOTE_CONTROL_DEFAULT_URL", "\"https://zcode.z.ai/remote\"")

        // 包体大头是 MLKit 扫码的四套 .so。侧载给自己手机时用 -PzcodeAbi=arm64-v8a 只留真机那套，
        // 不传这个属性就照常出全 ABI 通用包。
        (findProperty("zcodeAbi") as String?)?.takeIf { it.isNotBlank() }?.let { abi ->
            ndk { abiFilters.add(abi.trim()) }
        }
    }

    // 正式签名走环境变量，仓库里不落 keystore 与口令；缺省时产出未签名包，调试用 assembleDebug。
    signingConfigs {
        create("zcodeRelease") {
            val storePath = System.getenv("ZCODE_KEYSTORE_PATH")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = System.getenv("ZCODE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ZCODE_KEY_ALIAS")
                keyPassword = System.getenv("ZCODE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "REMOTE_CONTROL_DEFAULT_URL", "\"https://zcode.z.ai/remote\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (System.getenv("ZCODE_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("zcodeRelease")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
