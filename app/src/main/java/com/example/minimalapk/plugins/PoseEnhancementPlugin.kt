package com.example.minimalapk.plugins

import com.example.minimalapk.pose.ModelResult
import com.example.minimalapk.pose.PhotoBounds
import com.example.minimalapk.pose.PoseMeasurement
import com.example.minimalapk.pose.PoseNet
import java.util.Locale

/** Consumes the existing base inference; never loads a model or changes the enabled model list. */
internal class PoseEnhancementPlugin : CameraPlugin {
    override val descriptor = PluginDescriptor(
        PluginIds.POSE_ENHANCEMENT, "姿态增强",
        setOf(PluginCapability.POSE_RESULTS, PluginCapability.TEXT, PluginCapability.LAYER),
    )

    override fun onEvent(event: PluginEvent, context: PluginContext) {
        if (event !is PluginEvent.Pose || !context.isCurrent()) return
        val frame = event.frame
        val result = poseEnhancement(
            frame.poses.firstOrNull(), frame.source.space.photoBounds,
            PoseEnhancementSettings.from(context.settings),
        )
        context.publish(PluginOutput(
            timestampNanos = frame.timestampNanos,
            messages = result.messages,
            layers = listOf(PluginLayer(frame.source, lines = result.lines)),
        ))
    }
}

internal data class PoseEnhancement(val messages: List<PluginMessage>, val lines: List<OverlayLine>)

internal fun poseEnhancement(
    primary: ModelResult?,
    bounds: PhotoBounds,
    settings: PoseEnhancementSettings,
): PoseEnhancement {
    val detection = primary?.detection
    val height = settings.heightCm
    val measurement = if (detection != null && height != null) {
        PoseMeasurement.measure(detection, height, bounds, primary.spec.keypointThreshold)
    } else null
    val messages = buildList {
        when {
            primary == null -> add(PluginMessage("等待已启用的姿态模型结果", MessageKind.STATUS))
            height == null -> add(PluginMessage("请在 插件 → 姿态增强 填写有效身高（50–250 cm）", MessageKind.STATUS))
            detection == null -> add(PluginMessage("未检测到人体", MessageKind.STATUS))
            !PoseMeasurement.isFullBodyVisible(detection, bounds, primary.spec.keypointThreshold) ->
                add(PluginMessage("请后退，确保全身入镜", MessageKind.STATUS))
            measurement != null -> {
                measurement.armSpanCm?.let { add(PluginMessage("腕距约 ${formatPose(it)} cm（双臂平举，近似臂展）")) }
                measurement.eyeDistanceCm?.let { add(PluginMessage("双眼间距约 ${formatPose(it)} cm")) }
                measurement.shoulderTiltDeg?.let { add(PluginMessage("肩线倾斜 ${formatPose(it)}°")) }
                measurement.hipTiltDeg?.let { add(PluginMessage("髋线倾斜 ${formatPose(it)}°")) }
                measurement.torsoOffsetCm?.let { add(PluginMessage("肩髋横向偏移约 ${formatPose(it)} cm")) }
            }
        }
        add(PluginMessage("正对镜头、站直并全身入镜；单目 2D 尺寸为近似值", MessageKind.STATUS))
        add(PluginMessage("结果仅供筛查参考，不能用于医学诊断", MessageKind.STATUS))
    }
    val lines = buildList {
        if (measurement != null && detection != null && height != null) {
            if (settings.showHeightLine) {
                val center = (detection.left + detection.right) / 2f
                add(OverlayLine(
                    ImagePoint(center, detection.top), ImagePoint(center, detection.bottom),
                    0xFF76FF03.toInt(), "${formatPose(height)} cm",
                ))
            }
            if (settings.showArmSpan && measurement.armSpanCm != null) {
                val left = detection.keypoints[PoseNet.LEFT_WRIST]
                val right = detection.keypoints[PoseNet.RIGHT_WRIST]
                add(OverlayLine(
                    ImagePoint(left.x, left.y), ImagePoint(right.x, right.y),
                    0xFFFF4081.toInt(), "${formatPose(measurement.armSpanCm)} cm",
                ))
            }
        }
    }
    return PoseEnhancement(messages, lines)
}

internal fun formatPose(value: Float): String = String.format(Locale.US, "%.1f", value)
