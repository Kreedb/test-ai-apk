# #64 实现验证记录

## 已执行

- 工具链：`JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64`，`ANDROID_HOME=/usr/local/lib/android/sdk`。
- 依赖助手报告无支持的安装方式，依赖由 Gradle 解析。
- 完整任务 `assembleDebug assembleRelease lintDebug testDebugUnitTest --build-cache --parallel --console=plain` 已执行；首次完整成功日志 `/tmp/opencode/issue-64-full-check.log`。
- 审计增加会话音频/原始坐标与竞态回归后，最终源码执行相同任务加 `--no-daemon --offline`：`/tmp/opencode/issue-64-final-offline.log`，`BUILD SUCCESSFUL in 3m 23s`。中间一次在线 lint 长时间无结果，先 `./gradlew --stop` 后单次重试；没有并行启动竞争的 Gradle 构建。
- 随后在最终源码执行**原样要求的命令**（不加 offline/no-daemon），日志 `/tmp/opencode/issue-64-required-command.log`：`BUILD SUCCESSFUL in 1m 14s`；JVM 任务命中刚完成的 135 测试结果，lint 在线复查通过。
- JVM XML：`app/build/test-results/testDebugUnitTest/TEST-*.xml`，**27 suites / 135 tests / 0 failures / 0 errors / 0 skipped**。插件新增 35 个测试，另有 1 个配置存储通知测试；既有测试保留。
- `lintDebug`：0 errors；警告为已有依赖更新、固定屏幕方向与 KTX 建议，见 `app/build/reports/lint-results-debug.txt`。
- debug/release 的 arm64-v8a、x86_64 APK 均产出，release 完成 R8。
- `git diff --check` 通过。

这些日志路径属于此次执行工作区；PR 交付时由协调者按需上传日志或附 CI 链接。

## 待设备验证

`adb devices -l` 未列出设备；没有真机、模拟器或截图证据。统一卡片点击/TalkBack、相机切页与前后台、零模型及加载失败持续预览、Bitmap/GPU/Interpreter 生命周期、不同视口对齐与实际替换按压取消均待测，步骤见 [插件宿主接入](plugin-host.md#验证与设备清单)。

音频已由 `AudioCaptureHost` 实现真实 `AudioRecord` 采集（16 kHz 单声道 PCM-16-LE），`SuperResolutionPlugin` 也已接入 ESRGAN 按压替换；两者仍缺真机证据：麦克风采集、拒绝/撤权/后台释放、系统 ASR 占用麦克风时的 `MICROPHONE_BUSY` 互斥，以及超分模型推理与整帧合成效果均待设备验证。JVM 测试使用假图像尺寸验证来源匹配，不替代原生 Bitmap 或设备推理验证。
