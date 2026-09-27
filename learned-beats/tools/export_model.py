#!/usr/bin/env python3
"""Reproducibly export the MIT CPJKU small0 checkpoint; Python is build tooling only.
Usage: PYTHONPATH=/path/to/beat_this python export_model.py checkpoint.ckpt output.onnx
See models/provenance.json for the exact upstream commit and hashes.
"""
import os
os.environ["ORT_DISABLE_TELEMETRY"] = "1"
import hashlib, json, sys
from pathlib import Path
import numpy as np
import onnx
import onnxruntime as ort
ort.disable_telemetry_events()
import torch
from beat_this.inference import load_model
from rotary_embedding_torch import RotaryEmbedding
from tile_attention import tile_model

torch.set_num_threads(2)
torch.manual_seed(842)
model = load_model(sys.argv[1]).eval()
# Caching rotary positions during tracing silently freezes sequence length.
for module in model.modules():
    if isinstance(module, RotaryEmbedding):
        module.cache_if_possible = False
        module.cached_freqs_seq_len = 0
class Export(torch.nn.Module):
    def __init__(self, model):
        super().__init__(); self.model = model
    def forward(self, spectrogram):
        result = self.model(spectrogram)
        return result['beat'], result['downbeat']
wrapper = Export(model).eval()
x = torch.randn(1, 1500, 128)
output_path = Path(sys.argv[2])
raw_path = output_path.with_name(output_path.stem + '.raw.onnx')
with torch.inference_mode():
    torch.onnx.export(wrapper, (x,), str(raw_path), input_names=['spectrogram'],
        output_names=['beat_logits', 'downbeat_logits'], opset_version=17,
        dynamo=False, do_constant_folding=True,
        dynamic_axes={'spectrogram': {1:'frames'}, 'beat_logits': {1:'frames'}, 'downbeat_logits': {1:'frames'}})
onnx.checker.check_model(str(raw_path))
tiling = tile_model(raw_path, output_path)
options = ort.SessionOptions(); options.intra_op_num_threads = 2
options.inter_op_num_threads = 1
options.enable_cpu_mem_arena = False
options.enable_mem_pattern = False
session = ort.InferenceSession(sys.argv[2], options, providers=['CPUExecutionProvider'])
checks = []
for frames in (151, 500, 1500):
    inp = torch.randn(1, frames, 128)
    with torch.inference_mode():
        expected = wrapper(inp)
    actual = session.run(None, {'spectrogram': inp.numpy()})
    errors = [float(np.max(np.abs(a - e.numpy()))) for a,e in zip(actual, expected)]
    assert max(errors) < 0.002, (frames, errors)
    checks.append({'frames': frames, 'max_abs_logit_error': errors})
manifest = {
 'model':'CPJKU/beat_this small0', 'license':'MIT (code and published weights)',
 'upstream_repository':'https://github.com/CPJKU/beat_this',
 'upstream_commit':'b95c8ab0c58c2d9fcfd40508ae8dffbc05ac4f5c',
 'checkpoint_url':'https://cloud.cp.jku.at/public.php/dav/files/7ik4RrBKTS273gp/small0.ckpt',
 'checkpoint_sha256': hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest(),
 'onnx_sha256':hashlib.sha256(Path(sys.argv[2]).read_bytes()).hexdigest(),
 'raw_onnx_sha256':hashlib.sha256(raw_path.read_bytes()).hexdigest(),
 'attention_tiling':tiling,
 'opset':17, 'input':'float32 [1,frames,128] logmel', 'output':'float32 [1,frames] beat_logits, downbeat_logits',
 'torch':torch.__version__, 'onnx':onnx.__version__, 'onnxruntime':ort.__version__,
 'numerical_checks':checks,
 'accuracy_note':'Numerical parity with upstream is not a claim that predicted beats are always correct.'
}
Path(sys.argv[2]).with_name('provenance.json').write_text(json.dumps(manifest, indent=2)+'\n')
print(json.dumps(manifest, indent=2))
