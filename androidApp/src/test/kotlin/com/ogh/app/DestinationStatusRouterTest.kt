package org.aossie.ogh

import com.ogh.shared.domain.DestinationConnectionStatus
import com.ogh.shared.domain.DestinationConnectionStatus.CONNECTING
import com.ogh.shared.domain.DestinationConnectionStatus.FAILED
import com.ogh.shared.domain.DestinationConnectionStatus.LIVE
import org.aossie.ogh.viewmodel.StreamViewModel
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DestinationStatusRouterTest {

    private val router = DestinationStatusRouter()

    @Test
    fun delayedReportFromPreviousSession_doesNotChangeTheNextSessionWithTheSameDestinations() {
        val vm = StreamViewModel()

        // Session 1 publishes to destinations a and b.
        val firstPipeline = router.newPipeline()
        router.bind(vm.destinationStatusListenerFor(listOf("a", "b")))
        router.report(firstPipeline, 0, LIVE)
        vm.stopStreaming()

        // Session 2 uses the same destinations, so output indexes and IDs repeat.
        val secondPipeline = router.newPipeline()
        router.bind(vm.destinationStatusListenerFor(listOf("a", "b")))
        router.report(secondPipeline, 0, LIVE)
        router.report(secondPipeline, 1, LIVE)

        // Session 1's output #2 reports late, after session 2's map is populated.
        router.report(firstPipeline, 1, FAILED)
        router.report(firstPipeline, 0, DestinationConnectionStatus.DISCONNECTED)

        assertEquals(mapOf("a" to LIVE, "b" to LIVE), vm.destinationStatuses.value)
    }

    @Test
    fun destinationMembershipAlone_cannotTellSessionsApart() {
        // Why isolation has to happen where reports originate: the ViewModel accepts
        // any report for a destination in the current map, and a stale output index
        // maps to the same destination ID when the next session reuses destinations.
        val vm = StreamViewModel()
        vm.destinationStatusListenerFor(listOf("a", "b"))
        val secondSession = vm.destinationStatusListenerFor(listOf("a", "b"))
        secondSession(1, LIVE)

        secondSession(1, FAILED)

        assertEquals(FAILED, vm.destinationStatuses.value["b"])
    }

    @Test
    fun reportsBeforeBindingOrAfterClear_areDropped() {
        val received = CopyOnWriteArrayList<Pair<Int, DestinationConnectionStatus>>()
        val pipeline = router.newPipeline()

        router.report(pipeline, 0, CONNECTING)
        router.bind { index, status -> received += index to status }
        router.report(pipeline, 0, LIVE)
        router.clear()
        router.report(pipeline, 0, FAILED)

        assertEquals(listOf(0 to LIVE), received.toList())
    }

    @Test
    fun newPipeline_stopsReportsToTheListenerOfTheOldOne() {
        val received = CopyOnWriteArrayList<Pair<Int, DestinationConnectionStatus>>()
        val old = router.newPipeline()
        router.bind { index, status -> received += index to status }

        val current = router.newPipeline()
        router.report(old, 0, FAILED)
        router.report(current, 0, LIVE)

        assertTrue(received.isEmpty())
    }

    @Test
    fun rebinding_waitsForTheReportBeingDelivered() {
        val order = CopyOnWriteArrayList<String>()
        val delivering = CountDownLatch(1)
        val finishDelivery = CountDownLatch(1)
        val pipeline = router.newPipeline()
        router.bind { _, _ ->
            order += "delivery started"
            delivering.countDown()
            finishDelivery.await(5, TimeUnit.SECONDS)
            order += "delivery finished"
        }

        val report = thread { router.report(pipeline, 0, LIVE) }
        assertTrue(delivering.await(5, TimeUnit.SECONDS))
        // For example the session ending on the MediaProjection callback thread.
        val newSession = thread {
            router.newPipeline()
            order += "next pipeline"
        }
        newSession.join(300)
        assertEquals(listOf("delivery started"), order.toList())

        finishDelivery.countDown()
        report.join(5_000)
        newSession.join(5_000)
        assertEquals(listOf("delivery started", "delivery finished", "next pipeline"), order.toList())
    }
}
