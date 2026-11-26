#!/usr/bin/env python3
"""Client for the DuoRef XCUITest driver (see Driver/Driver.swift).

  duoref.py serve                 start the driver on the simulator (blocks; run in background)
  duoref.py <op> [key=value ...]  send one command, print the result
  duoref.py shot <file> [display] screenshot via simctl (display 1 = cover, 3 = inner)

Ops: ping, dump, frame, tap x= y=, press x= y= duration=, drag x= y= x2= y2= velocity= press= hold=,
     path points=[[t,x,y],...], rotate to=portrait|left|right, home, launch bundle=, quit
"""
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
RUN = os.path.join(HERE, "run")


def _udid():
    """DUO_UDID, else run/udid (written when the dedicated DuoRef simulator is created)."""
    if os.environ.get("DUO_UDID"):
        return os.environ["DUO_UDID"]
    try:
        with open(os.path.join(RUN, "udid")) as f:
            return f.read().strip()
    except OSError:
        return "booted"


UDID = _udid()


def send(op, timeout=120, **kw):
    os.makedirs(RUN, exist_ok=True)
    cid = int(time.time() * 1000)
    out = os.path.join(RUN, "out.json")
    cmd = os.path.join(RUN, "cmd.json")
    kw.update(op=op, id=cid)
    with open(cmd + ".tmp", "w") as f:
        json.dump(kw, f)
    os.replace(cmd + ".tmp", cmd)
    end = time.time() + timeout
    while time.time() < end:
        try:
            with open(out) as f:
                r = json.load(f)
            if r.get("id") == cid:
                return r
        except (OSError, ValueError):
            pass
        time.sleep(0.01)
    raise TimeoutError(op)


def serve():
    os.makedirs(RUN, exist_ok=True)
    env = dict(os.environ, TEST_RUNNER_DUOREF_DIR=RUN)
    return subprocess.call([
        "xcodebuild", "test-without-building", "-project", os.path.join(HERE, "DuoRef.xcodeproj"),
        "-scheme", "Driver", "-destination", f"id={UDID}", "-derivedDataPath", os.path.join(HERE, "build"),
        "-only-testing:Driver/Driver/testServe",
    ], env=env)


def shot(path, display="3"):
    subprocess.check_call(["xcrun", "simctl", "io", UDID, "screenshot", f"--display={display}", path],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    op = sys.argv[1]
    if op == "serve":
        return serve()
    if op == "shot":
        shot(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "3")
        return 0
    kw = {}
    for a in sys.argv[2:]:
        k, v = a.split("=", 1)
        try:
            kw[k] = json.loads(v)
        except ValueError:
            kw[k] = v
    r = send(op, **kw)
    res = r.get("result")
    print(res if isinstance(res, str) else json.dumps(r, indent=1))
    return 0 if r.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
