#!/usr/bin/env python3
"""Python mirror of the pure Kotlin logic in core/ (GoalLogic, Nudge,
StatsLogic, CsvFormat, OverlayPolicy, OemGuides, Retention), run against the
same cases as app/src/test/.../LogicTest.kt. Keep both in step.

    python replica/tests/logic_sim.py      # exit 1 on any failure
"""
import datetime as dt
import math
import random
import re
import sys

FAILS = []


def check(name, cond, detail=""):
    print(("  ok    " if cond else "  FAIL  ") + name + ("" if cond else "  -> " + str(detail)))
    if not cond:
        FAILS.append(name)


# ---------------- GoalLogic ----------------
def goal(**kw):
    g = dict(id=1, pkg=None, metric="reels", value=100, warn=80, action="notify", days=127,
             frm=None, to=None, snoozes=1, step=None, floor=None, next_step=None, enabled=True)
    g.update(kw)
    return g


def is_active(g, dow, minute):
    if not g["enabled"]:
        return False
    if g["days"] & (1 << (dow - 1)) == 0:
        return False
    f, t = g["frm"], g["to"]
    if f is None or t is None or f == t:
        return True
    return f <= minute < t if f < t else (minute >= f or minute < t)


def used(g, reels, ms):
    if g["metric"] == "minutes":
        total = sum(ms.values()) if g["pkg"] is None else ms.get(g["pkg"], 0)
        return total // 60_000
    return sum(reels.values()) if g["pkg"] is None else reels.get(g["pkg"], 0)


def level(u, target, warn):
    if target <= 0:
        return "NONE"
    if u >= target:
        return "REACHED"
    if u * 100 >= target * warn:
        return "WARN"
    return "NONE"


def evaluate(goals, reels, ms, dow, minute):
    out = []
    for g in goals:
        if not is_active(g, dow, minute):
            continue
        u = used(g, reels, ms)
        lv = level(u, g["value"], g["warn"])
        out.append((g["id"], lv, u, lv == "REACHED" and g["action"] == "block"))
    return out


def next_reduced(value, step, floor_v):
    if value <= floor_v:
        return floor_v
    reduced = math.floor(value * (100 - step) / 100.0)
    return max(floor_v, min(value - 1, reduced))


def apply_due_steps(g, today):
    if g["step"] is None or g["floor"] is None or g["next_step"] is None:
        return None
    nxt, value, changed = g["next_step"], g["value"], False
    while nxt <= today:
        value = next_reduced(value, g["step"], g["floor"])
        nxt = (dt.date.fromisoformat(nxt) + dt.timedelta(days=7)).isoformat()
        changed = True
    return (value, nxt) if changed else None


def streak(results, today):
    t = dt.date.fromisoformat(today)
    expected = t - dt.timedelta(days=1)
    n = 0
    for day, met in results:
        d = dt.date.fromisoformat(day)
        if d >= t:
            continue
        if d != expected or not met:
            break
        n += 1
        expected -= dt.timedelta(days=1)
    return n


def nudge_due(active_ms, videos, minutes, video_limit, sent):
    if sent:
        return False
    if minutes > 0 and active_ms >= minutes * 60_000:
        return True
    if video_limit > 0 and videos >= video_limit:
        return True
    return False


# ---------------- StatsLogic ----------------
def range_for(period, today, offset=0):
    if period == "DAY":
        d = today - dt.timedelta(days=offset)
        return d, d
    if period == "WEEK":
        monday = today - dt.timedelta(days=today.weekday()) - dt.timedelta(weeks=offset)
        return monday, monday + dt.timedelta(days=6)
    first = today.replace(day=1)
    y, m = first.year, first.month - offset
    while m <= 0:
        m += 12; y -= 1
    first = dt.date(y, m, 1)
    nxt = dt.date(y + (m // 12), m % 12 + 1, 1)
    return first, nxt - dt.timedelta(days=1)


def daily_series(rows, rng, pkg=None):
    f, t = rng
    out = []
    d = f
    while d <= t:
        r = [x for x in rows if x[0] == d.isoformat() and (pkg is None or x[1] == pkg)]
        out.append((d, sum(x[2] for x in r), sum(x[3] for x in r), sum(x[4] for x in r)))
        d += dt.timedelta(days=1)
    return out


def summary(series, today):
    elapsed = [s for s in series if s[0] <= today]
    total = sum(s[1] for s in series)
    avg = 0 if not elapsed else round_half_up(total / len(elapsed))
    past = [s for s in elapsed if s[0] < today or s[1] > 0]
    best = min(past, key=lambda s: s[1]) if past else None
    worst_pool = [s for s in past if s[1] > 0]
    worst = max(worst_pool, key=lambda s: s[1]) if worst_pool else None
    return total, avg, best, worst


def round_half_up(x):
    # Kotlin roundToInt rounds half up for positives (Math.round)
    return int(math.floor(x + 0.5))


def delta_pct(cur, prev):
    return None if prev <= 0 else round_half_up((cur - prev) * 100.0 / prev)


def timer(ms):
    s = max(0, ms // 1000)
    h, m, sec = s // 3600, (s % 3600) // 60, s % 60
    return "%d:%02d:%02d" % (h, m, sec) if h > 0 else "%d:%02d" % (m, sec)


# ---------------- CsvFormat ----------------
MAGIC = "# Tidemark export v1"
HEADER = "kind,day,package,videos,active_seconds,sessions,started_at,ended_at,end_reason"


def export(daily):
    lines = [MAGIC, HEADER]
    for d, p, v, ms, s in daily:
        lines.append("daily,%s,%s,%d,%d,%d,,," % (d, p, v, ms // 1000, s))
    return "\n".join(lines) + "\n"


def parse_daily(text):
    lines = [l.strip() for l in text.splitlines() if l.strip()]
    if not lines or lines[0] != MAGIC:
        return [], len(lines)
    rows, skipped = [], 0
    for line in lines[1:]:
        if line == HEADER or line.startswith("session,"):
            continue
        f = line.split(",")
        ok = len(f) >= 6 and f[0] == "daily" and re.fullmatch(r"\d{4}-\d{2}-\d{2}", f[1]) and re.fullmatch(r"[A-Za-z0-9_.]+", f[2])
        def toint(x):
            return int(x) if re.fullmatch(r"-?\d+", x or "") else None
        v, sec, ses = (toint(f[3]) if len(f) > 3 else None), (toint(f[4]) if len(f) > 4 else None), (toint(f[5]) if len(f) > 5 else None)
        if not ok or v is None or sec is None or ses is None or v < 0 or sec < 0 or ses < 0:
            skipped += 1
            continue
        rows.append((f[1], f[2], v, sec * 1000, ses))
    return rows, skipped


# ---------------- Policies ----------------
def overlay_visible(mode, engine, in_app, hidden, blocked):
    if blocked or hidden:
        return False
    return {"OFF": False, "AUTO": engine, "ALWAYS": engine or in_app}[mode]


def oem(m):
    m = (m or "").lower().strip()
    if any(k in m for k in ("xiaomi", "redmi", "poco")): return "XIAOMI"
    if "samsung" in m: return "SAMSUNG"
    if any(k in m for k in ("oppo", "realme", "oneplus")): return "OPPO"
    if "vivo" in m or "iqoo" in m: return "VIVO"
    if "huawei" in m or "honor" in m: return "HUAWEI"
    return "OTHER"


def delay_to_next(now, minute, iso_day=None):
    t = dt.datetime.combine(now.date(), dt.time()) + dt.timedelta(minutes=minute)
    if not t > now:
        t += dt.timedelta(days=1)
    if iso_day is not None:
        while t.isoweekday() != iso_day:
            t += dt.timedelta(days=1)
    return int((t - now).total_seconds() * 1000)


def main():
    print("GoalLogic")
    check("active all days, no window", is_active(goal(), 3, 600))
    check("disabled goal inactive", not is_active(goal(enabled=False), 3, 600))
    check("weekday mask: Mon-Fri goal off on Saturday", not is_active(goal(days=0b0011111), 6, 600))
    check("window 22:00-06:00 active at 23:00 and 05:00", is_active(goal(frm=1320, to=360), 1, 1380) and is_active(goal(frm=1320, to=360), 1, 300))
    check("window 22:00-06:00 inactive at 12:00", not is_active(goal(frm=1320, to=360), 1, 720))
    check("window 09:00-17:00 end exclusive", is_active(goal(frm=540, to=1020), 1, 540) and not is_active(goal(frm=540, to=1020), 1, 1020))
    check("levels at 79/80/100 of 100", [level(u, 100, 80) for u in (79, 80, 100)] == ["NONE", "WARN", "REACHED"])
    check("level for zero target is NONE", level(5, 0, 80) == "NONE")
    reels = {"ig": 70, "yt": 40}
    ms = {"ig": 30 * 60_000, "yt": 15 * 60_000 + 59_000}
    d = evaluate([goal(id=1), goal(id=2, pkg="ig", value=60, action="block"), goal(id=3, metric="minutes", value=50)], reels, ms, 1, 600)
    check("all-apps reels 110/100 reached, notify only", d[0] == (1, "REACHED", 110, False), d[0])
    check("per-app ig 70/60 reached and blocks", d[1] == (2, "REACHED", 70, True), d[1])
    check("minutes 45/50 warns (floor of minutes)", d[2] == (3, "WARN", 45, False), d[2])
    check("reduce 100 by 10% -> 90", next_reduced(100, 10, 30) == 90)
    check("reduce never below floor", next_reduced(32, 10, 30) == 30)
    check("reduce at least 1", next_reduced(5, 1, 1) == 4)
    check("at floor stays", next_reduced(30, 10, 30) == 30)
    g = goal(value=100, step=10, floor=30, next_step="2026-11-21")
    check("step not due before its day", apply_due_steps(g, "2026-11-20") is None)
    check("step due on its day", apply_due_steps(g, "2026-11-21") == (90, "2026-11-28"))
    check("three missed weeks apply three steps", apply_due_steps(g, "2026-12-06") == (72, "2026-12-12"), apply_due_steps(g, "2026-12-06"))
    res = [("2026-11-21", True), ("2026-11-20", True), ("2026-11-19", True), ("2026-11-18", False), ("2026-11-17", True)]
    check("streak ignores today and stops at a miss", streak(res, "2026-11-21") == 2)
    check("streak stops at a gap", streak([("2026-11-20", True), ("2026-11-18", True)], "2026-11-21") == 1)
    check("no results, no streak", streak([], "2026-11-21") == 0)
    check("nudge after 20 min", nudge_due(20 * 60_000, 3, 20, 0, False))
    check("nudge after 100 videos", nudge_due(1000, 100, 0, 100, False))
    check("nudge once per session", not nudge_due(99 * 60_000, 999, 20, 100, True))
    check("nudge off when both 0", not nudge_due(99 * 60_000, 999, 0, 0, False))

    print("StatsLogic")
    today = dt.date(2026, 11, 21)  # a Saturday
    check("week is Mon 16 - Sun 22", range_for("WEEK", today) == (dt.date(2026, 11, 16), dt.date(2026, 11, 22)))
    check("previous week", range_for("WEEK", today, 1) == (dt.date(2026, 11, 9), dt.date(2026, 11, 15)))
    check("month November", range_for("MONTH", today) == (dt.date(2026, 11, 1), dt.date(2026, 11, 30)))
    check("previous month across a year", range_for("MONTH", dt.date(2026, 1, 15), 1) == (dt.date(2025, 12, 1), dt.date(2025, 12, 31)))
    check("February 2028 (leap)", range_for("MONTH", dt.date(2028, 2, 10)) == (dt.date(2028, 2, 1), dt.date(2028, 2, 29)))
    rows = [("2026-11-16", "ig", 40, 600_000, 2), ("2026-11-16", "yt", 10, 60_000, 1), ("2026-11-18", "ig", 5, 1, 1),
            ("2026-11-21", "ig", 30, 0, 1), ("2026-11-23", "ig", 99, 0, 1)]
    ser = daily_series(rows, range_for("WEEK", today))
    check("series has 7 days with zeros", len(ser) == 7 and ser[1][1] == 0 and ser[0][1] == 50)
    total, avg, best, worst = summary(ser, today)
    check("total 85 in the week", total == 85)
    check("average over 6 elapsed days = 14", avg == 14, avg)
    check("best day is a zero day before today", best[1] == 0 and best[0] < today)
    check("worst day Monday 50", worst[0] == dt.date(2026, 11, 16) and worst[1] == 50)
    check("per-app series", [s[1] for s in daily_series(rows, range_for("WEEK", today), "yt")] == [10, 0, 0, 0, 0, 0, 0])
    check("delta -18%", delta_pct(82, 100) == -18)
    check("delta none without baseline", delta_pct(5, 0) is None)
    check("timer m:ss", timer(247_000) == "4:07")
    check("timer h:mm:ss", timer(3_729_000) == "1:02:09")

    print("CsvFormat")
    daily = [("2026-11-21", "com.instagram.android", 42, 1_830_000, 3), ("2026-11-20", "com.zhiliaoapp.musically", 0, 0, 1)]
    text = export(daily) + "session,2026-11-21,com.instagram.android,12,410,,2026-11-21T20:14:03Z,2026-11-21T20:20:53Z,left_surface\n"
    rows2, skipped = parse_daily(text)
    check("round trip of daily rows", rows2 == daily and skipped == 0, (rows2, skipped))
    bad = MAGIC + "\n" + HEADER + "\ndaily,2026-13-x,ig,1,1,1\ndaily,2026-11-01,ig,-1,0,0\ndaily,2026-11-01,a b,1,1,1\ndaily,2026-11-02,ok.pkg,3,4,5,,,\n"
    rows3, skipped3 = parse_daily(bad)
    check("malformed rows skipped, valid kept", rows3 == [("2026-11-02", "ok.pkg", 3, 4000, 5)] and skipped3 == 3, (rows3, skipped3))
    check("foreign file rejected", parse_daily("day,count\n2026-11-01,3\n") == ([], 2))
    rnd = random.Random(3)
    ok = True
    for _ in range(500):
        d = [("2026-%02d-%02d" % (rnd.randint(1, 12), rnd.randint(1, 28)), "p.%d" % rnd.randint(0, 9),
              rnd.randint(0, 5000), rnd.randint(0, 10**7) * 1000, rnd.randint(0, 50)) for _ in range(rnd.randint(0, 20))]
        if parse_daily(export(d)) != (d, 0):
            ok = False
    check("round trip fuzz x500", ok)

    print("Policies")
    check("AUTO follows the engine", overlay_visible("AUTO", True, True, False, False) and not overlay_visible("AUTO", False, True, False, False))
    check("ALWAYS shows anywhere in a tracked app", overlay_visible("ALWAYS", False, True, False, False))
    check("OFF never", not overlay_visible("OFF", True, True, False, False))
    check("hidden-until-leave and block win", not overlay_visible("ALWAYS", True, True, True, False) and not overlay_visible("AUTO", True, True, False, True))
    check("OEM families", [oem(x) for x in ["Xiaomi", "POCO", "samsung", "realme", "OnePlus", "vivo", "HONOR", "Google", None]]
          == ["XIAOMI", "XIAOMI", "SAMSUNG", "OPPO", "OPPO", "VIVO", "HUAWEI", "OTHER", "OTHER"])

    print("Schedule")
    sat2000 = dt.datetime(2026, 11, 21, 20, 0)
    check("next 21:00 in 1 h", delay_to_next(sat2000, 21 * 60) == 3_600_000)
    check("exactly now means tomorrow", delay_to_next(sat2000, 20 * 60) == 86_400_000)
    check("Sunday 21:00 in 25 h", delay_to_next(sat2000, 21 * 60, 7) == 25 * 3_600_000)
    check("next Saturday 19:00", delay_to_next(sat2000, 19 * 60, 6) == (6 * 24 + 23) * 3_600_000)

    print()
    print("%d failing" % len(FAILS))
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main())
