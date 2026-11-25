# Duo Launcher

A Galaxy Z Fold 8 home screen modeled on the iPhone Duo.

## Layout
| Screen | Pages shown | Dock |
|---|---|---|
| Inner screen, landscape | 2 pages side by side; a swipe moves **one** page (the right page slides to the left) | Vertical, right edge |
| Inner screen, portrait | 1 page, same 4×6 layout | Bottom |
| Cover screen | 1 page, 4×6 | Vertical, right edge |

Every page is the iPhone 4×6 grid, so the layout is identical in all three modes. Folding, unfolding and rotating keep the current page.

## Gestures and motion
- Swipe down anywhere opens the **Finder** (search). It follows your finger and matches pinyin and initials. You can switch this gesture to the notification panel in Settings; swipe up always opens the Finder. Swipe down in the Finder (with the list at the top) to close it.
- App open and close use the system animation; the Home icons only fade out and back in quickly. Settings has a switch for the launcher-drawn version (the icon grows into a card in its own colour and shrinks back into the icon), which is off by default. One UI only lets the system Home animate app windows, which is why that version is drawn by the launcher.
- All motion uses springs, so any animation can be interrupted and redirected mid-flight:
  - the unlock animation zooms in and the icons converge toward their places
  - fold and rotate transitions refocus the screen, and the dock slides in from its new edge

## Editing
- Long-press an icon for app shortcuts plus Edit Home Screen, Remove from Home Screen, Hide App, App Info and Uninstall. Moving the finger while the menu is open does not start a drag.
- To move icons, enter jiggle mode (Edit Home Screen, or long-press empty space), then hold an icon briefly and drag it.
- While dragging, tap other icons with a second finger to pick them up too (multi-select).
- Drop an icon on another to make a folder (auto-named from the app category). Drag into, out of, and within folders.
- Hold an icon at the screen edge to switch pages; holding it past the last page adds a new page. Empty pages are removed after the drop.
- Widgets: tap **+** in jiggle mode. Long-press a widget to resize it (2×2 / 4×2 / 4×4) or remove it.
- Apps removed from the Home Screen stay searchable in the Finder. Hidden apps are restored from Settings.

## Build
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew assembleRelease      # or assembleDebug
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd role add-role-holder android.app.role.HOME com.duo.launcher 0
adb shell appwidget grantbind --package com.duo.launcher --user 0   # optional: skip the widget permission prompt
```
To switch back to One UI Home:
```bash
adb shell cmd role add-role-holder android.app.role.HOME com.sec.android.app.launcher 0
```
