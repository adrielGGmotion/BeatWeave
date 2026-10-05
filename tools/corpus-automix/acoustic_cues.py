#!/usr/bin/env python3
"""CPU-batched inference using the independently pretrained MIT CUE-DETR model.

This is not a model trained by BeatWeave. Preprocessing follows the authors'
cue_points.py; temporal nonmaximum suppression uses seconds, not array indices.
"""
import argparse
import hashlib
import json
from pathlib import Path
import time
import numpy as np


def temporal_peaks(times, scores, radius=2.0):
    selected = []
    for i in np.argsort(-np.asarray(scores)):
        if all(abs(times[i] - times[j]) >= radius for j in selected):
            selected.append(int(i))
    return sorted(selected, key=lambda i: times[i])


def run(data, checkpoint, output):
    checkpoint_hash=hashlib.sha256((checkpoint/'model.safetensors').read_bytes()).hexdigest()
    if checkpoint_hash!='195b80f5c94e4b424b417a7c04e0aac238082c584ddcb65d01be8a97c69cadd3':
        raise ValueError('CUE-DETR checkpoint changed; review before inference')
    import torch
    import librosa
    from matplotlib import cm
    from transformers import DetrImageProcessor, DetrForObjectDetection, DetrConfig
    torch.set_num_threads(2)
    torch.set_num_interop_threads(2)
    config = DetrConfig.from_pretrained(checkpoint, local_files_only=True)
    config.use_pretrained_backbone = False
    model, loading = DetrForObjectDetection.from_pretrained(
        checkpoint, config=config, local_files_only=True, output_loading_info=True)
    if loading['missing_keys'] or loading['unexpected_keys'] or loading['mismatched_keys']:
        raise ValueError(loading)
    model.eval()
    processor = DetrImageProcessor(do_resize=False)
    started = time.monotonic()
    tracks = []
    for index in range(6):
        path = data / f'track-{index}.mono.f32'
        y = np.fromfile(path, dtype='<f4')
        spec = librosa.feature.melspectrogram(y=y, sr=22050, n_fft=2048)
        db = librosa.power_to_db(spec, ref=np.max)[::-1]
        mapper = cm.ScalarMappable(cmap='viridis')
        mapper.set_clim(db.min(), db.max())
        rgb = mapper.to_rgba(db, bytes=True)[:, :, :3]
        windows, borders, positions, scores = [], [], [], []
        count = int(np.floor((rgb.shape[1] + 266) / 88.75))
        for i in range(count):
            left = int(np.floor(i * 88.75)) - 266
            right = left + 355
            segment = rgb[:, max(0, left):min(rgb.shape[1], right)]
            segment = np.pad(segment, ((0, 0), (max(0, -left), max(0, right-rgb.shape[1])), (0, 0)), mode='linear_ramp')
            windows.append(segment)
            borders.append(left)
            if len(windows) == 4 or i == count - 1:
                batch = processor(images=windows, return_tensors='pt', do_resize=False)
                with torch.inference_mode():
                    predictions = model(**batch)
                decoded = processor.post_process_object_detection(predictions, threshold=0, target_sizes=[(128, 355)]*len(windows))
                for pred, border in zip(decoded, borders):
                    center = (pred['boxes'][:, 0] + pred['boxes'][:, 2]).numpy()/2 + border
                    positions.extend((center * 512 / 22050).tolist())
                    scores.extend(pred['scores'].numpy().tolist())
                windows, borders = [], []
        positions, scores = np.asarray(positions), np.asarray(scores)
        mask = (positions >= 0) & (positions < len(y)/22050)
        positions, scores = positions[mask], scores[mask]
        # Keep raw probabilities. No per-track min-max rescaling as confidence.
        peaks = temporal_peaks(positions, scores)
        item = {'track_index': index, 'duration': len(y)/22050, 'cues': [
            {'time': float(positions[i]), 'score': float(scores[i])} for i in peaks]}
        tracks.append(item)
        print('CUE-DETR track', index, 'windows', count, 'seconds', round(time.monotonic()-started, 1), flush=True)
    result = {'model': 'disco-eth/cue-detr', 'source': 'https://github.com/eth-disco/cue-detr',
              'license': 'MIT', 'trained_by_us': False,
              'checkpoint_sha256': checkpoint_hash,
              'preprocessing': '22050 Hz, mel 128/2048/512, viridis, 355-frame windows, 75% overlap; temporal NMS 2 s',
              'elapsed_seconds': time.monotonic()-started, 'tracks': tracks}
    output.write_text(json.dumps(result, indent=2)+'\n')


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('data', type=Path)
    p.add_argument('checkpoint', type=Path)
    p.add_argument('output', type=Path)
    a = p.parse_args()
    run(a.data, a.checkpoint, a.output)
