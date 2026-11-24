package com.ogh.shared.ui

import com.ogh.shared.domain.Destination
import com.ogh.shared.domain.DestinationConnectionStatus

/** A destination whose output is not delivering the session, shown by name. */
internal data class DestinationProblem(val name: String, val status: DestinationConnectionStatus)

/**
 * Per-destination session state condensed for the Preview status card: a count
 * while everything is healthy, and names only for outputs that need attention.
 */
internal data class SessionDestinationSummary(
    val total: Int,
    val live: Int,
    val problems: List<DestinationProblem>,
) {
    /** How many outputs are live; a single destination needs no count. */
    val liveCountLine: String?
        get() = if (total <= 1) null else "$live of $total destinations live"
}

/** The status card's two text lines: the session message, then the destination count. */
internal data class SessionStatusText(val message: String, val destinationLine: String?)

/**
 * The card always shows the session's own [statusMessage], so operation feedback
 * such as "Could not hide video" stays visible while live. The destination count
 * is a separate line, shown only while the session is live or video is hidden.
 */
internal fun sessionStatusText(
    statusMessage: String,
    isSourceSwitching: Boolean,
    isLive: Boolean,
    summary: SessionDestinationSummary,
): SessionStatusText = SessionStatusText(
    message = if (isSourceSwitching) "Switching source…" else statusMessage,
    destinationLine = if (isLive) summary.liveCountLine else null,
)

/**
 * Summarizes [statuses] for the destinations that belong to the session, in the
 * order they appear in [destinations]. Destinations without a status are not
 * part of the session and are ignored.
 */
internal fun summarizeSessionDestinations(
    destinations: List<Destination>,
    statuses: Map<String, DestinationConnectionStatus>,
): SessionDestinationSummary {
    val tracked = destinations.mapNotNull { destination ->
        statuses[destination.id]?.let { destination to it }
    }
    return SessionDestinationSummary(
        total = tracked.size,
        live = tracked.count { (_, status) -> status == DestinationConnectionStatus.LIVE },
        problems = tracked
            .filter { (_, status) ->
                status == DestinationConnectionStatus.FAILED ||
                    status == DestinationConnectionStatus.DISCONNECTED
            }
            .map { (destination, status) -> DestinationProblem(destination.name, status) },
    )
}
