package org.aossie.ogh

import com.pedro.common.ConnectChecker
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutputSessionTrackerTest {

    private val session = RecordingSession()
    private val endedPipelines = CopyOnWriteArrayList<OutputSessionTracker.Pipeline>()
    private val tracker = OutputSessionTracker(
        session = { session },
        onAllOutputsEnded = { endedPipelines += it },
    )

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
        assertTrue(endedPipelines.isEmpty())
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
        assertTrue(endedPipelines.isEmpty())
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
        assertTrue(endedPipelines.isEmpty())
    }

    // --- Pipeline replacement between generation check and dispatch ---

    @Test
    fun pipelineReplacement_waitsForTheCallbackBeingDelivered() {
        val order = CopyOnWriteArrayList<String>()
        val delivering = CountDownLatch(1)
        val finishDelivery = CountDownLatch(1)
        val slowSession = object : RecordingSession() {
            override fun onConnectionSuccess() {
                order += "delivery started"
                delivering.countDown()
                finishDelivery.await(5, TimeUnit.SECONDS)
                order += "delivery finished"
            }
        }
        val tracker = OutputSessionTracker(session = { slowSession })
        val old = tracker.startGeneration(1)

        val callback = thread { old[0].onConnectionSuccess() }
        assertTrue(delivering.await(5, TimeUnit.SECONDS))
        // For example MediaProjection revocation finishing the session off the main thread.
        val replacement = thread {
            tracker.startGeneration(1)
            order += "pipeline replaced"
        }
        replacement.join(300)
        assertEquals(listOf("delivery started"), order.toList())

        finishDelivery.countDown()
        callback.join(5_000)
        replacement.join(5_000)
        assertEquals(listOf("delivery started", "delivery finished", "pipeline replaced"), order.toList())
    }

    @Test
    fun callbackValidatedBeforeReplacement_neverReachesTheReplacementSession() {
        val original = RecordingSession()
        val replacementSession = RecordingSession()
        val activeSession = AtomicReference<ConnectChecker>(original)
        val lookupStarted = CountDownLatch(1)
        val finishLookup = CountDownLatch(1)
        val firstLookup = AtomicBoolean(true)
        val tracker = OutputSessionTracker(
            session = {
                // Pause the first lookup, after the generation check, so a replacement
                // can race it the way a different thread would.
                if (firstLookup.getAndSet(false)) {
                    lookupStarted.countDown()
                    finishLookup.await(5, TimeUnit.SECONDS)
                }
                activeSession.get()
            },
        )
        val old = tracker.startGeneration(1)

        val callback = thread { old[0].onConnectionSuccess() }
        assertTrue(lookupStarted.await(5, TimeUnit.SECONDS))
        val newSession = thread {
            tracker.startGeneration(1)
            activeSession.set(replacementSession)
        }
        newSession.join(300)
        finishLookup.countDown()
        callback.join(5_000)
        newSession.join(5_000)
        old[0].onDisconnect()

        assertEquals(listOf("success"), original.events)
        assertTrue(replacementSession.events.isEmpty())
    }

    // --- End of a pipeline and the cleanup tied to it ---

    @Test
    fun pipelineEnd_isReportedOnceWithTheOriginatingPipeline() {
        val outputs = tracker.startGeneration(2)
        outputs[0].onConnectionFailed("refused")
        outputs[1].onDisconnect()

        // Overlapping and late callbacks from the ended pipeline.
        outputs[1].onConnectionFailed("again")
        outputs[0].onAuthError()
        outputs[0].onConnectionStarted("retry")
        outputs[0].onNewBitrate(1_000)

        assertEquals(1, endedPipelines.size)
        assertTrue(tracker.isCurrent(endedPipelines.single()))
        assertEquals(listOf("disconnect"), session.events)
    }

    @Test
    fun concurrentTerminalCallbacks_reportTheEndExactlyOnce() {
        repeat(200) {
            val session = RecordingSession()
            val ended = CopyOnWriteArrayList<OutputSessionTracker.Pipeline>()
            val tracker = OutputSessionTracker(session = { session }, onAllOutputsEnded = { ended += it })
            val outputs = tracker.startGeneration(2)
            val start = CyclicBarrier(2)

            val a = thread { start.await(); outputs[0].onConnectionFailed("refused") }
            val b = thread { start.await(); outputs[1].onDisconnect() }
            a.join(5_000)
            b.join(5_000)

            assertEquals(1, ended.size)
            assertEquals(1, session.events.size)
        }
    }

    @Test
    fun deferredCleanup_ownsOnlyThePipelineThatEnded() {
        val outputs = tracker.startGeneration(1)
        outputs[0].onConnectionFailed("refused")
        val ended = endedPipelines.single()
        assertTrue(tracker.isCurrent(ended))

        // Stop, MediaProjection revocation, or a new session replaced the pipeline
        // before the posted cleanup ran: the cleanup no longer owns anything.
        tracker.startGeneration(1)
        assertFalse(tracker.isCurrent(ended))

        tracker.retire()
        assertFalse(tracker.isCurrent(ended))
    }

    @Test
    fun cleanupRacingMediaProjectionShutdown_neverTargetsTheReplacementPipeline() {
        repeat(200) {
            val ended = CopyOnWriteArrayList<OutputSessionTracker.Pipeline>()
            val tracker = OutputSessionTracker(session = { null }, onAllOutputsEnded = { ended += it })
            val outputs = tracker.startGeneration(1)
            val start = CyclicBarrier(2)

            // The last output fails while the MediaProjection thread finishes the session.
            val rtmp = thread { start.await(); outputs[0].onConnectionFailed("refused") }
            val projection = thread { start.await(); tracker.startGeneration(1) }
            rtmp.join(5_000)
            projection.join(5_000)

            assertTrue(ended.size <= 1)
            assertTrue(ended.none(tracker::isCurrent))
            assertTrue(tracker.canReuseCurrent(1))
        }
    }

    @Test
    fun endedPipeline_isNeverHandedToANewSession() {
        val outputs = tracker.startGeneration(1)
        assertTrue(tracker.canReuseCurrent(1))
        assertFalse(tracker.canReuseCurrent(2))

        outputs[0].onConnectionFailed("refused")
        assertFalse(tracker.canReuseCurrent(1))

        tracker.startGeneration(1)
        assertTrue(tracker.canReuseCurrent(1))

        tracker.retire()
        assertFalse(tracker.canReuseCurrent(1))
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
        assertTrue(endedPipelines.isEmpty())
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
        assertTrue(endedPipelines.isEmpty())
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

    private open class RecordingSession : ConnectChecker {
        val events = CopyOnWriteArrayList<String>()
        override fun onConnectionStarted(url: String) { events += "started" }
        override fun onConnectionSuccess() { events += "success" }
        override fun onConnectionFailed(reason: String) { events += "failed:$reason" }
        override fun onNewBitrate(bitrate: Long) { events += "bitrate" }
        override fun onDisconnect() { events += "disconnect" }
        override fun onAuthError() { events += "authError" }
        override fun onAuthSuccess() { events += "authSuccess" }
    }
}
