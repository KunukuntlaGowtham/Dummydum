# Checkbox Ticker

An Android app that ticks the checkboxes on the screen of whatever app is in front.

It runs as an accessibility service: it reads the screen, collects every node that is
checkable (checkboxes, and optionally switches and radio buttons), and clicks them one
at a time. If a checkbox cannot be clicked directly, the service taps its centre with a
gesture instead.

## Getting the APK

The APK is built by GitHub Actions, not committed here.

1. Open the repository's **Actions** tab.
2. Pick the **Build Checkbox Ticker APK** workflow, then the newest run (or press
   **Run workflow** to start one).
3. Download the **CheckboxTicker-apk** artifact and unzip it — `app-debug.apk` is inside.

It is a debug build, so the phone needs "install from unknown sources" allowed for
whatever app you install it from.

## Using it

1. Open **Checkbox Ticker** and press *1. Turn the service on in Accessibility*, then
   switch **Checkbox Ticker** on in the Accessibility list.
2. Choose what counts as a checkbox (empty boxes only, switches, radio buttons) and how
   fast to tap, then press *2. Save settings*.
3. Either tap the floating **TICK** button while the other app is open, or use
   *3. Tick in 5 seconds* and switch to the other app during the countdown.

Hold the floating button to turn **auto** mode on or off — in auto mode the service
ticks whatever appears each time the screen changes.

## Settings

| Setting | What it does |
| --- | --- |
| Only boxes that are empty | Skips boxes that are already ticked, so a run never unticks anything. |
| Also flip switches and toggles | Includes `Switch`, `SwitchCompat` and `ToggleButton` controls. |
| Also tick radio buttons | Includes `RadioButton` controls (off by default — they usually cancel each other out). |
| Show the floating TICK button | Draws the draggable bubble. It is an accessibility overlay, so no "draw over other apps" permission is needed. |
| Auto-tick whenever the screen changes | Runs by itself on each new screen, with a 1.5 s cooldown between runs. |
| Gap between taps | Milliseconds between one tick and the next (minimum 60 ms). |
| Most boxes in one run | Upper bound on how many boxes a single run touches. |

## Building locally

```
gradle assembleDebug          # Gradle 8.7, JDK 17, Android SDK 34
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.
