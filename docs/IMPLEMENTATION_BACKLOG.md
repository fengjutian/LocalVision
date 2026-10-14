# LocalVision 未完成功能与技术实现方案

- 文档版本：1.0
- 基线版本：LocalVision `0.2.0`
- 编写日期：2026-10-14
- 目标读者：负责继续实现本项目的编码 Agent / Android 工程师
- 规范来源：`docs/PRODUCT_SPEC.md`

## 1. 目的与实施原则

本文只描述当前仓库尚未实现、实现不完整或缺少验收闭环的能力。它不是要求一次性实现全部路线图。

实施时必须遵守以下原则：

1. 按本文阶段顺序提交；每个阶段独立编译、测试和验收，不做跨阶段大重构。
2. 不生成模拟识别结果。没有真实模型、模型校验失败或能力未安装时，返回明确的 `ModelUnavailable` / `InvalidModel`。
3. 默认离线，不新增 `INTERNET` 权限。云端 Provider 只有进入阶段 E 并经产品确认后才允许实现。
4. 不实现身份识别、人脸特征库、年龄/性别/种族推断、动作识别、跌倒检测或跨图片人物关联。
5. 所有坐标在领域层使用 EXIF 校正后原图的 `[0,1]` 归一化坐标；运行时 Tensor 类型不得进入 UI。
6. 每个新模型必须先固定来源、提交版本、SHA-256、许可证、输入输出契约和限制，再接入代码。
7. 不删除现有人脸检测、取消、导出、历史及隐私保护能力。

## 2. 当前实现边界

### 2.1 已实现，不应重复开发

- 单张图片系统选择器，无相册全量权限。
- JPEG、PNG、WebP、HEIF 解码，Android 8 EXIF 旋转/镜像处理及超大图采样。
- YuNet FP32 + ONNX Runtime Android CPU 人脸检测。
- 人脸框、五点关键点、置信度、数量和分阶段耗时。
- 图片缩放、平移、双击复位、叠加层开关和结果排序。
- 用户主动保存标注 JPEG、导出 JSON、确认后分享。
- 默认关闭且不保存图片/URI/人脸特征的分析历史。
- 推理串行化、协作取消、generation 过期结果拦截。
- 模型文件 SHA-256 运行时校验。

### 2.2 已有骨架但未完成

| 能力 | 当前状态 | 缺口 |
|---|---|---|
| 模型目录 F-007 | `ModelDescriptor` 和模型卡片已存在 | 缺 SHA-256、来源、运行时版本、输入输出契约等结构化字段；模型卡片未完整展示 |
| 异常恢复 F-008 | 已分类主要错误 | 缺模型列表为空的安全 UI、重试入口和完整自动化测试 |
| 结果导出 F-009 | JPEG/JSON 已实现 | JSON 缺模型版本、哈希、阈值、坐标契约和 schema 版本 |
| 阈值调整 | 下次分析真实生效 | 参数变化后旧结果仍按新阈值重绘，缺少“结果已过期”状态 |
| 模型缓存隐私控制 | 当前模型随 APK 发布 | 尚无可删除的下载模型缓存；UI 也无模型存储管理 |
| 人脸检测发布验收 | 算法已接入 | 缺独立测试集、质量报告、三档真机性能和商业使用复核 |
| 姿态/店铺任务 | 枚举、占位模型和结果类型已存在 | 无真实模型、引擎、后处理、叠加层和详情页 |

### 2.3 完全未开发

- 0.3.0 单人姿态估计。
- 0.4.0 门面检测、店铺类别分类、招牌 OCR。
- 批量分析 F-010。
- 云端 Provider F-011。
- 可下载/升级/回滚的模型管理。
- 开发者模式（显示低于发布阈值的候选、模型诊断信息）。
- INT8 对照版本及 NNAPI 实验后端。
- CI、仪器化集成测试、模型回归测试和自动化性能报告。

## 3. 推荐交付顺序

| 阶段 | 目标 | 是否属于 0.2.x | 前置条件 |
|---|---|---:|---|
| A | 补齐人脸检测产品闭环 | 是 | 无 |
| B | 补齐自动化测试、CI 与发布证据 | 是 | A 可并行部分工作 |
| C | 实现单人姿态 MVP | 0.3.0 | A、B 完成；姿态模型准入完成 |
| D | 实现店铺分析 MVP | 0.4.0 | 明确三个子能力各自模型 |
| E | 批量分析、模型下载、云端 Provider | 后续 | 单图端侧能力稳定且需求得到验证 |

MiniMax 首次实现只执行阶段 A。阶段 A 合并并通过测试后，再单独执行阶段 B；不要在同一个变更中接入姿态或店铺模型。

---

## 4. 阶段 A：补齐人脸检测产品闭环

### A1. 模型描述符与模型目录

#### 目标

让 F-007 的“版本、哈希、许可证、限制”来自同一个结构化数据源，并可在 UI 与导出文件中审计。

#### 数据结构

扩展 `ModelDescriptor`，至少增加：

```kotlin
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
)
```

约束：

- `sha256` 使用 64 位小写十六进制。
- 当前 schema 主版本只接受 `1`。
- `ModelRegistry` 是模型元数据唯一来源，禁止在 `AnalysisHistory`、`ResultExporter` 或 UI 硬编码模型 ID/版本。
- 当前 YuNet 值必须与 `PRODUCT_SPEC.md` 和 `third_party/yunet/MODEL_CARD.md` 一致。

#### UI

- `ModelCard` 接受可空模型；没有注册项时展示“模型信息不可用”，不得调用 `.first()`。
- 展示名称、版本、运行时、许可证、SHA-256 短摘要（前 12 位）及限制。
- 提供“查看完整信息”的可展开区域；完整哈希可复制。
- 未安装任务保留禁用/明确错误状态，不显示为已可分析。

#### 测试

- 注册表包含每个 `VisionTask` 的描述符。
- YuNet 的元数据与预期固定值一致。
- 非法 schema、非法 SHA-256 被拒绝。
- 空注册表不会导致 UI 或 ViewModel 崩溃。

### A2. 参数快照与结果过期状态

#### 问题

当前阈值只在下一次分析生效，但用户调整 Slider 后，旧结果仍存在，并按新阈值改变透明度，容易形成“结果已重新计算”的错觉。

#### 实现

新增不可变参数快照：

```kotlin
data class AnalysisConfig(
    val scoreThreshold: Float,
    val nmsThreshold: Float,
)

data class CompletedAnalysis(
    val result: VisionResult,
    val model: ModelDescriptor,
    val config: AnalysisConfig,
)
```

推荐将 `AnalysisStatus.Success` 改为携带 `CompletedAnalysis`。渲染旧结果必须使用成功时的 `config.scoreThreshold`，而不是当前 Slider 值。

当当前配置与成功结果配置不一致时：

- 保留旧结果，避免界面突兀清空；
- 显示“参数已更改，请重新分析”；
- “开始本地分析”按钮文案改为“使用新参数重新分析”；
- 导出仍使用成功结果对应的参数快照；
- 不自动重新推理，避免 Slider 拖动触发频繁原生计算。

#### 测试

- 分析使用点击开始时的参数快照。
- 运行期间移动 Slider 不改变当前请求参数。
- 成功后移动 Slider 会标记结果过期，但不改变旧结果的显示/导出参数。
- 重新分析成功后清除过期标记。

### A3. JSON 导出契约 v1

#### 目标

导出结果可独立追溯，不依赖应用内部状态。

#### Schema

```json
{
  "schemaVersion": 1,
  "createdAt": "2026-10-14T12:00:00Z",
  "task": "FACE_DETECTION",
  "coordinateSystem": "EXIF_CORRECTED_ORIGINAL_NORMALIZED_0_1",
  "model": {
    "id": "yunet-face-detection",
    "version": "2023mar-fp32",
    "sha256": "...",
    "runtime": "ONNX_RUNTIME_ANDROID",
    "runtimeVersion": "1.22.0",
    "license": "MIT"
  },
  "config": {
    "scoreThreshold": 0.6,
    "nmsThreshold": 0.3
  },
  "performanceMs": {
    "decode": 0,
    "preprocess": 0,
    "modelLoad": 0,
    "inference": 0,
    "postprocess": 0,
    "total": 0
  },
  "detections": []
}
```

要求：

- 所有浮点数以 JSON number 输出，禁止字符串化。
- 每个 detection 包含 `label`、`confidence`、`bounds` 和五个具名关键点。
- 不包含原图 URI、文件名、绝对路径、图片字节、人脸裁剪或设备稳定标识。
- 使用 UTC ISO-8601 时间。
- 保存失败不能破坏已有文件；优先先构建完整字节后再写入目标流。

#### 测试

- 固定结果的 golden JSON 结构测试。
- 空检测可正确导出。
- 特殊浮点边界不会生成 `NaN` / `Infinity`。
- 输出不包含 URI、路径、GPS 或图片内容。

### A4. 错误恢复与模型缺失 UI

#### 实现要求

- `modelsFor(task).first()` 改为安全查找。
- 模型缺失时，在分析按钮附近展示原因；按钮禁用或点击后显示相同、可理解的错误，两种路径只能选择一种并保持一致。
- 图片错误、模型错误、内存不足、取消、未知错误分别提供适合的下一步：重新选图、重试、选择小图等。
- 错误状态不得覆盖当前选图，除非 URI 已不可读。
- 成功空结果显示“未检测到人脸”，不得进入 Error。

#### 测试

- 三个任务中只有人脸模型已安装时，另外两个任务不会崩溃或产生模拟结果。
- 模型注册表为空时页面可正常组合。
- 各 `AnalysisFailure` 映射到稳定的 UI 错误类型和建议操作。

### A5. 字符串资源与结果列表完整性

- 把用户可见字符串迁移到 `res/values/strings.xml`。
- 格式化置信度时明确 `Locale`；显示文本使用本地 Locale，JSON 使用 Locale-independent number。
- `faces.take(10)` 不得静默截断。可选择：完整懒列表，或显示前 10 条并提供“查看全部 N 条”。
- 保持系统字体缩放；200% font scale 下主要操作和完整结果入口仍可访问。
- 关键状态不能只用颜色表达；弱化项同时提供“接近阈值”文本/语义描述。

---

## 5. 阶段 B：测试、CI 与发布验收

### B1. JVM/Robolectric 单元测试

新增测试至少覆盖：

1. `YuNetPreprocessor`
   - 纯色像素验证 NCHW、BGR、`[0,255]`；
   - 横图/竖图的缩放、黑色 padding 和逆变换；
   - 中间步骤异常时 Bitmap 资源释放。
2. `MainViewModel`
   - 换图、换任务、取消后不接收过期成功/异常/阶段回调；
   - 同一时刻只启动一个任务；
   - 参数快照和结果过期状态；
   - 成功空结果与错误严格区分。
3. `ResultExporter`
   - A3 的 schema 与隐私字段检查。
4. `AnalysisHistory`
   - 默认关闭、100 条上限、30 天过期、清除；
   - 不写入 URI 或图片数据；
   - 读取操作不产生无意义的重复写入。
5. `OnnxFaceDetectionEngine` 可测试边界
   - 把运行时 Session 包装成内部接口并注入 fake；
   - 正常、异常、取消路径都关闭 Tensor、Result 和 RunOptions；
   - close 与执行竞态不提前释放 Session。

### B2. 仪器化集成测试

创建 `app/src/androidTest`，至少覆盖：

- 启动 → 选择测试图片（使用测试资源或注入 URI）→ 分析 → 有结果/空结果。
- 连续换图与取消，不交付旧结果。
- 任务切换到未安装模型，显示真实缺失状态。
- 旋转/镜像基准图的叠加误差不超过 2 px。
- 超大图采样后不发生 OOM。
- 损坏模型返回模型错误而非空结果。
- 页面进入后台时取消活动分析，恢复后状态一致。

构建配置为 debug 测试提供 `x86_64`，release 仍可只发布 `arm64-v8a`。不要以增加 x86_64 代替 ARM64 真机测试。

### B3. CI

新增 GitHub Actions（若代码托管平台不是 GitHub，则提供等价流水线）：

1. JDK 17。
2. `./gradlew testDebugUnitTest lintDebug assembleDebug`。
3. 缓存 Gradle，但缓存 key 必须包含 wrapper 和构建脚本哈希。
4. 上传测试、lint 报告和 Debug APK。
5. 禁止 CI 下载未固定版本的模型文件；仓库内模型 SHA-256 校验必须作为测试执行。
6. PR 上任何单测、lint error、构建或模型哈希失败均阻止合并。

### B4. 人工/真机发布证据

这一部分不能用普通单元测试代替，也不能由编码 Agent 宣称完成：

- 建立 100–300 张来源和授权可追溯的独立测试集。
- 报告 Precision、Recall、mAP，并按小脸、侧脸、遮挡、暗光、旋转等切片。
- 低、中、高三档 ARM64 真机记录 P50/P95、峰值 PSS、30 次连续推理、耗电和温升。
- 完成 YuNet 与 ONNX Runtime 商业使用及再分发复核。
- 记录版本、设备、图片尺寸、模型哈希、应用 commit 和原始测量数据。

在以上证据齐全前，README 和 UI 继续保留“待真机与独立测试集验收”。

---

## 6. 阶段 C：单人姿态 MVP（0.3.0）

阶段 C 开始前必须先提交模型选型文档，不能由实现者临时下载任意模型。

### C1. 模型准入

模型至少输出 COCO 17 点，并归档：来源 commit、权重 SHA-256、许可证、输入尺寸/颜色空间/归一化、输出节点/shape、关键点顺序、可见性语义和官方指标。

若模型需要先检测人框再估计姿态，必须明确两阶段耗时、取消和坐标变换；不得把“单人”实现为从多人结果中无说明地随意选一个人。主要人物选择规则应固定，例如有效框面积最大且置信度达标。

### C2. 领域模型

建议扩展而不是复用人脸 `Keypoint`：

```kotlin
enum class CocoKeypoint { NOSE, LEFT_EYE, RIGHT_EYE, /* ... */ RIGHT_ANKLE }

enum class KeypointVisibility { VISIBLE, OCCLUDED, NOT_PRESENT }

data class PoseKeypoint(
    val type: CocoKeypoint,
    val point: NormalizedPoint?,
    val confidence: Float,
    val visibility: KeypointVisibility,
)

data class Pose(
    val keypoints: List<PoseKeypoint>,
    val confidence: Float,
    val bounds: NormalizedRect?,
)
```

不可见或图外点使用 `point = null`，不得钳到图片边缘伪装成有效坐标。

### C3. 引擎结构

新增：

- `OnnxPoseEstimationEngine`
- `PosePreprocessor`
- `PosePostprocessor`
- `PoseSkeleton`（固定骨架边集合）
- 姿态模型注册项和模型卡

复用 `AndroidImageDecoder`、取消门、性能分段与 `VisionEngine` 边界。`RegistryBackedVisionEngine` 按任务路由，不得让 MainViewModel 直接依赖具体姿态引擎。

### C4. UI

- 分析前明确“仅分析一个主要人物”。
- 绘制可信关键点与骨架；任一端点低于阈值、为空或越界时不画连接线。
- 提供关键点/骨架开关、阈值调节和 COCO 17 点文本详情。
- 无可信姿态返回成功空结果。
- 不显示动作名、健康推断或跌倒结论。

### C5. 验收

严格执行 `PRODUCT_SPEC.md` §10.2：至少 200 张合法独立测试集、`PCK@0.2 >= 0.80`、`AP50 >= 0.65`、合成图误差 ≤ 2 px，以及低/中/高三档 ARM64 真机报告。

---

## 7. 阶段 D：店铺分析 MVP（0.4.0）

店铺分析不是一个单模型功能，必须拆成三个可独立缺省的能力：

1. `STOREFRONT_DETECTION`：门面区域检测。
2. `STORE_CATEGORY_CLASSIFICATION`：对门面区域给出受控类别与置信度。
3. `SIGN_OCR`：招牌文字区域和文本识别。

建议将现有单个 `STOREFRONT_ANALYSIS` 保留为用户任务，由内部 pipeline 编排三个 capability。每个 capability 有独立 `ModelDescriptor`、安装状态、许可证和限制。

结构化结果建议为：

```kotlin
data class Storefront(
    val bounds: NormalizedRect,
    val detectionConfidence: Float,
    val category: CategoryResult?,
    val signs: List<SignTextResult>,
)

data class CategoryResult(val id: String, val displayName: String, val confidence: Float)

data class SignTextResult(
    val bounds: NormalizedRect,
    val text: String,
    val confidence: Float,
    val language: String?,
)
```

要求：

- 缺少分类或 OCR 模型时仍可返回门面检测结果，并明确哪些子能力不可用。
- OCR 文本只表示模型读到的画面文字，不声称经营主体、品牌归属、营业状态或地理位置。
- OCR 全文不得写日志或默认写历史。
- 导出需列出每个子结果对应的模型元数据。
- 在模型、合法测试集和类别 taxonomy 确定前，不开始生产代码实现。

---

## 8. 阶段 E：后续能力

### E1. 批量分析 F-010

- 使用系统多选文件器，不申请广泛相册权限。
- 有界队列；默认并发 1，避免多个原生 Session 争抢内存。
- 展示总数、完成、失败、取消和当前项；单项失败不终止整个批次。
- 批次取消必须终止当前推理并清空未开始项。
- 输出逐项 JSON 和批次汇总；不得默认保存原图副本。
- 在定义最大图片数、总字节数、失败恢复和后台策略前不要实现。

### E2. 可下载模型管理

- 需要模型 manifest schema、HTTPS 下载、SHA-256、正式版签名验证、临时文件、原子切换和回滚。
- 只存应用私有目录；旧模型在无活动 Session 引用后删除。
- UI 显示大小、版本、许可证、来源、下载进度和删除操作。
- 下载能力会引入 `INTERNET` 权限，必须与纯离线发布变体/产品决策一起评审。

### E3. 云端 Provider F-011

默认不实施。若未来批准：

- 必须在每次上传前明确二次确认服务方、上传内容、费用与保留策略。
- Provider 接口与 `VisionEngine` 分离，不能让本地任务意外走网络。
- 禁止在 APK 内放长期服务端密钥。
- 对响应做大小、MIME、schema、数值范围和坐标校验。
- 提供可验证的关闭开关；关闭后不得产生任何网络请求。

### E4. INT8 与 NNAPI

- 只有 FP32 完成独立质量和性能验收后才开始。
- INT8 必须做逐样例漂移和质量指标对照，不能只比较 APK 大小或单次耗时。
- NNAPI 按设备记录回退、数值偏差和 P50/P95；不一致时自动回 CPU。
- 不替换已验证 FP32，除非收益和允许的质量下降均有书面结论。

## 9. 建议代码组织

在不立即拆成多 Gradle 模块的前提下，按功能包整理：

```text
com.localvision.app
├─ modelcatalog/       ModelDescriptor、manifest、registry、校验
├─ analysis/           MainViewModel、状态、参数快照、任务编排
├─ vision/common/      坐标、解码、取消门、运行时公共边界
├─ vision/face/        YuNet 前后处理与 ONNX 引擎
├─ vision/pose/        姿态前后处理、骨架与引擎
├─ vision/storefront/  门面/分类/OCR pipeline
├─ export/             JSON schema、图片导出、分享
├─ history/            隐私安全的元数据历史
└─ ui/                 screen、panel、overlay、theme
```

阶段 A 不要求一次性完成整个目录迁移。修改到的类可以顺带移动，但要避免纯移动与行为变更混在同一提交中。

## 10. 每个阶段的完成定义

一个阶段只有同时满足以下条件才算完成：

- Debug APK 可构建，现有功能无回归。
- 新增逻辑有相应单元测试；关键用户路径有集成测试或明确的人工验收记录。
- 模型相关变更同时更新模型卡、许可证和固定哈希。
- 不新增敏感权限、日志或持久化数据；如确需新增，必须单独说明并获产品确认。
- UI 覆盖空白、运行、成功空结果、成功有结果、取消和各类错误。
- README、产品规格和实现状态同步更新。
- 未完成的真机、法务或数据集工作必须写成明确待办，Agent 不得伪造“已验收”结论。

## 11. 给 MiniMax 的第一批实施指令

请只实现阶段 A，并按以下提交顺序工作：

1. A1 模型描述符、注册表单一来源和安全模型卡。
2. A2 参数快照与结果过期状态。
3. A3 JSON schema v1 及测试。
4. A4 错误恢复。
5. A5 字符串资源与结果列表完整性。

每完成一项先运行：

```text
gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

最终报告必须包含：修改文件、行为变化、测试结果、仍未完成项和任何规格偏差。不要在阶段 A 中添加网络权限、下载模型、姿态/店铺模型、批量分析或大规模 Gradle 模块拆分。
