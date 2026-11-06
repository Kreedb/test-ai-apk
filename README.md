# 极简 APK 模板

一个类似 KernelSU 风格的极简 Android 应用模板，使用 Kotlin + Jetpack Compose 编写，并通过 GitHub Actions 自动构建 APK。

## 功能

底栏包含 4 个选项卡：

| 选项卡 | 内容 |
| --- | --- |
| 状态 | 显示运行状态、应用版本、设备型号、系统版本与 ABI |
| 相机 | 基于 CameraX 的后置摄像头实时预览，内置 YOLO26-pose 离线姿态测量与性能指标 |
| 插件 | 插件列表占位示例，可开关 |
| 设置 | 主题设置与关于 |

## 项目结构

```
app/src/main/java/com/example/minimalapk/
├── MainActivity.kt
└── ui/
    ├── MinimalApkApp.kt          # 底栏与页面切换
    ├── theme/
    │   ├── Theme.kt              # Material 3 主题
    │   └── ThemeMode.kt          # 主题模式持久化
    └── screens/
        ├── Common.kt             # 共用组件
        ├── StatusScreen.kt
        ├── CameraScreen.kt
        ├── PluginsScreen.kt
        └── ConfigScreen.kt
```

## 姿态测量（YOLO26-pose）

相机页集成了 [Ultralytics YOLO26n-pose](https://docs.ultralytics.com) 的模型，模型文件位于 `app/src/main/assets/models/yolo26n_pose.ncnn.param` 与 `.bin`，通过 [ncnn](https://github.com/Tencent/ncnn) 在设备端完成推理，运行过程不需要网络。

- ncnn 使用官方 `20261020` Android Vulkan 预编译静态库（`ncnn-android-vulkan.zip`，校验 SHA-256），构建时下载一次并静态链接进单个 `libncnnpose.so`，APK 不会额外携带 `libncnn.so`、`libomp.so` 或 `libc++_shared.so`。
- 模型输入为 `1x3x640x640`，输出为 `1x8400x56` 的原始姿态头（框、置信度与 17 个 COCO 关键点）；应用内按置信度选出前 300 个候选并补上类别 0，再按 `0.35` 阈值过滤。检测结果与原有 `1x300x57` 布局一致。
- 模型权重由原始 ONNX 经 pnnx `20261020` 转换，不使用外部提供的 ncnn 权重。
- 推理优先使用 Vulkan GPU；设备没有可用的 Vulkan 时自动回退到 CPU。性能面板中的“加速器”会显示 `ncnn (Vulkan)` 或 `ncnn (CPU)`。
- 输入身高用于把像素换算成厘米：`cm/px = 身高 / 人体检测框高度`，再据此估算臂展、双眼间距、肩线/髋线倾斜与肩髋横向偏移。
- 使用条件会影响精度：需要全身入镜、正对镜头、双臂平举，且人物应尽量站直。单目 2D 关键点无法给出临床意义上的脊柱侧弯角度，界面结果仅供筛查参考，不能用于医学诊断。
- 预览右上角显示性能指标：分析帧率、预处理/推理/总耗时，以及 CPU、GPU、NPU 利用率。Android 不向普通应用开放系统级占用：CPU 显示本进程占设备总 CPU 算力的比例（0–100%），GPU/NPU 会尝试读取常见厂商的 sysfs 节点，设备不暴露时显示 `N/A`。
- 模型来自 Ultralytics，许可为 AGPL-3.0；许可全文与模型来源见 `app/src/main/assets/models/NOTICE.txt` 和 `app/src/main/assets/models/LICENSE-AGPL-3.0.txt`。

## 自动构建

推送到 `main`、发起 Pull Request 或在 Actions 页面手动触发 `Build APK` 工作流，即可得到构建产物（工作流会同时运行 `lintDebug` 和单元测试）。APK 按 ABI 拆分为 `arm64-v8a` 与 `x86_64` 两个包，ncnn 只以对应 ABI 的 `libncnnpose.so` 形式打包：

- `minimal-apk-debug`：`app-arm64-v8a-debug.apk`、`app-x86_64-debug.apk`
- `minimal-apk-release`：经过 R8 代码压缩与资源裁剪的 `app-arm64-v8a-release.apk`、`app-x86_64-release.apk`
- `minimal-apk-release-mapping`：release 的 `mapping.txt`，用于还原混淆后的崩溃堆栈

## 本地构建

```bash
./gradlew assembleDebug assembleRelease
```

产物位于：

- `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`、`app/build/outputs/apk/debug/app-x86_64-debug.apk`
- `app/build/outputs/apk/release/app-arm64-v8a-release.apk`、`app/build/outputs/apk/release/app-x86_64-release.apk`

默认只构建 `arm64-v8a` 与 `x86_64`；如需支持 32 位设备，在 `app/build.gradle.kts` 的 `splits.abi.include` 中补充对应 ABI。

需要 JDK 21、Android NDK `30.0.16248370` 与 Android SDK（`compileSdk = 37`）。首次构建会从 GitHub Releases 下载约 40MB 的 ncnn 预编译包并解压到 `app/build/ncnn`，之后直接复用。

## Release 签名

Release 包默认使用本地 debug 签名，可直接安装测试；但该签名仅适合本地验证，不同机器或不同 CI 运行生成的签名不一致，无法覆盖安装升级。

如需正式签名，设置以下环境变量后再构建：

| 变量 | 说明 |
| --- | --- |
| `RELEASE_STORE_FILE` | keystore 文件路径（相对路径以仓库根目录为基准） |
| `RELEASE_STORE_PASSWORD` | keystore 密码 |
| `RELEASE_KEY_ALIAS` | 密钥别名 |
| `RELEASE_KEY_PASSWORD` | 密钥密码 |

在 CI 中，可在仓库 Secrets 配置 `RELEASE_KEYSTORE_BASE64`（keystore 的 Base64 编码）以及上述三个密码/别名变量；推送 `main` 或手动触发时工作流会自动解码并启用正式签名，Pull Request 构建不读取签名密钥、固定使用 debug 签名；未配置时回退到 debug 签名。四个变量必须全部配置或全部留空，只配置一部分会让构建直接失败，避免误发布使用 debug 签名的 release 包。

## 自定义

- 修改包名：更新 `app/build.gradle.kts` 中的 `namespace` 与 `applicationId`，并同步移动源码目录。
- 修改应用名：编辑 `app/src/main/res/values/strings.xml`。
- 修改底栏：编辑 `ui/MinimalApkApp.kt` 中的 `tabs` 列表与 `when` 分支。
- 替换占位逻辑：各页面位于 `ui/screens/` 下，按需替换即可。
