# 极简 APK 模板

一个类似 KernelSU 风格的极简 Android 应用模板，使用 Kotlin + Jetpack Compose 编写，并通过 GitHub Actions 自动构建 APK。

## 功能

底栏包含 4 个选项卡：

| 选项卡 | 内容 |
| --- | --- |
| 主页 | 上次加载的推理后端与应用版本、CPU/GPU 型号、设备型号、系统版本与 ABI（不启动占用监控） |
| 相机 | CameraX 后置摄像头预览，按启用的插件显示识别、测量、增强与监控结果 |
| 插件 | 独立总开关、点击卡片配置；既有场景及 LiteRT 画面分析、图像增强、英语字幕、神经朗读、录音处理、文本决策与本地文本建议 |
| 设置 | 推理后端、GitHub 镜像源、应用自检、日志、主题设置与关于 |

## 项目结构

```
app/src/main/java/com/example/minimalapk/
├── MainActivity.kt
├── examples/                     # 超分辨率、设备端训练等示例模型
├── models/                       # 通用 TFLite 配置、导入、输入转换与结果显示
├── log/
│   └── AppLog.kt                 # 应用内日志缓冲
├── pose/                         # 姿态模型、后端选择与测量
├── plugins/                      # 可观察设置、相机宿主事件/输出、模型消费者与姿态增强
└── ui/
    ├── MinimalApkApp.kt          # 底栏与页面切换
    ├── theme/
    │   ├── Theme.kt              # Material 3 主题
    │   └── ThemeMode.kt          # 主题模式持久化
    └── screens/
        ├── Common.kt             # 共用组件
        ├── StatusScreen.kt
        ├── CameraScreen.kt
        ├── PersonalizationScreen.kt
        ├── PluginsScreen.kt
        ├── ConfigScreen.kt
        ├── ModelsScreen.kt
        ├── ThemeSettingsScreen.kt
        ├── LogScreen.kt
        └── AboutScreen.kt
```

## 姿态测量（YOLO-pose）

相机页集成了 [Ultralytics](https://docs.ultralytics.com) 的 pose 模型，在设备端完成推理，推理过程不需要网络。APK 只内置主模型，其余模型在“添加”时从远程下载：

| 模型 | 获取方式 | 输出框格式 |
| --- | --- | --- |
| YOLO26n-pose 640 | 内置 `app/src/main/assets/models/yolo26n_pose.tflite` | `xyxy` |
| YOLO11n-pose 640 | 下载固定提交地址（可选 GitHub 镜像） | `cxcywh` |

模型共用同一份 `ModelSpec` 描述（输入尺寸、anchor 数、关键点数、框格式、输出布局、阈值、防抖）。YOLO11n-pose 由 `yolo11n-pose.onnx` 经 onnx2tf 转换而来，框通道是中心点+宽高，解码时换算成左上/右下；它不再放在仓库根目录的 `models/`，而是从固定提交 `e9f51fe286a7ea8863d40113e51dc5a4b9d49e88` 的 raw 地址按需下载，因此 APK 只包含主模型。固定大小 11,835,168 字节，SHA-256 为 `01b0d8c7e28369977c69d5c66a60c63301468a5a8ae24bafce942b61bab289c0`，下载后仍走同一套 TFLite 结构校验。

- 插件 → 自定义模型 中可启用/停用模型、导入本地文件、集中下载远程模型，并直接点击每个模型修改配置。YOLO26n 与 YOLO11n 共用参数编辑入口，框格式、布局、阈值、防抖均可配置；内置模型保留文件来源保护。保存后相机订阅更新并后台重载，零模型、加载期间或失败时均保留预览。
- 多个启用模型按“同帧串行”运行：一次预处理后按顺序跑每个模型，帧率约等于各模型推理耗时之和；只有第一个模型驱动叠加层，其余模型在底部只显示置信度。所有模型都关闭时仍显示相机画面（只做旋转与 letterbox，不做推理）。
- 本版本只做单人：每个模型只取置信度最高的一个检测。
- 导入模型可以走系统文件选择器（SAF，不需要存储权限），也可以走“从远程下载模型”：内置目录（`ModelCatalog`）或自定义 `https` 地址，下载上限 64MB，下载后与本地导入使用同一套校验。远程下载需要 `INTERNET` 权限，日常推理仍然离线。
- 设置 → GitHub 镜像源 可选官方直连（默认）、预置 `https://gh-proxy.org/` 或自定义 HTTPS 前缀，保存后所有模型下载立即使用。镜像只改写受支持的 GitHub raw 文件与 `releases/download` 地址；其它来源、伪装域名、带凭据/query/fragment 的地址以及已加过该前缀的地址保持原样。失败时界面显示原始地址与失败原因，并可“直连重试”；无论走镜像还是直连，大小、SHA-256 与 TFLite 结构校验都不会跳过。
- 导入时读取 TFLite 输入/输出张量形状并自动填写模型配置：
  - 接受单输入单输出、float32、NHWC 正方形输入，以及 `5+3K` 姿态头；值优先 `[1, 5+3K, N]` 与锚点优先 `[1, N, 5+3K]` 都会自动识别。非 17 个关键点的模型也会导入，但骨骼与身体测量只按 COCO 17 点索引，缺失的点不标注、不参与测量。
  - 不符合姿态管线的模型（量化、NCHW、非正方形、多输入/输出、非姿态头、动态维度等）保留在同一模型列表中，说明不适配原因并生成可编辑配置。文件格式损坏、超出 64 MB 或存储失败会阻止导入；一般模型不会被强行解释为姿态模型。
  - 自动识别的输入尺寸、anchor 数、关键点数和输出布局都会写进模型配置；输入尺寸由静态输入形状决定，anchor、关键点数、输出布局和框格式可以在模型的编辑弹窗里修正。改错导致加载失败时，相机页会提示检查这些字段或切换推理后端。框格式无法从张量形状推断，导入默认按 `xyxy` 解码，远程目录里的模型则带上已知的框格式。
  - onnx2tf 导出模型常把 `shape_signature` 的维度写成 `-1`，GPU delegate 可能拒绝这类图。姿态模型导入会静态化并报告改写数量；通用模型保留原始字节与动态签名，用 Interpreter 按配置调整输入。
  - 导入的模型复制到 `filesDir/models/<sha256>.tflite`，加载时用 `CompiledModel.create(路径, ...)`，不需要新依赖。导入只做形状校验，不做试跑：能通过校验的模型仍可能在该设备上编译失败，错误会显示在相机页。
- 应用按 `LiteRT (NPU)` → `LiteRT (GPU)` → `LiteRT (CPU)` 的顺序自动选择第一个可用的后端。LiteRT GPU 请求同时包含 CPU 加速器，未被 GPU 委托的算子回退到 CPU 执行。第二个模型复用第一个模型已探测成功的加速器，加载失败时只跳过该模型。
- LiteRT NPU 使用 `litert-npu-runtime-qualcomm` 与 Qualcomm `qnn-runtime` 的 Hexagon HTP 运行时，仅在 Android 12+、`arm64-v8a`、SoC 厂商为 Qualcomm（`Qualcomm`/`QTI`）的设备上探测。模型通过 LiteRT 在设备端 JIT 编译到 NPU，首次进入相机延迟明显，编译产物缓存在 `cacheDir`（后续通常复用）；JIT 需要 `libQnnHtpPrepare.so`。APK 打包 qnn-runtime 提供的全部 HTP/DSP 代 `Skel`/`Stub`，因此所有 Qualcomm 机型都会尝试 NPU，JIT 编译失败时回退到 GPU/CPU。LiteRT 2.3.2 会把单独的 NPU 请求扩展为 NPU+CPU，HTP 编译失败时可能静默回退 CPU：LiteRT 的 Kotlin API 只提供环境支持的加速器，不返回实际执行的子图，`QualcommOptions.Profiling` 也只写入 logcat，因此探测链无法判断 HTP 是否真正接管，会按 `LiteRT (NPU)` 接受该模型（这是一项明确的取舍，不是遗漏）。若怀疑回退到 CPU，请查看 QNN 日志确认。QNN 库需在安装时解压到 `nativeLibraryDir`，因此启用了 `useLegacyPackaging`；`arm64-v8a` 包的 QNN 运行库解压后约 211MB（其中 85MB 是 JIT 编译库），压缩进 APK 约 71MB，release APK 实测约 86MB。
- LiteRT 使用 `com.google.ai.edge.litert:litert`、`litert-gpu` 与 `litert-npu-runtime-qualcomm` `2.3.2` 的 `CompiledModel` API。GPU 请求同时包含 CPU 加速器：LiteRT 2.3.2 在只请求 GPU 时，只要有算子未被 GPU 委托就让整个模型编译失败（`Failed to compile model`）；加入 CPU 后这些算子回退 CPU，其余仍在 GPU 执行。GPU 默认使用 MLDrift 的 OpenCL 后端，委托创建失败时先用同一加速器库的 OpenGL 后端重试（该库提示 Adreno 8xx 旧驱动的 OpenCL 内核编译不稳定），仍失败才继续回退；NPU 请求不包含 GPU 兜底，但 LiteRT 会自行加入 CPU 兜底。onnx2tf 导出时会把 batch/height/width 标记成 `-1`，下载与导入的模型由 App 在设备端把签名改写成与具体形状一致；内置模型已提交为静态签名，重新导出后由 `TfliteGraphTest` 校验。
- 模型输入为 `1x3x640x640`（TFLite 为 NHWC `1x640x640x3`），输出为 `1x56x8400` 原始姿态头：通道 4 是置信度，通道 5..55 是 17 个 COCO 关键点的 x/y/confidence。解码器按配置的 `OutputLayout` 原位读取值优先或锚点优先布局，不构造中间 `300x57` 表；按模型配置的置信度阈值过滤后按置信度排序，取前 300 个候选。
- 模型权重由 Ultralytics 的 ONNX 姿态头经 onnx2tf 转换为 TFLite，不使用外部提供的权重；YOLO26n 使用 ONNX 原始姿态头 `/model.23/Transpose_output_0`，YOLO11n 使用 ONNX 的 `output0`。
- 性能面板中的“加速器”会显示实际选中的后端（如 `LiteRT (NPU)`、`LiteRT (GPU)`、`LiteRT (GPU/GL)` 或 `LiteRT (CPU)`）。`LiteRT (GPU)`/`LiteRT (GPU/GL)` 表示 GPU 成功参与委托，未被 GPU 委托的算子仍由 CPU 执行。
- 日志页会记录后端探测顺序、设备信息（SDK/ABI/SoC 厂商与型号）、启用的模型、NPU 门槛未通过的原因、NPU 环境与 JIT 编译耗时、LiteRT GPU 每次尝试的后端（`AUTOMATIC`/`OPENGL`）与环境可用加速器、各后端加载耗时/失败堆栈、导入时的签名改写数量，以及首帧每个模型的预处理/推理/后处理耗时，便于定位启动慢或回退问题。
- 输入身高用于把像素换算成厘米：`cm/px = 身高 / 人体检测框高度`，再据此估算臂展、双眼间距、肩线/髋线倾斜与肩髋横向偏移。点击“插件 → 姿态增强”卡片配置身高与叠加线；独立总开关关闭后撤回测量线、人体信息和该插件说明，基础骨架与已启用模型列表不受影响。设置保存到 `app_settings`，重启恢复。
- 插件 → 自定义模型 的配置页统一列出模型，每个模型独立开关与配置；开启总开关后运行列表中启用的模型，不再另选一个“相机使用的模型”。全部为 `IMAGE` 的输入在相机运行；显式单输入原始波形 `AUDIO` 可接共享麦克风，形状、量化、采样率与窗口须满足界面列出的协议。TEXT/NUMBERS/文件输入在模型配置中按需运行，保存并启用的模型可把结果带回相机；文件图像以有来源说明的快照报告显示，不覆盖实时帧。CPU Interpreter 推理可能降低帧率，模型失败分别报告。
- 原动作观察改为 **抬臂计次**：MoveNet 检查双肩、双腕和髋部，稳定“放下 → 双腕高于肩 → 放下”计一次，遮挡或长时间断帧中止未完成动作。**物品识别** 给出 ImageNet 前 5 类与真实标签；原场景检测改为 **物品定位与计数**，SSD 输出框、类别、当前数量和九宫格位置，同类别高重叠框去重。原场景分割改为 **区域覆盖分析**，保持原图比例输入 DeepLab，用 21 类 logits 的 argmax 输出透明背景色图、类别名称与面积占比，不把 logits 当置信度过滤。各插件按固定模型契约运行，用户的通用模型配置保留；停用、切页或后台清空结果。
- 插件 → 声音识别 可选共享麦克风或 WAV 文件。麦克风按 YAMNet 的 16 kHz 单声道、15600 采样窗口显示环境声类别；WAV 在相机中显式点击识别，无需录音权限，最多读取 16 MB、分析开头 0.975 秒。固定场景协议和标签独立于用户通用模型配置；不转写讲话内容、不保存录音。窗口有界，推理慢时丢弃旧窗口；播放、停用、切页、后台或改配置会清空缓冲并拒绝旧结果。标签随模型文件校验下载，从 TFLite 尾部附加 ZIP 中读取，避免 Android `ZipFile` 拒绝非 ZIP 文件头。
- 插件 → 语音朗读 复用系统 `TextToSpeech` 引擎，可选择设备上已安装的引擎并保存选择；配置页或相机中的显式朗读/停止按钮共用开始、完成、停止与错误回调。文本上限为系统 `getMaxSpeechInputLength()`，不需要麦克风权限；播放期间暂停共享采集，离开页面、后台、关闭弹层或停用时停止朗读。
- 插件 → 设备端学习 提供“我的物品分类”：命名 4 类 → 每类添加至少 5 张不同照片 → 训练保存 → 新照片验证与相机识别。类别名、未知阈值（默认 75%）、权重与 Adam 状态原子保存；低于阈值显示“未知”。旧 `personalization-head.bin` 保留，旧类别不自动改名，需要确认对应关系后保存。相机只读存档，不训练、不写盘；照片不上传。
- 使用条件会影响精度：需要全身入镜、正对镜头、双臂平举，且人物应尽量站直。单目 2D 关键点无法给出临床意义上的脊柱侧弯角度，界面结果仅供筛查参考，不能用于医学诊断。
- 预览请求 1280×960 原始相机图像（设备不支持时由 CameraX 回退），直接绘制原始像素，模型输入缩小不再降低预览清晰度；首帧日志记录实际分辨率。预览右上角的指标由“系统监控”提供，可隐藏帧率、耗时、后端、CPU、GPU、NPU，并配置 250–5000 ms 刷新间隔（默认 250 ms）与 1–10 s FPS 窗口；保留已保存的刷新间隔。监控文字持续到下次采样，不随图层帧号变化移入底部；关闭、切页或后台清空。**帧率**来自相机帧时间戳；**主姿态**单独报告预处理、含读回的推理链路和后处理；**同步帧处理**覆盖图像转换、姿态及同步插件分发，不含异步/显示。异步模型显示最近完成的耗时和时间，互不相加。**CPU(应用)** 为本进程占设备总算力的比例，GPU/NPU 报告厂商来源与单位。
- GPU/NPU 文件读取在独立有界 worker 执行，隐藏的使用率指标不再读取；关闭、后台和配置变化撤销旧采样。NPU 来源必须逐项按真机确认，不可读时显示 `missing` / `permission_denied` / `unsupported` / `parse_error` / `stale`；超过读取限额标 `truncated` / `timeout`，不会写成 0%。LiteRT 2.3.2 没有读取 NPU 利用率的 API，`QualcommOptions.Profiling` 只把逐算子耗时写进 logcat；选中 NPU 后端也不代表 HTP 真正接管（可能回退 CPU）。当前方案：

  | 方案 | 来源与语义 | 已知限制 |
  | --- | --- | --- |
  | 厂商负载节点 | `misc/npu/device/load`、`devices/platform/npu/load` 原文诊断 | 未核实驱动的单位/作用域时返回 `unsupported`；数值在 0–100 或带 `%` 均不足以证明是利用率 |
  | devfreq 设备发现 | 按 `npu`/`nna` 名称或设备树身份发现，读取 `load`/`npu_load` 原文与原因 | 未证实的属性保留为 raw；`busy`、`total`、`trans_stat`、频率、地址都不直接当利用率 |
  | Rockchip RKNPU | `/sys/kernel/debug/rknpu/load` 与 `/proc/rknpu/load`，支持单核 `NPU load: x%` 和多核 `CoreN: x%,` | `%2.d%%` 的零值可为空白加 `%`；必须整行合法，截断或重复核不解析；rknpu devfreq 固定 1/1 不使用 |
  | Samsung Exynos | 从 `exynos-npu` 平台驱动绑定设备及 NPU 身份节点读取 `rq_load`，原值 0–10000 = 0.01% | 显示请求队列忙碌率，不是硬件 MAC 利用率；`load` 受 `load_policy` 影响，`fps_load` 是执行时间/目标帧时间 |
  | Qualcomm 诊断 | `/sys/kernel/debug/qcom_stats/cdsp`、`cdsp1` 可读性与 Accumulated Duration | 仅 DSP 睡眠统计（硬件 ticks），不是 NPU 利用率，不硬编码时钟换算 |
  | busy/total 计数差分 | 未实现 | 未找到可证明会更新且对普通 App 导出的计数；Rockchip 固定 1/1 不能使用，因此明确返回 `unsupported` |

  开启 NPU 指标、选择“测试全部”并保存后，在相机点击“复制完整 NPU 诊断”：包含机型、SoC、Android/API、应用版本、每次读取的路径/状态/时间及保留的原文，不受相机文字摘要长度限制。每文件最多 4096 字节、每目录 64 项、每轮 256 次操作/128 KiB 原文/200 ms；超限明确标记，阻塞内核读取返回后停止后续 I/O。请在空闲与推理时分别复制，数据不会自动上传。厂商节点仍**待真机测试**，模拟器失败诊断与 fixture 不证明 NPU 可用。
- 模型来自 Ultralytics，许可为 AGPL-3.0；许可全文与模型来源见 `app/src/main/assets/models/NOTICE.txt` 与 `app/src/main/assets/models/LICENSE-AGPL-3.0.txt`。

## 插件宿主

相机底部按插件汇总有限的文字、状态与错误；姿态增强复用共享姿态推理，通用模型结果归属“自定义模型”。关闭全部插件后只有预览；监控指标保持在右上角，不随其它插件输出移动到底部。共享状态可观察，同 ID 配置保存也会重载模型。宿主提供帧/坐标、按下/释放/取消、会话/配置代次、生命周期和音频权限/PCM 事件；图层必须与显示图像同源，按压结束拒绝迟到的替换结果。

公共接口、资源所有权、替换仲裁、坐标核对与真机步骤见 [插件宿主接入](docs/plugin-host.md)。麦克风 PCM 由共享采集宿主统一提供；底栏固定为四个 tab。模型工作台界面已删除，模型直接在“插件 → 自定义模型”列表中配置，目录下载集中到“从远程下载模型”。

## 应用自检

设置 → **开始全部模型自检**：枚举内置模型、启用/停用的导入模型、场景缓存、远程目录与大模型资源文件。对本地可执行模型串行创建独立 CPU 实例、合成输入、检查输出形状和有限值；未下载、缺失运行时或超出内存预算的条目逐项记为 `SKIP`，失败记为 `FAIL`，只有真正推理并验证输出才为 `PASS`。不自动下载目录全部权重，不修改训练存档。

日志按 `SelfCheck / run=<id>` 记录设备、版本、后端、文件大小/哈希、配置摘要、签名与 I/O、阶段耗时和错误堆栈，末尾有覆盖汇总，可直接查看并分享。离开设置或进入后台会请求取消，当前原生调用返回后释放资源；自检不替代真实场景准确率及 GPU/NPU 验证。真机复测步骤见 [#63 反馈修复](docs/issue-63-device-feedback.md)。

## 示例能力（TensorFlow Lite 例程）

下列 [tensorflow/examples](https://github.com/tensorflow/examples/tree/master/lite/examples) 能力通过“插件”选项卡进入。模型首次使用时按需下载，校验固定大小与 SHA-256，下载后离线运行；APK 只内置 YOLO26n-pose。场景插件与自定义模型共用缓存，远程模型目录统一在“自定义模型 → 从远程下载模型”。

| 能力 | 来源 | 输入 → 输出 | 说明 |
| --- | --- | --- | --- |
| 姿态估计 | MoveNet Lightning，Google Coral 的量化测试模型（约 3 MB） | `[1,192,192,3]` uint8 → `[1,1,17,3]` float32 | 动作观察使用 COCO 17 点并提供入镜提示；模型来自固定提交的 Coral 源 |
| 图片分类 | `image_classification` MobileNet V1（约 4 MB） | 224×224 RGB → 1001 类 | 反量化后显示 Top-5，类别名从模型附带标签读取 |
| 物体检测 | `object_detection` SSD MobileNet V1（约 4 MB） | 300×300 RGB → 框、类别、分数、数量 | 四输出，显示检测框与标签 |
| 图片分割 | `image_segmentation` DeepLab V3（约 3 MB） | 257×257 RGB → 21 类像素分数 | 相机上显示分类区域、类别名称与覆盖比例 |
| 音频分类 | `audio_classification` YAMNet（约 4 MB） | 16 kHz、15600 个采样点 → 521 类 | 麦克风每个约 0.975 秒窗口输出真实标签与置信度 |
| 超分辨率 | `lite/examples/super_resolution` 的 `ESRGAN.tflite`（约 5 MB） | 每块 `[1,50,50,3]` → `[1,200,200,3]` float32 | 按住后处理整幅相机快照，带上下文的分块拼接，松手恢复实时预览 |
| 设备端训练 | `lite/examples/model_personalization` 的 `model.tflite`（约 18 MB） | 224×224 图片 → MobileNetV2 特征 → 4 类 | 冻结主干，只在本机训练最后的分类头；图片不会上传 |
| 语音合成 | 设备自带 TTS 引擎 | 文本 → 语音 | 见下方边界说明 |

- 场景插件与通用模型使用 `org.tensorflow.lite.Interpreter`（随 LiteRT AAR 提供，JNI 由 `libLiteRt.so` 实现），CPU 执行。模型在后台线程创建、运行和关闭，退出页面不在主线程等待推理结束。
- 设备端训练用模型的 `load` 签名提取 62720 维特征，纯 Kotlin `SoftmaxHead`（Adam，学习率 0.001）训练分类头；不使用需要 Flex 的官方训练/存档签名。每类 5–50 张不同照片，总计最多 200 张，训练 150 步。相同特征的照片不重复计数；训练候选失败或取消保留原分类器。照片与特征仅保留在当前页面，继续训练需重新选样本。v2 存档保存类别和阈值，旧版 App 无法读取显式升级后的 v2 存档。
- 语音合成没有使用 TFLite：官方示例仓库当前没有 TTS 例程；社区 FastSpeech2 的 TFLite 导出依赖 `FlexRandomUniform` 等 Flex 自定义算子（部分模型还需要音素前端），LiteRT 无法直接加载。这里调用系统 `TextToSpeech`，是否需要下载语音包取决于系统实现。
- 下载源为 TensorFlow 与 Google Coral（Apache-2.0），详情见 `ExampleCatalog` 与 `assets/models/NOTICE.txt`。场景模型缓存放在 `filesDir/examples/`，可配置模型与配置放在 `filesDir/configured_models/`；删除单个模型会删除该模型配置，场景缓存可供再次添加。

## 模型配置修正

入口：插件 → 自定义模型 → 点击需要配置的模型。

1. 导入本地 `.tflite`，或通过“从远程下载模型”选择目录/填写 HTTPS 地址。导入读取张量形状、dtype、量化参数、自定义算子与签名，不执行原生推理。
2. 点击列表中的模型配置。姿态与通用模型均提供参数表单；通用模型可编辑输入来源、形状、布局、归一化、采样率、输出显示、阈值与标签，也可展开高级 UTF-8 `.properties` 编辑、导入/导出与文件试跑。重导相同文件保留已有名称、配置和开关；字段含义必须与模型说明一致。按需运行可取消，关闭或后台撤销请求，原生调用排空后释放；列表注明输入协议和未就绪原因。
3. 打开模型开关与自定义模型总开关，纯图片输入模型即可在相机中运行；音频等专用任务使用对应插件。固定维度不可改变，动态维度需填正整数。应用自检可逐项检查可执行模型并输出诊断日志。

| 配置项 | 含义 |
| --- | --- |
| `version=1` / `signature=` | 配置版本；空签名为主子图，其它值为模型中的签名名 |
| `input.0.shape=1,192,192,3` | 第 0 个输入的运行形状；标量为空；动态维度必须明确填写 |
| `input.0.source=IMAGE` | `IMAGE` 图片，`NUMBERS` 数值，`TEXT` 字符串，`AUDIO` WAV，`BINARY` 原始张量 |
| `input.0.layout=NHWC` | 图片支持 `NHWC`/`NCHW`、非正方形及 1/3 通道；按形状拉伸，不做 letterbox |
| `input.0.mean=0` / `input.0.std=1` | 图片实数值 `(pixel−mean)/std`；uint8/int8 等按模型的 scale/zero-point 量化 |
| `input.0.values=0` | 数值用空格/逗号分隔，单个值填充整个张量；TEXT 用 `\n` 分隔每个字符串，按标准 UTF-8 字节传入，保留 Unicode/NUL；当前 Java API 要求首项非空 |
| `input.0.sampleRate=16000` | AUDIO 的目标采样率；需要 PCM16 单/双声道 WAV |
| `output.0.render=VALUES` | `VALUES`、`CLASSIFICATION`、`IMAGE`、`MOVENET`、`SEGMENTATION`、`BOXES` |
| `output.0.scale=1` / `output.0.offset=0` | 图片输出实数值 → 0–255 像素，例如 0–1 输出使用 scale=255 |
| `output.0.labels=猫\n狗` | 分类/检测标签，按输出类别索引排列 |
| `output.0.threshold=0.3` | MoveNet 关键点、检测框分数或分割像素最大类别分数阈值 |
| `output.0.classesOutput=1` / `scoresOutput=2` / `countOutput=3` | BOXES 对应的其它输出索引；框按归一化 `[ymin,xmin,ymax,xmax]` 读取 |

同样规则适用于 `input.1.*`、`output.1.*` 等多输入/输出。`IMAGE` 输出为 `[1,H,W,1或3]`，`SEGMENTATION` 为 `[1,H,W,类别数]`；未适配的输出可用 VALUES 查看。运行器支持 float32、int32、uint8、int8、int64、int16、bool 与字符串 I/O；BINARY 输入采用模型原始 dtype、小端存储，适合预先分词的 token、视频张量或多模型中间结果。数值文本经 double 解析，大于 2^53 的精确整数请使用 BINARY。输入总计与每个数值输出最多 4,194,304 个元素，配置文件最多 1 MB。

**兼容性边界**：保存合法模型不等于任意模型都能执行。配置可修正布局、动态输入、输入来源、归一化、签名与结果显示，但不能新增缺失的 Flex/自定义算子、改写固定网络结构或代替专用分词器。专用 LiteRT 场景按下表接入；`bert_qa`、风格迁移、多阶段检索、强化学习及其它未列配方仍须单独适配。既有系统 TTS 是否离线取决于已安装语音包，新增 Matcha 为另一明确标识的本地模型。

## LiteRT 模型库资产目录与运行时基线

`app/src/main/assets/models/litert-models.tsv` 是从 [google-ai-edge/litert-samples](https://github.com/google-ai-edge/litert-samples) 固定提交 `f9f0e000630e9cdd6d263c4cd80495e4231a2dc8` 的 `samples/litert_model_zoo/android/app/src/main/assets/models.json` 生成的制表符目录，共 34 个任务、76 个文件，其中 16 个文件超过 64 MB。目录记录 taskId、模型名、分组、声明后端、许可证名称/URL、上游与模型卡 URL、文件名、下载 URL、精确字节数和 SHA-256；生成脚本为 `tools/generate-litert-catalog.py`，来源与许可证说明见 `app/src/main/assets/models/NOTICE-LiteRT-Model-Zoo.txt`。应用不引入 JSON 依赖，按行解析并校验 HTTPS、文件名、字节数和 64 位十六进制哈希。

- 下载器按集合（taskId）流式写入 `filesDir/model_assets/<taskId>/`，全程使用 `Long` 计数和私有临时文件，不把大文件读成 `ByteArray`；目录下载不受手工导入的 64 MB 上限影响，手工导入的上限保持不变。
- 入口在 **自定义模型 → 从远程下载模型 → LiteRT 完整资源集**，显示每集文件/大小/来源/许可、进度、取消、继续、完整集 SAF 导入及删除。模型不随开关自动下载；授权受限资源可先合法取得，再选齐原文件名的全部文件导入。
- 断点续传使用 `.part`、强 `ETag` 与精确 `Content-Range`：匹配的 206 才追加，200 重启，416/版本或范围变化重启；重定向有界且全程 HTTPS。GitHub 镜像只处理受支持 GitHub URL，其它来源直连。取消关闭阻塞流并在写入/提交边界检查，已校验组件和有效断点可继续。
- 开始及传输中检查空间并保留 64 MiB 余量；所有文件通过大小/SHA-256 后，写入包含固定来源与哈希的修订收据并原子替换。相同大小的旧修订不会误标就绪；失败保留旧集合，读取前恢复未完成替换。模型持有资源租约；安装/删除先使对应插件代次失效，等待原生句柄释放，再更改文件。

| 兼容性项目 | 本仓库基线 | 下载 / 运行门槛 |
| --- | --- | --- |
| Android API | `minSdk 24`，`compileSdk`/`targetSdk 37` | API < 24 拒绝；QNN NPU 需要 API 31+ |
| ABI | 仅打包 `arm64-v8a`、`x86_64` | 其他 ABI 的 APK 不含对应 LiteRT/QNN 原生库 |
| LiteRT | `2.3.2`（`litert`、`litert-gpu`、`litert-npu-runtime-qualcomm`） | `.tflite` 由打包的 Interpreter / CompiledModel 加载 |
| QNN | `2.50.0`（`qnn-runtime`） | 仅 `arm64-v8a` + Qualcomm HTP，API 31+，需 JIT 编译成功 |
| 后端 | CPU 始终可用；GPU/NPU 为探测后可用 | 目录 `backend=gpu` 需要可用 GPU delegate；`npu` 需要 Qualcomm HTP；`mixed` 可回落 CPU |
| `.litertlm` | LiteRT-LM `0.19.0` | 独立补充目录只列已适配的固定 Qwen2.5-Coder-1.5B CPU 配方，逐请求新引擎、内存门槛及模板核对；其它 bundle 不自动认作支持 |
| 下载成功 | **不是执行证明** | 只证明字节数、SHA-256 与服务器响应匹配；算子、动态形状或设备后端仍可能编译/执行失败 |

`RuntimeReadiness` 报告 API、打包 ABI、声明后端及未适配的 `.litertlm` 配方；场景加载另按实际算法契约、资源租约和内存门槛检查。已安装或检查通过均不代替真实推理验证。基础相机预览与现有姿态/示例能力不依赖该模型库目录。

## LiteRT 场景接续

固定 Zoo 的 34 个任务按六个场景接入，使用已有插件宿主、共享麦克风和资源目录。配置后回相机点击对应动作；慢结果与来源快照一起显示，旧框/蒙版不贴到新帧。CPU/GPU 选择与回退原因随结果报告，不以菜单选择证明硬件执行。

| 场景 | 模型/功能 |
| --- | --- |
| 画面分析（12） | RF-DETR、MoViNet、MiniFASNet、6DRepNet、DM-Count、DINOv2、XFeat、RAM++、NIMA、Vision-RWKV、PlantNet、PP-OCRv5 |
| 画面增强与分区（9） | PIDNet、TwinLite、EDSR、DehazeFormer、RF-DETR-Seg、ormbg、MODNet、Real-ESRGAN、Depth Anything3 |
| 实时英语字幕（1） | Zipformer small，2–8 秒有界窗口；慢推理可能跳过窗口，不是无损连续听写 |
| 神经语音朗读（1） | Matcha 英语短句，包含 G2P/时长/CFM/vocoder；CFM 固定 CPU |
| 录音处理与试听（6） | PANNs、CREPE、Basic Pitch、DAC、TIGER、CMGAN；显式短录音、逐轨试听，播放暂停共享采集 |
| 文本决策（5） | Laya、Julia、GLiClass Edge、Open-Decision、GLiNER-Decide；真实分词/embedding/校准/解码 |

另有 **本地文本建议**：Qwen2.5-Coder-1.5B-Instruct int4，单独下载约 1.04 GiB；CPU、1024-token 窗口、每次全新引擎，代码只展示。RAM++ 与本地文本在加载前检查当前可用内存；门槛取自实际主机测量并留余量，仍需 Android 设备验证。

系统监控可显式比较同一内置 YOLO26n、同一合成输入的 CPU/GPU/NPU，串行预热/测量并报告加载、推理、读回和关闭耗时。混合后端不能确定实际子图时标“未知”，失败/跳过保留原因。

实现、主机数值对照、Android 检查、升级和剩余任务见 [#63 接续审计](docs/issue-63-continuation.md)。#71–#76 还包含额外 legacy、ASR/TTS、提示分割、跟踪、多模态和生成式配方；上述接入不代表所有子 issue 已完成。

## 自动构建

推送到 `main`、发起 Pull Request 或在 Actions 页面手动触发 `Build APK` 工作流，即可得到构建产物（工作流会同时运行 `lintDebug` 和单元测试）。APK 按 ABI 拆分为 `arm64-v8a` 与 `x86_64` 两个包，LiteRT 以预编译运行时库打包（QNN NPU 库仅 `arm64-v8a`）：

- `minimal-apk-debug`：`app-arm64-v8a-debug.apk`、`app-x86_64-debug.apk`
- `minimal-apk-release`：经过 R8 代码压缩与资源裁剪的 `app-arm64-v8a-release.apk`、`app-x86_64-release.apk`
- `minimal-apk-release-mapping`：release 的 `mapping.txt`，用于还原混淆后的崩溃堆栈

## 本地构建

```bash
./gradlew assembleDebug assembleRelease lintDebug testDebugUnitTest --build-cache --parallel --console=plain
```

产物位于：

- `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`、`app/build/outputs/apk/debug/app-x86_64-debug.apk`
- `app/build/outputs/apk/release/app-arm64-v8a-release.apk`、`app/build/outputs/apk/release/app-x86_64-release.apk`

默认只构建 `arm64-v8a` 与 `x86_64`；如需支持 32 位设备，在 `app/build.gradle.kts` 的 `splits.abi.include` 中补充对应 ABI。

需要 JDK 21 与 Android SDK（`compileSdk = 37`），无需 NDK。

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
