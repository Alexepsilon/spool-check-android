# Spool Check — Native Android

QC scanner for verifying pipe spool deliveries against transport lists. Camera OCR matches the printed tag on each spool against an imported master list (XLSX or photo of a printed list), then ticks off rows as they're verified on site.

Built for industrial yard use: works fully offline, EN + NL UI, on-device ML Kit text recognition (no cloud).

## What it does

- **Import a transport list** — pick an `.xlsx`, or take photo(s) of a printed list. Drawing No., Spool, Iso No., RAL, Ch.clean, Project, etc. are auto-detected from the header row.
- **Scan tags** — point the camera at a tag. Drawing number and spool letter fill in live in a credit-card-style "READING" card; auto-ticks after 2 stable frames.
- **Status board** — verified rows go yellow (mirrors the marker-pen convention on paper lists). Filter All / Verified / Missing with counts. Long-press a delivery on the home screen to delete; row long-press for hard delete.
- **Uncharted** — tags that didn't match the list (wrong project, missing from manifest, OCR misread) get parked here with a disposition picker.
- **Excel export** — two-sheet xlsx (Master list + Uncharted) for client deliverables.
- **Bilingual** — full English + Dutch translations via Per-App Language Preferences. Toggle in Settings → persists across reboots.

## Build & install

Requires:
- Android Studio Hedgehog+ (or just the Android SDK + JDK 17)
- An Android device on USB debugging (or an emulator with API 24+)

```bash
# Clone
git clone https://github.com/Alexepsilon/spool-check-android.git
cd spool-check-android

# Point Gradle at your Android SDK
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# Build a debug APK
./gradlew assembleDebug    # macOS / Linux
gradlew.bat assembleDebug  # Windows

# Install on a connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If you only need to sideload, grab the latest `app-debug.apk` from the Releases page.

For non-technical users: see [INSTALL.md](INSTALL.md) for a step-by-step phone install guide in English and Nederlands.

## Tag formats currently supported

Real-world layouts observed in production:

- 7-part: `322-FLA-1001-SS-100-P-2`, `322-GAS-0206-SS-80-N-1.2`
- 6-part: `321-OIL-0108-SS-15-T` (XYCLE / MOH yellow tags)
- Cell-layout tags (XYCLE peach, with each field in its own black-bordered cell)

Anchors recognised on tags (case-insensitive, both languages):

- Drawing: `Tek nr`, `Tekening`, `Drawing`, `Drg`, `DWG`
- Spool: `Spool`, `Spoolnr`, `Stuk`, `Piece`, `S/N`
- Sheet: `Sheet`, `Blad`, `Page`
- Paint: `Verfsysteem`, `Paint spec`
- RAL: `RAL`
- Scope: `Scope nr`, `Code`, `Ch.clean`

## Architecture

Single-module Android app, Kotlin + Jetpack Compose + Material 3.

- **`core/`** — pure-Kotlin OCR and matcher logic. `CodeMatcher` does anchor extraction, regex fallback, fuzzy character substitution (B↔8, O↔0, etc.), and intersects OCR text with the master list's valid spool letters. `TextReconstruction` re-flows ML Kit's `Text` blocks into spatial logical rows so cell-layout tags read correctly.
- **`data/`** — Room (SQLite) entities + DAOs. Master list / scans / uncharted / deliveries.
- **`ui/screens/`** — Compose screens. Home, Import, StatusBoard, Scanner (CameraX + ML Kit), Uncharted, Settings.
- **`res/values-nl/`** — Dutch translations.

OCR uses Google ML Kit's on-device latin text recognizer — no network required after install.

## License

Internal use. Not currently licensed for redistribution.
