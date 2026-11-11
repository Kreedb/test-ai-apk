# 插件宿主接入（#64）

## Model Zoo 场景接入

`LiteRtScenes` 将固定目录的 34 个 task ID 路由到六个统一开关/配置卡片：画面分析、画面增强与分区、实时英语字幕、神经语音朗读、录音处理与试听、文本决策。`LiteRtCatalogIntegrationTest` 对比目录与实际算法工厂，LM 的独立文本插件不计入这 34 项。文件就绪不代表模型已经完成推理。

- 视觉、图片、文本、Matcha 和录音处理首次显式操作才加载；启用不自动下载模型。缺失文件提示进入“自定义模型 → 从远程下载模型”，配置显示大小、来源、许可及安装状态。
- `LiteRtAssets` 在模型 owner 线程取得 `AssetResourceStore` lease，所有原生句柄、tokenizer、映射表关闭后才释放；声明 `MODEL_ASSETS` 的插件在 `host.configure(settings, resourcesRevision)` 收到资源写入开始/结束代次后失效并排空。保持原模型目录和用户文件。
- 运行前读取 `ActivityManager.MemoryInfo.availMem/lowMemory`。系统低内存时拒绝新加载；RAM++ 的门槛为 **3.50 GiB 当前可用内存**：依据已执行 Linux CPU 参考管线峰值 **3,090,028 KiB**，向上取整至 3 GiB 加 512 MiB 余量。来源：协调验证记录 `vision-fixtures/image-tagging-pipeline.json`；这是保守准入门槛，**不是 Android/GPU 内存测量或成功保证**。无法读取 RAM++ 所需内存信息时拒绝加载，并报告 need/have。其他任务没有伪造的逐模型内存估算。
- `LiteRtWork` 复用 `AsyncModelSlot` 和 `BoundedFrameWorker`，最多一个执行中和一个最新等待请求；本地请求代次独立于配置/会话代次。取消立即判废发布，原生调用返回后同线程释放，不强杀驱动。
- 人脸框是实际送入模型的 ROI：头部朝向填满黄色框；活体模型的青框用于放脸，黄色框提供 2.7× 上下文。XFeat 先捕获参考，primary=参考、secondary=当前。慢结果在独立来源快照报告显示，不叠加到新帧。EDSR/Real-ESRGAN 整幅快照长边限制 256，替换只在同一次按压 token 有效时显示，释放/取消后迟到结果不能恢复替换。
- 英语字幕用 2–8 秒共享 16 kHz PCM 窗口，慢推理会跳过旧待处理窗口，不声称原生连续流式听写。录音处理显式录制 2–10 秒，各模型内部重采样；结果音轨可逐项试听。Matcha 仅英语短句，过长音素序列由引擎明确拒绝。播放与旧系统 TTS 共用音频焦点并暂停共享采集，停用/后台/取消停止播放。原始录音不落盘。
- 单次录音在系统静音/竞争时丢弃已收集 PCM，但保留本次用户请求的恢复观察需求；恢复授权代次后重新采集完整窗口。墙钟上限为所选录音时长加 10 秒（最多 20 秒），静音/恢复事件不延长它；超时、取消、播放、停用或后台均结束需求。超时后可在同一相机会话显式点击“重试麦克风并录音”，重新观察当前系统状态；没有自动反复重开，也不绕过采集方原生读失败锁存。

### 通用操作、静态报告与测量

- `PluginDescriptor.actions` 白名单 + `ACTIONS` 能力，输出 `PluginAction(id,label,enabled)`，宿主 `action()` 才投递 `PluginEvent.Action`；配置弹层通过 `PluginSettingsStore.requestAction` 排队一次显式请求，绑定保存代次，进程重启不恢复请求。
- `REPORT_IMAGE` 输出 `PluginImageReport(source,input,result,label)`；最多一对、每图最多 4,194,304 像素。`source=null` 是明确标注的文件/生成图片，不能作为相机图层或替换。相机来源仍检查会话/帧号；关闭、改配置、离开立即清空。发布后的 Bitmap 不再改写或 recycle；算法自有输出在复制后释放，输入别名除外。
- `GUIDE` 输出相对真实照片的 `PhotoRoi`，可另带 `focusRoi`。这表示请求裁剪位置，不冒充人脸检测结果。
- `PluginOutput.captureAudio` 表示当下采集需求；有界录音插件声明 `continuousAudio=false` 避免初始误采集。字幕需求来自用户 listening 意图，系统 silencing 时仍让唯一前台 recorder 观察恢复；后台、停用、播放仍停止采集。`HostSnapshot.audioPermission` 显示采集方真实状态。
- 麦克风权限提示同样跟随 `captureAudio` / `continuousAudio` 意图，而不是仅检查能力声明。自定义 IMAGE/TEXT/file 模型不触发录音提示，发现显式 raw PCM 契约后才请求；`HostSnapshot.audioPermissionRequested` 让相机订阅异步发现/操作后的需求变化。单次录音在未授权时仍可点击操作并发布有界授权意图，真实授予后才收 PCM；拒绝、超时或取消清除意图，迟到授权不会重新开始已取消录音。授权/撤权事件继续发送给所有启用的权限订阅者。
- `FrameCompleted(frame,timing,asyncResults)` 在同步帧消费者返回后投递监控：`totalMs` 从帧进入宿主、转换/姿态开始至同步分发完成，`dispatchMs` 仅 acceptFrame/同步消费者墙钟时间，均不包括后续显示合成或异步模型。`PluginOutput.execution` 单独记录最近完成的异步耗时、后端，贡献携带完成时间/可选来源；不相加成“全部帧耗时”，也不取倒数冒充 FPS。

新增 JVM 回归覆盖精确目录路由、按请求取消/合并/线程排空、资源代次、动作白名单、文件/相机快照隔离、PCM 需求与播放、RAM++ 阈值及文本参数。Android 原生/Compose 操作、GPU/NPU、吞吐和设备内存仍由协调者运行检查，不能用下载或 host 数值参考替代。

公共接入点在 `app/src/main/java/com/example/minimalapk/plugins/`，均为应用模块内的 `internal` 接口。它们是内置插件的最小宿主，不是外部安装/脚本 SDK。

## 下游接入步骤

1. 在 `PluginIds` 添加稳定 ID、在 `pluginDefaults` 添加默认配置；新下载模型、音频消费者默认停用。不要用中文名称或 tab 索引作 ID。
2. 实现 `CameraPlugin`，声明实际需要的 `PluginCapability`，加入 `CameraScreen.kt` 的 `PluginHost(listOf(...))`。复用 `PluginsScreen.kt` 的 `PluginCard` 和 `PluginSwitchRow`；卡片配置区域与总开关是兄弟点击区域，停用仍能打开设置弹层。
3. 设置通过 `PluginSettingsStore.setEnabled/configure/update` 保存到 `app_settings` 的 `plugin.<id>.enabled/revision/config.<key>`，通过 `settings: StateFlow` 订阅。保存配置不改变开关，开关不改配置。旧 `pose_models`、`pose_backend`、主题及用户模型文件保持原格式。
4. 在 `Started` / `ConfigurationChanged` 更新消费者，在 `Stopped` 停止排队并释放资源。每次回调带 `PluginContext(token, settings)`：异步任务保留它，工作前调用 `isCurrent()`，完成后只用该 context 的 `publish()`。不能用任务结束时取到的新 context 包装旧结果。
5. 模型复用现有 `ModelConfig` / `ConfiguredModelStore` / `LiveConfiguredModel`，不要重建张量解析器。`AsyncModelSlot` 在同一个专用线程创建/运行/关闭，一个在用模型、一个合并的待加载配置；`runIfReady()` 在加载中立即返回 null。慢的图片/音频消费者可用 `BoundedFrameWorker`（一项执行中 + 一项最新待处理）；禁用、换配置、取消按压时 `clear()`，宿主最终 `CameraPlugin.close()` 时关闭 worker，前后台 `Stopped` 后仍可再次 `Started`。工作函数需处理失败并报告带 ID 的错误。

`PoseModelsPlugin` 持有唯一 `PoseAnalyzer`，由自定义模型或姿态增强需要时启动，二者均关闭时不再运行或发布状态；增强仍共享已有推理、不改写模型列表。`ConfiguredModelPlugin` 逐个运行所有已启用的通用图片模型，单模型失败隔离；开关保存在 `configured_models/<sha>.enabled`，旧 `plugin.configured_model.config.model_id` 在启动时幂等迁移，原配置和权重不移动。配置入口统一为 `ModelsScreen` 的每个模型卡片，工作台页面与单选器已删除。

`SystemMonitorPlugin` 输出右上角指标与底部显式 NPU 诊断。四个 `ExampleScenePlugin` 保留稳定 ID，分别提供抬臂计次、物品识别、物品定位与计数、区域覆盖分析；固定模型契约与通用模型的用户配置独立。`AudioClassificationPlugin` 使用共享 PCM，授权代次更新时重新加载，撤权释放窗口与模型；TTS 仅由配置弹层显式朗读，监听播放完成并保存引擎选择。`PersonalizationPlugin` 只读本机分类头存档，训练页负责命名、样本收集与原子保存。设置旧 `MODELS` 页面恢复到根页，旧示例 tab 索引恢复到主页。

## 事件与线程

- 控制事件（启停、配置、生命周期、按压）来自相机 UI 所有者；帧事件来自唯一 CameraX analysis worker。两者可并发，插件控制回调必须短，不在 UI 等待模型结束。宿主不在输出锁内调用插件。
- UI 控制器从主线程调用 `configure/lifecycle/press/release/cancel/permissionChanged`；音频采集线程可调用 `audio`，异步结果可从任意线程 `publish`。不要在帧回调里重入控制方法。停止事件携带失效 token 用于释放资源，清理不能以 `isCurrent()` 为前置条件；新会话的资源用 token 区分。
- `PluginToken` = 插件 ID + 会话号 + 配置代次。后台/离开/停止立即使 token 无效、清空帧/图层/文字；下次前台生成新会话。更改配置也增加代次并清空该插件结果。基础姿态配置变化同时使测量消费者失效。
- `CameraFrame` 含会话/帧号、`System.nanoTime()` 时间、原始 Bitmap 尺寸和旋转、朝上的分析图、真实照片 `PhotoBounds`、`ViewportMapping` 及共享姿态结果。`rawMapping.toImage/toRaw` 使用与 `PoseAnalyzer` 一致的旋转/缩放/letterbox 舍入中心，原始像素与模型坐标互转。UI 通过 `CameraFramePreview` 直接绘制高分辨率 `raw`，不显示降采样的模型输入；图层、姿态与手势仍使用同一坐标映射。时间戳是宿主收到帧的单调时间，**不是传感器曝光时间**。
- 图像借用为只读。保留 `PluginImage` / `CameraFrame` 引用即可跨回调保活；宿主不会 recycle 已发布图片。禁止修改其 `bitmap`。需要绘制/写入时 `copyBitmap()`，自己拥有并在不再使用时释放该副本。发布图片后也不可 recycle/原地复用，交由 GC 在 Compose 和所有消费者释放引用后回收。不得保留无界帧历史。
- `Press` 不等长按阈值，包含 pointer ID、预览坐标、单调时间和独立 `PressToken`。释放、出真实照片/视口边界、第二根手指、系统取消、视口改变、切页、后台、停止都会撤回替换。多指后要全松开再按；TalkBack 提供“开始按压预览”与“结束按压，恢复实时画面”动作。停用/改配置的插件失去当前按压资格，重新启用不能接管旧按压。
- 仅启用且声明相应输入能力的消费者接收帧、姿态、按压、生命周期、权限或 PCM。`Started/ConfigurationChanged/Stopped` 是每个消费者必须处理的控制事件。
- 音频采集由 `AudioCaptureHost` 实现：它持有唯一的 `AudioRecord`（16 kHz 单声道 PCM-16-LE，40 ms 分块），`CameraScreen` 只在启用音频插件时请求 `RECORD_AUDIO`。宿主提供 `hasSubscribers(AUDIO_PCM/AUDIO_PERMISSION)`、`requiresAudioPermission()`、`snapshot.session`、`permissionChanged(sessionId, permission)`、`audio(sessionId, PcmBlock)`。采集/权限请求保留启动时的 session，迟到回调不能借新会话投递。`PcmBlock` 用独立拥有的只读 ByteBuffer，每个读取者有独立 position/limit，声明采样率、1/2 声道、`PCM_16_LE` / `PCM_FLOAT_LE`、时间；一次最多 1 MB。仅在授予权限、会话前台且有音频订阅者时启动采集并转发 PCM；停止/后台/撤权/停用最后一个音频插件都会停止采集，下一前台必须重查权限。权限变化使音频消费者 token 更新并清除旧文字，消费者从 `AudioPermissionChanged` 获取新 context，撤权后旧识别结果不可发布。系统 ASR 自占麦克风与共享 PCM 互斥时用 `MICROPHONE_BUSY` 报告，麦克风控制器必须订阅配置/生命周期，在停用/撤权/离开时真正停止采集。

## 输出和坐标

`PluginOutput` 一次替换该插件的完整输出槽，包含时间戳、`PluginMessage(TEXT/STATUS/ERROR)`、`PluginLayer` 和可选 `PreviewReplacement`。文字按时间更新，与图像帧独立；小于已有输出时间的结果被拒绝。每插件最多 8 条 × 240 字、4 图层 × 128 线段，关闭/会话结束清空。错误被归到该插件，基础相机的错误区域只显示权限/绑定/取帧问题。

`MONITOR` 插件的文字独立于帧号归入右上角面板，不需要空图层占位；它的像素图层仍严格检查来源。`TEXT` 指标由宿主排除出底部 `reports`，`STATUS/ERROR` 诊断仍可复制。默认刷新间隔 250 ms，保留用户保存的其它间隔；最多 8 条时合并预处理/后处理行，确保 NPU 指标不会被截掉。

合成：基础图片 → 有效替换图片 → 来源兼容的基础骨架/插件图层 → 监控 → 插件文字。图层按稳定 ID 排序。`ImageSource` 的会话、帧号、旋转、图像尺寸、真实照片区域必须**全部相等**才可叠加。没有用“最接近时间”猜测旧框属于新帧。`ImageSpace` 使用完整预览坐标（含 letterbox）；图像图层可有不同分辨率，但宽高比必须一致，线条以 source 像素为单位。

替换者必须声明 `REPLACEMENT` + `POINTER` 并交回同一次按压 token。高 `replacementPriority` 获胜；同优先级按稳定 ID 升序，失败方显示冲突提示。同插件新输出应携带需保留的替换图；发仅文字输出会主动撤回其旧替换。

超分显示按压时取得的完整来源帧快照，不要求等于最新帧。整张照片按比例转换到受限工作分辨率，带上下文的 50×50 输入分块经 ESRGAN 输出 4× 图，再按同一来源坐标拼接；处理预算、进度与分辨率显式报告。宿主继续采集最新原画，释放/取消立刻切回最新帧；晚到替换被拒绝。与快照不同源的骨架/框隐藏，不能把旧块贴到当前帧。

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
- [ ] 点击模型卡片更新同 ID `.properties` 并保存，配置自动重载；快速启停/切配置，旧结果不恢复。
- [ ] 真机 0/90/180/270 旋转、不同视口比例核对上述坐标；增强替换多帧延迟后松手、滑出、多指、系统取消、切页、后台、停用均恢复最新原图。
- [ ] 监控内存、CPU/GPU 线程：展示中的 Bitmap 没有提前 recycle，离开后模型/Interpreter/GPU 同线程排空释放；release/R8 APK 能安装并完成相同流程。
- [ ] #68 音频：录音拒绝/撤权/隐私静音、系统 ASR 麦克风互斥、前后台与停用释放；未经录音权限时视频仍可用。
- [ ] #68 场景插件：四个卡片分别启用后下载/加载对应示例模型，关键点、检测框、分割掩码与相机预览对齐；阈值修改只影响相机叠加；停用/切页/后台停止推理且不留下旧图层。
- [ ] #68 声音识别：授权后约 1 秒窗口持续更新类别；拒绝/撤权时停用采集但相机继续，再授权可重启；已缓存模型升级后标签可读，模型卡片内 WAV 测试可运行。
- [ ] #68 语音朗读：系统 TTS 引擎就绪/缺失中文语音包提示正确；只在按下朗读时发声，重复点按不叠加；关闭弹层、切后台、停用插件立即停止，不与声音识别形成回环。
- [ ] #68 设备端学习：4 类各至少 5 张不同照片后训练保存；重启恢复名称/阈值；低分显示未知。旧存档的类别先保持未命名，确认映射后可升级保存；相机不改写存档，训练失败保留旧分类器。
- [ ] #68 导航：升级后旧保存索引 0/1/3/4 分别落到主页/相机/插件/设置，旧示例索引 2 与越界值落到主页；四个 tab 均保留自身滚动/子页面状态，示例页与旧工作台入口不可达。

相机卡片交互和实际替换消费者接入后的设备演示仍待执行；这里的 JVM 仲裁测试使用假图片尺寸，不声称跑过相机或超分模型。Bitmap 借用只读通过应用内约定保证，并非运行时沙箱；原生模型推理若永久阻塞，无法安全强制回收它，需设备验证和驱动恢复。当前兼容图片模型按帧同步执行，慢模型会降低基础帧率；异步超分应使用有界 worker。
