# 整体架构

## 端到端数据流

三端（Android、macOS、Windows）共用同一套分句、识别与翻译流水线，只有最前端的**系统声音采集**与最末端的**悬浮窗渲染**按平台分别实现：

```text
系统 / 应用播放的声音（16 kHz 单声道 PCM）
  ├─ Android : AudioPlaybackCapture（录制其他 App 的媒体/游戏音频）
  ├─ macOS   : sysaudio（Core Audio Process Tap 捕获整机音频）
  └─ Windows : sysaudio.exe（WASAPI Loopback 捕获默认播放设备音频）
        │
        ▼
  Silero VAD（每 32 ms 一帧，输出“正在说话”的概率）
        │
        ▼
  Segmenter（将连续语音切分为句子）
    • 边听边出：说话期间每 0.7 s 输出一次草稿（当前句累积音频）
    • 停顿定稿：静音满 0.4 s 定稿；已说满 3 s 的长句，静音满 0.19 s 就定稿（未开 Jev 时）；单句最长 7 s，超长时在该句最长停顿处切分
    • 短句等待：不足 0.4 s 的短语音先等最多 1.5 s，若继续开口则合并为同一句
    • Jev 断句（可选）：满 5 s 或停顿 0.22 s 时先问 Jev 是否说完；未说完则继续蓄音，最长 10 s 强制切分
        │
        ▼
  ASR 工作线程：ReazonSpeech zipformer（sherpa-onnx，int8 离线模型）
        │
        ▼
  MT 工作线程：用户自备的 OpenAI 兼容翻译接口（BYOK，流式请求）
    • 可选开启 Jev 译文检查：发现明显误译时附带修正提示重译一次
        │
        ▼
  悬浮字幕窗：灰色 = 草稿（随说话实时刷新），白色 = 定稿
```

---

## 模块与代码结构

仓库分为三个 Gradle 模块：跨平台核心 `:core`、Android 客户端 `:app`、桌面客户端 `:desktop`（macOS 与 Windows 共用）。

### 1. 跨平台核心（`core/src/main/java/io/github/korenkrita/simulsub/`）

不依赖 Android SDK，由手机端与桌面端直接共用：

| 文件 | 职责 |
|---|---|
| `Segmenter.kt` | 语音分句状态机：周期草稿、停顿定稿、超长切分、短句合并等待、Jev 断句协同 |
| `Pipeline.kt` | ASR 与 MT 双工作线程调度、过时草稿折叠、长句开头吞字补齐、滑动上文窗口、相同文本译文复用、过载保护 |
| `Translation.kt` | 翻译提示词构建（面向前缀缓存排布）、场景与术语表管理、支持语言列表、BYOK 流式客户端与关闭思考参数自动探测 |
| `Jev.kt` | TypeSafe Jev 客户端及「句尾是否说完」「译文是否译错」两个结构化判断定义 |
| `SessionLog.kt` | 会话使用记录（JSONL）后台异步写入，采用 `.part` 临时文件并在正常结束时原子转正 |
| `SessionLog.kt` 里的 `Transcript` | 字幕文本：每句定稿的原文和译文写成纯文本（`transcripts/`），独立开关；不改名，写入中也能查看、分享 |
| `ServiceStatus.kt` | 运行时状态与错误提示定义 |
| `SetupOrder.kt` | 首次启动与配置引导顺序判定 |

### 2. Android 客户端（`app/src/main/java/io/github/korenkrita/simulsub/`）

| 文件 | 职责 |
|---|---|
| `CaptionService.kt` | 前台服务：采集 `AudioPlaybackCapture` 音频、从 APK `assets` 加载模型、驱动 `Pipeline`、管理悬浮窗生命周期 |
| `Engines.kt` | `sherpa-onnx` 离线识别与 Silero VAD 的 Android 封装（模型在 APK 内不压缩存放，直接读取） |
| `CaptionOverlayView.kt` | 悬浮字幕视图、四角拖拽缩放与工具栏交互 |
| `MainActivity.kt` | 首页与设置页界面 |
| `Prefs.kt` | 设置持久化与 Android Keystore 密钥加密 |
| `AppUpdater.kt` | GitHub Release 自动检查更新、多代理源回退下载、SHA-256 与同签名证书校验 |

### 3. 桌面客户端（`desktop/` 与 `tools/`）

基于 Compose Desktop，macOS 与 Windows 共用同一套 Kotlin 代码：

| 文件 | 职责 |
|---|---|
| `tools/mac-capture/SysAudio.swift` | macOS 原生采集程序 `sysaudio`：通过 Core Audio Process Tap 捕获整机音频，向标准输出写入 16 kHz 单声道 `float32` |
| `tools/win-capture/sysaudio.cpp` | Windows 原生采集程序 `sysaudio.exe`：通过 WASAPI Loopback 捕获默认播放设备音频并自动跟随设备切换，输出格式同上 |
| `Session.kt` | 对应 Android 的 `CaptionService`：管理 `sysaudio` 子进程、从应用资源目录 `models/` 加载识别模型、驱动流水线并支持运行中热更新设置 |
| `Engines.kt` | `sherpa-onnx` 的桌面端 JNI 封装 |
| `Overlay.kt` | 透明、置顶、可跨屏拖动与缩放的悬浮字幕窗口及工具栏 |
| `App.kt` / `Ui.kt` | 主窗口（首页与设置）、菜单栏 / 系统托盘图标、版本更新检查 |
| `Prefs.kt` | 桌面端设置持久化；API Key 在 macOS 存入登录钥匙串，在 Windows 经 DPAPI 加密存入 `keys.json` |
| `Main.kt` | 平台差异适配（`isWindows`）、系统代理与直连回退路由、Windows Skiko 软件渲染与系统证书库加载 |

---

## 草稿与定稿策略

- **过时草稿自动折叠**：识别队列中若积压了多份草稿，旧草稿会被直接合并丢弃，只识别最新一份。若在工具栏选择「说完再出」，则完全跳过草稿的识别与翻译，节省电量与 Token。
- **定稿绝不静默丢弃**：每一句定稿都会按顺序完成识别与翻译并显示。
- **长句开头吞字自动补齐**：语音较长时，离线识别模型偶发吞掉定稿开头的词。若检测到草稿已识别出的前半句在定稿中丢失，流水线会自动用草稿前半句补齐再拼接定稿后半句；接缝处若出现重复字词，可通过设置中的「去掉重复的字」开关消重（默认关闭）。
- **相同原文译文复用**：同一句话的草稿与定稿（或相邻草稿）识别出的文本与提示词完全相同时，直接复用上一次译文，不重复发起翻译请求。

---

## 过载保护

当识别或翻译速度持续跟不上语速时，流水线不会无限积压队列，而是按明确的三步策略停机排空：

1. **停止新采集**：当等待翻译的定稿累计音频时长超过 **30 秒** 时，立即停止采集新音频，并在悬浮窗提示过载。
2. **排空已收队列**：继续翻译已进入队列的定稿，最长等待 **60 秒**（用户手动点击停止则会立即结束）。
3. **清理与收尾**：超时或排空后，取消剩余未处理的句子与进行中的网络请求，释放模型资源，并在悬浮窗明确报告「全部翻译完」或「已放弃 N 句」；报告后不再有新字幕弹出。

---

## 延迟统计

每条字幕从采集到上屏的各阶段耗时都会记录在日志中（详见 [logs.md](logs.md)）。Android 端采集时间优先采用 `AudioRecord` 硬件时间戳；设备不支持硬件时间戳时回退为读取时间，并在耗时数字前标注 `~`。
