# #68 八个既有场景、录音与整幅超分验收

## 范围与本轮证据

基线：`main@427c8b8`，包含 #77–#80；以 #63 后续真机反馈为准，超分覆盖整幅照片，不采用中心 ROI。系统 TTS 和 YAMNet 声音分类不是 ASR，也不计作上游 LiteRT 神经语音例程验收。

场景/音频/超分/学习/TTS 52 项与导航恢复 7 项专项 JUnit 通过；最终整合回归为 **622 项、0 失败、14 跳过**。API24、API35 首轮各 **15 项**原生回归通过；最终 API24 **21 项组合回归全部通过**，包含整幅渲染与学习输入。API35 七个真实模型的 **5 项场景测试全部通过**。最终构建与独立复审证据统一见 [本轮三项验收](issue-63-three-issues.md)。

独立下载核对 YAMNet：4,126,810 字节，SHA-256 `10c95ea3eb9a7bb4cb8bddf6feb023250381008177ac162ce169694d05c317de`；直接读取 FlatBuffer 确认 FLOAT32 `[15600] → [1,521]`。固定 16 kHz、0.975 秒原始单声道波形；标签来自同一个已校验模型的尾部 ZIP。

## 真实缺口与最小修复

| 根因 | 修复 | 关键回归 |
| --- | --- | --- |
| 超分按下期间模型未就绪，`runIfReady` 返回空后丢失整次请求 | 暂存至多一个按压 token + 原来源帧；就绪回调仅启动仍有效的请求。取消、配置/会话变化清空；不因加载重建每次触摸的解释器 | 原生 `SuperResolutionRenderingTest`：加载中按住、每种 cancel、关闭替换、新按压及晚到 native tile |
| 原生超分异常经模型加载回调上报，无法区分旧按压；旧调用可能污染下一次按压/恢复状态 | 在推理请求内部捕获并按该按压发布错误；未完成合成只回收私有 bitmap，源图保持不变 | 同上：迟到失败不盖回已恢复文字，新按压仍可处理 |
| 整幅合成只有纯几何覆盖测试，缺实际 Canvas/Bitmap 证据入口 | 抽出原来的整幅合成函数；保留相同分块/halo/预算/源图映射。注入 seam 只替换固定 50→200 tile 算子，实际宿主/线程/Canvas 均走生产路径 | 原生确定性四角、每像素、接缝、留边、0/90/180/270 度、源 bitmap 所有权；另有真实 ESRGAN 4 块测试 |
| 内置 YAMNet 读取用户可编辑通用模型的 shape/sampleRate/signature/labels | 固定场景直接读已校验缓存文件，验证原生 15600→521 FLOAT32 契约，复用输入/输出缓冲；不改通用模型 sidecar | 原生 `yamnetSilenceHasCorrectLabelAndIgnoresBrokenEditableSidecar` |
| 声音入口仅有麦克风，WAV 保留路径需绕到通用模型 | 声音配置新增 WAV 来源；显式相机“识别/取消”动作，16 MB 读取上限，复用既有 PCM16 WAV 解码，开头 0.975 秒，短文件补零；WAV 模式不发布录音需求 | JVM：WAV 重采样/双声道/截断/短文件；真实插件无权限需求、切回麦克风与拒绝权限 |
| TTS 暂停不改 host token，声音结果检查与发布之间可插入清理；旧模型就绪亦可覆盖播放暂停 | 本地代次同时用于模型请求/就绪/失败，检查与发布在同一插件锁中；暂停清窗口，恢复用新样本 | JVM：阻塞真实插件 runner，播放暂停→迟到分类→恢复，确认旧标签/部分窗口不回流 |
| 相机 TTS 只显示静态引导，无显式朗读/停止与回调状态 | 复用已有 `TtsEngine`，相机只在点击时朗读已保存用户文本；完成/失败/停止撤回当前内容，切引擎/后台/停用关闭绑定。保持无录音能力声明及临时焦点半双工 | JVM `TtsPluginTest` + 既有 `TtsPlaybackTest`，覆写引擎 seam 仅用于乱序回调/关闭证明 |
| MoveNet 计次显示关键点但未显示其真实得分 | 保留完整下→上→下稳定周期；点保留置信度，报告计次所需 6 点可见数/最低模型得分 | JVM `ExampleSceneSemanticsTest`；原生固定人像关键点与静态不计次 |
| 分割无可调显示效果；分类只展示当帧候选，用户无法查已知标签范围 | 保留 logits argmax，不引入伪概率阈值；新增蒙版透明度和缓存模型标签搜索，20 项可见上限 | JVM 透明度有效范围/旧阈值兼容；既有 logits/面积测试 |
| 学习相机输入来自其它模型决定的小尺寸分析图，和训练照片清晰度不一致 | 从原始相机图生成最长 512px 的正向整图，再交给原有中心裁切/特征提取，保持 checkpoint/名称/优化器不变 | 原生 `PersonalizationInputTest`；既有 v1/v2 原子 checkpoint/命名/失败保留测试 |
| tab 选中索引已迁移，但页面状态数字键仍会把旧示例=2/插件=3 的临时表单交给新插件/设置页 | 页面状态改用 `page:v2:home/camera/plugins/settings` 稳定键；保留既有版本化 tab 选择恢复和同页切回状态 | JVM `TabIndexTest`：旧数字键隔离、同页新键恢复和所有目的地唯一性 |

录音宿主/Gate 已有的单所有者、silenced/恢复排空、负数读取失败、权限代次和后台释放本轮未重写；相应 JVM 真实 gate→host 回归包含在 52 项中。完整动作计数、SSD 同类去重与位置、DeepLab 保比例 argmax、训练候选头/原子保存等既有实现保留。

## 八项端到端验收表

固定输入与模拟器证据不等于物理场景准确率及持续性能验收。

| 场景 | 配置 → 真实输入 → 输出 | 关闭/资源 | 本轮状态 |
| --- | --- | --- | --- |
| 抬臂计次（MoveNet） | 阈值→单人双肩/腕/髋→关键点/最低模型得分/稳定完整次数 | 配置/重新启用清次数，低置信或断帧暂停，关闭解释器/自身图层文字 | JVM 完整周期通过；原生人像双肩可见、静态照片不计次；真人动作待验 |
| 物品识别（MobileNet） | 查固定 ImageNet 标签→单物品整图→真实名称/模型得分 Top-5 | 来源代次隔离；停止释放 runner，缓存保留 | 原生 Grace Hopper Top-1 `military uniform` 0.6328；实物准确率待验 |
| 物品定位与计数（SSD） | 得分阈值/COCO 标签→桌面/房间→框/同类去重数量/九宫格位置 | 同源图层，下一帧不贴旧框；关闭撤回/释放 | 原生同图输出 `person` 0.7188、`tie` 0.5742；实景待验 |
| 区域覆盖分析（DeepLab） | 蒙版透明度/21 类→人物/宠物/家具→同色区域/类别面积/透明背景 | 严格来源帧，关闭只撤回自己；模型释放 | 原生 `person` 62.76%，面积排除模型留边；实景待验 |
| 声音识别（YAMNet） | 麦克风或 WAV、Top-K→共享 16 kHz PCM / 用户文件→521 类声音标签 | 静音/播放清窗口/旧结果；后台释放；WAV 原始文件不复制 | 原生静音+坏 sidecar 通过；实际 SAF→WAV→相机 `Silence` 80.1%，隐私静音/恢复/后台释放通过 |
| 超分辨率（ESRGAN） | 替换画面开关→按下的整幅源图→有界分块 4× 快照/进度/耗时，松手回最新原图 | press/context/session/frame 门控，取消立即撤回替换；原生单块结束后退场 | 双 API Canvas/四旋转/竞态 6 项通过，实际 ESRGAN 4 块通过；手机速度/画质/内存待验 |
| 设备端学习 | 4 类命名、每类 ≥5 不重复照片、未知阈值→特征/候选头训练→原子保存→相机命名识别 | 退出取消候选训练、已开始原子保存完成；相机仅读存档；重置仍需明确用户操作 | 双 API 原始输入测试通过；原生特征→训练→命名存档精确恢复通过；实物泛化待验 |
| 系统 TTS | 文本/已安装引擎→配置页或相机显式朗读→当前内容/完成或错误/停止 | 不申请录音；临时焦点使共享采集暂停；后台/切引擎/停用关闭绑定，迟到回调隔离 | JVM 动作/乱序回调通过；API35 如实显示缺少语音服务，未冒充朗读成功；中文包/播放待真机 |

## 入口与升级

插件卡片的总开关独立于点击配置。`PluginsScreen` 已接入新的场景/声音配置：保存完整输入来源、文件 URI、Top-K、阈值及透明度；声音卡片明确显示共享麦克风或 WAV 来源。相机 TTS 复用设备 Context 和已有引擎封装，配置完成后可直接在相机中显式朗读/停止。

最终四 tab 为主页、相机、插件、设置。原有 `v2:` 选中位置恢复继续有效；页面临时表单采用独立版本化名称，不再认领旧示例页数字键，也不影响当前同一 tab 切回时的状态。代码搜索无旧“示例 → …”引导。下载模型、插件配置、旧/新训练 checkpoint 的路径和存储格式均保留。

## 调用方核对

对本轮所有改动函数以 `grep` 检索生产/单测/Android 调用：

- `AudioClassificationRunner`：`AudioClassificationPlugin` 固定缓存路径、`ModelSelfCheck.checkAudioPipeline`（保留 `ConfiguredModel` 构造兼容）、新增原生测试。`classify` 由音频 worker/自检/原生测试调用；`yamnetWavWindow` 由文件 worker 与 WAV 单测调用。
- `AudioClassificationPlugin` 的 `restart/updatePermission/stop/onAudio/classify/report/actions/startFile/current` 均由其事件分派/worker/模型回调闭环；外部构造方为 `CameraScreen`、`ExampleDownloadLifecycleTest`、`AudioClassificationPluginTest`。`audioUsesMicrophone` 同时用于插件/配置 UI，默认旧配置仍是麦克风。
- `SuperResolutionRunner.runTile`：单块 `run` 与 `superResolutionComposite`；单块 `run` 保留自检入口。`SuperResolutionPlugin` 的 `restart/startPress/onFrame/endPress/submitPress/modelReady/enhance/close` 由原事件/slot/worker驱动；外部构造方为 `CameraScreen`、下载生命周期与原生测试。
- `superResolutionComposite`：生产超分、确定性 Android 拼接测试、真实 ESRGAN 原生测试。seam 不暴露其它能力，保持一个模型 owner。
- `ExampleScenePlugin.renderScene`：原帧分派；`drawSceneMask`：仅分割分支；`moveNetPoints`：计次分支/语义单测/原生测试；`ScenePoint` 默认得分兼容既有几何测试；`sceneMaskOpacity`：场景/设置弹层/单测。
- `PersonalizationPlugin`：`CameraScreen` 和下载生命周期测试；`personalizationCameraImage`：插件与两个 Android 输入回归。`BottleneckExtractor`/`SoftmaxHead`/checkpoint 格式未改。
- `TtsEngine.speak/stop/close`：`TtsSettingsDialog` 与新相机插件，新增 `TtsSession` seam 只承接既有生命周期。`TtsPlugin.onEvent/bind/publish/release/close`：Host、相机构造及 JVM 乱序/后台测试。
- 新两个设置弹层：`PluginsScreen` 原场景/声音配置分支；`tabPageStateKey`：`MinimalApkApp` 的 `SaveableStateProvider` 与 `TabIndexTest`。

## 可复现检查

完整构建、静态检查与单测：

```sh
./gradlew assembleDebug assembleRelease lintDebug testDebugUnitTest assembleDebugAndroidTest --build-cache --parallel --console=plain
```

原生测试类：

1. `com.example.minimalapk.plugins.SuperResolutionRenderingTest`：6 项，真实 Bitmap/Canvas/Host/线程；测试 tile 为确定性算子，无模型下载。
2. `com.example.minimalapk.plugins.PersonalizationInputTest`：2 项，真实 Bitmap 输入/旋转/上限。
3. `com.example.minimalapk.examples.ExampleModelsSmokeTest`：5 项，实际 APK LiteRT CPU：YAMNet、MobileNet/SSD/DeepLab、MoveNet、ESRGAN、personalization。资源缺失显式 skip；资源存在但 hash/推理/语义不符必须失败。

将固定资源放入设备的 `/data/local/tmp/issue63-fixtures`，可执行：

```sh
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.minimalapk.plugins.SuperResolutionRenderingTest,com.example.minimalapk.plugins.PersonalizationInputTest,com.example.minimalapk.examples.ExampleModelsSmokeTest \
  -Pandroid.testInstrumentationRunnerArguments.exampleModels=/data/local/tmp/issue63-fixtures \
  -Pandroid.testInstrumentationRunnerArguments.examplePhoto=/data/local/tmp/issue63-fixtures/grace_hopper.bmp
```

BMP SHA-256 `c43444bf3b1b1e3080551ca194bd231da4b67f20fc91454502915a6b806b614f`；设备不支持 BMP 解码时使用同目录无损 PNG，SHA-256 `6c870cebe91ab94620210bbc54d578e841c56655922b2daf632aa1d809514913`。ESRGAN 原生测试记录 64×48→256×192 四块耗时、合成 bitmap 字节和采样进程 PSS，不将小图/软件模拟耗时称为手机完整分辨率性能。

[API24 15 项日志](https://uploads.pullfrog.com/agent-uploads/0b0c95ef-90e8-4687-ba6d-3c6ba4d91472/bg-06358bf7.log)、[API35 15 项日志](https://uploads.pullfrog.com/agent-uploads/6aadf870-872a-4631-8197-dd717d880587/bg-914baf88.log)、[真实模型 5 项日志](https://uploads.pullfrog.com/agent-uploads/c60889f1-d81a-4663-940c-fb12975d85a3/bg-430f94ca.log)、[实际输出](https://uploads.pullfrog.com/agent-uploads/0fb479ff-7fe8-44ca-a20b-9addc8c9cfa0/real-model-outputs.txt)。软件模拟 ESRGAN 四块耗时 **97.77 秒**、采样进程 PSS 最大 101431 KiB，超过生产 30 秒预算；这是算子/拼接正确性验证，不证明手机全幅交互时延合格。

API35 实际 UI：[麦克风隐私与前后台证据](https://uploads.pullfrog.com/agent-uploads/76424f15-bde7-46f3-836c-93e96a0be328/microphone-privacy-final.zip)显示唯一录音客户端，静音撤回、同会话恢复、后台释放及前台新会话；[SAF WAV 闭环](https://uploads.pullfrog.com/agent-uploads/3a31a457-68c6-434a-a797-b59b37bb338b/wav-ui-evidence.zip)显示不启动录音、显式分类并输出 Silence 80.1%；[TTS 配置与缺引擎提示](https://uploads.pullfrog.com/agent-uploads/bafb2f86-118a-447e-a8eb-16e5f21e061f/tts-ui-evidence.zip)未伪称播放通过。

全幅生产上限仍为最长 512 工作像素、至多 96 块、输出最长 2048、30 秒预算；1280×960 等实际相机来源需要手机记录工作图尺寸/实际块数/总耗时/峰值 PSS/热稳定。若模拟设备无法在预算内完成，保持实时原图及明确超时，不能算超分验收通过。

## 仍待物理设备/整项关闭条件

- 首次/暂拒/永久拒绝、系统设置返回/撤权、Android 12+ 隐私麦克风、API29+ `isClientSilenced` 撤回与恢复；拒绝音频同时视频仍工作、后台/离开/最后消费者停用释放。
- 相机中 TTS 与声音插件同开，朗读/停止/完成/焦点丢失后采集暂停/恢复；多引擎/中文包缺失/网络型引擎的真实状态。
- 超分按下不等长按、松手/滑出/多指/系统 cancel/后台/配置修改/下载中/慢推理/失败/无障碍开始结束全部恢复最新原图；物理 TalkBack 可操作性。
- 八项上述真实场景准确性、相机 viewport 四角一致、命名 checkpoint 升级/继续训练和保存失败、持续性能/内存。
- API35 基线授权后曾停在“正在启动相机”。JDWP 确认当时 Activity `mResumed=false`、host `active=false`；Home 后返回前台变为 active，首帧日志和画面恢复。属于授权后生命周期异常待归因，后续覆盖新代码复测；没有据此改动 `CameraSession`。

最终整项目检查、原生结果及相机/音频物理验收完成前，#68 的“整项完成/关闭”仍是待验；不能用局部单测或固定模型能够 invoke 代替端到端验收。
