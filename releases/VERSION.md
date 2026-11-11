# 秒跳 · 版本说明

## 三个变体

| 变体 | applicationId | 版本 | 应用名 | 构建任务 | 摇一摇防护 |
|---|---|---|---|---|---|
| 普通手机版 | `com.miaotiao.adskip` | `0.2` | 秒跳 | `assemblePhoneDebug` | 隐藏 |
| 平板版 | `com.miaotiao.adskip.pad` | `0.2-pad` | 秒跳 Pad | `assemblePadDebug` | 隐藏 |
| Z50U 专用版 | `com.miaotiao.adskip.z50u` | `0.2-z50u` | 秒跳 Z50U | `assembleZ50uDebug` | 显示 |

三个包名不同，可以装在同一台设备上各自独立；但无障碍服务建议只启用一个，避免重复操作。

## 摇一摇防护（仅 Z50U 版）

- 依赖努比亚 MyOS 的「读取加速度传感器」特殊权限页
- 检测：开屏窗口内加速度采样 + 跳转浏览器/商城 + 跳转前无点击 + 24 小时内重复
- 命中：进入嫌疑名单；24 小时内第二次命中发通知
- 「去禁用」：自动导航到目标 App 的加速度权限页，由用户选择「允许 / 不允许 / 仅开屏时禁止」
- 推荐选择「仅开屏时禁止」：只拦启动时的摇一摇，不影响游戏、导航等正常使用

## 构建

```bash
# 环境：JDK 17 + Android SDK 34 + Gradle 8.6
gradle assemblePhoneDebug assemblePadDebug assembleZ50uDebug
```

输出：

```
app/build/outputs/apk/phone/debug/app-phone-debug.apk
app/build/outputs/apk/pad/debug/app-pad-debug.apk
app/build/outputs/apk/z50u/debug/app-z50u-debug.apk
```

## 安装

```bash
# 普通手机版
adb install -r miaotiao-0.2-phone.apk
adb shell pm grant com.miaotiao.adskip android.permission.WRITE_SECURE_SETTINGS
adb shell settings put secure enabled_accessibility_services \
  "com.miaotiao.adskip/com.miaotiao.adskip.service.SkipAccessibilityService"
adb shell settings put secure accessibility_enabled 1

# 平板版
adb install -r miaotiao-0.2-pad.apk
adb shell pm grant com.miaotiao.adskip.pad android.permission.WRITE_SECURE_SETTINGS
adb shell settings put secure enabled_accessibility_services \
  "com.miaotiao.adskip.pad/com.miaotiao.adskip.service.SkipAccessibilityService"

# Z50U 专用版
adb install -r miaotiao-0.2-z50u.apk
adb shell pm grant com.miaotiao.adskip.z50u android.permission.WRITE_SECURE_SETTINGS
adb shell settings put secure enabled_accessibility_services \
  "com.miaotiao.adskip.z50u/com.miaotiao.adskip.service.SkipAccessibilityService"
```

## 平板适配状态（小新 Pad Pro 2021 / TB-J716F / Android 13）

已完成：

- 独立包名安装：`com.miaotiao.adskip.pad`
- 横屏布局：内容最大宽度 720dp 居中，不拉伸
- 无障碍服务绑定成功
- 手势验证：模拟 4 秒倒计时 → 5 秒后上滑 → 第 1 集切到第 2 集 ✅
- OCR 底部区域读取正常（横屏 2560×1600）
- `WRITE_SECURE_SETTINGS` / `POST_NOTIFICATIONS` 已授权，看门狗可自动恢复

待验证 / 可能需要调整：

- 真实广告：红果在平板上需要看较多集才开始推广告，需要等自然出现
- 分屏 / 自由窗口下的布局与事件
- 平板横屏时广告「上滑」提示的位置是否与手机一致，OCR 裁剪区域可能需要按设备调整
- 如果平板端使用「选集 / 下一集」按钮而不是上滑，需要增加点击动作

## 说明

- 源码唯一，通过 product flavor 隔离；平板专属资源在 `app/src/pad/`，Z50U 专属资源在 `app/src/z50u/`
- `AccessibilityRestorer` 使用运行时 `packageName` 拼接无障碍组件名，三个变体都能自动恢复
- 三个版本共用同一套跳过/拦截/红果逻辑；摇一摇防护由 `BuildConfig.SHAKE_GUARD` 控制，手机版/平板版编译期关闭并隐藏入口
