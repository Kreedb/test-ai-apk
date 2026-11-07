# 极简 APK 模板

一个类似 KernelSU 风格的极简 Android 应用模板，使用 Kotlin + Jetpack Compose 编写，并通过 GitHub Actions 自动构建 APK。

## 功能

底栏包含 4 个选项卡：

| 选项卡 | 内容 |
| --- | --- |
| 主页 | 推理后端状态、CPU/GPU 占用与应用版本、CPU/GPU 型号、设备型号、系统版本与 ABI |
| 相机 | 基于 CameraX 的后置摄像头实时预览，内置 YOLO26-pose 离线姿态测量与性能指标 |
| 插件 | 姿态识别、身高线、臂长线叠加开关 |
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

相机页集成了 [Ultralytics YOLO26n-pose](https://docs.ultralytics.com) 的模型，同一份权重以三种格式提供：`app/src/main/assets/models/` 下的 `yolo26n_pose.ncnn.param`/`.bin`（ncnn）、`yolo26n_pose.mnn`（MNN）与 `yolo26n_pose.tflite`（LiteRT），在设备端完成推理，运行过程不需要网络。

- 应用按 `MNN (OpenCL/Vulkan)` → `LiteRT (GPU)` → `ncnn (Vulkan)` → `LiteRT (CPU)` → `MNN (CPU)` → `ncnn (CPU)` 的顺序自动选择第一个可用的后端。ncnn 的 Vulkan 后端在 Adreno 840（SM8850）上不稳定，因此默认优先 MNN 与 LiteRT；MNN 会报告会话实际使用的主后端，GPU 不可用时继续尝试下一个后端。
- MNN 使用官方 `3.6.2` Android 预编译库（CPU/OpenCL/Vulkan，校验 SHA-256）；该预编译包只提供 ARM 库，因此 `x86_64` 包会跳过 MNN，使用 LiteRT 或 ncnn。
- LiteRT 使用 `com.google.ai.edge.litert:litert` 与 `litert-gpu` `2.3.2` 的 `CompiledModel` API，GPU 后端不可用时回退 CPU。
- 模型输入为 `1x3x640x640`（TFLite 为 NHWC `1x640x640x3`），输出为 `1x8400x56` 的原始姿态头（框、置信度与 17 个 COCO 关键点）；TFLite 导出为 `1x56x8400`，应用内转置后统一按置信度选出前 300 个候选并补上类别 0，再按 `0.35` 阈值过滤。检测结果与原有 `1x300x57` 布局一致。
- 模型权重由 Ultralytics 的 ONNX 原始姿态头分别经 pnnx、MNNConvert 与 onnx2tf 转换，不使用外部提供的权重。
- 性能面板中的“加速器”会显示实际选中的后端（如 `LiteRT (GPU)`、`MNN (Vulkan)` 或 `ncnn (CPU)`）；MNN 会读取会话的主后端，在 GPU 不可用时回退 CPU。
- 输入身高用于把像素换算成厘米：`cm/px = 身高 / 人体检测框高度`，再据此估算臂展、双眼间距、肩线/髋线倾斜与肩髋横向偏移。
- 使用条件会影响精度：需要全身入镜、正对镜头、双臂平举，且人物应尽量站直。单目 2D 关键点无法给出临床意义上的脊柱侧弯角度，界面结果仅供筛查参考，不能用于医学诊断。
- 预览右上角显示性能指标：分析帧率、预处理/推理/总耗时，以及 CPU、GPU、NPU 利用率。Android 不向普通应用开放系统级占用：CPU 显示本进程占设备总 CPU 算力的比例（0–100%），GPU/NPU 会尝试读取常见厂商的 sysfs 节点，设备不暴露时显示 `N/A`。
- 模型来自 Ultralytics，许可为 AGPL-3.0；许可全文与模型来源见 `app/src/main/assets/models/NOTICE.txt` 和 `app/src/main/assets/models/LICENSE-AGPL-3.0.txt`。

## 自动构建

推送到 `main`、发起 Pull Request 或在 Actions 页面手动触发 `Build APK` 工作流，即可得到构建产物（工作流会同时运行 `lintDebug` 和单元测试）。APK 按 ABI 拆分为 `arm64-v8a` 与 `x86_64` 两个包，ncnn 静态链接进 `libncnnpose.so`，MNN 与 LiteRT 以各自的运行时库打包（MNN 仅 `arm64-v8a`）：

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

需要 JDK 21、Android NDK `30.0.16248370` 与 Android SDK（`compileSdk = 37`）。首次构建会从 GitHub Releases 下载约 40MB 的 ncnn 预编译包与约 6.5MB 的 MNN 预编译包，解压到 `app/build/ncnn` 与 `app/build/mnn`，之后直接复用。

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
