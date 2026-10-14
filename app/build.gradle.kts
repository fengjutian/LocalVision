import java.security.MessageDigest

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

// ---------------------------------------------------------------------------
// N-006：构建期模型 SHA-256 校验
//
// 预期哈希来自 assets/models/manifest.json —— 运行时的 ModelRegistry 读的是同一个文件。
// 刻意不从 Markdown 或 Kotlin 源码正则解析数值：文档会漂移，解析器本身也会出错。
// ---------------------------------------------------------------------------

val modelManifestFile = layout.projectDirectory.file("src/main/assets/models/manifest.json")
// assetPath 与运行时的 AssetManager 一致，相对于 src/main/assets，而不是 models/ 子目录。
val androidAssetsDir = layout.projectDirectory.dir("src/main/assets")
// 必须与 ModelCatalog.MANIFEST_ASSET_PATH 保持一致。
val MANIFEST_ASSET_PATH = "models/manifest.json"

val verifyBundledModels by tasks.registering {
    group = "verification"
    description = "校验随包分发的模型权重与 assets/models/manifest.json 登记的 SHA-256 一致"

    inputs.file(modelManifestFile)
        .withPropertyName("modelManifest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(androidAssetsDir)
        .withPropertyName("androidAssets")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(layout.buildDirectory.file("reports/model-verification/verified-models.txt"))
        .withPropertyName("verificationReport")

    doLast {
        val manifest = modelManifestFile.asFile
        val assetsRoot = androidAssetsDir.asFile
        if (!manifest.isFile) throw GradleException("缺少模型清单：${manifest.absolutePath}")

        val root = groovy.json.JsonSlurper().parseText(manifest.readText(Charsets.UTF_8))
        if (root !is Map<*, *>) throw GradleException("模型清单必须是 JSON 对象：$manifest")
        val schemaVersion = (root["schemaVersion"] as? Number)?.toInt()
        if (schemaVersion != 1) {
            throw GradleException("不支持的模型清单 schema 版本：$schemaVersion（期望 1）")
        }
        val models = root["models"] as? List<*>
        if (models.isNullOrEmpty()) throw GradleException("模型清单中没有任何模型条目：$manifest")

        val sha256Pattern = Regex("^[0-9a-f]{64}$")
        val weightSuffixes = listOf(".onnx", ".tflite", ".ort", ".pb", ".caffemodel")
        val problems = mutableListOf<String>()
        val declaredAssets = mutableSetOf<String>()
        val verified = mutableListOf<String>()

        models.forEachIndexed { index, entry ->
            val model = entry as? Map<*, *>
            if (model == null) {
                problems += "models[$index] 不是对象"
                return@forEachIndexed
            }
            val id = (model["id"] as? String).orEmpty().ifEmpty { "models[$index]" }
            val installed = model["installed"] as? Boolean ?: false
            val assetPath = (model["assetPath"] as? String)?.trim()?.ifEmpty { null }
            if (assetPath != null && assetPath.startsWith("/")) {
                problems += "$id 的 assetPath 必须是相对 assets 根目录的路径，不能以 / 开头：$assetPath"
                return@forEachIndexed
            }
            val declaredHash = (model["sha256"] as? String).orEmpty().trim()

            if (!installed && assetPath == null) {
                verified += "SKIPPED  $id  未安装，无随包权重"
                return@forEachIndexed
            }
            if (assetPath == null) {
                problems += "$id 声明为已安装但没有 assetPath"
                return@forEachIndexed
            }
            if (!sha256Pattern.matches(declaredHash)) {
                problems += "$id 的 sha256 不是 64 位小写十六进制：'$declaredHash'"
                return@forEachIndexed
            }

            declaredAssets += assetPath.replace('\\', '/')
            val file = assetsRoot.resolve(assetPath).normalize()
            if (!file.isFile) {
                problems += "$id 的权重文件不存在：src/main/assets/$assetPath"
                return@forEachIndexed
            }
            val actual = file.sha256Hex()
            if (actual != declaredHash) {
                // 只报告期望值与实测摘要，绝不输出模型文件内容。
                problems += "$id 的 SHA-256 不一致：期望 $declaredHash，实际 $actual"
                return@forEachIndexed
            }
            verified += "VERIFIED  $id  ${file.length()} bytes  sha256=$declaredHash"
        }

        // 未登记的权重：说明文档之类的非权重文件允许存在，但任何权重后缀都必须先进清单。
        assetsRoot.walkTopDown().filter(File::isFile).forEach { file ->
            val relative = file.relativeTo(assetsRoot).path.replace('\\', '/')
            if (relative == MANIFEST_ASSET_PATH) return@forEach
            if (weightSuffixes.any(relative::endsWith) && relative !in declaredAssets) {
                problems += "assets 下存在未登记的权重文件：$relative（须先在 models/manifest.json 登记来源、许可证与 SHA-256）"
            }
        }

        val report = layout.buildDirectory.file("reports/model-verification/verified-models.txt").get().asFile
        report.parentFile.mkdirs()
        report.writeText(
            buildString {
                appendLine("LocalVision bundled model verification")
                appendLine("declared models: ${models.size}")
                appendLine()
                verified.forEach(::appendLine)
                if (problems.isNotEmpty()) {
                    appendLine()
                    appendLine("FAILED (${problems.size}):")
                    problems.forEach { appendLine(" - $it") }
                }
            },
            Charsets.UTF_8,
        )

        if (problems.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("随包模型校验失败，共 ${problems.size} 项：")
                    problems.forEach { appendLine(" - $it") }
                    append("完整报告：${report.absolutePath}")
                },
            )
        }
        logger.lifecycle("verifyBundledModels: ${verified.count { it.startsWith("VERIFIED") }} 个权重通过 SHA-256 校验")
        verified.filter { it.startsWith("VERIFIED") }.forEach { logger.lifecycle("  $it") }
    }
}

fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

// 模型被换掉时必须让构建失败，而不是等到用户手机上才发现。
tasks.named("check").configure { dependsOn(verifyBundledModels) }
// matching + configureEach 是惰性的：AGP 在本脚本之后才创建 preReleaseBuild 与 assembleRelease，
// 用 tasks.named(...) 会在配置阶段直接抛 "Task with name 'preReleaseBuild' not found"。
tasks.matching { it.name == "preReleaseBuild" || it.name == "assembleRelease" }.configureEach {
    dependsOn(verifyBundledModels)
}

