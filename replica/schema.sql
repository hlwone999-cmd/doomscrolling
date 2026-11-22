-- Schema for the reel-counter clone. Revision 2: offline-only (build: is_open + no-UPSERT triggers).
-- On-device SQLite via Room. There is no server, no account and no cloud copy.
-- This file is the whole database. 6 tables.
--
-- Conventions:
--   * integer primary keys: one user, one device, no sync, so uuids add nothing.
--   * times are INTEGER epoch milliseconds in UTC. SQLite has no timestamptz.
--   * day_key is the *local* calendar day ('YYYY-MM-DD') computed at write time
--     with the user's reset hour (default 04:00), so a 1 a.m. session counts
--     toward "yesterday". tz_offset_min is kept so history survives travel.
--   * no owner column: the database file is the owner (app-private storage).
--   * access rule: the Android app sandbox; android:allowBackup="false".
--   * settings, overlay prefs, reminder prefs, the install salt and the service
--     heartbeat live in Jetpack DataStore, not here.
--   * detection rules are a bundled JSON asset (assets/detectors/v<N>.json),
--     not a table. session.rules_version records which set counted it.
--   * no video content, captions, usernames or creator names are stored.
--     item_key is a pager index or a salted one-way hash, never text.
--
-- Removed in revision 2 (and why):
--   detector_rule  rules are bundled in the APK; nothing is fetched remotely.
--   profiles, friendships, daily_totals  server-only (scroll battles); no server.
--                  daily_totals duplicated daily_stat anyway.
--   limit_rule -> goal, limit_event -> goal_event  goals are free for everyone
--                  and gain an optional gradual-reduction plan.

pragma foreign_keys = on;

-- 1 -------------------------------------------------------------------------
create table tracked_app (
  package        text primary key,                 -- e.g. com.instagram.android
  display_name   text not null,
  surface_kind   text not null
    check (surface_kind in ('reels','shorts','tiktok_feed','fb_reels','spotlight')),
  enabled        integer not null default 1 check (enabled in (0,1)),
  last_seen_version_code integer,                  -- target app version at last session
  added_at       integer not null,
  updated_at     integer not null
);

-- 2 -------------------------------------------------------------------------
create table session (
  id             integer primary key autoincrement,
  package        text not null references tracked_app(package) on delete cascade,
  surface_kind   text not null,
  day_key        text not null,                    -- local day of started_at
  tz_offset_min  integer not null,
  started_at     integer not null,
  last_event_at  integer not null,                 -- used to close sessions left open by a crash or reboot
  ended_at       integer,                          -- null while open
  reel_count     integer not null default 0 check (reel_count >= 0),
  active_ms      integer not null default 0 check (active_ms >= 0),
  ended_reason   text
    check (ended_reason in ('left_surface','app_switch','screen_off','goal_block',
                            'service_stopped','paused','day_rollover')),
  rules_version  integer not null,                 -- bundled detector set that counted it
  target_version_code integer,                     -- Instagram/TikTok/YouTube versionCode
  is_open        integer check (is_open is null or is_open = 1),  -- 1 while open, NULL when closed
  created_at     integer not null,
  updated_at     integer not null,
  check (last_event_at >= started_at),
  check (ended_at is null or ended_at >= started_at),
  check ((ended_at is null) = (ended_reason is null)),
  check ((is_open = 1) = (ended_at is null))
);
create index session_pkg_start on session (package, started_at);
create index session_day on session (day_key);
create index session_start on session (started_at);           -- history list, newest first
-- Only one open session at a time, enforced by the database: NULLs never
-- collide in a unique index, so only one row can hold is_open = 1.
-- (A plain index Room can declare; an expression/partial index would break
-- Room's schema validation during migrations.)
create unique index session_one_open on session (is_open);

-- 3 -------------------------------------------------------------------------
create table reel_event (
  id             integer primary key autoincrement,
  session_id     integer not null references session(id) on delete cascade,
  at             integer not null,
  item_key       text not null,                    -- 'i:<pager index>' or 'h:<16 hex salted hash>'
  dwell_ms       integer check (dwell_ms is null or dwell_ms >= 0),
  check (item_key like 'i:%' or item_key like 'h:%')
);
create index reel_event_at on reel_event (at);
-- The same video is never counted twice in one session: bounce-backs, duplicate
-- scroll events, re-layouts after rotation or closing the comments sheet.
-- (The unique index also serves lookups by session_id.)
create unique index reel_event_no_dupe on reel_event (session_id, item_key);

-- 4 -------------------------------------------------------------------------
-- Materialised daily totals: fast day/week/month charts, fast goal checks,
-- and what survives after old reel_event rows are pruned.
create table daily_stat (
  day_key        text not null,
  package        text not null references tracked_app(package) on delete cascade,
  reels          integer not null default 0 check (reels >= 0),
  active_ms      integer not null default 0 check (active_ms >= 0),
  sessions       integer not null default 0 check (sessions >= 0),
  updated_at     integer not null,
  primary key (day_key, package)
);
create index daily_stat_pkg on daily_stat (package, day_key);

create trigger reel_event_rollup after insert on reel_event
begin
  update session set reel_count = reel_count + 1,
                     last_event_at = max(last_event_at, new.at),
                     updated_at = new.at
   where id = new.session_id;
  insert or ignore into daily_stat (day_key, package, reels, active_ms, sessions, updated_at)
    select day_key, package, 0, 0, 0, new.at from session where id = new.session_id;
  update daily_stat set reels = reels + 1, updated_at = new.at
   where day_key = (select day_key from session where id = new.session_id)
     and package = (select package from session where id = new.session_id);
end;

create trigger session_open_rollup after insert on session
begin
  insert or ignore into daily_stat (day_key, package, reels, active_ms, sessions, updated_at)
    values (new.day_key, new.package, 0, 0, 0, new.started_at);
  update daily_stat set sessions = sessions + 1, updated_at = new.started_at
   where day_key = new.day_key and package = new.package;
end;

create trigger session_close_rollup after update of ended_at on session
  when old.ended_at is null and new.ended_at is not null
begin
  update daily_stat set active_ms = active_ms + new.active_ms, updated_at = new.ended_at
   where day_key = new.day_key and package = new.package;
end;

-- 5 -------------------------------------------------------------------------
-- Reduction targets. Free, no premium gate.
create table goal (
  id             integer primary key autoincrement,
  package        text references tracked_app(package) on delete cascade, -- null = all apps
  metric         text not null check (metric in ('reels','minutes')),
  value          integer not null check (value > 0),          -- today's target
  warn_at_pct    integer not null default 80 check (warn_at_pct between 1 and 99),
  action         text not null default 'notify' check (action in ('notify','block')),
  days_mask      integer not null default 127 check (days_mask between 1 and 127), -- bit 0 = Monday
  active_from_min integer check (active_from_min between 0 and 1439),  -- optional window (bedtime)
  active_to_min   integer check (active_to_min between 0 and 1439),
  snoozes_per_day integer not null default 1 check (snoozes_per_day between 0 and 5),
  -- optional gradual reduction: every 7 days lower value by step_pct, never below floor_value
  step_pct       integer check (step_pct between 1 and 50),
  floor_value    integer check (floor_value > 0),
  next_step_day  text,                                          -- day_key of next reduction
  enabled        integer not null default 1 check (enabled in (0,1)),
  created_at     integer not null,
  updated_at     integer not null,
  check ((active_from_min is null) = (active_to_min is null)),
  check ((step_pct is null) = (floor_value is null)),
  check ((step_pct is null) = (next_step_day is null)),
  check (floor_value is null or floor_value <= value)
);
-- One goal per (app or all, metric) is enforced by GoalDao.save (a transaction):
-- Room cannot declare an index on COALESCE(package, '*'). Plain index for lookups.
create index goal_package_metric on goal (package, metric);

-- 6 -------------------------------------------------------------------------
create table goal_event (
  id             integer primary key autoincrement,
  goal_id        integer not null references goal(id) on delete cascade,
  day_key        text not null,
  at             integer not null,
  kind           text not null check (kind in ('warned','reached','blocked','snoozed','met_day','missed_day','stepped_down')),
  seq            integer not null default 0,   -- 0 for every kind except 'snoozed' (1, 2, ...)
  value_at_event integer not null
);
create index goal_event_day on goal_event (day_key);
-- Each notice fires once per goal per day; snoozes get seq 1..snoozes_per_day,
-- so a plain unique index (which Room can declare) covers both rules.
create unique index goal_event_once on goal_event (goal_id, day_key, kind, seq);
