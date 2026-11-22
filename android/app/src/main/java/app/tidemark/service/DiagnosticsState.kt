package app.tidemark.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live detection status for Settings > Diagnostics. In memory only (the
 * service and the UI run in the same process); nothing here is saved or
 * sent. Holds technical metadata only: package names, event types, class
 * names and view-id entries. Never screen text.
 */
object DiagnosticsState {
    data class Snapshot(
        val connected: Boolean = false,
        val connectedAt: Long = 0L,
        val foregroundPackage: String? = null,
        val lastEvent: String? = null,
        val lastEventAt: Long = 0L,
        val surface: String? = null,
        val lastReason: String? = null,
        val lastReasonAt: Long = 0L,
        val countedSinceConnect: Int = 0,
        val eventsSinceConnect: Long = 0L,
        /** Pager scroll events received (from the pager itself, not comments or captions). */
        val scrollEvents: Long = 0L,
        /** Page changes confirmed (swipe evidence + a different page). */
        val pageChanges: Int = 0,
        val previousPosition: Int? = null,
        val position: Int = 0,
        /** Why the last probe did or didn't see a new video. */
        val pageDecision: String? = null,
        /** Timestamps (epoch ms) along the counting path, to see where time goes. */
        val lastScrollAt: Long = 0L,
        val lastScrollKind: String? = null,
        val lastPageChangeAt: Long = 0L,
        val lastCountAt: Long = 0L,
        /** Bottom navigation bar visible or not: tells the main feed from search/detail screens. */
        val feedContext: String? = null,
        /** BUG-006 correlation: the last scroll event's source (class, view-id entry, node id), indexes and deltas. */
        val scrollSource: String? = null,
        /** How that source relates to the held pager and what the event was used for. */
        val scrollVerdict: String? = null,
        val scrollAccepted: Long = 0L,
        val scrollIgnored: Long = 0L,
        /** The held pager: class, view-id entry and node id; and how often the app replaced it. */
        val pagerNode: String? = null,
        val pagerReplaced: Int = 0,
        /** The last probe of the pager: when, and what it saw. */
        val lastSnapshotAt: Long = 0L,
        val lastSnapshot: String? = null,
        /** Movement evidence still in the window, or none. */
        val motionAt: Long = 0L,
        /** A new page seen before its swipe evidence, waiting since (0 = none). */
        val pendingSince: Long = 0L,
        val tailEvents: Long = 0L,
        val lateConfirmations: Int = 0,
        val rejectedChanges: Int = 0,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state

    fun update(change: (Snapshot) -> Snapshot) {
        _state.value = change(_state.value)
    }
}
