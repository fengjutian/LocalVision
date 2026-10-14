package com.localvision.app.vision

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** 清单结构或取值不合法。继承 [IllegalArgumentException] 以便调用方用同一套方式处理。 */
internal class ModelCatalogException(message: String) : IllegalArgumentException(message)

/**
 * `assets/models/manifest.json` 的解析器。
 *
 * 清单是模型元数据的唯一真源：运行时从它构造 [ModelDescriptor]，构建期
 * `verifyBundledModels` 任务读同一个文件校验权重 SHA-256。两端共用一份数据，
 * 因此模型身份、版本与哈希不可能只在其中一端被改动。
 *
 * 这里是纯解析，不碰 AssetManager、Context 或任何 Android 组件，
 * 所以整套 schema 约束可以直接用 JVM 单元测试覆盖。
 */
internal object ModelCatalog {
    const val MANIFEST_ASSET_PATH = "models/manifest.json"

    private const val KEY_SCHEMA_VERSION = "schemaVersion"
    private const val KEY_MODELS = "models"

    fun parse(json: String): List<ModelDescriptor> {
        val root = try {
            JSONObject(json)
        } catch (error: JSONException) {
            throw ModelCatalogException("模型清单不是合法 JSON 对象：${error.message}")
        }

        val schemaVersion = root.optInt(KEY_SCHEMA_VERSION, -1)
        if (schemaVersion != ModelRegistry.CURRENT_SCHEMA_VERSION) {
            throw ModelCatalogException(
                "不支持的模型清单 schema 版本：$schemaVersion" +
                    "（当前实现只接受 ${ModelRegistry.CURRENT_SCHEMA_VERSION}）",
            )
        }

        val models = root.optJSONArray(KEY_MODELS)
            ?: throw ModelCatalogException("模型清单缺少 $KEY_MODELS 数组")
        if (models.length() == 0) throw ModelCatalogException("模型清单为空：没有任何任务被登记")

        val descriptors = (0 until models.length()).map { readModel(models.getJSONObject(it), it) }
        val duplicated = descriptors.groupBy(ModelDescriptor::id).filterValues { it.size > 1 }.keys
        if (duplicated.isNotEmpty()) {
            throw ModelCatalogException("模型清单存在重复 id：${duplicated.joinToString()}")
        }
        return descriptors
    }

    private fun readModel(raw: JSONObject, index: Int): ModelDescriptor {
        fun optional(field: String): String = raw.optString(field, "").trim()

        fun required(field: String): String = optional(field).ifEmpty {
            throw ModelCatalogException("models[$index] 缺少必填字段 $field")
        }

        fun task(field: String): VisionTask {
            val name = required(field)
            return VisionTask.entries.firstOrNull { it.name == name }
                ?: throw ModelCatalogException("models[$index] 的 $field 是未知任务：$name")
        }

        // assetPath 显式写 null 与留空等价，都表示"这个任务没有随包分发的权重"。
        val assetPath = if (raw.isNull("assetPath")) null else optional("assetPath").ifEmpty { null }

        return ModelDescriptor(
            schemaVersion = ModelRegistry.CURRENT_SCHEMA_VERSION,
            id = required("id"),
            displayName = required("displayName"),
            version = required("version"),
            task = task("task"),
            format = required("format"),
            runtime = required("runtime"),
            runtimeVersion = required("runtimeVersion"),
            assetPath = assetPath,
            sha256 = optional("sha256"),
            license = required("license"),
            sourceUrl = optional("sourceUrl"),
            sourceCommit = optional("sourceCommit"),
            installed = raw.optBoolean("installed", false),
            limitations = readLimitations(raw.optJSONArray("limitations"), index),
        )
    }

    private fun readLimitations(raw: JSONArray?, index: Int): List<String> {
        if (raw == null) return emptyList()
        return (0 until raw.length()).map { position ->
            // JSONObject.optString 会把数字/布尔静默转成字符串，那样一份坏清单就会被当成合法清单。
            // 限制说明是要显示给用户的文案，必须显式是字符串才收。
            if (raw.opt(position) !is String) {
                throw ModelCatalogException("models[$index].limitations 必须是字符串数组，第 $position 项不是字符串")
            }
            raw.optString(position, "").trim().ifEmpty {
                throw ModelCatalogException("models[$index].limitations[$position] 是空字符串")
            }
        }
    }
}
