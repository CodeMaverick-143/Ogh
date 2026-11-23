package org.aossie.ogh

import com.pedro.common.ConnectChecker
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutputSessionTrackerTest {

    private val session = RecordingSession()
    private val tracker = OutputSessionTracker(session = { session })

    // --- Callbacks from an obsolete pipeline ---

    @Test
    fun lateDisconnectFromPipelineRebuiltWithFewerOutputs_isIgnoredWithoutCrashing() {
        val old = tracker.startGeneration(2)
        old[0].onConnectionSuccess()
        old[1].onConnectionSuccess()
        session.events.clear()

        // Stop rebuilds the pipeline with one output before RootEncoder delivers
        // the old outputs' disconnects; output #2 no longer exists.
        tracker.startGeneration(1)
        old[1].onDisconnect()
        old[0].onDisconnect()

        assertTrue(session.events.isEmpty())
    }

    @Test
    fun staleCallbacksFromPreviousPipeline_doNotChangeTheCurrentSession() {
        val old = tracker.startGeneration(2)
        val current = tracker.startGeneration(2)
        current[0].onConnectionSuccess()

        old[0].onConnectionFailed("stale")
        old[1].onDisconnect()
        old[0].onNewBitrate(1_000)
        current[1].onConnectionFailed("timeout")

        // The current output 0 is still live, so the session neither failed nor ended.
        assertEquals(listOf("success"), session.events)
    }

    @Test
    fun retiredTracker_ignoresEveryCallback() {
        val outputs = tracker.startGeneration(1)
        tracker.retire()

        outputs[0].onConnectionStarted("rtmp://example/live")
        outputs[0].onConnectionSuccess()
        outputs[0].onNewBitrate(1_000)
        outputs[0].onDisconnect()

        assertTrue(session.events.isEmpty())
    }

    // --- Session-level aggregation for the current pipeline ---

    @Test
    fun sessionFailsOnlyAfterTheLastOutputFails() {
        val outputs = tracker.startGeneration(2)
        outputs[0].onConnectionStarted("a")
        outputs[1].onConnectionStarted("b")
        session.events.clear()

        outputs[0].onConnectionFailed("refused")
        assertTrue(session.events.isEmpty())

        outputs[1].onConnectionFailed("refused")
        assertEquals(listOf("failed:${OutputSessionTracker.ALL_OUTPUTS_FAILED}"), session.events)
    }

    @Test
    fun oneHealthyOutput_keepsTheSessionAliveWhenAnotherFails() {
        val outputs = tracker.startGeneration(2)
        outputs[0].onConnectionSuccess()
        outputs[1].onConnectionFailed("refused")

        assertEquals(listOf("success"), session.events)
    }

    @Test
    fun lastOutputToEnd_decidesTheSessionCallback() {
        val outputs = tracker.startGeneration(3)
        outputs[0].onConnectionFailed("refused")
        outputs[1].onAuthError()
        outputs[2].onDisconnect()

        assertEquals(listOf("disconnect"), session.events)
    }

    @Test
    fun outputRetryingAfterFailure_keepsTheSessionOpen() {
        val outputs = tracker.startGeneration(2)
        outputs[0].onConnectionFailed("refused")
        outputs[0].onConnectionStarted("a")
        outputs[1].onConnectionFailed("refused")

        assertTrue(session.events.none { it.startsWith("failed") })
    }

    @Test
    fun sessionSuccessIsReportedForTheFirstConnectedOutputOnly() {
        val outputs = tracker.startGeneration(2)
        outputs[0].onConnectionSuccess()
        outputs[1].onConnectionSuccess()

        assertEquals(listOf("success"), session.events)
    }

    // --- Ending the encoder session ---

    @Test
    fun stopAllOutputs_endsTheEncoderSessionWhenAFailedClientLeftItOpen() {
        val calls = mutableListOf<String>()

        stopAllOutputs(
            outputCount = 2,
            stopOutput = { calls += "output$it" },
            isSessionStreaming = { true },
            stopSession = { calls += "session" },
        )

        assertEquals(listOf("output0", "output1", "session"), calls)
    }

    @Test
    fun stopAllOutputs_doesNotStopTheSessionTwice() {
        val calls = mutableListOf<String>()

        stopAllOutputs(
            outputCount = 1,
            stopOutput = { calls += "output$it" },
            isSessionStreaming = { false },
            stopSession = { calls += "session" },
        )

        assertEquals(listOf("output0"), calls)
    }

    private class RecordingSession : ConnectChecker {
        val events = mutableListOf<String>()
        override fun onConnectionStarted(url: String) { events += "started" }
        override fun onConnectionSuccess() { events += "success" }
        override fun onConnectionFailed(reason: String) { events += "failed:$reason" }
        override fun onNewBitrate(bitrate: Long) { events += "bitrate" }
        override fun onDisconnect() { events += "disconnect" }
        override fun onAuthError() { events += "authError" }
        override fun onAuthSuccess() { events += "authSuccess" }
    }
}
