package com.example.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AssessmentWriteQueue(
    private val scope: CoroutineScope,
    private val onFailure: (Throwable) -> Unit
) {
    private data class Request(
        val operation: suspend () -> Unit,
        val completion: CompletableDeferred<Result<Unit>>
    )

    private val mutex = Mutex()
    private val channel = Channel<Request>(Channel.UNLIMITED)
    private val writerJob: Job = scope.launch {
        for (request in channel) {
            val result = runCatching { mutex.withLock { request.operation() } }
            result.onFailure(onFailure)
            request.completion.complete(result)
        }
    }

    fun tryEnqueue(operation: suspend () -> Unit): Boolean {
        val completion = CompletableDeferred<Result<Unit>>()
        return channel.trySend(Request(operation, completion)).isSuccess
    }

    suspend fun enqueueAndAwait(operation: suspend () -> Unit) {
        val completion = CompletableDeferred<Result<Unit>>()
        channel.send(Request(operation, completion))
        completion.await().getOrThrow()
    }

    suspend fun closeAndDrain() {
        channel.close()
        writerJob.join()
    }
}
