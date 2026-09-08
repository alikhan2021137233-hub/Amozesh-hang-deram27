package com.example.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AssessmentWriteQueueTest {
    @Test
    fun closeAndDrainRunsBufferedWritesBeforeReturning() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queue = AssessmentWriteQueue(scope) { throw AssertionError("unexpected failure", it) }
        val completed = AtomicInteger(0)

        repeat(3) {
            assertTrue(queue.tryEnqueue { completed.incrementAndGet() })
        }

        queue.closeAndDrain()

        assertEquals(3, completed.get())
    }

    @Test
    fun acknowledgementCompletesOnlyAfterOperationReturns() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queue = AssessmentWriteQueue(scope) { throw AssertionError("unexpected failure", it) }
        val operationFinished = CompletableDeferred<Unit>()
        val releaseOperation = CompletableDeferred<Unit>()
        val acknowledgement = async {
            queue.enqueueAndAwait {
                releaseOperation.await()
                operationFinished.complete(Unit)
            }
        }

        assertTrue(!operationFinished.isCompleted)
        assertTrue(!acknowledgement.isCompleted)
        releaseOperation.complete(Unit)
        withTimeout(1_000L) { acknowledgement.await() }
        assertTrue(operationFinished.isCompleted)
        queue.closeAndDrain()
    }

    @Test
    fun failedWriteIsReportedAndAcknowledgementFails() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val failure = CompletableDeferred<Throwable>()
        val queue = AssessmentWriteQueue(scope, failure::complete)

        val result = runCatching { queue.enqueueAndAwait { error("room failure") } }
        assertTrue(result.isFailure)
        assertEquals("room failure", withTimeout(1_000L) { failure.await() }.message)
        queue.closeAndDrain()
    }
}