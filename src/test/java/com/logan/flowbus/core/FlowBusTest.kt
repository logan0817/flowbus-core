package com.logan.flowbus.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections

class FlowBusTest {

    @Test
    fun `flowbus rejects negative buffer config`() {
        try {
            FlowBus(config = FlowBusConfig(normalBufferCapacity = -1))
            fail("Expected negative normalBufferCapacity to be rejected.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("normalBufferCapacity"))
        }

        try {
            FlowBus(config = FlowBusConfig(stickyReplay = -1))
            fail("Expected negative stickyReplay to be rejected.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("stickyReplay"))
        }

        try {
            FlowBus(config = FlowBusConfig(stickyExtraBufferCapacity = -1))
            fail("Expected negative stickyExtraBufferCapacity to be rejected.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("stickyExtraBufferCapacity"))
        }
    }

    @Test
    fun `flowbus rejects blank scope name across public scope apis`() {
        val bus = FlowBus()

        try {
            bus.scoped("   ")
            fail("Expected scoped(blank) to be rejected.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("scopeName"))
        }

        try {
            bus.openScope("   ")
            fail("Expected openScope(blank) to be rejected.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("scopeName"))
        }

        try {
            bus.removeScope("   ")
            fail("Expected removeScope(blank) to be rejected.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("scopeName"))
        }
    }

    @Test
    fun `flowbus owner rejects blank scope name`() {
        try {
            flowBusOwner("   ")
            fail("Expected flowBusOwner(blank) to be rejected.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("scopeName"))
        }
    }

    @Test
    fun `flowbus string sugar apis reject blank event name across bus variants`() = runBlocking {
        val bus = FlowBus()
        val scopedBus = bus.scoped("feature")
        val scope = bus.openScope("session")

        assertRejectsBlankEventName { bus.post(value = "root", eventName = "   ") }
        assertRejectsBlankEventNameSuspend { bus.emit(value = "root", eventName = "   ") }
        assertRejectsBlankEventName { bus.postSticky(value = "root", eventName = "   ") }
        assertRejectsBlankEventNameSuspend { bus.emitSticky(value = "root", eventName = "   ") }
        assertRejectsBlankEventName { bus.flow<String>(eventName = "   ") }
        assertRejectsBlankEventName { bus.stickyFlow<String>(eventName = "   ") }
        assertRejectsBlankEventName { bus.clearSticky<String>(eventName = "   ") }
        assertRejectsBlankEventName { bus.removeSticky<String>(eventName = "   ") }

        assertRejectsBlankEventName { scopedBus.post(value = "scoped", eventName = "   ") }
        assertRejectsBlankEventNameSuspend { scopedBus.emit(value = "scoped", eventName = "   ") }
        assertRejectsBlankEventName { scopedBus.postSticky(value = "scoped", eventName = "   ") }
        assertRejectsBlankEventNameSuspend { scopedBus.emitSticky(value = "scoped", eventName = "   ") }
        assertRejectsBlankEventName { scopedBus.flow<String>(eventName = "   ") }
        assertRejectsBlankEventName { scopedBus.stickyFlow<String>(eventName = "   ") }
        assertRejectsBlankEventName { scopedBus.clearSticky<String>(eventName = "   ") }
        assertRejectsBlankEventName { scopedBus.removeSticky<String>(eventName = "   ") }

        assertRejectsBlankEventName { scope.post(value = "scope", eventName = "   ") }
        assertRejectsBlankEventNameSuspend { scope.emit(value = "scope", eventName = "   ") }
        assertRejectsBlankEventName { scope.postSticky(value = "scope", eventName = "   ") }
        assertRejectsBlankEventNameSuspend { scope.emitSticky(value = "scope", eventName = "   ") }
        assertRejectsBlankEventName { scope.flow<String>(eventName = "   ") }
        assertRejectsBlankEventName { scope.stickyFlow<String>(eventName = "   ") }
        assertRejectsBlankEventName { scope.clearSticky<String>(eventName = "   ") }
        assertRejectsBlankEventName { scope.removeSticky<String>(eventName = "   ") }
    }

    @Test
    fun `normal post only creates normal flow`() {
        val bus = FlowBus()
        val key = eventKey<String>("demo")

        bus.post(key, "normal")

        assertTrue(bus.hasEventFlow(eventName = key.name, isSticky = false))
        assertFalse(bus.hasEventFlow(eventName = key.name, isSticky = true))
    }

    @Test
    fun `sticky post caches latest value`() {
        val bus = FlowBus()
        val key = eventKey<String>("demo")

        bus.postSticky(key, "first")
        bus.postSticky(key, "second")

        assertTrue(bus.hasEventFlow(eventName = key.name, isSticky = true))
        assertEquals(listOf("second"), bus.stickyReplayCache(key.name))
    }

    @Test
    fun `clear sticky event only clears replay cache`() {
        val bus = FlowBus()
        val key = eventKey<String>("demo")

        bus.postSticky(key, "sticky")
        bus.clearSticky(key)

        assertTrue(bus.hasEventFlow(eventName = key.name, isSticky = true))
        assertEquals(emptyList<Any>(), bus.stickyReplayCache(key.name))
    }

    @Test
    fun `remove sticky event removes cached flow`() {
        val bus = FlowBus()
        val key = eventKey<String>("demo")

        bus.postSticky(key, "sticky")
        bus.removeSticky(key)

        assertFalse(bus.hasEventFlow(eventName = key.name, isSticky = true))
        assertEquals(emptyList<Any>(), bus.stickyReplayCache(key.name))
    }

    @Test
    fun `remove sticky event clears replay cache of existing sticky flow reference`() {
        val bus = FlowBus()
        val key = eventKey<String>("demo")

        bus.postSticky(key, "sticky")
        val existingFlow = bus.stickyFlow(key) as MutableSharedFlow<String>

        assertEquals(listOf("sticky"), existingFlow.replayCache)

        bus.removeSticky(key)

        assertEquals(emptyList<String>(), existingFlow.replayCache)
    }

    @Test
    fun `scoped bus isolates events from root and other scopes`() = runBlocking {
        val bus = FlowBus()
        val key = eventKey<String>("demo")
        val featureA = bus.scoped("feature-a")
        val featureB = bus.scoped("feature-b")

        bus.postSticky(key, "root")
        featureA.postSticky(key, "feature-a")
        featureB.postSticky(key, "feature-b")

        assertEquals("root", bus.stickyFlow(key).first())
        assertEquals("feature-a", featureA.stickyFlow(key).first())
        assertEquals("feature-b", featureB.stickyFlow(key).first())
    }

    @Test
    fun `owner-backed scope points to the same scoped bus`() = runBlocking {
        val bus = FlowBus()
        val key = eventKey<String>("demo")
        val owner = flowBusOwner("session")

        bus.scoped(owner).postSticky(key, "owner-value")

        assertEquals("owner-value", bus.scoped("session").stickyFlow(key).first())
    }

    @Test
    fun `remove scope clears scoped cache`() {
        val bus = FlowBus()
        val key = eventKey<String>("demo")
        val scope = bus.scoped("session")

        scope.postSticky(key, "cached")
        scope.removeScope()

        assertFalse(bus.hasScope("session"))
    }

    @Test
    fun `remove scope clears replay cache of existing scoped sticky flow reference`() {
        val bus = FlowBus()
        val key = eventKey<String>("demo")
        val scope = bus.scoped("session")

        scope.postSticky(key, "cached")
        val existingFlow = scope.stickyFlow(key) as MutableSharedFlow<String>

        assertEquals(listOf("cached"), existingFlow.replayCache)

        scope.removeScope()

        assertEquals(emptyList<String>(), existingFlow.replayCache)
        assertFalse(bus.hasScope("session"))
    }

    @Test
    fun `typed event key rejects conflicting payload type for same channel name`() {
        val bus = FlowBus()
        bus.flow(eventKey<String>("user.state"))

        try {
            bus.flow(eventKey<Int>("user.state"))
            fail("Expected an IllegalArgumentException for conflicting event key types.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("user.state"))
        }
    }

    @Test
    fun `normal event flow keeps bounded pending events and drops oldest when overloaded`() = runBlocking {
        val bus = FlowBus(config = FlowBusConfig(normalBufferCapacity = 2))
        val key = eventKey<Int>("demo")
        val flow = bus.flow(key) as MutableSharedFlow<Int>
        val received = Collections.synchronizedList(mutableListOf<Int>())
        val firstReceived = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val job = launch {
            flow.collect { value ->
                received += value
                if (received.size == 1 && !firstReceived.isCompleted) {
                    firstReceived.complete(Unit)
                    releaseFirst.await()
                }
                if (received.size == 3 && !completed.isCompleted) {
                    completed.complete(Unit)
                }
            }
        }

        flow.subscriptionCount.first { it > 0 }
        bus.post(key, 0)
        firstReceived.await()

        bus.post(key, 1)
        bus.post(key, 2)
        bus.post(key, 3)
        bus.post(key, 4)

        releaseFirst.complete(Unit)

        withTimeout(1_000) {
            completed.await()
        }

        assertEquals(listOf(0, 3, 4), received)
        job.cancelAndJoin()
    }

    @Test
    fun `emit suspends until collector is ready when no buffer is available`() = runBlocking {
        val bus = FlowBus(
            config = FlowBusConfig(
                normalBufferCapacity = 0,
                overflowPolicy = kotlinx.coroutines.channels.BufferOverflow.SUSPEND
            )
        )
        val key = eventKey<Int>("demo")
        val flow = bus.flow(key) as MutableSharedFlow<Int>
        val firstHandled = CompletableDeferred<Unit>()
        val allowFirstToFinish = CompletableDeferred<Unit>()
        val secondEmitFinished = CompletableDeferred<Unit>()
        val received = Collections.synchronizedList(mutableListOf<Int>())

        val collectorJob = launch {
            flow.collect { value ->
                received += value
                if (value == 1 && !firstHandled.isCompleted) {
                    firstHandled.complete(Unit)
                    allowFirstToFinish.await()
                }
            }
        }

        flow.subscriptionCount.first { it > 0 }
        val firstEmitJob = launch { bus.emit(key, 1) }
        firstHandled.await()

        val secondEmitJob = launch {
            bus.emit(key, 2)
            secondEmitFinished.complete(Unit)
        }

        delay(100)
        assertFalse(secondEmitFinished.isCompleted)

        allowFirstToFinish.complete(Unit)

        withTimeout(1_000) {
            secondEmitFinished.await()
        }

        assertEquals(listOf(1, 2), received)
        firstEmitJob.cancelAndJoin()
        secondEmitJob.cancelAndJoin()
        collectorJob.cancelAndJoin()
    }

    @Test
    fun `scoped emit sticky only updates scoped bus`() = runBlocking {
        val bus = FlowBus()
        val key = eventKey<String>("demo")
        val feature = bus.scoped("feature")

        feature.emitSticky(key, "scoped-value")

        assertEquals("scoped-value", feature.stickyFlow(key).first())
        assertFalse(bus.hasEventFlow(eventName = key.name, isSticky = true))
    }

    @Test
    fun `open scope closes cache and rejects further use`() {
        val bus = FlowBus()
        val key = eventKey<String>("demo")
        val scope = bus.openScope("session")

        scope.postSticky(key, "cached")
        assertTrue(bus.hasScope("session"))

        scope.close()

        assertTrue(scope.isClosed)
        assertFalse(bus.hasScope("session"))

        try {
            scope.post(key, "again")
            fail("Expected closed FlowBusScope to reject further use.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("closed"))
        }
    }

    @Test
    fun `close waits for in-flight scope operations to finish`() = runBlocking {
        val key = eventKey<String>("demo")
        val store = FlowBusStore(FlowBusConfig())
        val storeLookupStarted = CompletableDeferred<Unit>()
        val releaseStoreLookup = CompletableDeferred<Unit>()
        val closeActionCalled = CompletableDeferred<Unit>()
        val closeCompleted = CompletableDeferred<Unit>()
        val scope = FlowBusScope(
            busScopeName = "session",
            scopedBus = ScopedFlowBus(
                scopeName = "session",
                storeProvider = {
                    if (!storeLookupStarted.isCompleted) {
                        storeLookupStarted.complete(Unit)
                    }
                    runBlocking { releaseStoreLookup.await() }
                    store
                },
                removeScopeAction = {}
            ),
            closeAction = {
                closeActionCalled.complete(Unit)
            }
        )

        val postJob = launch(Dispatchers.Default) {
            scope.post(key, "value")
        }

        storeLookupStarted.await()

        val closeJob = launch(Dispatchers.Default) {
            scope.close()
            closeCompleted.complete(Unit)
        }

        try {
            delay(100)
            assertFalse(closeActionCalled.isCompleted)
            assertFalse(closeCompleted.isCompleted)
        } finally {
            if (!releaseStoreLookup.isCompleted) {
                releaseStoreLookup.complete(Unit)
            }
        }

        withTimeout(1_000) {
            closeCompleted.await()
        }

        postJob.cancelAndJoin()
        closeJob.cancelAndJoin()
        assertTrue(scope.isClosed)
    }

    @Test
    fun `close waits for suspended scope emit to finish`() = runBlocking {
        val bus = FlowBus(
            config = FlowBusConfig(
                normalBufferCapacity = 0,
                overflowPolicy = kotlinx.coroutines.channels.BufferOverflow.SUSPEND
            )
        )
        val key = eventKey<Int>("demo")
        val scope = bus.openScope("session")
        val flow = scope.flow(key) as MutableSharedFlow<Int>
        val firstHandled = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEmitCompleted = CompletableDeferred<Unit>()
        val closeCompleted = CompletableDeferred<Unit>()

        val collectorJob = launch {
            flow.collect { value ->
                if (value == 1 && !firstHandled.isCompleted) {
                    firstHandled.complete(Unit)
                    releaseFirst.await()
                }
            }
        }

        flow.subscriptionCount.first { it > 0 }
        val firstEmitJob = launch { scope.emit(key, 1) }
        firstHandled.await()

        val secondEmitJob = launch {
            scope.emit(key, 2)
            secondEmitCompleted.complete(Unit)
        }

        delay(100)
        assertFalse(secondEmitCompleted.isCompleted)

        val closeJob = launch(Dispatchers.Default) {
            scope.close()
            closeCompleted.complete(Unit)
        }

        try {
            delay(100)
            assertFalse(closeCompleted.isCompleted)
        } finally {
            if (!releaseFirst.isCompleted) {
                releaseFirst.complete(Unit)
            }
        }

        withTimeout(1_000) {
            secondEmitCompleted.await()
            closeCompleted.await()
        }

        firstEmitJob.cancelAndJoin()
        secondEmitJob.cancelAndJoin()
        closeJob.cancelAndJoin()
        collectorJob.cancelAndJoin()
        assertTrue(scope.isClosed)
        assertFalse(bus.hasScope("session"))
    }

    @Test
    fun `closed scope cannot be reopened through scoped owner api`() {
        val bus = FlowBus()
        val scope = bus.openScope("session")
        scope.close()

        try {
            bus.scoped(scope)
            fail("Expected closed FlowBusScope to be rejected by scoped(owner).")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("closed"))
        }
    }
    @Test
    fun `error handler receives scoped sticky context for type cast failure`() = runBlocking {
        var capturedContext: FlowBusErrorContext? = null
        var capturedThrowable: Throwable? = null

        handleReceivedFlowBusEventSequentially<String>(
            value = 123,
            eventKey = eventKey<String>("demo"),
            scopeName = "session",
            isSticky = true,
            errorHandler = FlowBusErrorHandler { context, throwable ->
                capturedContext = context
                capturedThrowable = throwable
            },
            onReceived = { }
        )

        assertTrue(capturedThrowable is ClassCastException)
        assertEquals("demo", capturedContext?.eventName)
        assertEquals(String::class, capturedContext?.expectedValueType)
        assertEquals(Int::class, capturedContext?.actualValueType)
        assertEquals("session", capturedContext?.scopeName)
        assertTrue(capturedContext?.isSticky == true)
        assertEquals(FlowBusErrorPhase.ValueCast, capturedContext?.phase)
        assertNull(capturedContext?.dispatcher)
    }

    @Test
    fun `error handler receives context for subscriber callback failure`() = runBlocking {
        var capturedContext: FlowBusErrorContext? = null
        var capturedThrowable: Throwable? = null

        handleReceivedFlowBusEventSequentially<String>(
            value = "payload",
            eventKey = eventKey<String>("demo.callback"),
            scopeName = "feature-a",
            isSticky = false,
            errorHandler = FlowBusErrorHandler { context, throwable ->
                capturedContext = context
                capturedThrowable = throwable
            },
            onReceived = {
                throw IllegalStateException("boom")
            }
        )

        assertTrue(capturedThrowable is IllegalStateException)
        assertEquals("demo.callback", capturedContext?.eventName)
        assertEquals(String::class, capturedContext?.expectedValueType)
        assertEquals(String::class, capturedContext?.actualValueType)
        assertEquals("feature-a", capturedContext?.scopeName)
        assertTrue(capturedContext?.isSticky == false)
        assertEquals(FlowBusErrorPhase.SubscriberCallback, capturedContext?.phase)
        assertNull(capturedContext?.dispatcher)
    }

    @Test
    fun `class cast inside subscriber callback is reported as subscriber callback failure`() = runBlocking {
        var capturedContext: FlowBusErrorContext? = null
        var capturedThrowable: Throwable? = null

        handleReceivedFlowBusEventSequentially<String>(
            value = "payload",
            eventKey = eventKey<String>("demo.callback.cast"),
            errorHandler = FlowBusErrorHandler { context, throwable ->
                capturedContext = context
                capturedThrowable = throwable
            },
            onReceived = {
                val raw: Any = 123
                raw as String
            }
        )

        assertTrue(capturedThrowable is ClassCastException)
        assertEquals(FlowBusErrorPhase.SubscriberCallback, capturedContext?.phase)
        assertEquals(String::class, capturedContext?.expectedValueType)
        assertEquals(String::class, capturedContext?.actualValueType)
    }
    @Test
    fun `scope bound to job closes automatically when job completes`() {
        val bus = FlowBus()
        val key = eventKey<String>("demo")
        val job = Job()
        val scope = bus.openScope("session", closeWhen = job)

        scope.postSticky(key, "cached")
        assertTrue(bus.hasScope("session"))

        job.complete()

        assertTrue(scope.isClosed)
        assertFalse(bus.hasScope("session"))

        try {
            scope.post(key, "again")
            fail("Expected auto-closed FlowBusScope to reject further use.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("closed"))
        }
    }

    @Test
    fun `scope bound to coroutine scope closes automatically when parent scope is cancelled`() {
        val bus = FlowBus()
        val parentScope = CoroutineScope(SupervisorJob())
        val scope = bus.openScope("worker", closeWhen = parentScope)

        assertFalse(scope.isClosed)
        parentScope.coroutineContext[Job]!!.cancel()

        assertTrue(scope.isClosed)
        assertFalse(bus.hasScope("worker"))
    }

    @Test
    fun `binding to completed job closes scope immediately`() {
        val bus = FlowBus()
        val job = Job().apply { complete() }
        val scope = bus.openScope("finished").bindTo(job)

        assertTrue(scope.isClosed)
        assertFalse(bus.hasScope("finished"))
    }

    @Test
    fun `binding to coroutine scope without job is rejected`() {
        val bus = FlowBus()
        val scope = bus.openScope("invalid")
        val noJobScope = object : CoroutineScope {
            override val coroutineContext = EmptyCoroutineContext
        }

        try {
            scope.bindTo(noJobScope)
            fail("Expected CoroutineScope without Job to be rejected.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Job"))
        }
    }

    private fun assertRejectsBlankEventName(action: () -> Unit) {
        try {
            action()
            fail("Expected blank eventName to be rejected.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("name"))
        }
    }

    private suspend fun assertRejectsBlankEventNameSuspend(action: suspend () -> Unit) {
        try {
            action()
            fail("Expected blank eventName to be rejected.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("name"))
        }
    }
}



