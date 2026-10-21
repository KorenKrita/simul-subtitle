<p align="center">
  <img src="docs/images/icon.svg" width="112" alt="同传字幕">
</p>

<h1 align="center">同传字幕（Simul Subtitle）</h1>

<p align="center">
  日语、英语视频与直播的实时同传悬浮字幕（Android / macOS / Windows）<br>
  本地离线语音识别 · 自备 LLM 接口翻译（BYOK）· 浮在任意播放器与直播软件上方
</p>

<p align="center">
  <a href="README.en.md">English</a> ·
  <a href="../../releases">下载 Releases</a> ·
  <a href="docs/README.md">技术文档</a>
</p>

---

## 界面一览

<table>
  <tr><th colspan="3">Android</th></tr>
  <tr>
    <td align="center" valign="top"><img src="docs/images/screens/home.png" width="240" alt="Android · 首页"><br>首页</td>
    <td align="center" valign="top"><img src="docs/images/screens/caption.png" width="240" alt="Android · 设置 · 字幕"><br>设置 · 字幕</td>
    <td align="center" valign="top"><img src="docs/images/screens/translate.png" width="240" alt="Android · 设置 · 翻译"><br>设置 · 翻译</td>
  </tr>
  <tr><th colspan="3">桌面端（macOS / Windows）</th></tr>
  <tr>
    <td align="center" valign="top"><img src="docs/images/screens/desktop/home.png" width="260" alt="桌面端 · 首页"><br>首页</td>
    <td align="center" valign="top"><img src="docs/images/screens/desktop/caption.png" width="260" alt="桌面端 · 设置 · 字幕"><br>设置 · 字幕</td>
    <td align="center" valign="top"><img src="docs/images/screens/desktop/translate.png" width="260" alt="桌面端 · 设置 · 翻译"><br>设置 · 翻译</td>
  </tr>
</table>

## 核心特性

- **不挑播放器，全局悬浮**：B 站、YouTube、Twitch、浏览器或本地播放器都能直接配字幕。手机端直接内录目标 App 的播放音频（开启 DRM 或系统禁录的除外）；电脑端直接监听系统音频输出，无需麦克风外放。
- **边听边出，句尾定稿**：说话人开口即实时刷新草稿字幕，停顿或句末自动转为正式字幕；也可切换为「说完再出」以节省电量与 Token。
- **语音识别完全在本地完成**：App 内置 ReazonSpeech 与 Silero VAD 离线模型（约 78 MB），开箱即用、无需额外下载，**音频永远不会离开你的设备**。
- **自备 LLM 接口（BYOK）**：填入任意 OpenAI 兼容接口的地址、模型 ID 与 API Key（如 DeepSeek、通义千问、OpenRouter 等），按自身实际用量向服务商付费，可保存多套配置随时切换；首次连接时会自动探测并关闭模型的思考模式（Reasoning）。
- **支持任意目标语言**：内置中文、英语、日语、韩语，也可选择「自己填…」输入任意目标语言或方言（如繁体中文、粤语、法语）。
- **场景化背景与术语表**：自带「B站 VTuber 直播」场景预设，也支持自建场景——编写几句背景说明、维护固定译名术语表，或让 LLM 根据背景一键生成常用术语。

## 三步开始

1. **填写翻译接口**：首次打开时点击首页的「去填写」（或进入「设置 → 翻译」），填入 OpenAI 兼容服务的地址、模型 ID 与 API Key，点击「测试连接」验证。
   > 想先零门槛体验，也可以参考[翻译文档中的 B 站 Index 免费接口配置](docs/translation.md#示例使用-b-站-index-免费翻译接口)。
2. **授予权限**：
   - **Android**：按提示允许录制播放音频与显示悬浮窗。
   - **macOS**：首次开始字幕时，在系统弹窗中允许「录制系统声音」。
   - **Windows**：无需额外授权。
3. **开始字幕**：在首页选好视频原声语言（日语 / 英语），点击「开始字幕」。手机端在弹窗中选择要观看的 App，电脑端直接监听系统正在播放的声音，切回视频即可看到实时字幕。

### 悬浮窗手势与操作

手机端（点按）与桌面端（鼠标点击）操作完全一致：

| 操作 | 效果 |
|---|---|
| **按住拖动** | 移动字幕悬浮窗位置（桌面端可拖至外接显示器） |
| **点按字幕** | 展开 / 收起工具栏：调节字号、背景透明度、切换「边听边出 / 说完再出」、开关原文与耗时显示、暂停、锁定或停止 |
| **暂停** | 工具栏、App 首页（手机端还有通知栏）都能暂停和继续。暂停时说到一半的句子立即定稿，之后的声音不再识别，字幕窗口和会话记录保留；继续时不用重新加载模型 |
| **拖动四角箭头** | 展开工具栏时，拖动字幕框四角的箭头可调整宽度与高度 |
| **锁定位置** | 点击工具栏中的锁定图标后，字幕框固定不可拖动或缩放；再次点按仅在右下角浮现解锁按钮，防止看视频时误触 |

没调过高度时，字幕框随文字伸缩：放在屏幕下半部分时底边不动、向上长，正在看的最后一行不会被挤走；放在上半部分时顶边不动、向下长。字幕需要上移时（新的一行挤上来、或旧句被移走），会平滑地滑过去，不会突然跳动。

## 安装指南

请前往 [Releases](../../releases) 页面下载对应平台的安装包。

### Android

- **系统要求**：Android 10（API 29）及以上，arm64 架构。
- **安装方式**：下载 `.apk` 安装包并在手机上打开（需按系统提示允许「安装未知应用」）。
- **应用内更新**：0.4 版本起支持打开 App 时自动检查更新，可直接查看更新内容并下载覆盖安装，也可在「设置 → 关于」中手动检查或关闭自动检查。

### macOS

- **系统要求**：Apple Silicon（M1 及后续芯片），macOS 14.2 及以上。
- **安装方式**：下载 `SimulSub-<版本>-mac-arm64.dmg`，打开后将「同传字幕」拖入「应用程序（Applications）」。
- **首次打开放行**：安装包未做 Apple 公证。首次打开若提示“无法验证开发者”，请在「应用程序」中**右键点击同传字幕 → 打开**，或前往「系统设置 → 隐私与安全性」点击「仍要打开」。
- **系统录音权限**：首次点击「开始字幕」时，请允许录制系统声音（后续可在「系统设置 → 隐私与安全性 → 屏幕与系统录音」中修改）。
- **日常使用与数据目录**：
  - 关闭主窗口不会中断字幕；可通过顶部菜单栏图标随时开始、停止或重新打开主窗口。
  - 配置文件与使用记录保存在 `~/Library/Application Support/SimulSub/`，API Key 加密保存在 macOS 登录钥匙串中（删除 App 不会清除这些数据）。

### Windows

- **系统要求**：Windows 10 / 11，64 位（x64）。
- **运行方式**：下载 `SimulSub-<版本>-windows-x64.zip`，解压到任意目录后直接双击 `SimulSub.exe` 即可运行（绿色免安装）。
- **首次打开放行**：程序未附带代码签名。首次运行若弹出 Windows SmartScreen 提示“已保护你的电脑”，请点击**「更多信息」→「仍要运行」**。
- **日常使用与数据目录**：
  - 自动监听当前默认播放设备的声音，插拔耳机切换播放设备时会自动跟随。
  - 关闭主窗口不会中断字幕；可通过任务栏右下角托盘图标控制，彻底退出请右键托盘图标选择「退出」。
  - 网络请求默认跟随系统代理，代理不可达时自动回退直连。
  - 配置文件与使用记录保存在 `%APPDATA%\SimulSub\`，API Key 通过 Windows DPAPI 绑定当前系统账户加密保存在同目录的 `keys.json` 中。

## 常见问题

**Q：为什么手机上有些 App 或视频不出字幕？**
部分 App（如开启了 DRM 版权保护的流媒体视频）在系统层禁止其他应用录制其音频，这是 Android 系统的权限限制。电脑端直接捕获系统播放设备输出，不受此限制。

**Q：目前支持识别哪些原声语言？**
支持**日语**和**英语**。建议在首页准确选择视频原声语言，能让翻译提示词更贴合语境。

**Q：我的视频声音会被上传到服务器吗？**
**不会。** 语音识别完全在手机或电脑本地完成，网络请求只发送识别出来的文本（发往你配置的 LLM 接口）。详见[隐私说明](docs/privacy.md)。

**Q：应该选什么样的翻译模型？**
两个原则：**能关闭深度思考（Reasoning）**、**首字响应快**。开启思考会导致每句字幕延迟数秒甚至超时；首次连接某个模型时，App 会自动探测关闭思考所需的参数并记住。

**Q：觉得某些专有名词或上下文翻译不够准怎么办？**
可以在「设置 → 翻译」中开启「结合上文来翻译」，或在「内容类型」中新建场景，补充直播背景说明与专属术语表。你也可以在「设置 → 使用记录」中开启记录并导出 JSONL 日志用于排查。

## 技术文档

想了解内部实现、数据流或自行编译打包，可参阅 [`docs/`](docs/README.md)：

| 文档 | 内容概要 |
|---|---|
| [整体架构](docs/architecture.md) | 音频采集 → VAD → 分句 → 本地识别 → 翻译 → 悬浮窗渲染；三端模块划分与过载保护 |
| [语音识别（ASR）](docs/asr.md) | ReazonSpeech 离线模型细节，以及与 SenseVoice-Small 的准确率、延迟和内存对比 |
| [翻译（BYOK）](docs/translation.md) | OpenAI 兼容接口协议、6 种关闭思考参数自动探测、前缀缓存排布、上下文与场景术语表 |
| [Jev 智能辅助](docs/jev.md) | 可选的 TypeSafe Jev 云端结构化判断：句尾断句判断与译文错译重译、实测耗时与费用 |
| [隐私说明](docs/privacy.md) | 本地留存与网络发送的数据边界、系统权限用途、三端密钥加密方式 |
| [使用记录与调试](docs/logs.md) | JSONL 会话日志格式、Android `logcat` 延迟分析字段 |
| [构建与开发](docs/build.md) | 本地编译、模型校验、macOS 端到端测试、三端打包与版本发布流程 |

## 致谢

同传字幕基于以下优秀的开源项目构建：

- **[ReazonSpeech](https://github.com/reazon-research/ReazonSpeech)**：日语 / 英语语音识别模型（内置 [csukuangfj/reazonspeech-k2-v2-ja-en](https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en) sherpa-onnx 导出版）。
- **[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)**：跨平台离线语音识别与语音活动检测运行时（配合 [Silero VAD](https://github.com/snakers4/silero-vad)）。

此外，设置中可选开启的「智能断句」与「检查译文」功能基于 **[TypeSafe Jev](https://typesafe.ai)**（闭源商业云服务，默认关闭；开启并选用付费接口时按输入 Token 计费，每小时约 0.04 美元，详见 [Jev 说明](docs/jev.md)）。

---

开源许可：[MIT License](LICENSE)。内置模型与第三方依赖库各自遵循其原有许可证。
