# Duo Launcher

A Galaxy Z Fold 8 home screen modeled on the iPhone Duo.

## Layout
| Screen | Pages shown | Dock |
|---|---|---|
| Inner screen, landscape | 2 pages side by side; a swipe moves **one** page (the right page slides to the left) | Vertical, in a rail on the right edge |
| Inner screen, portrait | 1 page, same 4×6 layout | Bottom |
| Cover screen | 1 page, 4×6 | Vertical, in a rail on the right edge |

Every page is the iPhone 4×6 grid, so the layout is identical in all three modes. Folding, unfolding and rotating keep the current page.

The proportions follow the iPhone Duo Home Screen as measured in the Xcode simulator (see `tools/duoref`), re-balanced for the Fold 8's screen shapes rather than copied point for point:
- 60pt icons with 12pt labels; in the rail modes the left margin, the gutter between the two pages and the gap before the dock are the same width.
- The dock is a glass capsule that hugs its icons (48pt icons in the rail) and stretches on a spring when an icon is added or dragged out.
- A round search button sits under the dock (right of it in portrait) and opens the Finder.
- In landscape the page after the visible pair waits past the screen edge and slides in whole from behind the dock, so nothing peeks out from under the dock at rest.
- The page before the visible pair likewise waits fully off the left edge, so none of its labels peek in.
- Rail glass is frosted: pages passing behind the dock capsule or a round button are cut out of the workspace and drawn back blurred inside the glass (the blur covers only the glass itself).
- Page dots are always visible; in landscape the two visible pages are both lit.

## Gestures and motion
- Swipe down anywhere, or tap the search button, opens the **Finder** (search). While it is open the dock slides off toward its edge. It follows your finger and matches pinyin and initials. You can switch this gesture to the notification panel in Settings; swipe up always opens the Finder. Swipe down in the Finder (with the list at the top) to close it.
- A swipe that starts in the strip along the bottom edge is the system's and Home does not answer it with a gesture of its own: up goes Home (from another page that brings page 1 back), up and hold opens the recent apps. Swipe up from anywhere above that strip opens the Finder.
- App open and close get a small gesture of the icon's own, drawn by the launcher (One UI ignores every `ActivityOptions` animation from a third-party Home and always plays its own window transition, which the launcher leaves alone):
  - Opening: a copy of the icon swells to 1.7 sizes, drifting toward the middle of the screen (a short hop for an icon near it, up to 2.4 icon widths for one in a corner), fading from the moment it leaves so that it is only a trace by the time it is over its neighbours. Home itself draws back by 2.5%, sharp and undimmed. Both are critically damped springs: the copy is 90% of the way in about 0.09 s out and 0.14 s back.
  - Interrupted (Home, Back or a swipe before the app has covered Home): the copy turns round where it is and lands on the icon.
  - Coming back: the same in reverse, a medium-sized icon fading in from the direction of the middle and landing on its place, while Home opens out to full size again. It does not try to be the app's window and never travels to the middle of the screen: window-sized cards that took over from One UI's shrinking window were tried (see `LaunchCard`) and dropped, because the launcher cannot know where that window is when the finger lifts, and an icon far from the middle crossing half the screen looked wrong.
  - The icon that plays it is that of the app that was really in front, not the one last opened from Home: after switching apps in Recents or opening one from a notification it goes to that app's icon, turning to its page first if needed; a folder holding the app stands in for it; an app with no icon on Home fades at the centre.
  - Coming back from the Recents overview (the app was left there or swiped away) plays nothing.
  - This needs usage access (Settings → Return animation); without it only the launcher's own last launch is known.
- Pages settle on a critically damped spring (about 0.3 s, no overshoot) that carries the finger's velocity over, and rubber-band at the first and last page with the iOS curve.
- All motion uses springs, so any animation can be interrupted and redirected mid-flight:
  - the unlock animation zooms in and the icons converge toward their places
  - fold and rotate transitions refocus the screen, and the dock slides in from its new edge

## Editing
- Long-press an icon for app shortcuts plus Edit Home Screen, Remove from Home Screen, Hide App, App Info and Uninstall, in a light glass menu with leading icons. Moving the finger while the menu is open does not start a drag.
- To move icons, enter jiggle mode (Edit Home Screen, or long-press empty space), then hold an icon briefly and drag it. Done, add widget (+) and settings are round glass buttons at the top of the rail (in the status-bar strip in portrait).
- While dragging, tap other icons with a second finger to pick them up too (multi-select).
- Drop an icon on another to make a folder (auto-named from the app category). Drag into, out of, and within folders.
- Hold an icon at the screen edge to switch pages; holding it past the last page adds a new page. Empty pages are removed after the drop.
- Folders open as a 3×3 glass panel over the page that holds them; more than nine apps scroll.
- Widgets: tap **+** in jiggle mode; the list has a search field (app or widget name, pinyin and initials too). Long-press a widget to resize it (2×2 / 4×2 / 4×4) or remove it.
- Apps removed from the Home Screen stay searchable in the Finder. Hidden apps are restored from Settings.

## Build
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew assembleRelease      # or assembleDebug
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd role add-role-holder android.app.role.HOME com.duo.launcher 0
adb shell appwidget grantbind --package com.duo.launcher --user 0   # optional: skip the widget permission prompt
```

To try a build next to an installed launcher without touching its layout (or when the installed one was signed with another key), build a side-by-side copy with its own package name and data:
```bash
./gradlew assembleDebug -PidSuffix=.dev
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.duo.launcher.dev/com.duo.launcher.MainActivity
```
## Reference measurements
`tools/duoref` drives the iPhone Duo simulator (Xcode 27.1 / Device Hub) to measure the Home Screen this launcher is modelled on: an XCUITest driver that reads SpringBoard's element frames and replays exact touch paths on either display, plus scripts that record the screen and track icons frame by frame. See `tools/duoref/README.md`.

To switch back to One UI Home:
```bash
adb shell cmd role add-role-holder android.app.role.HOME com.sec.android.app.launcher 0
```
