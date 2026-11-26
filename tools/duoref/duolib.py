"""Helpers for measuring iPhone Duo Home Screen motion: record the simulator display while the
driver replays a touch path, then track coloured dummy icons frame by frame."""
import colorsys
import json
import os
import signal
import subprocess
import time

import numpy as np
from PIL import Image

import duoref

RUN = duoref.RUN
COVER, INNER = "1", "3"
N_APPS = 62


def app_rgb(i):
    """Icon colour of dummy app number i (1-based), as mkapps.py painted it."""
    return tuple(int(255 * c) for c in colorsys.hsv_to_rgb((i - 1) / N_APPS, 0.75, 0.95))


class Recorder:
    def __init__(self, name, display=INNER):
        self.path = os.path.join(RUN, name + ".mov")
        self.display = display
        self.proc = None

    def __enter__(self):
        if os.path.exists(self.path):
            os.remove(self.path)
        self.proc = subprocess.Popen(
            ["xcrun", "simctl", "io", duoref.UDID, "recordVideo", "--codec=h264", f"--display={self.display}", "--force", self.path],
            stderr=subprocess.PIPE, stdout=subprocess.DEVNULL, text=True)
        while True:
            line = self.proc.stderr.readline()
            if not line or "Recording started" in line:
                break
        self.t0 = time.time()
        time.sleep(0.4)
        return self

    def __exit__(self, *a):
        time.sleep(0.2)
        self.proc.send_signal(signal.SIGINT)
        self.proc.wait(timeout=30)


def frames(name, scale=1 / 3, rot=0):
    """Decodes run/<name>.mov into (times, array[n, h, w, 3]) at `scale` (1/3 = one pixel per point).
    The recording is always in the panel's native (portrait) orientation; `rot` = number of
    counter-clockwise quarter turns to apply so landscape UI reads upright (landscape-left: 1)."""
    path = os.path.join(RUN, name + ".mov")
    probe = json.loads(subprocess.check_output(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height:frame=pts_time",
         "-of", "json", path]))
    w, h = probe["streams"][0]["width"], probe["streams"][0]["height"]
    times = np.array([float(f["pts_time"]) for f in probe["frames"]])
    ow, oh = int(round(w * scale)), int(round(h * scale))
    raw = subprocess.check_output(
        ["ffmpeg", "-v", "error", "-noautorotate", "-i", path, "-fps_mode", "passthrough", "-vf", f"scale={ow}:{oh}:flags=area", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"])
    arr = np.frombuffer(raw, np.uint8).reshape(-1, oh, ow, 3)
    n = min(len(times), len(arr))
    arr = arr[:n]
    if rot:
        arr = np.ascontiguousarray(np.rot90(arr, rot, axes=(1, 2)))
    return times[:n], arr


def track(arr, rgb, tol=40, roi=None):
    """Centroid (x, y) and pixel count of the pixels close to `rgb` in every frame (NaN when absent)."""
    out = np.full((len(arr), 3), np.nan)
    c = np.array(rgb, np.int16)
    for i, f in enumerate(arr):
        g = f if roi is None else f[roi[1]:roi[3], roi[0]:roi[2]]
        m = (np.abs(g.astype(np.int16) - c).sum(axis=2) < tol)
        n = m.sum()
        if n >= 30:
            ys, xs = np.nonzero(m)
            ox, oy = (roi[0], roi[1]) if roi else (0, 0)
            out[i] = (xs.mean() + ox, ys.mean() + oy, n)
    return out


def bbox(arr, rgb, tol=40):
    """Bounding box (x0, y0, x1, y1) of the pixels close to `rgb` per frame."""
    out = np.full((len(arr), 4), np.nan)
    c = np.array(rgb, np.int16)
    for i, f in enumerate(arr):
        m = (np.abs(f.astype(np.int16) - c).sum(axis=2) < tol)
        if m.sum() >= 30:
            ys, xs = np.nonzero(m)
            out[i] = (xs.min(), ys.min(), xs.max() + 1, ys.max() + 1)
    return out


def swipe_points(x0, y0, x1, y1, duration, steps=None, ease=False):
    """A straight touch path sampled every ~1/60 s; `ease` decelerates toward the end (a held drag)."""
    n = steps or max(2, int(duration * 60))
    pts = []
    for k in range(n + 1):
        u = k / n
        v = 1 - (1 - u) ** 2 if ease else u
        pts.append([round(duration * u, 4), round(x0 + (x1 - x0) * v, 2), round(y0 + (y1 - y0) * v, 2)])
    return pts


def save_frame(arr, i, name):
    Image.fromarray(arr[i]).save(os.path.join(RUN, name))


def sheet(arr, idx, name, cols=6, scale=1.0):
    """Contact sheet of the given frame indices."""
    ims = [Image.fromarray(arr[i]) for i in idx]
    if scale != 1.0:
        ims = [im.resize((int(im.width * scale), int(im.height * scale))) for im in ims]
    w, h = ims[0].size
    rows = (len(ims) + cols - 1) // cols
    out = Image.new("RGB", (cols * w + (cols - 1) * 4, rows * h + (rows - 1) * 4), (255, 0, 255))
    for k, im in enumerate(ims):
        out.paste(im, ((k % cols) * (w + 4), (k // cols) * (h + 4)))
    out.save(os.path.join(RUN, name))


def hue_track(arr, app, roi=None, width=0.45):
    """Tracks one dummy app icon by hue (robust to the icon's gloss gradient and to neighbours, whose
    hues are one step of 1/N_APPS apart). Returns per frame (cx, cy, count, x0, x1, y0, y1)."""
    target = (app - 1) / N_APPS
    out = np.full((len(arr), 7), np.nan)
    for i, f in enumerate(arr):
        g = (f if roi is None else f[roi[1]:roi[3], roi[0]:roi[2]]).astype(np.float32) / 255
        mx = g.max(axis=2); mn = g.min(axis=2); d = mx - mn
        sat = np.where(mx > 0, d / np.maximum(mx, 1e-6), 0)
        r, gg, b = g[..., 0], g[..., 1], g[..., 2]
        dd = np.maximum(d, 1e-6)
        h = np.where(mx == r, ((gg - b) / dd) % 6, np.where(mx == gg, (b - r) / dd + 2, (r - gg) / dd + 4)) / 6
        dh = np.abs(((h - target + 0.5) % 1) - 0.5)
        m = (sat > 0.45) & (mx > 0.6) & (dh < width / N_APPS)
        n = int(m.sum())
        if n >= 40:
            ys, xs = np.nonzero(m)
            ox, oy = (roi[0], roi[1]) if roi else (0, 0)
            out[i] = (xs.mean() + ox, ys.mean() + oy, n, xs.min() + ox, xs.max() + 1 + ox, ys.min() + oy, ys.max() + 1 + oy)
    return out


def hue_mask(f, app, width=0.45):
    g = f.astype(np.float32) / 255
    mx = g.max(axis=2); mn = g.min(axis=2); d = mx - mn
    sat = np.where(mx > 0, d / np.maximum(mx, 1e-6), 0)
    r, gg, b = g[..., 0], g[..., 1], g[..., 2]
    dd = np.maximum(d, 1e-6)
    h = np.where(mx == r, ((gg - b) / dd) % 6, np.where(mx == gg, (b - r) / dd + 2, (r - gg) / dd + 4)) / 6
    dh = np.abs(((h - (app - 1) / N_APPS + 0.5) % 1) - 0.5)
    return (sat > 0.45) & (mx > 0.6) & (dh < width / N_APPS)


def icon_box(arr, app, min_run=12):
    """Per frame: the icon's box (x0, x1, y0, y1) from row/column profiles of its hue mask, ignoring
    stray pixels (a column counts when it holds at least `min_run` matching pixels)."""
    out = np.full((len(arr), 4), np.nan)
    for i, f in enumerate(arr):
        m = hue_mask(f, app)
        cols = np.nonzero(m.sum(axis=0) >= min_run)[0]
        rows = np.nonzero(m.sum(axis=1) >= min_run)[0]
        if len(cols) and len(rows):
            out[i] = (cols.min(), cols.max() + 1, rows.min(), rows.max() + 1)
    return out


# Posture presets: (display, video quarter turns, UI size, UI point -> native panel point).
MODES = {
    "cover": (COVER, 0, (466, 678), lambda x, y: (x, y)),
    "land": (INNER, 3, (951, 669), lambda x, y: (y, 951 - x)),          # inner, UI landscape-left
    "port_ud": (INNER, 2, (669, 951), lambda x, y: (669 - x, 951 - y)),  # inner, UI portrait upside down
    "port": (INNER, 0, (669, 951), lambda x, y: (x, y)),
}


def touch(mode, pts):
    """Replays UI-space points [[t, x, y], ...] on the display of `mode`."""
    disp, _, _, f = MODES[mode]
    native = [[t, *map(float, f(x, y))] for t, x, y in pts]
    return duoref.send("path", display=int(disp), orient=1, points=native)


def tap(mode, x, y):
    return touch(mode, [[0, x, y], [0.06, x, y]])


def hold(pts, d):
    t, x, y = pts[-1]
    return pts + [[round(t + d / 2, 4), x, y], [round(t + d, 4), x, y]]


def rec_frames(name, mode, scale=1 / 3):
    return frames(name, scale=scale, rot=MODES[mode][1])
