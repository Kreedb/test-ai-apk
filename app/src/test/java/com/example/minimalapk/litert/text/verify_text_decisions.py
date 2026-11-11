"""Independent reference for TextDecisionFixtureExporter; never downloads or executes model text.

Usage: python verify_text_decisions.py INSTALLED_TASK_ROOT EXPORTED_INPUT_DIRECTORY
Requires numpy, tokenizers and ai-edge-litert (validated with CPU CompiledModel 2.3.0).
Reads mmap embeddings, verifies the Kotlin inputs against Hugging Face tokenizers and the pinned
host contracts, runs all five real graphs, writes full outputs for JVM decoder verification.
Models and graph assets are provided by the caller, outside the checkout. See ATTRIBUTION.md.
"""

import gc
import json
import pathlib
import re
import sys
import time

import numpy as np
from tokenizers import Tokenizer
from ai_edge_litert.compiled_model import CompiledModel
from ai_edge_litert.hardware_accelerator import HardwareAccelerator


def reference_inputs(case, directory):
    tokenizer = Tokenizer.from_file(str(directory / "tokenizer.json"))
    encode = lambda text: tokenizer.encode(text, add_special_tokens=False).ids
    task, question, text = case["task"], case["question"], case["text"]
    options = [description or key for key, description in case.get("options", [])]
    markers = []
    spans = []
    if task.endswith("laya-multilingual"):
        kind, criteria = question["type"], question["criteria"]
        if kind == "choice":
            options = [key if value is None or value == "" else key + ": " + value for key, value in criteria.items()]
        elif kind == "score":
            options = [f"level {i}: {value}" for i, value in enumerate(criteria)]
        else:
            options = ["false: no, the statement does not hold", "true: yes, the statement holds"]
        head = encode(f"{kind} question: " + question["instructions"].replace("<mask>", " "))
        pieces = [[4] + encode(" " + value.replace("<mask>", " "))[:48] for value in options]
        budget = 256 - sum(map(len, pieces))
        if budget < 16:
            per = max(4, (256 - 16) // len(pieces))
            pieces = [piece[:per] for piece in pieces]
            budget = 256 - sum(map(len, pieces))
        ids = [2] + head[:max(8, budget)] + [1]
        for piece in pieces:
            markers.append(len(ids))
            ids += piece
        ids += [1]
        ids += encode(text.replace("<mask>", " "))[:max(0, 256 - len(ids) - 1)] + [1]
        ids = ids[:256]
        window, width, pad, table = 256, 768, 0, "token_embeddings_fp16.bin"
        extra = {"qtype_onehot": np.eye(3, dtype=np.float32)[["choice", "score", "noul"].index(kind)][None]}
    elif task.endswith("julia-1"):
        ids = [2] + encode("choice question: " + question) + [1]
        for option in options:
            markers.append(len(ids))
            ids += [4] + encode(" " + option)
        ids += [1] + encode(text) + [1]
        window, width, pad, table = 512, 384, 0, "julia1_token_table_fp16.bin"
        extra = {"qtype_onehot": np.array([[1, 0, 0]], dtype=np.float32)}
    elif task.endswith("gliclass-edge"):
        linearized = "".join("<<LABEL>>" + label for label in options) + "<<SEP>>"
        linearized += question + " " if question else ""
        ids = tokenizer.encode(linearized + text).ids
        markers = [i for i, value in enumerate(ids) if value == 50368]
        window, width, pad, table = 128, 384, 50283, "tok_embeddings_fp16.bin"
        routing = np.zeros((1, 25, window), dtype=np.float32)
        routing[0, np.arange(len(markers)), markers] = 1
        extra = {"label_routing": routing}
    elif task.endswith("open-decision"):
        ids = [1, 128001] + encode(text)[:256]
        head = encode(question)
        q = (len(ids) + 1, len(ids) + 1 + len(head))
        ids += [128002] + head
        for option in options:
            piece = encode(option)
            spans.append((len(ids) + 1, len(ids) + 1 + len(piece)))
            ids += [128003] + piece
        ids += [2]
        assert list(q) == case["question_span"] and [list(span) for span in spans] == case["option_spans"]
        window, width, pad, table = 256, 1024, 0, "word_embeddings_fp16.bin"
        qr = np.zeros((1, 128, window), dtype=np.float32)
        routing = np.zeros_like(qr)
        for i, (start, end) in enumerate(spans):
            qr[0, i, q[0]:q[1]] = np.float32(1) / np.float32(q[1] - q[0])
            routing[0, i, start:end] = np.float32(1) / np.float32(end - start)
        extra = {"q_routing": qr, "o_routing": routing}
    else:
        ids = encode("(") + encode("[P]") + encode("answer: " + question) + encode("(")
        for option in options:
            markers.append(len(ids))
            ids += encode("[L]") + encode(option)
        ids += encode(")") + encode(")") + encode("[SEP_TEXT]")
        collated = text if text.endswith((".", "!", "?")) else text + "."
        pattern = r"(?:https?://\S+|www\.\S+)|[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}|@[a-z0-9_]+|\w+(?:[-_]\w+)*|[^\s]"
        for match in re.finditer(pattern, collated, re.IGNORECASE):
            ids += encode(match.group().lower())
        window, width, pad, table = 128, 1024, 0, "word_embeddings_fp16.bin"
        routing = np.zeros((1, 32, window), dtype=np.float32)
        routing[0, np.arange(len(markers)), markers] = 1
        extra = {"label_routing": routing}
    assert ids == case["ids"], (case["id"], "Kotlin != Hugging Face token IDs")
    if "markers" in case:
        assert markers == case["markers"], (case["id"], "marker mismatch")
    assert len(ids) <= window
    padded = np.full(window, pad, dtype=np.int32)
    padded[:len(ids)] = ids
    mapped = np.memmap(directory / table, mode="r", dtype="<f2").reshape(-1, width)
    embeds = mapped[padded].astype(np.float32)[None]
    mask = np.zeros((1, window), dtype=np.float32)
    mask[0, :len(ids)] = 1
    result = {"inputs_embeds": embeds, "attention_mask": mask, **extra}
    if task.endswith("gliner-decide"):
        result = {"args_0": embeds, "args_1": mask, "args_2": extra["label_routing"]}
    return result


def softmax(values):
    exponents = np.exp(values - values.max())
    return exponents / exponents.sum()


def run_graph(path, data):
    model = CompiledModel.from_file(str(path), hardware_accel=HardwareAccelerator.CPU)
    inputs, outputs = model.create_input_buffers(0), model.create_output_buffers(0)
    try:
        signature = model.get_signature_list()["serving_default"]
        input_names, output_names = signature["inputs"], signature["outputs"]
        assert set(input_names) == set(data), (input_names, data.keys())
        start = time.monotonic()
        for name, buffer in zip(input_names, inputs, strict=True):
            buffer.write(np.ascontiguousarray(data[name], dtype=np.float32))
        model.run_by_index(0, inputs, outputs)
        result = {}
        for name, buffer in zip(output_names, outputs, strict=True):
            count = {"token_logits": data["attention_mask"].shape[1] if "attention_mask" in data else 0,
                     "pooled_cls": 768, "act_logits": 2,
                     "logits": 25 if "gliclass" in path.name else 128, "output_0": 32}[name]
            result[name] = buffer.read(count, np.float32)
            assert np.isfinite(result[name]).all()
        elapsed = (time.monotonic() - start) * 1000
        # All named inputs are rewritten, and a repeated call must reproduce the current request.
        for name, buffer in zip(input_names, inputs, strict=True):
            buffer.write(np.ascontiguousarray(data[name], dtype=np.float32))
        model.run_by_index(0, inputs, outputs)
        for name, buffer in zip(output_names, outputs, strict=True):
            np.testing.assert_allclose(buffer.read(result[name].size, np.float32), result[name], rtol=0, atol=1e-5)
        # A/B/A: change semantic rows (not merely re-run A), fully rewrite all buffers and prove
        # that a subsequent A has no retained state. This is safe for these fixed encoder graphs.
        changed = {name: value.copy() for name, value in data.items()}
        embedding_name = next((name for name in ("inputs_embeds", "args_0") if name in changed), None)
        if embedding_name is not None:
            mask_name = "attention_mask" if "attention_mask" in changed else "args_1"
            length = int(changed[mask_name].sum())
            changed[embedding_name][:, 1:length] = changed[embedding_name][:, 1:length][:, ::-1]
        else:
            changed["pooled_cls"] *= .5
            changed["feats"] = np.array([[.5, 0, 1, 2 / 255]], dtype=np.float32)
        for name, buffer in zip(input_names, inputs, strict=True):
            buffer.write(np.ascontiguousarray(changed[name], dtype=np.float32))
        model.run_by_index(0, inputs, outputs)
        for name, buffer in zip(output_names, outputs, strict=True):
            assert np.isfinite(buffer.read(result[name].size, np.float32)).all()
        for name, buffer in zip(input_names, inputs, strict=True):
            buffer.write(np.ascontiguousarray(data[name], dtype=np.float32))
        model.run_by_index(0, inputs, outputs)
        for name, buffer in zip(output_names, outputs, strict=True):
            np.testing.assert_allclose(buffer.read(result[name].size, np.float32), result[name], rtol=0, atol=1e-5)
        return result, elapsed
    finally:
        for buffer in inputs + outputs:
            buffer.destroy()
        model.close()
        gc.collect()


def main():
    assets, exported = map(pathlib.Path, sys.argv[1:])
    cases = json.loads((exported / "cases.json").read_text())
    results = []
    for case in cases:
        directory = assets / case["task"]
        expected = reference_inputs(case, directory)
        actual = {}
        for item in case["inputs"]:
            value = np.fromfile(exported / item["file"], dtype="<f4").reshape(item["shape"])
            np.testing.assert_array_equal(value, expected[item["name"]], err_msg=case["id"] + "/" + item["name"])
            actual[item["name"]] = value
        print("INPUT PARITY", case["id"], "ids", len(case["ids"]), "all float32 values bit-exact", flush=True)
        output, elapsed = run_graph(directory / case["graph"], actual)
        result = {"id": case["id"], "task": case["task"], "ms": elapsed, "input_parity": True,
                  "raw_outputs": {key: value.tolist() for key, value in output.items()}}
        if case["task"].endswith("laya-multilingual"):
            logits = output["token_logits"][case["markers"]]
            raw = softmax(logits)
            ordered = np.sort(raw)[::-1]
            k = max(len(raw), 2)
            feats = np.array([[ordered[0], ordered[0] - ordered[1],
                               -np.sum(raw * np.log(np.maximum(raw, np.float32(1e-9)))) / np.log(np.float32(k)),
                               np.float32(k) / np.float32(255)]], dtype=np.float32)
            action, act_ms = run_graph(directory / "laya_ml_act_head_fp32.tflite",
                                       {"pooled_cls": output["pooled_cls"][None], "feats": feats})
            result["act_logits"] = action["act_logits"].tolist()
            result["act_features"] = feats[0].tolist()
            result["ms"] += act_ms
            config = json.loads((directory / "laya_ml_calibration.json").read_text())
            kind = case["question"]["type"]
            bucket = kind + ":" + ("2" if k <= 2 else "3-5" if k <= 5 else "6-10" if k <= 10 else "11+")
            temperature = config["temperature_by_options"].get(bucket, config["temperature"][["choice", "score", "noul"].index(kind)])
            p = softmax(logits / np.float32(max(1e-3, temperature)))
            keys = list(case["question"]["criteria"]) if kind == "choice" else [str(i) for i in range(len(p))]
        else:
            logits = output.get("logits", output.get("output_0"))
            if case["task"].endswith("julia-1"):
                logits = output["token_logits"][case["markers"]].astype(np.float64)
            elif case["task"].endswith("open-decision"):
                logits = logits.astype(np.float64) / 1.05
            keys = [key for key, _ in case["options"]]
            p = softmax(logits[:len(keys)])
        result["probabilities"] = p.tolist()
        result["answer_key"] = keys[int(p.argmax())]
        if case.get("known_model_error"):
            result["semantic_expected_key"] = case["semantic_expected_key"]
            result["known_model_error"] = result["answer_key"] != case["semantic_expected_key"]
        if case["expected_key"] is not None:
            assert result["answer_key"] == case["expected_key"], result
        # Card checks only when the exact original single-question contract is used.
        if case["id"] == "julia-card-team":
            np.testing.assert_allclose(p, [.7925, .2073, .0002], rtol=0, atol=.003)
        if case["id"] == "laya-card-choice":
            np.testing.assert_allclose(p, [.9954, .0046], rtol=0, atol=.003)
        if case["id"] == "gliclass-card-promptless":
            np.testing.assert_allclose(p, [.8560521, .08718694, .03564049, .01963646, .00148390], rtol=0, atol=.003)
        if case["id"] == "gliner-readme18":
            # The pinned official model also chooses YES, although the passage entails NO.
            # This is conversion parity, explicitly NOT passing question-answering accuracy.
            np.testing.assert_allclose(p, [.996263325214386, .003736651735380292], rtol=0, atol=.001)
        results.append(result)
        (exported / "cpu-results.json").write_text(json.dumps(results, indent=2))
        print("PASS", case["id"], "answer", result["answer_key"], "probabilities", p.tolist(), "ms", result["ms"], flush=True)


if __name__ == "__main__":
    main()
