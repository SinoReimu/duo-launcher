#!/usr/bin/env python3
"""Summarises a SpringBoard dump (duoref.py dump): visible Home icons, dock, page dots, search."""
import re, sys
W = {"cover": (466, 678), "land": (951, 669), "port": (669, 951)}
def parse(path, size):
    w, h = size
    rx = re.compile(r"^(\s*)(\w+), 0x[0-9a-f]+, \{\{(-?[\d.]+), (-?[\d.]+)\}, \{([\d.]+), ([\d.]+)\}\}(.*)$")
    rows = []
    for line in open(path):
        m = rx.match(line.rstrip("\n"))
        if not m: continue
        ind, typ, x, y, ww, hh, rest = m.groups()
        idm = re.search(r"identifier: '([^']*)'", rest); lb = re.search(r"label: '([^']*)'", rest); vm = re.search(r"value: (.*)$", rest)
        rows.append(dict(depth=len(ind), type=typ, x=float(x), y=float(y), w=float(ww), h=float(hh),
                         id=idm.group(1) if idm else "", label=lb.group(1) if lb else "", value=vm.group(1) if vm else ""))
    return rows
def main():
    name = sys.argv[1]; size = W[name]
    rows = parse(f"run/dump_{name}.txt", size)
    # Use the last full-size window copy of each icon (the dump lists scaled-down snapshot windows too).
    icons = {}
    for i, r in enumerate(rows):
        if r["type"] == "Icon" and r["id"] and r["w"] >= 40:
            icons[r["id"] + "@%d,%d" % (r["x"], r["y"])] = (r, rows[i + 1: i + 12])
    vis = [(r, kids) for r, kids in icons.values() if -1 <= r["x"] <= size[0] and 0 <= r["y"] <= size[1]]
    vis.sort(key=lambda t: (t[0]["y"], t[0]["x"]))
    print(f"== {name} {size}")
    for r, kids in vis:
        img = next((k for k in kids if abs(k["w"] - k["h"]) < 0.5 and k["w"] >= 40), None)
        lab = next((k for k in kids if k["id"] == "label-view"), None)
        s = f"{r['id']:14s} view=({r['x']:.1f},{r['y']:.1f} {r['w']:.1f}x{r['h']:.1f})"
        if img: s += f" icon=({img['x']:.1f},{img['y']:.1f} {img['w']:.0f})"
        if lab: s += f" label=({lab['x']:.1f},{lab['y']:.1f} {lab['w']:.1f}x{lab['h']:.1f})"
        print(s)
    for r in rows:
        if r["id"] in ("Multitasking Dock", "user icon list view", "spotlight-search-button", "Page control") and r["w"] >= 40 or r["type"] == "PageIndicator":
            if r["x"] <= size[0] and r["w"] > 30:
                print(f"{r['id'] or r['type']:26s} ({r['x']:.1f},{r['y']:.1f} {r['w']:.1f}x{r['h']:.1f}) {r['value']}")
main()
