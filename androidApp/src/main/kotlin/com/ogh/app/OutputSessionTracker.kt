package org.aossie.ogh

import com.pedro.common.ConnectChecker

/**
 * Aggregates the per-output RTMP callbacks of one pipeline into the single
 * session-level [ConnectChecker] that the ViewModel observes, and reports when
 * every output of a pipeline has ended so the session that owns it can be
 * released.
 *
 * RootEncoder delivers callbacks asynchronously, including after the service
 * has released a pipeline and built a new one, possibly with fewer outputs. Each
 * output checker therefore stays bound to the [Pipeline] it was created for, and
 * callbacks from any pipeline other than the current one are ignored instead of
 * indexing into, or changing, the current session's state.
 *
 * Threading: RootEncoder 2.7.5 delivers every callback Ogh receives on the main
 * thread, but the service replaces pipelines from several threads (main, the
 * idle-preview coroutine, and the MediaProjection callback thread). The
 * generation check and the dispatch to [session] therefore happen under one
 * lock, so once [startGeneration] or [retire] returns, no callback from an
 * earlier pipeline is still being delivered or can start. Lock order is always
 * caller (for example the service monitor) → this tracker's lock → [session] /
 * [onAllOutputsEnded]; those two must not call back into the service or this
 * tracker synchronously.
 *
 * Holds no Android types so the lifecycle rules are covered by JVM tests.
 */
internal class OutputSessionTracker(
    private val session: () -> ConnectChecker?,
    private val onAllOutputsEnded: (Pipeline) -> Unit = {},
    private val log: (message: String, isError: Boolean) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private var current: Pipeline? = null

    /** One MultiStream's outputs. Opaque outside the tracker; identifies the session that owns it. */
    class Pipeline internal constructor(internal val outputCount: Int) {
        internal val connected = BooleanArray(outputCount)
        internal val terminal = BooleanArray(outputCount)

        /** Every output failed or disconnected; the pipeline's later callbacks are ignored. */
        internal var ended = false

        /** Marks [index] as ended; true when no output is still connected or connecting. */
        internal fun end(index: Int): Boolean {
            connected[index] = false
            terminal[index] = true
            return connected.none { it } && terminal.all { it }
        }
    }

    /**
     * Starts a new pipeline and returns one checker per output, in output-index
     * order. Waits for any callback that is still being delivered; callbacks from
     * earlier pipelines are ignored from now on.
     */
    fun startGeneration(outputCount: Int): Array<ConnectChecker> {
        require(outputCount > 0) { "At least one output is required" }
        val pipeline = Pipeline(outputCount)
        synchronized(lock) { current = pipeline }
        return Array(outputCount) { index -> OutputChecker(pipeline, index) }
    }

    /** Ignores every remaining callback, for example after the service is destroyed. */
    fun retire() {
        synchronized(lock) { current = null }
    }

    /** Whether [pipeline] is still the current one, i.e. its session has not been released. */
    fun isCurrent(pipeline: Pipeline): Boolean = synchronized(lock) { current === pipeline }

    /**
     * Whether a new session may keep using the current pipeline. A pipeline whose
     * outputs have all ended belongs to the session that just failed, and its
     * release is tied to that pipeline, so it is never handed to a new session.
     */
    fun canReuseCurrent(outputCount: Int): Boolean = synchronized(lock) {
        val pipeline = current
        pipeline != null && !pipeline.ended && pipeline.outputCount == outputCount
    }

    private inner class OutputChecker(
        private val pipeline: Pipeline,
        private val index: Int,
    ) : ConnectChecker {

        /** Runs [deliver] only while this output's pipeline is current and has not ended. */
        private fun whileCurrent(deliver: Pipeline.() -> Unit) {
            synchronized(lock) {
                if (current === pipeline && !pipeline.ended) pipeline.deliver()
            }
        }

        /** Marks this output ended; once all have, reports the end exactly once. */
        private fun Pipeline.endOutput(message: String, isError: Boolean, notify: ConnectChecker.() -> Unit) {
            log(message, isError)
            if (!end(index)) return
            ended = true
            session()?.notify()
            onAllOutputsEnded(this)
        }

        override fun onConnectionStarted(url: String) = whileCurrent {
            terminal[index] = false
            log("Destination ${index + 1} connecting", false)
            if (connected.none { it }) session()?.onConnectionStarted("")
        }

        override fun onConnectionSuccess() = whileCurrent {
            val wasAnyConnected = connected.any { it }
            connected[index] = true
            terminal[index] = false
            log("Destination ${index + 1} connected", false)
            if (!wasAnyConnected) session()?.onConnectionSuccess()
        }

        // RootEncoder errors may contain a complete RTMP URL and stream key, so the
        // reason is never logged or forwarded.
        override fun onConnectionFailed(reason: String) = whileCurrent {
            endOutput("Destination ${index + 1} failed", isError = true) {
                onConnectionFailed(ALL_OUTPUTS_FAILED)
            }
        }

        override fun onNewBitrate(bitrate: Long) = whileCurrent {
            session()?.onNewBitrate(bitrate)
        }

        override fun onDisconnect() = whileCurrent {
            endOutput("Destination ${index + 1} disconnected", isError = false) {
                onDisconnect()
            }
        }

        override fun onAuthError() = whileCurrent {
            endOutput("Destination ${index + 1} authentication failed", isError = true) {
                onAuthError()
            }
        }

        override fun onAuthSuccess() = whileCurrent {
            log("Destination ${index + 1} authentication succeeded", false)
            session()?.onAuthSuccess()
        }
    }

    companion object {
        const val ALL_OUTPUTS_FAILED = "All destinations failed to connect"
    }
}

/**
 * Stops every output, then the shared encoder session if it is still marked as
 * streaming. MultiStream ends that session only when no client reports
 * streaming, and a client whose connection failed can still report it, which
 * would leave the session open and make the next prepareAudio() fail.
 */
internal fun stopAllOutputs(
    outputCount: Int,
    stopOutput: (index: Int) -> Unit,
    isSessionStreaming: () -> Boolean,
    stopSession: () -> Unit,
) {
    repeat(outputCount, stopOutput)
    if (isSessionStreaming()) stopSession()
}
