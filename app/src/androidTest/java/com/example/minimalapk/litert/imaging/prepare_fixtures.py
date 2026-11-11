#!/usr/bin/env python3
# Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Generate real-image CPU goldens for ImagingFixtureSmokeTest, outside the APK/repository.

Requires ai-edge-litert, numpy, Pillow; no PyTorch/converter or model downloads.
Numerical reference: pinned Zoo wrappers and models/rfdetr/rf_detr_seg_nano/converted/
verify_rf_detr_seg_nano.py. The optional road/haze hero inputs are revision-pinned model-card
images, downloaded only into --output. Model files must already match the app catalog hashes.

Example:
  python prepare_fixtures.py --models /tmp/opencode/litert-assets \
    --upstream /tmp/opencode/litert-upstream/litert-samples-f9f0e000630e9cdd6d263c4cd80495e4231a2dc8 \
    --output /tmp/opencode/imaging-check/fixtures

Inputs are resized ONCE here to native tensor size so Android and Pillow resizing differences
cannot disguise tensor-layout errors. SR uses a small nonsquare input and the real halo protocol.
"""

import argparse
import gc
import hashlib
import importlib.metadata
import json
from pathlib import Path
import re
import time
import urllib.request

import numpy as np
from PIL import Image
from ai_edge_litert.interpreter import Interpreter


TASKS = (
    "semantic-segmentation", "lane-detection", "super-resolution", "image-dehazing",
    "instance-segmentation", "background-removal", "portrait-matting",
    "super-resolution-real-esrgan", "monocular-geometry-estimation",
)
UPSTREAM_REVISION = "f9f0e000630e9cdd6d263c4cd80495e4231a2dc8"
HOPPER = "samples/litert/qualcomm/mobilenet_v2/app/src/main/assets/sample/grace_hopper.jpg"
HOPPER_SHA = "a8ca6d734765703b09728ab47fe59f473d93ae3967fc24c7c0288c3c7adb7130"
MEAN = np.array([.485, .456, .406], np.float32)
STD = np.array([.229, .224, .225], np.float32)
CITYSCAPES = np.array([
    [128, 64, 128], [244, 35, 232], [70, 70, 70], [102, 102, 156], [190, 153, 153],
    [153, 153, 153], [250, 170, 30], [220, 220, 0], [107, 142, 35], [152, 251, 152],
    [70, 130, 180], [220, 20, 60], [255, 0, 0], [0, 0, 142], [0, 0, 70], [0, 60, 100],
    [0, 80, 100], [0, 0, 230], [119, 11, 32],
], np.uint8)
COLORS = [(0, 200, 83), (255, 109, 0), (41, 98, 255), (213, 0, 0),
          (170, 0, 255), (0, 184, 212), (255, 214, 0), (197, 17, 98)]
HEROES = {
    "semantic-segmentation": (
        "PIDNet-S-Cityscapes-LiteRT", "3d79fd7a49278c81649fbe180091f96feead56fd",
        "2462d602c7f5e38292fca1759e53b887413602161c7b65d3475cd8468b15e718", (0, 40, 768, 424)),
    "lane-detection": (
        "TwinLiteNet-LiteRT", "63f53ec91dd9fddcafbbb30c99646447711af4ee",
        "dcd0fc94317187d4eab666199df33cef5ac9a54ac040e71a708fa9f4a1517eeb", (0, 0, 1280, 720)),
    "image-dehazing": (
        "DehazeFormer-MCT-LiteRT", "7249e3942b1a762f0c9691487537f171f10f3f5d",
        "a8e02600b5b4ab1e0f4ceb077a668f851e566b71553ac3ee8e96c917969069b1", (0, 0, 640, 576)),
}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_models(root, catalog, task):
    files = []
    for line in catalog.read_text().splitlines():
        if line.startswith("#"):
            continue
        row = line.split("\t")
        if row[0] != task:
            continue
        path = root / task / row[8]
        assert path.stat().st_size == int(row[10]), path
        assert sha(path) == row[11], path
        files.append({"name": row[8], "sha256": row[11], "url": row[9]})
    assert files, task
    return files


def source_image(args, task):
    if task in HEROES:
        repo, revision, digest, crop = HEROES[task]
        path = args.output / f"{task}-hero.png"
        url = f"https://huggingface.co/litert-community/{repo}/resolve/{revision}/hero.png"
        if not path.is_file():
            path.write_bytes(urllib.request.urlopen(url, timeout=120).read())
        assert sha(path) == digest, path
        return Image.open(path).convert("RGB").crop(crop), {"url": url, "sha256": digest, "crop": crop}
    path = args.upstream / HOPPER
    assert sha(path) == HOPPER_SHA, path
    return Image.open(path).convert("RGB"), {"upstream": HOPPER, "sha256": HOPPER_SHA}


def run(path, inputs):
    runtime = Interpreter(model_path=str(path), num_threads=2)
    runtime.allocate_tensors()
    details = runtime.get_input_details()
    assert len(details) == len(inputs)
    # RF-DETR uses unambiguous element counts; other graphs have a single input.
    for tensor in details:
        matching = [x for x in inputs if x.size == int(np.prod(tensor["shape"]))]
        assert len(matching) == 1, (path, tensor, [x.shape for x in inputs])
        runtime.set_tensor(tensor["index"], np.ascontiguousarray(matching[0].reshape(tensor["shape"]), np.float32))
    started = time.perf_counter()
    runtime.invoke()
    outputs = [runtime.get_tensor(x["index"]) for x in runtime.get_output_details()]
    elapsed = (time.perf_counter() - started) * 1000
    assert all(np.isfinite(x).all() for x in outputs), path
    contract = {"input": [x["shape"].tolist() for x in details], "output": [list(x.shape) for x in outputs]}
    del runtime
    gc.collect()
    return outputs, elapsed, contract


def raw(path, values):
    """Little-endian fp32 sidecars for replaying real model outputs through production Kotlin."""
    np.ascontiguousarray(values, dtype="<f4").tofile(path)


def compiled_parity(path, inputs, expected):
    """Explicit host CPU CompiledModel run, compared with the independent Interpreter tensors."""
    from ai_edge_litert.compiled_model import CompiledModel
    from ai_edge_litert.hardware_accelerator import HardwareAccelerator
    model = CompiledModel.from_file(str(path), hardware_accel=HardwareAccelerator.CPU)
    ins, outs = [], []
    try:
        ins = model.create_input_buffers(0)
        outs = model.create_output_buffers(0)
        signature = model.get_signature_by_index(0)
        input_details = model.get_input_tensor_details(signature["key"])
        output_details = model.get_output_tensor_details(signature["key"])
        for i, buffer in enumerate(ins):
            count = int(np.prod(input_details[signature["inputs"][i]]["shape"]))
            matches = [x for x in inputs if x.size == count]
            assert len(matches) == 1
            buffer.write(np.ascontiguousarray(matches[0].flatten(), np.float32))
        started = time.perf_counter()
        model.run_by_index(0, ins, outs)
        errors = []
        outputs = []
        for i, buffer in enumerate(outs):
            shape = output_details[signature["outputs"][i]]["shape"]
            count = int(np.prod(shape))
            actual = buffer.read(count, np.float32).reshape(shape)
            outputs.append(actual)
            # Output order follows signature output_0/output_1, including TwinLite's equal shapes.
            target = expected[i].reshape(shape)
            assert np.isfinite(actual).all(), path
            maximum = float(np.max(np.abs(actual - target)))
            normalized = maximum / max(1., float(np.max(np.abs(target))))
            assert normalized < .01, (path, i, maximum, normalized)
            errors.append({"maxAbsoluteError": maximum, "maxNormalizedError": normalized})
        return {"runAndReadbackMs": (time.perf_counter() - started) * 1000, "outputs": errors}, outputs
    finally:
        for buffer in reversed(outs + ins):
            buffer.destroy()
        model.close()


def verify_compiled(args, task):
    directory = args.output / task
    metadata = json.loads((directory / "reference.json").read_text())
    data = lambda name: np.fromfile(directory / name, "<f4")
    model_dir = args.models / task
    if task == "instance-segmentation":
        a_expected = [data("a-output-61516.f32"), data("a-output-2704.f32"), data("a-output-173056.f32")]
        a, outputs = compiled_parity(model_dir / "rfdetrseg_graphA_fp16.tflite",
                                    [data("input.f32"), np.fromfile(model_dir / "clspos.bin", "<f4"),
                                     np.fromfile(model_dir / "pospatch.bin", "<f4")], a_expected)
        by_size = {x.size: x for x in outputs}
        cls = by_size[61516].reshape(676, 91)
        delta = by_size[2704].reshape(676, 4)
        memory = by_size[173056].reshape(676, 256) * np.float32(.5)
        embedding = data("refpoint-embedding.f32").reshape(100, 4)
        gy, gx = np.mgrid[0:26, 0:26]
        grid = (np.stack([gx + .5, gy + .5], -1).reshape(676, 2) / 26).astype(np.float32)
        coords = np.concatenate([delta[:, :2] * .05 + grid, np.exp(delta[:, 2:]) * .05], -1)
        selected = coords[np.argsort(-cls.max(-1), kind="stable")[:100]]
        ref = np.concatenate([embedding[:, :2] * selected[:, 2:] + selected[:, :2],
                              np.exp(embedding[:, 2:]) * selected[:, 2:]], -1).astype(np.float32)
        b, _ = compiled_parity(model_dir / "rfdetrseg_graphB_fp16.tflite",
                               [memory, ref, np.fromfile(model_dir / "query_feat.bin", "<f4")],
                               [data("b-output-400.f32"), data("b-output-9100.f32"), data("b-output-608400.f32")])
        result = {"graphA": a, "graphB": b}
    else:
        model_file = next(x["name"] for x in metadata["models"] if x["name"].endswith(".tflite"))
        count = len(metadata["tensorContracts"][0]["output"])
        result, _ = compiled_parity(model_dir / model_file, [data("input.f32")],
                                    [data(f"output-{i}.f32") for i in range(count)])
    metadata["compiledModelCpuParity"] = result
    (directory / "reference.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps({"task": task, "compiledModelCpuParity": result}), flush=True)
    return metadata


def nchw(pixels, normalization="unit"):
    values = np.asarray(pixels, np.float32) / np.float32(255)
    if normalization == "signed":
        values = (values - np.float32(.5)) / np.float32(.5)
    elif normalization == "imagenet":
        values = (values - MEAN) / STD
    return np.ascontiguousarray(values.transpose(2, 0, 1)[None])


def rgba(rgb, alpha=None):
    if alpha is None:
        alpha = np.full(rgb.shape[:2], 255, np.uint8)
    return np.concatenate([rgb, alpha[..., None]], axis=2).astype(np.uint8)


def composite(source, colors, alpha):
    a = np.asarray(alpha, np.float32)
    if a.ndim == 2:
        a = a[..., None]
    return np.rint(source * (1 - a / 255) + colors * a / 255).clip(0, 255).astype(np.uint8)


def mct(source, curves):
    height, width = source.shape[:2]
    size = curves.shape[-1]
    params = curves.reshape(3, 3, 8, size, size)
    y = np.linspace(0, size - 1, height, dtype=np.float32)
    x = np.linspace(0, size - 1, width, dtype=np.float32)
    y0 = y.astype(int)[:, None]
    x0 = x.astype(int)[None, :]
    y1 = np.minimum(y0 + 1, size - 1)
    x1 = np.minimum(x0 + 1, size - 1)
    fy, fx = (y[:, None] - y0), (x[None, :] - x0)
    output = np.zeros_like(source, np.float32)
    for c in range(3):
        for i in range(3):
            depth = source[..., i].astype(np.float32) * np.float32(7) / np.float32(255)
            d0 = depth.astype(int)
            d1 = np.minimum(d0 + 1, 7)
            fd = depth - d0
            taps = []
            for d in (d0, d1):
                taps.append(params[c, i, d, y0, x0] * (1 - fy) * (1 - fx) +
                            params[c, i, d, y0, x1] * (1 - fy) * fx +
                            params[c, i, d, y1, x0] * fy * (1 - fx) +
                            params[c, i, d, y1, x1] * fy * fx)
            output[..., c] += taps[0] * (1 - fd) + taps[1] * fd
    return np.floor((output.clip(-1, 1) * .5 + .5) * 255 + .5).clip(0, 255).astype(np.uint8)


def iou(a, b):
    x0, y0 = np.maximum(a[:2] - a[2:] / 2, b[:2] - b[2:] / 2)
    x1, y1 = np.minimum(a[:2] + a[2:] / 2, b[:2] + b[2:] / 2)
    intersection = max(0., x1 - x0) * max(0., y1 - y0)
    union = np.prod(a[2:]) + np.prod(b[2:]) - intersection
    return intersection / union if union > 0 else 0.


def rf_detr(model_dir, image, directory):
    clspos = np.fromfile(model_dir / "clspos.bin", "<f4")
    pospatch = np.fromfile(model_dir / "pospatch.bin", "<f4")
    embedding = np.fromfile(model_dir / "refpoint_embed.bin", "<f4").reshape(100, 4)
    query = np.fromfile(model_dir / "query_feat.bin", "<f4")
    out_a, ms_a, contract_a = run(model_dir / "rfdetrseg_graphA_fp16.tflite", [nchw(image, "imagenet"), clspos, pospatch])
    raw(directory / "input.f32", nchw(image, "imagenet"))
    raw(directory / "refpoint-embedding.f32", embedding)
    for array in out_a:
        raw(directory / f"a-output-{array.size}.f32", array)
    by_size = {x.size: x for x in out_a}
    logits = by_size[676 * 91].reshape(676, 91)
    delta = by_size[676 * 4].reshape(676, 4)
    memory = by_size[676 * 256].reshape(676, 256) * np.float32(.5)
    gy, gx = np.mgrid[0:26, 0:26]
    grid = (np.stack([gx + .5, gy + .5], -1).reshape(676, 2) / 26).astype(np.float32)
    coords = np.concatenate([delta[:, :2] * .05 + grid, np.exp(delta[:, 2:]) * .05], -1)
    top = np.argsort(-logits.max(-1), kind="stable")[:100]
    selected = coords[top]
    reference = np.concatenate([embedding[:, :2] * selected[:, 2:] + selected[:, :2],
                                np.exp(embedding[:, 2:]) * selected[:, 2:]], -1).astype(np.float32)
    raw(directory / "reference-points.f32", reference)
    raw(directory / "memory.f32", memory)
    out_b, ms_b, contract_b = run(model_dir / "rfdetrseg_graphB_fp16.tflite", [memory, reference, query])
    for array in out_b:
        raw(directory / f"b-output-{array.size}.f32", array)
    by_size = {x.size: x for x in out_b}
    boxes = by_size[400].reshape(100, 4)
    logits = by_size[9100].reshape(100, 91)
    masks = by_size[100 * 78 * 78].reshape(100, 78, 78)
    classes = logits.argmax(-1)
    scores = 1 / (1 + np.exp(-logits.max(-1)))
    keep = []
    for q in np.argsort(-scores):
        if scores[q] < .5 or classes[q] <= 0 or np.any(boxes[q, 2:] <= 0):
            continue
        if not any(classes[p] == classes[q] and iou(boxes[p], boxes[q]) > .6 for p in keep):
            keep.append(int(q))
    pixels = np.asarray(image).copy()
    expected_masks = []
    for q in keep:
        resized_logits = np.asarray(Image.fromarray(masks[q]).resize(image.size, Image.Resampling.BILINEAR))
        expected_masks.append(resized_logits > 0)
    for i in reversed(range(len(keep))):
        pixels = composite(pixels, np.array(COLORS[i % len(COLORS)]), np.where(expected_masks[i], 110, 0))
    details = [{"query": q, "class": int(classes[q]), "score": float(scores[q]),
                "box": boxes[q].tolist(), "maskFraction": float(expected_masks[i].mean())} for i, q in enumerate(keep)]
    return rgba(pixels), ms_a + ms_b, [contract_a, contract_b], {"instances": details}


def prepare(args, task):
    files = validate_models(args.models, args.catalog, task)
    source, source_info = source_image(args, task)
    dimensions = {
        "semantic-segmentation": (1024, 1024), "lane-detection": (640, 360),
        "super-resolution": (96, 64), "super-resolution-real-esrgan": (96, 64),
        "image-dehazing": (256, 256), "instance-segmentation": (312, 312),
        "background-removal": (1024, 1024), "portrait-matting": (512, 512),
        "monocular-geometry-estimation": (504, 896),
    }
    image = source.resize(dimensions[task], Image.Resampling.BILINEAR)
    directory = args.output / task
    directory.mkdir(parents=True, exist_ok=True)
    image.save(directory / "input.png")
    rgb = np.asarray(image)
    model_dir = args.models / task
    details = {}
    if task == "instance-segmentation":
        rendered, ms, contracts, details = rf_detr(model_dir, image, directory)
        assert any(d["class"] == 1 and d["score"] > .8 for d in details["instances"]), details
    else:
        file = next(item["name"] for item in files if item["name"].endswith(".tflite"))
        if task in ("super-resolution", "super-resolution-real-esrgan"):
            # Useful core is x/y=16..112/80; the rest is edge-replicated halo.
            tile = np.pad(rgb, ((16, 48), (16, 16), (0, 0)), mode="edge")
            values = tile.astype(np.float32)[None] / 255 if task.endswith("real-esrgan") else nchw(tile)
        else:
            normalization = "imagenet" if task in ("semantic-segmentation", "monocular-geometry-estimation") else (
                "signed" if task in ("image-dehazing", "portrait-matting") else "unit")
            values = nchw(image, normalization)
        outputs, ms, contract = run(model_dir / file, [values])
        raw(directory / "input.f32", values)
        for i, array in enumerate(outputs):
            raw(directory / f"output-{i}.f32", array)
        contracts = [contract]
        if task == "semantic-segmentation":
            labels = outputs[0][0].argmax(0).astype(np.uint8)
            full = np.asarray(Image.fromarray(labels).resize(image.size, Image.Resampling.NEAREST))
            rendered = rgba(composite(rgb, CITYSCAPES[full], 130))
            details["coverage"] = (np.bincount(labels.flatten(), minlength=19) / labels.size).tolist()
            # This street crop has a narrow strip of sky; road/building/car are the large regions.
            assert details["coverage"][0] > .25 and details["coverage"][2] > .1 and details["coverage"][13] > .03, details
        elif task == "lane-detection":
            road = outputs[0][0].argmax(0) == 1
            lane = outputs[1][0].argmax(0) == 1
            colors = np.where(lane[..., None], [255, 48, 48], [0, 208, 96])
            rendered = rgba(composite(rgb, colors, np.where(lane, 217, np.where(road, 116, 0))))
            details.update(drivableFraction=float(road.mean()), laneFraction=float(lane.mean()))
            assert road.mean() > .1 and lane.mean() > .001, details
        elif task in ("super-resolution", "super-resolution-real-esrgan"):
            output = (outputs[0][0].transpose(1, 2, 0).clip(0, 1) * 255).astype(np.uint8)
            rendered = rgba(output[64:64 + 64 * 4, 64:64 + 96 * 4])
            reference = np.asarray(image.resize((384, 256), Image.Resampling.BICUBIC)).astype(float)
            details["bicubicMae"] = float(np.abs(rendered[..., :3].astype(float) - reference).mean())
            assert details["bicubicMae"] < 35, details
        elif task == "image-dehazing":
            rendered = rgba(mct(rgb, outputs[0][0]))
            details["meanChange"] = float(np.abs(rendered[..., :3].astype(float) - rgb).mean())
            assert details["meanChange"] > 3, details
        elif task in ("background-removal", "portrait-matting"):
            alpha = outputs[0].reshape(rgb.shape[:2])
            if task == "background-removal":
                alpha = (alpha - alpha.min()) / (alpha.max() - alpha.min() + np.float32(1e-6))
            alpha = alpha.clip(0, 1)
            rendered = rgba(rgb, np.floor(alpha * 255 + .5).astype(np.uint8))
            details.update(meanAlpha=float(alpha.mean()), softAlphaFraction=float(((alpha > .01) & (alpha < .99)).mean()),
                           centerAlpha=float(alpha[image.height // 2, image.width // 2]), cornerAlpha=float(alpha[0, 0]))
            assert details["centerAlpha"] > .7 and details["cornerAlpha"] < .3, details
        else:
            depth = outputs[0].reshape(rgb.shape[:2])
            disparity = np.where((depth > 0) & np.isfinite(depth), 1 / depth, 0)
            sorted_values = np.sort(disparity[disparity > 0].flatten())
            low, high = sorted_values[int(.02 * (len(sorted_values) - 1))], sorted_values[int(.98 * (len(sorted_values) - 1))]
            n = 1 - ((disparity - low) / (high - low)).clip(0, 1) if high > low else np.full(depth.shape, .5)
            upstream = args.upstream / ("samples/litert_model_zoo/android/app/src/main/java/com/google/ai/edge/"
                                       "examples/litert_model_zoo/models/da3/DA3Predictor.kt")
            lut_text = upstream.read_text().split("private const val LUT =", 1)[1].split("private val SPECTRAL", 1)[0]
            lut = "".join(re.findall(r'"([0-9a-f]+)"', lut_text))
            colors = np.frombuffer(bytes.fromhex(lut), np.uint8).reshape(256, 3)
            rendered = rgba(colors[(n * 255).astype(np.uint8)])
            details["relativeDepthQuantiles"] = np.quantile(depth, [.02, .5, .98]).tolist()
            assert np.std(depth) > .001
    Image.fromarray(rendered).save(directory / "expected.png")
    summary = {
        "task": task, "models": files, "source": source_info, "upstreamRevision": UPSTREAM_REVISION,
        "inputSha256": sha(directory / "input.png"), "expectedSha256": sha(directory / "expected.png"),
        "tensorContracts": contracts, "modelInferenceMs": ms, "details": details,
        "runtime": {name: importlib.metadata.version(name) for name in ("ai-edge-litert", "numpy", "pillow")},
    }
    (directory / "reference.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps({"task": task, "modelInferenceMs": ms, "details": details}), flush=True)
    gc.collect()
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models", type=Path, required=True)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, default=Path("app/src/main/assets/models/litert-models.tsv"))
    parser.add_argument("--task", choices=TASKS)
    parser.add_argument("--verify-compiled", action="store_true", help="Run explicit CPU CompiledModel against already prepared references")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    tasks = [args.task] if args.task else TASKS
    summaries = [(verify_compiled(args, task) if args.verify_compiled else prepare(args, task)) for task in tasks]
    (args.output / (f"{args.task}-report.json" if args.task else "report.json")).write_text(json.dumps(summaries, indent=2) + "\n")


if __name__ == "__main__":
    main()
