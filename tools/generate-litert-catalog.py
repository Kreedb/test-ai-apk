#!/usr/bin/env python3
"""Generate app/src/main/assets/models/litert-models.tsv from the pinned LiteRT model-zoo JSON.

The Android app deliberately has no JSON parser dependency. Run this script when the pinned
upstream revision changes, then keep the generated TSV under source control.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

UPSTREAM_COMMIT = "f9f0e000630e9cdd6d263c4cd80495e4231a2dc8"
UPSTREAM_URL = (
    "https://github.com/google-ai-edge/litert-samples/blob/"
    f"{UPSTREAM_COMMIT}/samples/litert_model_zoo/android/app/src/main/assets/models.json"
)
COLUMNS = [
    "taskId",
    "name",
    "group",
    "backend",
    "licenseName",
    "licenseUrl",
    "upstreamUrl",
    "modelCardUrl",
    "fileName",
    "url",
    "bytes",
    "sha256",
]
SAFE_ID = re.compile(r"^[a-z0-9][a-z0-9-]*$")
HEX64 = re.compile(r"^[0-9a-fA-F]{64}$")


def escape(value: object) -> str:
    text = str(value)
    if any(ch in text for ch in "\t\n\r"):
        # Keep the line-oriented format unambiguous instead of silently changing the value.
        return (
            text.replace("\\", "\\\\")
            .replace("\t", "\\t")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        )
    return text.replace("\\", "\\\\")


def build_rows(data: dict) -> list[list[str]]:
    rows: list[list[str]] = []
    seen: dict[str, set[str]] = {}
    for task in data["tasks"]:
        task_id = task["taskId"]
        if not SAFE_ID.fullmatch(task_id):
            raise SystemExit(f"unsafe taskId: {task_id!r}")
        names = seen.setdefault(task_id, set())
        for item in task["files"]:
            name = item["name"]
            if (
                not name
                or "/" in name
                or "\\" in name
                or name in (".", "..")
                or any(ord(ch) < 32 for ch in name)
            ):
                raise SystemExit(f"unsafe file name in {task_id}: {name!r}")
            if name in names:
                raise SystemExit(f"duplicate file name in {task_id}: {name!r}")
            names.add(name)
            if int(item["bytes"]) <= 0:
                raise SystemExit(f"invalid byte count for {task_id}/{name}")
            if not HEX64.fullmatch(item["sha256"]):
                raise SystemExit(f"invalid SHA-256 for {task_id}/{name}")
            if not str(item["url"]).startswith("https://"):
                raise SystemExit(f"non-HTTPS URL for {task_id}/{name}")
            rows.append(
                [
                    task_id,
                    task["model"],
                    task["group"],
                    task["backend"],
                    task["license"]["name"],
                    task["license"]["url"],
                    task["upstream"],
                    task["modelCard"],
                    name,
                    item["url"],
                    str(int(item["bytes"])),
                    item["sha256"].lower(),
                ]
            )
    return rows


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("json_path", type=Path)
    parser.add_argument("output_path", type=Path)
    args = parser.parse_args()

    data = json.loads(args.json_path.read_text(encoding="utf-8"))
    rows = build_rows(data)
    lines = [
        "# LiteRT model zoo asset catalog. Generated; do not edit by hand.",
        f"# Upstream: {UPSTREAM_URL}",
        f"# Pinned commit: {UPSTREAM_COMMIT}",
        "# Escapes: \\\\ backslash, \\t tab, \\n newline, \\r carriage return.",
        "# " + "\t".join(COLUMNS),
    ]
    lines.extend("\t".join(escape(value) for value in row) for row in rows)
    args.output_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {len(rows)} files for {len(data['tasks'])} tasks to {args.output_path}")


if __name__ == "__main__":
    main()
