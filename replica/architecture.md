# Architecture: your reel counter (a rebuild of BrainPal's core features)

Revision 2, 2026-11-21. Working name until `/replica-brand`: **the app**.
Built from `recon.md` and `features.csv` (S-IDs, F-IDs and feature rows refer
to those files).

## Product decision (final)

The app is **100% free, offline, with no login, no registration, no payments
and no server.** Every feature is available to everyone with no premium tier.
User activity data never leaves the phone.

What enforces this in code, not just in the docs:

- **The manifest has no `INTERNET` permission.** The app cannot reach the
  network even by accident, and no SDK can phone home.
- **No third-party SDKs that need keys or accounts.** No billing, analytics,
  crash-reporting, ads, auth or remote-config SDKs. Dependencies are AndroidX,
  Kotlin and Compose libraries only (plus Vico for charts), all running
  locally.
- **No API keys** anywhere in the build.
- `android:allowBackup="false"` and `dataExtractionRules` exclude the
  database, so Google cloud backup doesn't copy it either. The only way data
  leaves the phone is a CSV export the user starts themselves.

Removed in revision 2: login and registration, authentication, account
management, subscriptions and Pro, Google Play Billing, RevenueCat, the
backend API, the cloud/server database (Supabase), remote detector rules
(Cloudflare), crash reporting and analytics (Sentry/Crashlytics), scroll
battles and friend leaderboards, and every route that needed the network.

## Stack

| layer | choice | why |
| --- | --- | --- |
| app | Kotlin + Jetpack Compose, Material 3, minSdk 26, target current SDK | the accessibility service and overlay are native-only; Compose for every screen |
| detection | `AccessibilityService` + our `DetectorEngine` | the only public API that sees another app's screen |
| detection rules | bundled JSON in `assets/detectors/`, versioned | no network; updated with app releases on the Play Store |
| overlay | `TYPE_ACCESSIBILITY_OVERLAY` drawn by the service | no "Display over other apps" permission; the counter lives exactly as long as the service |
| database | Room over SQLite ([`schema.sql`](schema.sql)) | on-device, typed, migrations, Flow queries for live UI |
| settings | Jetpack DataStore (Preferences) | theme, overlay, reminders, reset hour, pause, consent, install salt, heartbeat |
| DI | hand-written graph in `App.kt` | the service, the receivers, the workers and the UI share one repository (changed from Hilt in the build: fewer plugins) |
| charts | own Canvas bar chart (`design/Controls.kt`) | day, week and month charts; no chart library (changed from Vico in the build) |
| background work | WorkManager + a `BOOT_COMPLETED` receiver | reminders, day rollover, health check, pruning; survives restarts |
| notifications | `NotificationCompat`, 3 channels (Reminders, Goals, Service status) | the user can mute each channel in system settings |
| theme | Material 3 light + dark, follows system by default, manual override | dark/light mode requirement |
| diagnostics | local-only ring-buffer log (last 500 lines, no content), viewable in Settings and shareable by the user | replaces remote crash reporting |
| CI / release | GitHub Actions → signed AAB → Play internal testing | free distribution through the Play Store |

Not needed: auth, email, payments, hosting, backend, CDN. The Play listing
still needs a privacy policy URL. A static page (for example GitHub Pages)
saying "no data collected" covers it, and the app itself never contacts it.

## Schema

Tables: **6**, all on-device. Access rule: the Android app sandbox (private DB
file, backup excluded). Full SQL in [`schema.sql`](schema.sql). It was tested
in in-memory SQLite: duplicate videos, raw text keys, a second open session,
duplicate goals and a second same-day warning are all rejected, and rollups
and crash-closing work.

### Re-check of the previous 10 entities

| entity (rev 1) | decision | reason |
| --- | --- | --- |
| `tracked_app` | **kept**, + `last_seen_version_code` | detect when Instagram/TikTok/YouTube updated, to flag possible detection problems |
| `session` | **kept**, + `last_event_at`, `rules_version`, `target_version_code` | `last_event_at` lets a crash or reboot close a session at the right time instead of inflating duration |
| `reel_event` | **kept**, `item_index` → `item_key` (text, required) | dedupe works even when the target app exposes no index (salted hash) |
| `daily_stat` | **kept** | day, week and month stats all read from it |
| `limit_rule` | **renamed `goal`**, free, + gradual reduction (`step_pct`, `floor_value`, `next_step_day`) | "reduction targets" requirement; no Pro gate |
| `limit_event` | **renamed `goal_event`**, + kinds `reached`, `met_day`, `stepped_down` | drives notifications and the history of goals met |
| `detector_rule` | **removed** | rules are a bundled asset; a table only made sense for remote refresh |
| `profiles` | **removed** | no accounts |
| `friendships` | **removed** | no server, so no friends feature |
| `daily_totals` | **removed** | server copy of `daily_stat`; locally it was a duplicate |

Monthly stats, history and streaks need no new tables: they're queries over
`daily_stat`, `session` and `goal_event`.

### What the database enforces

- **One open session at a time.** Partial unique index `session_one_open`.
- **The same video is never counted twice in one session.** Unique
  `(session_id, item_key)`. The check constraint allows only `i:`/`h:` keys,
  so raw text can't be stored.
- **Totals can't drift from events.** Triggers roll `reel_event` and session
  open/close into `session` and `daily_stat`.
- **Each goal notice fires once per goal per day.** Partial unique index on
  `goal_event` (snoozes excluded; they're capped by `snoozes_per_day`).
- **One goal per app per metric.** Unique index on `goal`.

### DataStore keys (not in SQL)

`theme` (system/light/dark), `overlay.*` (show_mode auto/always/off, style,
size, opacity, x, y, show_timer), `reminders.*` (daily_summary_time,
weekly_report_day, in_session_nudge_minutes, goal_warnings on/off),
`reset_hour`, `tracking_paused_until`, `disclosure_accepted_at`,
`onboarding_done`, `install_salt` (32 random bytes, made on first run),
`service_heartbeat_at`, `last_health_state`.

## Detection design

```
AccessibilityEvent ─► EventGate ─► SurfaceDetector ─► VideoChangeDetector ─► Repository ─► Room
  (packageNames =      (throttle,    (on the short-video    (settled on a new     (single-writer
   tracked apps)        drop noise)   screen? grace timer)   video? dedupe)        coroutine actor)
                                            │
                                            └─► OverlayController (show / hide / update)
```

- **Service config:** `packageNames` = enabled tracked apps (updated at
  runtime), events `TYPE_WINDOW_STATE_CHANGED | TYPE_WINDOW_CONTENT_CHANGED |
  TYPE_VIEW_SCROLLED`, `canRetrieveWindowContent=true`, `notificationTimeout`
  ~100 ms. Not declared as `isAccessibilityTool`.
- **Rules** come from `assets/detectors/v<N>.json`: per package and per
  target-app `versionCode` range, a `surface` matcher (structure only:
  full-screen vertical pager, class, resource-id and content-description
  patterns) and a `count` rule. When no range matches a new app version, the
  newest rule is used and Settings → Diagnostics shows "Instagram updated;
  detection not yet verified for this version".
- **Generic fallback detector:** a full-screen vertical pager inside a tracked
  app, matched by structure alone. It catches many layout changes until a
  rule update ships.

### Show and hide the counter

- It shows when SurfaceDetector matches (Reels tab, Shorts player, TikTok For
  You/Following). It hides on any window-state change where the match fails
  (main feed, DMs, search, profile, another app, home screen).
- **A 1.5 s hide delay** stops flicker during transitions. A **30 s session
  grace period** keeps the same session (and its dedupe set) alive if the
  user briefly opens comments, the share sheet, the notification shade or
  another app and comes back.

### When a video counts (written rules, so tests can check them)

1. A video counts when it becomes the settled current page for at least
   **400 ms** (configurable in rules). Videos flung past without settling
   don't count.
2. The first video on entering the surface counts once it settles.
3. **Deduplication key** (`item_key`), in order of preference:
   - `i:<index>`: the pager's adapter position from `TYPE_VIEW_SCROLLED`
     `fromIndex`/`toIndex`, when the target app exposes it and the index is
     stable.
   - `h:<hash>`: HMAC-SHA256(install_salt, package + the current item's
     structural fingerprint), truncated to 64 bits. The fingerprint may use
     the item node's content description, which is the only place content
     text is touched. It is hashed in memory and never stored or logged in
     clear. With a random per-install salt, the hash can't be reversed or
     matched across phones.
4. **No recount within a session.** That covers swiping back to a previous
     video, re-layout after rotation, closing the comments sheet, a
     pause/resume, or duplicate or burst events. The in-memory set catches
     these first, and the `(session_id, item_key)` unique index is the
     backstop.
5. A new session (after the 30 s grace, or on a new day) starts a fresh set.
   Seeing the same video again in a later session counts again, by design.
6. Ads in the feed count as videos, because the user still scrolled past
   them. This is listed on the counting rules page in the app.

**Accuracy claim:** none, until the test protocol below has been run on real
devices. The app and store listing say "counts videos you watch" and never
"100% accurate".

## Restart and recovery

| situation | what happens |
| --- | --- |
| Phone restarts | Android rebinds enabled accessibility services after boot by itself. `BootReceiver` (`BOOT_COMPLETED`) closes any dangling open session at `last_event_at` with `service_stopped`. WorkManager jobs survive reboot on their own. |
| Service process killed or crashed | System rebinds the service. `onServiceConnected` closes dangling sessions, reloads rules, writes a heartbeat. |
| Service disabled by OEM battery manager or the user | Can't be re-enabled programmatically (that needs `WRITE_SECURE_SETTINGS`, which is not available). `HealthCheckWorker` (every 15 min) and `BootReceiver` read `AccessibilityManager.getEnabledAccessibilityServiceList`. If ours is missing and tracking isn't paused, they post a "Counting stopped" notification that opens S16, and S05 shows a red banner. |
| Enabled but not running (some OEMs) | Heartbeat older than 15 min while enabled → the same S16 flow with OEM-specific steps (autostart, battery "no restrictions", lock in recents). |
| App updated | `MY_PACKAGE_REPLACED` receiver re-schedules workers and rechecks the service. |
| Tracked app updated | At session open, compare `versionCode` with `tracked_app.last_seen_version_code`; if no rule range covers it, flag it in Diagnostics. |

Onboarding (S03) asks for the battery-optimisation exemption and shows OEM
tips up front, because prevention beats recovery.

## Internal API (no network)

The "API" is the app's internal surface: service, repositories, prefs,
system intents and workers. One table per flow.

**F01 First run to first counted video**

| method path | does | who | input | output | flow |
| --- | --- | --- | --- | --- | --- |
| `ui OnboardingVM.acceptDisclosure()` | stores consent timestamp, continues | user on S02 | none | `disclosure_accepted_at` | F01 |
| `ui OnboardingVM.declineDisclosure()` | app opens with tracking off; S05 shows "tracking off" | user on S02 | none | none | F01 |
| `intent ACTION_ACCESSIBILITY_SETTINGS` | enable the service | user on S03 | none | back to app | F01 |
| `intent ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | battery exemption | user on S03 | package | granted or not | F01 |
| `perm POST_NOTIFICATIONS` | notification permission (13+) | user on S03 | none | granted or not | F01 |
| `repo AppRepo.setTracked(pkg, on)` | upsert `tracked_app`, update `packageNames` | S04, S14 | package, bool | none | F01 |
| `repo HealthRepo.status()` | Flow: service enabled, heartbeat, battery, notifications | S03, S05, S15, S16 | none | status | F01, F07 |

**F02 Scroll with the live counter (core loop)**

| method path | does | who | input | output | flow |
| --- | --- | --- | --- | --- | --- |
| `svc ReelService.onAccessibilityEvent(e)` | gate → surface → video change | system | event | none | F02 |
| `svc ReelService.onServiceConnected()` | close dangling sessions, load rules, heartbeat | system | none | none | F02, F07 |
| `repo SessionRepo.open(pkg, surface, versionCode)` | insert `session` | service | package, surface, now | session id | F02 |
| `repo SessionRepo.countVideo(sessionId, itemKey, dwell)` | `insert or ignore` into `reel_event`; triggers roll up | service | ids, key | today count (app + all) | F02 |
| `repo SessionRepo.touch(sessionId, at)` | bump `last_event_at` (at most every 5 s) | service | time | none | F02 |
| `repo SessionRepo.close(reason, endedAt)` | set `ended_at`, `active_ms`, reason | service, receivers, workers | reason | none | F02, F06, F07 |
| `repo SessionRepo.closeDangling()` | close open session at `last_event_at` | boot, service connect | none | count closed | F07 |
| `svc OverlayController.show/hide/update(state)` | counter pill on the accessibility overlay | service | count, timer, severity | none | F02, F05 |
| `bcast ACTION_SCREEN_OFF` | close session on screen off | system | none | none | F02 |

**F03 Stats, history and dashboard**

| method path | does | who | input | output | flow |
| --- | --- | --- | --- | --- | --- |
| `repo StatsRepo.today()` | Flow: today's totals + open session + goal progress | S05, widget | reset hour | dashboard model | F03 |
| `repo StatsRepo.range(from, to, bucket)` | bucket = hour (from `reel_event`), day, week or month (from `daily_stat`) | S08, S09 | day keys, bucket | series per app | F03 |
| `repo StatsRepo.compare(period)` | this week vs last week, this month vs last month | S05, S08 | period | deltas | F03 |
| `repo HistoryRepo.sessions(pkg?, cursor)` | paged session history | S10 | filter, cursor | rows | F03 |
| `repo HistoryRepo.goalHistory(cursor)` | days met / missed from `goal_event` | S10, S11 | cursor | rows | F03, F04 |

**F04 Reduction goals (free)**

| method path | does | who | input | output | flow |
| --- | --- | --- | --- | --- | --- |
| `repo GoalRepo.upsert(goal)` | create or edit a goal, optional reduction plan | S11 | goal | id | F04 |
| `repo GoalRepo.evaluate(pkg, today)` | after each count: none / warn / reached / block; writes `goal_event` once | service | totals | decision | F04 |
| `svc BlockController.block(goal)` | full-screen accessibility overlay (S12) + `GLOBAL_ACTION_BACK` | service, only if action = block | goal | none | F04 |
| `repo GoalRepo.snooze(goalId)` | allowed while under `snoozes_per_day` | S12 | id | ok or denied | F04 |
| `repo GoalRepo.applyReductionSteps(today)` | lower `value` by `step_pct` (to `floor_value`), log `stepped_down` | DayRolloverWorker | day | none | F04 |

**F05 Counter appearance**

| method path | does | who | input | output | flow |
| --- | --- | --- | --- | --- | --- |
| `prefs OverlayPrefs.setPosition(x, y)` | save normalised position on drag end | S06 | 0..1 coords | none | F05 |
| `prefs OverlayPrefs.update(style, size, opacity, showMode, showTimer)` | counter appearance | S14 | prefs | none | F05 |

**F06 Privacy controls**

| method path | does | who | input | output | flow |
| --- | --- | --- | --- | --- | --- |
| `prefs Tracking.pauseUntil(t)` | service ignores events; open session closed as `paused` | S07, S15 | time | none | F06 |
| `repo DataRepo.exportCsv(uri)` | sessions + daily totals to a file the user picks (SAF) | S15 | destination | file | F06 |
| `repo DataRepo.deleteRange(from, to)` | delete history for a period, rebuild `daily_stat` | S15 | day keys | none | F06 |
| `repo DataRepo.deleteAll()` | close session, wipe DB and prefs, new install salt | S15 | confirm | empty state | F06 |
| `prefs Privacy.setRetention(days)` | `reel_event` retention (30/90/365) | S15 | days | none | F06 |

**F07 Recovery**

| method path | does | who | input | output | flow |
| --- | --- | --- | --- | --- | --- |
| `rcv BootReceiver` (`BOOT_COMPLETED`) | close dangling session, check service, notify if off | system | none | maybe notification | F07 |
| `rcv UpdateReceiver` (`MY_PACKAGE_REPLACED`) | re-schedule workers, check service | system | none | none | F07 |
| `ui RecoveryVM.oemGuide()` | maps `Build.MANUFACTURER` to bundled steps | S16 | none | guide | F07 |

**F08 Notifications and reminders**

| method path | does | who | input | output | flow |
| --- | --- | --- | --- | --- | --- |
| `prefs ReminderPrefs.update(...)` | daily summary time, weekly report day, in-session nudge minutes, goal warnings | S14 | prefs | none | F08 |
| `svc NudgeController.check(session)` | "You've watched 20 min / 150 videos" notice during a session | service | session | notification | F08 |
| `notify Goals` | warned (80%), reached, met-the-day | GoalRepo | event | notification | F04, F08 |

Count: **44** internal surfaces: 39 in the eight flow tables (receivers
included) plus 5 background jobs. **0 network calls.**

Webhooks in and out: none. There's no server to receive them.

### Background jobs (WorkManager, all local)

| job | schedule | does |
| --- | --- | --- |
| DayRolloverWorker | daily at reset hour | close an open session (`day_rollover`) and re-open on the new day; apply goal reduction steps; log `met_day` |
| DailySummaryWorker | daily at the user's time (inexact) | "Yesterday: 142 videos, 38 min" notification |
| WeeklyReportWorker | weekly on the user's day | week vs last week notification, opens S08 |
| HealthCheckWorker | every 15 min (WorkManager minimum) | service enabled + heartbeat check → S16 notification |
| PruneWorker | weekly | delete `reel_event` older than retention; `daily_stat` stays |

No exact alarms (`SCHEDULE_EXACT_ALARM` isn't needed, and Play restricts it).
Reminders may arrive a few minutes late. That's acceptable and stated in
Settings.

## Test protocol before any accuracy claim

Results go in `replica/accuracy.md` (made during the build).

- **Devices:** at least 3 physical phones (one Samsung One UI, one Xiaomi/HyperOS or Oppo/ColorOS, one Pixel/stock), on Android 10, 13 and 15 or newer.
- **Per app** (Instagram Reels, TikTok For You, YouTube Shorts), record the target app's versionCode. Then:
  - Scroll 100 videos while counting by hand.
  - Include 10 swipe-backs, opening and closing comments 5 times, a rotation, a pause/resume, fast flings, an ad, and leaving and returning within 30 s.
- **Pass:** the count is within ±3 of the manual tally per 100. There are 0 counts on non-video screens (feed, DMs, search, profile). The counter is never shown off-surface for more than the 1.5 s hide delay.
- **Restart test:** reboot mid-session, then check that the session is closed at `last_event_at`, the service is back, and counting resumes.
- **Kill test:** force-stop from settings and the OEM battery-manager kill. Then check that the S16 notification fires within 15 min.
- Re-run on every target-app major update you notice, and before every release.

## The parts that bite

- **Detection breaks when the target apps update.** With no remote rules, a
  fix needs an app release. Play review usually takes hours to a few days.
  Mitigation: versionCode-ranged rules, the generic pager fallback, a
  Diagnostics flag, a pinned-version regression rig, and a release checklist.
  This is the biggest ongoing cost.
- **Google Play Accessibility review.** Prominent disclosure (S02) in normal
  flow, its own screen, an affirmative button, and separate from other
  consent. The disclosure must mention the salted on-device hash of the
  current video's description. Declaration form plus a video of the consent
  and no-consent paths. Data Safety: no data collected or shared.
- **OEM background kills.** Covered by onboarding, the health check and S16,
  but it can't be fully solved. On some phones the user must change OEM
  settings.
- **Android 13+ restricted settings.** Sideloaded APKs need extra steps to
  enable accessibility. Play installs are fine.
- **Time zones and DST.** UTC ms stored, `day_key` at write time with a 04:00
  default reset hour, so DST changes at 02:00/03:00 never split a day. The
  per-session `tz_offset_min` handles travel, and history is never
  recomputed.
- **Idempotency and duplicates.** In-memory set, then `insert or ignore`,
  then the unique index. Workers are idempotent because `goal_event` unique
  indexes make a second run a no-op.
- **Races.** Accessibility callbacks arrive on the main thread, and all writes
  go through one single-writer coroutine actor. `session_one_open` is the
  backstop.
- **Battery and performance.** Walk the node tree only on window-state
  changes and settled scrolls, cap depth, recycle nodes. Target under 1%
  battery per hour of scrolling, measured with Battery Historian.
- **Overlay touch.** The pill must not steal swipes: `FLAG_NOT_FOCUSABLE`, a
  small hit area, drag on long-press. Only the block screen intercepts
  touches.
- **Data loss.** Uninstalling, a new phone or "clear data" erases history
  (no cloud by design). CSV export is the user's backup, and the app says so
  in S15.
- **Sustainability.** No revenue means maintenance is volunteer or funded
  some other way. Keep the codebase small; consider open-sourcing the
  detector rules so others can contribute fixes.
- **Not applicable:** auth, payments, webhooks, rate limits, email, search,
  multi-tenancy, server GDPR deletion.

## Build order

1. **Vertical slice: Instagram Reels end to end, ugly.**
   - Screens: S02 disclosure, S03 (accessibility toggle only), S05 today's number, S06 pill that shows on Reels and hides elsewhere.
   - Tables: `tracked_app`, `session`, `reel_event`, `daily_stat`.
   - Surfaces: `onAccessibilityEvent`, `onServiceConnected`, `SessionRepo.open/countVideo/close/closeDangling`, `OverlayController`, `StatsRepo.today`.
   - Manifest without `INTERNET`, bundled rules v1.
   - **Exit:** test protocol for Instagram on 2 phones, including the restart test. Then Play internal testing with the accessibility declaration.
2. **Detection for all three apps.** TikTok and YouTube Shorts rules, the
   generic pager fallback, show/hide delay and session grace, the full
   dedupe chain, screen-off, the Diagnostics flag. **Exit:** test protocol on
   3 phones for all three apps.
3. **Reliability.** Full S03 (battery, notifications, OEM tips), S04 choose
   apps, `BootReceiver`, `UpdateReceiver`, HealthCheckWorker, heartbeat, S16,
   DayRolloverWorker. **Exit:** reboot and kill tests pass.
4. **Stats and history.** S08 day/week/month with comparisons, S09 app
   detail, S10 history, S05 dashboard polish, retention and PruneWorker.
5. **Goals, notifications, privacy, theme.**
   - Goals: S11, S12, `goal`, `goal_event`, gradual reduction.
   - Notifications: daily summary, weekly report, in-session nudges.
   - Privacy: S07/S15 pause, export, delete range, delete all.
   - Theme and overlay: light/dark theme, overlay style settings (F05).
6. **Could-haves.** Facebook Reels and Snapchat detectors, home widget, streaks, breathing break.
7. **Fixes from replica-entrepreneur** (false counts, battery and nag complaints from public reviews).

## Build status

The code for every must, should and could row now exists in `android/`. It
has not been compiled or run on a phone. See `audit.md` for the status of
each feature, `build-log.md` for the deviations, and `test-plan.md` for what
was tested and how.
