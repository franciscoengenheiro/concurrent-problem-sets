package pt.isel.pc.problemsets.set3

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Represents a thread-safe queue that can be used to exchange messages between threads
 * in a *non-blocking* way, using *asynchronous* operations that take advantage of coroutines and suspending functions.
 * This class provides a coroutine synchronization mechanism which is not present in the standard library.
 * The queue is bounded, which means that it has a maximum [capacity].
 * This queue orders elements in FIFO (*first-in-first-out*) ordering.
 * @param capacity the maximum number of messages that can be enqueued. Once created, the capacity cannot be changed.
 * @throws IllegalArgumentException if [capacity] is less than 1.
 */

class AsyncMessageQueue<T>(private val capacity: Int) {
    init {
        require(capacity >= 1) { "capacity must be greater than 0" }
    }

    private val lock: Lock = ReentrantLock()

    private class ProducerRequest<T>(
        val message: T,
        val continuation: Continuation<Unit>,
        var canResume: Boolean = false
    )

    private class ConsumerRequest<T>(
        val duration: Duration,
        val continuation: Continuation<T>,
        var canResume: Boolean = false
    )

    // Queues
    private val producerQueue = ConcurrentLinkedQueue<ProducerRequest<T>>()
    private val consumerQueue = ConcurrentLinkedQueue<ConsumerRequest<T>>()
    private val messageQueue = ConcurrentLinkedQueue<T>()

    // TODO(Notes:
    //  1. A continuation cannot be called inside a lock, works similar to future's callbacks.
    //  2. Although coroutines are used, the mutable state of the queue is still accessed by multiple threads.
    //  3. Use Kernel-style synchronization?!
    //  4. If a coroutine sees the message queue is full,
    //  it places the continuation in the queue and suspends itself.
    /**
     * Enqueues a message into the queue. If the queue is **full**, the operation is suspended until there is space
     * available in the queue.
     * @param message the message to enqueue.
     */
    suspend fun enqueue(message: T) {
        // fast-path: if there are no pending producer requests and there is space available, enqueue the message
        lock.withLock {
            if (producerQueue.isEmpty() && messageQueue.size < capacity) {
                messageQueue.add(message)
                val consumerRequest = consumerQueue.poll()
                // "signal" the consumer request that it can resume
                // necessary since no continuation method can be called inside a lock
                consumerRequest.canResume = true
                return
            }
        }
        // suspend-path: if there is no space available or there are pending producer requests, place
        // the continuation in the producer requests queue and suspend the coroutine
        return suspendCoroutine { continuation ->
            val producerRequest = ProducerRequest(message, continuation)
            producerQueue.add(producerRequest)
            // TODO("additional logic here")
        }
    }

    /**
     * Dequeues a message from the queue.
     * If the queue is **empty**, the operation is suspended until there is a message
     * available in the queue or the specified [timeout] is reached.
     * @param timeout the maximum time to await while suspended for a message to be available.
     * @return the message dequeued.
     * @throws TimeoutException if the specified [timeout] is reached.
     */
    @Throws(TimeoutException::class)
    suspend fun dequeue(timeout: Duration): T {
        // fast-path A: if there are no pending consumer requests and there is a message available, dequeue the message
        lock.withLock {
            if (consumerQueue.isEmpty() && messageQueue.isNotEmpty()) {
                val message: T = messageQueue.poll()
                val producerRequest = producerQueue.poll()
                // "signal" the producer request that it can resume
                // necessary since no continuation method can be called inside a lock
                producerRequest.canResume = true
                return message
            }
        }
        // fast-path B: the thread executing the coroutine does not want it to be suspended
        if (timeout == 0.seconds) {
            throw TimeoutException()
        }

        // suspend-path: if there is no message available or there are pending consumer requests, place
        // the continuation in the consumer requests queue and suspend the coroutine
        return suspendCoroutine { continuation ->
            // keep reference to the consumer request so that it can be removed from the queue
            // when the coroutine is resumed or canceled
            val consumerRequest = ConsumerRequest(timeout, continuation)
            consumerQueue.add(consumerRequest)
            // TODO("additional logic here, like cancellation")
            // TODO("this logic might needs to be placed elsewhere")
            // TODO("need to suspend again, at least for timeout duration, but how?")
            if (consumerRequest.canResume) {
                // Do not need to remove since the coroutine that enabled the continuation
                // already took the consumer request from the queue
                continuation.resume(???)
            } else {
                // if the coroutine is resumed, remove the consumer request from the queue
                // and "signal" the consumer request that it can resume
                consumerQueue.remove(consumerRequest)
                continuation.resumeWithException(TimeoutException())
            }
        }
    }

    private suspend fun completeProducerRequests()
}