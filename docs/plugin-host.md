# 插件宿主接入（#64）

公共接入点在 `app/src/main/java/com/example/minimalapk/plugins/`，均为应用模块内的 `internal` 接口。它们是内置插件的最小宿主，不是外部安装/脚本 SDK。

## 下游接入步骤

1. 在 `PluginIds` 添加稳定 ID、在 `pluginDefaults` 添加默认配置；新下载模型、音频消费者默认停用。不要用中文名称或 tab 索引作 ID。
2. 实现 `CameraPlugin`，声明实际需要的 `PluginCapability`，加入 `CameraScreen.kt` 的 `PluginHost(listOf(...))`。复用 `PluginsScreen.kt` 的 `PluginCard` 和 `PluginSwitchRow`；卡片配置区域与总开关是兄弟点击区域，停用仍能打开设置弹层。
3. 设置通过 `PluginSettingsStore.setEnabled/configure/update` 保存到 `app_settings` 的 `plugin.<id>.enabled/revision/config.<key>`，通过 `settings: StateFlow` 订阅。保存配置不改变开关，开关不改配置。旧 `pose_models`、`pose_backend`、主题及用户模型文件保持原格式。
4. 在 `Started` / `ConfigurationChanged` 更新消费者，在 `Stopped` 停止排队并释放资源。每次回调带 `PluginContext(token, settings)`：异步任务保留它，工作前调用 `isCurrent()`，完成后只用该 context 的 `publish()`。不能用任务结束时取到的新 context 包装旧结果。
5. 模型复用现有 `ModelConfig` / `ConfiguredModelStore` / `LiveConfiguredModel`，不要重建张量解析器。`AsyncModelSlot` 在同一个专用线程创建/运行/关闭，一个在用模型、一个合并的待加载配置；`runIfReady()` 在加载中立即返回 null。慢的图片/音频消费者可用 `BoundedFrameWorker`（一项执行中 + 一项最新待处理）；禁用、换配置、取消按压时 `clear()`，宿主最终 `CameraPlugin.close()` 时关闭 worker，前后台 `Stopped` 后仍可再次 `Started`。工作函数需处理失败并报告带 ID 的错误。

现有 `PoseModelsPlugin` 持有唯一 `PoseAnalyzer`，同时供基础骨架与 `PoseEnhancementPlugin` 使用；增强开关不加载/关闭它、不改写启用模型列表。`ConfiguredModelPlugin` 已承接“自定义模型”插件：稳定 ID 仍为 `configured_model`，配置页复用 `ModelsScreen`/`ModelWorkbenchScreen`，相机模型选择保存在 `plugin.configured_model.config.model_id`；停用只停止该消费者并清空输出。`SystemMonitorPlugin` 只消费相机帧事件，输出右上角指标和底部 NPU 诊断；不依赖姿态增强或任何模型是否启用。`ExampleScenePlugin` 以四个固定示例模型实例化（`movenet_scene`、`image_classification_scene`、`object_detection_scene`、`image_segmentation_scene`），沿用 `ExampleDownload.install`、`LiveConfiguredModel` 和 `ConfiguredModelRunner`，阈值只作为运行时覆盖，不改写已存在的 `.properties`。`AudioClassificationPlugin`（`audio_classification`）只消费共享 `AUDIO_PCM`，用有界窗口和专用 worker 运行 YAMNet；`TtsPlugin`（`tts`）只发布系统 TTS 状态，朗读动作在配置弹层中显式触发。设置中的旧“模型”页已删除，恢复旧 `SettingsPage.MODELS` 状态时回落到根页；示例页及其工作台入口暂留，设备端个人化与导航迁移待后续任务。

## 事件与线程

- 控制事件（启停、配置、生命周期、按压）来自相机 UI 所有者；帧事件来自唯一 CameraX analysis worker。两者可并发，插件控制回调必须短，不在 UI 等待模型结束。宿主不在输出锁内调用插件。
- UI 控制器从主线程调用 `configure/lifecycle/press/release/cancel/permissionChanged`；音频采集线程可调用 `audio`，异步结果可从任意线程 `publish`。不要在帧回调里重入控制方法。停止事件携带失效 token 用于释放资源，清理不能以 `isCurrent()` 为前置条件；新会话的资源用 token 区分。
- `PluginToken` = 插件 ID + 会话号 + 配置代次。后台/离开/停止立即使 token 无效、清空帧/图层/文字；下次前台生成新会话。更改配置也增加代次并清空该插件结果。基础姿态配置变化同时使测量消费者失效。
- `CameraFrame` 含会话/帧号、`System.nanoTime()` 时间、原始 Bitmap 尺寸和旋转、朝上的预览图、真实照片 `PhotoBounds`、`ViewportMapping` 及共享姿态结果。`rawMapping.toImage/toRaw` 使用与 `PoseAnalyzer` 一致的旋转/缩放/letterbox 舍入中心，把原始分辨率 ROI 与预览坐标互转。时间戳是宿主收到帧的单调时间，**不是传感器曝光时间**；按压和未来音频同用该时基。
- 图像借用为只读。保留 `PluginImage` / `CameraFrame` 引用即可跨回调保活；宿主不会 recycle 已发布图片。禁止修改其 `bitmap`。需要绘制/写入时 `copyBitmap()`，自己拥有并在不再使用时释放该副本。发布图片后也不可 recycle/原地复用，交由 GC 在 Compose 和所有消费者释放引用后回收。不得保留无界帧历史。
- `Press` 不等长按阈值，包含 pointer ID、预览坐标、单调时间和独立 `PressToken`。释放、出真实照片/视口边界、第二根手指、系统取消、视口改变、切页、后台、停止都会撤回替换。多指后要全松开再按；TalkBack 提供“开始按压预览”与“结束按压，恢复实时画面”动作。停用/改配置的插件失去当前按压资格，重新启用不能接管旧按压。
- 仅启用且声明相应输入能力的消费者接收帧、姿态、按压、生命周期、权限或 PCM。`Started/ConfigurationChanged/Stopped` 是每个消费者必须处理的控制事件。
- 音频采集由 #66 实现：宿主已有 `hasSubscribers(AUDIO_PCM/AUDIO_PERMISSION)`、`snapshot.session`、`permissionChanged(sessionId, permission)`、`audio(sessionId, PcmBlock)`。采集/权限请求保留启动时的 session，迟到回调不能借新会话投递。`PcmBlock` 用独立拥有的只读 ByteBuffer，每个读取者有独立 position/limit，声明采样率、1/2 声道、`PCM_16_LE` / `PCM_FLOAT_LE`、时间；一次最多 1 MB。授予权限且会话前台才转发 PCM；停止后重置权限，下一前台必须重查。权限变化使音频消费者 token 更新并清除旧文字，消费者从 `AudioPermissionChanged` 获取新 context，撤权后旧识别结果不可发布。系统 ASR 自占麦克风与共享 PCM 互斥时用 `MICROPHONE_BUSY` 报告，麦克风控制器必须订阅配置/生命周期，在停用/撤权/离开时真正停止采集。这里只有事件协议，没有申请录音权限或启动麦克风。

## 输出和坐标

`PluginOutput` 一次替换该插件的完整输出槽，包含时间戳、`PluginMessage(TEXT/STATUS/ERROR)`、`PluginLayer` 和可选 `PreviewReplacement`。文字按时间更新，与图像帧独立；小于已有输出时间的结果被拒绝。每插件最多 8 条 × 240 字、4 图层 × 128 线段，关闭/会话结束清空。错误被归到该插件，基础相机的错误区域只显示权限/绑定/取帧问题。

合成：基础图片 → 有效替换图片 → 来源兼容的基础骨架/插件图层 → 监控 → 插件文字。图层按稳定 ID 排序。`ImageSource` 的会话、帧号、旋转、图像尺寸、真实照片区域必须**全部相等**才可叠加。没有用“最接近时间”猜测旧框属于新帧。`ImageSpace` 使用完整预览坐标（含 letterbox）；图像图层可有不同分辨率，但宽高比必须一致，线条以 source 像素为单位。

替换者必须声明 `REPLACEMENT` + `POINTER` 并交回同一次按压 token。高 `replacementPriority` 获胜；同优先级按稳定 ID 升序，失败方显示冲突提示。同插件新输出应携带需保留的替换图；发仅文字输出会主动撤回其旧替换。

慢超分允许显示按压期间取得的旧来源帧快照，不要求等于最新帧。**输出必须是完整来源帧合成图**：ROI 增强先贴回那一帧的原图，不能把旧 ROI 贴到宿主当前帧。宿主继续采集最新原画，释放/取消立刻切回最新帧；晚到替换被拒绝。与旧快照不同源的骨架/框隐藏，而不是错位展示。

### 按帧坐标核对

1. 640×480 源顺时针 90°，640 方形预览的真实照片范围应为 `(80,0)-(560,640)`；0° 为 `(0,80)-(640,560)`。
2. 300×600 视口、640×640 图像使用 Crop：scale=0.9375、left=-150、top=0，图像中心 `(320,320)` 映射至 `(150,300)`。用 `ViewportMapping.toPreview/toImage` 双向核对，点击灰色 padding 不算按压。
3. 同帧图层、姿态关键点与完整增强图应映射到相同坐标；改帧号/旋转/真实照片区域后旧图层立即不可见。按压期间让增强延迟数帧返回：显示完整旧帧并隐藏不兼容图层，松手恢复最新原图。

## 资源与恢复

`CameraSession` 是唯一 CameraX 所有者，只绑定一个 `ImageAnalysis`，保留 `KEEP_ONLY_LATEST`。关闭在 UI 同步 clearAnalyzer / unbind **自己的** use case，provider future 迟到不会再绑定。proxy 保持到主线程收到显示交付后才 close；analysis executor 正常 shutdown，不 interrupt 仍借用 Bitmap 的推理。配置更改不重新绑定 CameraX。

`PoseAnalyzer.preview()` 是不进入模型加载锁的纯 bitmap 路径：零模型、正在加载、配置失败或模型加载超时均继续预览。模型失败停止该 runner 后续推理直到配置/会话改变。`AsyncModelSlot` 先撤销旧借用资格，排空在用模型再同线程 close/open；迟到打开的旧模型同线程关闭。原有 `PoseAnalyzer` 的 GPU 创建/运行/关闭线程与探测超时机制保留。原生驱动永久卡住不能安全强杀；已有超时锁避免反复打开姿态后端，预览不依赖该锁。

配置来源：`CameraModelSettingsStore` 监听旧 `app_settings` 的模型/后端变更，`ConfiguredModelStore.revision` 在成功安装/原子保存配置/删除后更新；`cameraPluginSettings()` 合并依赖，重载同 ID 模型。`PoseAnalyzer.open(stillNeeded=...)` 在获取编译锁后再次判废，避免切页时排队打开已无消费者的旧请求。

## 验证与设备清单

JVM 覆盖 settings restore/observable/save、关闭/代次/会话失效、有限输出和失败隔离、音频订阅权限、慢替换/冲突/取消/晚到结果、严格坐标匹配、异步打开/配置合并/同线程关闭/停用排空、姿态边界与线开关。既有姿态/关键点/测量、模型解析、权限、帧率测试保留。

完整命令（JDK 21、Android SDK）：

```bash
./gradlew assembleDebug assembleRelease lintDebug testDebugUnitTest --build-cache --parallel --console=plain
```

以下必须在真机执行，JVM 测试不能替代 Bitmap / CameraX / GPU / Compose 手势证据：

- [ ] 点击卡片只开弹层，点击总开关只启停；关闭时修改身高/两种线并保存；重启恢复，TalkBack 区分两个点击目标。
- [ ] 增强关闭后测量线、人体文字、限制说明消失，基础骨架及用户启用模型列表保持原设置。
- [ ] 禁用所有姿态与图片模型仍持续预览；加载慢、损坏配置、模型失败时仍有新相机帧，错误归属正确。
- [ ] 连续切相机/插件/设置 20 次，前后台 20 次；包括模型加载过程中离开/回来；检查无重复绑定、黑屏、use case 泄漏或后台推理。
- [ ] 更新同 ID 工作台 `.properties` 并保存，配置自动重载；快速启停/切配置，旧结果不恢复。
- [ ] 真机 0/90/180/270 旋转、不同视口比例核对上述坐标；增强替换多帧延迟后松手、滑出、多指、系统取消、切页、后台、停用均恢复最新原图。
- [ ] 监控内存、CPU/GPU 线程：展示中的 Bitmap 没有提前 recycle，离开后模型/Interpreter/GPU 同线程排空释放；release/R8 APK 能安装并完成相同流程。
- [ ] #66 接入后：录音拒绝/撤权/隐私静音、系统 ASR 麦克风互斥、前后台与停用释放；未经录音权限时视频仍可用。
- [ ] #68 场景插件：四个卡片分别启用后下载/加载对应示例模型，关键点、检测框、分割掩码与相机预览对齐；阈值修改只影响相机叠加；停用/切页/后台停止推理且不留下旧图层。
- [ ] #68 声音识别：授予麦克风后约 1 秒窗口持续更新声音/环境声前几类；拒绝/撤权/系统 ASR 占用时停用音频插件但相机继续；启停/切页不残留旧结果，工作台 WAV 路径仍可运行。
- [ ] #68 语音朗读：系统 TTS 引擎就绪/缺失中文语音包提示正确；只在按下朗读时发声，重复点按不叠加；关闭弹层、切后台、停用插件立即停止，不与声音识别形成回环。

相机卡片交互和实际替换消费者接入后的设备演示仍待执行；这里的 JVM 仲裁测试使用假图片尺寸，不声称跑过相机或超分模型。Bitmap 借用只读通过应用内约定保证，并非运行时沙箱；原生模型推理若永久阻塞，无法安全强制回收它，需设备验证和驱动恢复。当前兼容图片模型按帧同步执行，慢模型会降低基础帧率；异步超分应使用有界 worker。
