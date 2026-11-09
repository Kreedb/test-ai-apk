#!/usr/bin/env python3
"""Tests for the model shape tool. Run with `python3 tools/test_staticize_tflite_shapes.py`."""

import os
import sys
import tempfile
import unittest
from unittest import mock

# Keep the checkout clean when importing the tool.
sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import staticize_tflite_shapes as staticize  # noqa: E402


class AtomicWriteTest(unittest.TestCase):
    def test_replaces_original_on_success(self):
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "model.tflite")
            with open(path, "wb") as model:
                model.write(b"old")
            staticize.write_atomically(path, b"new")
            with open(path, "rb") as model:
                self.assertEqual(b"new", model.read())
            self.assertEqual([], [name for name in os.listdir(directory) if name.endswith(".tmp")])

    def test_failed_write_keeps_original(self):
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "model.tflite")
            original = b"original model bytes"
            with open(path, "wb") as model:
                model.write(original)
            with mock.patch("os.fsync", side_effect=OSError("disk full")):
                with self.assertRaises(OSError):
                    staticize.write_atomically(path, b"x" * 4096)
            with open(path, "rb") as model:
                self.assertEqual(original, model.read())
            self.assertEqual([], [name for name in os.listdir(directory) if name.endswith(".tmp")])


class PoseContractTest(unittest.TestCase):
    def test_bundled_model_matches_app_contract(self):
        path = os.path.join(
            os.path.dirname(os.path.abspath(__file__)),
            "..",
            "app",
            "src",
            "main",
            "assets",
            "models",
            "yolo26n_pose.tflite",
        )
        with open(path, "rb") as model:
            data = bytearray(model.read())
        staticize.validate_pose_contract(data)


if __name__ == "__main__":
    unittest.main()
