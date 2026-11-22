#!/usr/bin/env python3
"""Line-for-line Python port of detect/CountingEngine.kt, for checking its
logic without an Android toolchain.

1. Runs the same scenarios as app/src/test/.../CountingEngineTest.kt.
2. Fuzzes random event sequences and checks the counting rules always hold.

Keep in step with CountingEngine.kt: if the Kotlin changes, change this too
(the port mirrors it method by method, same names).

    python replica/tests/engine_sim.py          # exit 1 on any failure
"""
import random
import sys

LEFT_SURFACE, APP_SWITCH, SCREEN_OFF, SERVICE_STOPPED, PAUSED, DAY_ROLLOVER = (
    "left_surface", "app_switch", "screen_off", "service_stopped", "paused", "day_rollover")


class Cfg:
    def __init__(self, settle=400, grace=30_000, hide=1_500, touch=5_000):
        self.settleMs, self.graceMs, self.hideDelayMs, self.touchEveryMs = settle, grace, hide, touch


class Open:
    def __init__(self, token, pkg, surface, dayKey, startedAt):
        self.token, self.pkg, self.surface, self.dayKey, self.startedAt = token, pkg, surface, dayKey, startedAt
        self.counted = set()
        self.keyKind = None
        self.activeMs = 0
        self.onSurfaceSince = None
        self.leftAt = None
        self.lastTouchAt = startedAt
        self.mayCount = True


class Engine:
    def __init__(self, config, sink, dayKeyOf):
        self.config, self.sink, self.dayKeyOf = config, sink, dayKeyOf
        self.open = None
        self.nextToken = 1
        self.pendingKey = None
        self.pendingSince = 0
        self.overlayShown = False
        self.hideAt = None

    @property
    def isOnSurface(self):
        return self.open is not None and self.open.onSurfaceSince is not None

    def onSurface(self, pkg, surface, now):
        o = self.open
        if o is not None and o.pkg != pkg:
            self._close(o, now, APP_SWITCH); o = None
        left = o.leftAt if o else None
        if o is not None and o.onSurfaceSince is None and left is not None and now - left >= self.config.graceMs:
            self._close(o, left, LEFT_SURFACE); o = None
        if o is not None and o.dayKey != self.dayKeyOf(now):
            end = (o.leftAt if o.leftAt is not None else now) if o.onSurfaceSince is None else now
            self._close(o, end, DAY_ROLLOVER); o = None
        if o is None:
            o = Open(self.nextToken, pkg, surface, self.dayKeyOf(now), now)
            self.nextToken += 1
            self.open = o
            self.sink.openSession(o.token, pkg, surface, now)
        if o.onSurfaceSince is None:
            o.onSurfaceSince = now
            o.leftAt = None
            o.mayCount = True
        self.hideAt = None
        self._setOverlay(True)

    def onOffSurface(self, now):
        o = self.open
        if o is not None:
            since = o.onSurfaceSince
            if since is not None:
                o.activeMs += now - since
                o.onSurfaceSince = None
                o.leftAt = now
        self.pendingKey = None
        if self.overlayShown and self.hideAt is None:
            self.hideAt = now + self.config.hideDelayMs

    def onScroll(self, now):
        o = self.open
        if o is None or o.onSurfaceSince is None:
            return
        o.mayCount = True
        self.pendingKey = None

    def onObserve(self, key, now):
        o = self.open
        if o is None or o.onSurfaceSince is None:
            return
        if key is None:
            self.pendingKey = None
            return
        kind = o.keyKind
        if kind is not None and key[:1] != kind:
            return
        if key == self.pendingKey:
            return
        self.pendingKey = key
        self.pendingSince = now

    def onScreenOff(self, now):
        self.onOffSurface(now)
        if self.open is not None:
            it = self.open
            self._close(it, it.leftAt if it.leftAt is not None else now, SCREEN_OFF)
        self._hideOverlayNow()

    def stop(self, now, reason):
        it = self.open
        if it is not None:
            if it.onSurfaceSince is not None:
                self.onOffSurface(now)
            self._close(it, it.leftAt if it.leftAt is not None else now, reason)
        self._hideOverlayNow()

    def tick(self, now):
        if self.hideAt is not None and now >= self.hideAt:
            self._hideOverlayNow()
        o = self.open
        if o is None:
            return
        left = o.leftAt
        if o.onSurfaceSince is None and left is not None and now - left >= self.config.graceMs:
            self._close(o, left, LEFT_SURFACE)
            return
        if o.onSurfaceSince is None:
            return
        if o.dayKey != self.dayKeyOf(now):
            pkg, surface = o.pkg, o.surface
            self.onOffSurface(now)
            self._close(o, now, DAY_ROLLOVER)
            self.onSurface(pkg, surface, now)
            return
        key = self.pendingKey
        if key is not None and now - self.pendingSince >= self.config.settleMs:
            self.pendingKey = None
            self._commit(o, key, now)
        if now - o.lastTouchAt >= self.config.touchEveryMs:
            o.lastTouchAt = now
            self.sink.touchSession(o.token, now, self._activeMsAt(o, now))

    def sessionActiveMs(self, now):
        return self._activeMsAt(self.open, now) if self.open is not None else 0

    def _activeMsAt(self, o, now):
        return o.activeMs + ((now - o.onSurfaceSince) if o.onSurfaceSince is not None else 0)

    def nextDeadline(self):
        o = self.open
        best = self.hideAt
        def consider(t):
            nonlocal best
            best = t if best is None else min(best, t)
        if self.pendingKey is not None:
            consider(self.pendingSince + self.config.settleMs)
        if o is not None:
            if o.onSurfaceSince is None and o.leftAt is not None:
                consider(o.leftAt + self.config.graceMs)
            if o.onSurfaceSince is not None:
                consider(o.lastTouchAt + self.config.touchEveryMs)
        return best

    def _commit(self, o, key, now):
        if not o.mayCount:
            return
        o.mayCount = False
        if key in o.counted:
            return
        o.counted.add(key)
        if o.keyKind is None:
            o.keyKind = key[0]
        self.sink.countVideo(o.token, key, now, now - self.pendingSince)

    def _close(self, o, endedAt, reason):
        since = o.onSurfaceSince
        if since is not None:
            o.activeMs += endedAt - since
            o.onSurfaceSince = None
        self.sink.closeSession(o.token, endedAt, o.activeMs, reason)
        if self.open is o:
            self.open = None
        self.pendingKey = None

    def _setOverlay(self, visible):
        if self.overlayShown == visible:
            return
        self.overlayShown = visible
        self.sink.overlayVisible(visible)

    def _hideOverlayNow(self):
        self.hideAt = None
        self._setOverlay(False)


class Sink:
    def __init__(self):
        self.opened, self.counted, self.closed, self.activeMs = [], [], [], []
        self.overlay, self.touches = False, 0
        self.openTokens = set()
    def openSession(self, token, pkg, surface, at):
        self.opened.append((token, pkg)); self.openTokens.add(token)
        self.startedAt = getattr(self, "startedAt", {}); self.startedAt[token] = at
    def countVideo(self, token, key, at, dwell):
        self.counted.append((token, key, at, dwell))
    def touchSession(self, token, at, active):
        self.touches += 1
        self.touchActive = getattr(self, "touchActive", []) + [active]
    def closeSession(self, token, endedAt, activeMs, reason):
        self.closed.append((token, endedAt, reason)); self.activeMs.append(activeMs); self.openTokens.discard(token)
    def overlayVisible(self, v):
        self.overlay = v


FAILS = []


def check(name, cond, detail=""):
    print(("  ok    " if cond else "  FAIL  ") + name + ("" if cond else "  -> " + str(detail)))
    if not cond:
        FAILS.append(name)


IG, YT = "com.instagram.android", "com.google.android.youtube"


def make():
    state = {"day": "2026-11-21"}
    s = Sink()
    e = Engine(Cfg(), s, lambda _t: state["day"])
    return e, s, state


def settle(e, key, at):
    e.onObserve(key, at)
    e.tick(at + 400)


def scenarios():
    print("Scenarios mirrored from CountingEngineTest.kt")
    e, s, _ = make(); e.onSurface(IG, "reels", 0); e.onObserve("i:0", 10); e.tick(300)
    a = len(s.counted); e.tick(410)
    check("firstVideoCountsAfterSettle", a == 0 and [c[1] for c in s.counted] == ["i:0"] and s.overlay)

    e, s, _ = make(); e.onSurface(IG, "reels", 0); e.onScroll(10); e.onObserve("i:1", 100); e.onScroll(200)
    e.onObserve("i:2", 250); e.onScroll(350); e.onObserve("i:3", 400); e.tick(900)
    check("flingPastDoesNotCount", [c[1] for c in s.counted] == ["i:3"], s.counted)

    e, s, _ = make(); e.onSurface(IG, "reels", 0); settle(e, "i:0", 0); e.onScroll(1000); settle(e, "i:1", 1100)
    e.onScroll(3000); settle(e, "i:0", 3100)
    check("swipingBackDoesNotRecount", [c[1] for c in s.counted] == ["i:0", "i:1"], s.counted)

    e, s, _ = make(); e.onSurface(IG, "reels", 0); settle(e, "h:aaaaaaaaaaaaaaaa", 0); settle(e, "h:bbbbbbbbbbbbbbbb", 2000)
    check("keyChangeWithoutScrollDoesNotRecount", len(s.counted) == 1)

    e, s, _ = make(); e.onSurface(IG, "reels", 0); settle(e, "h:aaaaaaaaaaaaaaaa", 0)
    e.onScroll(1000); settle(e, "h:cccccccccccccccc", 1100); e.onScroll(2000); settle(e, "h:aaaaaaaaaaaaaaaa", 2100)
    settle(e, "h:dddddddddddddddd", 4000)
    check("swipeBackThenKeyChangeDoesNotCount", len(s.counted) == 2, s.counted)

    e, s, _ = make(); e.onSurface(IG, "reels", 0)
    for i in range(5): e.onObserve("i:7", 10 + i)
    e.tick(500); e.tick(900); e.tick(5000)
    check("duplicateObservationsCountOnce", len(s.counted) == 1)

    e, s, _ = make(); e.onSurface(IG, "reels", 0); settle(e, "i:0", 0); e.onOffSurface(1000); e.tick(2600)
    hidden = not s.overlay
    e.onSurface(IG, "reels", 10_000); shown = s.overlay; settle(e, "i:0", 10_000)
    check("shortLeaveKeepsSessionAndDedupe", hidden and shown and len(s.opened) == 1 and len(s.counted) == 1 and not s.closed)

    e, s, _ = make(); e.onSurface(IG, "reels", 0); settle(e, "i:0", 0); e.onOffSurface(20_000); e.tick(50_000)
    ok = s.closed == [(1, 20_000, LEFT_SURFACE)] and s.activeMs == [20_000]
    e.onSurface(IG, "reels", 60_000)
    check("longLeaveClosesAtLeaveTime", ok and len(s.opened) == 2, (s.closed, s.activeMs))

    e, s, _ = make(); e.onSurface(IG, "reels", 0); e.onOffSurface(1_000); e.onSurface(IG, "reels", 40_000)
    check("returnAfterGraceWithoutTickStillStartsNewSession", s.closed[0][2] == LEFT_SURFACE and len(s.opened) == 2)

    e, s, _ = make(); e.onSurface(IG, "reels", 0); e.onSurface(YT, "shorts", 5_000)
    check("appSwitchClosesAndOpens", s.closed[0][2] == APP_SWITCH and s.opened == [(1, IG), (2, YT)])

    e, s, _ = make(); e.onSurface(IG, "reels", 0); settle(e, "i:0", 0); e.onScroll(1000); settle(e, "h:aaaaaaaaaaaaaaaa", 1100)
    check("mixedKeyKindsIgnoredInOneSession", len(s.counted) == 1)

    e, s, _ = make(); e.onSurface(IG, "reels", 0); e.onObserve(None, 0); e.tick(5_000)
    check("nullKeyNeverCounts", not s.counted)

    e, s, _ = make(); e.onSurface(IG, "reels", 0); e.onScreenOff(8_000)
    check("screenOffClosesImmediately", s.closed == [(1, 8_000, SCREEN_OFF)] and not s.overlay)

    e, s, st = make(); e.onSurface(IG, "reels", 0); settle(e, "i:0", 0); st["day"] = "2026-11-22"; e.tick(10_000)
    ok = s.closed[0][2] == DAY_ROLLOVER and len(s.opened) == 2
    settle(e, "i:0", 10_000)
    check("dayRolloverSplitsSession", ok and len(s.counted) == 2)

    e, s, st = make(); e.onSurface(IG, "reels", 0); e.onOffSurface(1_000); st["day"] = "2026-11-22"
    e.onSurface(IG, "reels", 20_000)
    check("dayRolloverDuringGraceEndsAtLeaveTime (BUG-001)",
          s.closed == [(1, 1_000, DAY_ROLLOVER)] and s.activeMs == [1_000], (s.closed, s.activeMs))

    e, s, _ = make(); e.onSurface(IG, "reels", 1_000); e.onObserve("i:0", 2_000)
    check("nextDeadlineTracksPendingSettle", e.nextDeadline() == 2_400, e.nextDeadline())

    e, s, _ = make(); e.stop(0, PAUSED)
    check("stopWhenIdleDoesNothing", not s.closed and e.nextDeadline() is None)

    e, s, _ = make(); e.onSurface(IG, "reels", 0); e.tick(5_000); e.tick(10_000)
    check("touchesWhileWatching", s.touches == 2 and s.touchActive == [5_000, 10_000], (s.touches, getattr(s, "touchActive", None)))

    e, s, _ = make(); e.onSurface(IG, "reels", 0); e.onOffSurface(4_000); e.onSurface(IG, "reels", 10_000)
    a1 = e.sessionActiveMs(13_000); e.tick(15_000)
    check("liveActiveTimeExcludesTimeAway", a1 == 7_000 and s.touchActive[-1] == 9_000, (a1, s.touchActive))


def fuzz(runs=3000, steps=200, seed=7):
    """Random signal sequences, as the service would send them (always ticking at
    nextDeadline), checking invariants after every step."""
    print("Fuzz: %d runs x %d steps" % (runs, steps))
    rnd = random.Random(seed)
    problems = {}

    def bad(name, detail):
        problems.setdefault(name, detail)

    for run in range(runs):
        st = {"day": "d0"}
        s = Sink()
        e = Engine(Cfg(), s, lambda _t: st["day"])
        now = 0
        scrolls_since_count = {}   # token -> scrolls/entries since its last count
        last_count_at = {}
        for _ in range(steps):
            # Fire every due deadline first, exactly like ReelService.schedule().
            for _guard in range(10):
                d = e.nextDeadline()
                if d is None or d > now:
                    break
                e.tick(d)
            before = len(s.counted)
            tok_before = e.open.token if e.open else None
            entering = e.open is None or not e.isOnSurface
            r = rnd.random()
            if r < 0.10:
                e.onSurface(rnd.choice([IG, YT]), "x", now)
            elif r < 0.17:
                e.onOffSurface(now)
            elif r < 0.37:
                e.onScroll(now)
            elif r < 0.80:
                key = rnd.choice([None, "i:%d" % rnd.randint(0, 6), "h:%016x" % rnd.randint(0, 6)])
                e.onObserve(key, now)
            elif r < 0.82:
                e.onScreenOff(now)
            elif r < 0.83:
                st["day"] = "d%d" % (int(st["day"][1:]) + 1)
            elif r < 0.84:
                e.stop(now, PAUSED)
            now += rnd.choice([0, 1, 50, 150, 300, 400, 450, 1000, 2000, 31_000])

        # Invariants over the whole run
        per_token = {}
        for tok, key, at, dwell in s.counted:
            per_token.setdefault(tok, []).append((key, at, dwell))
        for tok, items in per_token.items():
            keys = [k for k, _, _ in items]
            if len(keys) != len(set(keys)):
                bad("a key counted twice in one session", (run, tok, keys))
            kinds = {k[0] for k in keys}
            if len(kinds) > 1:
                bad("mixed key kinds in one session", (run, tok, keys))
            if any(d < 400 for _, _, d in items):
                bad("counted before settle", (run, tok, items))
        closed_tokens = [c[0] for c in s.closed]
        if len(closed_tokens) != len(set(closed_tokens)):
            bad("a session closed twice", (run, s.closed))
        opened_tokens = [o[0] for o in s.opened]
        never_closed = set(opened_tokens) - set(closed_tokens)
        if len(never_closed) > 1:
            bad("more than one session left open", (run, never_closed))
        for (tok, ended, reason), active in zip(s.closed, s.activeMs):
            if active < 0:
                bad("negative active time", (run, tok, active))
            if ended < s.startedAt[tok]:
                bad("session ends before it starts", (run, tok, ended, s.startedAt[tok]))
            if active > ended - s.startedAt[tok]:
                bad("active time longer than the session", (run, tok, active, ended - s.startedAt[tok], reason))
        for tok, key, at, dwell in s.counted:
            if tok not in opened_tokens or (tok in closed_tokens and
                                            at > [c for c in s.closed if c[0] == tok][0][1] + 0):
                # count after its session ended
                if tok in closed_tokens:
                    bad("count after session closed", (run, tok, key, at))

    for name in ["a key counted twice in one session", "mixed key kinds in one session", "counted before settle",
                 "a session closed twice", "more than one session left open", "negative active time",
                 "count after session closed", "session ends before it starts",
                 "active time longer than the session"]:
        check("fuzz: never " + name.replace("a key", "is a key").replace("more than", "is more than"),
              name not in problems, problems.get(name))


def no_recount_without_scroll(runs=2000, seed=11):
    """Rule 4 in isolation: on one video, any number of key changes and
    re-observations with no scroll and no re-entry yields at most one count."""
    print("Rule 4: no second count without a scroll")
    rnd = random.Random(seed)
    worst = 0
    for _ in range(runs):
        e, s, _ = make()
        e.onSurface(IG, "reels", 0)
        now = 0
        for _ in range(60):
            e.onObserve(rnd.choice(["h:%016x" % rnd.randint(0, 9), None]), now)
            now += rnd.choice([50, 400, 800])
            e.tick(now)
        worst = max(worst, len(s.counted))
    check("at most 1 count per video without scrolling", worst <= 1, worst)


if __name__ == "__main__":
    scenarios()
    fuzz()
    no_recount_without_scroll()
    print()
    print("%d failing" % len(FAILS))
    sys.exit(1 if FAILS else 0)
