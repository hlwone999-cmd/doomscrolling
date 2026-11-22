#!/usr/bin/env python3
"""Runs the app's real SQL against SQLite, without Android.

Pulls the trigger SQL from AppDatabase.kt and every @Query from Daos.kt
verbatim, builds tables equivalent to what Room generates from
Entities.kt, and checks the data-layer cases in replica/test-plan.md.

    python replica/tests/check_data_layer.py      # exit 1 on any failure
"""
import os
import re
import sqlite3
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SRC = os.path.join(ROOT, "android", "app", "src", "main", "java", "app", "tidemark", "data")

# Equivalent of Room's generated DDL for Entities.kt (columns, nullability, FKs, indices).
DDL = """
CREATE TABLE tracked_app (package TEXT NOT NULL, display_name TEXT NOT NULL, surface_kind TEXT NOT NULL,
  enabled INTEGER NOT NULL, last_seen_version_code INTEGER, added_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
  PRIMARY KEY(package));
CREATE TABLE session (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, package TEXT NOT NULL, surface_kind TEXT NOT NULL,
  day_key TEXT NOT NULL, tz_offset_min INTEGER NOT NULL, started_at INTEGER NOT NULL, last_event_at INTEGER NOT NULL,
  ended_at INTEGER, reel_count INTEGER NOT NULL, active_ms INTEGER NOT NULL, ended_reason TEXT,
  rules_version INTEGER NOT NULL, target_version_code INTEGER, is_open INTEGER, created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  FOREIGN KEY(package) REFERENCES tracked_app(package) ON UPDATE NO ACTION ON DELETE CASCADE);
CREATE INDEX index_session_package_started_at ON session (package, started_at);
CREATE INDEX index_session_day_key ON session (day_key);
CREATE INDEX index_session_started_at ON session (started_at);
CREATE UNIQUE INDEX index_session_is_open ON session (is_open);
CREATE TABLE reel_event (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, session_id INTEGER NOT NULL, at INTEGER NOT NULL,
  item_key TEXT NOT NULL, dwell_ms INTEGER,
  FOREIGN KEY(session_id) REFERENCES session(id) ON UPDATE NO ACTION ON DELETE CASCADE);
CREATE INDEX index_reel_event_at ON reel_event (at);
CREATE UNIQUE INDEX index_reel_event_session_id_item_key ON reel_event (session_id, item_key);
CREATE TABLE daily_stat (day_key TEXT NOT NULL, package TEXT NOT NULL, reels INTEGER NOT NULL, active_ms INTEGER NOT NULL,
  sessions INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(day_key, package),
  FOREIGN KEY(package) REFERENCES tracked_app(package) ON UPDATE NO ACTION ON DELETE CASCADE);
CREATE INDEX index_daily_stat_package_day_key ON daily_stat (package, day_key);
CREATE TABLE goal (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, package TEXT, metric TEXT NOT NULL, value INTEGER NOT NULL,
  warn_at_pct INTEGER NOT NULL, action TEXT NOT NULL, days_mask INTEGER NOT NULL, active_from_min INTEGER,
  active_to_min INTEGER, snoozes_per_day INTEGER NOT NULL, step_pct INTEGER, floor_value INTEGER, next_step_day TEXT,
  enabled INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
  FOREIGN KEY(package) REFERENCES tracked_app(package) ON UPDATE NO ACTION ON DELETE CASCADE);
CREATE INDEX index_goal_package_metric ON goal (package, metric);
CREATE TABLE goal_event (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, goal_id INTEGER NOT NULL, day_key TEXT NOT NULL,
  at INTEGER NOT NULL, kind TEXT NOT NULL, seq INTEGER NOT NULL, value_at_event INTEGER NOT NULL,
  FOREIGN KEY(goal_id) REFERENCES goal(id) ON UPDATE NO ACTION ON DELETE CASCADE);
CREATE UNIQUE INDEX index_goal_event_goal_id_day_key_kind_seq ON goal_event (goal_id, day_key, kind, seq);
CREATE INDEX index_goal_event_day_key ON goal_event (day_key);
"""


def kotlin_strings(path):
    text = open(path, encoding="utf-8").read()
    return text


def triggers():
    text = kotlin_strings(os.path.join(SRC, "AppDatabase.kt"))
    found = re.findall(r'"""(CREATE TRIGGER.*?)"""', text, flags=re.S)
    assert len(found) == 3, "expected 3 triggers in AppDatabase.kt, found %d" % len(found)
    return found


def queries():
    text = kotlin_strings(os.path.join(SRC, "Daos.kt"))
    out = {}
    pattern = re.compile(r'@Query\(\s*(?:"""(.*?)"""|"(.*?)")\s*\)\s*(?:abstract\s+)?(?:suspend\s+)?fun\s+(\w+)', re.S)
    daos = [(m.start(), m.group(1)) for m in re.finditer(r"(?:interface|abstract class)\s+(\w+Dao)", text)]
    for m in pattern.finditer(text):
        triple, single, name = m.groups()
        dao = [d for pos, d in daos if pos < m.start()][-1]
        sql = (triple or single).strip()
        out["%s.%s" % (dao, name)] = sql
        out.setdefault(name, sql)  # unqualified = first DAO that declares it
    return out


def seed_sql():
    text = kotlin_strings(os.path.join(SRC, "AppDatabase.kt"))
    m = re.search(r'"(INSERT OR IGNORE INTO tracked_app[^"]*)"', text)
    return m.group(1)


FAILS = []


def check(name, cond, detail=""):
    print(("  ok    " if cond else "  FAIL  ") + name + ("" if cond else "  -> " + str(detail)))
    if not cond:
        FAILS.append(name)


def fresh():
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA foreign_keys = ON")
    db.executescript(DDL)
    for t in triggers():
        db.execute(t)
    for pkg, name, surface in [("com.instagram.android", "Instagram", "reels"),
                               ("com.google.android.youtube", "YouTube", "shorts")]:
        db.execute(seed_sql(), (pkg, name, surface, 1, 1, 1))
    return db


Q = queries()


def open_session(db, pkg="com.instagram.android", day="2026-11-21", at=1000, is_open=1):
    cur = db.execute(
        "INSERT INTO session (package, surface_kind, day_key, tz_offset_min, started_at, last_event_at, ended_at,"
        " reel_count, active_ms, ended_reason, rules_version, target_version_code, is_open, created_at, updated_at)"
        " VALUES (?, 'reels', ?, 420, ?, ?, NULL, 0, 0, NULL, 1, NULL, ?, ?, ?)",
        (pkg, day, at, at, is_open, at, at))
    return cur.lastrowid


def count(db, sid, key, at):
    cur = db.execute("INSERT OR IGNORE INTO reel_event (session_id, at, item_key, dwell_ms) VALUES (?, ?, ?, 400)",
                     (sid, at, key))
    return cur.rowcount == 1


def stat(db, day="2026-11-21", pkg="com.instagram.android"):
    return db.execute("SELECT reels, active_ms, sessions FROM daily_stat WHERE day_key=? AND package=?",
                      (day, pkg)).fetchone()


def main():
    print("SQLite", sqlite3.sqlite_version)
    for t in triggers():
        check("trigger has no UPSERT (Android 8-10 safe)", "ON CONFLICT" not in t.upper())

    print("DB-1 one open session at a time")
    db = fresh()
    open_session(db)
    try:
        open_session(db, at=2000)
        check("second open session rejected", False, "insert succeeded")
    except sqlite3.IntegrityError:
        check("second open session rejected", True)

    print("DB-2 dedupe and rollups")
    db = fresh(); sid = open_session(db)
    results = [count(db, sid, k, 1100 + i) for i, k in enumerate(["i:0", "i:1", "i:1", "i:0", "i:2"])]
    check("duplicate keys ignored", results == [True, True, False, False, True], results)
    check("session.reel_count = 3", db.execute("SELECT reel_count FROM session").fetchone()[0] == 3)
    check("daily_stat reels 3, sessions 1", stat(db)[0] == 3 and stat(db)[2] == 1, stat(db))
    check("totalFor = 3", db.execute(Q["totalFor"], {"dayKey": "2026-11-21"}).fetchone()[0] == 3)
    check("last_event_at advanced", db.execute("SELECT last_event_at FROM session").fetchone()[0] == 1104)

    print("DB-3 close adds active time once")
    n = db.execute(Q["close"], {"id": sid, "endedAt": 9000, "reason": "left_surface", "activeMs": 8000, "now": 9000}).rowcount
    check("close updated 1 row", n == 1)
    n2 = db.execute(Q["close"], {"id": sid, "endedAt": 9500, "reason": "left_surface", "activeMs": 8500, "now": 9500}).rowcount
    check("second close is a no-op", n2 == 0)
    check("daily_stat active_ms = 8000", stat(db)[1] == 8000, stat(db))
    check("is_open cleared", db.execute("SELECT is_open FROM session").fetchone()[0] is None)
    sid2 = open_session(db, at=10_000)
    check("new session opens after close", sid2 == 2)
    check("sessions = 2", stat(db)[2] == 2)

    print("DB-4 crash recovery (closeDangling)")
    db = fresh(); sid = open_session(db, at=1000)
    count(db, sid, "i:0", 4000)
    db.execute(Q["touch"], {"id": sid, "at": 7000, "activeMs": 0})
    n = db.execute(Q["closeDangling"], {"now": 99_999}).rowcount
    row = db.execute("SELECT ended_at, ended_reason, active_ms, is_open FROM session").fetchone()
    check("dangling session closed", n == 1)
    check("ends at last activity, not at boot", row[0] == 7000, row)
    check("active_ms = last_event_at - started_at", row[2] == 6000, row)
    check("reason service_stopped", row[1] == "service_stopped")
    check("rolled into daily_stat once", stat(db)[1] == 6000, stat(db))
    check("closeDangling twice is a no-op", db.execute(Q["closeDangling"], {"now": 100_000}).rowcount == 0)

    print("DB-5 touch only affects open sessions")
    db.execute(Q["touch"], {"id": sid, "at": 50_000, "activeMs": 0})
    check("closed session not touched", db.execute("SELECT last_event_at FROM session").fetchone()[0] == 7000)

    print("DB-6 byApp read model")
    db = fresh()
    a = open_session(db, "com.instagram.android", at=1000)
    for i in range(5): count(db, a, "i:%d" % i, 1000 + i)
    db.execute(Q["close"], {"id": a, "endedAt": 2000, "reason": "app_switch", "activeMs": 1000, "now": 2000})
    b = open_session(db, "com.google.android.youtube", at=3000)
    for i in range(2): count(db, b, "i:%d" % i, 3000 + i)
    rows = db.execute(Q["byApp"], {"dayKey": "2026-11-21"}).fetchall()
    check("byApp ordered by reels desc", [r[0] for r in rows] == ["com.instagram.android", "com.google.android.youtube"], rows)
    check("byApp has display names", rows[0][1] == "Instagram", rows)

    print("DB-7 demo data path (is_open NULL, then finishDemo)")
    db = fresh()
    d = open_session(db, at=1000, is_open=None)
    real = open_session(db, at=1500)  # a real open session must not collide with demo rows
    for i in range(3): count(db, d, "i:demo-%d" % i, 1000 + i)
    db.execute(Q["finishDemo"], {"id": d, "endedAt": 5000, "activeMs": 4000})
    check("demo coexists with a real open session", real == 2)
    check("demo rolled up: 3 reels, 4000 ms", stat(db)[0] == 3 and stat(db)[1] == 4000, stat(db))

    print("DB-8 cascade delete of a tracked app")
    db.execute("DELETE FROM tracked_app WHERE package='com.instagram.android'")
    left = db.execute("SELECT (SELECT COUNT(*) FROM session) + (SELECT COUNT(*) FROM reel_event) + (SELECT COUNT(*) FROM daily_stat)").fetchone()[0]
    check("sessions, events and stats removed", left == 0, left)

    print("DB-9 day key isolation")
    db = fresh()
    s1 = open_session(db, day="2026-11-21", at=1000); count(db, s1, "i:0", 1001)
    db.execute(Q["close"], {"id": s1, "endedAt": 2000, "reason": "day_rollover", "activeMs": 1000, "now": 2000})
    s2 = open_session(db, day="2026-11-22", at=3000); count(db, s2, "i:0", 3001)
    check("same key counts again on a new day (by design)",
          db.execute(Q["totalFor"], {"dayKey": "2026-11-22"}).fetchone()[0] == 1)
    check("previous day unchanged", db.execute(Q["totalFor"], {"dayKey": "2026-11-21"}).fetchone()[0] == 1)

    print("DB-10 live active time of the open session (C-6)")
    db = fresh(); sid = open_session(db, at=1000)
    db.execute(Q["touch"], {"id": sid, "at": 6000, "activeMs": 4500})
    db.execute(Q["touch"], {"id": sid, "at": 7000, "activeMs": 4000})   # never goes backwards
    live = db.execute(Q["openActive"], {"dayKey": "2026-11-21"}).fetchall()
    check("openActive reports live ms", live == [("com.instagram.android", 4500)], live)
    check("daily_stat not yet credited while open", stat(db)[1] == 0, stat(db))
    db.execute(Q["close"], {"id": sid, "endedAt": 8000, "reason": "left_surface", "activeMs": 5000, "now": 8000})
    check("credited once on close", stat(db)[1] == 5000, stat(db))
    check("openActive empty after close", db.execute(Q["openActive"], {"dayKey": "2026-11-21"}).fetchall() == [])

    print("DB-11 crash recovery prefers the touched active time")
    db = fresh(); sid = open_session(db, at=1000)
    db.execute(Q["touch"], {"id": sid, "at": 31_000, "activeMs": 12_000})   # user was away part of the time
    db.execute(Q["closeDangling"], {"now": 99_000})
    check("active_ms = touched value, not wall time", db.execute("SELECT active_ms FROM session").fetchone()[0] == 12_000)

    print("DB-12 retention prune keeps totals and the open session")
    db = fresh(); a = open_session(db, at=1000)
    for i in range(3): count(db, a, "i:%d" % i, 1000 + i)
    db.execute(Q["close"], {"id": a, "endedAt": 2000, "reason": "left_surface", "activeMs": 1000, "now": 2000})
    b = open_session(db, at=3000); count(db, b, "i:0", 3001)
    n = db.execute(Q["pruneBefore"], {"cutoff": 10_000}).rowcount
    check("old closed-session events pruned", n == 3, n)
    check("open session events kept", db.execute("SELECT COUNT(*) FROM reel_event").fetchone()[0] == 1)
    check("totals unchanged", stat(db)[0] == 4, stat(db))

    print("DB-13 delete a period and delete all")
    db = fresh()
    for d, at in [("2026-11-19", 100), ("2026-11-20", 200), ("2026-11-21", 300)]:
        x = open_session(db, day=d, at=at); count(db, x, "i:0", at + 1)
        db.execute(Q["close"], {"id": x, "endedAt": at + 5, "reason": "left_surface", "activeMs": 5, "now": at + 5})
    open_session(db, day="2026-11-21", at=400)
    db.execute(Q["SessionDao.deleteDays"], {"fromDay": "2026-11-19", "toDay": "2026-11-20"})
    check("sessions of the period gone, others kept", db.execute("SELECT COUNT(*) FROM session").fetchone()[0] == 2)
    check("events of the period cascade", db.execute("SELECT COUNT(*) FROM reel_event").fetchone()[0] == 1)
    db.execute(Q["SessionDao.deleteDays"], {"fromDay": "2026-11-21", "toDay": "2026-11-21"})
    check("open session survives a period delete",
          db.execute("SELECT COUNT(*) FROM session WHERE is_open = 1").fetchone()[0] == 1)
    db.execute(Q["deleteAllClosed"])
    check("delete all keeps only the open session", db.execute("SELECT COUNT(*) FROM session").fetchone()[0] == 1)
    db.execute(Q["StatsDao.deleteDays"], {"fromDay": "2026-11-19", "toDay": "2026-11-20"})
    check("stats of the period deleted",
          db.execute("SELECT COUNT(*) FROM daily_stat WHERE day_key < '2026-11-21'").fetchone()[0] == 0)

    print("DB-14 hourly times for a day (times only)")
    db = fresh(); a = open_session(db, at=1000)
    for i in range(3): count(db, a, "i:%d" % i, 5000 + i)
    times = [r[0] for r in db.execute(Q["timesForDay"], {"dayKey": "2026-11-21", "pkg": None})]
    check("all apps", times == [5000, 5001, 5002], times)
    times = [r[0] for r in db.execute(Q["timesForDay"], {"dayKey": "2026-11-21", "pkg": "com.google.android.youtube"})]
    check("filtered by app", times == [], times)

    print("DB-15 goal notices fire once per day, snoozes repeat")
    db = fresh()
    db.execute("INSERT INTO goal (package, metric, value, warn_at_pct, action, days_mask, snoozes_per_day, enabled, created_at, updated_at)"
               " VALUES (NULL, 'reels', 100, 80, 'block', 127, 2, 1, 0, 0)")
    def ev(kind, seq=0):
        return db.execute("INSERT OR IGNORE INTO goal_event (goal_id, day_key, at, kind, seq, value_at_event)"
                          " VALUES (1, '2026-11-21', 0, ?, ?, 80)", (kind, seq)).rowcount
    check("first warning stored", ev("warned") == 1)
    check("second warning same day ignored", ev("warned") == 0)
    check("snoozes 1 and 2 stored", ev("snoozed", 1) == 1 and ev("snoozed", 2) == 1)
    check("countKind sees 2 snoozes",
          db.execute(Q["countKind"], {"goalId": 1, "dayKey": "2026-11-21", "kind": "snoozed"}).fetchone()[0] == 2)
    check("byScope finds the all-apps goal with IS NULL",
          db.execute(Q["byScope"], {"pkg": None, "metric": "reels"}).fetchone() is not None)
    check("byScope does not match a per-app scope",
          db.execute(Q["byScope"], {"pkg": "com.instagram.android", "metric": "reels"}).fetchone() is None)
    db.execute("DELETE FROM tracked_app WHERE package = 'com.instagram.android'")
    check("all-apps goal survives deleting an app", db.execute("SELECT COUNT(*) FROM goal").fetchone()[0] == 1)

    print("DB-16 import never overwrites live days")
    db = fresh(); a = open_session(db, at=1000); count(db, a, "i:0", 1001)
    db.execute("INSERT OR IGNORE INTO daily_stat (day_key, package, reels, active_ms, sessions, updated_at) VALUES "
               "('2026-11-21', 'com.instagram.android', 99, 0, 0, 0), ('2026-11-01', 'com.instagram.android', 7, 0, 1, 0)")
    check("existing day kept, missing day added", stat(db)[0] == 1 and stat(db, day="2026-11-01")[0] == 7,
          (stat(db), stat(db, "2026-11-01")))

    print("DB-17 range and recent queries")
    rows = db.execute(Q["rangeOnce"], {"fromDay": "2026-11-01", "toDay": "2026-11-30"}).fetchall()
    check("range returns both days in order", [r[0] for r in rows] == ["2026-11-01", "2026-11-21"], rows)
    check("recent excludes the open session", db.execute(Q["recent"], {"limit": 10}).fetchall() == [])

    print()
    print("%d failing" % len(FAILS))
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main())
