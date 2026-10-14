# LocalVision 后续开发任务书（交付 MiniMax）

- 文档版本：2.0
- 当前代码基线：`e5d89fe`
- 编写日期：2026-10-14
- 当前产品版本：`0.2.0`
- 使用方式：将本文直接提供给 MiniMax；首次只执行“迭代 1”

## 1. 当前结论

阶段 A 的人脸检测产品闭环已经完成：模型元数据、模型卡片、参数快照、结果过期提示、JSON schema v1、错误恢复、字符串资源、结果展开以及相应 JVM 测试均已进入代码。

当前仍未开发或未闭环的能力如下。

### 1.1 MiniMax 可以立即编码的工作

| ID | 工作 | 状态 | 优先级 |
|---|---|---|---:|
| N-001 | `YuNetPreprocessor` Tensor/letterbox 单元测试 | 未开发 | P0 |
| N-002 | ONNX 引擎运行时边界可注入化及资源释放测试 | 未开发 | P0 |
| N-003 | `app/src/androidTest` 仪器化集成测试 | 目录为空 | P0 |
| N-004 | debug 支持 x86_64 模拟器，release 保持 arm64 | 未开发 | P0 |
| N-005 | CI：test + lint + assemble | 未开发 | P0 |
| N-006 | 模型文件构建期 SHA-256 校验任务 | 仅运行时校验 | P1 |
| N-007 | 依赖验证与依赖更新约束 | 未开发 | P1 |
| N-008 | 基准测试/性能数据采集框架 | 未开发 | P1 |
| N-009 | 预览与推理重复解码优化 | 未开发 | P2 |
| N-010 | UI/包结构继续模块化 | 部分完成 | P2 |

### 1.2 必须先获得外部资产或产品决定的工作

以下事项不能由 MiniMax 擅自完成：

| 能力 | 缺少的前置输入 |
|---|---|
| 0.2.0 独立质量验收 | 100–300 张合法授权并完成标注的独立图片集 |
| 三档真机性能验收 | 低、中、高三档 ARM64 真实设备及测试记录 |
| 商业发布 | YuNet、模型权重和 ONNX Runtime 商业使用/再分发法务结论 |
| 0.3.0 单人姿态 | 已批准的模型、权重、固定 commit、许可证、SHA-256、输入输出契约 |
| 0.4.0 店铺分析 | 门面检测、分类、OCR 三个能力的模型与类别 taxonomy |
| 模型下载 | 是否接受 `INTERNET` 权限、下载服务、签名和版本发布策略 |
| 云端 Provider | 服务方、费用、密钥代理、数据保留和用户同意方案 |

没有这些输入时，只能补充接口或测试基础设施，不得下载任意模型、生成模拟结果或宣称验收完成。

## 2. 实施规则

1. 遵守根目录 `AGENTS.md`、`docs/PRODUCT_SPEC.md` 和 `docs/IMPLEMENTATION_BACKLOG.md`。
2. 按迭代逐项实现；一个迭代通过后才进入下一迭代。
3. 保持默认离线，不新增 `INTERNET` 或广泛媒体读取权限。
4. 不修改现有模型数学契约，除非新增回归测试并解释偏差。
5. 不以 x86_64 模拟器测试替代 ARM64 真机验收。
6. 不把缺少测试资产、设备或法务结论写成“已通过”。
7. 每个提交只解决一个任务 ID 或一组强相关任务。

---

## 3. 迭代 1：补齐核心自动化自证

本轮只实现 N-001、N-002、N-004。不要同时引入姿态、店铺、网络或批量分析。

### N-001：YuNetPreprocessor 单元测试

#### 目标

锁定最容易发生静默错误的输入契约：`640×640`、NCHW、BGR、FP32、`[0,255]`、黑色居中 letterbox。

#### 推荐改造

Android `Bitmap` 在普通 JVM 测试中的行为有限。将纯数学和像素排列逻辑从 Android 绘图操作中拆开，但不要改变公开行为：

```kotlin
internal data class LetterboxGeometry(
    val scaledWidth: Int,
    val scaledHeight: Int,
    val padX: Int,
    val padY: Int,
)

internal fun calculateLetterbox(width: Int, height: Int, inputSize: Int): LetterboxGeometry

internal fun argbToNchwBgr(pixels: IntArray, width: Int, height: Int): FloatArray
```

`YuNetPreprocessor.prepare(Bitmap)` 继续负责创建/缩放 Bitmap，但调用以上纯函数。

#### 必测用例

1. 单像素 ARGB `0xFF112233` 转换后：B=`0x33`、G=`0x22`、R=`0x11`。
2. 两个以上像素验证平面布局，而非交错 BGR。
3. 正方形输入：无 padding。
4. 横图：垂直居中 padding。
5. 竖图：水平居中 padding。
6. 极端宽高比仍保证缩放尺寸至少为 1。
7. `LetterboxTransform.originalX/originalY` 在内容区正确逆变换。
8. padding、贴边和越界值的现有钳位语义被明确测试，不在本任务擅自改变。
9. 输入宽高为零时抛出明确异常。

#### 资源释放

`letterboxed.getPixels()` 或后续转换抛异常时，中间 Bitmap 也必须回收。用 `try/finally` 包围临时 Bitmap 生命周期；不要回收调用方传入的 source Bitmap。

#### 完成条件

- 新增 `YuNetPreprocessorTest`。
- 测试不依赖设备和真实 ONNX 模型。
- 现有人脸结果行为不变。

### N-002：ONNX 引擎边界与资源释放测试

#### 问题

`OnnxFaceDetectionEngine` 直接创建 ONNX Runtime 对象，难以在 JVM 测试中验证正常、异常、取消和 close 竞态路径。

#### 设计

引入最小内部接口，不把 ONNX 类型带到领域层：

```kotlin
internal interface FaceRuntimeSession : AutoCloseable {
    fun run(input: FloatArray, shape: LongArray, control: RunControl): FaceRuntimeOutput
}

internal interface RunControl : AutoCloseable {
    fun terminate()
}

internal interface FaceRuntimeSessionFactory {
    fun create(modelBytes: ByteArray): FaceRuntimeSession
}
```

具体名称可调整，但必须满足：

- ONNX 类型只存在于 ONNX adapter 实现内部。
- 生产实现仍复用一个 Session。
- 每次 run 使用独立控制对象。
- fake 能记录创建、执行、terminate 和 close 次数。
- 不为了测试把 Session 暴露给 ViewModel/UI。

#### 必测用例

1. 正常执行关闭输入 Tensor、Result、RunOptions。
2. runtime 抛异常时仍关闭本次控制资源。
3. 协程取消时 `terminate()` 到达当前运行。
4. 尚未进入 run 时取消不会误终止下一次运行。
5. 同一 Session 最大并发为 1。
6. `close()` 在空闲状态只关闭一次。
7. `close()` 与执行并发时等待当前执行退出，不提前关闭 Session。
8. close 请求后拒绝新执行。
9. 模型哈希错误不会创建 Session。
10. 输出 shape 错误释放资源并映射为 `InvalidOutput`。

#### 注意

保持现有 `InferenceRunGate` 语义。若新增 adapter 会重复其职责，应组合而不是另建第二套互斥状态机。

### N-004：debug x86_64 ABI

#### 目标

允许标准 x86_64 Android 模拟器安装 debug APK 和运行 `androidTest`，同时避免无意扩大 release APK ABI。

#### 实现要求

- release 继续只包含 `arm64-v8a`。
- debug 包含 `arm64-v8a` 与 `x86_64`。
- 不复制两套相互覆盖的 `abiFilters` 配置；使用 AGP 支持的 variant/buildType 配置。
- 运行 Gradle task 验证 debug/release 产物的 native ABI；若当前环境不能构建 release，至少检查 merged native libs 报告并说明限制。

#### 验收

- x86_64 模拟器可以安装 debug APK。
- ARM64 真机仍可以安装 debug/release APK。
- ABI 修改不被描述成真机性能验收完成。

---

## 4. 迭代 2：仪器化集成测试

本轮实现 N-003。测试必须可重复，不能要求人工操作系统文件选择器。

### 4.1 测试可注入边界

不要在测试中自动化真实系统 DocumentsUI。将“选择图片后的 URI 处理”抽成可直接调用的 ViewModel/UI 入口，并用测试 `ContentProvider`、资源 URI 或复制到测试 cache 的 fixture 提供输入。

需要准备可合法提交的小型 fixtures：

- 一张固定方向的简单图片；
- EXIF 旋转图片；
- 无人脸图片；
- 可选的极小人脸基准图片；
- 损坏图片字节；
- 损坏模型必须通过依赖注入或测试 asset 实现，禁止修改生产模型。

不得提交包含真实私人照片或来源不明的人脸图。人脸 fixture 应使用自制合成图、明确许可素材，或测试时仅验证 fake engine 的 UI 流程。

### 4.2 必测路径

1. 未选图时分析按钮不可用。
2. 注入图片 URI 后显示预览并可开始分析。
3. fake engine 返回有人脸结果，UI 显示数量、详情和模型信息。
4. fake engine 返回空列表，UI 显示成功空结果而不是错误。
5. 运行时取消进入 Cancelled，不显示随后到达的旧结果。
6. 换图、换任务后旧请求结果被丢弃。
7. 切换姿态/店铺任务显示模型未安装且不产生模拟结果。
8. 图片读取失败、模型失败、内存不足显示各自恢复建议。
9. 参数变化显示结果过期提示，重新分析后清除。
10. 展开超过十条结果，不静默丢失。
11. 页面进入后台触发取消，恢复后状态可理解。
12. 200% 字体缩放下关键按钮和“查看全部”仍可访问。

### 4.3 真模型 smoke test

允许增加一条使用生产 YuNet asset 的 smoke test，但它不能替代 fake engine 的确定性测试。断言只验证：不会崩溃、返回类型正确、坐标在合法范围；不要对不同 CPU/ORT 版本使用过窄的浮点断言。

### 4.4 完成条件

- `app/src/androidTest` 不再为空。
- 测试能在 x86_64 API 目标模拟器运行。
- README 记录运行命令和推荐 API level。
- 若 CI 暂时不跑 emulator，必须保留本地命令和后续 CI 工单。

---

## 5. 迭代 3：CI 与供应链校验

本轮实现 N-005、N-006，并评估 N-007。

### N-005：CI

新增 `.github/workflows/android.yml`：

- 使用 JDK 17。
- 使用仓库 Gradle Wrapper。
- 执行 `testDebugUnitTest`、`lintDebug`、`assembleDebug`。
- 上传单测报告、lint 报告和 Debug APK。
- Gradle 缓存 key 包含 wrapper properties、版本和 `*.gradle.kts` 哈希。
- 只授予 contents read 权限。
- 不注入云端密钥，不下载未固定模型。
- 任一测试、lint error、构建或模型哈希错误使 workflow 失败。

可选地增加 emulator job，但应与普通 JVM/build job 分开；优先保证基础 job 稳定。

### N-006：构建期模型哈希校验

新增可缓存的 Gradle task，例如 `verifyBundledModels`：

- 输入为生产 assets 中的模型文件。
- 预期 SHA-256 来自单一结构化配置，不从 Markdown 正则解析。
- 哈希不一致、文件缺失或出现未登记模型时失败。
- `check` 和 release assemble 依赖该任务。
- 测试输出不得打印模型文件内容。

若 Kotlin 源码中的 `ModelRegistry` 无法安全成为 Gradle 配置来源，可建立 `app/src/main/assets/models/manifest.json`，由运行时与构建任务共同解析。引入 manifest 时必须增加 schema 测试，并避免继续保留两份元数据真源。

### N-007：依赖验证

评估并生成 Gradle dependency verification metadata。要求：

- 覆盖当前全部构建配置实际解析的 artifacts。
- 提交 metadata 与可信 key/checksum。
- 不使用宽泛的“信任全部”规则。
- 更新依赖时有明确再生成流程。

如果一次性引入会造成不可审计的大型 metadata 变更，可单独提交，不得用关闭验证来假装完成。

---

## 6. 迭代 4：性能与内存工程

本轮实现 N-008、N-009；必须先有可重复测量方法，再做优化。

### N-008：基准测试框架

- 新增 Macrobenchmark 或独立 benchmark module，测量应用启动和完整单图分析。
- 输出冷启动、首次模型加载、热推理 P50/P95、总耗时和峰值内存采样。
- 固定模型哈希、fixture、图片尺寸、设备型号、Android 版本和应用 commit。
- 连续运行 30 次，记录失败、内存趋势和线程趋势。
- benchmark 数据写到构建报告，不进入应用历史。

注意：模拟器结果只用于回归趋势，发布门槛必须使用真实 ARM64 设备。

### N-009：消除重复解码

当前预览和推理分别解码图片。优化前先用 profiler/benchmark 证明内存或耗时收益。

推荐方案：

- 增加请求级 `DecodedImage`，包含校正后的 Bitmap、原始尺寸和来源 token。
- ViewModel/analysis coordinator 持有受控生命周期，不把可变 Bitmap 塞进持久化状态。
- 预览和推理只在同一 URI、同一 generation 下共享。
- 换图、取消、ViewModel 清理时释放；正在被 Canvas 或推理读取时不得回收。
- 只缓存当前图片，不先引入通用 LRU；如确需 LRU，必须定义字节上限而不是仅按条目数。

验收：

- 同一次选择与分析只进行一次完整解码。
- 换图不会显示旧 Bitmap 或交付旧结果。
- 4096 长边输入的峰值内存有前后对比。
- 无 use-after-recycle 崩溃。

---

## 7. 暂缓功能及启动门槛

### 7.1 单人姿态 0.3.0

收到批准模型后才能开始。启动包必须包含：

- 模型文件或合法下载地址；
- 固定仓库 commit 和 SHA-256；
- 商业使用/再分发许可证；
- 输入 shape、颜色、归一化；
- 输出节点、shape、COCO 17 点顺序与 visibility 语义；
- 单人主要人物选择规则；
- 至少 200 张合法独立测试集计划。

实现时遵循 `IMPLEMENTATION_BACKLOG.md` 阶段 C，不可输出动作、意图、健康或跌倒判断。

### 7.2 店铺分析 0.4.0

必须先分别确定门面检测、类别分类、招牌 OCR。类别 taxonomy、语言范围和每个模型的缺省降级行为必须在编码前确定。不得将 OCR 文本解释为经营主体、品牌归属、营业状态或地理位置。

### 7.3 批量分析

必须先确定最大图片数、总字节数、前后台策略、失败恢复和导出格式。默认并发应为 1，不得并发创建多个大型原生 Session。

### 7.4 模型下载与云端 Provider

这两项会改变应用的零网络权限属性。只有产品明确批准网络权限、服务端和隐私方案后才能实现。

## 8. MiniMax 首次执行指令

请仅完成“迭代 1”：N-001、N-002、N-004。

执行步骤：

1. 阅读 `AGENTS.md`、`PRODUCT_SPEC.md` 和本文。
2. 先提交实现计划，列出将修改/新增的文件和测试点。
3. 实现 N-001，运行相关单测与完整 JVM 单测。
4. 实现 N-002，重点证明正常/异常/取消/close 路径资源释放。
5. 实现 N-004，检查 debug/release ABI 行为。
6. 运行：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

7. 最终报告必须包含：
   - 修改文件清单；
   - 每个任务 ID 的实现说明；
   - 新增测试及测试数量；
   - 实际运行的命令和结果；
   - 未运行的模拟器/真机验证及原因；
   - 仍存风险与下一迭代建议。

禁止在首次执行中：

- 下载或接入姿态/店铺模型；
- 添加网络权限；
- 实现云端 Provider 或模型下载；
- 修改 YuNet 阈值或数学公式来迎合测试；
- 删除既有测试、隐私提示或模型哈希校验；
- 宣称独立质量、真机性能或法务验收已经完成。

## 9. 迭代 1 完成定义

- N-001 的 Tensor、letterbox 和资源释放测试完整通过。
- ONNX adapter 具备 fake 注入边界，未向 UI/领域层泄漏 ONNX 类型。
- 正常、异常、取消和 close 竞态均有自动化测试。
- debug 支持 x86_64，release ABI 未意外扩大。
- `testDebugUnitTest`、`lintDebug`、`assembleDebug` 全部通过。
- 人脸检测、导出、历史、参数快照和隐私行为无回归。
- README/规格仅记录已实际完成的事实。
