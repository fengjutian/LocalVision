# LocalVision

LocalVision 是隐私优先的 Android 端侧视觉 AI 应用与可插拔推理框架。

当前版本为 `0.2.0` 人脸检测 MVP，已经接入 YuNet 2023mar FP32 与 ONNX Runtime Android CPU。应用支持系统图片选择、等比例 letterbox 预处理及坐标逆映射、真实本地人脸检测、人脸框与五点关键点叠加、分阶段性能统计、模型元数据、分析状态和错误处理。姿态与店铺任务尚未接入真实模型，不会输出模拟结果。

0.2.x 同时提供超大图采样与完整 EXIF 方向处理、可调置信度、专业缩放查看器、标注图/JSON 导出、确认后分享，以及默认关闭的纯元数据检测历史。保存和分享均由用户主动触发；历史不记录图片或人脸特征。

YuNet 权重来自固定的 OpenCV Zoo 提交，模型文件在加载前执行 SHA-256 校验。来源、许可证、输入输出契约与限制见 [模型卡](third_party/yunet/MODEL_CARD.md)。当前构建已通过 JVM 单元测试和 Debug APK 编译；正式发布前仍需完成独立图片测试集及多档 Android 真机验收。

## 构建

1. 安装 JDK 17 与 Android SDK 35。
2. 配置 `local.properties` 中的 `sdk.dir`，或设置 Android SDK 环境变量。
3. 执行 `./gradlew test assembleDebug`（Windows 使用 `gradlew.bat`）。

详细范围、验收与迭代计划见 [产品与技术规格](docs/PRODUCT_SPEC.md)。

