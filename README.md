# Pen Keyboard

A fast, pen-first Android keyboard for the Boox Go 7. Handwriting becomes text
(Danish and English), using the Onyx Pen SDK for low-latency ink and Google ML Kit
Digital Ink for on-device recognition.

Status: bare-bones first version. Text mode only.

## Install on the tablet

1. On the Go 7, open this repo's **Releases** page in the browser and download the newest `pen-keyboard-N.apk`.
2. Open it and allow installing from the browser when asked.
3. Open the **Pen Keyboard** app and follow its three buttons: turn the keyboard on, switch to it, download the models (needs internet once).
4. Write in the box at the bottom to try it.

Every push to `main` builds a new APK with GitHub Actions. All builds are signed with
the same checked-in debug key, so a new one installs over the old one.

After the first install, update from inside the app: the **Pen Keyboard** app shows
which build is installed and whether a newer one is on the Releases page. Tap
**Update to build N**; the first time, Android asks you to allow Pen Keyboard to
install apps (switch it on, go back, tap Update again).

Open pull requests also get a **test build** (a pre-release on the Releases page).
The app lists any that are newer than what is installed as **Try test build N**,
so a change can be tried on the tablet before it is merged. Closing the PR removes
its test build; the next build from `main` installs over it.

## How it works

- Write a word in the strip. After a short pause (0.7 s) it is recognized in
  Danish and English, and the best guess in the active language is typed, with a space.
- The row above the strip shows the guesses. Tap one to replace the word just typed.
  Guesses from the other language are marked `·en` or `·da`.
- Keys: `DA/EN` switches the active language, `⌨` switches to another keyboard,
  `space`, `⌫` (clears unrecognized ink first, otherwise deletes a character),
  `⏎` (shows **Send** in chat apps and sends; long-press for a new line).

## Pen shortcuts

Hold the pen's side button (on the Go 7 that makes the pen an eraser) and tap,
press, or draw on the keyboard's strip. The **Pen shortcuts** screen (button in the
app) sets what each does:

- Tap twice, tap three times, press and hold, swipe sideways: each gets an action
  (Send, switch DA/EN, open the quick pad, delete the last word, go back, open an app,
  or nothing). Go back needs **Pen Keyboard helper** switched on in Accessibility settings.
- Your own gestures: **Add a gesture**, draw the shape two or three times, pick an
  action. Draw it with the button held on an empty strip, or with the tip in the quick pad.
  A T for Telegram, a circle for anything you like.

- Page buttons: hold the Go 7's page Up or Down button and tap or draw with the tip
  on the keyboard. Each button has its own set of actions (tap twice, three times,
  press, swipe; recorded gestures are shared). Boox has to send the buttons as
  volume keys: set Customize Buttons → Volume for the apps you write in, and the
  smart button's long press → None. Pen Keyboard swallows those keys.
- Page buttons on their own: a press or double press of Up or Down runs an action
  even with the keyboard hidden (defaults: Up double press opens Pen Pad, Down
  double press goes back). Opening Pen Pad or an app from a hidden keyboard needs
  Pen Keyboard helper switched on, since Android blocks background app starts.

Over unrecognized ink, a button sweep still rubs out what it touches. On an empty
strip, a shape that matches nothing does nothing (it says so in the guess row),
so a sloppy gesture never deletes text by accident.

Shapes are matched with the $P point-cloud recognizer against your own samples, on
the device, so stroke order and direction don't matter. The pen test box in the app
shows what a button-held drawing was recorded as and how close it came to a gesture.

## Pen Pad (write over any app)

A third icon, **Pen Pad**, opens a see-through writing layer over whatever app is
open. Map a Boox smart button (long press) to it. Tap a text field in the app,
press the button, and write anywhere on the screen: each word goes into that
field. Button-held taps and shapes work as on the keyboard. **Send** presses the
field's send action, **Done** closes the pad.

It needs **Pen Keyboard helper** switched on (Accessibility settings): the helper
remembers the last text field you tapped in another app and writes into it.

## Pen Launcher (quick pad)

The app also installs a second icon, **Pen Launcher**. Write an app name ("Tele")
and lift the pen: if exactly one app's name starts with it, that app opens;
otherwise up to four matches are shown to tap.
To open it with a hardware button: Boox Settings → button settings → map a button
to open the **Pen Launcher** app.

## Code

- `InkView.kt`: the writing strip. Onyx `TouchHelper` raw drawing on a Boox,
  plain touch elsewhere. The TouchHelper call order comes from
  [Miroir-IME](https://github.com/nctahiti/Miroir-IME) (Apache 2.0).
- `Recognizer.kt`: ML Kit Digital Ink, one recognizer per language.
- `PenIME.kt`: the keyboard service: commit timing, spacing, candidates, keys.
- `PenApp.kt`: hidden-API exemptions the Onyx SDK needs (hence `targetSdk 29`).
- `SetupActivity.kt`: the setup screen.
- `LauncherActivity.kt`, `AppMatcher.kt`: Pen Launcher (handwriting → open app).
- `ButtonGestures.kt`: groups button-held contacts into taps, a press, or a drawn shape.
- `Shortcuts.kt`: shortcut settings, actions, and the $P shape matcher.
- `ShortcutsActivity.kt`, `RecordGestureActivity.kt`: the Pen shortcuts screens.
- `PenPadActivity.kt`: Pen Pad. `PenButtonProbe.kt`: the accessibility helper (text
  field access for Pen Pad, Back, key probe).
