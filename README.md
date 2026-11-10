# 极简 APK 模板

一个类似 KernelSU 风格的极简 Android 应用模板，使用 Kotlin + Jetpack Compose 编写，并通过 GitHub Actions 自动构建 APK。

## 功能

底栏包含 5 个选项卡：

| 选项卡 | 内容 |
| --- | --- |
| 主页 | 推理后端状态、CPU/GPU 占用与应用版本、CPU/GPU 型号、设备型号、系统版本与 ABI |
| 相机 | 基于 CameraX 的后置摄像头实时预览，内置 YOLO-pose 模型做离线姿态测量与性能指标，可在设置中启用、导入与调参 |
| 示例 | 模型工作台（姿态/分类/检测/分割/音频）、超分辨率、设备端训练、系统语音合成 |
| 插件 | 姿态增强：身高设置、身高线、臂长线叠加开关 |
| 设置 | 推理后端、模型（启用/导入/阈值/防抖）、主题设置与关于 |

## 项目结构

```
app/src/main/java/com/example/minimalapk/
├── MainActivity.kt
├── examples/                     # 超分辨率、设备端训练等示例模型
├── models/                       # 通用 TFLite 配置、导入、输入转换与结果显示
├── log/
│   └── AppLog.kt                 # 应用内日志缓冲
├── pose/                         # 姿态模型、后端选择与测量
└── ui/
    ├── MinimalApkApp.kt          # 底栏与页面切换
    ├── theme/
    │   ├── Theme.kt              # Material 3 主题
    │   └── ThemeMode.kt          # 主题模式持久化
    └── screens/
        ├── Common.kt             # 共用组件
        ├── StatusScreen.kt
        ├── CameraScreen.kt
        ├── ExamplesScreen.kt
        ├── ModelWorkbenchScreen.kt
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
| YOLO11n-pose 640 | 下载 `models/yolo11n_pose.tflite` | `cxcywh` |

模型共用同一份 `ModelSpec` 描述（输入尺寸、anchor 数、关键点数、框格式、输出布局、阈值、防抖）。YOLO11n-pose 由 `yolo11n-pose.onnx` 经 onnx2tf 转换而来，框通道是中心点+宽高，解码时换算成左上/右下；它放在仓库根目录的 `models/`，不参与 APK 打包，因此 APK 只包含主模型。

- 设置 → 模型 里可以启用/停用模型、从本地导入或远程下载 tflite、修改检测置信度阈值、关键点阈值、防抖帧数、框格式与输出布局。同一时间最多启用 2 个模型，修改在重新进入相机后生效。
- 多个启用模型按“同帧串行”运行：一次预处理后按顺序跑每个模型，帧率约等于各模型推理耗时之和；只有第一个模型驱动叠加层，其余模型在底部只显示置信度。所有模型都关闭时仍显示相机画面（只做旋转与 letterbox，不做推理）。
- 本版本只做单人：每个模型只取置信度最高的一个检测。
- 导入模型可以走系统文件选择器（SAF，不需要存储权限），也可以走“从远程下载模型”：内置目录（`ModelCatalog`）或自定义 `https` 地址，下载上限 64MB，下载后与本地导入使用同一套校验。远程下载需要 `INTERNET` 权限，日常推理仍然离线。
- 导入时会读取 TFLite 的输入/输出张量形状并自动填写模型配置：
  - 接受单输入单输出、float32、NHWC 正方形输入，以及 `5+3K` 姿态头；值优先 `[1, 5+3K, N]` 与锚点优先 `[1, N, 5+3K]` 都会自动识别。非 17 个关键点的模型也会导入，但骨骼与身体测量只按 COCO 17 点索引，缺失的点不标注、不参与测量。
  - 不符合相机姿态管线的模型（量化、NCHW、非正方形、多输入/输出、非姿态头、动态维度等）会保留到“模型工作台”，说明不适配原因，并生成可编辑配置。文件格式损坏、超出 64 MB 或存储失败会阻止导入。工作台不会把一般模型强行解释为姿态模型。
  - 自动识别的输入尺寸、anchor 数、关键点数和输出布局都会写进模型配置；输入尺寸由静态输入形状决定，anchor、关键点数、输出布局和框格式可以在模型的编辑弹窗里修正。改错导致加载失败时，相机页会提示检查这些字段或切换推理后端。框格式无法从张量形状推断，导入默认按 `xyxy` 解码，远程目录里的模型则带上已知的框格式。
  - onnx2tf 导出模型常把 `shape_signature` 里的维度写成 `-1`，GPU delegate 可能拒绝这类图。符合相机姿态管线的导入仍会静态化并报告改写数量；无法静态化时转入工作台。工作台保留原始模型字节，用 Interpreter 在运行时调整输入，不修改动态签名。
  - 导入的模型复制到 `filesDir/models/<sha256>.tflite`，加载时用 `CompiledModel.create(路径, ...)`，不需要新依赖。导入只做形状校验，不做试跑：能通过校验的模型仍可能在该设备上编译失败，错误会显示在相机页。
- 应用按 `LiteRT (NPU)` → `LiteRT (GPU)` → `LiteRT (CPU)` 的顺序自动选择第一个可用的后端。LiteRT GPU 请求同时包含 CPU 加速器，未被 GPU 委托的算子回退到 CPU 执行。第二个模型复用第一个模型已探测成功的加速器，加载失败时只跳过该模型。
- LiteRT NPU 使用 `litert-npu-runtime-qualcomm` 与 Qualcomm `qnn-runtime` 的 Hexagon HTP 运行时，仅在 Android 12+、`arm64-v8a`、SoC 厂商为 Qualcomm（`Qualcomm`/`QTI`）的设备上探测。模型通过 LiteRT 在设备端 JIT 编译到 NPU，首次进入相机延迟明显，编译产物缓存在 `cacheDir`（后续通常复用）；JIT 需要 `libQnnHtpPrepare.so`。APK 打包 qnn-runtime 提供的全部 HTP/DSP 代 `Skel`/`Stub`，因此所有 Qualcomm 机型都会尝试 NPU，JIT 编译失败时回退到 GPU/CPU。LiteRT 2.3.2 会把单独的 NPU 请求扩展为 NPU+CPU，HTP 编译失败时可能静默回退 CPU：LiteRT 的 Kotlin API 只提供环境支持的加速器，不返回实际执行的子图，`QualcommOptions.Profiling` 也只写入 logcat，因此探测链无法判断 HTP 是否真正接管，会按 `LiteRT (NPU)` 接受该模型（这是一项明确的取舍，不是遗漏）。若怀疑回退到 CPU，请查看 QNN 日志确认。QNN 库需在安装时解压到 `nativeLibraryDir`，因此启用了 `useLegacyPackaging`；`arm64-v8a` 包的 QNN 运行库解压后约 211MB（其中 85MB 是 JIT 编译库），压缩进 APK 约 71MB，release APK 实测约 86MB。
- LiteRT 使用 `com.google.ai.edge.litert:litert`、`litert-gpu` 与 `litert-npu-runtime-qualcomm` `2.3.2` 的 `CompiledModel` API。GPU 请求同时包含 CPU 加速器：LiteRT 2.3.2 在只请求 GPU 时，只要有算子未被 GPU 委托就让整个模型编译失败（`Failed to compile model`）；加入 CPU 后这些算子回退 CPU，其余仍在 GPU 执行。GPU 默认使用 MLDrift 的 OpenCL 后端，委托创建失败时先用同一加速器库的 OpenGL 后端重试（该库提示 Adreno 8xx 旧驱动的 OpenCL 内核编译不稳定），仍失败才继续回退；NPU 请求不包含 GPU 兜底，但 LiteRT 会自行加入 CPU 兜底。onnx2tf 导出时会把 batch/height/width 标记成 `-1`，下载与导入的模型由 App 在设备端把签名改写成与具体形状一致；内置模型已提交为静态签名，重新导出后由 `TfliteGraphTest` 校验。
- 模型输入为 `1x3x640x640`（TFLite 为 NHWC `1x640x640x3`），输出为 `1x56x8400` 原始姿态头：通道 4 是置信度，通道 5..55 是 17 个 COCO 关键点的 x/y/confidence。解码器按配置的 `OutputLayout` 原位读取值优先或锚点优先布局，不构造中间 `300x57` 表；按模型配置的置信度阈值过滤后按置信度排序，取前 300 个候选。
- 模型权重由 Ultralytics 的 ONNX 姿态头经 onnx2tf 转换为 TFLite，不使用外部提供的权重；YOLO26n 使用 ONNX 原始姿态头 `/model.23/Transpose_output_0`，YOLO11n 使用 ONNX 的 `output0`。
- 性能面板中的“加速器”会显示实际选中的后端（如 `LiteRT (NPU)`、`LiteRT (GPU)`、`LiteRT (GPU/GL)` 或 `LiteRT (CPU)`）。`LiteRT (GPU)`/`LiteRT (GPU/GL)` 表示 GPU 成功参与委托，未被 GPU 委托的算子仍由 CPU 执行。
- 日志页会记录后端探测顺序、设备信息（SDK/ABI/SoC 厂商与型号）、启用的模型、NPU 门槛未通过的原因、NPU 环境与 JIT 编译耗时、LiteRT GPU 每次尝试的后端（`AUTOMATIC`/`OPENGL`）与环境可用加速器、各后端加载耗时/失败堆栈、导入时的签名改写数量，以及首帧每个模型的预处理/推理/后处理耗时，便于定位启动慢或回退问题。
- 输入身高用于把像素换算成厘米：`cm/px = 身高 / 人体检测框高度`，再据此估算臂展、双眼间距、肩线/髋线倾斜与肩髋横向偏移。身高与叠加线设置在“插件 → 姿态增强”中。
- 相机页的“示例模型叠加”会列出“示例 → 模型工作台”中已下载或导入的模型，并在每个预览帧上运行；姿态、检测框、分割或图片输出直接叠加在摄像头画面上，分类结果写在底部。只支持所有输入均为 `IMAGE` 的模型，音频/文本/数值模型仍在工作台运行；叠加模型使用 CPU Interpreter，会降低帧率。
- 使用条件会影响精度：需要全身入镜、正对镜头、双臂平举，且人物应尽量站直。单目 2D 关键点无法给出临床意义上的脊柱侧弯角度，界面结果仅供筛查参考，不能用于医学诊断。
- 预览右上角显示性能指标：分析帧率、预处理/推理/分析耗时（取第一个启用模型），以及 CPU、GPU、NPU 利用率。Android 不向普通应用开放系统级占用：CPU 显示本进程占设备总 CPU 算力的比例（0–100%），GPU/NPU 会尝试读取常见厂商的 sysfs 节点，设备不暴露时显示 `N/A`。LiteRT 2.3.2 也没有读取 NPU 利用率的 API，`QualcommOptions.Profiling` 只把逐算子耗时写进 logcat，因此 NPU 无法通过 LiteRT 补上该指标。
- 模型来自 Ultralytics，许可为 AGPL-3.0；许可全文与模型来源见 `app/src/main/assets/models/NOTICE.txt`、`models/NOTICE.txt` 与 `app/src/main/assets/models/LICENSE-AGPL-3.0.txt`。

## 示例（TensorFlow Lite 例程）

“示例”选项卡提供下列 [tensorflow/examples](https://github.com/tensorflow/examples/tree/master/lite/examples) 能力。模型首次使用时按需下载，校验固定大小与 SHA-256，下载后离线运行；APK 仍只内置 YOLO26n-pose。

| 示例 | 来源 | 输入 → 输出 | 说明 |
| --- | --- | --- | --- |
| 姿态估计 | MoveNet Lightning，Google Coral 的量化测试模型（约 3 MB） | `[1,192,192,3]` uint8 → `[1,1,17,3]` float32 | 选择图片，显示 COCO 17 点与骨骼；官方旧 TF Hub 链接已失效，改用固定提交的 Coral 源 |
| 图片分类 | `image_classification` MobileNet V1（约 4 MB） | 224×224 RGB → 1001 类 | 反量化后显示 Top-5，类别名从模型附带标签读取 |
| 物体检测 | `object_detection` SSD MobileNet V1（约 4 MB） | 300×300 RGB → 框、类别、分数、数量 | 四输出，显示检测框与标签 |
| 图片分割 | `image_segmentation` DeepLab V3（约 3 MB） | 257×257 RGB → 21 类像素分数 | 显示逐像素分类色图，背景为黑色 |
| 音频分类 | `audio_classification` YAMNet（约 4 MB） | 16 kHz、15600 个采样点 → 521 类 | 选择 PCM16 WAV，转单声道、线性重采样，取开头 0.975 秒，不足补零 |
| 超分辨率 | `lite/examples/super_resolution` 的 `ESRGAN.tflite`（约 5 MB） | `[1,50,50,3]` float32 → `[1,200,200,3]` float32 | 先中心裁切并缩到 50×50，再 4× 放大到 200×200；不是通用无损放大，细节取决于原图 |
| 设备端训练 | `lite/examples/model_personalization` 的 `model.tflite`（约 18 MB） | 224×224 图片 → MobileNetV2 特征 → 4 类 | 冻结主干，只在本机训练最后的分类头；图片不会上传 |
| 语音合成 | 设备自带 TTS 引擎 | 文本 → 语音 | 见下方边界说明 |

- 示例与工作台使用 `org.tensorflow.lite.Interpreter`（随 LiteRT AAR 提供，JNI 由 `libLiteRt.so` 实现），CPU 执行。模型在后台线程创建、运行和关闭，退出页面不在主线程等待推理结束。
- 设备端训练只用模型的 `load` 签名提取 62720 维瓶颈特征，分类头由纯 Kotlin 的 `SoftmaxHead`（Adam，学习率 0.001）在本机训练；官方 `train`、`save`、`restore` 签名含 Flex 算子，因此不使用它们。每类至少一张图片、总计最多 200 张，训练 150 步后原子保存权重与优化器状态；重新进入页面可直接识别，继续训练需重新选样本，重置删除存档。图片与样本特征不持久化。
- 语音合成没有使用 TFLite：官方示例仓库当前没有 TTS 例程；社区 FastSpeech2 的 TFLite 导出依赖 `FlexRandomUniform` 等 Flex 自定义算子（部分模型还需要音素前端），LiteRT 无法直接加载。这里调用系统 `TextToSpeech`，是否需要下载语音包取决于系统实现。
- 下载源为 TensorFlow 与 Google Coral（Apache-2.0），详情见 `ExampleCatalog` 与 `assets/models/NOTICE.txt`。示例缓存放在 `filesDir/examples/`，可配置模型及其配置放在 `filesDir/configured_models/`；删除工作台模型时删除其配置，示例缓存可供再次添加。

## 模型工作台与配置修正

入口：示例 → 模型工作台，或设置 → 模型 → 模型工作台与配置文件。

1. 导入本地 `.tflite`、填写 HTTPS 地址，或点击示例目录下载。导入不创建原生运行时，也不执行模型；读取张量形状、dtype、量化参数、自定义算子与所有签名。
2. 选择主子图或命名签名，自动生成 UTF-8 `.properties` 配置。可以直接编辑、保存、从文件导入或导出；重导相同模型保留已有配置。自动推断仅是起点，归一化、标签及输出含义必须与模型说明一致。已安装的纯图片输入模型也可以在相机页作为实时叠加层运行。
3. 选择每个输入的文件，或填写数值/字符串，然后点击运行。固定维度不能通过配置改变，动态维度可填正整数；每次执行新建 Interpreter，因此一般签名调用之间不保留可变张量状态。

| 配置项 | 含义 |
| --- | --- |
| `version=1` / `signature=` | 配置版本；空签名为主子图，其它值为模型中的签名名 |
| `input.0.shape=1,192,192,3` | 第 0 个输入的运行形状；标量为空；动态维度必须明确填写 |
| `input.0.source=IMAGE` | `IMAGE` 图片，`NUMBERS` 数值，`TEXT` 字符串，`AUDIO` WAV，`BINARY` 原始张量 |
| `input.0.layout=NHWC` | 图片支持 `NHWC`/`NCHW`、非正方形及 1/3 通道；按形状拉伸，不做 letterbox |
| `input.0.mean=0` / `input.0.std=1` | 图片实数值 `(pixel−mean)/std`；uint8/int8 等按模型的 scale/zero-point 量化 |
| `input.0.values=0` | 数值用空格/逗号分隔，单个值填充整个张量；TEXT 用 `\n` 分隔每个字符串 |
| `input.0.sampleRate=16000` | AUDIO 的目标采样率；需要 PCM16 单/双声道 WAV |
| `output.0.render=VALUES` | `VALUES`、`CLASSIFICATION`、`IMAGE`、`MOVENET`、`SEGMENTATION`、`BOXES` |
| `output.0.scale=1` / `output.0.offset=0` | 图片输出实数值 → 0–255 像素，例如 0–1 输出使用 scale=255 |
| `output.0.labels=猫\n狗` | 分类/检测标签，按输出类别索引排列 |
| `output.0.threshold=0.3` | MoveNet 关键点或检测框分数阈值 |
| `output.0.classesOutput=1` / `scoresOutput=2` / `countOutput=3` | BOXES 对应的其它输出索引；框按归一化 `[ymin,xmin,ymax,xmax]` 读取 |

同样规则适用于 `input.1.*`、`output.1.*` 等多输入/输出。`IMAGE` 输出为 `[1,H,W,1或3]`，`SEGMENTATION` 为 `[1,H,W,类别数]`；未适配的输出可用 VALUES 查看。运行器支持 float32、int32、uint8、int8、int64、int16、bool 与字符串 I/O；BINARY 输入采用模型原始 dtype、小端存储，适合预先分词的 token、视频张量或多模型中间结果。数值文本经 double 解析，大于 2^53 的精确整数请使用 BINARY。输入总计与每个数值输出最多 4,194,304 个元素，配置文件最多 1 MB。

**兼容性边界**：保存合法模型不等于任意模型都能执行。配置可修正布局、动态输入、输入来源、归一化、签名与结果显示，但不能新增缺失的 Flex/自定义算子、改写固定网络结构或代替专用分词器。`bert_qa`、OCR、风格迁移、多阶段检索、视频分类、生成式 AI、强化学习等完整交互流程尚未逐个移植；可以导入其 TFLite 文件并配置支持的张量/签名，但不宣称具备全部上游应用功能。官方仓库没有 TTS 示例，系统 TTS 是否离线取决于已安装的语音包。

## 自动构建

推送到 `main`、发起 Pull Request 或在 Actions 页面手动触发 `Build APK` 工作流，即可得到构建产物（工作流会同时运行 `lintDebug` 和单元测试）。APK 按 ABI 拆分为 `arm64-v8a` 与 `x86_64` 两个包，LiteRT 以预编译运行时库打包（QNN NPU 库仅 `arm64-v8a`）：

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
