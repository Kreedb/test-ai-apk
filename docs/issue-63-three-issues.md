# #63 本轮三项交付：#66、#67、#68

基线 `main@427c8b8`，已包含 #77–#80。按“本次先处理 3 个”选择尚开放且前置已合并的 #66 系统监控、#67 自定义模型、#68 既有场景/录音/整幅超分。#64、#65 已关闭；#70–#76 的额外 LiteRT 场景继续按 [剩余矩阵](issue-63-remaining.md) 跟踪。

## 交付对应

| 子任务 | 本轮补齐 | 详细验收 |
| --- | --- | --- |
| #66 | 有界后台采样、按显示指标读取、完整诊断显式复制、短读/截断/权限/陈旧值、Rockchip 单核及 Samsung 真实身份发现、GPU 已知单位 | [监控](issue-66-verification.md) |
| #67 | 重导保留名称/配置/开关、输入就绪诊断、按需取消与结果隔离、模型删除前排空、自检读者保护、逐轴量化事实、结构引用验证、INT64 精确值 | [自定义模型](issue-67-verification.md) |
| #68 | 加载中超分按压不丢失、迟到错误隔离、四旋转整幅拼接、固定 YAMNet 协议与 WAV 输入、TTS 相机操作、标签/透明度/关键点得分、学习原始图、旧页面状态隔离 | [场景](issue-68-verification.md) |

复用现有相机、模型目录、下载器、线程所有者和存档格式。模型权重仍按需下载，APK 只含内置 YOLO26n；没有增加应用依赖或更改生产签名流程。

## 实际验证

### 构建与单测

JDK21、项目固定 Android SDK/LiteRT2.3.2，命令：

```sh
./gradlew assembleDebug assembleRelease lintDebug testDebugUnitTest assembleDebugAndroidTest --build-cache --parallel --console=plain --no-daemon
```

- 最终 Debug/Release/测试 APK 构建和单测成功：[构建日志](https://uploads.pullfrog.com/agent-uploads/512d8717-6fcb-47fb-bc70-07bbf8579f42/bg-96d4a11c.log)。冻结源码后单独运行 `lintDebug --no-parallel --max-workers=2` 成功，**0 errors / 35 warnings / 1 hint**：[lint 日志](https://uploads.pullfrog.com/agent-uploads/53cf057f-f8b3-403d-9180-1f4df92a7a0d/bg-59717dda.log)。
- 最终 JVM 回归：**622 tests、0 failures、0 errors、14 skipped**：[逐套结果](https://uploads.pullfrog.com/agent-uploads/b7670bf9-cfb5-415d-b41b-20439c7bc0d1/check-results.json)。本轮新增的真实八模型结构 fixture 已通过仓库外 init script 启用；14 个跳过项是既有图像/tokenizer/图输出/网络可选 fixture，未计为通过。
- 两 ABI Release 签名校验成功，arm64-v8a 100891272 字节、x86_64 31243155 字节，均仅内置 YOLO26n：[APK 清单](https://uploads.pullfrog.com/agent-uploads/c87a1182-288d-4b4c-8907-c6747ee1f805/packaging.json)。
- 独立审计确认非仿射量化 `details_type` 原先被忽略；修复为保存真实字段并拒绝不支持的通用转换，增加实际 schema fixture。复审确认已解决，无新增问题。

### Android 原生

两个设备均为 **x86_64 软件模拟器，未使用 KVM**，不代表 arm64 手机性能：

| 检查 | 结果与证据 |
| --- | --- |
| API24 和 API35 输入/生命周期/超分/学习输入 | 各 **15 项全部通过**，[API24](https://uploads.pullfrog.com/agent-uploads/0b0c95ef-90e8-4687-ba6d-3c6ba4d91472/bg-06358bf7.log)、[API35](https://uploads.pullfrog.com/agent-uploads/6aadf870-872a-4631-8197-dd717d880587/bg-914baf88.log)；真实 Bitmap/Canvas，超分算子替身用于逐像素/竞态断言 |
| API35 七个真实模型 | **5 项全部通过**：[日志](https://uploads.pullfrog.com/agent-uploads/c60889f1-d81a-4663-940c-fb12975d85a3/bg-430f94ca.log)、[输出](https://uploads.pullfrog.com/agent-uploads/0fb479ff-7fe8-44ca-a20b-9addc8c9cfa0/real-model-outputs.txt)；YAMNet Silence、MobileNet military uniform、SSD person/tie、DeepLab 人物区域、MoveNet 静态不计次、真实 ESRGAN 四块、真实特征训练及存档恢复 |
| API24 更新后的输入/取消/精确整数/自检/剪贴板 | **13 项全部通过**：[日志](https://uploads.pullfrog.com/agent-uploads/1c3ae7ea-b361-4622-9496-fe677bfb7ecf/bg-5249a31b.log)；含超过 8×240 字的剪贴板原文逐字节一致、INT64 大整数、实际 native 取消、删除等待自检与动态输出 |
| API24 最终组合复验 | **21 项全部通过**：[日志](https://uploads.pullfrog.com/agent-uploads/31b925bb-0e30-444e-af73-9f85e9586797/bg-645da40e.log)、[APK 身份](https://uploads.pullfrog.com/agent-uploads/ab8c798a-33fd-4c65-bae3-243235937ce1/native-results.json)；含全部受影响输入、精确整数、取消/删除、自检、姿态实际 ADD 图的 A/B/A 精确输出及同线程关闭、整幅渲染、学习输入 |

资源校验：[七模型固定大小/SHA-256](https://uploads.pullfrog.com/agent-uploads/e5bb9315-ca58-45da-bfb2-b30c17ad1454/download-results.json)。语义输入是已知照片/静音/合成训练样本，不是完整准确率数据集。

**超分实测限制**：软件模拟器 64×48→256×192 四块耗时 97.77 秒，采样进程 PSS 最大 101431 KiB；该算子测试不启用生产 30 秒预算。生产插件超过预算保留原图，不可据此宣称手机全幅超分时延通过。

### 实际界面、权限与升级

- [监控配置与复制](https://uploads.pullfrog.com/agent-uploads/8413379b-232f-4d63-8b85-8d24b8ff5502/monitor-ui-evidence.zip)：通过 UI 保存 1000 ms/测试全部，姿态关闭仍有 FPS/CPU，指标与声音结果各自显示；[停用监控](https://uploads.pullfrog.com/agent-uploads/d46ceb64-45ac-4366-a8f3-3e92bf6da3d7/monitor-disabled.xml)后面板和诊断动作清空。
- [麦克风系统证据](https://uploads.pullfrog.com/agent-uploads/76424f15-bde7-46f3-836c-93e96a0be328/microphone-privacy-final.zip)：唯一 16 kHz 单声道 PCM16 客户端，系统隐私开关使其 `silenced=true`，App 撤回分类并提示暂停；解除后同会话恢复，后台无客户端，前台重新建立唯一新会话。
- [WAV 闭环](https://uploads.pullfrog.com/agent-uploads/3a31a457-68c6-434a-a797-b59b37bb338b/wav-ui-evidence.zip)：实际 SAF 选文件→保存来源→相机显式运行→Silence 80.1%，期间无录音客户端。测试 WAV 为 1 秒零 PCM，32044 字节，SHA-256 `643f8a8dc8bd9c19225afffad2becfec5426180b3749cb208abdf1a6c8354efc`。
- [TTS 入口](https://uploads.pullfrog.com/agent-uploads/bafb2f86-118a-447e-a8eb-16e5f21e061f/tts-ui-evidence.zip)：配置、总开关、引擎选择可操作；API35 AOSP 镜像没有可用中文 TTS 服务，明确显示未就绪，不计为成功朗读。
- [同签名覆盖升级](https://uploads.pullfrog.com/agent-uploads/121a46d9-131f-4b5f-9a40-06a7060c6755/upgrade-evidence.zip)：CI43025864024 基线与新 Debug 的测试副本统一临时签名，直接 `adb install -r`，没有卸载或清数据。旧代码真实训练保存的分类头、3 个模型及其 properties/name/enabled、音频缓存和偏好共 **15 文件哈希完全不变**；新 UI 显示原阈值0.61和模型名称，旧 NUMBERS 配置实际输出 `1.25, -0.5, 0.0, 1.0`。
- API24 实际缩减 Release 同签名覆盖后启动成功，[旧模型与阈值](https://uploads.pullfrog.com/agent-uploads/39fee379-875d-44a0-8717-d5c1e0bda88e/release-models.xml)保留，[真实数值推理](https://uploads.pullfrog.com/agent-uploads/b1fbfbc0-13fb-43d0-94cb-44b6b4557bdf/release-numeric.xml)输出正确。该设备检查在最后量化诊断补丁前执行；最后 APK 已重新构建、签名/资源校验并通过 Debug 原生组合复验。

## 检查中发现并纠正的失败

- 监控 procfs 成功节点消失时被提前过滤，无法保留 stale 值；新增回归最初失败，已修复并纳入最终通过结果。
- 一次主包/测试包来自不同构建，出现 `NoSuchMethodError: ConfiguredModelAccess.open(ConfiguredModel)`；同次重建后 13 项全部通过，不计初次失败组为通过。
- 姿态删除的旧零算子 I/O alias 测试图在组合测试输出未初始化数据，不适用于 CompiledModel 独立外部缓冲区。改为真实非零 ADD、不同输出张量、红/绿/红精确断言，最终 21 项组合全部通过；没有修改生产数值逻辑或放宽容差。
- 修改源码期间一次组合检查的 `lintAnalyzeDebug` 停留在空中间结果，主动停止；冻结后单独 lint 成功。其它构建目标随后也已在冻结源码重跑成功。
- 软件模拟器出现 SystemUI、电话组件及 App 冷启动 ANR，日志为输入焦点超时及高 CPU 压力；预编译和恢复后取得上述实际证据。基线录音授权后曾停在非 RESUMED 状态，Home 返回后恢复；不能把本环境所有相机权限组合声明为通过。

## 剩余验收与 issue 状态

三项功能修复交付 PR，合并前不提前关闭子 issue。物理 arm64 手机的厂商 NPU/GPU 读取与数值、真人动作/实物识别、TTS 中文引擎及半双工、TalkBack、全部手势、持续性能和热/内存仍待验证；#63 和总验收 #69 保持开放。#70–#76 额外模型任务不在本轮三项范围内。
