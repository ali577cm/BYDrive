# Sudoku Overlay

Floating bar that sits over a Sudoku app, reads the board from the screen, solves it and either shows the missing
digits in green (**Solve**) or taps them in for you (**Fill**, batched 5 cells per gesture for speed).

Setup: install the APK, open it, 1) allow drawing over other apps, 2) turn on "Sudoku Overlay" under Accessibility
(needed for Fill only; on Android 13+ sideloaded apps may need "Allow restricted settings" in the app's info page first),
3) tap Start overlay and accept the screen-capture prompt. Keep pencil mode off in the game.

How it works: MediaProjection screenshot -> grid found from its long lines -> each digit cleaned and read with
on-device ML Kit OCR -> backtracking solver -> overlay / accessibility taps. The 1-9 buttons are located by OCR.
Core logic (`core/`) is plain Kotlin with unit tests (`gradle :app:testDebugUnitTest`).

Build: `gradle :app:assembleDebug` (needs the Android SDK).
