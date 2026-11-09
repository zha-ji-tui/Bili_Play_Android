<div align="center">

# Bili Play

一款 Android 在线音乐客户端。<br>
把哔哩哔哩的视频变成歌曲，在手机上听全网资源。

</div>

> 本项目是 [soda-rx/Bili_Play](https://github.com/soda-rx/Bili_Play)（Windows / WinUI 3 版本）的 Kotlin + Jetpack Compose 安卓移植版。

## 功能进度

- [x] M0 工程脚手架、主题、关于与鸣谢页
- [x] M1 扫码登录、WBI 签名、网络层（真机扫码验证待进行）
- [x] M2 首页推荐、搜索（历史记录可复用/删除）
- [x] M3 播放内核、队列与循环模式、后台播放、迷你/全屏播放器、离线下载与管理（歌词、倍速待补）
- [ ] M4 评论、通知中心、私信、个人空间
- [ ] M5 下载与转码、多账号隔离、细节打磨

## 系统要求

| 项目 | 要求 |
| --- | --- |
| Android | 10（API 29）及以上 |
| 架构 | arm64-v8a |
| 适配设备 | 华为 Mate 40 Pro / HarmonyOS |

## 构建

需要 JDK 17 与 Android SDK 36。

```bash
./gradlew :app:assembleDebug
```

调试 APK 输出于 `app/build/outputs/apk/debug/app-debug.apk`。

## 鸣谢

本项目的产品设计、功能定义与实现思路来自 **Bili Play**（作者 **soda-rx**）。

- 原项目地址：https://github.com/soda-rx/Bili_Play
- 原项目以 MIT 许可证开源，Copyright (c) 2026 soda-rx
- 完整版权声明见 [NOTICE](NOTICE) 与 [LICENSE](LICENSE)
- 感谢原作者打造了这样一款出色的应用

应用内「我的 → 关于与鸣谢」同样展示上述信息。

## 免责声明

**Bili Play 是个人开发的非官方项目，与哔哩哔哩不存在隶属关系。**

视频、音频、字幕、封面和评论的权利归各自权利人所有。请在获得授权并符合平台规则的前提下使用相关内容。
