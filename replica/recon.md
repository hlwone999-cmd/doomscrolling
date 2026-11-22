# Recon map: BrainPal: Quit Scrolling (Android; iOS assessed)

Scope: the complete core of a short-form scroll tracker. Detecting and counting
Instagram Reels, TikTok and YouTube Shorts; a floating live counter that shows
itself only on the short-video screen; session duration; daily and weekly
stats; per-app tracking; settings and privacy controls; reduction goals and
reminders.
For: the user's own branded product for the general public, **100% free,
offline, no login, no payments, no server** (product decision, revision 2).
Date: 2026-11-21 (revision 2 the same day: removed Pro, paywall, scroll
battles and everything server-side; see `architecture.md`)
Method: **public sources only.** No account used, no APK downloaded,
decompiled or traffic-logged, nothing scraped. Store pages were read once each.

> **Reference only.** BrainPal (package `com.brainrot.android`, EpowerX Labs)
> is the reference for features and flow. Don't reuse its name, "brain state"
> artwork, copy, icons or colours. Counting reels through Android's
> Accessibility Service is a common pattern shared by Thumbo, Scrolless,
> ScrollTracker, ReelCounter and others, so it is not BrainPal's property.
>
> **TikTok.** BrainPal's public listing names Instagram, YouTube, Facebook
> and Snapchat, and **not TikTok**. TikTok support is a gap the clone can fill.
> Rivals Thumbo, ScrollTracker and ReelCounter all claim it.

## Sources

| # | source | URL | notes |
| --- | --- | --- | --- |
| 1 | Play listing (target) | https://play.google.com/store/apps/details?id=com.brainrot.android | did not render for the fetcher; read through #2 instead |
| 2 | AppGoblin mirror (target) | https://appgoblin.info/apps/com.brainrot.android | features, ~2.6M installs, 4.5★ from ~129K ratings, released 2026-03-28, updated 2026-11-19, IAP only, SDK list (Firebase, Segment, RevenueCat, Razorpay, ad SDKs) |
| 3 | developer site | https://brainrotapp.ai | named on #2, not read yet |
| 4 | search summary | https://www.appbrain.com/app/brainpal-quit-scrolling/com.brainrot.android | 403 on fetch; only a search snippet: "data never leaves your device", Pro blocking |
| 5 | rival: ScrollTracker | https://appgoblin.info/apps/com.novabloom.scrolltracker | free/Pro split, streaks, widget, breathing break, bedtime mode, scroll-event counting |
| 6 | rival: ReelCounter | https://appgoblin.info/apps/com.reelscounter.app | count-based limits, daily goal mode, floating button style/visibility, calendar stats |
| 7 | rival: Scroll (iOS) | https://apps.apple.com/app/id6759265456 | iOS "estimated" swipe count, $6.99/wk or $49.99/yr, leaderboards, widgets |
| 8 | rival: Thumbo | https://play.google.com/store/apps/details?id=app.thumbo | claims IG, YT, TikTok, Snapchat, FB; page not rendered |
| 9 | rival: Scrolless | https://play.google.com/store/apps/details?id=com.scrolless.app | blocks Reels/Shorts; page not rendered |
| 10 | Play Accessibility policy | https://support.google.com/googleplay/android-developer/answer/10964491 | prominent disclosure, affirmative consent, declaration form plus video, monitoring apps are not accessibility tools |
| 11 | Play monitoring policy | https://support.google.com/googleplay/android-developer/answer/12955211 | don't transmit personal usage data, so keep it on-device |
| 12 | AccessibilityService API | https://developer.android.com/reference/android/accessibilityservice/AccessibilityService | events, `canRetrieveWindowContent`, `packageNames`, accessibility overlays |
| 13 | AccessibilityEvent API | https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent | `TYPE_VIEW_SCROLLED`, `TYPE_WINDOW_STATE_CHANGED`, `TYPE_WINDOW_CONTENT_CHANGED` |
| 14 | WindowManager.LayoutParams | https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY | `TYPE_APPLICATION_OVERLAY` vs `TYPE_ACCESSIBILITY_OVERLAY` |
| 15 | Apple DeviceActivity | https://developer.apple.com/documentation/deviceactivity | page did not render; the iOS notes below need checking against it |

**Gaps to close before build:** no screenshots were captured (the store
screenshots carry no captions in the mirror). Screens below are inferred from
the description, the rivals and the platform's constraints. Recommended next
pass: install BrainPal on **your own phone**, walk through it yourself, and
save screenshots to `replica/screens/` using the S-IDs below. That's allowed
because it's your own device and account. Don't decompile it.

## Core loop

You open Reels, Shorts or TikTok, and a small counter in the corner ticks up
with every video, so the number makes you stop sooner. Afterwards you look at
today's and this week's total.

## How the original likely works (platform evidence, not its code)

- **Detection:** an `AccessibilityService` with `canRetrieveWindowContent`
  and `packageNames` set to the tracked apps. The foreground app comes from
  window-state events. The short-video *surface* (Reels tab vs feed vs DMs) is
  recognised from the node tree, e.g. a full-screen vertical pager. (#12, #13)
- **Counting:** a new video is counted when the vertical pager settles on a
  new item (a scroll of about one screen height, or a new focused item),
  debounced so a half-swipe and a bounce-back don't count. Rivals' listings
  say only "scroll events" (#5). The exact rule has to be designed and tested
  per app.
- **Overlay:** `TYPE_ACCESSIBILITY_OVERLAY`, drawn by the accessibility
  service, needs no extra permission. `TYPE_APPLICATION_OVERLAY` needs
  "Display over other apps". ReelCounter asks for that (#6, #14).
- **Blocking:** when the cap is hit, a full-screen overlay on top of the
  short-video surface, and/or a GLOBAL_ACTION_BACK to leave it (guess).
- **Storage:** on-device (claim in #2). The SDK list shows RevenueCat for
  subscriptions and Firebase/Segment analytics, so "never leaves the device"
  probably applies to scroll data only.
- **iOS:** a third-party iOS app can't read another app's screen or draw over
  it. Screen Time (FamilyControls / DeviceActivity / ManagedSettings) gives
  per-app *time* thresholds and shielding, not swipe counts. The iOS rival
  calls its count an "estimate" (#7). Revision 2: iOS is out of scope; the
  product is Android only.

## Screens

States legend: E empty, L loading, F filled, X error, P permission missing,
O off/paused. All screens are inferred, none were seen; confirm with your own
screenshots.

| ID | screen | route / how to reach | purpose | key components | states seen |
| --- | --- | --- | --- | --- | --- |
| S01 | Welcome | first launch | the pitch: see your reel count | hero visual, value bullets, Start button | F |
| S02 | Accessibility disclosure | S01 → Start | Play-mandated prominent disclosure of what's read and why, with affirmative consent | disclosure text, data-accessed list, Agree / Not now | F, declined |
| S03 | Permission setup | S02 → Agree | turn on the accessibility service, notifications, battery-optimisation exemption (no overlay permission: the counter is an accessibility overlay) | permission checklist rows with status, deep-link buttons, OEM battery tips | P, partial, all granted |
| S04 | Choose apps | S03 done, or Settings | pick which installed apps to track | app rows (icon, name, installed?, toggle), "not installed" hint | F, none installed |
| S05 | Home / Today | main tab 1 | today's total reels, time, sessions, per-app split, "brain state" style meter | big count, meter, per-app chips, current-session card, limit progress | E (no reels yet), F, P (service off banner), O (paused) |
| S06 | Floating counter | in a tracked app's short-video surface | live count while scrolling | draggable pill: count, optional timer, app colour, tap to expand, long-press to hide | shown, hidden (off-surface), near-limit, limit hit, dragging |
| S07 | Counter expanded | tap S06 | quick session info and actions | session count and time, today total, pause tracking, close | F |
| S08 | Stats | main tab 2 | daily and weekly charts | Day/Week toggle, bar chart, totals, avg per day, best/worst day, per-app legend | E, F |
| S09 | App detail | S08 or S05 → app | one app's history | count and time chart, sessions list, avg session length | E, F |
| S10 | Session history | S05/S09 → sessions | list of sessions | rows: app, start time, duration, reels | E, F |
| S11 | Goals (free) | main tab 3 | set daily reel and/or minute targets, total or per app, optional gradual reduction | goal cards, steppers, warn-at %, action (notify/block), schedule, reduction plan, days met | E, F |
| S12 | Goal reached / block screen | over S06 surface at the target | stop the scroll | full-screen card, today's count, "close app", snooze (capped) | warning, blocked |
| S13 | ~~Paywall~~ removed | n/a | revision 2: the app is free; ID kept so other IDs stay stable | n/a | n/a |
| S14 | Settings | main tab 4 | everything configurable | sections: tracked apps, counter (style, size, opacity, position, show-mode), notifications, daily reset hour, theme | F |
| S15 | Privacy & data | S14 → Privacy | trust | on-device statement, pause tracking, export CSV, delete all data, permission status, open disclosure again | F, deleted (E) |
| S16 | Service-stopped recovery | banner on S05, notification | the OS killed the service or the user revoked it | explanation, re-enable button, OEM-specific steps | X |
| S17 | ~~Scroll battles~~ removed | n/a | revision 2: needs a server; ID kept so other IDs stay stable | n/a | n/a |
| S18 | Home widget (could) | launcher | today's count at a glance | count, small meter | E, F |

## Flows

```
F01 New user gets their first counted reel
    S01 -> S02 -> S03 (system Accessibility settings, back) -> S04 -> S05 -> open Instagram Reels -> S06 shows 1
    happy path clicks: ~8 (Start, Agree, Enable, system toggle, Allow, Back, pick apps, Done)
    edge: user declines disclosure (app must still open, no tracking); restricted-settings block on
          sideloaded/Android 13+ installs; tracked app not installed; OEM kills service immediately

F02 Scroll with the live counter (core loop)
    open tracked app -> (feed: S06 hidden) -> Reels/Shorts/TikTok feed: S06 appears -> swipe x N, count ticks -> leave surface: S06 hides, session closes
    happy path clicks: 0 (fully passive)
    edge: half-swipe and bounce back (no count); fast flings; same video re-entered; ads in feed;
          Shorts opened from a link; split-screen/PiP; screen off mid-session; app update changes layout

F03 Check how today and this week went
    S05 -> S08 (Day/Week toggle) -> S09 -> S10
    happy path clicks: 2
    edge: first day (one bar), day boundary at custom reset hour, time zone change, no data for a tracked app

F04 Set a reduction goal and reach it (free)
    S11 -> set 100 reels/day, block, optional -10%/week to 30 -> scroll -> S06 warns at 80% -> S12 at 100
    happy path clicks: ~5
    edge: limit lowered below today's count; snooze abuse; limit per app vs total; midnight reset during block

F05 Move and restyle the counter
    long-press/drag S06 to a corner; S14 -> counter -> size/opacity/show-mode
    happy path clicks: 1 drag, or 3 in settings
    edge: position off-screen after rotation, overlay over the app's own buttons, show-mode "always"

F06 Pause tracking or wipe data
    S07 -> pause (until tomorrow / 1h)   or   S14 -> S15 -> delete all -> confirm
    happy path clicks: 2-3
    edge: paused state visible on S05; delete while a session is open

F07 Recover a stopped service
    notification / S05 banner -> S16 -> system settings -> back -> S05 healthy
    happy path clicks: 3
    edge: user revoked on purpose; repeated kills (show OEM guide)

F08 Get reminders and notifications (revision 2; replaces "Upgrade to Pro")
    S14 -> reminders: daily summary time, weekly report day, in-session nudge, goal warnings -> notification -> opens S05 or S08
    happy path clicks: 3
    edge: notification permission denied (13+); channel muted in system settings; reminder arrives a few minutes late (inexact)

F09 (removed in revision 2: scroll battles need a server)
```

## Components

| component | variants | states | used on |
| --- | --- | --- | --- |
| Button | primary, secondary, ghost, danger | default, pressed, focus, disabled, loading | all |
| Floating counter pill | compact (count), with timer, expanded card | hidden, shown, near-goal, goal reached, dragging, paused | S06, S07 |
| Big count / meter | ring or bar, 3+ severity levels | low, mid, high, over goal | S05, S18 |
| Permission row | accessibility, notifications, battery | missing, granted, error | S03, S15, S16 |
| App toggle row | installed, not installed | on, off, disabled | S04, S14 |
| Stat card | total, average, sessions, time, vs previous period | empty, filled | S05, S08, S09 |
| Bar chart | day (hourly), week (daily), month (daily), stacked by app | empty, filled, selected bar | S08, S09 |
| Segmented control | Day / Week / Month | selected | S08 |
| List row (session) | with app icon | default | S10 |
| Stepper / slider | count, minutes, percent | default, disabled | S11, S14 |
| Goal card | total, per app, with reduction plan | off, on, met today, missed | S11 |
| Full-screen interrupt | warning, block | with/without snooze | S12 |
| Banner | info, warning, error | dismissible | S05, S16 |
| Bottom sheet | confirm, options | open | S07, S15 |
| Toast/snackbar | info, undo | | settings saves, delete |
| Bottom tab bar | 4 tabs (Today, Stats, Goals, Settings) | selected | main app |
| Theme switch | system, light, dark | selected | S14 |
| Empty state | per screen with illustration slot | | S05, S08, S09, S10, S11 |

Removed in revision 2: paywall plan card, Pro badge/lock, leaderboard row.

## Inferred data model

Everything is on-device (SQLite/Room plus DataStore). There is no server and
no account. Nothing below is BrainPal's schema; it's inferred from features.
The final SQL is in `schema.sql` (6 tables).

```
TrackedApp  package (pk), display_name, enabled, surface_kind (reels | shorts | tiktok_feed),
            last_seen_version_code, added_at
            evidence: #2 app list, S04, rivals #5/#6
            confidence: high

Session     id, package, surface_kind, started_at, last_event_at, ended_at, reel_count,
            active_ms, ended_reason, rules_version
            evidence: "session duration" in scope, rivals' scroll time (#5)
            confidence: high (fields: medium)

ReelEvent   id, session_id, at, item_key (pager index or salted hash), dwell_ms
            evidence: hourly chart, dedupe requirement
            confidence: medium (our design)

DailyStat   day_key (local, after reset hour), package, reels, active_ms, sessions
            (materialised for fast day/week/month charts)
            evidence: S05, S08 daily/weekly/monthly stats
            confidence: high

Goal        id, scope (all | package), metric (reels | minutes), value, warn_at_pct,
            action (notify | block), days, time window, reduction step + floor, enabled
            evidence: #2 block at a cap; #6 count-based limits; #5 per-app limits, bedtime,
            gradual reduction
            confidence: medium

GoalEvent   id, goal_id, day_key, at, kind (warned | reached | blocked | snoozed | met_day | stepped_down)
            evidence: S12 snooze, notifications, goal history
            confidence: medium

OverlayPrefs, ReminderPrefs, Settings  (DataStore, not tables)
            evidence: #6 customizable floating-button visibility and style; S14; Play disclosure rule (#10)
            confidence: high
```

Relationships: TrackedApp 1-n Session, Session 1-n ReelEvent,
TrackedApp 1-n DailyStat, TrackedApp 0-n Goal (or Goal scope = all),
Goal 1-n GoalEvent.

Removed in revision 2: Entitlement (no payments), DetectorRule (rules are a
bundled JSON asset), Friend/Battle (no server).

## Feature matrix

See `features.csv`. Must: 40, should: 6, could: 6, skip: 10.

## Out of scope (cannot or should not be cloned)

- **BrainPal's brand, "brain state" artwork, copy and colours.** Make your own.
- **BrainPal's user base, ratings and friend graph.** Not needed; there's no social feature.
- **Anything needing a server or payment** (product decision): accounts, Pro, billing, scroll battles, cloud backup, remote rules, remote analytics.
- **iOS.** Android only. iOS can't count videos inside other apps.
- **Reading or storing what's in the videos** (captions, creators, content). Only a salted one-way hash is kept, for dedupe.
- **Any use of the target apps' private APIs or bundled code** for detection. Use only the on-screen accessibility tree on the user's own device.

## Size

Screens 16 (S13 and S17 removed), flows 8, entities 6 tables plus DataStore prefs.

Hard parts:
1. **Reliable detection and counting per app.** Instagram, TikTok and YouTube
   change their layouts often. Rules are bundled, so every fix needs an app
   release. Needs versioned rules, a generic fallback detector, and a real
   device test rig. Accuracy isn't claimed until tested.
2. **Google Play approval for Accessibility use.** You need the in-app
   prominent disclosure, consent and no-consent flows, a declaration form with
   video, and no data transmission (guaranteed: no INTERNET permission).
3. **Staying alive in the background.** OEM battery killers (Xiaomi, Oppo,
   Samsung, Huawei) stop accessibility services. You need boot recovery, a
   health check, a recovery screen and per-OEM guides.

Size: **M** (a few weeks) for the Android app with must + should rows, with
one developer and real test devices. Removing billing and the server makes it
smaller than revision 1. No promise of matching BrainPal's detection accuracy
on day one.
