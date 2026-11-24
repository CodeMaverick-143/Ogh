package com.ogh.shared.ui

import com.ogh.shared.domain.Destination
import com.ogh.shared.domain.DestinationConnectionStatus.CONNECTING
import com.ogh.shared.domain.DestinationConnectionStatus.DISCONNECTED
import com.ogh.shared.domain.DestinationConnectionStatus.FAILED
import com.ogh.shared.domain.DestinationConnectionStatus.LIVE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionDestinationSummaryTest {

    private val a = destination("a", "Studio")
    private val b = destination("b", "Backup")
    private val c = destination("c", "Archive")

    @Test
    fun allLive_reportsCountAndNoProblems() {
        val summary = summarizeSessionDestinations(listOf(a, b), mapOf("a" to LIVE, "b" to LIVE))

        assertEquals("2 of 2 destinations live", summary.liveCountLine)
        assertTrue(summary.problems.isEmpty())
    }

    @Test
    fun partialFailure_namesOnlyTheFailedAndDisconnectedOutputsInListOrder() {
        val summary = summarizeSessionDestinations(
            destinations = listOf(a, b, c),
            statuses = mapOf("c" to DISCONNECTED, "a" to LIVE, "b" to FAILED),
        )

        assertEquals("1 of 3 destinations live", summary.liveCountLine)
        assertEquals(
            listOf(DestinationProblem("Backup", FAILED), DestinationProblem("Archive", DISCONNECTED)),
            summary.problems,
        )
    }

    @Test
    fun connectingOutputs_areNeitherLiveNorProblems() {
        val summary = summarizeSessionDestinations(listOf(a, b), mapOf("a" to CONNECTING, "b" to LIVE))

        assertEquals(1, summary.live)
        assertTrue(summary.problems.isEmpty())
    }

    @Test
    fun singleDestination_hasNoCountLine() {
        val summary = summarizeSessionDestinations(listOf(a), mapOf("a" to LIVE))

        assertNull(summary.liveCountLine)
    }

    @Test
    fun destinationsOutsideTheSession_areIgnored() {
        val summary = summarizeSessionDestinations(listOf(a, b), mapOf("a" to LIVE, "gone" to FAILED))

        assertEquals(1, summary.total)
        assertTrue(summary.problems.isEmpty())
    }

    @Test
    fun noStatuses_isAnEmptySummary() {
        val summary = summarizeSessionDestinations(listOf(a, b), emptyMap())

        assertEquals(0, summary.total)
        assertNull(summary.liveCountLine)
    }

    // --- Status card text: operation feedback is never replaced by the count ---

    @Test
    fun liveOperationErrors_stayVisibleAlongsideTheDestinationCount() {
        val summary = summarizeSessionDestinations(listOf(a, b), mapOf("a" to LIVE, "b" to FAILED))

        for (error in listOf(
            "Could not hide video",
            "Could not switch video source",
            "Streaming service is unavailable",
            "Screen capture permission denied",
            "Audio permission denied",
            "Could not change audio source",
        )) {
            val text = sessionStatusText(error, isSourceSwitching = false, isLive = true, summary = summary)

            assertEquals(error, text.message)
            assertEquals("1 of 2 destinations live", text.destinationLine)
        }
    }

    @Test
    fun normalLiveSession_showsTheMessageAndTheCount() {
        val summary = summarizeSessionDestinations(listOf(a, b), mapOf("a" to LIVE, "b" to LIVE))

        val text = sessionStatusText("Live", isSourceSwitching = false, isLive = true, summary = summary)

        assertEquals(SessionStatusText("Live", "2 of 2 destinations live"), text)
    }

    @Test
    fun sourceSwitch_showsSwitchingButKeepsTheCount() {
        val summary = summarizeSessionDestinations(listOf(a, b), mapOf("a" to LIVE, "b" to LIVE))

        val text = sessionStatusText("Live", isSourceSwitching = true, isLive = true, summary = summary)

        assertEquals(SessionStatusText("Switching source…", "2 of 2 destinations live"), text)
    }

    @Test
    fun countLine_appearsOnlyWhileLiveOrVideoHidden() {
        val summary = summarizeSessionDestinations(listOf(a, b), mapOf("a" to CONNECTING, "b" to CONNECTING))

        val preparing = sessionStatusText("Connecting…", isSourceSwitching = false, isLive = false, summary = summary)

        assertEquals(SessionStatusText("Connecting…", null), preparing)
    }

    @Test
    fun singleDestinationSession_showsOnlyTheMessage() {
        val summary = summarizeSessionDestinations(listOf(a), mapOf("a" to LIVE))

        val text = sessionStatusText("Could not hide video", isSourceSwitching = false, isLive = true, summary = summary)

        assertEquals(SessionStatusText("Could not hide video", null), text)
    }

    private fun destination(id: String, name: String) = Destination(
        id = id,
        name = name,
        rtmpUrl = "rtmp://example.com/live",
        streamKey = "key",
    )
}
