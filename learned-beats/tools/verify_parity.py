#!/usr/bin/env python3
"""Compare packaged Kotlin frontend + ONNX backend + postprocessing with official upstream.
Inputs are decoded mono22050 f32, and Kotlin output prefix from LearnedAnalyzeJvm.kt.
Never downloads data or contacts a service. Upstream path supplied through PYTHONPATH.
"""
import os
os.environ['ORT_DISABLE_TELEMETRY']='1'
import sys, json
from pathlib import Path
import numpy as np
import torch
from beat_this.preprocessing import LogMelSpect
from beat_this.inference import Spect2Frames
from beat_this.model.postprocessor import Postprocessor

torch.set_num_threads(2)
checkpoint, pcm_path, prefix = sys.argv[1:]
pcm = np.fromfile(pcm_path, dtype='<f4')
with torch.inference_mode():
    expected_mel = LogMelSpect()(torch.from_numpy(pcm))
    expected_b, expected_d = Spect2Frames(checkpoint)(expected_mel)
    beat_times, downbeat_times = Postprocessor()(expected_b, expected_d)
actual_mel = np.fromfile(prefix+'.mel.f32',dtype='<f4').reshape(expected_mel.shape)
actual_b = np.fromfile(prefix+'.beat-logits.f32',dtype='<f4')
actual_d = np.fromfile(prefix+'.downbeat-logits.f32',dtype='<f4')
rows = np.genfromtxt(prefix+'.beats.tsv',delimiter='\t',names=True,dtype=None,encoding='utf8')
actual_beat = rows['seconds']
actual_downbeat = rows['seconds'][rows['downbeat']=='true'] if rows['downbeat'].dtype.kind in 'US' else rows['seconds'][rows['downbeat']]
mel_error = np.abs(actual_mel-expected_mel.numpy())
b_error = np.abs(actual_b-expected_b.numpy()); d_error=np.abs(actual_d-expected_d.numpy())
beat_equal = actual_beat.shape==beat_times.shape and np.allclose(actual_beat,beat_times,atol=1e-10)
downbeat_equal = actual_downbeat.shape==downbeat_times.shape and np.allclose(actual_downbeat,downbeat_times,atol=1e-10)
report = {
 'input': Path(pcm_path).name, 'duration_seconds': len(pcm)/22050,
 'frontend_max_absolute_error':float(mel_error.max()), 'frontend_mean_absolute_error':float(mel_error.mean()),
 'beat_logit_max_absolute_error':float(b_error.max()),'downbeat_logit_max_absolute_error':float(d_error.max()),
 'beat_times_exact_upstream_match':bool(beat_equal),'downbeat_times_exact_upstream_match':bool(downbeat_equal),
 'beats':len(beat_times),'downbeats':len(downbeat_times),
 'tempo_from_median_eight_beat_span': float(480/np.median(beat_times[8:]-beat_times[:-8])) if len(beat_times)>8 else None,
 'downbeat_interval_histogram':dict(zip(*[list(map(int,v)) for v in np.unique(np.diff(np.searchsorted(beat_times,downbeat_times)),return_counts=True)])),
 'accuracy_note':'Upstream parity proves implementation fidelity, not human annotated beat accuracy. 20 ms output grid. Model scores are not calibrated.'
}
Path(prefix+'.parity.json').write_text(json.dumps(report,indent=2)+'\n')
expected_mel.numpy().astype('<f4').tofile(prefix+'.reference.mel.f32')
expected_b.numpy().astype('<f4').tofile(prefix+'.reference.beat-logits.f32')
expected_d.numpy().astype('<f4').tofile(prefix+'.reference.downbeat-logits.f32')
np.savetxt(prefix+'.reference.beats.tsv',beat_times,delimiter='\t')
np.savetxt(prefix+'.reference.downbeats.tsv',downbeat_times,delimiter='\t')
print(json.dumps(report,indent=2))
assert mel_error.max()<0.003
assert b_error.max()<0.004 and d_error.max()<0.004
assert beat_equal and downbeat_equal
