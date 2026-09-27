#!/usr/bin/env python3
"""Compare raw/tiled models at dynamic edges and on full cached Kotlin mel inputs.

Writes exact file hashes, max logit error, and full-song peak equality. Runs the
same 1500-frame/6-frame-border keep-first convention as BeatThisAnalyzer. Does
not use annotations, move peaks, or claim musical correctness from model parity.
"""
import argparse
import hashlib
import json
from pathlib import Path
import time

import numpy as np
import onnxruntime as ort


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def peaks(values):
    chosen = [i for i, v in enumerate(values)
              if v > 0 and v >= max(values[max(0, i-3):i+4])]
    groups = []
    for i in chosen:
        if groups and groups[-1][-1] == i - 1:
            groups[-1].append(i)
        else:
            groups.append([i])
    return [sum(g) / len(g) for g in groups]


def musical_events(outputs):
    beat = peaks(outputs[0])
    down = peaks(outputs[1])
    return beat, sorted(set(min(beat, key=lambda b: abs(b-d)) for d in down)) if beat else []


def full_song(session, mel):
    count = len(mel)
    starts = list(range(-6, count - 6, 1488))
    if count > 1488:
        starts[-1] = count - 1494
    result = np.full((2, count), -1000, dtype=np.float32)
    assigned = np.zeros(count, dtype=bool)
    for start in starts:
        lo, hi = max(0, start), min(start + 1500, count)
        left, right = max(0, -start), max(0, min(6, start + 1500 - count))
        chunk = np.pad(mel[lo:hi], ((left, right), (0, 0)))[None]
        output = np.asarray(session.run(None, {"spectrogram": chunk}))[:, 0, :]
        for local in range(6, len(chunk[0]) - 6):
            target = start + local
            if 0 <= target < count and not assigned[target]:
                result[:, target] = output[:, local]
                assigned[target] = True
    assert assigned.all()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("raw", type=Path)
    parser.add_argument("tiled", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--mel-directory", action="append", type=Path, default=[])
    args = parser.parse_args()
    ort.disable_telemetry_events()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    options.enable_cpu_mem_arena = False
    options.enable_mem_pattern = False
    sessions = [ort.InferenceSession(str(p), options, providers=["CPUExecutionProvider"])
                for p in (args.raw, args.tiled)]
    report = {"raw_sha256": sha(args.raw), "tiled_sha256": sha(args.tiled),
              "verifier_sha256": sha(Path(__file__)), "runtime": ort.__version__,
              "absolute_logit_error_limit": 0.0001, "require_peak_identity": True,
              "dynamic_shapes": [], "recordings": []}
    rng = np.random.default_rng(9125)
    for frames in (1, 6, 63, 64, 65, 127, 128, 129, 151, 255, 256, 257, 500, 1499, 1500):
        inputs = rng.standard_normal((1, frames, 128), dtype=np.float32)
        before, after = [np.asarray(s.run(None, {"spectrogram": inputs}))[:, 0, :] for s in sessions]
        row = {"frames": frames, "max_abs_logit_error": np.max(abs(before-after), axis=1).tolist(),
               "bit_identical": bool(np.array_equal(before, after)),
               "peaks_identical": musical_events(before) == musical_events(after)}
        report["dynamic_shapes"].append(row)
        assert max(row["max_abs_logit_error"]) < 0.0001 and row["peaks_identical"], row
    for directory in args.mel_directory:
        for path in sorted(directory.glob("*.mel.f32")):
            mel = np.fromfile(path, dtype="<f4").reshape(-1, 128)
            started = time.perf_counter()
            before, after = [full_song(s, mel) for s in sessions]
            before_events, after_events = musical_events(before), musical_events(after)
            row = {"name": path.name, "mel_sha256": sha(path), "frames": len(mel),
                   "max_abs_logit_error": np.max(abs(before-after), axis=1).tolist(),
                   "bit_identical": bool(np.array_equal(before, after)),
                   "peaks_identical": before_events == after_events,
                   "beat_count": len(before_events[0]), "downbeat_count": len(before_events[1]),
                   "reference_logits_sha256": hashlib.sha256(before.astype('<f4').tobytes()).hexdigest(),
                   "tiled_logits_sha256": hashlib.sha256(after.astype('<f4').tobytes()).hexdigest(),
                   "elapsed_seconds_both_models": time.perf_counter() - started}
            report["recordings"].append(row)
            args.output.write_text(json.dumps(report, indent=2) + "\n")
            print(json.dumps(row), flush=True)
            assert max(row["max_abs_logit_error"]) < 0.0001 and row["peaks_identical"], row
    report["status"] = "PASS"
    args.output.write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
