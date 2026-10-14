package com.localvision.app.vision

import java.io.File

/**
 * JVM 单元测试用的模型目录入口。
 *
 * 关键点：这里读的是**仓库里那一份** `app/src/main/assets/models/manifest.json`，
 * 不是测试代码里的副本。因此清单被改坏、字段被删掉、任务被改名时，
 * `testDebugUnitTest` 会直接失败，而不需要有人在测试里同步改一遍。
 *
 * 生产代码走 [ModelRegistry.load]，仪器化测试走真实 AssetManager；
 * 只有纯 JVM 测试受限于没有 AssetManager，才需要这个磁盘读取的等价物。
 */
internal object TestModelCatalog {
    private val manifestFile: File by lazy { locateManifest() }

    /** 与生产同源的目录。 */
    fun registry(): ModelRegistry = ModelRegistry(ModelCatalog.parse(manifestFile.readText(Charsets.UTF_8)))

    fun faceModel(): ModelDescriptor =
        requireNotNull(registry().activeModel(VisionTask.FACE_DETECTION)) { "清单中缺少已安装的人脸模型" }

    fun rawJson(): String = manifestFile.readText(Charsets.UTF_8)

    private fun locateManifest(): File {
        val relative = "src/main/assets/${ModelCatalog.MANIFEST_ASSET_PATH}"
        var directory: File? = File(".").absoluteFile
        // 单元测试的工作目录是模块目录，但向上找几层可以让 IDE 直接运行单个测试类时也成立。
        repeat(6) {
            val candidate = directory?.resolve(relative)
            if (candidate != null && candidate.isFile) return candidate
            directory = directory?.parentFile
        }
        throw AssertionError(
            "找不到 $relative（从 ${File(".").absolutePath} 向上查找 6 层）。" +
                "模型清单必须随仓库提交，构建期 verifyBundledModels 依赖它。",
        )
    }
}
