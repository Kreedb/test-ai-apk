# #63 接续审计

## 基线与关闭原则

审计基线为 `dc8792db2a386c1168f9e806942002933e1481d6`，已包含合并的 [#78](https://github.com/Kreedb/test-ai-apk/pull/78) 和 [#79](https://github.com/Kreedb/test-ai-apk/pull/79)。旧 issue 的要求按 #63 后续真机反馈校准：删除模型工作台、模型逐项开关、整幅画面超分。

已交付的代码、自动检查和设备证据分别记录。下载完成、模型能加载或 PR 已合并本身不能证明场景正确。新修复提交 PR 后，仍待合并的 issue 不提前关闭。父 #63 与总审计 #69 继续开放。

| Issue | 基线审计 | 接续范围 |
| --- | --- | --- |
| #64 插件基础 | 已完成并关闭 | 卡片/开关独立、配置可观察、会话/配置/按压代次、基础预览；设备组合回归归 #69 |
| #65 镜像与 YOLO11 | 网络迁移已合并，取消路径有缺口 | 阻塞读取取消、安装与模型注册一致性 |
| #66 监控 | 已有监控与厂商探测，指标语义有缺口 | 区分姿态耗时、同步帧处理和异步推理；未知 NPU 单位不能推断成百分比 |
| #67 自定义模型 | UI/配置迁移完成，非图像结果接线有缺口 | 显式 PCM 协议、文件/文本/数值按需结果回到相机；旧数据保留 |
| #68 既有场景 | 场景迁移与反馈修复已合并，麦克风异常有缺口 | 负数读取失败、隐私静音/采集受限、状态撤回与恢复 |
| #70 资源与运行时 | 只有目录/安装库及 CPU 自检 | 资源管理入口、取消/进度/续传校验、完整集 SAF 导入、资源租约、LM 运行时及后端比较 |
| #71 视觉 | 新 Zoo 算法未接入 | 12 个 Zoo 任务；其它 legacy 模型继续逐项验收 |
| #72 图像处理 | 新 Zoo 算法未接入 | 9 个 Zoo 任务；提示分割、视频记忆、DIS 等另有完整配方要求 |
| #73 音频 | YAMNet/系统 TTS 不等于本任务完成 | 8 个 Zoo 任务；其它 ASR/TTS 引擎仍须独立实现与验证 |
| #74 文本 | 新文本算法/LM 未接入 | 5 个 Zoo 决策任务与首个实际本地文本引擎；其它配方继续验收 |
| #75 多模态 | 未实现 | 等待视觉、音频、文本的可复用闭环，逐项核对资源与接口 |
| #76 生成/抠图/OCR 复核 | 未实现 | 等待运行时与前置图像/文本能力，保留 >2 GiB、内存及实际输出要求 |
| #69 总审计 | 未完成 | 汇总以上功能、升级、真机、release、性能与许可证证据 |

#70–#76 的原生父子关系和 `blocked-by` 已补齐，依赖与各 issue 正文一致。工作流启动仍返回 `could not create workflow dispatch event: HTTP 403: Resource not accessible by integration`，本轮由会话内执行器实现，协调者核对证据。

全部非 Zoo 剩余项、固定资源、真实访问/硬件限制和下一切片见 [剩余场景接续矩阵](issue-63-remaining.md)。

## 基线验证

- JDK 21：`compileDebugKotlin testDebugUnitTest --build-cache --parallel --console=plain` 成功，301 tests，0 failures/errors，1 skipped（可选网络冒烟）。
- YOLO11 官方直连与 gh-proxy 均 HTTP 200、11,835,168 字节、固定 SHA-256 一致：[网络证据](https://uploads.pullfrog.com/agent-uploads/e68a9006-922a-4439-8c77-0787b00e38d6/yolo11-network-audit.json)。
- Android 15/API35 x86_64 软件模拟器安装 CI `42996479614` 的基线 APK。预编译后启动成功；切换姿态开关不打开弹层，停用时可继续打开配置；全插件关闭时有基础相机图像且无姿态状态文字。
- 软件模拟器初次启动有系统服务 ANR，相机持续运行后系统服务重启；不据此声称手机相机、GPU/NPU、实时吞吐或长期稳定性通过。
- 另在 Android 7.0/API24 x86_64 软件模拟器安装并预编译同一基线，首页与四个 tab 正常显示：[最小 API UI 证据](https://uploads.pullfrog.com/agent-uploads/06a031e2-f8f1-40e0-968d-6f1becfba488/baseline24.xml)。这不是新接入模型的运行证明。

![基线全部插件关闭时的相机预览](https://uploads.pullfrog.com/agent-uploads/f559ba9a-dcc2-4f7b-8d04-dd265a4165e4/baseline-camera.png)

## 固定模型与独立 CPU 对照

上游固定 `google-ai-edge/litert-samples@f9f0e000630e9cdd6d263c4cd80495e4231a2dc8`。实际下载目录 34 集合、76 文件，共 5,336,091,224 字节，全部大小/SHA-256 匹配；47 个 TFLite 图的输入输出已逐项读取。权重只放测试临时目录，不打包进 APK。

- [76 文件下载校验](https://uploads.pullfrog.com/agent-uploads/5c26eba7-9220-42f8-85d2-2426c922633e/download-results.json)
- [实际张量契约](https://uploads.pullfrog.com/agent-uploads/1b9dd522-76d7-423c-8ff8-489f4e7b36bd/tensor-contracts.json)
- [主机 CPU 语义对照](https://uploads.pullfrog.com/agent-uploads/b61645ee-c82f-4718-a529-687b05b4d832/issue63-host-verification.json)

主机运行时为 `ai-edge-litert 2.3.0`，与 APK 内 `2.3.2` 不同，以下不是 Android 驱动证明：

| 模型 | 已知输入 | 实际结果 |
| --- | --- | --- |
| Zipformer small | 上游 `what_time_is_it.wav`，16 kHz | `WHAT TIME IS IT`，完整 filterbank→图→CTC |
| CREPE | 220/440/880 Hz 正弦 | 219.885/440.244/881.400 Hz，置信度 0.924/0.966/0.969 |
| Vision-RWKV-S | 上游 Grace Hopper 照片 | Top-1 `military uniform`，0.5374 |
| NIMA | 同照片 | 美学评分 3.99989，分布和为 1；不是清晰度真值 |
| PP-OCRv5 recognizer | 合成 `HELLO`、`OPEN`、`1234567890` | 三项匹配；另一个 `GATE 42` 被识别为 `GATE4Z`，保留误识别记录 |

这些输入用于核对协议与标签，不是准确率评测集。其余模型必须有自己的实际执行证据，不能从上述结果推断全部 34 项可用。

独立算法执行器另提交并经协调核对的参考证据：

- [12 条视觉流水线](https://uploads.pullfrog.com/agent-uploads/c56abc51-8751-49ed-ad74-7d63f0358ea3/vision-pipelines-audit.json)，主机 LiteRT 2.2.0 CPU：RF-DETR 在照片中检测到 person/tie；XFeat 对已知 `(16,8)` 平移得到相同中位匹配位移；MoViNet 重置后的输出与新会话相同。人脸朝向/人流/植物在该照片上只核对数值协议，不能称为场景准确率通过。RAM++ 主机峰值约 3,017 MiB，须按实际资源门槛加载。
- [9 条图像处理参考](https://uploads.pullfrog.com/agent-uploads/c3b298d5-c75b-4907-98ff-b4abeab16cae/report.json)，主机 LiteRT 2.3.0 CPU：图级 Interpreter/CompiledModel 对照、道路/车道覆盖、人物实例、透明 alpha、超分和深度参考图；Android Bitmap 渲染还需 instrumentation 比较。
- [8 条音频参考](https://uploads.pullfrog.com/agent-uploads/44f40f2e-bf5b-4cde-9673-c56042adf9ca/results.json)，生产 Kotlin DSP/解码与主机 LiteRT 2.3.0 图：PANNs 的语音标签、Basic Pitch 的 A4/MIDI69、TIGER 语音轨、CMGAN 加噪对照、DAC 真实码本与重建、Matcha 多图合成。DAC 有 8 采样点对齐偏移；Matcha 生成句子回转写为 `THE RAIN IS SOFT TO DAY`，不能据此代替人耳可懂度测试。
- [5 个文本引擎、8 个实际输入](https://uploads.pullfrog.com/agent-uploads/f85a2ada-4673-49b2-b5ac-705af1a9fe97/cpu-results.json)：Kotlin 分词/路由/embedding 与独立 Python 输入逐位一致；CPU 2.3.0 完成实际图执行及 A/B/A 状态复测。GLiNER treaty 样本仍输出错误的 `yes`（0.996264），与上游数值一致但语义答案应为 `no`，该模型错误未掩盖。

完成算法切片的独立回归：视觉 31 tests，图像处理 33 tests（含真实输出回放），文本 29 tests（开启真实 tokenizer/图输出 fixtures）。这些 scoped 检查不替代最终整项目构建与 Android 调用验证。

NPU 单位修复的实际 Kotlin/JUnit 独立检查通过 25 tests：[结果](https://uploads.pullfrog.com/agent-uploads/5a23a31b-dd61-462a-806b-8ce01e55210e/npu-regression-results.txt)，仍需纳入整项目回归。

## 本地文本运行时核查

实际审计 LiteRT-LM 0.19.0 AAR：minSdk24，arm64/x86_64 各含 `liblitertlm_jni.so`，没有第二份 `libLiteRt.so`，无需 `pickFirst`。Qwen2.5-Coder-1.5B 固定资源 1,117,385,648 字节，SHA-256 `273ecc7771ba2dd5fe1bb6d4d4726ad0353102f04ad094082ccf59bca9f21213`。

主机实际生成覆盖算术 `42`、代码文本、`Merci`、原生取消后再次生成及输出 token 限制。审计发现初版 `:nocache` 配置会重复打包权重，单次约 7.4 GiB、重复请求最高约 8.3 GiB，不能采用原先 2304 MiB 门槛。`:memory` 虽在主机降低了内存，但 API24 模拟器内核实测 `memfd_create` 返回 `ENOSYS`，原生可能无缓存继续加载。

最终使用**每请求独立的私有文件缓存**共享打包权重，关闭 Engine 后删除，不保留会话/KV。[最终六项功能对照](https://uploads.pullfrog.com/agent-uploads/00a8e667-da81-4c61-9841-90812e7a414e/qwen-lm-reference-private-cache.json) 通过；[四次完整预算内存记录](https://uploads.pullfrog.com/agent-uploads/ed916ba8-07c4-43bb-8f3d-d888fb77a6aa/qwen-lm-memory-private-cache.json) 最大约 1913 MiB，关闭后约 514–725 MiB；临时缓存约 834 MiB、逐请求删除。当前要求至少 2560 MiB 可用内存与 1536 MiB 可用存储，门槛来自主机测量加余量，不是 Android 峰值保证。LM→姿态同进程与 shrunk-release JNI 仍需实际设备验证。

## Android 原生回归

已在 Android 7.0/API24 x86_64 的新 Debug APK 上执行 **7 tests 全通过**：4 项实际 Bitmap/Canvas 渲染与坐标测试、3 项真实动态输出 TFLite 自检回归。[instrumentation 结果](https://uploads.pullfrog.com/agent-uploads/ff13ba06-567f-4e6d-b3a0-5b5aa0b6f242/bg-ad0166a7.log)。其余模型实际 Android 推理仍逐项验证，不把这些小图回归当作所有模型通过。

随后通过的实际 APK/LiteRT2.3.2 CPU 模型检查：
- [NIMA 固定照片与十档分布对照](https://uploads.pullfrog.com/agent-uploads/9e55abc6-cf0f-4f16-ac8d-02b38ea2aa6a/bg-291e6abb.log)，1 test。
- [CREPE 三个已知频率音调](https://uploads.pullfrog.com/agent-uploads/0bcfa2f3-42a6-4f12-9ceb-1f92cf0148c4/bg-a7f89d6e.log)，1 test。
- [Basic Pitch A4 音符及起止时间](https://uploads.pullfrog.com/agent-uploads/c636d511-1fab-4e55-8991-f349d3ed84b4/bg-7c44c139.log)，1 test。
- [Zipformer 完整语音转写与取消后重跑](https://uploads.pullfrog.com/agent-uploads/94ccea98-47ee-4df5-9186-1476f23939aa/bg-54faefd1.log)，1 test，实际 `WHAT TIME IS IT`。

软件模拟没有硬件加速，单个检查可耗时数分钟；这些时间不能代表手机性能。

MODNet 实际 Android golden 比较在软件模拟器持续计算但未完成，停止该次测试以运行小输入协议回归；不计为通过。物理设备上的图像处理/内存结果仍待验。

API24 上最终 shrunk Release 覆盖安装后 `am start -W` 返回 `Status: ok`；实际界面保留旧 YOLO 阈值 `0.61/0.67`、防抖3帧和已导入模型名称/开关，完整资源菜单能打开 Qwen 的固定源、许可、大小及 SAF 整集导入按钮。[模型列表](https://uploads.pullfrog.com/agent-uploads/e5098b4e-2b98-42e0-92cc-321a1f74bbf7/final-release-models.xml)、[资源入口](https://uploads.pullfrog.com/agent-uploads/49fa9ee4-4ffd-4fa7-892b-2a97fc7c108b/final-release-qwen-import.xml)。这证明 release 启动/入口，尚不证明 LM 原生生成。

![最终 Release 完整模型资源入口](https://uploads.pullfrog.com/agent-uploads/e188c883-0249-446d-b6cb-55d196d87562/final-release-resource.png)

### 自定义输入原生复测发现

四项 NUMBERS/BINARY、STRING、WAV/PCM、IMAGE 原生测试中，首次 STRING 失败，其余三项通过。根因不是模型或输出解码：LiteRT2.3.2 的 Java `String` JNI 写入 modified UTF-8 并把末尾 NUL 计入长度。改用该运行时正式支持的逐元素 UTF-8 `byte[]` 输入，保留真实 NUL 和非 BMP 字符；不裁掉输出中的 NUL 来掩盖模型输入变化。首个空元素受当前 Java 维度检查限制，明确报原因。修复增加精确字节与 scalar/多维/空后项回归，native 断言覆盖中英文、emoji、组合字符、嵌入/尾随 NUL，并包含失败时实际 codepoints。[实际 AAR/JNI/反汇编证据](https://uploads.pullfrog.com/agent-uploads/5f9f30ed-63aa-48ff-a22e-607db2bb8ff3/handoff.md)。

修复后 API24 的 [四项原生输入回归全部通过](https://uploads.pullfrog.com/agent-uploads/1a961fd6-d059-44c2-89ce-2a63623c9e05/bg-092932ba.log)，整合单测为 [580 tests / 0 failures / 0 errors / 1 skipped](https://uploads.pullfrog.com/agent-uploads/f67bef15-4103-4a05-8d0f-74c982502a03/unicode-final-unit-counts.json)。独立复审核对实际 Java/JNI API，未发现该修复的未解决问题。

该补丁的 [Debug/单测/测试APK重建](https://uploads.pullfrog.com/agent-uploads/fcaac58c-364b-42b4-a005-8bb6d586e465/bg-7b936bac.log) 及 [Release/lint重建](https://uploads.pullfrog.com/agent-uploads/bf8181c3-06c2-44c4-8313-ea2d4d5e5fbd/bg-026bbf2f.log) 均成功，lint仍0错误；最终Release签名有效，arm64 100,856,784字节、x86_64 31,208,667字节，仍只内置YOLO26n。

## 整合检查与复审

稳定源码上执行 `assembleDebug assembleRelease lintDebug testDebugUnitTest assembleDebugAndroidTest --build-cache --parallel --console=plain --no-daemon`，另用仓库外临时 init script 为测试传入已准备的真实图像/tokenizer/输出 fixtures，**全部成功**。[完整日志](https://uploads.pullfrog.com/agent-uploads/d15d9306-6e88-4cce-8631-80604412aa5c/bg-b19a2404.log)、[577 tests / 0 failures / 0 errors / 1 skipped](https://uploads.pullfrog.com/agent-uploads/ec58ba4d-f969-41fd-8ca0-a61a74f170c3/final-unit-counts.json)。唯一默认跳过项是可选网络大文件冒烟，[已独立通过](https://uploads.pullfrog.com/agent-uploads/0271c6ce-89e3-48e3-851a-93fcee494599/network-smoke-results.txt)。生产安装器在 192 MiB JVM 堆下完成 OCR 整套与 1.04 GiB LM 流式导入/哈希/租约/删除，[证据](https://uploads.pullfrog.com/agent-uploads/2a66b6c2-1ee0-46dc-b843-0b44d1b4d0c4/real-smoke-results.txt)。

Lint **0 errors / 34 warnings / 1 hint**，涉及工具版本、固定方向、可分配存储建议及 KTX 等；没有禁用检查规则。两次整合中 lint 曾在正在修改的 Kotlin 文件上出现 FIR/ExperimentalDetector 内部异常，源码冻结后完整检查通过。

Release 两 ABI 签名校验成功：arm64-v8a 100,856,668 字节，x86_64 31,208,551 字节；均只内置 YOLO26n 权重，另有对应 ABI 的 LiteRT-LM JNI，无额外模型文件捆绑。最终重新打包纳入 AAR 原始 LICENSE/THIRD_PARTY_NOTICE，并核对 APK 中字节完全一致。独立预提交审计指出短录音静音恢复死路，已补齐有界恢复等待、显式重试、取消/超时释放及按需权限，复审未发现未解决问题。

## 同签名覆盖升级

使用同一个临时 debug 签名重签基线和新 Debug 测试副本，在 API24 模拟器的基线安装中保存：已编辑的通用模型配置、模型开关、名称、权重，插件/姿态/镜像/主题偏好，以及用旧版 `SoftmaxHead` 实际训练并保存的命名 v2 分类头。旧版启动并显示保存的插件开关后，再执行 `adb install -r` 覆盖新 APK。

[升级前](https://uploads.pullfrog.com/agent-uploads/3ddfc67e-1eca-4f0c-8347-42fcd1c8a962/device-before-upgrade.json) 与 [升级后](https://uploads.pullfrog.com/agent-uploads/e6221686-b14e-4435-87da-ace506197045/device-after-upgrade.json) 六个文件的字节数/SHA-256 完全相同，覆盖期间没有卸载主 App 或清数据。第一次直接安装 Gradle APK 因默认 debug key 位于另一个目录而返回 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；改为只重签测试副本后成功，不修改生产签名流程。

## 仍需设备证据

API24 基础功能、物理 arm64 手机、GPU 数值正确性、厂商 NPU 节点、相机/音频组合、TalkBack、同签名升级、长时内存/温度/功耗继续按 #69 验收。软件模拟器与主机 CPU 不替代这些项目。
