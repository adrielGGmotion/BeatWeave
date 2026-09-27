#!/usr/bin/env python3
"""Bound attention working memory without reducing the model's temporal context.

Run after export_model.py: python tile_attention.py raw.onnx tiled.onnx
Only queries are sliced; every query still attends to every key/value. The
weights, rotary positions, scaling, softmax reduction axis and output order are
unchanged. Uses only standard ONNX opset 17 operators (including Loop/Sequence).
Requires onnx and numpy as build-time tools; consumers still only need ORT.
"""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto as T, helper as h, numpy_helper as nh

RAW_SHA256 = "463f6c85a0fc613ebfe970e96ba4e4dcb47dd20dc7b63a5d3d1053e0a98a57d9"


def tile_model(source: Path, destination: Path, query_rows: int = 128):
    if not 1 <= query_rows <= 1500:
        raise ValueError("query_rows must be in 1..1500")
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    if digest != RAW_SHA256:
        raise ValueError("Expected the pinned, unmodified small0 export")
    model = onnx.load(source)
    nodes = list(model.graph.node)
    uses = {}
    for node in nodes:
        for name in node.input:
            uses[name] = uses.get(name, 0) + 1
    replacements = {}
    remove = set()
    names = []
    for index, softmax in enumerate(nodes):
        if softmax.op_type != "Softmax" or "/attnF/" in softmax.name:
            continue
        before, after = nodes[index - 1], nodes[index + 1]
        assert before.op_type == after.op_type == "MatMul"
        assert list(softmax.input) == list(before.output)
        assert after.input[0] == softmax.output[0]
        assert uses[before.output[0]] == uses[softmax.output[0]] == 1
        assert all(a.name == "axis" and a.i == -1 for a in softmax.attribute)
        q, kt = before.input
        value = after.input[1]
        p = f"beatweave_tiled_{len(names)}_"
        new = []
        initializers = []

        def constant(name, values, dtype=np.int64):
            full = p + name
            initializers.append(nh.from_array(np.asarray(values, dtype=dtype), full))
            return full

        rows = constant("rows", query_rows)
        rows_minus_one = constant("rows_minus_one", query_rows - 1)
        axis = constant("query_axis", 2)
        axes = constant("query_axes", [2])
        scalar_axis = constant("scalar_axis", [0])
        true = constant("true", True, np.bool_)
        new.extend([
            h.make_node("Shape", [q], [p + "shape"]),
            h.make_node("Gather", [p + "shape", axis], [p + "length"], axis=0),
            h.make_node("Add", [p + "length", rows_minus_one], [p + "ceil_numerator"]),
            h.make_node("Div", [p + "ceil_numerator", rows], [p + "iterations"]),
            h.make_node("SequenceEmpty", [], [p + "empty"], dtype=T.FLOAT),
        ])
        body_nodes = [
            h.make_node("Mul", [p + "iteration", rows], [p + "start_scalar"]),
            h.make_node("Add", [p + "start_scalar", rows], [p + "end_scalar"]),
            h.make_node("Unsqueeze", [p + "start_scalar", scalar_axis], [p + "starts"]),
            h.make_node("Unsqueeze", [p + "end_scalar", scalar_axis], [p + "ends"]),
            h.make_node("Slice", [q, p + "starts", p + "ends", axes], [p + "queries"]),
            h.make_node("MatMul", [p + "queries", kt], [p + "scores"]),
            h.make_node("Softmax", [p + "scores"], [p + "probabilities"], axis=-1),
            h.make_node("MatMul", [p + "probabilities", value], [p + "attended"]),
            h.make_node("SequenceInsert", [p + "sequence_in", p + "attended"], [p + "sequence_out"]),
            h.make_node("Identity", [p + "condition_in"], [p + "condition_out"]),
        ]
        body = h.make_graph(body_nodes, p + "body", [
            h.make_tensor_value_info(p + "iteration", T.INT64, []),
            h.make_tensor_value_info(p + "condition_in", T.BOOL, []),
            h.make_tensor_sequence_value_info(p + "sequence_in", T.FLOAT, [None]*4),
        ], [
            h.make_tensor_value_info(p + "condition_out", T.BOOL, []),
            h.make_tensor_sequence_value_info(p + "sequence_out", T.FLOAT, [None]*4),
        ])
        new.extend([
            h.make_node("Loop", [p + "iterations", true, p + "empty"], [p + "result_sequence"],
                        body=body, name=p + "Loop"),
            h.make_node("ConcatFromSequence", [p + "result_sequence"], list(after.output), axis=2, new_axis=0),
        ])
        model.graph.initializer.extend(initializers)
        replacements[index - 1] = new
        remove.update((index - 1, index, index + 1))
        names.append(softmax.name)
    assert len(names) == 9, f"Expected 9 temporal attention operations, found {len(names)}"
    del model.graph.node[:]
    for index, node in enumerate(nodes):
        if index in replacements:
            model.graph.node.extend(replacements[index])
        elif index not in remove:
            model.graph.node.append(node)
    onnx.checker.check_model(model, full_check=True)
    onnx.save(model, destination)
    return {"source_sha256": digest, "output_sha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
            "query_rows": query_rows, "temporal_context_frames": 1500,
            "onnx": onnx.__version__, "modified_operations": names}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--query-rows", type=int, default=128)
    args = parser.parse_args()
    print(json.dumps(tile_model(args.source, args.destination, args.query_rows), indent=2))
