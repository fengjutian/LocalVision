package com.localvision.app.vision

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelCatalogTest {

    // ---------- 仓库里真实提交的那份清单 ----------

    @Test
    fun `bundled manifest declares exactly one model per task`() {
        val models = ModelCatalog.parse(TestModelCatalog.rawJson())

        VisionTask.entries.forEach { task ->
            assertEquals(
                "任务 $task 必须恰好登记一个模型，避免 UI 出现互相矛盾的两张模型卡",
                1,
                models.count { it.task == task },
            )
        }
    }

    @Test
    fun `only the reviewed face model is installed`() {
        val installed = ModelCatalog.parse(TestModelCatalog.rawJson()).filter(ModelDescriptor::installed)

        assertEquals(listOf(VisionTask.FACE_DETECTION), installed.map { it.task })
    }

    @Test
    fun `installed model declares asset path and full sha256`() {
        ModelCatalog.parse(TestModelCatalog.rawJson()).filter(ModelDescriptor::installed).forEach { model ->
            assertNotNull("已安装模型必须声明 assetPath：${model.id}", model.assetPath)
            assertTrue("SHA-256 必须是 64 位小写十六进制：${model.id}", model.sha256.matches(SHA256))
        }
    }

    @Test
    fun `uninstalled model never claims a weight file`() {
        ModelCatalog.parse(TestModelCatalog.rawJson()).filterNot(ModelDescriptor::installed).forEach { model ->
            assertNull("未安装模型不应带 assetPath：${model.id}", model.assetPath)
            assertEquals("未安装模型不应带哈希：${model.id}", "", model.sha256)
        }
    }

    @Test
    fun `every model states its limitations`() {
        // AGENTS.md 要求"所有用户可见限制必须与实际实现一致"；
        // 空 limitations 会让 UI 把尚未验收的能力显示成已验收。
        ModelCatalog.parse(TestModelCatalog.rawJson()).forEach { model ->
            assertTrue("模型缺少限制说明：${model.id}", model.limitations.isNotEmpty())
        }
    }

    @Test
    fun `model ids are unique`() {
        val models = ModelCatalog.parse(TestModelCatalog.rawJson())

        assertEquals(models.size, models.map { it.id }.toSet().size)
    }

    // ---------- 坏清单：每条都必须硬失败，而不是被静默接受 ----------

    @Test
    fun `rejects unknown schema version`() {
        assertRejected("schema 版本") {
            JSONObject().put("schemaVersion", 99).put("models", JSONArray()).toString()
        }
    }

    @Test
    fun `rejects malformed json`() {
        assertRejected("JSON") { "{not json" }
    }

    @Test
    fun `rejects manifest without a models array`() {
        assertRejected("models") { JSONObject().put("schemaVersion", 1).toString() }
    }

    @Test
    fun `rejects empty model list`() {
        assertRejected("空") {
            JSONObject().put("schemaVersion", 1).put("models", JSONArray()).toString()
        }
    }

    @Test
    fun `rejects model with blank id`() {
        assertRejected("id") { manifest { it.put("id", "   ") } }
    }

    @Test
    fun `rejects unknown task name`() {
        assertRejected("任务") { manifest { it.put("task", "FACE_RECOGNITION") } }
    }

    @Test
    fun `rejects uppercase sha256`() {
        assertRejected("sha256") { manifest(installed = true, assetPath = MODEL_ASSET) { it.put("sha256", "A".repeat(64)) } }
    }

    @Test
    fun `rejects truncated sha256`() {
        assertRejected("sha256") { manifest(installed = true, assetPath = MODEL_ASSET) { it.put("sha256", "a".repeat(63)) } }
    }

    @Test
    fun `rejects installed model without asset path`() {
        assertRejected("assetPath") { manifest(installed = true, assetPath = null) }
    }

    @Test
    fun `rejects installed model without sha256`() {
        assertRejected("sha256") { manifest(installed = true, assetPath = MODEL_ASSET, sha256 = "") }
    }

    @Test
    fun `rejects duplicate model ids`() {
        // 两个字段齐全但 id 相同的条目，才能真正走到重复检测那一支。
        val duplicated = JSONObject()
            .put("schemaVersion", 1)
            .put("models", JSONArray().put(modelJson()).put(modelJson()))
            .toString()

        assertRejected("重复") { duplicated }
    }

    @Test
    fun `rejects non string limitations`() {
        assertRejected("limitations") { manifest { it.put("limitations", JSONArray().put(1).put(2)) } }
    }

    @Test
    fun `rejects blank limitation text`() {
        assertRejected("limitations") { manifest { it.put("limitations", JSONArray().put("  ")) } }
    }

    // ---------- 降级行为 ----------

    @Test
    fun `registry degrades to empty catalog instead of throwing`() {
        // 生产路径不能因为清单损坏而崩溃：空目录会让 UI 说明"模型未安装"并禁用分析，
        // 这比启动即崩溃更可接受，也绝不等于假装有模型可用。
        assertTrue(ModelRegistry.fromManifestJson("{broken").modelsFor(VisionTask.FACE_DETECTION).isEmpty())
        assertTrue(ModelRegistry.fromManifestJson("").modelsFor(VisionTask.FACE_DETECTION).isEmpty())
        assertTrue(ModelRegistry.fromManifestJson("""{"schemaVersion":99,"models":[]}""").modelsFor(VisionTask.FACE_DETECTION).isEmpty())
    }

    private fun assertRejected(expectedFragment: String, manifestJson: () -> String) {
        try {
            ModelCatalog.parse(manifestJson())
            fail("清单应当被拒绝，但解析成功了：$manifestJson")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "错误信息应包含「$expectedFragment」，实际为：${expected.message}",
                expected.message.orEmpty().contains(expectedFragment),
            )
        }
    }

    /**
     * 拼出一份"字段全部合法"的模型清单，再通过 [mutate] 把某个字段改坏。
     *
     * 刻意用 [JSONObject] 构造而不是字符串拼接：早期版本用字符串拼接时，
     * 生成的 JSON 本身是坏的，于是每个用例都失败在"JSON 解析"，
     * 而不是失败在它真正要验证的那条规则上。
     */
    private fun manifest(
        installed: Boolean = false,
        assetPath: String? = null,
        sha256: String = if (installed) "a".repeat(64) else "",
        mutate: (JSONObject) -> Unit = {},
    ): String = JSONObject()
        .put("schemaVersion", 1)
        .put("models", JSONArray().put(modelJson(installed, assetPath, sha256, mutate)))
        .toString()

    private fun modelJson(
        installed: Boolean = false,
        assetPath: String? = null,
        sha256: String = if (installed) "a".repeat(64) else "",
        mutate: (JSONObject) -> Unit = {},
    ): JSONObject = JSONObject()
        .put("id", "test-model")
        .put("displayName", "测试模型")
        .put("version", "1")
        .put("task", VisionTask.FACE_DETECTION.name)
        .put("format", "ONNX")
        .put("runtime", "ONNX_RUNTIME_ANDROID")
        .put("runtimeVersion", "1.0.0")
        .put("assetPath", assetPath ?: JSONObject.NULL)
        .put("sha256", sha256)
        .put("license", "MIT")
        .put("sourceUrl", "")
        .put("sourceCommit", "")
        .put("installed", installed)
        .put("limitations", JSONArray().put("仅用于测试"))
        .also(mutate)

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
        const val MODEL_ASSET = "models/test/model.onnx"
    }
}
