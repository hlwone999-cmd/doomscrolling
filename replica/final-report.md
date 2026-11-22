# Tidemark: final audit report

Date: 2026-11-21. Scope: the whole project after rounds 1-3 (build, full feature set, branding).

**In one line:** all 52 in-scope features are written and 173 logic, data and static checks pass. **Update (round 4):** GitHub Actions compiled the app, the JVM unit tests passed and a debug APK was built (run 44965546782, artifact `tidemark-debug-apk`). The app has **not been run on a phone**, and short-video detection is **unverified**.

---

## 1. Features written

All in `android/app/src/main/java/app/tidemark/`. "Written" means the code exists; it does not mean it works.

**Must (40).**
- **Onboarding:** prominent disclosure with consent and a decline path (S02); permission setup with phone-maker tips (S03); choosing apps (S04).
- **Detection:** foreground app; Instagram Reels, YouTube Shorts and TikTok by bundled rules plus a generic fallback; video-change detection with dedupe (scroll gate, settle time, unique index); a flag for unverified app versions.
- **Overlay:** floating counter that shows and hides by itself, with drag and a remembered position.
- **Tracking and reliability:** sessions with live active time; crash and reboot recovery; health check and recovery screen (S16).
- **Stats:** Today dashboard (S05); day, week and month stats with comparisons (S08); per-app (S09); history (S10).
- **Goals:** reduction goals with warning %, notify or block (S11, S12), gradual weekly reduction, and goal notices.
- **Notifications:** daily summary, weekly report, in-session nudge.
- **Look and privacy:** light and dark themes; on-device data with no INTERNET permission; pause, delete a period, delete all, retention (S15).

**Should (6).** Counter size, opacity and timer; CSV export; custom reset hour; severity meter; goal time windows and weekdays; local diagnostics log.

**Could (6).** Facebook Reels and Snapchat Spotlight (opt-in, generic detection); home-screen widget; streaks; breathing break; CSV import of daily totals.

**Branding.**
- Name Tidemark; sea-teal palette.
- Adaptive launcher icon with themed (monochrome) layer, and a status-bar icon.
- Inter font with its OFL licence and a licences screen.
- Voice rewrite in English and Indonesian.
- Package `app.tidemark`.

**Out of scope (10, `skip`).** Payments, accounts, server features, iOS, and the original's brand and content.

## 2. Tests that actually ran (all on this PC, without Android)

| suite | what it runs | result |
| --- | --- | --- |
| `replica/tests/engine_sim.py` | line-for-line Python port of `CountingEngine.kt`: 19 scenarios + fuzz (3,000 random runs × 200 steps, 9 invariants) + no-recount property | **29/29 pass** |
| `replica/tests/logic_sim.py` | Python mirror of goals, stats, CSV, overlay policy, phone makers, schedule | **53/53 pass** |
| `replica/tests/check_data_layer.py` | the app's real trigger and `@Query` SQL, extracted from the Kotlin files and run in SQLite | **54/54 pass** |
| `replica/tests/check_project.py` | static: manifest (no INTERNET), Gradle (no network/billing libs), rules vs seeds vs `<queries>`, 280+ strings EN/ID with matching format args, every resource and import resolves, brace balance, no TODO, no raw colours, adaptive icon layers, icon inside the safe zone, font file, Inter licence | **37/37 pass** |
| `contrast.py` | WCAG contrast of the token pairs | **81 pairs, 0 AA failures** |
| `sweep.py` (brand) | `android/` against the original's name, developer, domain and our old placeholder | **clean** |
| XML/JSON parse | all 20 XML/JSON files in `android/` | **all parse** |
| `schema.sql` | loads in SQLite | **loads** |
| file integrity | `android/` file count before and after the package move | **75 = 75** |
| font integrity | bundled Inter vs the source copy (SHA-256); the font reports family Inter with weight and optical-size axes | **identical** |

Total: **173 checks pass, 0 fail.** One bug was found and fixed (BUG-001, day rollover during the grace period, S4).

**Limits of these tests.**
- The Python ports mirror the Kotlin; they are not the Kotlin.
- The static checker is not a compiler.
- None of these prove the app compiles or behaves correctly on a phone.

## 3. Not tested

- **Compilation and APK.** Never done. There is no Android SDK here, by instruction.
- **JVM unit tests (36)** in `app/src/test`: written, never run.
- **Instrumented tests (22)** in `app/src/androidTest` (13 Room, 9 Compose UI): written, never run.
- **Device cases: 50 of the 92 in `test-plan.md`.**
  - Every detection tally (Instagram, YouTube, TikTok, Facebook, Snapchat).
  - Counter, card and block-screen touch behaviour.
  - Reboot, kill and battery-manager recovery.
  - Notification timing, the widget, export and import through the system file picker.
  - Privacy checks: zero network bytes, no text stored, backup excluded.
- **Branding on a device:** the icon on real launchers, Inter rendering, and how the Indonesian text fits on screen.
- **GitHub Actions workflow:** written and YAML-valid, never run.

## 4. Remaining problems

| # | problem | severity / kind | next step |
| --- | --- | --- | --- |
| 1 | Code has never compiled; first-build errors are likely (C-1) | blocker | build in Studio or CI and fix the errors |
| 2 | Pager view ids for Instagram/YouTube are guesses; TikTok, Facebook and Snapchat use the generic fallback only (C-2) | blocker for a launch claim | `uiautomator dump` on a phone, then the device test protocol |
| 3 | Generic fallback might count feed videos or miss swipes (C-3, C-5); the fingerprint might drift (C-4) | risk | device tallies |
| 4 | Gradle wrapper (`gradlew` and jar) missing | setup | Studio creates it, or `gradle wrapper --gradle-version 8.9` |
| 5 | `applicationId` is a placeholder (`app.tidemark`) | must fix before Play | use a reverse domain you own; it cannot change later |
| 6 | "Tidemark" has only had web screening; an enterprise "Tidemark" software brand exists | legal risk | official trademark searches plus a lawyer |
| 7 | Overlays taking touches, widget refresh, WorkManager timing (C-10 to C-12) | to check | device |
| 8 | Back key reaches the app under the block screen; import restores daily totals only; hourly chart is empty beyond retention; widget uses the system font | known limitations | documented in `audit.md` |
| 9 | Library versions are a known-compatible 2024 set (AGP 8.7, Kotlin 2.0.21, Compose BOM 2024.12) | maintenance | Studio may offer upgrades; upgrade them together |
| 10 | No release signing and no store listing | not started (by instruction) | `/replica-launch` later |
| 11 | Room schema version 1 was extended in place; there are no migrations | fine before the first install | every change after release needs a Migration |

## 5. Building the APK with GitHub Actions

The workflow is already in the project at `.github/workflows/main.yml`. It has never run, and nothing has been pushed.

1. Create a GitHub repository of your own (private is fine). Add `android/`, `replica/` and `.github/`. `.claude/` is ignored by `.gitignore` and never pushed; CI does not need it.
2. Push to `main` (or open a pull request to it). You can also open **Actions → Tidemark build → Run workflow**.
3. Three jobs:
   - **checks**: Python 3.12 runs the four suites.
   - **build**: JDK 17 (Temurin) and Gradle 8.9 (`gradle/actions/setup-gradle`), then `gradle :app:testDebugUnitTest` and `gradle :app:assembleDebug`. It then checks that the merged manifest has no INTERNET permission, and uploads the test reports and the APK.
   - **instrumented** (only by hand, with the "instrumented" box ticked): an API 34 emulator runs `gradle :app:connectedDebugAndroidTest`.
4. When **build** is green, download the **tidemark-debug-apk** artifact from the run page.
5. Install it with `adb install app-debug.apk`, or copy it to the phone and allow "install unknown apps". Because it doesn't come from Play, Android 13+ asks you to allow restricted settings first: App info → ⋮ → Allow restricted settings. Then enable the accessibility service.
6. Run the device test protocol (`architecture.md`, "Test protocol"). Record the results in `replica/accuracy.md`. Add verified app versions to `verifiedVersions` in `assets/detectors/v1.json` only after the protocol passes.
7. If the build fails, the log shows the Gradle and Kotlin errors. Expect some on the first run (item 1 above).

A debug APK is for testing. A Play release still needs a real `applicationId`, a signing key (keystore as GitHub secrets) and a `bundleRelease` job. None of these is set up yet.
