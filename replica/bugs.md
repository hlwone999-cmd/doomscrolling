# Bugs

Only reproduced bugs are listed here. Reproduction so far happens in the
Python engine port and against the app's SQL (see `test-plan.md`), since the
Android build hasn't run yet. "To check" below lists suspicions that need a
real build or phone. The project is not a git repo, so fixes have no commit
id; each one names the files changed.

| severity | open | fixed |
| --- | --- | --- |
| S1 | 0 | 0 |
| S2 | 0 | 0 |
| S3 | 0 | 0 |
| S4 | 0 | 1 |

Round 2 (full feature set): no new bug reproduced. Two "to check" items
were resolved in code (C-6, C-7); four new ones need a phone (C-10 to C-13).

### BUG-001: Day rollover during the grace period records the wrong end time

- Severity: S4
- Flow / case: F02 / F02-E12
- Screen: none (session history data; S10 will show it)
- Build: milestone 1 source, 2026-11-21. Device: engine port (`replica/tests/engine_sim.py`)

Steps
1. Open Instagram Reels at 03:59:00 (reset hour 04:00).
2. Pull down the notification shade at 03:59:50 (session enters the 30 s grace).
3. Return to Reels at 04:00:10.

Expected: the old session ends at 03:59:50, when the user left, with reason `day_rollover`.
Actual: it ended at 04:00:10, so session history would show 20 extra seconds and `last_event_at` moved forward. Active time was already correct.
Evidence: before the fix, `engine_sim.py` printed `closed: [(1, 20000, 'day_rollover')]` for leave at 1 000 ms and return at 20 000 ms.
Suspected cause: `CountingEngine.onSurface` closed the stale-day session at `now` without checking whether the user was away.
Status: **fixed.** `detect/CountingEngine.kt` now closes at `leftAt` when off-surface. Tests: `CountingEngineTest.dayRolloverDuringGraceEndsAtLeaveTime` (written, not run) and `engine_sim.py` (passes).

## To check (not reproduced; needs a build or a phone)

| # | suspicion | why | how to check |
| --- | --- | --- | --- |
| C-1 | Code may not compile on first open | written without a compiler; risky spots: `PathParser` icon builder, `LifecycleResumeEffect`, Room KSP setup | Android Studio build |
| C-2 | Instagram/YouTube pager ids are guesses | `clips_viewer_view_pager`, `reel_recycler` not seen on a device | `uiautomator dump` on Reels/Shorts |
| C-3 | Generic fallback may count Instagram main-feed video posts | relies on feed items being < 90% of the pager height | F02-E13 |
| C-4 | Fingerprint may drift while a video plays (captions that change, translated text) | stability filter only drops digits and short labels | watch one video 60 s with no scroll: count must stay 1 (rule 4 should hold) |
| C-5 | `TYPE_VIEW_SCROLLED` may come from an inner view for some apps, so `isPagerScroll` misses real swipes, leading to undercount | bounds check uses 80% of the pager height | F02-H1..H3 tallies |
| ~~C-6~~ | **Resolved in code:** Today's minutes ignored the open session | engine now sends live active time every 5 s (`touch`), Today adds `openActive`; sql DB-10 and JVM `liveActiveTimeExcludesTimeAway` | F03-E4 on device |
| ~~C-7~~ | **Resolved in code:** BootReceiver's async write might not finish | `goAsync()` + direct DAO call, `finish()` in `finally` | F07-H1 on device |
| C-8 | `ACTION_SCREEN_OFF` registered as NOT_EXPORTED | system broadcasts should still arrive | F02-E10 on Android 14+ |
| C-9 | Merged manifest gains INTERNET from a dependency | none of the current libraries should add it; WorkManager does not request it | P-1 after build |
| C-10 | Block screen and counter card are accessibility overlays that must take touches | `TYPE_ACCESSIBILITY_OVERLAY` with touchable views; untested | F04-H2 on device |
| C-11 | Widget may show yesterday's number until the first count or app open after the reset hour | refreshed by counts, app resume and DayRolloverWorker (inexact) | F03-E5 on device |
| C-12 | WorkManager periodic jobs drift with Doze | reminders documented as inexact; times may slip by minutes | F08-H1/H2 on device |
| C-13 | Goal "minutes" use whole minutes; a goal of 30 min triggers at 30:00, not before | floor of active ms / 60 000 | by design; confirm wording in S11 |
