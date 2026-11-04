# 极简 APK 模板

一个类似 KernelSU 风格的极简 Android 应用模板，使用 Kotlin + Jetpack Compose 编写，并通过 GitHub Actions 自动构建 APK。

## 功能

底栏包含 4 个选项卡：

| 选项卡 | 内容 |
| --- | --- |
| 状态 | 显示运行状态、应用版本、设备型号、系统版本与 ABI |
| 相机 | 基于 CameraX 的后置摄像头实时预览，含权限申请 |
| 插件 | 插件列表占位示例，可开关 |
| 配置 | 自动更新、详细日志、服务端地址等设置占位 |

## 项目结构

```
app/src/main/java/com/example/minimalapk/
├── MainActivity.kt
└── ui/
    ├── MinimalApkApp.kt          # 底栏与页面切换
    ├── theme/Theme.kt            # Material 3 主题
    └── screens/
        ├── Common.kt             # 共用组件
        ├── StatusScreen.kt
        ├── CameraScreen.kt
        ├── PluginsScreen.kt
        └── ConfigScreen.kt
```

## 自动构建

推送到 `main`、发起 Pull Request 或在 Actions 页面手动触发 `Build APK` 工作流，即可得到 `minimal-apk-debug` 构建产物（`app-debug.apk`）。

## 本地构建

```bash
./gradlew assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

需要 JDK 17 与 Android SDK（`compileSdk = 35`）。

## 自定义

- 修改包名：更新 `app/build.gradle.kts` 中的 `namespace` 与 `applicationId`，并同步移动源码目录。
- 修改应用名：编辑 `app/src/main/res/values/strings.xml`。
- 修改底栏：编辑 `ui/MinimalApkApp.kt` 中的 `tabs` 列表与 `when` 分支。
- 替换占位逻辑：各页面位于 `ui/screens/` 下，按需替换即可。
