#!/usr/bin/env python3
"""Verify local ONNX files against the checked-in model provenance."""

import argparse
import hashlib
import json
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", choices=("small0", "final0", "both"), default="both")
    parser.add_argument(
        "--directory", type=Path,
        default=Path(__file__).resolve().parents[1] / "learned-beats/models",
    )
    args = parser.parse_args()
    provenance_root = Path(__file__).resolve().parents[1] / "learned-beats/models"
    variants = ("small0", "final0") if args.variant == "both" else (args.variant,)
    failed = False
    for variant in variants:
        provenance_name = "provenance.json" if variant == "small0" else "final0-provenance.json"
        expected = json.loads((provenance_root / provenance_name).read_text())["onnx_sha256"]
        model = args.directory / f"beat-this-{variant}.onnx"
        if not model.is_file():
            print(f"Missing {model.name}; extract the model archive into the repository root.")
            failed = True
            continue
        with model.open("rb") as stream:
            actual = hashlib.file_digest(stream, "sha256").hexdigest()
        if actual != expected:
            print(f"Checksum mismatch: {model.name}\n  expected {expected}\n  actual   {actual}")
            failed = True
        else:
            print(f"OK {model.name} ({model.stat().st_size:,} bytes)")
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
