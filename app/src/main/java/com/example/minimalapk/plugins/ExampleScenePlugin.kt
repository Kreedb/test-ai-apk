package com.example.minimalapk.plugins

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.example.minimalapk.examples.ExampleCatalog
import com.example.minimalapk.examples.ExampleDownload
import com.example.minimalapk.examples.ExampleModel
import com.example.minimalapk.examples.ImageFit
import com.example.minimalapk.examples.fittedImage
import com.example.minimalapk.log.AppLog
import com.example.minimalapk.models.ConfiguredModelStore
import com.example.minimalapk.models.ConfiguredModel
import com.example.minimalapk.models.LiveConfiguredModel
import com.example.minimalapk.models.LiveTensorOutput
import com.example.minimalapk.models.ModelConfig
import com.example.minimalapk.pose.GraphSignature
import com.example.minimalapk.pose.PoseNet
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.math.hypot
import kotlin.math.roundToInt

internal data class ExampleSceneSpec(
    val id: String,
    val name: String,
    val example: ExampleModel,
    val description: String,
    val thresholdEnabled: Boolean,
    val textHint: String? = null,
    val recommendedThreshold: Float = 0.3f,
)

internal object ExampleScenes {
    val moveNet = ExampleSceneSpec(
        PluginIds.MOVENET_SCENE, "抬臂计次", ExampleCatalog.moveNet,
        "一人正对相机，保持双肩、双腕和髋部可见。先放下双臂，再抬到双腕高于双肩，最后放下；" +
            "每个端点保持约半秒，完成一次加 1。根据 MoveNet 关键点的几何关系计次，" +
            "关键点不清晰时暂停并重新等待放下；重新启用或修改配置会清零。",
        thresholdEnabled = true,
        textHint = "单人正对相机：放下 → 双腕高于肩 → 放下；每个端点保持约半秒",
    )
    val classification = ExampleSceneSpec(
        PluginIds.IMAGE_CLASSIFICATION_SCENE, "物品识别", ExampleCatalog.classification,
        "把一件物品放到画面中央并靠近相机，查看 ImageNet 标签中的候选名称和模型得分；" +
            "标签随模型一起下载。模型对整张图片分类，不提供物品位置。",
        thresholdEnabled = false,
        textHint = "请让一件物品占据画面；以下为 ImageNet 候选类别与模型得分",
    )
    val detection = ExampleSceneSpec(
        PluginIds.OBJECT_DETECTION_SCENE, "物品定位与计数", ExampleCatalog.detection,
        "对准桌面或房间，框上显示模型标签和得分；下方汇总本帧各类物品数量与左/中/右、上/中/下位置。" +
            "SSD MobileNet 仅识别其 COCO 标签集，每帧最多 10 个目标，建议阈值 0.5；" +
            "计数是当前可见目标，不累计经过的人或物。",
        thresholdEnabled = true,
        textHint = "对准物品查看框内名称；数量为本帧可见目标，位置相对画面",
        recommendedThreshold = 0.5f,
    )
    val segmentation = ExampleSceneSpec(
        PluginIds.IMAGE_SEGMENTATION_SCENE, "区域覆盖分析", ExampleCatalog.segmentation,
        "对准人、宠物、家具或交通工具，同色区域表示同一类别，并标注占真实画面的面积比例，背景透明。" +
            "使用 DeepLab 的 21 类最高响应逐像素划分；百分比是面积而非识别置信度。" +
            "保持照片比例输入 257×257，适合查看大致区域，不是精细发丝抠图。",
        // This pinned DeepLab exports logits, not calibrated probabilities. A 0..1 confidence
        // setting on its raw maxima was meaningless. Old stored thresholds are intentionally unused.
        thresholdEnabled = false,
        textHint = "同色 = 同类区域；百分比 = 占画面面积（不是置信度），背景透明",
    )
    val all = listOf(moveNet, classification, detection, segmentation)
}

/** Fixed, hash-pinned scene contracts, independent of user-edited custom-model configurations. */
internal fun sceneModelConfig(id: String, graph: GraphSignature): ModelConfig {
    val (inputSize, inputType, outputs) = when (id) {
        PluginIds.MOVENET_SCENE -> Triple(192, 3, listOf(listOf(1, 1, 17, 3)))
        PluginIds.IMAGE_CLASSIFICATION_SCENE -> Triple(224, 3, listOf(listOf(1, 1001)))
        PluginIds.OBJECT_DETECTION_SCENE -> Triple(300, 3, listOf(listOf(1, 10, 4), listOf(1, 10), listOf(1, 10), listOf(1)))
        PluginIds.IMAGE_SEGMENTATION_SCENE -> Triple(257, 0, listOf(listOf(1, 257, 257, 21)))
        else -> error("未知视觉场景 $id")
    }
    // SSD's custom postprocess populates shapes during AllocateTensors; the pinned flatbuffer has
    // four empty shapes. Enforce the concrete runtime contract in sceneDetections as well.
    val outputShapesMatch = graph.outputs.map { it.shape } == outputs ||
        id == PluginIds.OBJECT_DETECTION_SCENE && graph.outputs.size == 4 && graph.outputs.all { it.shape.isEmpty() }
    require(graph.key.isEmpty() && graph.inputs.size == 1 &&
        graph.inputs.single().shape == listOf(1, inputSize, inputSize, 3) && graph.inputs.single().type == inputType &&
        outputShapesMatch) { "$id 模型张量与场景契约不符" }
    require(graph.outputs.all { it.type == if (id == PluginIds.IMAGE_CLASSIFICATION_SCENE) 3 else 0 }) {
        "$id 输出类型与场景契约不符"
    }
    val inferred = ModelConfig.infer(graph)
    // Values below are verified against these exact models' metadata; MoveNet PTQ uses raw UINT8.
    return inferred.copy(inputs = inferred.inputs.map {
        if (id == PluginIds.MOVENET_SCENE) it.copy(mean = 0f, std = 1f)
        else it.copy(mean = 127.5f, std = 127.5f)
    })
}

internal class ExampleScenePlugin(
    private val application: Context,
    private val spec: ExampleSceneSpec,
) : CameraPlugin {
    override val descriptor = PluginDescriptor(
        spec.id, spec.name,
        setOf(PluginCapability.CAMERA_FRAMES, PluginCapability.TEXT, PluginCapability.LAYER),
    )
    private data class Request(val context: PluginContext)
    private class SceneModel(
        val runner: LiveConfiguredModel,
        val size: Int,
        val labels: List<String>,
    ) : AutoCloseable {
        val raises = RaiseCycleTracker()
        var firstFrame = true
        override fun close() = runner.close()
    }
    private var current: PluginContext? = null
    private val lock = Any()
    private val model = AsyncModelSlot<Request, SceneModel>(
        "ExampleScene:${spec.id}",
        open = { request ->
            if (!request.context.isCurrent()) throw CancellationException("场景模型请求已失效")
            // Scene contracts must not depend on a user's separate custom-model properties.
            val file = ExampleDownload.ensure(application, spec.example)
            val installed = ConfiguredModel(spec.example.sha256, spec.example.name, file)
            if (!request.context.isCurrent()) throw CancellationException("场景模型请求已失效")
            val graph = ConfiguredModelStore.describe(installed).signatures.first { it.key.isEmpty() }
            val config = sceneModelConfig(spec.id, graph)
            val labels = ExampleDownload.labels(installed.file, spec.example)
            if (!request.context.isCurrent()) throw CancellationException("场景模型请求已失效")
            AppLog.i("ScenePlugin", "${spec.id} sha256=${spec.example.sha256} " +
                "input=${graph.inputs.single().shape}/type=${graph.inputs.single().type} " +
                "RGB mean=${config.inputs.single().mean} std=${config.inputs.single().std} " +
                "whole-photo fit/black padding; outputs=${graph.outputs.map { it.shape }} labels=${labels.size}")
            SceneModel(LiveConfiguredModel(installed, config, graph), config.inputs.single().shape[1], labels)
        },
        ready = { request, _ ->
            request.context.publish(PluginOutput(System.nanoTime(), listOf(PluginMessage(
                "已就绪：${spec.textHint ?: spec.description}", MessageKind.STATUS,
            ))))
        },
        failed = { request, error ->
            if (request.context.isCurrent()) {
                AppLog.w("ScenePlugin", "${spec.id} 加载/推理失败", error)
                request.context.publish(PluginOutput(System.nanoTime(), listOf(PluginMessage(
                    "未就绪：${error.message ?: "${spec.name}模型加载失败"}", MessageKind.ERROR,
                ))))
            }
        },
    )

    override fun onEvent(event: PluginEvent, context: PluginContext) {
        when (event) {
            PluginEvent.Started, PluginEvent.ConfigurationChanged -> synchronized(lock) {
                if (!context.isCurrent()) return
                current = context
                context.publish(PluginOutput(System.nanoTime(), listOf(PluginMessage(
                    "正在下载/加载 ${spec.name}（首次约 ${spec.example.sizeBytes / 1024 / 1024} MB）", MessageKind.STATUS,
                ))))
                model.set(Request(context))
            }
            is PluginEvent.Stopped -> synchronized(lock) {
                if (current?.token == context.token) {
                    current = null
                    model.set(null)
                }
            }
            is PluginEvent.Frame -> model.runIfReady { request, scene ->
                if (request.context.token != context.token || !context.isCurrent()) return@runIfReady
                val frame = event.frame
                val started = System.nanoTime()
                val input = fittedImage(frame.raw.bitmap, scene.size, scene.size, frame.source.rotationDegrees)
                val tensors = try { scene.runner.runTensors(input.bitmap) } finally { input.bitmap.recycle() }
                if (!context.isCurrent()) return@runIfReady
                val threshold = context.settings.config[SCENE_THRESHOLD_KEY]?.toFloatOrNull()
                    ?.takeIf { it in SceneThresholdSettings.MIN_THRESHOLD..SceneThresholdSettings.MAX_THRESHOLD }
                    ?: spec.recommendedThreshold
                val output = renderScene(spec, scene, tensors, input.fit, frame, threshold)
                if (scene.firstFrame) {
                    scene.firstFrame = false
                    AppLog.i("ScenePlugin", "${spec.id} first inference+render=${(System.nanoTime() - started) / 1_000_000}ms " +
                        "raw=${frame.raw.width}x${frame.raw.height} rotation=${frame.source.rotationDegrees} " +
                        "fit=${input.fit.photoWidth}x${input.fit.photoHeight} source=${frame.source.space.width}x${frame.source.space.height}; " +
                        output.messages.joinToString { it.text }.take(500))
                }
                // Retain the originating generation and exact frame; rejected images never escape.
                if (!context.isCurrent() || !context.publish(output)) {
                    output.layers.forEach { it.image?.bitmap?.recycle() }
                }
            }
            else -> Unit
        }
    }

    private fun renderScene(
        spec: ExampleSceneSpec,
        scene: SceneModel,
        tensors: List<LiveTensorOutput>,
        fit: ImageFit,
        frame: CameraFrame,
        threshold: Float,
    ): PluginOutput {
        val messages = mutableListOf<PluginMessage>()
        spec.textHint?.let { messages += PluginMessage(it, MessageKind.STATUS) }
        var overlay: Bitmap? = null
        when (spec.id) {
            PluginIds.MOVENET_SCENE -> {
                val points = moveNetPoints(tensors.single().values, fit, threshold)
                val position = armPosition(points, fit.sourceWidth, fit.sourceHeight)
                val progress = scene.raises.update(position, frame.timestampNanos)
                messages += PluginMessage("已完成 ${progress.repetitions} 次 · ${progress.message}")
                overlay = drawScenePose(points, fit, frame.source)
            }
            PluginIds.OBJECT_DETECTION_SCENE -> {
                val objects = sceneDetections(tensors, fit, threshold, scene.labels)
                messages += PluginMessage("本帧 ${objects.size} 个目标 · 模型得分阈值 ${decimal(threshold.toDouble())}")
                objects.groupBy { it.label }.entries.take(5).forEach { (label, detections) ->
                    val locations = detections.groupingBy { objectLocation(it.left, it.top, it.right, it.bottom) }.eachCount()
                    messages += PluginMessage("$label ×${detections.size}：" + locations.entries.joinToString { "${it.key} ×${it.value}" })
                }
                if (objects.isEmpty()) messages += PluginMessage("未找到达到阈值的支持类别；请靠近物品并保持光线充足", MessageKind.STATUS)
                overlay = drawSceneObjects(objects, frame.source)
            }
            PluginIds.IMAGE_SEGMENTATION_SCENE -> {
                val tensor = tensors.single()
                val mask = sceneSegmentation(tensor.values, tensor.shape[2], tensor.shape[1], scene.labels.size, fit)
                val ranked = mask.counts.indices.filter { it > 0 && mask.counts[it] > 0 }.sortedByDescending { mask.counts[it] }
                ranked.take(5).forEach { label ->
                    messages += PluginMessage("${scene.labels[label]}：${coverage(mask.counts[label], mask.photoPixels)}")
                }
                if (ranked.size > 5) messages += PluginMessage("其他前景类别：${coverage(ranked.drop(5).sumOf { mask.counts[it] }, mask.photoPixels)}")
                messages += PluginMessage("背景：${coverage(mask.counts[0], mask.photoPixels)} · 21 类最高响应划分", MessageKind.STATUS)
                if (ranked.isEmpty()) messages += PluginMessage("当前只分出背景；请对准支持的人、宠物、家具或交通工具", MessageKind.STATUS)
                overlay = drawSceneMask(mask, scene.labels, fit, frame.source)
            }
            PluginIds.IMAGE_CLASSIFICATION_SCENE -> {
                val scores = tensors.single().values
                require(scores.size == scene.labels.size && scores.all { it.isFinite() }) { "分类分数与标签不匹配" }
                scores.indices.sortedByDescending { scores[it] }.take(5).forEach {
                    messages += PluginMessage("${scene.labels[it]}：${decimal(scores[it] * 100)}%")
                }
            }
        }
        return PluginOutput(frame.timestampNanos, messages.take(PluginHost.MAX_MESSAGES),
            overlay?.let { listOf(PluginLayer(frame.source, BitmapImage(it))) } ?: emptyList())
    }

    override fun close() = model.close()
}

internal data class ScenePoint(val x: Float, val y: Float)

internal fun moveNetPoints(values: DoubleArray, fit: ImageFit, threshold: Float): List<ScenePoint?> {
    require(values.size == 51 && values.all { it.isFinite() }) { "MoveNet 关键点无效" }
    return List(17) { index ->
        val x = values[index * 3 + 1].toFloat()
        val y = values[index * 3].toFloat()
        if (values[index * 3 + 2] < threshold || !fit.contains(x, y)) null
        else ScenePoint(fit.sourceX(x), fit.sourceY(y))
    }
}

internal enum class ArmPosition { LOWERED, RAISED, MOVING }

/** Measured wrist/shoulder relation along the torso axis, not invented action-model labels. */
internal fun armPosition(points: List<ScenePoint?>, width: Int, height: Int): ArmPosition? {
    val ls = points.getOrNull(5) ?: return null
    val rs = points.getOrNull(6) ?: return null
    val lw = points.getOrNull(9) ?: return null
    val rw = points.getOrNull(10) ?: return null
    val lh = points.getOrNull(11) ?: return null
    val rh = points.getOrNull(12) ?: return null
    val dx = (lh.x + rh.x - ls.x - rs.x) / 2f
    val dy = (lh.y + rh.y - ls.y - rs.y) / 2f
    val torso = hypot(dx, dy)
    if (torso < height * 0.1f || dy < torso * 0.7f || hypot(ls.x - rs.x, ls.y - rs.y) < width * 0.05f) return null
    fun elevation(wrist: ScenePoint, shoulder: ScenePoint): Float =
        ((wrist.x - shoulder.x) * dx + (wrist.y - shoulder.y) * dy) / (torso * torso)
    val left = elevation(lw, ls)
    val right = elevation(rw, rs)
    return when {
        left < -0.15f && right < -0.15f -> ArmPosition.RAISED
        left > 0.35f && right > 0.35f -> ArmPosition.LOWERED
        else -> ArmPosition.MOVING
    }
}

internal data class RaiseProgress(val repetitions: Int, val message: String)

/** A full stable down→up→down cycle; dropouts/time gaps invalidate the unfinished cycle. */
internal class RaiseCycleTracker {
    private var repetitions = 0
    private var candidate: ArmPosition? = null
    private var candidateSince = 0L
    private var candidateFrames = 0
    private var stage = 0 // 0: establish down, 1: wait for up, 2: wait for return down.
    private var lastTimestamp: Long? = null
    private var progress = RaiseProgress(0, "请先放下双臂")

    fun update(position: ArmPosition?, timestamp: Long): RaiseProgress {
        val previous = lastTimestamp
        if (previous != null && timestamp <= previous) return progress
        if (position == null || previous != null && timestamp - previous > 1_500_000_000L) {
            stage = 0
            candidate = null
            candidateFrames = 0
        }
        lastTimestamp = timestamp
        if (position == null) {
            progress = RaiseProgress(repetitions, "暂停：请正对相机，让双肩、双腕和髋部清晰可见，再放下双臂")
            return progress
        }
        if (position == ArmPosition.MOVING) {
            candidate = null
            candidateFrames = 0
            progress = RaiseProgress(repetitions, if (stage == 2) "抬起已确认，请放下双臂" else "移动中，请在端点保持约半秒")
            return progress
        }
        if (candidate != position) {
            candidate = position
            candidateSince = timestamp
            candidateFrames = 1
        } else candidateFrames = minOf(candidateFrames + 1, 3)
        if (candidateFrames < 3 || timestamp - candidateSince < 350_000_000L) {
            progress = RaiseProgress(repetitions, "保持当前姿态，正在确认")
            return progress
        }
        when {
            stage == 0 && position == ArmPosition.LOWERED -> stage = 1
            stage == 1 && position == ArmPosition.RAISED -> stage = 2
            stage == 2 && position == ArmPosition.LOWERED -> { repetitions++; stage = 1 }
        }
        progress = RaiseProgress(repetitions, when (stage) {
            1 -> "放下已确认，请将双腕抬至双肩上方"
            2 -> "抬起已确认，请放下双臂完成本次"
            else -> "请先放下双臂，建立起始姿态"
        })
        return progress
    }
}

internal data class SceneObject(
    val label: String, val score: Double,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
)

internal fun sceneDetections(
    tensors: List<LiveTensorOutput>, fit: ImageFit, threshold: Float, labels: List<String>,
): List<SceneObject> {
    require(tensors.map { it.shape } == listOf(listOf(1, 10, 4), listOf(1, 10), listOf(1, 10), listOf(1)) &&
        labels.isNotEmpty()) { "SSD 运行时输出或标签与场景契约不符" }
    val boxes = tensors[0].values
    val classes = tensors[1].values
    val scores = tensors[2].values
    val count = tensors[3].values.single()
    require(count.isFinite() && count == count.toInt().toDouble() && count.toInt() in 0..10 &&
        count <= boxes.size / 4 && count <= classes.size && count <= scores.size) { "检测数量无效" }
    val candidates = buildList {
        repeat(count.toInt()) { index ->
            val score = scores[index]
            require(score.isFinite() && score in 0.0..1.0) { "检测得分无效" }
            if (score < threshold) return@repeat
            val id = classes[index]
            require(id.isFinite() && id == id.toInt().toDouble() && id.toInt() in labels.indices) { "检测类别与标签不匹配" }
            val y1 = boxes[index * 4].toFloat()
            val x1 = boxes[index * 4 + 1].toFloat()
            val y2 = boxes[index * 4 + 2].toFloat()
            val x2 = boxes[index * 4 + 3].toFloat()
            require(listOf(x1, y1, x2, y2).all { it.isFinite() }) { "检测坐标无效" }
            if (x2 <= x1 || y2 <= y1 || !fit.contains((x1 + x2) / 2, (y1 + y2) / 2)) return@repeat
            val left = (fit.sourceX(x1) / fit.sourceWidth).coerceIn(0f, 1f)
            val top = (fit.sourceY(y1) / fit.sourceHeight).coerceIn(0f, 1f)
            val right = (fit.sourceX(x2) / fit.sourceWidth).coerceIn(0f, 1f)
            val bottom = (fit.sourceY(y2) / fit.sourceHeight).coerceIn(0f, 1f)
            val label = labels[id.toInt()].takeUnless { it.isBlank() || it == "???" } ?: "模型未命名类别 #${id.toInt()}"
            add(SceneObject(label, score, left, top, right, bottom))
        }
    }
    // The exported postprocess permits high-overlap candidates (e.g. several boxes on one tie).
    // Count each same-label overlapping detection once; different classes remain independent.
    val kept = mutableListOf<SceneObject>()
    for (candidate in candidates.sortedByDescending { it.score }) {
        if (kept.none { it.label == candidate.label && objectIou(it, candidate) > 0.45f }) kept += candidate
    }
    return kept
}

private fun objectIou(a: SceneObject, b: SceneObject): Float {
    val intersection = maxOf(0f, minOf(a.right, b.right) - maxOf(a.left, b.left)) *
        maxOf(0f, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
    val union = (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - intersection
    return if (union > 0f) intersection / union else 0f
}

internal fun objectLocation(left: Float, top: Float, right: Float, bottom: Float): String {
    val column = (((left + right) / 2) * 3).toInt().coerceIn(0, 2)
    val row = (((top + bottom) / 2) * 3).toInt().coerceIn(0, 2)
    return listOf("左上", "上方", "右上", "左侧", "中央", "右侧", "左下", "下方", "右下")[row * 3 + column]
}

internal data class SceneMask(val width: Int, val height: Int, val classes: IntArray, val counts: IntArray, val photoPixels: Int)

/** Argmax of DeepLab logits. Padding never contributes to area; no fabricated probability cutoff. */
internal fun sceneSegmentation(values: DoubleArray, width: Int, height: Int, classes: Int, fit: ImageFit): SceneMask {
    require(width in 1..512 && height in 1..512 && classes == 21 && values.size == width * height * classes) {
        "DeepLab 分割输出需要 [1,H,W,21] 且尺寸受限"
    }
    val labels = IntArray(width * height) { -1 }
    val counts = IntArray(classes)
    var photoPixels = 0
    for (y in 0 until height) for (x in 0 until width) {
        if (!fit.contains((x + 0.5f) / width, (y + 0.5f) / height)) continue
        val pixel = y * width + x
        var best = Double.NEGATIVE_INFINITY
        var label = 0
        repeat(classes) { c ->
            val score = values[pixel * classes + c]
            require(score.isFinite()) { "DeepLab 输出包含 NaN/Infinity" }
            if (score > best) { best = score; label = c }
        }
        labels[pixel] = label
        counts[label]++
        photoPixels++
    }
    require(photoPixels > 0) { "分割输入没有有效照片区域" }
    return SceneMask(width, height, labels, counts, photoPixels)
}

/** Pascal VOC color table, also used for the class's on-image label. */
internal fun sceneColor(label: Int): Int {
    var value = label
    var r = 0
    var g = 0
    var b = 0
    repeat(8) { bit ->
        r = r or ((value and 1) shl (7 - bit))
        g = g or (((value shr 1) and 1) shl (7 - bit))
        b = b or (((value shr 2) and 1) shl (7 - bit))
        value = value shr 3
    }
    return (r shl 16) or (g shl 8) or b
}

private fun photoPoint(point: ScenePoint, fit: ImageFit, source: ImageSource): ImagePoint {
    val bounds = source.space.photoBounds
    return ImagePoint(bounds.left + point.x / fit.sourceWidth * (bounds.right - bounds.left),
        bounds.top + point.y / fit.sourceHeight * (bounds.bottom - bounds.top))
}

private fun drawScenePose(points: List<ScenePoint?>, fit: ImageFit, source: ImageSource): Bitmap {
    val bitmap = Bitmap.createBitmap(source.space.width, source.space.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff52e6a5.toInt(); strokeWidth = maxOf(2f, bitmap.width / 180f) }
    val mapped = points.map { it?.let { p -> photoPoint(p, fit, source) } }
    PoseNet.skeletonEdges.forEach { (a, b) ->
        val first = mapped[a]
        val second = mapped[b]
        if (first != null && second != null) canvas.drawLine(first.x, first.y, second.x, second.y, paint)
    }
    mapped.filterNotNull().forEach { canvas.drawCircle(it.x, it.y, paint.strokeWidth * 1.5f, paint) }
    return bitmap
}

private fun drawSceneObjects(objects: List<SceneObject>, source: ImageSource): Bitmap {
    val bitmap = Bitmap.createBitmap(source.space.width, source.space.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val bounds = source.space.photoBounds
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff52e6a5.toInt(); strokeWidth = maxOf(2f, bitmap.width / 180f); style = Paint.Style.STROKE
    }
    objects.forEach {
        val rect = RectF(bounds.left + it.left * (bounds.right - bounds.left), bounds.top + it.top * (bounds.bottom - bounds.top),
            bounds.left + it.right * (bounds.right - bounds.left), bounds.top + it.bottom * (bounds.bottom - bounds.top))
        canvas.drawRect(rect, paint)
        sceneLabel(canvas, "${it.label} ${(it.score * 100).roundToInt()}%", rect.left, rect.top, paint.color, source)
    }
    return bitmap
}

private fun drawSceneMask(mask: SceneMask, labels: List<String>, fit: ImageFit, source: ImageSource): Bitmap {
    val pixels = IntArray(mask.classes.size) { if (mask.classes[it] <= 0) 0 else (0x78 shl 24) or sceneColor(mask.classes[it]) }
    val small = Bitmap.createBitmap(pixels, mask.width, mask.height, Bitmap.Config.ARGB_8888)
    val bitmap = Bitmap.createBitmap(source.space.width, source.space.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val bounds = source.space.photoBounds
    val sx = (bounds.right - bounds.left) / fit.photoWidth
    val sy = (bounds.bottom - bounds.top) / fit.photoHeight
    try {
        canvas.save()
        canvas.clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        canvas.drawBitmap(small, null, RectF(bounds.left - fit.left * sx, bounds.top - fit.top * sy,
            bounds.left + (fit.width - fit.left) * sx, bounds.top + (fit.height - fit.top) * sy), Paint())
        canvas.restore()
    } finally { small.recycle() }
    // Pick a real pixel of each class nearest its centroid, so a label never floats in a hole.
    val sumX = DoubleArray(labels.size)
    val sumY = DoubleArray(labels.size)
    mask.classes.forEachIndexed { index, label ->
        if (label > 0) { sumX[label] += index % mask.width; sumY[label] += index / mask.width }
    }
    val best = DoubleArray(labels.size) { Double.POSITIVE_INFINITY }
    val representative = IntArray(labels.size) { -1 }
    mask.classes.forEachIndexed { index, label ->
        if (label > 0) {
            val dx = index % mask.width - sumX[label] / mask.counts[label]
            val dy = index / mask.width - sumY[label] / mask.counts[label]
            val distance = dx * dx + dy * dy
            if (distance < best[label]) { best[label] = distance; representative[label] = index }
        }
    }
    representative.forEachIndexed { label, index ->
        if (index >= 0) {
            val point = photoPoint(ScenePoint(fit.sourceX((index % mask.width + 0.5f) / mask.width),
                fit.sourceY((index / mask.width + 0.5f) / mask.height)), fit, source)
            sceneLabel(canvas, "${labels[label]} ${coverage(mask.counts[label], mask.photoPixels)}", point.x, point.y,
                0xff000000.toInt() or sceneColor(label), source)
        }
    }
    return bitmap
}

private fun sceneLabel(canvas: Canvas, text: String, x: Float, y: Float, color: Int, source: ImageSource) {
    val bounds = source.space.photoBounds
    val size = maxOf(12f, minOf(source.space.width, source.space.height) / 32f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size }
    val display = text.take(60)
    val width = minOf(paint.measureText(display) + size * 0.6f, bounds.right - bounds.left)
    val left = x.coerceIn(bounds.left, maxOf(bounds.left, bounds.right - width))
    val top = y.coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - size * 1.5f))
    paint.color = 0xd0000000.toInt()
    canvas.drawRect(left, top, left + width, top + size * 1.5f, paint)
    paint.color = color
    canvas.drawRect(left, top, left + size * 0.2f, top + size * 1.5f, paint)
    paint.color = 0xffffffff.toInt()
    canvas.save()
    canvas.clipRect(left, top, left + width, top + size * 1.5f)
    canvas.drawText(display, left + size * 0.3f, top + size * 1.1f, paint)
    canvas.restore()
}

private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
private fun coverage(count: Int, total: Int): String =
    if (count > 0 && count * 100.0 / total < 0.1) "<0.1%" else "${decimal(count * 100.0 / total)}%"
