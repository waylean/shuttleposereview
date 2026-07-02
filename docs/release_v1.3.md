# ShuttlePoseReview v1.3

1.3 版本开始，PC 端与 Android 端同步更新，HarmonyOS 端提供测试包，后续 release 将尽量保持多端版本一致。

## 更新内容

- 新增长视频骨架模式：支持导入更长视频并生成全程骨架标注视频，处理耗时较长，请按需选择。
- 保留短视频复盘模式：60 秒内视频继续支持重发力识别、慢动作复盘、评分和证据卡。
- 长视频结果页提醒：长视频模式不支持评分、重发力识别等内容。
- Web 端长视频结果页不再重复展示右侧标注视频和输出文件区域。
- Android 端首页新增短视频复盘 / 长视频骨架模式选择。
- HarmonyOS 端提供测试版 HAP 包，可在鸿蒙设备上安装体验基础复盘流程。
- iOS 端开放 TestFlight 测试，但当前仍是 1.0 测试版，暂不包含慢动作、60 秒复盘和长视频骨架模式。

## 使用建议

- 想看重发力、评分、证据卡和慢动作复盘，请使用短视频复盘模式。
- 想复盘整段训练或对抗录像中的身体姿态轨迹，请使用长视频骨架模式。
- iOS 测试版当前适合提前体验基础流程，完整 1.3 能力以后续 iOS 更新为准。

## iOS TestFlight

```text
https://testflight.apple.com/join/NSfacCqq
```

## Release 附件

建议上传以下文件作为 GitHub Release 附件：

```text
shuttleposereview1.3.apk
ShuttlePoseReview-HarmonyOS-signed-hap-20260702-165239.zip
```

本地构建路径：

```text
apps/android/app/build/outputs/apk/debug/shuttleposereview1.3.apk
C:/Users/41039/Documents/Codex/2026-07-01/qing/work/shuttleposereview-harmony/outputs/release/ShuttlePoseReview-HarmonyOS-signed-hap-20260702-165239.zip
```

Android APK SHA256:

```text
8E5B3548B148EA8EE71A10724DBC7EA1D7D0F8B9286901E8070B0327A4EED490
```

HarmonyOS ZIP SHA256:

```text
FE364712505A5733C54B4C39F14F517B2FCDAB6F7D17590C798B99267E8CECBB
```
