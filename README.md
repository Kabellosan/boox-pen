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

## How it works

- Write a word in the strip. After a short pause (0.7 s) it is recognized in
  Danish and English, and the best guess in the active language is typed, with a space.
- The row above the strip shows the guesses. Tap one to replace the word just typed.
  Guesses from the other language are marked `·en` or `·da`.
- Keys: `DA/EN` switches the active language, `⌨` switches to another keyboard,
  `space`, `⌫` (clears unrecognized ink first, otherwise deletes a character),
  `⏎` (shows **Send** in chat apps and sends; long-press for a new line).

## Code

- `InkView.kt`: the writing strip. Onyx `TouchHelper` raw drawing on a Boox,
  plain touch elsewhere. The TouchHelper call order comes from
  [Miroir-IME](https://github.com/nctahiti/Miroir-IME) (Apache 2.0).
- `Recognizer.kt`: ML Kit Digital Ink, one recognizer per language.
- `PenIME.kt`: the keyboard service: commit timing, spacing, candidates, keys.
- `PenApp.kt`: hidden-API exemptions the Onyx SDK needs (hence `targetSdk 29`).
- `SetupActivity.kt`: the launcher screen.
