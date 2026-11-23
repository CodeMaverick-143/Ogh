package org.aossie.ogh

import com.pedro.common.ConnectChecker

/**
 * Aggregates the per-output RTMP callbacks of one pipeline into the single
 * session-level [ConnectChecker] that the ViewModel observes.
 *
 * RootEncoder delivers callbacks asynchronously, including after the service
 * has released a pipeline and built a new one, possibly with fewer outputs. Each
 * output checker therefore stays bound to the pipeline generation it was created
 * for, and callbacks from any generation other than the current one are ignored
 * instead of indexing into, or changing, the current session's state.
 *
 * Holds no Android types so the lifecycle rules are covered by JVM tests.
 */
internal class OutputSessionTracker(
    private val session: () -> ConnectChecker?,
    private val log: (message: String, isError: Boolean) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private var current: Generation? = null

    private class Generation(outputCount: Int) {
        val connected = BooleanArray(outputCount)
        val terminal = BooleanArray(outputCount)

        /** Marks [index] as ended; true when no output is still connected or connecting. */
        fun end(index: Int): Boolean {
            connected[index] = false
            terminal[index] = true
            return connected.none { it } && terminal.all { it }
        }
    }

    /**
     * Starts a new pipeline generation and returns one checker per output, in
     * output-index order. Callbacks from earlier generations are ignored from now on.
     */
    fun startGeneration(outputCount: Int): Array<ConnectChecker> {
        require(outputCount > 0) { "At least one output is required" }
        val generation = Generation(outputCount)
        synchronized(lock) { current = generation }
        return Array(outputCount) { index -> OutputChecker(generation, index) }
    }

    /** Ignores every remaining callback, for example after the service is destroyed. */
    fun retire() {
        synchronized(lock) { current = null }
    }

    private inner class OutputChecker(
        private val generation: Generation,
        private val index: Int,
    ) : ConnectChecker {

        /** Runs [update] only while this output's generation is current; null otherwise. */
        private fun <T> ifCurrent(update: Generation.() -> T): T? = synchronized(lock) {
            if (current === generation) generation.update() else null
        }

        override fun onConnectionStarted(url: String) {
            val firstAttempt = ifCurrent {
                terminal[index] = false
                connected.none { it }
            } ?: return
            log("Destination ${index + 1} connecting", false)
            if (firstAttempt) session()?.onConnectionStarted("")
        }

        override fun onConnectionSuccess() {
            val firstConnection = ifCurrent {
                val wasAnyConnected = connected.any { it }
                connected[index] = true
                terminal[index] = false
                !wasAnyConnected
            } ?: return
            log("Destination ${index + 1} connected", false)
            if (firstConnection) session()?.onConnectionSuccess()
        }

        override fun onConnectionFailed(reason: String) {
            val allEnded = ifCurrent { end(index) } ?: return
            // RootEncoder errors may contain a complete RTMP URL and stream key.
            log("Destination ${index + 1} failed", true)
            if (allEnded) session()?.onConnectionFailed(ALL_OUTPUTS_FAILED)
        }

        override fun onNewBitrate(bitrate: Long) {
            ifCurrent { } ?: return
            session()?.onNewBitrate(bitrate)
        }

        override fun onDisconnect() {
            val allEnded = ifCurrent { end(index) } ?: return
            log("Destination ${index + 1} disconnected", false)
            if (allEnded) session()?.onDisconnect()
        }

        override fun onAuthError() {
            val allEnded = ifCurrent { end(index) } ?: return
            log("Destination ${index + 1} authentication failed", true)
            if (allEnded) session()?.onAuthError()
        }

        override fun onAuthSuccess() {
            ifCurrent { } ?: return
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
