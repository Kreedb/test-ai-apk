# #66 系统监控收敛与验证

## 范围与结论

基线：`main@427c8b8`，包含 #77–80。本轮收敛系统监控，并在共享插件页接入 #68 的场景与声音配置。

专项及整合回归已通过，实际设备证据见下；厂商节点仍待物理设备测试。通用 `load` 单位与普通应用可访问的 NPU 累计 busy/total 缺少可证契约，保持 `raw/unsupported`。最终交付状态统一见 [本轮三项验收](issue-63-three-issues.md)。

## 实际根因与最小修复

| 根因 | 修复与检验 |
| --- | --- |
| “测试全部”的唯一输出先被 `excerpt` 缩短，再受 8×240 字宿主消息限制；Rockchip/Samsung 成功后提前返回，失败位置与辅助属性丢失 | 保留每次节点/目录访问的路径、时间、状态和**原始换行/空格**；增加现有插件 action `npu_diagnosis_copy`，直接复制完整有界报告。概要不再是复制数据源。报告包括所有尝试、辅助 `load_policy/load/fps_load`、两处 Rockchip 节点、Qualcomm 完整睡眠统计、设备信息及读取限制。同轮重复读取复用结果，并在报告内引用同一来源。 |
| `InputStream.read()` 一次短读即被当作完整文件；4096 字节上限无截断说明 | 循环读到 EOF/4096+1，短读正确拼接；超限明确 `truncated`，保留前缀但禁止以它解析成功利用率。每目录64项、每轮256操作/128 KiB原文/200 ms预算；枚举失败、取消、超时也可诊断。 |
| sysfs 读取在 `FrameCompleted` 内同步执行，插件/引擎锁同时包住 I/O；关闭/后台可被慢节点拖住；隐藏指标仍采所有设备项 | 新增一个运行中＋一个最新待处理请求的 `MonitorUsageSampler`。无计时轮询，不持有相机像素；帧到达且刷新门限允许才请求。重配置/停止使代次失效、清待处理与展示，节点返回后取消后续 I/O；迟到结果不复活。CPU/GPU/NPU 分别按显示消费者读取，全部隐藏时无设备采样。 |
| Rockchip 解析只支持 `CoreN`，会忽略后续非法核；两处位置压缩失败导致 procfs 旧值无法转 stale | 支持固定驱动单核 `NPU load: 12%`/空白零值、多核逗号/末尾逗号；整段解析，非法/重复核拒绝。保留两个位置的每项结果，使按路径缓存正确保留≤5秒旧值并明确 `stale`，同时保留当前失败原因；过期/重启清除。 |
| Samsung 只查 class/devfreq，实际属性注册在平台 device；`load_policy` 被当作标量或直接塞进短概要；任意同名 `rq_load` 可误借单位 | 从已核实的 `exynos-npu` 平台驱动绑定设备目录发现实际设备，不猜地址；回退 class 发现要核对 `name`/DT compatible。只给已确认 Samsung 身份的整数0–10000赋0.01%单位。解析带 `*` 的策略菜单；load/fps_load 独立保留raw，不相加。`scheduler_enable=0` 明确为停止更新的 stale。 |
| GPU 泛化正则会把任意首个数字/未知负载当百分比 | 仅有已核实 KGSL `gpu_busy_percentage` 与 `gpubusy` 合同产生百分比；未知 GPU 属性也保留raw来源。数值要求完整匹配、有限，busy>total/零分母拒绝。KGSL两值是驱动已完成采样窗，不作累计差分，更不作为第五种 NPU 方案。 |
| #68 新设置已实现但旧入口仍在 | 调用 `ExampleSceneSettingsDialog`、`AudioClassificationSettingsDialog`，直接保存Map；删除旧private弹窗和只供旧入口使用的小组件/import。声音卡片区分共享麦克风、待选WAV、已选WAV；没有改新弹窗实现。 |

### 整合曾失败的 stale 回归

协调者报告 `bg-f76774ff.log`：613 tests，1 failure，14 skipped。已读取 XML，确认为 `DeviceUsageSamplerTest.disappearingRockchipProcfsValueIsStaleEvenWhenDebugfsRemainsDenied`，期望 `STALE` 却得到 `PERMISSION_DENIED`。

核对当时整合 class 的反汇编，`probeRockchip()` 末尾仍为 `filter(state != MISSING).ifEmpty { take(1) }`；debugfs 一直 denied、procfs 从35%变missing时，procfs 的失败结果在进入按路径缓存前已被过滤掉。冻结源码已改为保留两个位置，每项均保有读取证据。原断言保留：**第二次为35%的stale、来源仍是procfs、当前procfs missing仍在诊断中**。最终独立运行此单项为 `runs=1 failures=0`，包含它的专项为54 tests全通过。

这不是把权限失败改成0%、不是放宽断言，也不是把新失败重新标成成功。异步缓存的读取时间另显示“采样Xs前”，慢帧时不会冒称刚采到。

## 已复核的调用链与语义

- 监控只注册 `CAMERA_FRAMES/MONITOR/TEXT/ACTIONS`，不订阅姿态结果；基础帧由 `CameraSession` 在无模型时仍产生。PoseEnhancement 的启停不是监控消费者条件。
- `CameraSession` 的 ON_PAUSE/ON_STOP/close → `PluginHost` BACKGROUND/STOP/LEAVE → `Stopped` 清输出、撤销会话；新启动清CPU基线/FPS窗口/旧值。测试覆盖队列尚未执行、读取正在阻塞、停止后迟到和恢复新会话。
- `PluginHost` 已把 interval monitor文字保留在 `snapshot.monitors`，不要求与最新图层帧号相等；`CameraScreen.MonitorPanel` 已位于TopEnd。普通指标是TEXT且被底部报告过滤。没有新写 AppLog；显式复制诊断只写剪贴板。既有后端比较自身日志不属于周期监控采样。
- FPS来自实际被处理的相机帧流的单调时间戳，不是 `1000/inferenceMs`，也不是传感器标称帧率。保留“慢流至少两帧”的原算法和3项 `CameraFrameRateTest`。
- CPU使用进程CPU时间差÷同窗墙钟时间差÷可用核数，标签为“CPU(应用/总算力)”。首窗/停启后需先建立基线。没有改成系统CPU或频率加权值。
- 主姿态预处理/推理链路(含读回)/后处理，同步帧处理(含分发，不含异步/显示)，异步最近完成(带age，不相加)保持独立；GPU/NPU主姿态后端显示实际分区未知/可含CPU。
- `StatusScreen` 只有一次静态系统信息读取、上次加载后端与静态机型；没有 `DeviceUsageSampler`、循环/计时采样或 `SupportLinks`。相关代码本已正确，未重写。

## 固定来源证据与限制

1. **Rockchip**：[rknpu_debugger.c@95a3ad83… L39–75](https://github.com/rockchip-linux/kernel/blob/95a3ad83f7f90e0e672b9c99b0858b4964318667/drivers/rknpu/rknpu_debugger.c#L39-L75)：num_irqs>1时才打印Core及逗号；`%2.d%%`零值可为空白。两个导出路径是位置，不是两种利用率指标。仍只有源码/fixture证明，普通App权限未做物理验证。
2. **Samsung**：[npu-scheduler.c@69515fbb…](https://github.com/ExtremeXT/android_kernel_samsung_exynos990/blob/69515fbb7a4395898c05a8624f76a12afbac11c5/drivers/vision/npu/core/npu-scheduler.c) 的 `npu_scheduler_calculate_rq_load` 用 `(total-idle)*10000/total`，`npu_show_attrs_scheduler` 的 `load_policy`用`*`标选中项，`__npu_scheduler_work`在enable=0时不更新。[npu-device.c L719–730](https://github.com/ExtremeXT/android_kernel_samsung_exynos990/blob/69515fbb7a4395898c05a8624f76a12afbac11c5/drivers/vision/npu/core/npu-device.c#L719-L730) 给出 `samsung,exynos-npu` 与平台驱动名 `exynos-npu`；[npu-scheduler.h](https://github.com/ExtremeXT/android_kernel_samsung_exynos990/blob/69515fbb7a4395898c05a8624f76a12afbac11c5/drivers/vision/npu/core/npu-scheduler.h) 定义idle/fps/rq/fps-rq hybrid顺序。rq_load是**请求队列忙碌率**，非MAC硬件占用。
3. **通用厂商/devfreq**：只有名称/数字范围/`%`后缀不能建立单位。继续输出完整raw与unsupported，不读频率、地址、trans_stat作为利用率。不把尚无合同的这两策略说成已支持的百分比驱动。
4. **GPU合同**：[KGSL固定e3f6acd6… `gpubusy_show` L1135](https://github.com/LineageOS/android_kernel_qcom_sm8250/blob/e3f6acd662618dabb3a7a953e48743019badc373/drivers/gpu/msm/kgsl_pwrctrl.c#L1135-L1150)、[`gpu_busy_percentage_show` L1413](https://github.com/LineageOS/android_kernel_qcom_sm8250/blob/e3f6acd662618dabb3a7a953e48743019badc373/drivers/gpu/msm/kgsl_pwrctrl.c#L1413-L1435)、[`kgsl_pwrctrl_busy_time` L1693](https://github.com/LineageOS/android_kernel_qcom_sm8250/blob/e3f6acd662618dabb3a7a953e48743019badc373/drivers/gpu/msm/kgsl_pwrctrl.c#L1693-L1711)：分别导出同窗busy_old/total_old及其百分比，周期更新后清内部累积值。此证据不证明Android设备允许App读取。
5. **NPU累计计数仍无可证出口**：本轮继续核查同一Qualcomm固定版本，[npu_dev.c L164–168](https://github.com/LineageOS/android_kernel_qcom_sm8250/blob/e3f6acd662618dabb3a7a953e48743019badc373/drivers/media/platform/msm/npu/npu_dev.c#L164-L168)只注册caps/pwr/perf_mode_override/boot/dcvs_mode；[npu_debugfs.c L300–359](https://github.com/LineageOS/android_kernel_qcom_sm8250/blob/e3f6acd662618dabb3a7a953e48743019badc373/drivers/media/platform/msm/npu/npu_debugfs.c#L300-L359)导出寄存器/偏移/控制/固件状态等，没有可只读使用的busy/total。结合issue已有Rockchip固定1/1证据，未实现虚构计数差分。该调查有界，不宣称所有厂商都不存在。
6. **Qualcomm诊断**：沿用issue核实的 `qcom_stats/cdsp`/`cdsp1`，单位ticks，仅DSP睡眠统计；保留Count/Entered/Exited/Accumulated原文，不硬编码时钟或折算NPU百分比。

Java无法安全中止一个已陷在内核的文件read。因此200ms是**返回后阻止后续I/O的预算**，不是内核硬超时保证；UI/预览/关闭不等待它。文件超过4096字节时报告明确为截断，不声称复制到未读取部分。厂商sysfs文本通常远小于此限额；真正的“完整”指成功读至EOF的来源，绝非8×240摘要。

## 实际验证记录

专项使用 **Kotlin 2.4.21 / JUnit 4.13.2、JVM target21** 编译实际生产源码和测试，54 项通过。随后整合 Gradle 回归为 **613 项、0 失败、14 个可选 fixture 跳过**，包括此前失败的 procfs stale 用例：[结果](https://uploads.pullfrog.com/agent-uploads/817c4169-7cc3-40c2-8a35-fa8c705e120b/post-monitor-unit-results.json)。

| 检查 | 结果与证据 |
| --- | --- |
| `NpuProbesTest`、`NpuProbesFilesTest`、`DeviceUsageSamplerTest`、`MonitorUsageSamplerTest`、`SystemMonitorPluginTest`、`CameraFrameRateTest` | **54 tests，0 failures**；`/tmp/opencode/issue-66-check/junit.log`。覆盖短读/超限、完整原文、无消费者/取消、阻塞读取停止、源切换、完整host action、非有限值与慢FPS等。 |
| 最终procfs stale单项 | `runs=1 failures=0`；`/tmp/opencode/issue-66-check/stale-final.log`。冻结源hash见同目录 `frozen-source-sha256.txt`。 |
| `PluginsScreen.kt` 独立Compose编译 | exit0；同目录 `ui-compile.log`。包括#68新弹窗Map签名接线。初次误用Gradle的embedded插件与系统kotlinc不兼容；改用该kotlinc随附匹配插件后成功，不属于源码/依赖修改。 |
| `SystemMonitorClipboardTest.kt` | API24 实际 Android ClipboardManager 逐字节回归通过，包含超过显示摘要上限的完整原文、后台撤回；[13 项组合日志](https://uploads.pullfrog.com/agent-uploads/1c3ae7ea-b361-4622-9496-fe677bfb7ecf/bg-5249a31b.log)。 |
| scoped `git diff --check` | exit0。 |

### API35 实际 UI 与生命周期

[六项 UI/偏好证据](https://uploads.pullfrog.com/agent-uploads/8413379b-232f-4d63-8b85-8d24b8ff5502/monitor-ui-evidence.zip)：实际保存 `refresh_ms=1000`、`npu_source=all`、`fps_window_s=2`；无姿态模型时仍显示 FPS/CPU/同步耗时，NPU 如实报告 permission_denied，并同时显示声音分类结果。[复制回执](https://uploads.pullfrog.com/agent-uploads/848d0220-c764-4dc0-a703-f812982d9a1d/npu-copied.xml)明确成功；[停用后](https://uploads.pullfrog.com/agent-uploads/d46ceb64-45ac-4366-a8f3-3e92bf6da3d7/monitor-disabled.xml)指标与诊断动作全部消失，声音结果继续独立显示。

这是模拟器 UI、配置保存、输出隔离和复制调用证据。软件模拟器约 0.1–0.2 FPS 不代表手机性能；失败节点不代表厂商读取可用。剪贴板原文逐字节测试与最终构建在总验收记录中单列。

## 复现命令与设备入口

1. 冻结后整合命令：
   ```sh
   ./gradlew assembleDebug assembleRelease lintDebug testDebugUnitTest assembleDebugAndroidTest --build-cache --parallel --console=plain
   ```
   之前613 tests的失败日志不作为最终通过证据。确认上述procfs stale单项与54项所属测试纳入整合结果。
2. 新原生检查入口：`com.example.minimalapk.plugins.SystemMonitorClipboardTest#explicitActionCopiesEveryBytePastOverlayLimitsAndStopsWithSession`。它启动App前台，用真实Android ClipboardManager、真实PluginHost/action/exporter与有界固定驱动原文，逐字节检查超过8×240字符的末尾内容、后台撤回。用测试runner的`-e class`定向运行；需保持App前台以满足Android剪贴板访问规则。**这项是原文传输证明，不是厂商节点fixture变真机。**
3. UI：插件→系统监控→打开NPU→来源“测试全部”→保存→相机；待采样后点“复制完整NPU诊断”。模型全关/姿态增强关时仍显示基础帧率；分别在空闲与启用模型后复制，标明模型、所选后端、设备状态并对比。切250/1000/5000ms，核对保存与刷新；关闭CPU/GPU/NPU，保持FPS；关闭监控、切首页、Home后台后确认展示撤回，恢复仅有新会话样本。期间其它插件/按压替换不让监控文字移到底部。
4. 物理设备：Rockchip单/多核两个节点、Samsung实际平台身份/队列策略、Qualcomm权限/睡眠ticks、KGSL GPU，均需普通App权限实测；不要求root/adb授权/放宽SELinux。额外记录长时间前后台、慢读/陈旧值、实际帧率与CPU负载。不根据环境加速器枚举或选中NPU名称断言硬件接管。

## 修改清单

- 修改：`plugins/SystemMonitorPlugin.kt`、`pose/DeviceUsageSampler.kt`、`pose/NpuProbes.kt`、`ui/screens/PluginsScreen.kt`。
- 新增任务内小文件：`plugins/MonitorUsageSampler.kt`、`plugins/MonitorDiagnosisReport.kt`、`pose/NpuProbesFiles.kt`。
- 修改回归：`plugins/SystemMonitorPluginTest.kt`、`pose/DeviceUsageSamplerTest.kt`、`pose/NpuProbesTest.kt`；既有`CameraFrameRateTest`原样运行。
- 新增回归：`plugins/MonitorUsageSamplerTest.kt`、`pose/NpuProbesFilesTest.kt`、`androidTest/.../plugins/SystemMonitorClipboardTest.kt`。
- 文档仅此文件；首页与共享相机/宿主边界已审核但无修改。
