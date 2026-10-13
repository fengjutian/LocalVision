# LocalVision

LocalVision 是隐私优先的 Android 端侧视觉 AI 应用与可插拔推理框架。

当前版本为 `0.1.0` 工程基线，已经具备系统图片选择、任务切换、模型元数据、分析状态与错误处理。仓库暂不包含未经许可证和真机验证的模型权重，因此不会输出伪造的识别结果。

## 构建

1. 安装 JDK 17 与 Android SDK 35。
2. 配置 `local.properties` 中的 `sdk.dir`，或设置 Android SDK 环境变量。
3. 执行 `./gradlew test assembleDebug`（Windows 使用 `gradlew.bat`）。

详细范围、验收与迭代计划见 [产品与技术规格](docs/PRODUCT_SPEC.md)。

