#!/usr/bin/env python3
"""Independent signal checks for BeatWeave; requires Python, NumPy, SciPy and FFmpeg.

fixtures DIR validates analytic fixtures emitted by MixerRegression.kt. The oracle is
the fixture's known sample timing/frequencies, never BeatWeave's own beat estimates.
tempo AUDIO reports independent autocorrelation evidence, NOT musical beat truth.
There are currently no human beat/downbeat annotations for the supplied song files.
"""
from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

import numpy as np
from scipy.ndimage import gaussian_filter1d
from scipy.signal import fftconvolve, find_peaks, stft


def read_pcm(path: Path) -> np.ndarray:
    pcm = np.fromfile(path, dtype="<f4")
    if len(pcm) % 2 or not np.all(np.isfinite(pcm)):
        raise ValueError(f"Invalid interleaved stereo PCM: {path}")
    return pcm.reshape(-1, 2).astype(np.float64)


def pitch_hz(x: np.ndarray, rate: int, expected: float) -> float:
    # Narrow search excludes the expected tone's separately generated harmonic.
    n = 1 << (len(x) * 4 - 1).bit_length()
    mag = abs(np.fft.rfft(x * np.hanning(len(x)), n))
    lo, hi = int(expected * 0.75 * n / rate), int(expected * 1.25 * n / rate)
    best = lo + np.argmax(mag[lo:hi])
    y = np.log(np.maximum(mag[best - 1:best + 2], 1e-30))
    fraction = 0.5 * (y[0] - y[2]) / (y[0] - 2 * y[1] + y[2])
    return float((best + fraction) * rate / n)


def pulse_metrics(pcm: np.ndarray, spec: dict) -> dict:
    rate = spec["rate"]
    power = np.mean(pcm * pcm, axis=1)
    smooth = gaussian_filter1d(power, rate * 0.0005)
    expected = np.asarray(spec["expected_seconds"])
    errors, signed_errors, widths, levels = [], [], [], []
    for timestamp in expected:
        lo, hi = max(0, int((timestamp - 0.1) * rate)), min(len(pcm), int((timestamp + 0.1) * rate))
        peak = lo + np.argmax(smooth[lo:hi])
        errors.append(abs(peak / rate - timestamp) * 1000)
        signed_errors.append((peak / rate - timestamp) * 1000)
        levels.append(float(smooth[peak]))
        a, b = max(0, peak - int(rate * 0.06)), min(len(pcm), peak + int(rate * 0.06))
        cdf = np.cumsum(power[a:b])
        if cdf[-1] > 0:
            q = np.searchsorted(cdf, np.asarray([0.1, 0.9]) * cdf[-1])
            widths.append(float((q[1] - q[0]) * 1000 / rate))
    peaks, _ = find_peaks(smooth, height=max(levels) * 0.1, distance=int(rate * 0.18))
    interior = peaks[(peaks / rate >= expected[0] - 0.1) & (peaks / rate <= expected[-1] + 0.1)]
    missing = int(np.sum(np.asarray(levels) < np.median(levels) * 0.1))
    result = {
        "transients_expected": len(expected), "transients_detected": len(interior),
        "missing": missing, "timing_p95_ms": float(np.quantile(errors, 0.95)),
        "timing_max_ms": float(max(errors)),
        "energy_width_p95_ms": float(np.quantile(widths, 0.95)),
        "source_energy_width_ms": spec["source_width_ms"],
        "first_quarter_mean_offset_ms": float(np.mean(signed_errors[:max(1, len(signed_errors) // 4)])),
        "last_quarter_mean_offset_ms": float(np.mean(signed_errors[-max(1, len(signed_errors) // 4):])),
    }
    result["pass"] = bool(
        missing == 0 and len(interior) == len(expected)
        and result["timing_p95_ms"] <= spec["p95_error_ms"]
        and result["timing_max_ms"] <= spec["max_error_ms"]
        and result["energy_width_p95_ms"] <= max(10.0, spec["source_width_ms"] * 2)
    )
    return result


def fixtures(directory: Path) -> dict:
    report = {}
    for spec in json.loads((directory / "manifest.json").read_text()):
        pcm = read_pcm(directory / (spec["name"] + ".f32"))
        rate = spec["rate"]
        if spec["type"] == "pulse":
            result = pulse_metrics(pcm, spec)
        elif spec["type"] == "tone":
            hz = pitch_hz(pcm[rate:7 * rate, 0], rate, spec["expected_hz"])
            cents = float(1200 * np.log2(hz / spec["expected_hz"]))
            segment = pcm[rate:7 * rate]
            stereo_error = float(np.linalg.norm(segment[:, 1] - spec["right_gain"] * segment[:, 0])
                                 / max(1e-15, np.linalg.norm(segment[:, 1])))
            result = {"pitch_hz": hz, "pitch_error_cents": cents, "stereo_relative_error": stereo_error,
                      "pass": bool(abs(cents) <= spec["max_pitch_cents"] and stereo_error <= 1e-4)}
            if spec.get("rolling_pitch"):
                if len(pcm) < 6 * rate:
                    raise ValueError("Full-duration pitch validation requires at least six seconds")
                windows = []
                # Check all of the changing tempo profile, including the outro.
                # The former 1..7-second-only probe missed later pitch failures.
                starts = [0.25, *range(1, int(len(pcm) / rate) - 4, 2),
                          len(pcm) / rate - 4.25]
                for start in sorted(set(s for s in starts if s >= 0.25)):
                    segment = pcm[round(start * rate):round((start + 4) * rate)]
                    pitches = []
                    for expected in spec.get("harmonics_hz", [spec["expected_hz"]]):
                        measured = pitch_hz(segment[:, 0], rate, expected)
                        pitches.append({"expected_hz": expected, "measured_hz": measured,
                                        "error_cents": float(1200 * np.log2(measured / expected))})
                    residual = segment[:, 1] - spec["right_gain"] * segment[:, 0]
                    stereo = float(np.sqrt(np.sum(residual * residual) /
                                           max(1e-30, np.sum(segment[:, 1] ** 2))))
                    windows.append({"start_seconds": start, "end_seconds": start + 4,
                                    "pitches": pitches, "stereo_relative_error": stereo})
                result["rolling_windows"] = windows
                result["worst_rolling_pitch_cents"] = max(abs(p["error_cents"])
                    for window in windows for p in window["pitches"])
                result["worst_rolling_stereo_relative_error"] = max(w["stereo_relative_error"] for w in windows)
                result["pass"] = bool(result["pass"] and
                    result["worst_rolling_pitch_cents"] <= spec["max_pitch_cents"] and
                    result["worst_rolling_stereo_relative_error"] <= 1e-4)
        elif spec["type"] == "identity":
            t = np.arange(len(pcm)) / rate
            reference = np.column_stack((0.30 * np.sin(2 * np.pi * 440 * t) + 0.17 * np.sin(2 * np.pi * 733 * t),
                                         0.29 * np.cos(2 * np.pi * 440 * t) + 0.19 * np.cos(2 * np.pi * 977 * t)))
            reference *= spec["gain"]
            error = float(np.linalg.norm(pcm - reference) / np.linalg.norm(reference))
            result = {"relative_rms_error": error, "pass": bool(error < 1e-4)}
        elif spec["type"] == "seek":
            full = read_pcm(directory / (spec["reference"] + ".f32"))
            start = int(spec["start_seconds"] * rate)
            reference = full[start:start + len(pcm)]
            error = float(np.linalg.norm(pcm - reference) / max(1e-15, np.linalg.norm(reference)))
            # A seek may legitimately reset spectral phase. Report sample mismatch,
            # but use frequency recovery as the perceptual gate for this tone fixture.
            hz = pitch_hz(pcm[rate // 2:-rate // 2, 0], rate, 440.0)
            cents = float(1200 * np.log2(hz / 440.0))
            result = {"crop_relative_rms_error_diagnostic": error, "pitch_error_cents": cents,
                      "pass": bool(abs(cents) <= 5.0 and (not spec.get("exact_crop") or error <= 1e-6))}
        else:
            raise ValueError(f"Unknown fixture kind {spec['type']}")
        result["peak"] = float(np.max(abs(pcm)))
        result["pass"] = result["pass"] and result["peak"] <= 1.0
        report[spec["name"]] = result
    report["all_pass"] = all(entry["pass"] for entry in report.values())
    return report


def tempo(path: Path) -> dict:
    rate, hop = 11025, 128
    raw = subprocess.check_output(["ffmpeg", "-v", "error", "-i", str(path), "-ac", "1", "-ar", str(rate),
                                   "-f", "f32le", "-"])
    pcm = np.frombuffer(raw, dtype="<f4")
    freq, times, z = stft(pcm, fs=rate, nperseg=1024, noverlap=1024 - hop, boundary=None)
    magnitude = np.log1p(abs(z) * 100)
    positive_flux = np.maximum(np.diff(magnitude, axis=1, prepend=magnitude[:, :1]), 0)
    evidence = []
    duration = len(pcm) / rate
    for label, lo, hi in [("full", 40, 5000), ("bass", 40, 250), ("treble", 1000, 5000)]:
        onset = positive_flux[(freq >= lo) & (freq <= hi)].sum(axis=0)
        onset = np.maximum(0, onset - gaussian_filter1d(onset, 25))
        onset /= gaussian_filter1d(onset, 100) + 1e-5
        for start, end in [(0.0, duration)] + [(float(s), min(float(s + 30), duration)) for s in range(10, int(duration - 15), 30)]:
            segment = onset[(times >= start) & (times < end)]
            segment = segment - np.mean(segment)
            correlation = fftconvolve(segment, segment[::-1], mode="full")[len(segment) - 1:]
            correlation /= np.arange(len(segment), 0, -1)
            correlation /= max(correlation[0], 1e-10)
            indices, _ = find_peaks(correlation)
            candidates = []
            for index in indices:
                bpm = 60.0 * rate / (hop * index)
                if not 70 <= bpm <= 180:
                    continue
                left, centre, right = correlation[index - 1:index + 2]
                fraction = 0.5 * (left - right) / (left - 2 * centre + right)
                candidates.append({"bpm": float(60.0 * rate / (hop * (index + fraction))),
                                   "autocorrelation": float(centre)})
            candidates.sort(key=lambda item: item["autocorrelation"], reverse=True)
            evidence.append({"band": label, "start_seconds": start, "end_seconds": end, "candidates": candidates[:4]})
    return {"source": path.name, "duration_seconds": duration,
            "interpretation": "Periodicity evidence only; neither BPM metadata nor these peaks annotate beats or downbeats.",
            "human_beat_ground_truth_available": False, "windows": evidence}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["fixtures", "tempo"])
    parser.add_argument("path", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = fixtures(args.path) if args.mode == "fixtures" else tempo(args.path)
    text = json.dumps(report, indent=2, allow_nan=False)
    if args.output:
        args.output.write_text(text + "\n")
    print(text)
    if args.mode == "fixtures" and not report["all_pass"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
