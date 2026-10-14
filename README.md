# LocalVision

LocalVision 是隐私优先的 Android 端侧视觉 AI 应用与可插拔推理框架。

当前版本为 `0.2.0` 人脸检测 MVP，已经接入 YuNet 2023mar FP32 与 ONNX Runtime Android CPU。应用支持系统图片选择、等比例 letterbox 预处理及坐标逆映射、真实本地人脸检测、人脸框与五点关键点叠加、分阶段性能统计、模型元数据、分析状态和错误处理。姿态与店铺任务尚未接入真实模型，不会输出模拟结果。

0.2.x 同时提供超大图采样与完整 EXIF 方向处理、可调置信度、专业缩放查看器、标注图/JSON 导出、确认后分享，以及默认关闭的纯元数据检测历史。保存和分享均由用户主动触发；历史不记录图片或人脸特征。

YuNet 权重来自固定的 OpenCV Zoo 提交，模型文件在加载前执行 SHA-256 校验。来源、许可证、输入输出契约与限制见 [模型卡](third_party/yunet/MODEL_CARD.md)。当前构建已通过 JVM 单元测试和 Debug APK 编译；正式发布前仍需完成独立图片测试集及多档 Android 真机验收。

## 实现状态

0.2.x 人脸检测的产品闭环已补齐（对应 `docs/IMPLEMENTATION_BACKLOG.md` 阶段 A）：

- 模型元数据集中在 `ModelRegistry`，含 schema 版本、SHA-256、来源提交、运行时版本与许可证；推理引擎从目录读取权重路径与哈希，不再自带模型常量。
- 分析参数在点击时冻结为 `AnalysisConfig` 快照；改动阈值不会清空旧结果，也不会把旧结果重绘成新参数，而是标记为过期并提示重新分析。
- 导出 JSON 使用 v1 契约：包含模型、参数、分阶段耗时与 UTC 时间戳，不含图片 URI、路径、GPS 或设备标识。
- 模型缺失、模型错误、图片错误、内存不足与未知错误分别给出可理解的下一步；未安装真实模型时禁用分析并说明原因，不生成模拟结果。
- 用户可见文案集中在 `res/values/strings.xml`；结果列表默认折叠但可展开全部，不再静默截断。

自动化验证现状：`gradlew.bat testDebugUnitTest lintDebug assembleDebug verifyBundledModels` 通过，98 个 JVM 单元测试，lint 0 error。`verifyBundledModels` 会在构建期比对 `assets/models/manifest.json` 登记的 SHA-256，模型被换掉或出现未登记权重时直接让构建失败。`app/src/androidTest` 已包含 17 条仪器化集成测试，覆盖选择图片、取消、换图换任务丢弃旧结果、未安装模型、错误恢复、结果过期、列表展开、进入后台取消和 200% 字体缩放。

仪器化测试的源码随仓库编译通过（`gradlew.bat assembleDebugAndroidTest`），但**尚未在设备或模拟器上实际执行过**。`.github/workflows/android.yml` 已配置 emulator job 来补上这一环，在第一次 CI 跑绿之前，这里不把它写成"已通过"。本地手动跑：

```powershell
.\gradlew.bat connectedDebugAndroidTest          # 需要已启动的模拟器或已连接设备
```

- 推荐镜像：Android 14 / API 34 或 API 35 的 `x86_64` 系统镜像（debug APK 含 `arm64-v8a` 与 `x86_64`，release 仍只含 `arm64-v8a`）。
- 测试图片由 `ImageFixtures` 在测试运行时用 `Canvas` 合成到应用缓存目录并用完即删，仓库不提交任何真实照片；EXIF 旋转 fixture 通过 `ExifInterface` 写入 Orientation 标签生成。
- 人脸结果相关的 UI 断言由 `ScriptedVisionEngine` fake 提供；只有 `YuNetSmokeTest` 会加载真实权重，且只断言"不崩溃、类型正确、坐标在 0..1"，**不做任何精度或浮点断言**。

## CI

`.github/workflows/android.yml` 在 push / PR 到 `main` 时运行两个 job：

- **build**（PR 的强制门禁）：JDK 17 + 仓库 Gradle Wrapper，执行 `testDebugUnitTest`、`lintDebug`、`assembleDebug`、`verifyBundledModels`，上传单测报告、lint 报告、模型校验报告和 Debug APK。任一失败即阻断。
- **emulator**：`needs: build`，只在 main 推送与手动触发时运行（不在 PR 上跑，避免模拟器抖动误伤 PR），执行真实的 `connectedDebugAndroidTest`。

CI 不注入任何 secret、不下载模型（权重随仓库提交并由 `verifyBundledModels` 校验），权限只有 `contents: read`。Gradle 发行版由 `gradle-wrapper.properties` 的 `distributionSha256Sum` 固定摘要。

尚未覆盖的是独立测试集质量报告、三档真机性能与商业/法务复核——这些仍需人工完成，README 与 UI 继续保留"待真机与独立测试集验收"的表述。

## 构建

1. 安装 JDK 17 与 Android SDK 35。
2. 配置 `local.properties` 中的 `sdk.dir`，或设置 Android SDK 环境变量。
3. 执行 `./gradlew test assembleDebug`（Windows 使用 `gradlew.bat`）。

详细范围、验收与迭代计划见 [产品与技术规格](docs/PRODUCT_SPEC.md)。

