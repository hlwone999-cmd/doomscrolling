# Build log

Code is in `android/`. **Nothing has been compiled or run yet.** This PC has
only Java 8 and no Android SDK. You chose to write code without compiling it,
and the first build happens in Android Studio. Every row below is therefore
"written", not "done", until it builds and passes the test protocol in
`architecture.md` on a real phone. No `replica/clone-screens/` screenshots
exist yet for the same reason.

| ID | date | status | what is missing | harder than expected |
| --- | --- | --- | --- | --- |
| shell | 2026-11-21 | written | launcher icon; Inter font files (falls back to system font) | Room can't declare the partial "one open session" index without breaking migration validation, so it became a nullable `is_open` column with a plain unique index |
| design | 2026-11-21 | written | design screen strings are developer-only English | tokens generated straight into the app by `replica/design/gen.py`; `AppTheme.kt` moved into the app |
| S02 | 2026-11-21 | written | "Read again" path is reachable from Settings and the Today banner | Play's disclosure must name the on-device hash of the video description |
| S03 | 2026-11-21 | written (slice) | OEM autostart tips (milestone 3) | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` avoided (Play restricts it); the system exemption list opens instead |
| S05 | 2026-11-21 | written (slice) | goal progress (m5); minutes count only after a session closes; no live open-session minutes | |
| S06 | 2026-11-21 | written | tap → S07 card (later); size/opacity settings (m5) | plain Views on a `TYPE_ACCESSIBILITY_OVERLAY` window, not Compose, to avoid lifecycle wiring in a service |
| S08, S11 | 2026-11-21 | stub | routes exist, screens arrive in m4/m5 | |
| S14 | 2026-11-21 | written (slice) | theme, permissions, disclosure and design links only | |
| engine | 2026-11-21 | written + 17 unit tests | tests not run (no toolchain here) | rule 4 (a count needs a scroll or a fresh entry) was added so that a key change on the same video (likes, rotation, comments sheet) can never recount |
| detection | 2026-11-21 | written, **unverified** | real view ids for Instagram/YouTube; TikTok has no ids, generic fallback only | the generic fallback must reject the main feed (posts don't fill the pager) and Stories (horizontal); both are heuristics until tested |
| restart | 2026-11-21 | written (slice) | "counting stopped" notification and HealthCheckWorker (m3) | Android 8-10 SQLite has no UPSERT, so triggers use INSERT OR IGNORE + UPDATE |

## Deviations from architecture.md

- **No Hilt.** A small hand-written graph (`App.kt`) is used instead: fewer
  build plugins while nothing can be compiled here. Revisit when the app grows.
- **Session uniqueness** uses an `is_open` column (see the shell row). `schema.sql` is updated to match.
- **Package id** is the placeholder `app.tidemark` until `/replica-brand`.

## Before calling milestone 1 done

1. Open `android/` in Android Studio (it creates the Gradle wrapper and SDK), build, and run `./gradlew test`.
2. Install on your phone, accept S02, and enable the service in S03.
3. Run `adb shell uiautomator dump` on Instagram Reels, YouTube Shorts and TikTok. Put the real pager ids into `assets/detectors/v1.json` and set `verified` only after the test protocol passes.
4. Run the milestone 1 exit test: 50 Reels on 2 phones, within ±2, zero counts in the feed and DMs, then the reboot test. Record it in `replica/accuracy.md`.

## Round 2: full feature set (2026-11-21)

Still **not compiled and not run** (no Android SDK on this PC, by instruction).
Every row below is "written". Checks that could run here passed: 162/162
across `replica/tests/*.py`.

| ID | status | what is missing | harder than expected |
| --- | --- | --- | --- |
| S03 | written | nothing in scope | phone-maker steps written for 5 families; text only, no deep links (they differ per firmware version) |
| S04 | written | nothing in scope | TikTok has two package names; both seeded, both switchable |
| S05 | written | nothing in scope | live minutes need the open session's active time, so the engine now sends it every 5 s and Today adds it (C-6) |
| S06/S07 | written | nothing in scope | counter card and block screen are separate accessibility overlays; touch behaviour must be checked on a phone (C-10) |
| S08 | written | nothing in scope | own Canvas bar chart instead of Vico: one dependency fewer while nothing can compile |
| S09, S10 | written | nothing in scope | |
| S11 + editor | written | nothing in scope | Room can't declare "one goal per scope" or "one notice per day but many snoozes"; solved with a transaction in `GoalDao.save` and a `seq` column |
| S12 | written | Back key reaches the app below (overlay not focusable) | the block must stop counting underneath: the service ignores tracked-app events while it is shown |
| S14 | written | nothing in scope | |
| S15 | written | import restores daily totals only | deleting while a session is open: the writer closes the row and re-creates one on the next count, so counting continues |
| S16 | written | nothing in scope | |
| S18 widget | written | no preview image | classic `AppWidgetProvider` + RemoteViews instead of Glance (no extra library) |
| workers | written | timing is inexact (WorkManager) | health check, prune, day rollover, daily summary, weekly report |
| diagnostics | written | nothing in scope | rules gained `verifiedVersions` (empty) so the app can say "not yet verified" per installed version |
| breathe | written | nothing in scope | |

### Deviations (round 2)

- **Charts.** A Canvas bar chart instead of the Vico library.
- **Widget.** RemoteViews instead of Glance.
- **Goal uniqueness.** Enforced by a DAO transaction. `goal_event` uses `seq` plus a plain unique index. `schema.sql` is updated.
- **Schema version.** Still 1. No build has ever been installed, so tables were extended in place. From the first release on, every change needs a Migration.
- **Facebook and Snapchat.** Seeded but switched off by default (could-haves with unverified generic detection).

### Before calling anything done

The four steps at the top of this file still apply: build, run `test` and
`connectedAndroidTest`, read the real view ids, and run the device test
protocol. `features.csv` stays at `partial` until then.

## Round 3: branding as Tidemark (2026-11-21)

Still **not compiled and not run**. No change to core behaviour. Details in `brand.md`.

| item | status | notes |
| --- | --- | --- |
| name | Tidemark | "BrainPal Alternative" stays the folder name only. Screening by web search; official trademark searches still to run (possible conflict: an enterprise "Tidemark" finance product) |
| launcher icon | written | adaptive icon (background, foreground, monochrome) in `mipmap-anydpi-v26`; minSdk 26 means no PNG fallbacks are needed. Rendered previews on light, dark and themed launchers look right; real launchers not yet checked |
| status-bar icon | written | same motif, one colour |
| palette | done | sea-teal accent replaces the placeholder blue; brand deep/foam/mark; 81 contrast pairs, 0 AA failures |
| Inter | bundled | `res/font/inter_variable.ttf` (variable: wght 100-900, opsz 14-32); `AppFonts.Inter` in every text style; overlays use it via `fontVariationSettings`. Widget keeps the system font |
| licence | shipped | `assets/licenses/inter_OFL.txt` (SIL OFL 1.1, unmodified), shown in Settings > Open-source licences |
| voice | done | most-seen strings rewritten (EN + ID): disclosure, permissions, Today empty state, block screen, nudge, goals empty state, "counting stopped", widget name |
| package | renamed | `app.scrollcount` -> `app.tidemark` (namespace, Kotlin packages, folders in main/test/androidTest), `tidemark.db`, `Theme.Tidemark`, export header `# Tidemark export v1`. Files moved, none deleted (75 before and after) |
| applicationId | `app.tidemark` | **must be replaced with a reverse domain you own before the first Play upload** |
| sweep | clean | `sweep.py android --config replica/brand.json`: no original name, domain, or leftover placeholder |
| brand files | `replica/brand/` | icon.svg, icon-monochrome.svg, logo.svg, play-icon-512.png, feature-graphic-1024x500.png, icon-preview.png, icon-16/48.png, render.py, fonts/ |

### Final audit (round 3)

- `android/` has 75 files.
  - No `scrollcount`, `BrainPal`, `Brainrot` or `EpowerX` anywhere.
  - Every Kotlin file declares a package under `app.tidemark`.
  - 20 XML/JSON files parse.
- Gradle: namespace and applicationId are `app.tidemark`; rootProject is "Tidemark"; compileSdk 35, minSdk 26, targetSdk 35.
- **Gradle wrapper missing.** There is no `gradlew` and no `gradle-wrapper.jar`, only `gradle-wrapper.properties` (8.9). Android Studio offers to create them; the CI workflow installs Gradle itself.
- CI: `.github/workflows/main.yml` is written but has never run.

## Round 4: first real build in GitHub Actions (2026-11-21)

Repository: https://github.com/hlwone999-cmd/doomscrolling (branch `main`). Workflow: `.github/workflows/main.yml`.

| run | commit | result | cause / fix |
| --- | --- | --- | --- |
| 44965044126 | c7ec3e8 | failed at "Set up Android SDK" | third-party `android-actions/setup-android@v3` failed (log needs sign-in). Replaced with the runner's own `sdkmanager` (platform 35, build-tools 34.0.0) in 90faf5a |
| 44965271578 | 90faf5a | failed at compile | one Kotlin error: `Unresolved reference 'heading'` in `Controls.kt` (fully qualified extension call). Fixed in e690a21 |
| 44965546782 | e690a21 | **success** | Python checks pass; `testDebugUnitTest` passes; `assembleDebug` passes; APK has no INTERNET permission; artifact `tidemark-debug-apk` (11.4 MB, kept 14 days) uploaded |

What this proves:
- The project **compiles** (main + unit-test source sets).
- The 36 JVM unit tests ran without a failure (Gradle fails the task on any failing test).
- A debug APK is produced with no INTERNET permission.

What it does not prove: anything on a device. Detection, the overlays, the instrumented tests (manual emulator job, not run yet) and the 50 device cases are all still pending.

## Round 5: sample data removed, detection reworked (2026-11-21)

Trigger: device report (Realme): the dashboard showed 88 sample videos and nothing counted. See BUG-002.

| run | commit | result |
| --- | --- | --- |
| 44968870448 | ae47e34 | **success**: Python checks, compile, JVM unit tests (now including PositionTrackerTest), debug APK (11.4 MB), no INTERNET permission |

**Next, on the phone.** Install the APK from run 44968870448, then:
1. Open Settings → Diagnostics.
2. Scroll Reels, Shorts and TikTok.
3. Share the Diagnostics log.

The log contains only structure (class names, view-id entries, sizes, scroll directions). It is what tunes detection for that phone.
