#!/usr/bin/env python3
"""Fills the reference simulator with dummy apps (coloured icons) so Home Screen pages are full.
usage: mkapps.py <count>   (needs build/…/Host.app from `xcodebuild build-for-testing`)"""
import colorsys, os, plistlib, shutil, subprocess, sys
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.join(HERE, "build/Build/Products/Debug-iphonesimulator/Host.app")
OUT = os.path.join(HERE, "run/apps")
UDID = open(os.path.join(HERE, "run/udid")).read().strip()
WORDS = ("Alpha Bravo Charlie Delta Echo Foxtrot Golf Hotel India Juliett Kilo Lima Mike November Oscar Papa "
         "Quebec Romeo Sierra Tango Uniform Victor Whiskey Xray Yankee Zulu Amber Birch Cedar Dune Ember Fern "
         "Grove Harbor Iris Jade Kelp Lotus Maple Nimbus Opal Pine Quartz Reef Sage Tide Umber Vale Willow "
         "Yarrow Zephyr Atlas Beacon Comet Drift Flint Glade Haven Inlet Knoll Ledge Mesa Notch Orbit Prism "
         "Ridge Slate Thorn Vista Wren").split()

def icon(i, n, size):
    h = i / n
    r, g, b = [int(255 * c) for c in colorsys.hsv_to_rgb(h, 0.75, 0.95)]
    im = Image.new("RGB", (size, size), (r, g, b))
    d = ImageDraw.Draw(im)
    try:
        f = ImageFont.truetype("/System/Library/Fonts/SFNS.ttf", int(size * 0.45))
    except OSError:
        f = ImageFont.load_default()
    t = str(i + 1)
    box = d.textbbox((0, 0), t, font=f)
    d.text(((size - box[2] + box[0]) / 2 - box[0], (size - box[3] + box[1]) / 2 - box[1]), t, fill="white", font=f)
    return im

def main():
    n = int(sys.argv[1])
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT)
    for i in range(n):
        name = WORDS[i % len(WORDS)]
        app = os.path.join(OUT, f"Ref{i + 1:02d}.app")
        shutil.copytree(BASE, app)
        shutil.rmtree(os.path.join(app, "PlugIns"), ignore_errors=True)
        shutil.rmtree(os.path.join(app, "_CodeSignature"), ignore_errors=True)
        pl = os.path.join(app, "Info.plist")
        with open(pl, "rb") as f:
            info = plistlib.load(f)
        info["CFBundleIdentifier"] = f"dev.duoref.app{i + 1:02d}"
        info["CFBundleDisplayName"] = name
        info["CFBundleName"] = name
        info["CFBundleIcons"] = {"CFBundlePrimaryIcon": {"CFBundleIconFiles": ["Icon60"], "CFBundleIconName": ""}}
        info["CFBundleIcons"]["CFBundlePrimaryIcon"].pop("CFBundleIconName")
        with open(pl, "wb") as f:
            plistlib.dump(info, f)
        icon(i, n, 120).save(os.path.join(app, "Icon60@2x.png"))
        icon(i, n, 180).save(os.path.join(app, "Icon60@3x.png"))
        subprocess.check_call(["codesign", "-f", "-s", "-", app], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.check_call(["xcrun", "simctl", "install", UDID, app])
        print(i + 1, name, flush=True)

main()
