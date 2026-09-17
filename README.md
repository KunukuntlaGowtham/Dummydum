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

The floating button is **START / STOP**. START keeps going by itself — tick a box, clear the
pop-up, scroll on about 2 cm, tick the next — until you press STOP. Holding the button saves
a **scan report** instead; see below.

## Settings

| Setting | What it does |
| --- | --- |
| Only boxes that are empty | Skips boxes that are already ticked, so a run never unticks anything. |
| Also flip switches and toggles | Includes `Switch`, `SwitchCompat` and `ToggleButton` controls. |
| Also tick radio buttons | Includes `RadioButton` controls (off by default — they usually cancel each other out). |
| Also web page and custom boxes | Catches controls that never report themselves as checkable (see below). |
| Show the floating TICK button | Draws the draggable bubble. It is an accessibility overlay, so no "draw over other apps" permission is needed. |
| Auto-tick whenever the screen changes | Runs by itself on each new screen, with a 1.5 s cooldown between runs. |
| Gap between taps | Milliseconds between one tick and the next (minimum 60 ms). |
| Most boxes in one run | Upper bound on how many boxes a single run touches. |

## Web pages inside an app

A WebView is not a black box: while an accessibility service is running, Chromium mirrors
the DOM into virtual nodes, so an `<input type="checkbox">` arrives as
`android.widget.CheckBox` with a working `ACTION_CLICK`, and so does anything carrying
`role="checkbox"`. Those are ticked the same way as native ones.

What that misses is a checkbox built from a styled `<div>` with no ARIA role. With
**Also web page and custom boxes** on, a control that never reports itself as checkable
is still treated as one when

* its view id, text or description mentions a checkbox, or
* it is a small empty square, tappable, and sits inside a WebView.

Because a web node can accept a click and ignore it, every tick on a control that reports
its state is verified afterwards: if the state did not change, the spot is tapped with a
gesture instead.

### When a screen still will not tick

Hold the floating button on that screen, then open Checkbox Ticker and press
**Show last scan report**. It lists every window, how many nodes each one publishes, how
many are checkable, how many sit inside a WebView, and a sample of the likely controls —
which says plainly whether the content is reachable at all. Share it from that dialog.

Three shapes of answer:

* *nodes in the hundreds, checkable=0, webviews=1* — the page is exposed but its boxes are
  custom-drawn. The loose matching should catch them; if it does not, the sample lines say
  what they actually look like.
* *no root, or nodes=1* — the app publishes nothing for that window. No node-tree approach
  can work; it needs pixel matching.
* *the window is not listed at all* — the content is in a window the service was not
  reading. Every window is now scanned, not just the focused one, which is the fix for this.

## Apps that publish nothing

Some screens expose no accessibility tree at all: a UI painted on a canvas (Flutter web's
default renderer, WebGL, Unity), or a WebView an app has locked down. No amount of node
walking reaches those, so there is a second path that does not use the tree.

Press **Allow screen reading (works in any app)** and grant the screen-capture prompt. When
a run finds nothing in the tree, Checkbox Ticker grabs one frame instead and looks for what
an empty checkbox looks like: a small, roughly square outline with a flat, empty middle.
Each one it finds is ringed in green for a moment and tapped by coordinate.

A ticked box is filled in, so it fails the "empty middle" test and is left alone — which is
how a second run does not undo the first.

Notes:

* The frame is measured on the phone and dropped. Nothing is stored and nothing is sent.
* Screen reading is used only on a run you ask for (the floating button, or *Tick in
  5 seconds*), never by auto mode, which would tap wildly on every screen change.
* Android shows a screen-capture notice while it is on; the permission ends when you
  reboot or swipe the notification's service away.

## Pop-ups between ticks

Some apps answer every tick with a pop-up that has to be dismissed before the next box can
be ticked. **After each tick → "A pop-up appears - tap its colour"** handles that: it waits
for the pop-up, finds the biggest patch of a colour you choose, taps it, and only then goes
on to the next box.

| Setting | Default | What it does |
| --- | --- | --- |
| Colour of the pop-up button | `#663398` | The colour to look for. Any hex value. |
| Colour tolerance | 60 | How far each of red, green and blue may differ from it. Raise it if the button is shaded or has a gradient. |
| Wait for the pop-up | see **Speed** | How long the pop-up gets to appear. |
| Ignore the top % of the screen | 20 | That slice is never searched, so a coloured status bar, toolbar or header is never mistaken for the button. |

This needs screen reading to be on. With it on, the screen-reading path also changes shape:
instead of finding every box in one picture and tapping through the list, it ticks one box,
deals with the pop-up, then looks at the screen again for the next one — because a pop-up
can move everything underneath it, and a list captured beforehand would go stale. A ticked
box no longer looks empty, so it drops out of the next look by itself.

## Running until you stop it

START runs a cycle: tick one box → wait for the pop-up and tap its colour → scroll on →
look again. Running out of boxes on screen is not the end: it scrolls and keeps looking, so
a long list works through on its own.

It stops by itself at the end of the page. A scroll that leaves the screen exactly as it
was means nothing moved, and after two of those in a row the run ends with *Reached the end
of the page*. Press STOP (the same floating button, red while it runs) to end it sooner;
*Stop now* in the app does the same.

**Full speed** (on by default) means no round waits on the clock at all. The pop-up is
tapped the instant it appears, the next box is ticked the moment the pop-up has gone, and a
scroll is followed the moment the page stops moving — checked every 60 ms against the
screen itself. A fast phone runs fast and a slow one still keeps up. *Give up waiting after*
(2.5 s) is the one limit: a pop-up that never appears is not waited for forever.

The green rings are not drawn during a full-speed run, because they are on the screen the
run is photographing.

With full speed off, the three fixed waits are used instead:

| Setting | Default | What it waits for |
| --- | --- | --- |
| After ticking a box | 300 ms | The pop-up to appear. |
| After clearing the pop-up | 300 ms | The pop-up to go away. |
| After scrolling | 300 ms | The page to settle. |
| Scroll each time | 20 mm | How far the swipe moves the page - 20 mm is about 2 cm. |

So a box takes roughly a second by default. Lower the three waits for a faster run; raise
them if the app is slow and a tick lands before the screen has caught up. They are plain
milliseconds, so 1000 is one second and 2000 is two.

## Noting down what was ticked

**Note down the words under each box** (on by default) writes a line for every box just
before it is ticked — before, because the pop-up covers the label afterwards.

The label is taken from the app's own text when the app publishes any. When it publishes
nothing, the strip of screen just under the box is read out of the picture the scan already
took, using on-device text recognition (ML Kit, model bundled in the APK). Either way the
reading happens on the phone; nothing is uploaded.

**Show the notes** in the app lists them, shares them, or clears them:

```
1. [11:52:03] Accept the terms of service
2. [11:52:05] Subscribe to the newsletter
```

The strip searched is the width of about six boxes and four boxes deep, centred under the
box. A label sitting to the side rather than underneath is not picked up.

## Building locally

```
gradle assembleDebug          # Gradle 8.7, JDK 17, Android SDK 34
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.
