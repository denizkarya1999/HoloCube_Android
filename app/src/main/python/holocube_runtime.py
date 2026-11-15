"""The Python entry point called by the app's background PyTorch worker.

Phone edition of the Quest app's holocube_runtime.py. The only difference is the
pixel order: OpenCV's letterbox output already starts at the top row, while the
headset's Unity GPU readback starts at the bottom.
"""
from pathlib import Path
import json
import numpy as np
import torch
from checkpoint_runtime import (load_model, load_orientation_model, orientation_rows, check_orientation_rows,
                                CHECKPOINT_SHA256, ORIENTATION_CHECKPOINT_SHA256, ORIENTATION_SIZE)

_model = None
_orientation = None
MODEL_SIZE = 320


def set_threads(threads=4):
    torch.set_num_threads(max(1, min(8, int(threads))))


def initialize(threads=4):
    global _model
    set_threads(threads)
    if _model is None:
        candidate = load_model(str(Path(__file__).with_name('holocube_transmitter.pt')))
        # Check the actual Android runtime against an independent real-image oracle.
        folder = Path(__file__).parent
        reference = json.loads((folder / 'reference.json').read_text())
        if reference['checkpoint_sha256'] != CHECKPOINT_SHA256:
            raise RuntimeError('Checkpoint startup check: reference belongs to another model.')
        data = np.fromfile(str(folder / 'reference.f32'), dtype='<f4').reshape(1, 3, MODEL_SIZE, MODEL_SIZE)
        with torch.no_grad():
            rows = candidate(torch.from_numpy(data)).numpy()[0]
        if rows.shape != (300, 6) or not np.isfinite(rows).all():
            raise RuntimeError('Checkpoint startup check: invalid detection rows.')
        found = rows[rows[:, 4] >= reference['threshold']]
        expected = reference['detections']
        if not expected or len(found) != len(expected):
            raise RuntimeError('Checkpoint startup check: wrong detection count.')
        scale = max(reference['width'], reference['height']) / MODEL_SIZE
        for row, other in zip(found, expected):
            if (int(row[5]) != other['classId'] or
                abs(float(row[4]) - other['confidence']) >= .001 or
                np.linalg.norm((row[:4] - other['box']) * scale) >= 1):
                raise RuntimeError('Checkpoint startup check: inference differs from reference.')
        _model = candidate
    return 'HOLOCUBE_PT_DEVICE_PARITY_PASSED: Transmitter Recognition Model; reference boxes match.'


def rgba_to_tensor(rgba):
    # Java byte[] values are signed; preserve the original bits as uint8.
    pixels = np.asarray(rgba, dtype=np.int8).view(np.uint8).reshape(MODEL_SIZE, MODEL_SIZE, 4)
    # OpenCV letterbox bytes are already top row first; keep RGB and drop alpha.
    rgb = np.ascontiguousarray(pixels[:, :, :3].transpose(2, 0, 1), dtype=np.float32) / 255.0
    return torch.from_numpy(rgb[None])


def detect_rgba(rgba):
    if _model is None:
        raise RuntimeError('Load the transmitter checkpoint before detecting.')
    with torch.no_grad():
        result = _model(rgba_to_tensor(rgba))
    if tuple(result.shape) != (1, 300, 6):
        raise RuntimeError('Expected YOLO26 detection rows [1,300,6].')
    return result.reshape(-1).tolist()


def initialize_orientation():
    """Load the Orientation Detection Model and repeat its reference check on this phone.

    Called after initialize(). A failure here leaves the transmitter model usable; the
    app then reports that orientation is unavailable instead of stopping.
    """
    global _orientation
    if _orientation is None:
        folder = Path(__file__).parent
        candidate = load_orientation_model(str(folder / 'holocube_orientation.pt'))
        reference = json.loads((folder / 'orientation_reference.json').read_text())
        if reference['checkpoint_sha256'] != ORIENTATION_CHECKPOINT_SHA256:
            raise RuntimeError('Orientation startup check: reference belongs to another model.')
        pixels = np.fromfile(str(folder / 'orientation_reference.u8'), dtype=np.uint8)
        pixels = pixels.reshape(ORIENTATION_SIZE, ORIENTATION_SIZE, 3)
        tensor = torch.from_numpy(np.ascontiguousarray(pixels.transpose(2, 0, 1), dtype=np.float32)[None] / 255.0)
        check_orientation_rows(orientation_rows(candidate, tensor), reference)
        _orientation = candidate
    return 'HOLOCUBE_ORIENTATION_DEVICE_PARITY_PASSED: Orientation Detection Model; reference box, class, and triangle match.'


def orientation_rgba_to_tensor(rgba):
    # The same conversion as rgba_to_tensor, for the 640 x 640 orientation input.
    pixels = np.asarray(rgba, dtype=np.int8).view(np.uint8).reshape(ORIENTATION_SIZE, ORIENTATION_SIZE, 4)
    rgb = np.ascontiguousarray(pixels[:, :, :3].transpose(2, 0, 1), dtype=np.float32) / 255.0
    return torch.from_numpy(rgb[None])


def detect_orientation_rgba(rgba):
    """Rows [x1,y1,x2,y2,confidence,class, ax,ay, bx,by, cx,cy], strongest first, flattened."""
    if _orientation is None:
        raise RuntimeError('Load the orientation checkpoint before detecting.')
    return orientation_rows(_orientation, orientation_rgba_to_tensor(rgba)).reshape(-1).tolist()
