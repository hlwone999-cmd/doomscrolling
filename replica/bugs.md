# Bugs

Only reproduced bugs are listed here. Reproduction so far happens in the
Python engine port and against the app's SQL (see `test-plan.md`), since the
Android build hasn't run yet. "To check" below lists suspicions that need a
real build or phone. The project is not a git repo, so fixes have no commit
id; each one names the files changed.

| severity | open | fixed |
| --- | --- | --- |
| S1 | 0 | 2 (BUG-002, BUG-003: fixed in code; device check pending) |
| S2 | 0 | 2 (BUG-005, BUG-006: fixed in code; device check pending) |
| S3 | 0 | 0 |
| S4 | 0 | 1 |

Round 2 (full feature set): no new bug reproduced. Two "to check" items
were resolved in code (C-6, C-7); four new ones need a phone (C-10 to C-13).

### BUG-006: TikTok on the Realme: swipes seen, page changes rejected ("without a swipe")

- Severity: S2 (core flow: counts missing)
- Flow / case: F02-H1, F02-E1..E5
- Device: Realme, TikTok, APK from run 44976396186 (user report, 2026-11-21)

Evidence from the phone (Diagnostics and log)
- Scroll events 6, page transitions 0, counted 0; last scroll kind MOVING; last page decision "same video"; position – → 0.
- Log: `page not counted in TikTok: page structure changed without a swipe: not a new video (pager scroll events so far: 5)`.
- Screen kind changed between `no bottom navigation` (13:54:45) and `main feed (bottom navigation, 5 items)` (13:55:25).

Diagnosis (source; the event order on the phone is inferred, see "not yet proven")
- **Order of signals.** `PageTracker` required the swipe evidence to arrive *before* the new page. When a probe saw the new page first, it was taken as a rebuild and became the baseline at once; the scroll event that followed then found "the same video". Scroll events were received (6) but every change was rejected. The Python port shows the symptom exactly: with that rule, 10 swipes in this order gave 5 counts ("several swipes needed") or none.
- **Scroll source not checked.** Any scroll event from a node of pager size was used, including its indexes. TikTok has a full-screen horizontal tab pager around the video pager; its indexes could be mixed with the video pager's.
- **Pager baseline kept across visits.** On returning to the video screen the old page stayed the baseline, so the arrival page was compared with a page from a different screen.
- **Feed kind from one reading.** "Main feed or not" came from a single snapshot at entry, which can be taken before the bottom bar is laid out.

Fix
- `PageTracker`: evidence and page change are correlated in either order. A page seen first waits up to 1 s (`PENDING_WINDOW_MS`) for evidence; evidence then confirms it at once (the service probes immediately). Evidence is no longer erased by a same-page probe; it expires after 1.5 s.
- Scroll events in the 300 ms after a counted change are the tail of that swipe (`TAIL`), so many MOVING events give one count. A change inside the same top-level page (cover replaced by the player) is not a new video once the pager's pages are known to be its top-level children.
- `ScrollSource` + `SurfaceDetector.scrollRelation`: each scroll event's source is classified against the held pager (the pager, its own list, inside it, around it, same size only, unrelated). Indexes are used only from the pager or its list; sideways scrolls and small scrollers are ignored. IDs need not match exactly.
- The baseline is rebased on each arrival and when the app replaces its pager.
- `FeedContext`: the bottom-navigation reading is taken at entry, 400 ms later and every second; the verdict changes only after two readings agree. For TikTok (`countOnlyMainFeed` in the rules, v3), swipes count only in the main feed.
- Diagnostics: scroll source (class, view-id entry, node id, from/to, item count, scrollY, dY, dX), its relation to the pager and how it was used, the pager node and replacements, last probe time and what it saw, evidence held since, page waiting since, late confirmations, rejected changes. Structure only, no text.

Tests
- Python port (`engine_sim.py`): 11 BUG-006 checks, all passing; with the old "adopt at once" rule, `tenLateEvidenceSwipesGiveTenCounts` gives 5 of 10.
- JVM: `FeedPipelineTest` (11 new) and `PageTrackerTest` (5 new): late evidence, evidence too late, events from the child list with snapshots from the parent, same-size inner node, tab and comment scrolls, child ids changing mid-swipe, constant wrapper, many MOVING events → one count, search viewer not the main feed.

Status: **fixed in code. Not proven on the phone.** The exact event order on the Realme is still an inference; the new Diagnostics fields show it directly.

### BUG-003: Only the first video counted on TikTok (counter stuck at 1)

- Severity: S1 (core flow)
- Flow / case: F02-H3, F02-E1..E5
- Device: Realme, TikTok (user report, 2026-11-21). The overlay appeared and showed 1; after several swipes it stayed at 1.

Diagnosis (source)
- The page key came from the pager's direct child only. With a constant wrapper it never changed, so every swipe gave the same `p:0` key.
- ViewPager keeps its children in creation order, not page order, so `PositionTracker` could step back onto an already-counted position.
- `TYPE_VIEW_SCROLLED` carries the adapter position (`fromIndex`/`toIndex`, set by ViewPager and RecyclerView), but it was ignored.
- Probes on content changes ran only every 600 ms, so the mid-swipe moment was usually missed.

Fix (00383ec): `PageTracker` + `FeedPipeline`
- A new video needs swipe evidence and a different page (adapter index, or a two-level page signature). No text is read.
- Scrolls from small views (comments, captions) are not swipes.
- On-screen probes run every 250 ms.
- The counter now defaults to top centre, below the status bar plus 64 dp.
- Tests: `FeedPipelineTest` and `PageTrackerTest` (JVM, passed in CI run 44970583305), and the same scenarios in `engine_sim.py`.

Status: **fixed in code and built. Not yet verified on the phone.**

**Follow-up (f4f8aea, run 44972030687).** The newest APK was still stuck at 1, so the service was traced again:
- Window-state events re-ran `find()` mid-swipe. It failed while two pages were half visible, which dropped the surface and lost the swipe's scroll events. The held pager is now kept while it is visible in the same window.
- `find()` preferred the outermost wrapper; it now picks the innermost pager.
- The page signature follows full-screen containers up to 4 levels deep.
- Rejected page decisions are now logged.

Status: **built, not yet verified on the phone.**

### BUG-002: Dashboard showed sample data (88 = 42 + 29 + 17) and nothing counted on a Realme phone

- Severity: S1 (core flow blocked, statistics not real)
- Flow / case: F02-H1, F03-E1
- Build: 0.1.0 debug APK from run 44965884111; device: Realme (user report, 2026-11-21)

Steps
1. Install the APK, accept the disclosure, enable the accessibility service.
2. Open Instagram Reels and scroll.

Expected: Today starts at 0 and rises with each video.
Actual: Today showed 88 videos (Instagram 42, TikTok 29, YouTube 17) and the count did not rise.

Diagnosis (source)
- **Sample data.** The numbers came from `DemoData.insertToday()`, which only the developer screen's "Insert demo data" button called. It wrote real rows (`rules_version` 0, keys `i:demo-…`), so the correct Room queries showed them.
- **No counting.** Short-video screens were found either by guessed exact view ids or by a narrow generic test: the container had to be a RecyclerView/ViewPager *and* expose its scroll direction. Custom pagers fail that test, so the screen is likely never recognised.
- **Caption hash.** The fallback video key hashed caption text, which goes against the no-text requirement.
- **Scroll gate.** Counting after the first video needed a scroll event from the pager itself, so a pager that sends none would count at most one video per session.

Fix (ae47e34)
- DemoData and its button are removed. On start, the app purges sample sessions and rebuilds daily totals from real data (sql DB-18/19).
- Structural detection: the container fills the window, is pager-like and not horizontal-only, and its current child fills ≥ 95 %. Id hints only raise priority.
- Text-free keys: adapter row, or a position from container identity (`PositionTracker`, JVM-tested).
- A structurally confirmed page change counts as a swipe.
- `getWindows()` fallback for reading the app window.
- Live diagnostics plus a structural candidate log.

Status: **fixed in code, built** (run 44968870448: compile, JVM tests and APK pass). **Detection is not yet verified on the phone.**

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
| ~~C-1~~ | **Resolved:** code did not compile on first open | first CI compile (run 44965271578) found one error: `heading()` called fully qualified in `Controls.kt`; fixed in e690a21. Run 44965546782 compiled, passed `testDebugUnitTest` and built the APK | done |
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
