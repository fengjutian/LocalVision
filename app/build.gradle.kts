plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.localvision.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.localvision.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 发布口径只面向真实 ARM64 设备；debug 会在此基础上追加 x86_64 供模拟器使用。
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        debug {
            // 标准 x86_64 模拟器需要它才能安装 debug APK 跑 androidTest；
            // ARM64 真机同样安装 debug/release，因此 debug 仍然包含 arm64-v8a。
            // 不为 release 增加 x86_64，避免无意扩大发布 APK 的原生库面积。
            // 代价（已知并接受）：release 在 x86_64 ChromeOS 上不可安装，
            // lint 会为此报 ChromeOsAbiSupport 警告，属于有意为之而不是遗漏。
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"

    lint {
        // local.properties 是机器本地、已 gitignore 的文件，sdk.dir 必须使用
        // 原生 Windows 路径；PropertyEscape 对该文件是已知误报。
        disable += "PropertyEscape"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    testImplementation("org.mockito:mockito-core:5.24.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.06.01"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    // createAndroidComposeRule 需要 ActivityScenarioRule；这里显式声明，避免依赖传递关系漂移
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

