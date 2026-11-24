package org.aossie.ogh

import com.ogh.shared.domain.DestinationConnectionStatus

/**
 * Delivers per-output connection status to the session that is currently
 * publishing, and only to it.
 *
 * Output indexes are reused by every session, and so are destination IDs when
 * the same destinations stay enabled. A status callback delivered late by a
 * previous pipeline (for example an output that was still connecting when Stop
 * was pressed) would otherwise reach the new session's listener and change the
 * status shown for the same destination. Each pipeline therefore reports with
 * the [Pipeline] it was created for, and a listener only receives reports from
 * the pipeline that was current when it was bound.
 *
 * Reports are delivered under this router's lock, so once [bind], [clear], or
 * [newPipeline] returns no earlier report is still being delivered. Listeners
 * must not call back into the service or this router.
 */
internal class DestinationStatusRouter {

    /** Identifies one pipeline's outputs. */
    class Pipeline internal constructor()

    private val lock = Any()
    private var current: Pipeline? = null
    private var boundPipeline: Pipeline? = null
    private var listener: ((Int, DestinationConnectionStatus) -> Unit)? = null

    /** Starts a new pipeline; any listener bound to an earlier one stops receiving reports. */
    fun newPipeline(): Pipeline {
        val pipeline = Pipeline()
        synchronized(lock) {
            current = pipeline
            boundPipeline = null
            listener = null
        }
        return pipeline
    }

    /** Binds [listener] to the current pipeline, replacing any earlier listener. */
    fun bind(listener: (index: Int, status: DestinationConnectionStatus) -> Unit) {
        synchronized(lock) {
            boundPipeline = current
            this.listener = listener
        }
    }

    /** Stops delivering reports, for example when the session ends. */
    fun clear() {
        synchronized(lock) {
            boundPipeline = null
            listener = null
        }
    }

    /** Delivers [status] for output [index] only if [pipeline] is the one the listener is bound to. */
    fun report(pipeline: Pipeline, index: Int, status: DestinationConnectionStatus) {
        synchronized(lock) {
            if (pipeline === boundPipeline) listener?.invoke(index, status)
        }
    }
}
