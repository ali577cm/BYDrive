# Sudoku Overlay

Floating bar over a Sudoku app: reads the board from the screen, solves it, shows the answers (Solve) or types them in (Fill).
Everything runs on the phone. See the in-app setup screen for the permissions it needs.

## How it reads the board
Screenshot (MediaProjection) -> grid found from its long lines -> each digit cut out -> identical-looking digits grouped
and labelled by matching them to the on-screen 1-9 buttons (same font), with ML Kit OCR as a backup -> backtracking solver.
Core logic is plain Kotlin in `core/` with unit tests (`gradle :app:testDebugUnitTest`).

## Updates
Every push to `main` that touches `sudoku-overlay/` builds a signed APK (`.github/workflows/sudoku.yml`) and publishes it as a
GitHub Release tagged `sudoku-v<N>` with the file `SudokuOverlay.apk`. Those releases are published with `--latest=false`
because the repository's "latest" release belongs to the BYDrive app, whose updater follows it.

The app checks for a newer `sudoku-v<N>` release when it opens or the overlay starts (at most every 6 hours), downloads it
over https from GitHub, and hands it to Android's installer. Android only accepts it if it is signed with the same key, and
may ask for one tap to confirm. The workflow uses the same `KEYSTORE_B64` / `KEYSTORE_PASSWORD` secrets as BYDrive.

A build made without that key (for example a local `assembleDebug`) is debug-signed and cannot be updated over a release-signed
install: uninstall it once and install `SudokuOverlay.apk` from the first Release.

## Problem reports
When a Solve or Fill fails (not when you cancel by touching the screen) a quiet notification appears; tapping it opens a
new-issue page on this repository with the failure, build, device and the tail of the log already filled in. No token or
password is stored in the app: you press Submit. Switch it off with "Offer to report failures". The same page opens from
**Report a problem** on the setup screen.

## Debug log
The app keeps a log and its two latest screenshots; **Copy log text** / **Save screenshots to Photos** on the setup screen.
