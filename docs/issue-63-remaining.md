# #63 剩余场景接续矩阵

更新：2026-11-11。本文保存非 Zoo 场景的执行顺序、固定源码依据和真实限制；总状态、构建及设备证据由协调者维护的 [issue-63-continuation.md](issue-63-continuation.md) 汇总。

后续按“本次先处理 3 个”收敛的 #66/#67/#68，交付和实际验收见 [本轮三项验收](issue-63-three-issues.md)。本文的额外 LiteRT 模型范围继续保留，不因基础插件修复视为完成。

**本轮已实现全部 34 个 Model Zoo 引擎（#71：12，#72：9，#73：8，#74：5），不等于这些子任务完整关闭，也不等于手机/GPU/NPU/release 验证通过。父 #63 保持开放。** #64–68 的宿主、配置、升级和音频兼容、#70 的完整资源管理与运行时、#69 的总验收仍按各自证据交付；本文不替代它们。

## 状态与源码约定

- **待实现**：仍需算法移植、产物导出或宿主接线；没有复现外部失败，不称为阻塞。
- **已实现/待证据**：代码路径已有，仍需该模型固定输入、停止/再次运行、配置、关闭及实际设备证据。
- **条件限制**：只限制指定资源、后端或设备；下文逐项给解除条件，不能扩大成整个场景不可做。
- 上游统一固定为 [google-ai-edge/litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8][U]。`S/` = `samples/litert/`，`L/` = `samples/litert_lm/`，`M/` = `models/`。表中链接均指向固定版本；行号属于该版本。
- 模型 Hub 的独立 revision 与代码 revision 分开记录。下列资源大小/SHA 来自上游固定清单或 Hub LFS 元数据；可访问、hash 匹配和图能加载都不是场景输出正确性的证明。GB 为十进制，GiB/MiB/KiB 为二进制。

## 当前 LM 基线及内存纠正

当前已接入 **`litertlm-android:0.19.0`**、固定 Qwen2.5-Coder CPU 场景、专用目录/设置/取消、资源租约及 LM JNI release keep rules。`RuntimeReadiness` 已接受该精确配方，对其它 LM 配方报告“未验证”，不再报告依赖缺失。Laya choice/score/yes-no 算法及 UI 接线也已实现；后续补验收，不再重复开发。

应用依据：`litert/lm/{LocalTextRunner,LocalTextModels,LocalTextCompileCache,LocalTextReadiness}.kt`、`plugins/{LocalTextPlugin,LiteRtTextPlugin}.kt`、`models/RuntimeReadiness.kt`、`app/src/release/keepRules/litert-lm.keep`（Kotlin 路径根为 `app/src/main/java/com/example/minimalapk/`）。

### 已实测的缓存区别，不能恢复旧门槛

同一固定 Qwen 文件（资源 Q1），主机 `litert-lm 0.19.0` Python/C API、CPU 2 线程、context 1024、每请求 fresh Engine/Conversation、内存测量关闭 benchmark：

| 配置 | 实测 RSS（KiB） | 结论 |
| --- | --- | --- |
| `:nocache`，32 输出 token | 第一次生成 7,762,660，第三次 8,709,468；对应关闭 Engine 后 6,008,392 / 6,721,380 | 禁用 XNNPACK packed-weight sharing 会在多个签名间重复打包；原 2304 MiB 门槛无效。不能作为通用省内存策略。 |
| `:memory`，6 次长输入/256 输出 token | 最大记录 1,911,996；关闭后 525,960 → 676,260 → 676,832 → 683,760 → 683,768 → 683,848 | 主机上共享权重有效；这不是 Android 所有 kernel 的能力证明。 |
| 每请求新私有磁盘缓存，4 次同样长输入/256 输出 | 生成 1,684,400 / 1,869,280 / 1,920,348 / 1,958,496；最大记录 1,958,500；关闭后 525,836 / 684,560 / 735,460 / 742,392 | 当前采用此方案。每次 packed cache 为 874,644,712 字节，Engine 关闭后删除。 |

**API24/x86_64、kernel 3.10 的实际探针** `syscall(SYS_memfd_create=319, "LocalText XNNPACK probe", 0)` 返回 `fd=-1 errno=38 (ENOSYS)`。固定 XNNPACK 在匿名缓存失败时会记录错误并继续无权重共享；仅增加模拟器 RAM 或看到 `:memory` 参数不能证明安全。当前实现统一使用 `noBackupFilesDir/local_text_compile/request-UUID`，先检查写入/sync/空间，初始化后确认 `.xnnpack_cache`，关闭 Engine 后清理；不退回无缓存。fresh Engine/KV 隔离与同一 Engine 内共享打包权重并不冲突。

可长期复核的运行时来源：[LM0.19 WORKSPACE:6][LM-WORKSPACE]固定LiteRT `dcb657e8a537b2dc944e53e14c7eb43bcd9ededa`；其 [file_util.cc:142–149][LM-MEMFD]实际探测memfd syscall，[xnnpack_delegate.cc:687–739][LM-CACHE]包含“cache could neither be loaded … or saved”及“weight cache not enabled”继续路径。

当前准入为 **2560 MiB 可用内存 + 1536 MiB 可用临时存储**，另需模型安装/导入空间；内存门槛来自主机峰值向上取整至 2 GiB 再加 512 MiB，**不是 Android 实测保证**。每次重新检查，残留分配会减少下一次准入余量。API24 基础相机仍保留；原 2 GiB 模拟器不能通过此 LM 内存门槛。

这些数据保留自运行时执行器最新交接及原始记录：`qwen-lm-memory-repeated.json`、`qwen-lm-memory-max-input.json`、`qwen-lm-memory-private-cache.json`。原记录 SHA-256 分别为 `f389e675563bf7fc03174129f8802845a505a6ee1882f9fa8a5769c7d763f8fe`、`ac00647dbc59079145b1614298ebc5c5336c2e9f3f1cf2c2eab374aebe51dee9`、`89a12ea72bb0f01f69be2af269a9bc7c98b857f7cffb6489817446f5635cf07e`；本节保存必要测量条件和数值，不依赖临时目录继续存在。实现方另以最终私有缓存通过主机算术 `42`、代码文本、`Merci`、原生取消后 fresh `42`、16-token 限制等六项；不是 Android 验证。

解除设备待验证状态：使用真实固定资源，执行 `LocalTextNativeSmokeTest` 的生成→取消→fresh 生成→同进程既有姿态推理；无 `localTextModel` 参数的 skip 不算通过。另验实际 shrunk release、API24 基础启动与 arm64 推理/内存/后台释放。匿名缓存若以后启用，必须实际能力探测、失败关闭及对应设备峰值证明，不能按 API 等级猜测。

## 可复用边界与最小公共补充

| 当前接口 | 后续使用方式 |
| --- | --- |
| `AssetResourceStore.acquire(set): AssetLease`；`AssetInstaller`；`LocalTextModels.assetSets(context)` | 同一 `filesDir/model_assets/<id>` 资源入口。租约保持到原生图、映射表、懒读文件全部关闭；完整资源固定 revision/大小/hash。Omni 等另计抽取图与编译临时空间。 |
| `VisionTasks.create(...)` / `ImagingTasks.create(...)` → `SingleImageEngine.run(ImageTaskRequest)` | 复用已实现图像算法；新增有类型的 prompt/ROI/点选请求。PP-OCR 的 `metrics["lines"]` 已含文字、置信度、source 像素框和旋转，给 OCR Check 暴露类型化区域，不能从画过字的图反读候选。 |
| `ZipformerEngine(..., modelName).transcribe(AudioTaskRequest)`；`MatchaEngine.synthesize(...)`；`AudioTasks.create(...)` | 复用 PCM、格式/窗口、取消及进度。Zipformer medium 已有参数化引擎分支，仍需独立资源选项和输出证据。 |
| `TextDecisionTasks`；`LayaDecisionEngine.decide(text, LayaQuestion)` | Laya 已有真实 score/noul 通道及配置接线，继续数值/交互验收；GLiNER 实体抽取是另一配方。 |
| `PluginEvent.Action`、`PluginContext.currentFrame()`、`PluginImageReport`、`PhotoRoi` | 按需捕获输入快照并在相机查看结果，避免重图每帧排队。生成图/导入文档需明确来源类型，不能伪造摄像头来源。 |
| `LiteRtWork.submit/publishIf/cancel`、`PluginToken` | 保留请求、会话、配置代次。`cancel` 撤销发布资格不等于中止 LM native；LM owner 必须发 `cancelProcess()` 并等待终止回调。 |
| `PluginAudioFocus`、`AudioCaptureHost.playbackChanged`、`PluginOutput.captureAudio` | 沿用唯一麦克风和半双工焦点。`LiteRtPlayback.play()` 会先 stop；句级/codec 流需同一 session 的 append/drain 有界播放接口，而非每块调用 play 打断前块。 |
| `PluginMessage`（最多 8×240 字） | LaTeX/JSON/代码/引用需要有界、可完整复制和编辑的文档结果；短报告应明确截断，不能拿截断字符串做结构化结果。 |

LM 0.19.0 实际 AAR minSdk24、arm64-v8a/x86_64，只有各 ABI 的 `liblitertlm_jni.so`，没有重复 `libLiteRt.so`/`libc++_shared.so`；POM 为 Gson2.14.0、kotlin-reflect2.4.0、coroutines1.11.0，项目 Kotlin2.4.21。保留现有 LiteRT2.3.2/QNN2.50，不能照搬旧样例 `pickFirst`/排除 litert-api/提高全 App minSdk。

- 多模态：[0.19.0 Kotlin API][LM-API] 的 `EngineConfig(modelPath, backend, visionBackend, audioBackend, maxNumImages, cacheDir)` → `createConversation` → `sendMessageAsync(Message.user(Contents.of(Content.ImageBytes(png), Content.Text(prompt))), callback, ...)`。模型选择应读取 `ModelInfo.from(path)` 的 modalities/backend/SoC/context。
- Embedding：`EmbeddingEngine(EmbeddingEngineConfig(...))` → `computeEmbedding(listOf(InputData.Text(...)), EmbeddingOptions(normalize=true, outputSize=768)).embedding`；图像用 `InputData.Image(png)`、音频用 `InputData.Audio(wav)`。无需 Flutter bridge。
- [Conversation.kt:440–474][LM-FLOW] 的 Flow 包装忽略 `trySend` 失败且 `awaitClose {}` 为空；采用有界 callback bridge，显式取消，终止后再关闭，不照抄为生命周期保证。
- AAR 无 consumer ProGuard；当前 Qwen 的精确 keep rules 只证明该路径。新增 embedding/多模态/tool JNI 类型时补所需保留及 release 实际调用验证。

## #71：视觉识别的非 Zoo 剩余项

本轮 12 个 Zoo taskId 已有实现；本表是其外的模型/场景选项，均**待实现或接线验证**。

| 必需项 | 下一可执行切片 / 不能省略的契约 | 固定源码证据 |
| --- | --- | --- |
| EfficientNet-Lite0 / Lite2、MobileNet-v2 | 分类插件增加各模型、真实 labels/Top-k/阈值；v1 保留但不顶替 v2。v2 RGB `/255`；按实际模型读 dtype。 | [image_classification README:7–18][F1-CLASS]；[MobileNet-v2:14–26,73–132][F1-MOBILE] |
| EfficientDet-Lite0 NPU / Lite2 | 两个 detector 选项、框/类别/分数和实际后端；Lite0 uint8 输入，NPU 匹配 QNN/SoC，不能用旧 SSD 冒充。 | [Lite0:63–76][F1-DET]；legacy `object_detection/android/app/download_models.gradle:17–19` 指向 `efficientdet_lite2.tflite` |
| YOLO26n detector | 迁 Flutter raw-head detector/后处理；供 #75 即时计数共用。不是 YOLO26n-pose。 | [Flutter models.lock:27–28][F5-LOCK]：源图→裁 raw head，导出结果也有大小/hash |
| MNIST CPU/GPU/NPU + legacy 同模型 | 手写或明确单数字裁切→0–9；统一场景，按后端验收。实读图为 float32 `[1,28,28,3]`→`[1,10]`；上游实际喂 RGB0–255，注释中的灰度0–1不可照信。 | [DigitClassificationHelper.kt:83–145][F1-DIGIT]；小资源见附录 |
| PlaneStrike | 用户8×8棋盘→屏蔽已试位置→合法建议一步→用户 hit/miss→更新/重置。实读 float32 `[1,8,8]`→`[1,64]`；全局 argmax 必须加合法性过滤。 | [ReinforcementLearningHelper.kt:48–71][F1-PLANE]；同目录 `UiState.kt:29–51`；模型85,004字节 |

## #72：分割/几何/跟踪的非 Zoo 剩余项

本轮 9 个 Zoo 引擎与按压超分接线不覆盖以下独立算法；旧 DeepLab/ESRGAN 保留。

| 必需项 | 下一可执行切片 / 资源和正确性约束 | 固定源码证据 |
| --- | --- | --- |
| Selfie multiclass CPU/GPU/NPU | 模型选项→真实类别 mask；从实际输出核对类别数/归一化，不凭21类/6类文字猜测。公开资源16,371,837字节；NPU仍需实际后端证据。 | `S/image_segmentation/*/app/download_model.gradle`；[Selfie固定模型][SELFIE] |
| DIS | 执行 notebook 转换/原图-mask 对照，固定可下载/导入产物后接现有 Android helper；不是“只有导入入口”。 | [DIS Android README:9,32][F2-DIS]；[可访问转换 notebook][DIS-NOTE] 导出 `isnet.tflite` / `isnet_tfl_drq.tflite` / `isnet_pt2e_drq.tflite`。待转换，不是缺URL阻塞。 |
| SAM2.1 still image | 约80MB encoder +17MB decoder +3072字节 prompt 常量；每图编码缓存、点击/负点编码、mask/坐标/清除。 | [Web README:14,123–126,194–200][WEB]；`samples/web_demos/src/sam2/sam2.js` |
| CLIPSeg | 三图+CLIP BPE+embedding/projection表，约278MB；图像特征缓存，文字→mask。decoder FP32；Android不能直接继承 WebGPU 的通过/失败判断。 | [Web README:142–158][WEB]；`src/clipseg/{host,tokenizer}.js` |
| SAM3 image | vision→text→head、token embedding、presence×class score、逐实例框/mask；约1.71GB+tokenizer。text CPU/FP32，保存rank4修正。SAM License。 | [SAM3 converted README:11–41,76–89][F2-SAM3]；LiteRT≥2.2，当前版本满足版本条件但仍须数值验证 |
| SAM2 video（converted / tensor_api / web_demo） | 导出/固定完整图和5项host常量，移植 encode→memory attention→decode→memorize；2/7-slot bank、对象指针、正负点、多对象/reset。不能以still decoder算完成。 | [converted README:3–36][F2-SAM2V]；[web_demo README:10–39,106–140][F2-SAM2WEB]；约80+26+18+3MB（每个memcond变体约26MB），rank4 memory attention |
| MoGe-2 | photo→points/normals/mask/scale→可旋转点云；GPU图70.7MB、CPU图136.3MB。预测几何不是标定测量。 | [Web README:10,115–126][WEB]；`src/moge/main.js:17–27` |
| TIPSv2-B14-DPT | RGB0–1 NCHW448→depth/normals/150类seg；使用318.34MB已修正fp16产物，保留depth动态范围缩放；标示NYU训练尺度。 | [converted README:5–18,37,59–71][F2-TIPS] |

CPU/GPU拷贝路径可以先闭环；C++零拷贝的 `cl_khr_gl_sharing`、WebGPU私有实现、Play AI packs/动态feature不构成所有Android实现的前置阻塞。

## #73：非 Zoo ASR/TTS/关键词

本轮 Zipformer-small、Matcha、DAC、PANNs、CREPE、TIGER、CMGAN、Basic Pitch 已有实现。以下仍需独立配置/真实文字或波形验证；所有音频只消费共享 PCM，保留时基、重采样、窗口与重叠去重。

| 引擎 | 下一切片与实际限制 | 固定源码证据 |
| --- | --- | --- |
| Zipformer medium | 现有 `ZipformerEngine.MEDIUM_MODEL` 分支接固定131,490,944字节图+tokens；不重复写ASR。 | [voice_assistant models.json:5–28][VOICE-ASSETS] |
| Parakeet TDT / CTC / 日语TDT-CTC | 各自mel/CTC或TDT状态解码、tokenizer、时间/重叠去重；图约614/596/608MB。日语模型不与英语结果合并算一个。 | [ASR README:13–21,75–120,153–205][F3-ASR]；[model_metadata.json:2–47][ASR-META] |
| Parakeet Ultra | mel+encoder+decoder，10/20/30s窗口；10s int8 encoder616.31MB + decoder17.87MB + mel1.61MB。不要套所有模型5s。 | [model_metadata.json:15–25][ASR-META] |
| Moonshine tiny | 原始16k PCM、5s窗口、encoder/decoder、词表；int8图51.94MB；Reachy使用另一f32版本109.37MB。 | [ASR metadata:48–55][ASR-META]；[Flutter lock:37–39][F5-LOCK] |
| Whisper tiny / base | 30s图、对应mel/tokenizer/结束token、重叠合并；int8约41.12/77.01MB。真实语言/翻译模式分开。 | [ASR metadata:56–68][ASR-META]；[Flutter lock:33–35][F5-LOCK] |
| Qwen3-ASR | 5s mel+decoder host，约793.93MB；start198/stop151645/skip-through151704，KV/窗口重置。 | [ASR metadata:69–83][ASR-META] |
| KittenTTS | 句级流式、G2P、voices、duration行展开、动态vocoder；fp16三图约32MB+voices3.28MB+共享G2P约28MB。 | [streaming README:28–48,77–101][F3-KITTEN]；voice_assistant `tts/` |
| Inflect-Nano-v2 | 两图约8.16MB+共享Matcha G2P；移植有界句内chunk合成，验证phoneme映射。 | [Flutter lock:41–49][F5-LOCK]；Reachy `assets/inflect/say.py` / `emulator/inflect_tts.py:16–26`。后者实际用GPL espeak-ng，不与Flutter字典/神经fallback许可混淆。 |
| Qwen3-TTS多语言 | 迁现有Kotlin BPE、mmap表、talker/MTP/codec/KV；同revision完整legacy集约1.9GB（folded/split组合较小）。x-vector导入可做；3s录音端上提取还缺已集成speaker encoder。 | [TTS-LM README:5–30,42–49][F3-QWENTTS]；`kotlin_cpu/android/.../Qwen3TtsEngine.kt`；根路径404修正见限制表 |
| legacy Speech Commands | 实读图float `[1,44032]`，44.1kHz→9类，5,814,462字节；重采样/真实labels/阈值/去重，与ASR区分。 | `samples/litert_interpreter/audio_classification/android/.../AudioClassificationHelper.kt:123–147,267–277` |
| wav2vec2 KWS | 1s16k原始音频→frontend9.25MB +head180.57MB→12类；保留weighted-layer sum和阈值/去重。 | [converted README:5–7,26,48–60][F3-KWS]；whole fused GPU图失败已有split解决，不是整模型阻塞 |

Kitten具体原生约束：实读 **LiteRT2.3.2 Kotlin API仍无CompiledModel resize**，但C库已导出 resize/output-layout/managed-buffer/run符号；可迁 [小JNI适配][KITTEN-JNI]，核对当前handle布局、16KiB页对齐及release。predictor/prosody的变量LSTM保留Interpreter、每句 `resetVariableTensors()`；不强制全部CompiledModel。Matcha CFM继续保留CPU正确性策略。

ASR样例实际依赖 `jlibrosa-1.1.8-SNAPSHOT-jar-with-dependencies.jar`（mutable URL需固定commit/hash）及tokenizers JNI。arm64/x86_64静态库实存36,598,168/36,535,908字节，不是缺失LFS占位；固定来源/许可或验证现有DSP/tokenizer替代，不能删必要预处理。纯Kotlin/已有DSP可复用时不引入整个样例App。

## #74：非 Zoo 文本与本地模型

| 项目 | 状态 / 下一切片 | 依据 |
| --- | --- | --- |
| BERT、average_word_classifier（legacy/CompiledModel归并） | 待实现词表/真正分词/分类选项。实读BERT输入按序为word IDs/type IDs/mask（均int32 `[1,128]`）；样例写mask/type的顺序相反，按实际name绑定。average为int32 `[1,256]`。 | [TextClassificationHelper:101–135,159–193][F4-BERT]；小资源见附录 |
| Laya choice/score/yes-no | **算法与UI已实现，待真实场景证据**；保留calibration/action head、`score`期望等级、`noul`真概率及截断说明。 | [zero_shot README:3–9,38–49][F4-LAYA]；当前 `LayaDecisionEngine` / `LiteRtTextPlugin` |
| GLiNER2.5 Small实体 | 待迁Hub Kotlin `GlinerExtractor/Inputs/Tokenizer/Decoder`；固定英文person/organization/location/product/date，不是Decide/任意schema。最大512 encoded tokens/384 words；GPU显式FP32；code-point→Android UTF16偏移转换。 | [README:19–26,52–87,115–149][F4-GLINER]；固定Hub revision `8f2acf3cb9088bfbc2a6e921ec9a4607433e8f6d` 的 `android/`，图54–84MB+196.62MB embedding+0.47MB sparse参数 |
| EmbeddingGemma300m | 授权完整SAF导入模型+SentencePiece→cosine/查找；匿名401为访问条件，不绕过。 | [semantic_similarity README:7–8,18–23,63–72][F4-SEM] |
| EmbeddingGemma2 | **推荐下一小切片**：484.62MB自含bundle→本地语义排序/来源片段，再接RAG/视觉记忆；与300m分开标示。 | [Flutter lock:30–31][F5-LOCK]；LM `EmbeddingEngine` API |
| LLaDA8B条件适配 | 待导出固定真实32层图+tokenizer、`denoise`迭代直到masked_count=0，再接补全文本/停止；明确 `--layers=32`，默认4会覆盖LLaDA架构层数。6.29GB mmap已在上游Pixel8a CPU int4完成，不能因8B/C++排除。 | [Tensor API README:54–79][F4-LLADA]；[diffusion_main.cc:48–87,125–160,195–315][LLADA-HOST]：输入token_ids/rope_cos/rope_sin，输出new_token_ids/masked_count。当前2.3.2未复现不兼容，待执行不是阻塞。 |

九个文本模型是各自模型选项，共用本地文本场景；Qwen的1024/256预算、模板及缓存策略不能直接推广：

| 模型 | 下一配方单位 / 关键限制 | 固定转换依据 |
| --- | --- | --- |
| MiniCPM5-2B | 待接int4约1.55GB/int8约2.6GB；patched零scale、BOS/stops/thought、量化对应activation；长thinking不能用int4偶然结束代替验证。 | [converted README:5–16,45–78,80–99][LM-MINI] |
| Granite4.1-3B | 待接int4约2.19GB；无spurious BOS（BOS=EOS会复述提示）；GPU驻留按设备实测。 | [converted README:32–66,81–84][LM-GRANITE] |
| Hy-MT2-1.8B | 待翻译配方约1.83GB；LM≥0.18的content-parts模板、literal BOS只出现一次、stop120020。 | [converted README:5–13,46–48,94–107][LM-HY] |
| LFM3-1.7B | 待mixed-int4约1.44GB、2048/4096上下文选项；fresh Engine、EOS124900、tools/thought模板，手机长上下文另验。 | [converted README:11–18,73–89,147–150][LM-LFM]；也供offline_planner |
| Ministral3-3B | 待约2.34GB **text decoder only**；原生Mistral `[INST]`、合法 `<s>` BOS/`</s>` stop，不能宣传视觉能力。 | [converted README:5–7,39–49,75–78][LM-MINISTRAL] |
| Nemotron3-Nano4B | 待约4.13GB、50 hybrid states、FP32；GPU默认cache有已记录错误，需该模型专属策略和内存测量，不能复用Qwen的无cache坏模式。 | [converted README:5–15,43–57,69–85][LM-NEMOTRON]；`cacheDir=":nocache"`虽是可用API，是否可交付须本模型验证 |
| Qwen2.5-Coder1.5B | **已实现固定CPU配方/主机六项，Android及release待证据**；1,117,385,648字节，vendor system内嵌user prefix，不再添加system/ChatML，不执行代码。 | [converted README:45–59,80–85][LM-QWEN]；资源Q1和本文内存纠正 |
| SmolLM3-3B | 待约2.00GB；bare ChatML；thinking需显式对应system，不能猜模板内置默认；context4096。 | [converted README:32–60,75–78][LM-SMOL] |
| VibeThinker3B | 待约2.06GB；block32、双stop151643/151645、4096context及足够输出；验证最终算术答案而非thought中的数字。 | [converted README:32–52,56–76][LM-VIBE] |

## #75：多模态、文档与工具场景（逐行保留）

除可复用的基础引擎外，以下场景均待集成和项目实际输入验证。CPU/GPU/NPU变体是同场景的配方，不另造App/相机/下载器。

| 上游场景 / 模型 | 下一可执行单位 | 资源、限制、源码证明 |
| --- | --- | --- |
| PhotoTalk：Gemma3/Gemma4/FastVLM | 分类标签+置信度→明确标示“标签上下文”的文字问答；另建照片bytes→真正VLM问答。 | [LiteRtLmHelper:168–194][F5-PHOTO]实际只传label；Gemma3 1B/270M不是视觉模型。Gemma4/FastVLM按bundle实际tower/API/许可验证；不能把两条路径混为照片理解。 |
| Flutter：YOLO即时场景问答 | detector结果回答类别/数量；详细问题单次捕获照片交VLM，保留回答路线和帧归属。 | [README:23–29,62–79][F5-FLUTTER]；raw-head detector的导出/固定hash在models.lock。 |
| Flutter：EmbeddingGemma2 RAG | 文档分片→768维embedding→top-k原文片段/引用→LM答案。先小语料直接cosine即可，sqlite-vec不是首闭环前置。 | [knowledge_config:49–115][RAG-CONFIG]：样例限制1024tokens/top3；query前缀 `task: search result \| query: `，document `title: none \| text: `；索引绑定模型hash/前缀/维数。 |
| Flutter：短期视觉记忆 | 闲时变化帧→image embedding→找之前物品/时间卡片→可清除；换源/离开清空。 | [README:269–278][F5-FLUTTER]；`live_camera_config.dart:153–266`：最多200帧/16MiB、缩略图320、至少3s间隔；图片查询文本不加检索前缀。 |
| Photo voice chat：LFM3-Omni | 先照片+文字→多轮文字/语音；再共享麦克风和短视频实际AV采样。复用 `Conversation/LmRunner/Parts/SpeechGenerator/Video` host。 | [README:17–44,49–80][F5-OMNI]：四下载文件4,012,449,712字节，另抽取3,224,086,736字节图及cache。4096context、首13s视频、30s音频；保留帧/音轨时间、resampler、codec overlap-add和清状态。是CompiledModel自驱，不靠普通LM消息API合成语音。 |
| Voicemail：LFM3-Audio | 录音/文件→来电人/意图/号码→朗读；回拨只打开dialer。 | [README:7–32,49–83][F5-VOICE]：五必需文件2,067,289,524字节，另抽取1,528,586,416字节图。30s截取明确；英语interleaved，西语text→TTS两段，保留号码读法和实测语言边界。 |
| Voice assistant：Zipformer→Gemma4→Kitten | 先文字请求→真实datetime/alarm/timer/local-calendar工具结果，再接ASR及句级合成。参数/权限/幂等/前台校验，失败不说完成。 | [README:30–45,100–110][F5-ASSIST]；`loop/{ToolRunner,ToolFormat,PhoneTools}.kt`：`automaticToolCalling=false`，Message.toolCalls→校验执行→Message.tool/Content.ToolResponse。AlarmManager仅能证实next alarm；timer只能报告requested。 |
| Math to LaTeX：MiniCPM-OCR1.0 | 框选公式→448²白底/Pillow resize→LaTeX流→离线MathJax/复制，再扩整页切片。 | [README:27–49,80–97][F5-MATH]；1,997,636,640字节，CPU，样例gate2.5GB可用；overview+最多9slices、`slice_cols`；fresh Engine。保留按框与整页语义、停止后重跑。 |
| Receipt to JSON：Xiaomi-OCR0 | **推荐首个#75闭环**：照片/选字段→EXIF/4:7白底768×1344→流式JSON→核对编辑/复制→取消/关闭。 | [README:5–33,48–57,94][F5-RECEIPT]；1,314,747,056字节，CPU，样例gate3.2GB；复用OcrEngine/Kie/Pictures/PilBicubic及fixture。缺字段/日期歧义显式保留，不确认财务事实；fresh Engine。 |
| Offline planner：LFM3-1.7B | 用户句子→动作建议→Run→App自身calendar/contact/outbox变更；重验唯一目标/时间/state version/idempotency。 | [README:13–41,97–106][F5-PLANNER]；1.44GB/context2048。`ToolCalls.kt:87–91`的Action.run本身不幂等；增加请求/动作ID与重复运行防护，不动系统账户数据。 |
| Where to put：LFM3-VL2B | 图+放置要求→有效0–1000点→来源快照标记；无点/越界/仅thought则不画。 | [README:22–26,51,75,105–109][F5-PLACE]；2,071,611,643字节/context4096，样例gate6GB。README109明确0.19新Conversation仍泄漏短卷积状态，迁移时改fresh Engine；item大小是App猜测。 |
| Click or code：Mage-VL4B | 截图+请求→显示点击点或可复制代码，不执行；保留先请求screenshot再反馈的两步协议。 | [README:5–24,57–82,91][F5-MAGE]；3,199,591,888字节，textGPU/visionCPU，gate6GB；1920×1088/EXIF，`round(x/999*(width-1))`；不要仅做单轮文本框。 |
| Reachy voice robot迁手机 | 最新检测+Moonshine转写→Gemma→Inflect；共用当前帧/音频与有界pipeline。 | [README:47–83][F5-REACHY]；电机/Reachy SDK/Pi服务不是手机已集成能力；保留see/hear/answer的真实路由。 |
| Flutter Intel NPU会议能力迁手机 | 带时间转写/文件→引用原文问答；后续任务/邮件等草稿、workspace/目标/重复调用检查。 | [README:14–30,120–135,291–305][F5-INTEL]；先按实际context有界输入/检索。四幕race/功耗sidecar不作Android NPU证据；原12B长context/未公开26B路径单独记录。 |
| Picture questions原Muse30B/Qwen72B | 可共用小VLM照片交互，但保留原两模型未交付状态；不能以小模型一次运行勾掉多步阅读算术。 | [macOS README:19–30,69–89][F5-BIG]：文件23.4/42.7GB，实测峰值25.9/43.7GB，原App要求32/64GB Mac；仅对普通手机目标构成具体资源条件，解除见下表。 |
| Qualcomm/Google Tensor后端变体 | `qualcomm/{fast_vlm,gemma3/cpu_gpu,gemma3/npu,gemma4/cpu_gpu,llm_chatbot_npu}`、`google/sample_app_tpu`共用上述场景；固定AOT型号/SoC/运行时与tower placement，实际推理后开放。 | [Flutter README:66–98][F5-FLUTTER]；[TPU README:8–11,75–105][F5-TPU]。公开FastVLM SM8750/SM8850与Gemma4 G5文件存在；TPU样例亦带真实dispatch，未测兼容不是未公开。旧样例自动fallback不能用于NPU-only bundle。 |

各GB/内存数字是上游配方约束，不是本App测量。LM cancellation必须等native确认；上游math whole-page在Read后1.5s点Stop，到Read后24.6s才结束（取消延迟约23.1s）；Mage可能等vision完成。须立刻撤销结果资格并显示停止中，不可在回调仍活动时释放Engine。

## #76：三条独立重图路径

| 模型 | 下一可执行单位 | 完整资源 / API / 验证要点 |
| --- | --- | --- |
| Bonsai Image4B | **先256²闭环**：prompt/seed/steps→进度/取消→生成图展示/撤回，再512²模型选项；迁Hub Kotlin `BonsaiPipeline/BonsaiMath/QwenTokenizer/PromptRules`。 | [固定Android样例][BONSAI-ANDROID]、[U配方:17–27,49–76][F6-BONSAI]；三图4,264,287,724字节+tokenizer/meta，DiT单图>2GiB。CompiledModel CPU/XNNPACK，按args_n位置绑定，SplitMix64/Box–Muller、sigma/Euler/unpatchify/VAE；三阶段先关再开。上游256/512峰值4.6/5GiB，256不降低权重峰值。 |
| WeVisOne | image+description→透明alpha/可保存PNG；同引擎保留detect/find/point/count/read-text原型任务、No match/Stop和来源帧。 | [README:7–20,35–45,59–86][F6-WEVIS]；六文件3,257,599,697字节、four graph stages+fp16 embedding/tokenizer。必须取输入token为`<OBJ>`时hidden→Fourier box→alpha decoder；普通LiteRT-LM回答没有该hidden。默认int8权重/GPU FP32；前置4200MiB左右，实测峰值6.1GiB。 |
| OCR Check：MiniCPM-OCR-Jev/Decision2B | PP-OCR候选/框→region+候选renderer→真实score→threshold标记→编辑重评分；Formula/Table按协议；与原MLKit候选/原型输出比较。 | [README:9–32,49–73,85–95][F6-OCR]；五文件5,110,221,205字节，含2.75GB decision。GPU、两tower+decision有界LRU、bf16表/patch/M-RoPE/tokenizer/末token分类head。gate≈4.6GiB，实测resident6.5GiB；过长输入报所缺bucket，不假分数。 |

这三项均不依赖普通LM Conversation API；#70大文件/资源租约及现有CompiledModel已是接续基础。OCR候选若用PP-OCR，依赖#71的类型化结果；若保留MLKit Latin，新增明确 `com.google.mlkit:text-recognition:16.0.1`及条款。识别语言与评分模型语言分开说明。renderer为WebView/AndroidX WebKit1.12.1、KaTeX0.16.47/DejaVu；24文件共2,018,916字节，固定U内资源与NOTICE。WeVis额外保留`box_log_table.bin`，不能用普通log近似替换高频Fourier输入。

## 真实条件限制与解除条件

| 证据（不能泛化） | 解除条件 / 可继续内容 |
| --- | --- |
| API24/kernel3.10 `memfd_create`实测`ENOSYS`；`:memory`失败可能静默退回无共享 | 当前私有文件scratch已绕开syscall；验证可写/实际packed cache、内存、清理及Android/release。不是LM整体不可用，也不能弱化门槛强跑2GiB设备。 |
| 匿名HEAD：EmbeddingGemma300m模型/tokenizer、Gemma3 SM8750 bundle均401，`X-Error-Code: GatedRepo`；消息要求授权并登录 | 用户合法取得后完整SAF导入/校验；公开EmbeddingGemma2/Gemma4场景继续。不能转发凭据到无关重定向或绕过门禁。 |
| Qwen3-TTS根`talker_int4.tflite`为404 `EntryNotFound`，同仓`legacy/talker_int4.tflite`为200、255,998,768字节 | **路径迁移，非模型不可得**；固定整个legacy revision及表/codec/tokenizer/voice集合，不能只修一个URL。 |
| Gemma4 `gemma-4-E2B-it_qualcomm_sm8750.litertlm`在新main为404，但历史固定revision为200、3,016,294,400字节 | 使用附录硬件pin并验证SM8750/QNN/tower；不因main404关闭任务。FastVLM当前实际AOT文件含`.qualcomm.sm8750/.sm8850`，旧README短名也须核对。 |
| Flutter README74：特定手机Gemma4全NPU towers“provided by Google, not yet published”；Intel README55：26B A4B build“provided by Intel, not public” | 获公开/获准分发且匹配设备的具体文件再验；普通CPU/GPU、已公开AOT和会议草稿逻辑不受阻。许可按各模型卡（Gemma3、FastVLM Apple-AMLR、LFM Open License等），不能一概Apache。 |
| Intel PTL build在LNL驱动拒绝：`ELF_ABI_VERSION … Expected: 1.3.0 vs received: 2.0.0`；E2B NPU拒绝`7249 >= 4096` | 匹配硬件/驱动/编译产物和足够context后重测。平台特定错误不能作Android全部NPU阻塞；缩短/分片只能完成对应有界场景，不证明原长context展示。 |
| Muse30B/Qwen72B原配方峰值25.9/43.7GB，普通手机资源不足；上游小VLM多步算术不等价 | 在满足实测资源的目标上验证原产物，或验证另一个明确命名配方并保留原项未交付；不能凭尺寸一句“太大”排除，也不能凭替代模型勾原项。 |
| Kitten变量LSTM的CompiledModel限制及2.3.2 Kotlin无resize | Interpreter变量reset +现有最小C API桥并验波形；已有可实施路径，不构成整个TTS阻塞。 |
| Nemotron默认GPU cache有`Invalid BindGroup`/token soup（上游Mac LM0.16）；SAM3 text FP16使部分提示输出0 detections | 见各固定转换README；采用其有证据的CPU/精度/图配方，分别重测当前Android。无当前复现不能捏造2.3.2错误，也不能把单后端失败写成整模型阻塞。 |
| DIS/SAM2-video/LLaDA尚无本App固定可安装产物；部分设备/模型尚未测试 | **待导出/实现/验证，不是外部阻塞**。执行给定配方、固定产物和fixture；只有实际runtime/resource/license失败才新增带错误/环境/解除条件的限制。 |

## 资源交接：首批精确 pin

资源基址均为 `https://huggingface.co/<repo>/resolve/<revision>/`；下列R编号的链接直接指向固定文件，不使用main。详细完整多文件集合优先迁既有清单，不另猜版本。

| ID / 文件 | repo / revision | 字节 / SHA-256 |
| --- | --- | --- |
| Q1 [Qwen2.5-Coder-1.5B-Instruct_int4.litertlm][Q1] | `litert-community/Qwen2.5-Coder-1.5B-Instruct` / `7840209bfd393408ef028cf55921d831baa1e4bf` | 1,117,385,648 / `273ecc7771ba2dd5fe1bb6d4d4726ad0353102f04ad094082ccf59bca9f21213` |
| R1 [Xiaomi-OCR-0_int8.litertlm][R1] | `litert-community/Xiaomi-OCR-0` / `9012fe326c2b3038f97623258486ffeb52513260` | 1,314,747,056 / `82880f7361b90f51a3fda9609878e24b4a00e95a058f8ff47391b90e5e95d9be` |
| R2 [MiniCPM-OCR-1.0-VL_int8.litertlm][R2] | `litert-community/MiniCPM-OCR-1.0` / `c818533aaf2264fd38cf5e494ff74e0ea8b1dc62` | 1,997,636,640 / `f233e3b991b4b76abec8910295ddc0660c8f93df30708a6709f27dd770fe03cc` |
| R3 [embeddinggemma-2-740m.litertlm][R3] | `litert-community/embeddinggemma-2-740m-litert-lm` / `24d962e906c7d332c6428e71c9676855024569e2` | 484,622,336 / `e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c` |
| R4 [gemma-4-E2B-it.litertlm][R4] | `litert-community/gemma-4-E2B-it-litert-lm` / `b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1` | 2,588,147,712 / `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |
| R5 [LFM3-1.7B_mixed_int4_ekv2048.litertlm][R5] | `litert-community/LFM3-1.7B-LiteRT` / `9bbda264c623f30c905b9c247761ee7463f2b00d` | 1,441,620,928 / `0400d8ffdb00e4bbe1209120e2c7baf05322a8c900839b0a94cc28f599011196` |
| R6 [LFM3-VL-2B_int4_ctx4096.litertlm][R6] | `litert-community/LFM3-VL-2B-LiteRT` / `f547c061f063788155b9f95a19959293f558418b` | 2,071,611,643 / `64d89b858df4cea27fea4ebcb3bc468e0bc6af2c2cc87db69ac9776332c451e4` |
| R7 [Mage-VL-Cowork_vision-fp16.litertlm][R7] | `litert-community/Mage-VL-Cowork` / `2318e906b25048599ef48ce1f93427f36858b339` | 3,199,591,888 / `1e977695d8f46222653233143e95431ccf6e46bfabe2c77073abf2e8ba75c5df` |

| 多文件集合 | 固定资源版本 / 已有完整清单 |
| --- | --- |
| WeVisOne六文件 | Hub `litert-community/WeVisOne-VL-2B@9f2f07d2678e6bb5d9212c5c20668059730a6a41/cutout/`；[ModelFiles.kt:30–78][WEVIS-FILES]逐文件大小/hash；额外U内 `box_log_table.bin`：27,948字节，`abd1a6f39ef244d8e278c4b7fc4a9ef6cb720585db72476e6e6224faf7e2ca88`。 |
| OCR Check五文件及可选大bucket | Hub `litert-community/MiniCPM-OCR-Decision-2B-LiteRT@672db2e312814fd9b56aa02d494755676140cfdb`；[ModelFiles.kt:33–64][OCR-FILES]；默认tokenizer/tables/tower_N400/tower_N576/decide_L384；renderer同U。 |
| Omni四文件 | Hub `litert-community/LFM3-Omni-2B-LiteRT@6d95b0222f6724130b7cfdff9e63ed65982bcb77`；[README:53–58][F5-OMNI]完整hash；同样例`assets/bundle_sections.json`还固定各section偏移/hash；不可与其它bundle替换混用。 |
| Voicemail五文件+可选SM8850 NPU pass | Hub `litert-community/LFM3-Audio-2B-LiteRT@11dc7f8bd957a28f9cb652d456e24f3e644ac5ce`；[README:51–60][F5-VOICE]及`bundle_sections.json`。 |
| Zipformer medium/Kitten/G2P | [voice_assistant models.json][VOICE-ASSETS]：Zipformer `7732ad6c15ec43402968d5ae04acfa7a204027f5`；Kitten `d4662d891f9bf54b3d93432610d0d296d229e026`；Matcha G2P `8d650e794583c0b0869c87027c2f3a7c293902cb`；不要把fp32和fp16清单混用。 |
| YOLO raw-head / Whisper-base / Moonshine / Inflect | [Flutter models.lock:25–49][F5-LOCK]包含来源commit、源/导出文件名、大小/hash及pip转换版本；G2P字典BSD-3-Clause-Clear，神经G2P MIT，YOLO AGPL-3.0。 |
| Qwen3-TTS legacy整集 | [固定legacy目录][QWEN-TTS-LEGACY] `3993383816e7108c6c57cf9329e6446e55fd3bde`；talker+MTP+codec+tables+voices+vocab/merges。talker_int4：255,998,768字节，`e03df54e73ed1f88b2ae6d47bbf82dd64ea90a3620d753a0f3c8d6a8d60848db`。其余逐文件补清单后才ready。 |
| MathJax | [math_to_latex build.gradle.kts:53–57][MATHJAX]：`mathjax@3.2.2/es5/tex-svg-full.js`，SHA `a4354ff94fd868aea0cc6eaaa79a57fda0588646fc46ee3700a349ee0a11cbe6`，离线打包及Apache/Pillow归属。 |

Bonsai首个256资源集：Hub `litert-community/Bonsai-Image-ternary-4B@317d577f5f962eb2d5a3980bca2ce047a8338212`（[Kotlin host及模型目录][BONSAI-ANDROID]）。三图与样例明确引用的旧资源revision `8878f895ec6c22eab5ee6315aca6b85d8219a994` hash相同；小文件SHA本次按内容计算。

| 相对路径 | 字节 | SHA-256 |
| --- | ---: | --- |
| `textenc_int4.tflite` | 1,798,100,240 | `12937850e4a1670986e79663649d071f9db4095d9a74ce1cca045551447f7683` |
| `dit_256_int4b32.tflite` | 2,267,356,304 | `4a8f6c12382a9be5ff2d45bb1f70c1cbbce47fe139fa3970b9f868d91565024e` |
| `vae_dec_256_fp32.tflite` | 198,831,180 | `f3ac3b1bca6a48a4fee55779317a3e6adf797336afe82aaa03e8a9b5bbdab324` |
| `pipeline_meta.json` | 6,577 | `e94faa62e7648db114f3dfcf85fadd5444e64de5e04d8d34ec14e9c306b2a4c6` |
| `tokenizer/vocab.json` | 2,776,833 | `ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910` |
| `tokenizer/merges.txt` | 1,671,853 | `8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5` |

512选项另加同revision `dit_int4b32.tflite`（2,267,355,872，SHA `580c3485d2a878cd6b3c116373b7685032fd2974ae974ca04720b0b55a187646`）及 `vae_dec_fp32.tflite`（198,831,180，`96fff2562ecb0c8ffa673126d24c78f340b0b6fb794e90a3b29180aa7a23cacf`）。保持形状变体成套，输入名相同shape也不能互换。

小legacy资源基址 `https://storage.googleapis.com/ai-edge/interpreter-samples/`，服务无Git revision，采用U内下载脚本+精确内容hash固定（本次实际读取并计算，未运行推理）：

| 路径 | 字节 / SHA-256 |
| --- | --- |
| `digit_classifier/android/mnist_metadata.tflite` | 1,212,797 / `199fa9722eb18d7b03efa4c51859972ef7250eddd103645cac6855038e90ddee` |
| `reinforcement_learning/android/planestrike_tf.tflite` | 85,004 / `403c809fd130cfa4b191076328abd5363dc446f94a3cbba54cb7da21a7762f2d` |
| `text_classification/android/average_word_classifier.tflite` | 775,706 / `13bf6f7f35964f1e85d6cc762ee7b1952009b532b233baa5bdb4bf7441097f34` |
| `text_classification/android/bert_classifier.tflite` | 25,707,538 / `9b45012ab143d88d61e10ea501d6c8763f7202b86fa987711519d89bfa2a88b1` |
| `audio_classification/android/speech_commands.tflite` | 5,814,462 / `e92b131896702df74395570806c0577f888827163bbca65325920d3afbaa91f4` |

硬件pin：Gemma4 SM8750使用R4相同repo/revision下 `gemma-4-E2B-it_qualcomm_sm8750.litertlm`，3,016,294,400字节，SHA `41dd675fbe735b6029012b5576a5716bac614fd8156de0128db4c9dff3cebd4e`。Google Tensor G5为同repo revision `5b472bf479ec0e944b01c214b46fec082b3e8c01` 的 `gemma-4-E2B-it_Google_Tensor_G5.litertlm`，3,206,299,658字节，`72d056066f34362adcecf6b76995de67ad899f6f33dfe9079593c1acd2984191`。存在不等于与本项目runtime/设备兼容。

## 下一批执行顺序与退出标准

1. 收敛当前#70/本地Qwen的整项目检查与Android/release LM→pose证据，保留上述真实缓存/资源条件。
2. **Receipt首闭环**：新建模型特定image请求适配，复用 `OcrEngine/Kie/Pictures/PilBicubic`；字段编辑、EXIF/4:7、缺字段/歧义、取消/换图/fresh Engine再次生成全部走同一插件。随后复用fresh-engine图像路径交付Math。
3. **EmbeddingGemma2首闭环**：真实文本相似/检索排序→来源片段→RAG；再扩image embedding与有界视觉记忆。先验证768维/normalize/前缀/截断规则，不能只看向量非NaN。
4. 独立进行Bonsai256、WeVisOne、OCR Check（PP-OCR类型化候选），以及小legacy MNIST/PlaneStrike/BERT场景；不用等待所有LM场景写完。
5. Kitten+medium Zipformer+真实工具→完整语音助手；随后Omni/voicemail的自驱codec和AV时间路径。其它#71–74模型按表逐项配置/输出/停止验收。

每模型退出条件：固定输入→语义可核对输出→配置→取消/再次运行→关闭/后台释放；记录源revision/hash/许可、实际API/ABI/SoC/内存/后端、首字/首音/取消延迟、来源/请求/配置代次。必要fixture覆盖tokenizer/模板、坐标/EXIF/切片、JSON/tool匹配/幂等、音视频时间、状态重置、>2GiB/缺文件/hash错/空间不足；下载失败保留旧资产。只迁算法/资源，沿用相机、模型入口及按需权限，生成文本/工具参数是数据。

合入验收命令仍为 `./gradlew assembleDebug assembleRelease lintDebug testDebugUnitTest --build-cache --parallel --console=plain`，由实现/协调流程运行；本文件的审计不执行构建。#69证据包必须区分主机参考、模拟器、物理arm64、GPU/NPU与shrunk release；原模型条件限制和未测试分别列明。**所有行达到其真实退出条件前，不关闭对应完整子任务；不关闭#63。**

<!-- 固定引用：行号在正文，URL 不随 main 漂移。 -->
[U]: https://github.com/google-ai-edge/litert-samples/tree/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8
[LM-API]: https://github.com/google-ai-edge/LiteRT-LM/tree/v0.19.0/kotlin/java/com/google/ai/edge/litertlm
[LM-FLOW]: https://github.com/google-ai-edge/LiteRT-LM/blob/v0.19.0/kotlin/java/com/google/ai/edge/litertlm/Conversation.kt#L440-L474
[LM-WORKSPACE]: https://github.com/google-ai-edge/LiteRT-LM/blob/v0.19.0/WORKSPACE#L6
[LM-MEMFD]: https://github.com/google-ai-edge/LiteRT/blob/dcb657e8a537b2dc944e53e14c7eb43bcd9ededa/tflite/delegates/xnnpack/file_util.cc#L142-L149
[LM-CACHE]: https://github.com/google-ai-edge/LiteRT/blob/dcb657e8a537b2dc944e53e14c7eb43bcd9ededa/tflite/delegates/xnnpack/xnnpack_delegate.cc#L687-L739
[F1-CLASS]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert/image_classification/README.md
[F1-MOBILE]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert/qualcomm/mobilenet_v2/README.md
[F1-DET]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert/qualcomm/object_detection/efficientdet/kotlin_npu/README.md
[F1-DIGIT]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert/digit_classifier/kotlin_cpu_gpu/android/app/src/main/java/com/google/aiedge/examples/digit_classifier/DigitClassificationHelper.kt#L83-L145
[F1-PLANE]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert_interpreter/reinforcement_learning/android/app/src/main/java/com/google/aiedge/examples/reinforcement_learning/ReinforcementLearningHelper.kt#L48-L71
[SELFIE]: https://huggingface.co/litert-community/MediaPipe-Selfie-Segmentation/resolve/d22c1f5ea6f5bf3643fb267c04b9a800ad393483/selfie_multiclass.tflite
[F2-DIS]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert_interpreter/image_segmentation_DIS/android/README.md
[DIS-NOTE]: https://github.com/google-ai-edge/models-samples/blob/a7b9f4b881d4fc818ffb1883a721723f9b7f9127/convert_pytorch/DIS_segmentation_and_quantization.ipynb
[WEB]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/web_demos/README.md
[F2-SAM3]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/models/sam3/sam3_image/converted/README.md
[F2-SAM2V]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/models/sam2/sam2_hiera_tiny_video/converted/README.md
[F2-SAM2WEB]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/models/sam2/sam2_hiera_tiny_video/web_demo/README.md
[F2-TIPS]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/models/tipsv2/tipsv2_b14_dpt/converted/README.md
[VOICE-ASSETS]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert_lm/voice_assistant/android/app/src/main/assets/models.json
[F3-ASR]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert/speech_recognition/README.md
[ASR-META]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert/speech_recognition/AndroidApp/app/src/main/assets/model_metadata.json
[F3-KITTEN]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert/text_to_speech_streaming/README.md
[F3-QWENTTS]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert/text_to_speech_lm/README.md
[F3-KWS]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/models/wav2vec2/wav2vec2_kws/converted/README.md
[KITTEN-JNI]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert_lm/voice_assistant/android/app/src/main/cpp/dynamic_shape_jni.cc#L21-L37
[F4-BERT]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert/text_classification/kotlin_cpu/android/app/src/main/java/com/google/aiedge/examples/textclassification/TextClassificationHelper.kt
[F4-LAYA]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert/zero_shot_classification/README.md
[F4-GLINER]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/models/gliner/gliner2_5_small/README.md
[F4-SEM]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert/semantic_similarity/build_from_source/README.md
[F4-LLADA]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/models/llada/llada_8b/tensor_api/README.md
[LLADA-HOST]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/models/llada/llada_8b/tensor_api/diffusion_main.cc
[LM-MINI]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/models/minicpm/minicpm5_2b/converted/README.md
[LM-GRANITE]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/models/granite/granite_4_1_3b/converted/README.md
[LM-HY]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/models/hunyuan/hy_mt2_1_8b/converted/README.md
[LM-LFM]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/models/lfm/lfm3_1_7b/converted/README.md
[LM-MINISTRAL]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/models/ministral/ministral_3_3b_instruct_2512/converted/README.md
[LM-NEMOTRON]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/models/nemotron/nemotron_3_nano_4b/converted/README.md
[LM-QWEN]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/models/qwen/qwen2_5_coder_1_5b_instruct/converted/README.md
[LM-SMOL]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/models/smollm/smollm3_3b/converted/README.md
[LM-VIBE]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/models/vibethinker/vibethinker_3b/converted/README.md
[F5-PHOTO]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert/phototalk_sample_app/android/app/src/main/java/com/google/aiedge/examples/phototalk/LiteRtLmHelper.kt#L168-L194
[F5-FLUTTER]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert/flutter/README.md
[F5-LOCK]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert/flutter/tool/models.lock
[RAG-CONFIG]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert/flutter/lib/config/knowledge_config.dart#L49-L115
[F5-OMNI]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert/photo_voice_chat/README.md
[F5-VOICE]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert/voicemail_summary/README.md
[F5-ASSIST]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert_lm/voice_assistant/README.md
[F5-MATH]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert_lm/math_to_latex/README.md
[F5-RECEIPT]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert_lm/receipt_to_json/README.md
[F5-PLANNER]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert_lm/offline_planner/README.md
[F5-PLACE]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert_lm/where_to_put/README.md
[F5-MAGE]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert_lm/click_or_code/README.md
[F5-REACHY]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert_lm/reachy-voice-robot/README.md
[F5-INTEL]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert/flutter_intel_npu/README.md
[F5-BIG]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert_lm/picture_questions/macos/README.md
[F5-TPU]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert/google/sample_app_tpu/README.md
[BONSAI-ANDROID]: https://huggingface.co/litert-community/Bonsai-Image-ternary-4B/tree/317d577f5f962eb2d5a3980bca2ce047a8338212/android
[F6-BONSAI]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/models/bonsai/bonsai_image_4b/README.md
[F6-WEVIS]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert/image_cutout/README.md
[F6-OCR]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert/ocr_verification/README.md
[WEVIS-FILES]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert/image_cutout/kotlin_gpu/android/app/src/main/java/com/google/ai/edge/examples/image_cutout/ModelFiles.kt#L30-L78
[OCR-FILES]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert/ocr_verification/kotlin_gpu/android/app/src/main/java/com/google/ai/edge/examples/ocr_verification/ModelFiles.kt#L33-L64
[QWEN-TTS-LEGACY]: https://huggingface.co/litert-community/Qwen3-TTS-12Hz-0.6B-Base/tree/3993383816e7108c6c57cf9329e6446e55fd3bde/legacy
[MATHJAX]: https://github.com/google-ai-edge/litert-samples/blob/f9f0e000630e9cdd6d263c4cd80495e4231a2dc8/samples/litert_lm/math_to_latex/android/app/build.gradle.kts#L53-L57
[Q1]: https://huggingface.co/litert-community/Qwen2.5-Coder-1.5B-Instruct/resolve/7840209bfd393408ef028cf55921d831baa1e4bf/Qwen2.5-Coder-1.5B-Instruct_int4.litertlm
[R1]: https://huggingface.co/litert-community/Xiaomi-OCR-0/resolve/9012fe326c2b3038f97623258486ffeb52513260/Xiaomi-OCR-0_int8.litertlm
[R2]: https://huggingface.co/litert-community/MiniCPM-OCR-1.0/resolve/c818533aaf2264fd38cf5e494ff74e0ea8b1dc62/MiniCPM-OCR-1.0-VL_int8.litertlm
[R3]: https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/24d962e906c7d332c6428e71c9676855024569e2/embeddinggemma-2-740m.litertlm
[R4]: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm
[R5]: https://huggingface.co/litert-community/LFM3-1.7B-LiteRT/resolve/9bbda264c623f30c905b9c247761ee7463f2b00d/LFM3-1.7B_mixed_int4_ekv2048.litertlm
[R6]: https://huggingface.co/litert-community/LFM3-VL-2B-LiteRT/resolve/f547c061f063788155b9f95a19959293f558418b/LFM3-VL-2B_int4_ctx4096.litertlm
[R7]: https://huggingface.co/litert-community/Mage-VL-Cowork/resolve/2318e906b25048599ef48ce1f93427f36858b339/Mage-VL-Cowork_vision-fp16.litertlm
