# Test plan: ScrollCount (working name)

Build: full feature set (round 2), **not yet compiled** (no Android toolchain on this PC; no git repo, so no commit id)
Date: 2026-11-21
Env: logic checks run here in Python 3.13 / SQLite 3.50; everything Android waits for Android Studio + a phone.

This is an Android app, not a web app, so the pack's Playwright/axe step
becomes four kinds of test:

| type | where | how to run | status |
| --- | --- | --- | --- |
| **sim** | `replica/tests/engine_sim.py` (line-for-line port of `CountingEngine.kt` + fuzz) | `python replica/tests/engine_sim.py` | **run here: 29/29 pass** |
| **sql** | `replica/tests/check_data_layer.py` (the app's real trigger and @Query SQL) | `python replica/tests/check_data_layer.py` | **run here: 54/54 pass** |
| **logic** | `replica/tests/logic_sim.py` (mirror of goals, stats, CSV, policies, schedule) | `python replica/tests/logic_sim.py` | **run here: 53/53 pass** |
| **static** | `replica/tests/check_project.py` (resources, strings EN/ID, imports, manifest, offline rules) | `python replica/tests/check_project.py` | **run here: 37/37 pass** (26 + 11 brand resource checks) |
| **unit** | `android/app/src/test` (JUnit, JVM) | Android Studio → `test` | written, not run (19 engine + 5 core + 12 logic = 36) |
| **inst** | `android/app/src/androidTest` (Room on device SQLite, Compose UI) | Android Studio → `connectedAndroidTest` | written, not run (13 db + 4 onboarding UI + 5 controls UI = 22) |
| **device** | manual on real phones, results in `replica/accuracy.md` | checklist below | pending: needs a build and your phone |

"blocked" = the feature is not built yet (later milestone).

## F01 First run to first counted video

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| F01-H1 | happy | fresh install → S02 Agree → S03 Turn on accessibility → back → Continue → open Instagram Reels | counter appears top-right within ~1 s; Today shows 1 after the first settled video | device | pending |
| F01-H2 | happy | S02 Agree button | callback once | inst `agreeCallsBackOnce` | written |
| F01-N1 | negative: decline | S02 Not now | app opens; Today shows "counting is off" banner; no counting in Reels | inst `declineIsEquallyAvailable` + device | written / pending |
| F01-N2 | negative: withdraw | Settings → Read again → Not now while counting | open session closes (`paused`), counter disappears, no further counts | device | pending |
| F01-E1 | edge: sideloaded APK, Android 13+ | install debug APK, try enabling the service | system "restricted setting" dialog; user can allow via App info ⋮ menu (document in README) | device | pending |
| F01-E2 | edge: notifications denied (13+) | deny in S03 | row stays "Turn on"; app works | device | pending |
| F01-E3 | edge: language | phone in Indonesian | every S02/S03/S05 string in Indonesian, nothing cut off at 200% font | device | pending |
| F01-E4 | edge: screen reader | TalkBack on S02 | title announced as heading; reads in order; both buttons reachable | inst `titleIsAHeadingForScreenReaders` + device | written / pending |
| F01-E5 | edge: disclosure content | S02 | mentions the one-way hash and "no internet" (Play requirement) | inst `disclosureNamesTheHashAndNoInternet` | written |
| F01-E6 | edge: tracked app not installed | S04 | — | — | blocked (S04, m2) |

## F02 Scroll with the live counter (core loop)

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| F02-H1 | happy | Instagram Reels: scroll 50 videos, tally by hand | count within ±2 of tally (m1 exit test, 2 phones) | device | pending |
| F02-H2 | happy | same on YouTube Shorts | within ±3 per 100 | device | pending |
| F02-H3 | happy | same on TikTok For You (both package names if available) | within ±3 per 100 | device | pending |
| F02-E1 | edge: swipe back | settle A, B, swipe back to A | 2 counts | unit + sim | **sim pass** |
| F02-E2 | edge: fling | fling past videos < 400 ms each | only the settled one counts | unit + sim | **sim pass** |
| F02-E3 | edge: UI change, same video | like / rotate / open comments without scrolling | no second count even if the key changes | unit + sim (+2000-run property) | **sim pass** |
| F02-E4 | edge: swipe back then key drift | A, C, back to A, A's key changes | 2 counts | unit + sim | **sim pass** |
| F02-E5 | edge: duplicate events | same key observed 5× | 1 count | unit + sim | **sim pass** |
| F02-E6 | edge: brief leave | notification shade / share sheet < 30 s, return | same session, counter hides after 1.5 s and returns, no recount | unit + sim + device | **sim pass** / device pending |
| F02-E7 | edge: long leave | leave > 30 s | session closed at leave time; new session on return | unit + sim | **sim pass** |
| F02-E8 | edge: grace expiry without tick | return after 40 s before any tick | old session closed, new one opened | unit + sim | **sim pass** |
| F02-E9 | edge: app switch | Reels → Shorts | Instagram session closed (`app_switch`), YouTube opened | unit + sim | **sim pass** |
| F02-E10 | edge: screen off | lock phone mid-session | closed at once (`screen_off`), counter gone | unit + sim + device | **sim pass** / device pending |
| F02-E11 | edge: reset hour while watching | 04:00 passes | split into two sessions; same video counts again (by design) | unit + sim | **sim pass** |
| F02-E12 | edge: reset hour while briefly away | leave at 03:59:50, return 04:00:10 | old session ends at leave time | unit + sim | **sim pass after BUG-001 fix** |
| F02-E13 | edge: off-surface screens | Instagram feed, DMs, search, profile, Stories | counter never shows (beyond the 1.5 s hide delay); 0 counts | device | pending |
| F02-E14 | edge: ads in feed | scroll past a sponsored reel | counts (documented) | device | pending |
| F02-E15 | edge: counter drag | long-press, drag, release | snaps to nearest edge; same place next time | device | pending |
| F02-E16 | edge: counter never steals swipes | swipe starting next to the pill | the app scrolls normally | device | pending |
| F02-E17 | edge: battery | 1 h of Reels with Battery Historian | < 1% per hour attributable to the app | device | pending |
| F02-E18 | edge: target app updates | update Instagram mid-test | detection still works or Diagnostics flags it | device | blocked flag (m2) |
| F02-N1 | negative: unreadable item | key cannot be computed | never counts (undercount over double count) | unit + sim | **sim pass** |
| F02-N2 | negative: mixed key kinds | index then hash in one session | hash ignored | unit + sim | **sim pass** |
| F02-INV | invariants | 3000 random runs × 200 steps | no key twice per session, never before settle, ≤1 open session, no double close, no count after close, 0 ≤ active ≤ duration | sim fuzz | **sim pass** |

## F03 Stats, history, dashboard

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| F03-H1 | happy | Settings → Design → Insert demo data → Today | 88 videos; per-app rows ordered Instagram 42, TikTok 29, YouTube 17 | sql DB-6/DB-7 + device | **sql pass** / device pending |
| F03-E1 | edge: empty | fresh install | ring at 0, "Calm", empty state | device | pending |
| F03-E2 | edge: reset hour | 01:30 counts for "yesterday" (reset 04:00) | correct day key, incl. DST | unit `CoreTest` | written |
| F03-E3 | edge: theme | System / Light / Dark switch in Settings | instant, all screens readable | device | pending |
| F03-E4 | edge: open session minutes | watch 5 min without leaving | minutes on Today update only after the session closes | device | known limitation (to check) |
| F03-W | weekly / monthly / history | — | — | — | blocked (m4) |

## F03 additions (round 2)

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| F03-W1 | happy | Stats → Week; ← previous week | totals, average over elapsed days, vs last week, best/worst day | logic (ranges, series, summary, delta) + device | **logic pass** / device pending |
| F03-W2 | happy | Stats → Month in January, ← | previous month is December of last year; leap February | logic | **logic pass** |
| F03-W3 | happy | Stats → Day | hourly chart from event times; "View as list" shows the same numbers | sql DB-14 + device | **sql pass** / device pending |
| F03-W4 | happy | tap an app row | S09 week/month chart + recent sessions | device | pending |
| F03-W5 | happy | Stats → All sessions | S10 grouped by day, "Show more" after 100 | sql DB-17 + device | **sql pass** / device pending |
| F03-E4 | edge: open session minutes | watch 5 min without leaving | Today minutes rise every ~5 s (C-6 resolved in code) | sql DB-10 + unit + device | **sql/sim pass** / device pending |
| F03-E5 | edge: widget | add the widget, count videos, cross the reset hour | count updates within ~15 s of counting; resets after the day changes | device | pending |

## F04 Reduction goals (free)

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| F04-H1 | happy | Goals → Add → all apps, 100 videos, notify | goal card with progress, warning notice at 80, reached notice at 100, once each per day | logic + sql DB-15 + device | **logic/sql pass** / device pending |
| F04-H2 | happy | per-app goal Instagram 60, Block | at 60 the block screen covers Reels; "Close the app" goes home; leaving dismisses it | logic (blocks flag) + device | **logic pass** / device pending |
| F04-E1 | edge: snooze | block, tap "5 more minutes" twice with 1 snooze/day | first works for 5 min, second is not offered | sql DB-15 + device | **sql pass** / device pending |
| F04-E2 | edge: minutes goal | 30 min goal | warns at 24 min, reaches at 30 (whole minutes, C-13) | logic | **logic pass** |
| F04-E3 | edge: weekdays | Mon–Fri goal on Saturday | inactive | logic | **logic pass** |
| F04-E4 | edge: window over midnight | 22:00–06:00 | active at 23:00 and 05:00, not at 12:00; end exclusive | logic | **logic pass** |
| F04-E5 | edge: gradual reduction | 100, −10 %/week, floor 30, phone off 3 weeks | 72 after three due steps; never below the floor; at least −1 per step | logic | **logic pass** |
| F04-E6 | edge: one goal per scope | add a second all-apps video goal | replaces the first | inst `goalSaveKeepsOnePerScope` | written |
| F04-E7 | edge: streak | met, met, met, missed | streak counts back from yesterday, stops at a miss or a gap | logic | **logic pass** |
| F04-E8 | edge: met-day notice | goal met yesterday | "Goal met" notice after the reset hour, once | device | pending |

## F05 Counter appearance

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| F05-H1 | happy | Settings → Counter → Large, 70 %, timer on | pill grows, becomes more see-through, shows m:ss | logic (scale) + device | device pending |
| F05-E1 | edge: modes | Always / Off | Always: visible anywhere in a tracked app; Off: never, counting continues | logic `overlayPolicy` | **logic pass** |
| F05-E2 | edge: card | tap the pill | card with session videos/time, today; Pause 1 h, Hide until I leave, Open app; closes after 6 s or outside tap | device | pending |
| F05-E3 | edge: reset position | "Put the counter back" | top-right again | device | pending |

## F06 Privacy controls

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| F06-H1 | happy: pause | Pause 1 h | counter hides, nothing counts, Today shows "paused until" with Resume | device | pending |
| F06-H2 | happy: export | Export as CSV → pick a folder | file with daily and session rows, no keys, no content | logic (round trip ×500) + device | **logic pass** / device pending |
| F06-H3 | happy: import | Import that file on a fresh install | days restored; existing days untouched; malformed lines skipped and counted | logic + sql DB-16 + inst | **logic/sql pass** / inst written |
| F06-H4 | happy: delete period | Delete the last 7 days | sessions, totals and goal events of those days gone; open session kept and counting continues | sql DB-13 + inst | **sql pass** / inst written |
| F06-H5 | happy: delete all | Delete all data → confirm | everything gone, new salt, log cleared, consent kept | device | pending |
| F06-E1 | edge: retention | 30 days | detailed events older than 30 days pruned weekly; totals kept | sql DB-12 + inst | **sql pass** / inst written |
| F06-N1 | negative: foreign file | import a random CSV | "not an export from this app", nothing imported | logic | **logic pass** |

## F08 Notifications and reminders

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| F08-H1 | happy | daily summary at 21:00 | notice around 21:00 with today's videos and minutes; tap opens Stats | logic (schedule delay) + device | **logic pass** / device pending |
| F08-H2 | happy | weekly report on Sunday | this week vs last week (fewer / more / first week) | logic (delta, schedule) + device | **logic pass** / device pending |
| F08-H3 | happy | nudge after 20 min | one notice per session at 20 min or N videos | logic `nudges` + device | **logic pass** / device pending |
| F08-N1 | negative: permission off | deny notifications (Android 13+) | Reminders screen shows the warning with "Turn on"; nothing crashes | device | pending |
| F08-E1 | edge: time change | change reminder time | jobs re-planned (CANCEL_AND_REENQUEUE) | device | pending |

## F07 Recovery

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| F07-H1 | happy: reboot mid-session | watch Reels, reboot | session closed at its last activity (≤5 s before the reboot), service back, counting resumes | sql DB-4 + inst `danglingSessionEndsAtLastActivity` + device | **sql pass** / device pending |
| F07-E1 | edge: force-stop | Settings → Force stop while watching | same as H1 on next start; service must be re-enabled by the user on most phones | device | pending |
| F07-E2 | edge: OEM battery kill | Xiaomi/Oppo/Samsung background kill | "Counting stopped" notice within ~15 min (at most every 6 h); Today banner → S16 with phone-specific steps | device | pending |
| F07-E4 | edge: reboot with service off | turn the service off, reboot | notice right after boot (BootReceiver) | device | pending |
| F07-E3 | edge: app update | install a newer debug build mid-session | dangling session closed, counting resumes | device | pending |

## Data layer (all flows)

| case | check | auto | result |
| --- | --- | --- | --- |
| DB-0 | triggers contain no UPSERT (Android 8-10 SQLite) | sql | **pass** |
| DB-1 | second open session rejected | sql + inst | **pass** / written |
| DB-2 | duplicate keys ignored; rollups to session and daily_stat | sql + inst | **pass** / written |
| DB-3 | close adds active time once; second close no-op | sql + inst | **pass** / written |
| DB-4 | dangling session ends at last activity, once | sql + inst | **pass** / written |
| DB-5 | touch ignores closed sessions | sql | **pass** |
| DB-6 | per-app read model order and names | sql | **pass** |
| DB-7 | demo rows coexist with a real open session | sql + inst | **pass** / written |
| DB-8 | cascade delete | sql | **pass** |
| DB-9 | day keys isolate totals | sql | **pass** |

## Privacy and policy

| case | check | auto | result |
| --- | --- | --- | --- |
| P-1 | manifest has no `INTERNET`; merged manifest too (a library could add it) | inspect now; `aapt dump permissions app-debug.apk` after build | source **pass** / merged pending |
| P-2 | no network at runtime | device: Settings → Network usage for the app = 0 B after a day | pending |
| P-3 | no text stored | `adb shell run-as app.tidemark sqlite3 databases/tidemark.db "select item_key from reel_event limit 20"` shows only `i:` / `h:` | pending |
| P-4 | backup excluded | `adb shell bmgr backupnow app.tidemark` does not include the db | pending |

## Totals

- **Run here, round 3 (after branding):** 173 checks: 29 sim + 54 sql + 53 logic + 37 static. **173 pass, 0 fail.** Brand sweep clean; contrast 81 pairs, 0 failures. Round 1 found one bug (BUG-001, fixed). Round 2 found none.
- **Written, waiting for Android Studio:** 36 JVM unit tests and 22 instrumented tests. None have run.
- **Device cases pending:** 50 of 92 cases. These include the milestone exit tests, every detection tally, and the four privacy checks.
- **Not run anywhere:** compilation, APK build, any on-device behaviour. Nothing here claims detection works.
