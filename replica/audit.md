# Feature audit

Date: 2026-11-21. Source: `features.csv` (62 rows: 40 must, 6 should, 6 could, 10 skip).

**Status levels used here:**

- **Verified.** Proven to work. Today that only covers what source inspection can prove.
- **Implemented (unverified).** All the code is written, the pure logic is tested, and the data layer is tested in SQLite. It has **not been compiled** (no Android SDK on this PC, by instruction) and **not run on a phone**.
- **Not implemented.** No code.

`features.csv` maps "verified" to `yes` and "implemented (unverified)" to `partial`. `replica-diff` therefore scores 51/100. That number is right until the build and the device tests pass.

## Before and after this round

| priority | before: yes / partial / no | after: verified / implemented (unverified) / not implemented |
| --- | --- | --- |
| must (40) | 1 / 18 / 21 | 1 / 39 / 0 |
| should (6) | 0 / 0 / 6 | 0 / 6 / 0 |
| could (6) | 0 / 0 / 6 | 0 / 6 / 0 |
| skip (10) | out of scope (payments, accounts, server, iOS, brand) | unchanged |

## Must

| feature | status | where |
| --- | --- | --- |
| Prominent accessibility disclosure with consent | implemented (unverified) | S02 `ui/Onboarding.kt` |
| App usable if disclosure declined | implemented (unverified) | `AppRoot`, Today banner |
| Guided permission setup | implemented (unverified) | S03 |
| OEM battery and autostart tips | implemented (unverified) | S03, S16, `core/Policies.kt` |
| Choose which apps to track | implemented (unverified) | S04 `AppsScreen` |
| Detect foreground tracked app | implemented (unverified) | `service/ReelService.kt` |
| Detect Instagram Reels screen | implemented, **detection unverified** | `detect/SurfaceDetector.kt`, rules v1 |
| Count Instagram Reels per settled video | implemented, **detection unverified** | `detect/CountingEngine.kt` |
| YouTube Shorts | implemented, **detection unverified** | rules v1 |
| TikTok For You / Following | implemented, **detection unverified** (generic fallback only) | rules v1 |
| No recount on UI change or duplicate events | implemented (unverified); logic fuzzed | engine + unique index |
| Bundled versioned rules | implemented (unverified) | `assets/detectors/v1.json` |
| Diagnostics flag for unverified versions | implemented (unverified) | Diagnostics screen, S04 |
| Floating counter | implemented (unverified) | `service/OverlayController.kt` |
| Auto show/hide | implemented (unverified) | engine + `OverlayPolicy` |
| Draggable, remembered position | implemented (unverified) | overlay |
| Session tracking | implemented (unverified) | engine + `SessionWriter` |
| Close sessions left by crash/reboot | implemented (unverified) | `closeDangling`, `BootReceiver` |
| Survives restart | implemented (unverified) | `BootReceiver` (goAsync), WorkManager |
| Health check and recovery | implemented (unverified) | `HealthCheckWorker`, S16 |
| Dashboard with goal progress and live minutes | implemented (unverified) | S05 |
| Daily, weekly, monthly statistics | implemented (unverified) | S08 |
| Per-app statistics | implemented (unverified) | S09 |
| Activity history | implemented (unverified) | S10 |
| Reduction goals, warning, block, gradual reduction, notices | implemented (unverified) | S11, S12, `core/GoalLogic.kt`, workers |
| Daily summary, weekly report, in-session nudge | implemented (unverified) | `service/Workers.kt`, service |
| Light and dark mode | implemented (unverified) | `design/AppTheme.kt` |
| Data on device only, no INTERNET | **verified** (source) | manifest, Gradle, `check_project.py` |
| Pause, delete a period, delete all, retention | implemented (unverified) | S15, `PruneWorker` |

## Should and could

All implemented (unverified):

- **Should:** counter size, opacity and timer; CSV export; reset hour; severity meter; goal windows and weekdays; local diagnostics log.
- **Could:** Facebook Reels and Snapchat Spotlight (opt-in, generic detection, unverified); home widget; streaks; breathing break; CSV import.

## Known limitations (written down, not hidden)

- **Detection.** All pager view ids are guesses. TikTok, Facebook and Snapchat rely on the generic fallback. `verifiedVersions` is empty for every app.
- **Import.** Restores daily totals only, not individual sessions, and never overwrites a day already on the phone.
- **Hourly chart.** Needs per-video events, so it is empty for days older than the retention setting. Daily totals are kept.
- **Block screen.** The phone's Back key reaches the app underneath (the overlay isn't focusable), but the block screen stays on top until the user leaves the app or snoozes.
- **Branding.** No launcher icon or Inter font files yet (system font). Both come with `/replica-brand`.
- **Developer screen.** The design catalogue has specimen buttons with no action, by design. It is listed as "(developer)".

## Branding (round 3)

The app is **Tidemark**. Branding changed names, colours, the icon, the font
and copy, not behaviour. Every feature status above is unchanged.

| item | status |
| --- | --- |
| app name, strings EN + ID | done in resources; on-screen look not seen on a device |
| launcher icon (adaptive + themed) | resources written and statically checked (layers exist, safe zone); not seen on a real launcher |
| Inter font + OFL licence | bundled and checked (same file hash as the source, licence text complete); rendering on a device not seen |
| palette | contrast checked (81 pairs, 0 failures) |
| package `app.tidemark` | renamed; imports and packages statically checked; **not compiled** |
| sweep for the original's identity | clean |
| trademark clearance for "Tidemark" | **not done** (web screening only) |
| `applicationId` | placeholder `app.tidemark`; must become your own domain before release |
