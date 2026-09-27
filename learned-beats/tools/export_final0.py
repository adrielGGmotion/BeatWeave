#!/usr/bin/env python3
"""Reproduce optional final0 from the official MIT checkpoint and pinned source.

Python is build tooling only. Consumers load the resulting local ONNX model.
Usage: PYTHONPATH=/path/to/pinned/beat_this python export_final0.py final0.ckpt output.onnx
See models/final0-provenance.json for exact sources, versions and validation.
"""
import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path
import tempfile

import beat_this
from beat_this.inference import load_model
import onnx
import torch
from tile_final0_attention import tile_model

CHECKPOINT_SHA256 = "8c328b45f59d8dd3dff219253ff6a8d6482be57d0133a29140e2febbf8eb8331"
RAW_SHA256 = "842f64f16d021b9494782540cb37b6e2e56502142b37779e7e8ff0650a20088f"
MODEL_SHA256 = "c8293b7b787e73b40ad5e899624dbe08763268031543ef7ebcab53f8e78da66f"
SOURCE_SHA256 = {
    "beat_this/model/beat_tracker.py": "bfa2fd212b4d6bf00b007eb0e606a6b11f1f7fc44c95bd1121ef5d2a934a923f",
    "beat_this/model/roformer.py": "c83fa0047fe726bbfdb33658f40a0f9e721c5796067bfda560092e5969d50722",
    "beat_this/inference.py": "ccccff4665399b931ebf181b73d264a6ad64fc1fe8acb4d3b950df825c47ce2d",
    "beat_this/utils.py": "779d6102ea28bdf94c8227157790191f5abbbb4564d0c36ce5ce0859e067cfab",
}


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def rename_graph_values(graph, mapping):
    for value in list(graph.input) + list(graph.output) + list(graph.value_info):
        value.name = mapping.get(value.name, value.name)
    for node in graph.node:
        for index, name in enumerate(node.input):
            node.input[index] = mapping.get(name, name)
        for index, name in enumerate(node.output):
            node.output[index] = mapping.get(name, name)
        for attribute in node.attribute:
            if attribute.type == onnx.AttributeProto.GRAPH:
                rename_graph_values(attribute.g, mapping)
            elif attribute.type == onnx.AttributeProto.GRAPHS:
                for child in attribute.graphs:
                    rename_graph_values(child, mapping)


class Export(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, spectrogram):
        result = self.model(spectrogram)
        return result["beat"], result["downbeat"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkpoint", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    assert sha256(args.checkpoint) == CHECKPOINT_SHA256, "Wrong official final0 checkpoint"
    source_root = Path(beat_this.__file__).resolve().parent.parent
    for relative, expected in SOURCE_SHA256.items():
        assert sha256(source_root / relative) == expected, f"Source mismatch: {relative}"
    assert torch.__version__ == "2.5.1+cpu", "Use the pinned CPU Torch version for byte reproduction"
    assert onnx.__version__ == "1.19.0"
    assert importlib.metadata.version("einops") == "0.8.1"
    assert importlib.metadata.version("rotary-embedding-torch") == "0.8.6"
    torch.set_num_threads(2)
    torch.set_num_interop_threads(1)
    torch.manual_seed(20260927)
    model = load_model(str(args.checkpoint), "cpu").eval()
    # A cached rotary table during tracing can silently freeze sequence length.
    for module in model.modules():
        if hasattr(module, "cache_if_possible"):
            module.cache_if_possible = False
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="beatweave-final0-export-") as temporary:
        raw = Path(temporary) / "final0.raw.onnx"
        tiled = Path(temporary) / "final0.tiled.onnx"
        with torch.inference_mode():
            torch.onnx.export(Export(model).eval(), torch.zeros(1, 128, 128), str(raw),
                input_names=["spectrogram"], output_names=["beat", "downbeat"],
                dynamic_axes={"spectrogram": {1: "frames"}, "beat": {1: "frames"}, "downbeat": {1: "frames"}},
                opset_version=17, dynamo=False, do_constant_folding=True)
        assert sha256(raw) == RAW_SHA256, "Export differs from validated raw graph"
        tiling = tile_model(raw, tiled)
        graph = onnx.load(tiled)
        original = graph.SerializeToString()
        names = {"beat": "beat_logits", "downbeat": "downbeat_logits"}
        rename_graph_values(graph.graph, names)
        onnx.checker.check_model(graph, full_check=True)
        onnx.save(graph, args.output)
        rename_graph_values(graph.graph, {value: key for key, value in names.items()})
        assert graph.SerializeToString() == original, "Output renaming changed numerical graph"
    assert sha256(args.output) == MODEL_SHA256, "Final artifact differs from validated model"
    print(json.dumps({"output": str(args.output), "sha256": MODEL_SHA256,
                      "bytes": args.output.stat().st_size, "tiling": tiling,
                      "upstream_source_sha256": SOURCE_SHA256}, indent=2))


if __name__ == "__main__":
    main()
