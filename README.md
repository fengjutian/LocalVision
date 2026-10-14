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

自动化验证现状：`gradlew.bat testDebugUnitTest lintDebug assembleDebug` 通过，53 个 JVM 单元测试，lint 0 error。尚未覆盖的是仪器化集成测试、独立测试集质量报告、三档真机性能与商业/法务复核——这些仍需人工完成，README 与 UI 继续保留“待真机与独立测试集验收”的表述。

## 构建

1. 安装 JDK 17 与 Android SDK 35。
2. 配置 `local.properties` 中的 `sdk.dir`，或设置 Android SDK 环境变量。
3. 执行 `./gradlew test assembleDebug`（Windows 使用 `gradlew.bat`）。

详细范围、验收与迭代计划见 [产品与技术规格](docs/PRODUCT_SPEC.md)。

