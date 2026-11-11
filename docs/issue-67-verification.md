# #67 自定义模型：实现与验收

## 范围和当前结论

基线：`main@427c8b8`，整合分支 `pullfrog/63-complete-plugin-foundation`，已合并 #77–#80。按 #63 后续真机反馈验收：**删除模型工作台，YOLO26/11 使用同一配置页，模型逐项开关驱动，不再单选相机模型**。

本轮实现已收敛：通用模型重导名称保留、取消/删除排空、结果代次隔离、就绪原因及输入协议边界均已补齐；量化/结构检查、姿态删除和自检共享边界也已接通。最终 API24 **21 项组合原生回归全部通过**，其中包含本任务 13 项；真实 ADD 图保留红/绿/红零容差断言。升级后旧配置保留且 NUMBERS 实际运行正确，最终构建和独立复审通过，详见 [本轮三项验收](issue-63-three-issues.md)。

## 实际修复与根因

| 根因 | 修复 | 回归 |
| --- | --- | --- |
| 重导相同权重仍重写 `.name`，安装失败可能留新 sidecar | `ConfiguredModelStore.install` 仅创建缺失文件；现存 ID、权重、properties、name、enabled 原字节不动；权重最后成为列表可见标志；本次失败清理本次新文件 | `ConfiguredModelStoreTest`：人工名字/注释保留、重导不触发无效重载、失败保旧 |
| 页面推理只是协程取消；阻塞读和 native invoke 可继续，后台晚到可回传 | `ConfiguredModelEditDialog` 关闭、取消、ON_STOP 同时取消任务与 `AssetTransfer`；SAF 图片/音频/二进制采用有上限可关闭流；`ConfiguredModelAccess` 设置 LiteRT cancellable，取消只置旗标，invoke 排空后才 close | `ConfiguredModelAccessTest`、`ConfiguredModelPluginTest`；新增原生取消检查 |
| 重开页面可能并发累积单次运行，旧取消可能清掉新结果 | 全进程单次运行串行、仅最新意图；取消过的旧请求不能替换新请求；result ticket 按路径/config hash/模型启停代次/插件 revision/请求序号检查，按 ticket 撤回 | `ConfiguredModelAccessTest`、`ConfiguredModelResultsTest` |
| 检查版本与发布之间仍可能遇到配置改动 | begin/publish 与存储变更使用同一存储监视器；待执行 ticket 也注册取消，不只在 native 打开后才受保护 | `ConfiguredModelResultsTest` |
| 删除先 unlink，runner 仍持有映射；sidecar 删除失败未报告 | 路径级开门屏障，先撤回结果/取消，再排空已登记的 native 读者；不持存储锁等待；同目录 rename 暂存，失败回滚；接受后统一清理 | `ConfiguredModelStoreTest`；原生测试阻塞借用并证明文件未提前删除、独立缓存读者可继续 |
| 不支持的 dtype/错误渲染仅显示通用卡片，开启后实际不运行 | `configuredExecutionProblem` 给出来源/dtype/形状/渲染协议原因；列表和配置页显示运行模式与“未就绪”；native 算子兼容仍明确以运行结果为准 | `ConfiguredInputContractTest` |
| STRING 二维输出也被推断为分类 | STRING 默认 VALUES；错误人工渲染显示具体原因 | `ConfiguredInputContractTest` |
| BINARY 可精确输入 INT64，但 VALUES 经 Double 解码显示时悄悄舍入 | INT64 非量化 VALUES 按 Long 输出文本；数值型渲染仍使用 Double | 新增 `binaryInt64ValuesAboveDoublePrecisionKeepTheirExactOutputText` |
| WAV 仅校验部分块，容器长度/byteRate 损坏仍接受 | 校验 RIFF 总长、fmt/data 重复、byteRate、奇数块 padding、尾部截断和目标窗口 | `TensorDataTest`；共享调用方声音分类 WAV 路径需随整合检查 |
| 逐轴量化被标成 scale=0，损坏的算子/常量引用未检查 | `GraphTensor` 保存完整 scale/zero-point 向量、quantized_dimension 与 details_type，UI/摘要显示真实值；非 NONE details 优先于仿射向量，逐轴/非仿射方案当前明确拒绝运行并保留资产；检查表/向量边界、算子/张量/签名/控制流引用、buffer 与密集常量长度 | `TfliteGraphTest`：合法动态 custom-op 图保留，损坏引用/截断拒绝；8 个真实目录模型结构检查；真实 CustomQuantization fixture 即使带貌似有效标量 scale/zero 也拒绝直接编码与运行协议 |
| 姿态先删文件，GPU/native 可能仍用同路径；删除失败未保旧 | `PoseModelAccess` 按路径拒绝新打开，阻止旧帧发布，在创建 worker 停止并关闭目标消费者；之后可回滚 rename 和持久化列表一次提交；共享路径其它 spec 保留；`LiteRtBackend.close` 尝试全部清理并回抛失败 | `PoseModelAccessTest`：排空/同线程/共享别名/取消/关闭失败；真实非零 ADD 图的组合原生复验通过 |
| 自检直接开文件，通用删除不能等待其释放 | inspector 与执行实例均使用 `ConfiguredModelAccess`，保留 `setUseXNNPACK(false)` 和动态输出独立 inspector；整段检查租约还覆盖固定声音分类，姿态自检也走路径屏障与专属 worker | `ConfiguredModelAccessTest`；`ModelSelfCheckTest` 4 项原生通过，含删除与自检并发 |
| 删除后 finally 调用保存型 UI 回调，重复写入已提交列表 | `ModelsScreen.onSpecsReload` 只刷新 UI，唯一调用方 `PluginsScreen` 只赋值；最新列表在 IO 线程读取，不经保存回调，不把缺失文件过滤结果重新持久化 | 调用方 grep 与 Compose 类型检查；存储事务继续负责唯一持久化提交 |

取消不会在另一个线程中途 close Interpreter。构造/单个不响应取消的 native 算子仍须返回后才能释放；晚到结果无权重新发布。协调者独立核查 LiteRT v2.3.2 `nativeinterpreterwrapper_jni.cc` 的取消旗标使用 `std::atomic_bool::store`。

## #67 验收覆盖

| 要求 | 已核对代码/证据 | 边界 |
| --- | --- | --- |
| 总开关、逐项开关 | `PluginSettingsStore` 总开关不写逐项偏好；`ConfiguredModelStore` 独立 enabled；`cameraPluginSettings` 仅在总开关或姿态增强需要时启用共享姿态生产者 | 姿态增强独立消费关系保留；物理机组合复测归 #69 |
| 入口迁移 | `PluginsScreen` → `ModelsScreen`；两种 YOLO 均 `ModelEditDialog`；配置控件仍含锚点/关键点/布局/阈值/防抖/框格式；下载收拢到远程模型入口 | `ConfigScreen` 无 MODELS 菜单，`SettingsPageTest` 覆盖旧 MODELS 恢复回 ROOT；主源码无旧工作台/单选相机引导 |
| 推断与文件事实分开 | 字段事实来自 `TfliteGraph`；完整量化向量/轴、算子代码/版本和签名可见；来源、布局、归一化、输出含义为推断，标签/tokenizer/采样率待按说明配置 | 逐轴量化 I/O 暂不转换，明确未就绪；校验关键 FlatBuffer 引用/载荷边界，不冒充所有算子的语义验证器 |
| 合法不适配模型保留 | 通用安装不创建 Interpreter，不静态化改写；姿态不适配时保存原权重及可编辑配置；缺 Flex/自定义算子给运行失败原因 | 64 MiB 导入/输入文件上限，1 MiB 配置、8 维、合计 4,194,304 输入元素；不是任意模型运行时 |
| 实时/按需执行 | 图像与明确 PCM 共用已有串行 owner；相机同帧串行；姿态最多 2 个；单次执行进程级串行，无无界推理队列 | 通用已启用模型仍逐项加载，模型数增加会增加内存/降低帧率 |
| 按需结果返相机 | 只有当前已保存配置且模型/总开关均启用才发布；文件图像保留来源，使用 imageReport 而非覆盖当前相机图层 | 最多保留一份 2048 字摘要及两张长边 ≤512 图；多图只保留首个输出，页面可查看其它输出 |
| 配置/关闭/删除/后台隔离 | 新 owner 取消、host token、PCM permission/playback generation、按需 ticket、姿态路径屏障及自检租约共同保护 | 已发布按需摘要可跨 tab 返回相机；未完成的关闭/后台任务被撤回；等待 native/GPU 排空期间 UI 可取消，文件/列表未提交则保留 |
| 升级/重导保留 | 原 `app_settings/pose_models`、`files/models`、`configured_models`、`examples` 路径未迁移；properties/name/ID/启用偏好保留 | 本轮实际覆盖升级的文件范围见下，不扩大为全部历史数据类型已设备验证 |

## 可执行输入及明确限制

| 输入/输出 | 可复现真实执行 | 限制 |
| --- | --- | --- |
| IMAGE | `ConfiguredModelInputSmokeTest.selectedImageOutputCarriesItsActualInputAndRetainedPreviewOwnsBoundedCopies`：红/绿 PNG → 原生图 → 像素一致，独立缩略图副本 | batch=1、NHWC/NCHW、1 或 3 通道；按配置缩放/mean/std；不猜标签与框语义 |
| PCM / WAV | 同一测试类 `wavAndSharedPcmUseTheSameConfiguredSampleRateAndRealInterpreter`：16k PCM/WAV → 8k 重采样 → 相同原生输出 | AUDIO 明确选择单声道原始 `[-1,1]` 波形；`[N]`/`[1,N]`/`[1,N,1]`；PCM16 WAV 1/2 声道；共享采集 8–48k，窗口 ≤30 秒且 ≤480000 样本；不生成 mel/频谱 |
| NUMBERS | 同一测试类 `numericAndBinaryInputsExecuteBothIndexedAndNamedSignatures`：已知浮点逐项相等 | 单值填充或逗号/空白分隔；Double 精度，INT64 精确值需 BINARY；不含 tokenizer |
| TEXT | 同一测试类 `stringTensorPassesUnicodeWithoutInventingATokenizer`：ASCII/中文/emoji/组合字符/真实 NUL/末项空串 | 真 STRING，逐元素标准 UTF-8 byte[]；当前 Java API 首个元素为空明确拒绝；非 STRING 需自行准备 token 数值 |
| BINARY | 上述 float 测试；本轮新增 INT64 `2^53+1`、Long.MIN/MAX 精确输出测试 | 小端原始张量，字节数必须完全匹配；不再归一化/量化；支持 FLOAT32/INT32/UINT8/INT64/BOOL/INT16/INT8，FLOAT16 等有具体限制提示 |
| 多输入/多输出/签名/动态 | `ConfiguredModelLifecycleTest.dynamicMultiInputOutputAndTwoSignaturesExecuteExactArithmetic`：真实 ADD/SUB，2 输入/2 输出，主图及第二子图，别名顺序不同，动态 `[1]→[3]`，逐项和/差断言 | 只按所选签名配置；全 IMAGE 可接同一相机，混合输入按需填各项；空输入签名明确不支持 |

逐轴量化 I/O 的完整向量、轴号和 details_type 可查看、配置可编辑、权重原字节保留，但当前通用编码/解码明确不运行。`details_type != NONE` 时按 schema 忽略 scale/zero-point，明确不支持 Custom/Blockwise/MultiAxis 等非仿射方案，不用其表面标量参数计算。超过 64 MiB 的诊断路径采用映射 inspector；对 Java API 不能核对完整量化向量的 UINT8/INT8/INT16 I/O 明确 SKIP，不把标量 0 当作无量化。

测试中的 1244 字节 ADD/SUB 图 SHA-256：`c8fb66a5dd9121d4c68fbd0ca43bd4a031937551c42848d6b84735d9bb3f00d2`。这些是 I/O/算术/生命周期证明，不是视觉或语音识别准确率评测。

## 已有真实验证

### Android API24 / API35

协调者首轮各执行 **15 tests，0 失败、0 skip**，组合中含已有 `ConfiguredModelInputSmokeTest` 4 项、本轮早期 `ConfiguredModelLifecycleTest` 3 项及超分/学习测试。

- [API24 原生日志](https://uploads.pullfrog.com/agent-uploads/0b0c95ef-90e8-4687-ba6d-3c6ba4d91472/bg-06358bf7.log)
- [API35 原生日志](https://uploads.pullfrog.com/agent-uploads/6aadf870-872a-4631-8197-dd717d880587/bg-914baf88.log)

共享边界修复后的匹配构建已在 API24 完成：

- [13 项组合全部通过、无 skip](https://uploads.pullfrog.com/agent-uploads/1c3ae7ea-b361-4622-9496-fe677bfb7ecf/bg-5249a31b.log)：本任务 `ConfiguredModelInputSmokeTest` 4 项、`ConfiguredModelLifecycleTest` 4 项、`ModelSelfCheckTest` 4 项，另含 Clipboard 1 项。
- [PoseModelDeletionTest 早期单独执行通过记录](https://uploads.pullfrog.com/agent-uploads/902eee85-ce78-452a-af4e-11bd782d9faf/bg-2c399561.log)：此时使用零算子 I/O alias 图，不能再视为稳定的数值证明。

随后组合执行 `/tmp/pullfrog-5AbsYD/bg-02934718.log`：21 项中 20 通过，姿态 fixture 首次推理红色期望 `1.0` 却得 `7.1429368E31`，发生在启动删除之前。本任务通用输入 4 + 生命周期 4 + 自检 4 项仍全部通过。

核查旧 fixture：0 算子、输入/输出均 tensor 0；生产 `LiteRtBackend` 给 `CompiledModel` 创建独立输入与输出外部缓冲区。[LiteRT v2.3.2 compiled_model.cc](https://github.com/google-ai-edge/LiteRT/blob/v2.3.2/litert/runtime/compiled_model.cc) 的 `Run` 先注册输入再输出，`RegisterBuffer` 按同一 tensor 设置外部 allocation；零算子别名图不会把独立输入自动复制到输出，读值取决于原 native 内存，故原 fixture 不适合证明该 API 路径。

修复测试为 **1 个实际 ADD、不同输入输出 tensor、非零常量 `[.25,.5,.75]`**，红/绿/红 A/B/A 分别精确断言 `[1.25,.5,.75]`、`[.25,1.5,.75]`、`[1.25,.5,.75]`，容差 **0**，其后仍验证关闭排空和注册提交。新图 456 字节、SHA-256 `513e31bd9327d7a7aeae088fe77ca8c487cb877b5751cc678fe645f0b5c6cb06`；[最终 21 项组合全部通过](https://uploads.pullfrog.com/agent-uploads/31b925bb-0e30-444e-af73-9f85e9586797/bg-645da40e.log)，无跳过。

生产调用方核对：内置 YOLO26n 为 440 算子、输入 0/输出 768；下载 YOLO11n 为 401 算子、输入 0/输出 702，均无 I/O alias。导入姿态契约要求四维图像输入与三维姿态输出，不能为同一张量；`BackendComparisonLiteRt` 使用同一 YOLO26n。通用 identity 测试使用 `Interpreter`，已通过，并非此 `CompiledModel` 独立缓冲路径。本次未因测试 fixture 修改生产数值逻辑。

API35 原生日志是首轮版本证据；最终共享边界的组合复验在 API24 完成，未将早期 API35 结果冒充最终版本复验。

### 同签名覆盖升级

协调者从 CI `43025864024` 基线构造旧数据：真实旧 `SoftmaxHead` 训练 30 次保存、3 个真实 identity 模型，人工 properties/name/开关，UI 实际 YOLO 阈值 `0.61`，音频缓存。API24 同签名 `adb install -r`，未卸载/清数据。

本执行器只读比对了：

- `/tmp/opencode/issue63-upgrade/before.json`
- `/tmp/opencode/issue63-upgrade/after-first-install.json`
- `/tmp/opencode/issue63-upgrade/baseline-models.xml`

**15 文件 bytes/SHA-256 完全相同**：3 模型 × 权重/properties/name/enabled 共 12 文件，`examples/personalization-head.bin`、`examples/audio.tflite`、`app_settings.xml`。新 UI 与真实 NUMBERS 执行后再次核对仍未变化：

- [运行后的 15 文件 hash](https://uploads.pullfrog.com/agent-uploads/c2fcb6dd-c95e-441b-b787-1bd7059816ef/after-ui-run.json)
- [新模型列表：旧阈值 0.61、人工名称、运行模式](https://uploads.pullfrog.com/agent-uploads/61d5792c-5324-4627-a195-983048ab6880/after-models.xml)
- [实际运行输出 1.25, -0.5, 0.0, 1.0](https://uploads.pullfrog.com/agent-uploads/1f214882-71a4-4d4d-b00c-d69d65352a5d/configured-result.xml)

本次清单没有第二个导入姿态权重，不能把它扩称为两姿态模型/全部历史量化动态模型的设备升级证明。

### JVM / 静态检查

- 独立 Kotlin 2.4.21/JUnit 检查扩展到模型/姿态/诊断：**109 tests，0 failures**，包含 8 个真实目录模型结构解析和非 NONE 量化 details fixture；日志 `/tmp/opencode/issue67-shared/unit.log`。启用可选 `configured.graph.fixtures`，本阶段未运行 Gradle。
- 修改的 Compose 页与 4 个相关 instrumentation 类独立类型检查通过，日志 `/tmp/opencode/issue67-ui-check/compile-shared.log`。这只证明静态调用兼容。
- 最终整合单测 **622 tests，0 failures，14 skipped**，本轮真实八模型结构 fixture 已启用并通过：[逐套结果](https://uploads.pullfrog.com/agent-uploads/b7670bf9-cfb5-415d-b41b-20439c7bc0d1/check-results.json)。Debug/Release/AndroidTest 构建、lint 和独立复审通过，证据见总验收。
- 自有文件 `git diff --check` 通过（只读检查，未操作 Git 状态）。

## 调用方与已接通的共享边界

变更前后均 grep 调用点：

- `ConfiguredModelStore.install/saveConfig`：`ModelsScreen.prepareModelImport`、`ExampleDownload.prepareInstall`、配置页；保留目录预置修复标签逻辑和重导编辑。
- `list/revision/setEnabled`：`ModelsScreen`、`ConfiguredModelPlugin`、`CameraScreen` combine、`PluginSettingsStore` 旧 `model_id` 迁移。
- `ConfiguredModelRunner.run`：配置页、已有输入原生测试、新生命周期原生测试。
- `LiveConfiguredModel`：自定义模型插件、`ExampleScenePlugin`（文件在独立 examples 缓存）；`runTensors` 保持原返回类型。
- `ConfiguredPcmModel`：自定义模型插件、原生 WAV/PCM 测试。
- `execute/configureInputs`：通用/实时/PCM runner、`ModelSelfCheck.checkInterpreter`；`shapedArray/encodeTensor/decodeTensor` 还有诊断及旧声音分类调用链，已核查不改变签名。
- `wavSamples`：通用 AUDIO 与 #68 声音分类 WAV；合法 PCM16 行为保留。
- `ConfiguredModelResults.begin/publish`：配置页；result flow：自定义插件。
- `PoseModelImport.delete`：`ModelsScreen` 在 IO 任务执行，返回后纯 UI 刷新；`PluginsScreen` 为唯一刷新回调接线。
- `GraphTensor`：文件 parser、诊断 runtime inspector、通用输入契约、encoder/execute、配置页和对应 fixtures；所有文件事实展示使用完整量化向量。
- `PoseAnalyzer.open/loadWithTimeout`：导入模型在创建 worker 中经 `PoseModelAccess.open`；`PoseModelsPlugin` 使用删除 revision 拒绝旧帧并在成功/失败结束后恢复。
- `LiteRtBackend.open`：生产姿态 analyzer 和姿态自检均已登记路径；内置资产和测试专用直接打开仍无可删用户文件。
- `ConfiguredModelAccess.open/inspect`：通用/实时/PCM runner、自检 inspector/执行实例与整段音频检查；既有自检 `setUseXNNPACK(false)`、线程数和独立 inspector 均保留。

先前列出的解析、姿态删除、自检读者三个共享问题均已实现修复，无未接线的生产调用。#68 声音分类插件使用独立 `examples` 缓存；自检若验证已导入声音模型，则整个检查持有原路径租约直到固定声音 runner 关闭，不复制用户数据、不绕过删除屏障。

## 可复现检查命令

源码冻结后按仓库 JDK/SDK 配置执行（本阶段执行器没有运行这些命令）：

```bash
./gradlew assembleDebug assembleRelease lintDebug testDebugUnitTest assembleDebugAndroidTest --build-cache --parallel --console=plain
```

针对性 JVM 重跑：

```bash
./gradlew testDebugUnitTest --tests 'com.example.minimalapk.models.*' --tests 'com.example.minimalapk.plugins.Configured*' --tests 'com.example.minimalapk.plugins.CameraModelSettingsTest' --tests 'com.example.minimalapk.pose.ModelStoreTest' --tests 'com.example.minimalapk.pose.PoseModelAccessTest' --tests 'com.example.minimalapk.pose.PoseAnalyzerTest' --tests 'com.example.minimalapk.pose.TfliteGraphTest' --tests 'com.example.minimalapk.diagnostics.SelfCheck*' --tests 'com.example.minimalapk.ui.screens.SettingsPageTest' --tests 'com.example.minimalapk.ui.screens.ModelConfigFieldsTest' --console=plain
```

安装协调者最终 Debug/测试 APK 后在 API24 与 API35 分别执行：

```bash
adb -s "$SERIAL" shell am instrument -w -e class com.example.minimalapk.models.ConfiguredModelInputSmokeTest,com.example.minimalapk.models.ConfiguredModelLifecycleTest,com.example.minimalapk.diagnostics.ModelSelfCheckTest,com.example.minimalapk.pose.PoseModelDeletionTest com.example.minimalapk.test/androidx.test.runner.AndroidJUnitRunner
```

预期 13 项；原生 `cancelledNativeHandleRejectsOldWorkAndFreshRunnerStillExecutes` 覆盖主图/两个签名实际取消，`ModelSelfCheckTest` 覆盖动态输出与并发删除，`PoseModelDeletionTest` 覆盖真实 CompiledModel 创建线程排空。可选真实目录结构测试使用测试 JVM 属性 `configured.graph.fixtures` 指向包含 8 个目录模型的目录；不传则该项显式跳过。

## 物理设备待测

- arm64 手机相机多模型+姿态增强+PCM 组合：各开关、配置、关闭、后台/恢复无晚到结果，基础预览持续可用；核对真实吞吐、内存、发热。
- 真实麦克风权限撤销、系统静音、音频播放、不同采样率及硬件占用恢复。
- 两个姿态模型（含下载 YOLO11）旧配置与权重、用户量化/动态/多输入模型的同签名升级和重导；按需结果从插件页返回相机；共享缓存删除隔离。
- SAF 外部/慢 provider 读取消与存储失败，TalkBack 开关/卡片独立操作。
- GPU/NPU 数值正确性和场景准确率不由这组 CPU 小图回归证明。
