# doomscrolling

## Tidemark (project folder: BrainPal-Alternative)

*See how far you scrolled. Then let it ebb.*

Tidemark is a free, offline Android app that counts the short videos you
watch in Instagram Reels, TikTok and YouTube Shorts. It has no account, no
server, no payments and no internet permission. It was rebuilt from public
information about a similar app (clean room), with its own name, icon, colours
and copy. The folder name is historical only; "BrainPal" appears nowhere in
the app.

**Status:** every in-scope feature is written. Nothing has been compiled or
run on a phone yet. Detection has not been verified on any app.

| where | what |
| --- | --- |
| `android/` | the app (Kotlin, Jetpack Compose, Room). Build steps are in `android/README.md` |
| `.github/workflows/main.yml` | CI: checks, unit tests, debug APK, optional emulator tests (never run yet) |
| `replica/recon.md` | what was studied, screens and flows |
| `replica/architecture.md` | how it works, offline by design |
| `replica/schema.sql` | the database |
| `replica/design/` | design tokens and component specs |
| `replica/brand.md`, `replica/brand/` | name checks, palette, icon, Inter font and licence, voice |
| `replica/features.csv`, `replica/audit.md` | every feature and its status |
| `replica/test-plan.md`, `replica/bugs.md` | what was tested, what is still open |
| `replica/build-log.md` | build history and deviations |
| `replica/final-report.md` | the final audit report |
| `replica/tests/` | checks that run without Android: `python replica/tests/<name>.py` |

Third-party: Inter typeface © The Inter Project Authors, SIL Open Font
License 1.1 (`android/app/src/main/assets/licenses/inter_OFL.txt`).
