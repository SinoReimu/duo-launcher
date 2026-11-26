# duoref

Measures the iPhone Duo Home Screen in the Xcode simulator, as the reference for Duo Launcher's
layout and motion. Needs Xcode 27.1 (iOS 27.1 runtime with the iPhone Duo device type), `xcodegen`,
`ffmpeg`, and Python with Pillow and numpy.

## Setup
```bash
cd tools/duoref
mkdir -p run
xcrun simctl create DuoRef "iPhone Duo" com.apple.CoreSimulator.SimRuntime.iOS-27-1 > run/udid
xcrun simctl boot "$(cat run/udid)"
xcodegen generate
xcodebuild build-for-testing -project DuoRef.xcodeproj -scheme Driver -destination "id=$(cat run/udid)" -derivedDataPath build
python3 mkapps.py 62      # 62 dummy apps, each icon its own hue (trackable in recordings)
python3 seticons.py       # full pages, a folder, 4 icons in the dock; restarts SpringBoard
swiftc -O dhbutton.swift -o run/dhbutton
python3 duoref.py serve & # the XCUITest driver; polls run/cmd.json
```
The fold state has no command-line switch; `run/dhbutton` presses Device Hub's toolbar buttons
through the accessibility API without moving the mouse (Device Hub must be open with the simulator
selected: `run/dhbutton --select DuoRef`). Buttons: `Closed`, `Partially Open`, `Open`,
`Rotate Right`, `Home`.

## Use
- `python3 duoref.py dump > run/dump_land.txt; python3 parse.py land` lists the frames of the
  visible icons, the dock, the page dots and the search button (`land`, `port` or `cover`).
- `duolib.py` is the library for motion: `Recorder` records a display, `touch(mode, points)` replays
  a touch path with exact timing on the cover (display 1) or inner (display 3) screen,
  `rec_frames` decodes the recording upright at one pixel per point, `icon_box` tracks a dummy
  app's icon by hue, `sheet` writes a contact sheet.
- Restarting SpringBoard (`seticons.py`) ends the driver; start `duoref.py serve` again.

## What was measured (iOS 27.1 simulator, points)
| | Open, landscape 951×669 | Open, portrait 669×951 | Closed 466×678 |
|---|---|---|---|
| Icon / label | 60 / 12 medium | 60 / 12 | 60 / 12 |
| Column pitch | 88 | 106 | 88 |
| Row pitch | 98 | 118 | 98.8 |
| First icon top | 54 | 82 | 54 |
| First icon left | 73 (second page 470) | 145.7 | 27 |
| Dock | capsule 72 wide, 48pt icons, pitch 64, 12 from the right edge | capsule 92 high, 60pt icons, pitch 76, 8 from the bottom | as landscape |
| Search button | 48, centre (903, 621) | 48, right of the dock, 24 from the edge | 48, centre (418, 630) |
| Page dots | 8pt, pitch 17.5, in the gutter between pages | above the dock | under the grid |

Motion:
- Landscape shows the current page on the right and the previous one on the left; a swipe moves
  one page. Slots are 397 apart; the next page waits with its first icon at x = 966 (15 past the
  edge), so it travels 1.25× the finger and slides in from behind the dock rail.
- Page settle fits a critically damped spring with a natural frequency of about 20 rad/s
  (stiffness 400 for unit mass), starting from the finger's velocity.
- A folder opens in about 0.35 s as a 3×3 glass panel (290×345, column pitch 88, row pitch 103)
  centred on its page, with the Home Screen blurred behind it.
- Edit mode replaces the status items at the top of the rail with two round glass buttons, hides
  the search button, puts the page dots in a glass pill and shows light glass "−" badges.
- Search: the Home Screen blurs and dims and the dock slides off to the right.
- Unfolding is a plain fade-in of the inner screen (about 0.4 s) with the layout already final.
