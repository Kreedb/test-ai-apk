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

推送到 `main`、发起 Pull Request 或在 Actions 页面手动触发 `Build APK` 工作流，即可得到两个构建产物：

- `minimal-apk-debug`：`app-debug.apk`
- `minimal-apk-release`：经过 R8 代码压缩与资源裁剪的 `app-release.apk`
- `minimal-apk-release-mapping`：release 的 `mapping.txt`，用于还原混淆后的崩溃堆栈

## 本地构建

```bash
./gradlew assembleDebug assembleRelease
```

产物位于：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

需要 JDK 17 与 Android SDK（`compileSdk = 35`）。Gradle 配置已启用构建缓存与配置缓存，重复构建会明显加快。

## Release 签名

Release 包默认使用本地 debug 签名，可直接安装测试；但该签名仅适合本地验证，不同机器或不同 CI 运行生成的签名不一致，无法覆盖安装升级。

如需正式签名，设置以下环境变量后再构建：

| 变量 | 说明 |
| --- | --- |
| `RELEASE_STORE_FILE` | keystore 文件路径（相对路径以仓库根目录为基准） |
| `RELEASE_STORE_PASSWORD` | keystore 密码 |
| `RELEASE_KEY_ALIAS` | 密钥别名 |
| `RELEASE_KEY_PASSWORD` | 密钥密码 |

在 CI 中，可在仓库 Secrets 配置 `RELEASE_KEYSTORE_BASE64`（keystore 的 Base64 编码）以及上述三个密码/别名变量，工作流会自动解码并启用正式签名；未配置时回退到 debug 签名。

## 自定义

- 修改包名：更新 `app/build.gradle.kts` 中的 `namespace` 与 `applicationId`，并同步移动源码目录。
- 修改应用名：编辑 `app/src/main/res/values/strings.xml`。
- 修改底栏：编辑 `ui/MinimalApkApp.kt` 中的 `tabs` 列表与 `when` 分支。
- 替换占位逻辑：各页面位于 `ui/screens/` 下，按需替换即可。
