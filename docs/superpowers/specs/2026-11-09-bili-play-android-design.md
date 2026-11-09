# Bili Play Android 移植设计

日期：2026-11-09
状态：用户已确认

## 目标

- 将 [soda-rx/Bili_Play](https://github.com/soda-rx/Bili_Play)（C# / WinUI 3，v0.9.9）移植为 Kotlin Android 客户端
- 完整对齐原版功能
- 目标设备：华为 Mate 40 Pro（HarmonyOS，arm64-v8a），minSdk 29

## 已确认决策

| 项目 | 决策 |
| --- | --- |
| 功能范围 | 完整对齐原版 |
| 登录方式 | 仅扫码登录；二维码可保存到相册，用 B 站 App 相册扫码 |
| UI | 单 Activity + Jetpack Compose + Material 3 |
| 播放内核 | Media3 ExoPlayer + MediaSessionService |
| 依赖注入 | 手写 AppContainer，不引入 Hilt |
| 包名 | `io.github.zhajitui.biliplay` |
| 应用名 | Bili Play |
| 初始版本 | 0.1.0 |

## 技术方案

选择方案 A：单 Activity + Jetpack Compose + Media3/ExoPlayer。

- 播放：ExoPlayer 直接播放 DASH 音频直链；`OkHttpDataSource` 统一附加 UA / Referer；
  `ResolvingDataSource` 在 403 时切换 backupUrl 并重新解析。
- 桌面端 AudioProxy 不做等价移植：Android 播放器可自定义请求头，无需本地回环代理。
- 数据：Room 存历史 / 搜索记录 / 元数据；DataStore 存设置；Keystore 加密保存 Cookie。
- 网络：OkHttp + kotlinx.serialization；WBI 签名逐字复刻 `Core/WbiSigner.cs`。
- 图片：Coil，磁盘缓存对齐原版 256MB / 30 天策略。

## 架构分层

```
app
├── data/api        BilibiliClient、WbiSigner、扫码登录、评论/通知/空间接口
├── data/model      Video、Page、DashAudio、Subtitle、Comment、Notification 等
├── data/local      Room、DataStore、CookieStore（Keystore 加密）
├── playback        AudioResolver、PlaybackService、QueuePolicy
├── download        DownloadService、MP3 转码（后续里程碑）
├── ui              登录/首页/搜索/收藏/历史/下载/通知/空间/设置/关于/播放器
└── di              AppContainer
```

## 关键数据流

1. 扫码登录：`qrcode/generate` 展示二维码 → 轮询 `qrcode/poll` → 提取 Cookie →
   Keystore 加密落盘 → 后续请求自动携带。
2. 播放：`x/player/wbi/playurl`（fnval=4048，qn=80）→ 解析 `dash.audio[]` / `dash.flac` →
   ExoPlayer 加载 → MediaSessionService 提供后台播放与通知栏控制。
3. 歌词：`x/player/wbi/v2` 获取字幕列表 → 拉取字幕 JSON → 解析 `body[from,to,content]` →
   播放进度驱动滚动歌词。
4. 首页：音乐频道 + 指定 UP 主作品混排；分页加载，缓存 120 条。
5. 下载：解析音频流 → 下载 m4a → 后续里程碑用 FFmpeg 转 MP3 192/320kbps。

## 错误处理

- WBI 签名失败或 412：刷新 `wbi_img` key 后重试一次，仍失败提示"请求被拦截，请稍后再试"。
- 音频 403 / URL 过期：重新解析 playurl，依次尝试 backupUrl。
- 未登录：需要账号的页面引导至扫码登录，不展示错误码。
- 无字幕：歌词页显示"该视频没有可用歌词"。
- 网络异常：列表展示重试入口，播放保留当前队列。

## 适配 Mate 40 Pro

- minSdk 29 / targetSdk 36 / compileSdk 36；arm64-v8a；竖屏优先，支持 edge-to-edge。
- 不依赖 Google Play 服务。
- 播放前台服务类型 `mediaPlayback`，降低 HarmonyOS 后台清理概率。

## 测试策略

- WbiSigner 已知向量单测。
- API JSON 解析单测（离线样本，不使用真实账号数据）。
- QueuePolicy 单测。
- 真机验收：Mate 40 Pro 安装 APK，验证扫码登录、播放、后台、锁屏控制、歌词。

## 鸣谢与许可

- README 设「鸣谢」章节，注明基于 soda-rx/Bili_Play 移植。
- 根目录 `NOTICE` 保留原项目 MIT 版权声明（Copyright (c) 2026 soda-rx）。
- `LICENSE` 同时保留原作者与新项目版权。
- 应用内「关于与鸣谢」页面展示鸣谢文案、原项目链接与 MIT 许可说明。
- 应用内不出现开发术语、接口路径或内部字段名。

## 里程碑

- M0 工程脚手架：Compose 主题、底部导航、关于/鸣谢页、可编译 APK
- M1 登录与 API 基础：WBI 签名、扫码登录、Cookie 加密、网络层
- M2 内容浏览：首页推荐、搜索与联想词、收藏夹、最近播放、播放列表
- M3 播放核心：音频解析、ExoPlayer、后台播放、歌词、倍速、音质切换
- M4 社区功能：评论、通知中心、私信、个人空间
- M5 完整化：下载与 MP3 转码、多账号隔离、主题与动效打磨

## 风险

| 风险 | 应对 |
| --- | --- |
| WBI / 风控（412、-352） | 逐字复刻算法，key 定期刷新，失败重试与用户提示 |
| 音频 URL 短时有效 | 403 时重新解析并回退 backupUrl |
| HarmonyOS 后台限制 | MediaSessionService 前台服务 + 通知栏控制 |
| 页面内嵌数据解析随改版失效 | 主链路走 API，页面解析仅作为回退 |
| MP3 转码 | 后续里程碑引入 FFmpeg（LGPL），先提供原始 m4a 下载 |

## 许可核查

原项目 LICENSE 为 MIT，Copyright (c) 2026 soda-rx。移植与再分发需保留版权声明与许可文本。
