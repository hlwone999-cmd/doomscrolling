# Tidemark

*See how far you scrolled. Then let it ebb.*

An offline Android app that counts the short videos you watch in Instagram
Reels, TikTok and YouTube Shorts. It's free, with no account and no server, and it
has no internet permission. The plan is in `../replica/architecture.md`; the
brand (name checks, palette, icon, voice) is in `../replica/brand.md`.

Fonts: Inter (SIL Open Font License 1.1), bundled as
`app/src/main/res/font/inter_variable.ttf`, licence in
`app/src/main/assets/licenses/inter_OFL.txt` and shown in Settings > Open-source licences.

**Status:** every in-scope feature is written. Nothing has been compiled or run
yet; see `../replica/audit.md` and `../replica/build-log.md`.

## Build

None of these has been run yet. The project has never been compiled.

Requirements: JDK 17, Android SDK platform 35, and Gradle 8.9. The Gradle
wrapper (`gradlew` and `gradle-wrapper.jar`) is not in the project yet; only
`gradle/wrapper/gradle-wrapper.properties` is.

### Option A: Android Studio (on a PC with about 6-8 GB free)

1. Install Android Studio. It brings JDK 17, the SDK and Gradle.
2. File → Open → this `android/` folder. If Studio says the wrapper is missing, accept its offer to create it.
3. Unit tests: Gradle panel → `app > verification > testDebugUnitTest`, or right-click `app/src/test` → Run.
4. Instrumented tests (Room and Compose): with a phone or emulator connected, run `connectedDebugAndroidTest`.
5. APK: Build → Build APK(s). The file is `app/build/outputs/apk/debug/app-debug.apk`.

### Option B: GitHub Actions (no local Android install)

The workflow is in `../.github/workflows/main.yml`. It has never run.

1. Put this project in a GitHub repository of your own. Include `android/`, `replica/` and `.github/`. `.claude/` is ignored by `.gitignore` and never pushed; CI does not need it.
2. Push. The workflow runs on every push and pull request to `main`, or by hand from Actions → "Tidemark build" → Run workflow.
3. Its jobs:
   - **checks**: the four Python suites.
   - **build**: JDK 17 + Gradle 8.9, `testDebugUnitTest`, `assembleDebug`, and a check that the merged manifest has no INTERNET permission.
   - **instrumented**: only when started by hand with "instrumented" ticked. It runs `connectedDebugAndroidTest` on an API 34 emulator.
4. Download the `tidemark-debug-apk` artifact from the run page and install it:
   `adb install app-debug.apk` (or copy it to the phone and allow "install unknown apps").
5. A debug APK is for testing only. A Play release needs a signed release build (keystore stored as GitHub secrets) and a real `applicationId`. Neither is set up.

If the first CI build fails, read the Gradle error in the run log. The code
was written without a compiler, so compile errors on the first build are
expected (see `../replica/bugs.md`, C-1).

## First test on a phone

```
adb install app/build/outputs/apk/debug/app-debug.apk
```

Installs from outside the Play Store (adb, or an APK from CI) hit Android 13+
"restricted settings": before you can enable the accessibility service, open
App info → ⋮ → "Allow restricted settings".

1. Open the app → Agree → Turn on the accessibility service.
2. Open Instagram Reels. The counter should appear top right within about a second.
3. Scroll and compare the count with your own tally.

To find the real view ids for a detector rule, run this while the Reels,
Shorts or TikTok feed is on screen:

```
adb shell uiautomator dump /sdcard/ui.xml
adb pull /sdcard/ui.xml
```

Search the file for the full-screen `RecyclerView` or `ViewPager` and copy
its `resource-id` into `app/src/main/assets/detectors/v1.json`. Add the app's
versionCode range to `"verifiedVersions"` (for example `[{"min": 123, "max": 123}]`)
only after the test protocol in `architecture.md` passes on that version.

## Layout

```
app/src/main/java/app/tidemark/
  App.kt                 object graph (db, prefs, rules, writer, widgets), job scheduling, reset hour
  core/                  pure logic: day keys, item keys, severity, goals, stats, CSV, policies, schedule
  data/                  Room entities/DAOs/triggers, DataStore prefs, SessionWriter, Usage, DiagLog, DemoData
  detect/                CountingEngine (pure), SurfaceDetector, Rules
  service/               ReelService, overlays (pill, card, block), Workers, Notifications, BootReceiver, Widgets
  design/                DesignTokens.kt (generated), AppTheme, Components, Controls, Icons
  ui/                    AppRoot (navigation) and every screen: onboarding, Today, Stats, App detail,
                         History, Goals + editor, Settings, Counter, Reminders, Apps, Privacy,
                         Recovery, Diagnostics, Breathe, Design (developer)
app/src/main/assets/detectors/v1.json   bundled detection rules (all unverified)
app/src/test/                           JVM tests: engine, core, logic (36)
app/src/androidTest/                    Room + Compose tests (22)
```

Checks that run without Android (from the repo root):

```
python replica/tests/engine_sim.py
python replica/tests/logic_sim.py
python replica/tests/check_data_layer.py
python replica/tests/check_project.py
```

Design tokens: edit `replica/design/tokens.json`, then run
`python replica/design/gen.py`. Never edit `DesignTokens.kt` by hand.
