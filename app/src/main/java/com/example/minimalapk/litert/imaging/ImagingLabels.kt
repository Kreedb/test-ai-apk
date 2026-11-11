/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.minimalapk.litert.imaging

/** Pinned Zoo models/pidnet/CityscapesPalette.kt: 19 train IDs, not VOC or Selfie class IDs. */
internal object CityscapesLabels {
    val names = listOf(
        "road", "sidewalk", "building", "wall", "fence", "pole", "traffic light", "traffic sign",
        "vegetation", "terrain", "sky", "person", "rider", "car", "truck", "bus", "train",
        "motorcycle", "bicycle",
    )
    val colors = intArrayOf(
        0xff804080.toInt(), 0xfff423e8.toInt(), 0xff464646.toInt(), 0xff66669c.toInt(),
        0xffbe9999.toInt(), 0xff999999.toInt(), 0xfffaaa1e.toInt(), 0xffdcdc00.toInt(),
        0xff6b8e23.toInt(), 0xff98fb98.toInt(), 0xff4682b4.toInt(), 0xffdc143c.toInt(),
        0xffff0000.toInt(), 0xff00008e.toInt(), 0xff000046.toInt(), 0xff003c64.toInt(),
        0xff005064.toInt(), 0xff0000e6.toInt(), 0xff770b20.toInt(),
    )
}

/**
 * Exact sparse ID order of pinned Zoo assets/rfdetrseg_coco_labels.txt (91 lines, 80 labels).
 * Inlined here so a missing downloaded/bundled label asset cannot shift the COCO category IDs.
 */
internal object RfDetrSegLabels {
    val names = listOf(
        "", "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "", "stop sign", "parking meter", "bench", "bird", "cat", "dog",
        "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "", "backpack", "umbrella", "", "",
        "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite", "baseball bat",
        "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle", "", "wine glass", "cup",
        "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot",
        "hot dog", "pizza", "donut", "cake", "chair", "couch", "potted plant", "bed", "", "dining table",
        "", "", "toilet", "", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone", "microwave",
        "oven", "toaster", "sink", "refrigerator", "", "book", "clock", "vase", "scissors", "teddy bear",
        "hair drier", "toothbrush",
    )
    val colors = intArrayOf(
        0xff00c853.toInt(), 0xffff6d00.toInt(), 0xff2962ff.toInt(), 0xffd50000.toInt(),
        0xffaa00ff.toInt(), 0xff00b8d4.toInt(), 0xffffd600.toInt(), 0xffc51162.toInt(),
    )
    fun name(id: Int): String = names.getOrNull(id)?.takeIf { it.isNotBlank() } ?: "category $id"
}
