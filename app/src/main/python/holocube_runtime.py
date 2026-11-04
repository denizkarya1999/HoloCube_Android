"""The Python entry point called by the app's background PyTorch worker.

Phone edition of the Quest app's holocube_runtime.py. The only difference is the
pixel order: OpenCV's letterbox output already starts at the top row, while the
headset's Unity GPU readback starts at the bottom.
"""
from pathlib import Path
import json
import numpy as np
import torch
from checkpoint_runtime import load_model

_model = None
MODEL_SIZE = 320


def set_threads(threads=4):
    torch.set_num_threads(max(1, min(8, int(threads))))


def initialize(threads=4):
    global _model
    set_threads(threads)
    if _model is None:
        _model = load_model(str(Path(__file__).with_name('yolo26n.pt')))
        # Check the actual Android runtime against an independent real-image oracle.
        folder = Path(__file__).parent
        reference = json.loads((folder / 'reference.json').read_text())
        data = np.fromfile(str(folder / 'reference.f32'), dtype='<f4').reshape(1, 3, MODEL_SIZE, MODEL_SIZE)
        with torch.no_grad():
            rows = _model(torch.from_numpy(data)).numpy()[0]
        found = rows[rows[:, 4] >= .35]
        expected = reference['detections']
        if len(found) != len(expected):
            raise RuntimeError('Checkpoint startup check: wrong detection count.')
        scale = max(reference['width'], reference['height']) / MODEL_SIZE
        for row, other in zip(found, expected):
            if (int(row[5]) != other['classId'] or
                abs(float(row[4]) - other['confidence']) >= .001 or
                np.linalg.norm((row[:4] - other['box']) * scale) >= 1):
                raise RuntimeError('Checkpoint startup check: inference differs from reference.')
    return 'HOLOCUBE_PT_DEVICE_PARITY_PASSED: original checkpoint; five reference detections match.'


def rgba_to_tensor(rgba):
    # Java byte[] values are signed; preserve the original bits as uint8.
    pixels = np.asarray(rgba, dtype=np.int8).view(np.uint8).reshape(MODEL_SIZE, MODEL_SIZE, 4)
    # OpenCV letterbox bytes are already top row first; keep RGB and drop alpha.
    rgb = np.ascontiguousarray(pixels[:, :, :3].transpose(2, 0, 1), dtype=np.float32) / 255.0
    return torch.from_numpy(rgb[None])


def detect_rgba(rgba):
    if _model is None:
        raise RuntimeError('Load the checkpoint before detecting objects.')
    with torch.no_grad():
        result = _model(rgba_to_tensor(rgba))
    if tuple(result.shape) != (1, 300, 6):
        raise RuntimeError('Expected YOLO26 detection rows [1,300,6].')
    return result.reshape(-1).tolist()
