#!/usr/bin/env python3
"""Rewrites the reference simulator's Home Screen: full pages of dummy apps and 4 icons in the dock
(the right rail on iPhone Duo), then restarts SpringBoard so it loads the layout."""
import os, plistlib, subprocess, time
HERE = os.path.dirname(os.path.abspath(__file__))
UDID = open(os.path.join(HERE, "run/udid")).read().strip()
P = os.path.expanduser(f"~/Library/Developer/CoreSimulator/Devices/{UDID}/data/Library/SpringBoard/IconState.plist")
with open(P, "rb") as f:
    st = plistlib.load(f)
apps = [f"dev.duoref.app{i:02d}" for i in range(1, 63)]
st["buttonBar"] = ["com.apple.mobilesafari"] + apps[59:62]
home = ["com.apple.Preferences"] + apps[:59]
# A folder in the second slot, holding apps 2, 3, 5, 6 and 7 (for the folder open/close reference).
inside = [apps[i] for i in (1, 2, 4, 5, 6)]
home = [a for a in home if a not in inside]
home.insert(1, {"displayName": "Tools", "listType": "folder", "iconLists": [inside]})
st["iconLists"] = [home[i:i + 24] for i in range(0, len(home), 24)]
with open(P, "wb") as f:
    plistlib.dump(st, f, fmt=plistlib.FMT_BINARY)
subprocess.call(["xcrun", "simctl", "spawn", UDID, "launchctl", "kill", "KILL", "system/com.apple.SpringBoard"])
time.sleep(1)
with open(P, "rb") as f:
    st = plistlib.load(f)
print("dock:", len(st["buttonBar"]), "pages:", [len(p) for p in st["iconLists"]])
