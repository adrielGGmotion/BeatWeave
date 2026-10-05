"""Synthetic source events independently check dense-map duration, timing and pitch."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
RATE = 48000
BINARY = Path(os.environ.get('BEATWEAVE_RENDERER_BINARY', ROOT / 'build/preference-training/warp-map'))

class MappedRendererTests(unittest.TestCase):
    def check_clock(self, source_time):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            output_frames = 25 * RATE
            output_times = np.r_[np.arange(0, 25, .05), (output_frames - 1) / RATE]
            source_times = source_time(output_times)
            source_frames = round(source_times[-1] * RATE) + 1
            target_events = np.arange(3., 25., 3.)
            source_events = source_time(target_events)
            t = np.arange(source_frames) / RATE
            x = .03 * np.sin(2 * np.pi * 440 * t)
            rng = np.random.default_rng(9182)
            for event in source_events:
                at = round(event * RATE)
                x[at:at + 960] += rng.normal(0, .5, 960) * np.exp(-np.arange(960) / 110)
            np.stack([x, x], axis=1).astype('<f4').tofile(work / 'input.f32')
            (work / 'map.txt').write_text(''.join(
                f'{round(s * RATE)} {round(o * RATE)}\n' for s, o in zip(source_times, output_times)
            ))
            subprocess.run([str(BINARY), str(work / 'input.f32'), str(work / 'output.f32'),
                            str(output_frames), str(work / 'map.txt')], check=True)
            y = np.fromfile(work / 'output.f32', dtype='<f4').reshape(-1, 2)
            self.assertLessEqual(abs(len(y) - output_frames), 4)
            self.assertTrue(np.isfinite(y).all())
            np.testing.assert_array_equal(y[:, 0], y[:, 1])
            errors = []
            for expected in target_events:
                lo, hi = round((expected - .30) * RATE), round((expected + .30) * RATE)
                found = (lo + np.argmax(abs(y[lo:hi, 0]))) / RATE
                errors.append(abs(found - expected))
            self.assertLess(max(errors), .03, errors)
            tone = y[4 * RATE:5 * RATE, 0]
            measured_hz = np.argmax(abs(np.fft.rfft(tone * np.hanning(len(tone))))) * RATE / len(tone)
            self.assertLessEqual(abs(measured_hz - 440), 2)
            print(f'{self.id()}: {len(y)} frames, max attack error {max(errors)*1000:.3f} ms, tone {measured_hz:.1f} Hz')

    def test_decreasing_speed_dense_map(self):
        # R3 returned 7,002 fewer frames on this independent synthetic fixture.
        self.check_clock(lambda t: t + .1 * (np.minimum(t, 20) - np.minimum(t, 20)**2 / 40))

    def test_oscillating_speed_dense_map(self):
        self.check_clock(lambda t: t * 1.07 + .04 * np.sin(2 * np.pi * t / 3))

    def test_reversed_map_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp)
            np.zeros((RATE, 2), dtype='<f4').tofile(p / 'input.f32')
            (p / 'map.txt').write_text('0 0\n24000 30000\n30000 25000\n')
            r = subprocess.run([str(BINARY), str(p / 'input.f32'), str(p / 'output.f32'),
                                str(RATE), str(p / 'map.txt')], capture_output=True)
            self.assertNotEqual(r.returncode, 0)
            self.assertFalse((p / 'output.f32').exists())

if __name__ == '__main__':
    unittest.main()
