# Models

BeatWeave distributes ONNX converted derivatives of the
[CPJKU Beat This!](https://github.com/CPJKU/beat_this) model weights. The upstream
[license statement](https://github.com/CPJKU/beat_this/blob/b95c8ab0c58c2d9fcfd40508ae8dffbc05ac4f5c/README.md#license)
identifies its code and published model weights as MIT licensed, and notes that
training data may have separate terms. Inference runs locally with ONNX Runtime;
applications supply the model bytes and the library never downloads them.

The ONNX files are distributed separately from the source. Download
[`BeatWeave-models-0.11.0.zip`](https://github.com/adrielGGmotion/BeatWeave/releases/download/v0.11.0/BeatWeave-models-0.11.0.zip),
extract it from the repository root, then check the files:

```sh
unzip /path/to/BeatWeave-models-0.11.0.zip
python3 tools/check-models.py
```

| File in `learned-beats/models/` | Size | SHA-256 |
| --- | ---: | --- |
| `beat-this-small0.onnx` | 9,470,458 bytes | `b6d54bca156b039593b6d9d48fd3ab3e5d09be06bbbe2706f0376c9f103d5191` |
| `beat-this-final0.onnx` | 82,113,665 bytes | `c8293b7b787e73b40ad5e899624dbe08763268031543ef7ebcab53f8e78da66f` |

The backend recognizes each variant by its checksum. `small0` is smaller;
`final0` has different accuracy and memory costs. Neither variant guarantees
correct bars. See [measured accuracy](accuracy.md) before choosing a default.

The 0.11.0 bundle refreshes library/policy metadata (`distribution.json`) and keeps
both ONNX files byte-for-byte identical to 0.10.0. Pulse-count compatibility and
sparse-outro handling are planner changes, not a retraining of Beat This!.
Existing checksum-verified model files remain compatible; update the library
and its version in analysis-cache keys to get the new behavior.

Package one model as an Android asset or load it from a local desktop file.
Weights are not embedded in the library AAR or JAR. Release maintainers can run
`./gradlew modelDistribution` after installing both files to produce a verified
model ZIP with licenses and provenance in `dist/`. Distribute it alongside the
matching public source release; see [Publishing](publishing.md).

Preserve the model ID, library
version, and recording/decoding identity in analysis cache keys. Reanalyze when
any of these change.

## Export provenance

The checked-in `provenance.json` and `final0-provenance.json` record upstream
revision, checkpoint hashes, export versions, and numerical comparisons. Their
references to `validation/` point into the separate 0.10.0 evidence archive.

Both exports retain the model's full 1,500-frame context. Attention is evaluated
in query tiles to reduce intermediate allocations. Model parity checks establish
agreement with upstream inference, not correctness of musical interpretation.

The scripts in `learned-beats/tools/` retain the export and comparison tooling.
`export_final0.py` checks the pinned checkpoint, upstream source, dependencies,
and output hash. Follow `final0-provenance.json` for its exact environment. The
small0 export and tiling scripts describe the earlier export pipeline; its
recorded Torch and ONNX versions differ between stages. Use the supplied,
checksum-verified ONNX files unless you are working on model export itself.

Model and frontend notices are in [learned-beats/licenses](../learned-beats/licenses).
