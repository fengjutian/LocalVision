package com.localvision.app.vision

import android.content.Context

/**
 * 结构化模型元数据。
 *
 * 字段与 `docs/PRODUCT_SPEC.md` §8 的模型清单 schema 对齐。[ModelRegistry] 是这些值的唯一来源：
 * UI 的模型面板、导出 JSON、分析历史都从这里取值，任何一处都不得再硬编码模型 ID 或版本。
 */
data class ModelDescriptor(
    val schemaVersion: Int,
    val id: String,
    val displayName: String,
    val version: String,
    val task: VisionTask,
    val format: String,
    val runtime: String,
    val runtimeVersion: String,
    val assetPath: String?,
    val sha256: String,
    val license: String,
    val sourceUrl: String,
    val sourceCommit: String,
    val installed: Boolean,
    val limitations: List<String>,
) {
    init {
        require(schemaVersion == ModelRegistry.CURRENT_SCHEMA_VERSION) {
            "不支持的模型清单 schema 版本：$schemaVersion"
        }
        require(id.isNotBlank()) { "模型 id 不能为空" }
        if (sha256.isNotEmpty()) {
            require(SHA256_PATTERN.matches(sha256)) { "sha256 必须是 64 位小写十六进制：$sha256" }
        }
        require(!installed || (assetPath != null && sha256.isNotEmpty())) {
            "已安装模型必须同时声明 assetPath 与 sha256"
        }
    }

    /** 面板上展示的哈希短摘要；完整值由调用方按需展开并允许复制。 */
    val shortSha256: String get() = sha256.take(SHORT_SHA256_LENGTH)

    companion object {
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        const val SHORT_SHA256_LENGTH = 12
    }
}

class ModelRegistry(models: List<ModelDescriptor>) {
    private val modelsByTask = models.groupBy(ModelDescriptor::task)

    fun modelsFor(task: VisionTask): List<ModelDescriptor> = modelsByTask[task].orEmpty()

    fun activeModel(task: VisionTask): ModelDescriptor? = modelsFor(task).firstOrNull(ModelDescriptor::installed)

    /** 任务是否具备真实可执行模型。为 false 时 UI 不得展示"可分析"状态。 */
    fun supports(task: VisionTask): Boolean = activeModel(task) != null

    companion object {
        /** 当前实现支持的主版本；未知主版本必须拒绝加载而不是静默忽略。 */
        const val CURRENT_SCHEMA_VERSION = 1

        /**
         * 生产目录：解析 `assets/models/manifest.json`。
         *
         * 权重身份、哈希、来源与许可证都只在那一份清单里定义，构建期 `verifyBundledModels`
         * 校验的是同一个文件，因此运行时看到的和构建时核对过的不可能是两套数据。
         *
         * 读取或解析失败时返回**空目录**而不是抛异常：目录为空时 UI 会说明模型未安装并禁用
         * 分析，这比让应用在启动时崩溃更可接受，也绝不等于"假装有模型可用"。
         */
        fun load(context: Context): ModelRegistry = ModelRegistry(readManifest(context))

        /** 从给定 JSON 文本构造目录；解析失败同样降级为空目录。 */
        fun fromManifestJson(json: String): ModelRegistry = ModelRegistry(parseOrEmpty(json))

        private fun readManifest(context: Context): List<ModelDescriptor> = parseOrEmpty(
            runCatching {
                context.assets.open(ModelCatalog.MANIFEST_ASSET_PATH).use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull().orEmpty(),
        )

        private fun parseOrEmpty(json: String): List<ModelDescriptor> =
            if (json.isBlank()) emptyList() else runCatching { ModelCatalog.parse(json) }.getOrDefault(emptyList())
    }
}
