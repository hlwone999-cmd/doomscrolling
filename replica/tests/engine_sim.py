#!/usr/bin/env python3
"""Line-for-line Python port of detect/CountingEngine.kt (and PageTracker.kt /
FeedPipeline), for checking its
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
    def __init__(self, settle=250, grace=30_000, hide=1_500, touch=5_000):
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
        self.mayCount = False
        self.entryUnseen = True


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
            o.mayCount = False
            o.entryUnseen = True
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
            if o.entryUnseen:
                o.entryUnseen = False
                if key not in o.counted:
                    o.counted.add(key)
                    if o.keyKind is None:
                        o.keyKind = key[0]
            return
        o.entryUnseen = False
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
    print("Scenarios mirrored from CountingEngineTest.kt (BUG-005: arrival video is not counted)")
    e, s, _ = make(); e.onSurface(IG, "reels", 0); e.onObserve("i:0", 10); e.tick(400)
    check("arrivalVideoIsNotCounted", s.counted == [], s.counted)

    e, s, _ = make(); e.onSurface(IG, "reels", 0); settle(e, "i:0", 0)
    e.onScroll(1000); e.onObserve("i:1", 1010); e.tick(1200)
    a = len(s.counted); e.tick(1260)
    check("firstSwipeCountsAfterSettle", a == 0 and [c[1] for c in s.counted] == ["i:1"], s.counted)

    e, s, _ = make(); e.onSurface(IG, "reels", 0); e.onScroll(10); e.onObserve("i:1", 100); e.onScroll(200)
    e.onObserve("i:2", 250); e.onScroll(350); e.onObserve("i:3", 400); e.tick(900)
    check("flingPastDoesNotCount", [c[1] for c in s.counted] == ["i:3"], s.counted)

    e, s, _ = make(); e.onSurface(IG, "reels", 0); settle(e, "i:0", 0); e.onScroll(1000); settle(e, "i:1", 1100)
    e.onScroll(3000); settle(e, "i:0", 3100)
    check("swipingBackDoesNotRecount", [c[1] for c in s.counted] == ["i:1"], s.counted)

    e, s, _ = make(); e.onSurface(IG, "reels", 0); settle(e, "h:aaaaaaaaaaaaaaaa", 0)
    e.onScroll(1000); settle(e, "h:cccccccccccccccc", 1100); settle(e, "h:bbbbbbbbbbbbbbbb", 3000)
    check("keyChangeWithoutScrollDoesNotCount", len(s.counted) == 1, s.counted)

    e, s, _ = make(); e.onSurface(IG, "reels", 0); settle(e, "h:aaaaaaaaaaaaaaaa", 0)
    e.onScroll(1000); settle(e, "h:cccccccccccccccc", 1100); e.onScroll(2000); settle(e, "h:aaaaaaaaaaaaaaaa", 2100)
    settle(e, "h:dddddddddddddddd", 4000)
    check("swipeBackThenKeyChangeDoesNotCount", len(s.counted) == 1, s.counted)

    e, s, _ = make(); e.onSurface(IG, "reels", 0); settle(e, "i:6", 0); e.onScroll(500)
    for i in range(5): e.onObserve("i:7", 510 + i)
    e.tick(900); e.tick(1300); e.tick(6000)
    check("duplicateObservationsCountOnce", len(s.counted) == 1)

    e, s, _ = make(); e.onSurface(IG, "reels", 0); settle(e, "i:0", 0); e.onOffSurface(1000); e.tick(2600)
    hidden = not s.overlay
    e.onSurface(IG, "reels", 10_000); shown = s.overlay; settle(e, "i:0", 10_000)
    check("shortLeaveKeepsSessionAndCountsNothing", hidden and shown and len(s.opened) == 1 and not s.counted and not s.closed)

    e, s, _ = make(); e.onSurface(IG, "reels", 0); settle(e, "i:0", 0); e.onOffSurface(1000)
    e.onSurface(IG, "reels", 5000); settle(e, "i:9", 5000)
    check("returningToADifferentVideoIsNotCounted", not s.counted, s.counted)

    e, s, _ = make(); e.onSurface(IG, "reels", 0); settle(e, "i:0", 0); e.onOffSurface(20_000); e.tick(50_000)
    ok = s.closed == [(1, 20_000, LEFT_SURFACE)] and s.activeMs == [20_000]
    e.onSurface(IG, "reels", 60_000)
    check("longLeaveClosesAtLeaveTime", ok and len(s.opened) == 2, (s.closed, s.activeMs))

    e, s, _ = make(); e.onSurface(IG, "reels", 0); e.onOffSurface(1_000); e.onSurface(IG, "reels", 40_000)
    check("returnAfterGraceWithoutTickStillStartsNewSession", s.closed[0][2] == LEFT_SURFACE and len(s.opened) == 2)

    e, s, _ = make(); e.onSurface(IG, "reels", 0); e.onSurface(YT, "shorts", 5_000)
    check("appSwitchClosesAndOpens", s.closed[0][2] == APP_SWITCH and s.opened == [(1, IG), (2, YT)])

    e, s, _ = make(); e.onSurface(IG, "reels", 0); settle(e, "i:0", 0); e.onScroll(1000); settle(e, "h:aaaaaaaaaaaaaaaa", 1100)
    check("mixedKeyKindsIgnoredInOneSession", not s.counted)

    e, s, _ = make(); e.onSurface(IG, "reels", 0); e.onScroll(0); e.onObserve(None, 0); e.tick(5_000)
    check("nullKeyNeverCounts", not s.counted)

    e, s, _ = make(); e.onSurface(IG, "reels", 0); e.onScreenOff(8_000)
    check("screenOffClosesImmediately", s.closed == [(1, 8_000, SCREEN_OFF)] and not s.overlay)

    e, s, st = make(); e.onSurface(IG, "reels", 0); settle(e, "i:0", 0); e.onScroll(500); settle(e, "i:1", 500)
    st["day"] = "2026-11-22"; e.tick(10_000)
    ok = s.closed[0][2] == DAY_ROLLOVER and len(s.opened) == 2
    settle(e, "i:1", 10_000); e.onScroll(11_000); settle(e, "i:2", 11_000)
    check("dayRolloverSplitsSession", ok and [c[1] for c in s.counted] == ["i:1", "i:2"], s.counted)

    e, s, st = make(); e.onSurface(IG, "reels", 0); e.onOffSurface(1_000); st["day"] = "2026-11-22"
    e.onSurface(IG, "reels", 20_000)
    check("dayRolloverDuringGraceEndsAtLeaveTime (BUG-001)",
          s.closed == [(1, 1_000, DAY_ROLLOVER)] and s.activeMs == [1_000], (s.closed, s.activeMs))

    e, s, _ = make(); e.onSurface(IG, "reels", 1_000); e.onObserve("i:0", 2_000)
    check("nextDeadlineTracksPendingSettle", e.nextDeadline() == 2_250, e.nextDeadline())

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
            if any(d < Cfg().settleMs for _, _, d in items):
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


# ---------------- PageTracker + FeedPipeline (port of detect/PageTracker.kt) ----------------
class PageTracker:
    MOTION_WINDOW_MS = 1500
    PENDING_WINDOW_MS = 1000
    TAIL_MS = 300

    def __init__(self):
        self.position = 0
        self.previousPosition = None
        self.scrollEvents = 0
        self.tailEvents = 0
        self.pageChanges = 0
        self.lateConfirmations = 0
        self.rejectedChanges = 0
        self.lastPageChangeAt = None
        self.rebase()

    def _clear(self):
        self.lastMotionAt = None
        self.deltaSum = 0
        self.midUpper = None
        self.midLower = None
        self.scrollIndex = None

    def _clear_pending(self):
        self.pendingSince = None
        self.pendingSignature = None
        self.pendingIndex = None

    def reset(self):
        self.position = 0
        self.previousPosition = None
        self.rebase()

    def rebase(self):
        self.lastSignature = None
        self.lastIndex = None
        self.topVaries = False
        self._clear()
        self._clear_pending()

    def onScroll(self, f, t, dy, now):
        self.scrollEvents += 1
        settled = f if (f >= 0 and f == t) else None
        reference = self.scrollIndex if self.scrollIndex is not None else self.lastIndex
        newIndex = settled is not None and reference is not None and settled != reference
        moving = dy != 0 or (f >= 0 and t >= 0 and f != t) or newIndex
        if settled is not None:
            self.scrollIndex = settled
        if not moving:
            return "NOISE"
        c = self.lastPageChangeAt
        if c is not None and 0 <= now - c < self.TAIL_MS:
            self.tailEvents += 1
            return "TAIL"
        self.lastMotionAt = now
        self.deltaSum += dy
        return "NEW_INDEX" if newIndex else "MOVING"

    def onMidSwipe(self, upper, lower, now):
        self.lastMotionAt = now
        self.midUpper, self.midLower = upper, lower

    def onSettled(self, signature, row, now):
        index = row if row is not None else self.scrollIndex
        if self.lastSignature is None:
            self.lastSignature, self.lastIndex = signature, index
            self._clear(); self._clear_pending()
            return (self.position, False, "video on entry")
        motionAt = self.lastMotionAt
        moved = motionAt is not None and 0 <= now - motionAt <= self.MOTION_WINDOW_MS
        since = self.pendingSince
        late = False
        if since is not None:
            followed = motionAt is not None and motionAt >= since and motionAt - since <= self.PENDING_WINDOW_MS
            if followed:
                late = True
            elif now - since > self.PENDING_WINDOW_MS:
                self.lastSignature = self.pendingSignature
                if self.pendingIndex is not None:
                    self.lastIndex = self.pendingIndex
                self.rejectedChanges += 1
                self._clear_pending()
        prevIndex = self.lastIndex
        sigChanged = signature != self.lastSignature
        idxChanged = index is not None and prevIndex is not None and index != prevIndex
        topChanged = signature[:1] != self.lastSignature[:1]
        if sigChanged and not topChanged and not idxChanged and self.topVaries:
            self.lastSignature = signature
            if index is not None:
                self.lastIndex = index
            self._clear_pending()
            return (self.position, False, "same video (content rebuilt)")
        if not sigChanged and not idxChanged:
            if index is not None:
                self.lastIndex = index
            self._clear_pending()
            return (self.position, False, "same video")
        if not moved:
            if self.pendingSince is None:
                self.pendingSince = now
            self.pendingSignature, self.pendingIndex = signature, index
            return (self.position, False, "page structure changed without a swipe")
        if idxChanged:
            step = index - prevIndex
        elif self.deltaSum != 0:
            step = 1 if self.deltaSum > 0 else -1
        elif self.midLower is not None and self.midLower in signature:
            step = 1
        elif self.midUpper is not None and self.midUpper in signature:
            step = -1
        else:
            step = 1
        if late:
            self.lateConfirmations += 1
        if topChanged:
            self.topVaries = True
        self.previousPosition = self.position
        self.position += step
        self.pageChanges += 1
        self.lastPageChangeAt = now
        self.lastSignature, self.lastIndex = signature, index
        self._clear(); self._clear_pending()
        return (self.position, True, "page changed")


def is_motion(kind):
    return kind in ("MOVING", "NEW_INDEX")


class FeedPipeline:
    def __init__(self, engine):
        self.engine = engine
        self.pages = PageTracker()
        self.countable = True

    def onNewSession(self):
        self.pages.reset()

    def onEnterSurface(self):
        self.pages.rebase()

    def onPagerScroll(self, f, t, dy, now):
        k = self.pages.onScroll(f, t, dy, now)
        if is_motion(k) and self.countable:
            self.engine.onScroll(now)
        return k

    def onMidSwipe(self, upper, lower, now):
        self.pages.onMidSwipe(upper, lower, now)
        if self.countable:
            self.engine.onScroll(now)
        self.engine.onObserve(None, now)

    def onSettledPage(self, signature, row, now):
        r = self.pages.onSettled(signature, row, now)
        if r[1] and self.countable:
            self.engine.onScroll(now)
        self.engine.onObserve("p:%d" % r[0], now)
        return r


# Port of detect/ScrollSource.kt
def judge(relation, same_size, horizontal_only):
    """Returns (accepted, indexes_usable)."""
    if horizontal_only:
        return (False, False)
    if relation in ("PAGER", "PAGER_LIST"):
        return (True, True)
    if relation == "UNRELATED" or not same_size:
        return (False, False)
    return (True, False)


class FeedContext:
    MIN_NAV_ITEMS = 4

    def __init__(self):
        self.reset()

    def reset(self):
        self.stable = "UNKNOWN"
        self.last = None

    def onReading(self, items):
        if items < 0:
            return self.stable
        k = "MAIN_FEED" if items >= self.MIN_NAV_ITEMS else "OTHER"
        if k == self.last:
            self.stable = k
        self.last = k
        return self.stable


class FeedHarness:
    """Mirror of FeedPipelineTest's helpers."""

    def __init__(self):
        self.sink = Sink()
        self.notes = []
        self.e = Engine(Cfg(), self.sink, lambda _t: "2026-11-21")
        self.feed = FeedPipeline(self.e)
        self.now = 0
        self.e.onSurface("tiktok", "tiktok_feed", 0)
        self.feed.onNewSession()

    def advance(self, ms):
        end = self.now + ms
        while True:
            d = self.e.nextDeadline()
            if d is None or d > end:
                break
            self.now = max(self.now, d)
            self.e.tick(self.now)
        self.now = end

    def settle(self, page, row=None):
        self.feed.onSettledPage([page, page + "-inner"], row, self.now)
        self.advance(500)

    def swipe(self, a, b, fi=-1, ti=-1, dy=1800):
        self.feed.onPagerScroll(fi, fi + 1, dy // 2, self.now); self.advance(40)
        self.feed.onMidSwipe(a, b, self.now); self.advance(40)
        self.feed.onPagerScroll(ti, ti, dy // 2, self.now); self.advance(150)
        self.settle(b)

    def keys(self):
        return [c[1] for c in self.sink.counted]

    def scroll_from(self, relation, f=-1, t=-1, dy=900, dx=0, same_size=True):
        """What the service does with a scroll event: judge its source, then feed it."""
        ok, idx = judge(relation, same_size, dx != 0 and dy == 0)
        if not ok:
            return "IGNORED"
        return self.feed.onPagerScroll(f if idx else -1, t if idx else -1, dy, self.now)

    def late_swipe(self, a, b):
        """Device order seen on the Realme (BUG-006): the new page is up before the scroll event."""
        self.feed.onSettledPage([b, b + "-inner"], None, self.now); self.advance(60)
        k = self.feed.onPagerScroll(-1, -1, 900, self.now)
        if is_motion(k) and self.feed.pages.pendingSince is not None:
            self.feed.onSettledPage([b, b + "-inner"], None, self.now)  # the service probes at once
        self.advance(100)
        for _ in range(3):                                               # tail of the same swipe
            self.feed.onPagerScroll(-1, -1, 300, self.now); self.advance(50)
        self.settle(b)


def feed_scenarios():
    print("FeedPipeline (port of FeedPipelineTest.kt, BUG-005 semantics)")
    h = FeedHarness(); h.settle("A")
    check("feed: arrivalVideoIsNotCounted", h.keys() == [], h.keys())

    h = FeedHarness(); h.settle("V0")
    for i in range(1, 11):
        h.swipe("V%d" % (i - 1), "V%d" % i, i - 1, i)
    check("feed: tenSwipesGiveTenCounts", h.keys() == ["p:%d" % i for i in range(1, 11)], h.keys())

    h = FeedHarness(); h.settle("V0")
    for i in range(1, 11):
        h.swipe("V%d" % (i - 1), "V%d" % i)
    check("feed: tenSwipesWithoutAdapterIndexesStillCount", len(h.keys()) == 10, h.keys())

    h = FeedHarness(); v = ["A", "B", "C"]; h.settle("A")
    for i in range(1, 10):
        h.swipe(v[(i - 1) % 3], v[i % 3], i - 1, i)
    check("feed: recycledContainersStillCount", len(h.keys()) == 9, h.keys())

    h = FeedHarness(); h.settle("A")
    for _ in range(6):
        h.feed.onPagerScroll(0, 1, 300, h.now); h.advance(30)
    h.feed.onMidSwipe("A", "B", h.now); h.advance(30)
    h.feed.onPagerScroll(1, 1, 0, h.now)
    h.settle("B"); h.settle("B"); h.settle("B"); h.advance(3000)
    check("feed: oneSwipeIsCountedOnce", h.keys() == ["p:1"], h.keys())

    h = FeedHarness(); h.settle("A")
    h.feed.onSettledPage(["A", "A-inner-rebuilt"], None, h.now); h.advance(600)
    h.settle("A"); h.advance(2000); h.settle("A")
    check("feed: likeAndCommentDoNotCount", h.keys() == [], h.keys())

    h = FeedHarness(); h.settle("A")
    h.e.onOffSurface(h.now); h.advance(5000)
    h.e.onSurface("tiktok", "tiktok_feed", h.now); h.settle("A")
    check("feed: openingAProfileDoesNotCount", h.keys() == [] and not h.sink.closed, (h.keys(), h.sink.closed))

    h = FeedHarness(); h.settle("A"); h.e.onOffSurface(h.now); h.advance(40_000)
    h.e.onSurface("tiktok", "tiktok_feed", h.now); h.feed.onNewSession(); h.settle("SearchResult")
    check("feed: openingASearchResultDoesNotCount", h.keys() == [], h.keys())

    h = FeedHarness(); h.settle("A"); h.swipe("A", "B", 0, 1)
    h.feed.onPagerScroll(1, 0, -900, h.now); h.advance(40)
    h.feed.onMidSwipe("A", "B", h.now); h.advance(40)
    h.feed.onPagerScroll(0, 0, -900, h.now); h.advance(150)
    h.settle("A"); h.swipe("A", "B", 0, 1)
    check("feed: forwardAndBackDoNotRecount", h.keys() == ["p:1"], h.keys())

    h = FeedHarness(); h.settle("A")
    h.feed.onPagerScroll(0, 1, 200, h.now); h.advance(40)
    h.feed.onPagerScroll(0, 0, -200, h.now); h.advance(150); h.settle("A")
    check("feed: bounceBackIsNotANewVideo", h.keys() == [], h.keys())

    h = FeedHarness(); h.settle("A"); h.settle("Z")
    check("feed: pageChangeWithoutAnySwipeIsNotCounted", h.keys() == [], h.keys())

    # BUG-005: a probe at the very start of the swipe (old page still on screen) must not erase the swipe.
    h = FeedHarness(); h.settle("A")
    h.feed.onPagerScroll(-1, -1, 300, h.now); h.advance(20)
    h.feed.onSettledPage(["A", "A-inner"], None, h.now); h.advance(80)   # early probe: same page
    h.advance(100)                                                       # swipe finishes, no more events
    h.feed.onSettledPage(["B", "B-inner"], None, h.now); h.advance(300)
    check("feed: earlyProbeDoesNotEraseTheSwipe", h.keys() == ["p:1"], h.keys())

    # BUG-005: scroll events that move nothing must not delay the count.
    h = FeedHarness(); h.settle("A")
    h.feed.onPagerScroll(0, 1, 0, h.now); h.advance(40)
    h.feed.onPagerScroll(1, 1, 0, h.now)
    h.feed.onSettledPage(["B", "B-inner"], None, h.now)
    t0 = h.now
    for _ in range(10):                                                   # trailing no-op events
        h.advance(30); h.feed.onPagerScroll(1, 1, 0, h.now)
    h.advance(260 - (h.now - t0))
    check("feed: countLandsWithin260msOfThePageChange", h.keys() == ["p:1"], (h.keys(), h.now - t0))

    h = FeedHarness(); h.settle("A"); shown = h.sink.overlay
    h.e.onOffSurface(h.now); h.advance(2000); hidden = not h.sink.overlay
    h.advance(31000)
    check("feed: leavingTheFeedEndsTheSessionAndHidesTheOverlay",
          shown and hidden and [c[2] for c in h.sink.closed] == [LEFT_SURFACE], h.sink.closed)

    print("PageTracker (port of PageTrackerTest.kt)")
    t = PageTracker(); t.onSettled(["W"], None, 0); t.onScroll(0, 0, 0, 10); t.onSettled(["W"], None, 400)
    t.onScroll(0, 1, 0, 500); t.onScroll(1, 1, 0, 600); r = t.onSettled(["W"], None, 700)
    check("tracker: constantWrapperWithChangingIndexStillMoves", r[1] and r[0] == 1, r)
    t = PageTracker(); t.onSettled(["A"], 4, 0); t.onScroll(4, 5, 0, 100); t.onScroll(5, 5, 0, 200); r1 = t.onSettled(["B"], None, 300)
    t.onScroll(5, 4, 0, 1400); t.onScroll(4, 4, 0, 1500); r2 = t.onSettled(["A"], None, 1600)
    check("tracker: adapterIndexGivesStepAndDirection", r1[0] == 1 and r2[0] == 0, (r1, r2))
    t = PageTracker(); t.onSettled(["B"], None, 0); t.onMidSwipe("A", "B", 100); r = t.onSettled(["A"], None, 200)
    check("tracker: midSwipeTellsDirection", r[0] == -1, r)
    t = PageTracker(); t.onSettled(["W", "page1"], None, 0); t.onMidSwipe("page1", "page2", 100); r = t.onSettled(["W", "page2"], None, 200)
    check("tracker: innerContainerChangeCountsWhenWrapperIsConstant", r[1] and r[0] == 1, r)
    t = PageTracker(); t.onSettled(["A"], None, 0); t.onScroll(-1, -1, 100, 100); r = t.onSettled(["B"], None, 100 + 1600)
    check("tracker: movementExpiresAfterTheWindow", not r[1], r)
    t = PageTracker(); t.onSettled(["A"], 3, 0)
    check("tracker: noOpScrollIsNoise", t.onScroll(3, 3, 0, 10) == "NOISE")
    check("tracker: newIndexIsReported", t.onScroll(4, 4, 0, 20) == "NEW_INDEX")

    print("BUG-006: scroll/page correlation, scroll sources, feed context")
    h = FeedHarness(); h.settle("V0")
    for i in range(1, 11):
        h.late_swipe("V%d" % (i - 1), "V%d" % i)
    check("bug006: tenLateEvidenceSwipesGiveTenCounts", h.keys() == ["p:%d" % i for i in range(1, 11)], h.keys())

    h = FeedHarness(); h.settle("A")
    h.feed.onSettledPage(["B", "B-inner"], None, h.now); h.advance(1500)
    h.feed.onPagerScroll(-1, -1, 900, h.now); h.feed.onSettledPage(["B", "B-inner"], None, h.now); h.advance(500)
    check("bug006: evidenceTooLateDoesNotCount", h.keys() == [], h.keys())

    h = FeedHarness(); h.settle("A")
    h.scroll_from("PAGER_LIST", 0, 1); h.advance(40)
    h.feed.onMidSwipe("A", "B", h.now); h.advance(40)
    h.scroll_from("PAGER_LIST", 1, 1); h.advance(150); h.settle("B")
    check("bug006: eventFromChildListSnapshotFromParent", h.keys() == ["p:1"], h.keys())

    h = FeedHarness(); h.settle("A")
    h.scroll_from("INSIDE_PAGER", 7, 7); h.advance(150); h.settle("B")
    check("bug006: sameSizeNodeInsidePagerGivesMovementOnly", h.keys() == ["p:1"] and h.feed.pages.lastIndex is None,
          (h.keys(), h.feed.pages.lastIndex))

    h = FeedHarness(); h.settle("A")
    k1 = h.scroll_from("AROUND_PAGER", 0, 1, dy=0, dx=1080)
    k2 = h.scroll_from("UNRELATED", -1, -1, dy=400, same_size=False)
    h.advance(150); h.settle("B")
    check("bug006: tabSwipeAndCommentScrollAreNotEvidence", k1 == "IGNORED" and k2 == "IGNORED" and h.keys() == [],
          (k1, k2, h.keys()))

    h = FeedHarness(); h.settle("A")
    h.feed.onPagerScroll(-1, -1, 300, h.now); h.advance(40)
    h.feed.onMidSwipe("A-cover", "B-cover", h.now); h.advance(40)       # mid-swipe nodes differ from the final ones
    h.feed.onSettledPage(["B", "B-cover"], None, h.now); h.advance(100)
    h.feed.onPagerScroll(-1, -1, 200, h.now)                            # tail event
    h.feed.onSettledPage(["B", "B-player"], None, h.now); h.advance(300) # player replaces the cover: child id changes
    h.feed.onPagerScroll(-1, -1, 200, h.now); h.advance(800)            # a straggler event after the tail
    h.feed.onSettledPage(["B", "B-player"], None, h.now); h.advance(500)
    check("bug006: childIdChangesDuringSwipeCountsOnce", h.keys() == ["p:1"], h.keys())

    h = FeedHarness()
    h.feed.onSettledPage(["Wrap", "Page0"], None, h.now); h.advance(500)
    for i in range(1, 6):
        h.feed.onSettledPage(["Wrap", "Page%d" % i], None, h.now); h.advance(60)
        h.feed.onPagerScroll(-1, -1, 900, h.now); h.feed.onSettledPage(["Wrap", "Page%d" % i], None, h.now)
        h.advance(500)
    check("bug006: constantWrapperChangingChildCounts", h.keys() == ["p:%d" % i for i in range(1, 6)], h.keys())

    h = FeedHarness(); h.settle("A")
    for _ in range(8):
        h.feed.onPagerScroll(-1, -1, 250, h.now); h.advance(30)
    h.feed.onSettledPage(["B", "B-inner"], None, h.now)
    for _ in range(5):
        h.advance(40); h.feed.onPagerScroll(-1, -1, 120, h.now)
    h.settle("B"); h.advance(2000)
    check("bug006: manyMovingEventsOneSwipeOneCount", h.keys() == ["p:1"] and h.feed.pages.tailEvents == 5,
          (h.keys(), h.feed.pages.tailEvents))

    fc = FeedContext(); fc.onReading(0); first = fc.stable; fc.onReading(1)
    check("bug006: searchViewerIsNotMainFeed", first == "UNKNOWN" and fc.stable == "OTHER", (first, fc.stable))
    fc = FeedContext(); fc.onReading(5); fc.onReading(5); fc.onReading(0)
    check("bug006: oneFlickerDoesNotChangeTheVerdict", fc.stable == "MAIN_FEED", fc.stable)

    h = FeedHarness(); fc = FeedContext(); fc.onReading(0); fc.onReading(0)
    h.feed.countable = fc.stable == "MAIN_FEED"
    h.settle("S0")
    for i in range(1, 4):
        h.swipe("S%d" % (i - 1), "S%d" % i)
    no_search = h.keys() == []
    h.e.onOffSurface(h.now); h.advance(3000)
    h.e.onSurface("tiktok", "tiktok_feed", h.now); h.feed.onEnterSurface()
    fc.reset(); fc.onReading(5); fc.onReading(5); h.feed.countable = fc.stable == "MAIN_FEED"
    h.settle("F0"); h.swipe("F0", "F1")
    check("bug006: searchViewerSwipesDoNotCountMainFeedDoes", no_search and len(h.keys()) == 1, h.keys())


if __name__ == "__main__":
    scenarios()
    fuzz()
    no_recount_without_scroll()
    feed_scenarios()
    print()
    print("%d failing" % len(FAILS))
    sys.exit(1 if FAILS else 0)
