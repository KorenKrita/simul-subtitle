# 技术文档

面向想了解内部实现、参与开发或自行编译打包的读者。日常安装与使用说明请见[项目首页](../README.md)。

| 文档 | 内容概要 |
|---|---|
| [architecture.md](architecture.md) | 整体数据流（采集 → VAD → 分句 → 本地识别 → 翻译 → 悬浮窗）、`:core` / `:app` / `:desktop` 模块划分、草稿与定稿策略、过载保护 |
| [asr.md](asr.md) | 内置 ReazonSpeech 离线识别模型细节，以及与 SenseVoice-Small 在准确率、延迟、体积和内存上的实测对比 |
| [translation.md](translation.md) | OpenAI 兼容翻译接口（BYOK）、7 种关闭思考参数自动探测、面向前缀缓存的提示词排布、滑动上文与场景术语表 |
| [jev.md](jev.md) | 可选的 TypeSafe Jev 云端判断：句尾断句判断与译文错译重译、实测请求频率与费用估算 |
| [privacy.md](privacy.md) | 本地留存与网络发送的数据边界、Android / macOS / Windows 三端权限用途与密钥加密方式 |
| [logs.md](logs.md) | JSONL 会话使用记录格式、Android `logcat` 各阶段延迟指标 |
| [build.md](build.md) | 编译环境、内置模型校验、macOS 端到端回归测试、三端打包与正式版发布流程 |
