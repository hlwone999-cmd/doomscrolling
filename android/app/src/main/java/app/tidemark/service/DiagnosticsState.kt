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
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state

    fun update(change: (Snapshot) -> Snapshot) {
        _state.value = change(_state.value)
    }
}
