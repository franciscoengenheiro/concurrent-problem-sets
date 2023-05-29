package pt.isel.pc.problemsets.set3

import java.util.LinkedList
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * This class represents a synchronized message queue that allows producers to enqueue messages and consumers to
 * dequeue messages. The queue is implemented using coroutines, which allows the operations to be performed
 * in a *non-blocking* way, using *asynchronous* operations that take advantage of coroutines possible
 * suspension points.
 * The queue is bounded, which means that it has a maximum [capacity].
 * This queue orders elements in FIFO (*first-in-first-out*) ordering, to avoid suspending a coroutine indefinitely.
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
        val startTime: Long,
        val deadline: Long,
        val continuation: Continuation<T>,
        var message: T? = null,
        var canResume: Boolean = false,
    )

    // Queues
    // TODO("use non thread-safe queues, if possible use NodeLinkedList<T>")
    private val producerQueue = LinkedList<ProducerRequest<T>>()
    private val consumerQueue = LinkedList<ConsumerRequest<T>>()
    private val messageQueue = LinkedList<T>()

    /**
     * Enqueues a message into the queue. If the queue is **full**, the operation is suspended until there is space
     * available in the queue.
     * @param message the message to enqueue.
     */
    suspend fun enqueue(message: T): Unit {
        lock.lock()
        val observedConsumers = consumerQueue.toList()
        // fast-path: if there are no pending producer requests and there is space available, enqueue the message
        if (producerQueue.isEmpty() && messageQueue.size <= capacity) {
            messageQueue.add(message)
            // mark a pending consumer request if there is a message available in the queue
            if (consumerQueue.isNotEmpty() && messageQueue.isNotEmpty()) {
                val consumerRequest: ConsumerRequest<T> = consumerQueue.poll()
                consumerRequest.let {
                    // if the consumer request has not expired, complete it
                    // TODO(learn more about cancellation operations)
                    if (it.deadline >= System.currentTimeMillis() - it.startTime) {
                        // deadline did not expire, since it's bigger than the elapsed time, and as such,
                        // retrieve the message from the queue and add it to the consumer request
                        it.message = messageQueue.poll()
                    }
                    it.canResume = true
                }
            }
            lock.unlock()
            completeConsumerRequests(observedConsumers)
            return
        }
        // suspend-path: if there is no space available or there are pending producer requests, place
        // the continuation in the producer requests queue and suspend the coroutine until it can resume
        suspendCoroutine { continuation ->
            val producerRequest = ProducerRequest(message, continuation)
            producerQueue.add(producerRequest)
            lock.unlock()
            // try to complete a pending consumer requests before suspending
            completeConsumerRequests(observedConsumers)
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
        lock.lock()
        val observedProducers = producerQueue.toList()
        // fast-path A: if there are no pending consumer requests and there is at least a
        // message available, dequeue the message
        if (consumerQueue.isEmpty() && messageQueue.isNotEmpty()) {
            val message: T = messageQueue.poll()
            // mark a pending producer request if there is space available in the queue
            if (producerQueue.isNotEmpty() && messageQueue.size <= capacity) {
                val producerRequest: ProducerRequest<T> = producerQueue.poll()
                producerRequest.let {
                    messageQueue.add(it.message)
                    it.canResume = true
                }
            }
            lock.unlock()
            completeProducerRequests(observedProducers)
            return message
        }
        val message: T = suspendCoroutine { continuation ->
            // fast-path B: if the specified timeout is 0, throw a TimeoutException and do not suspend the coroutine
            if (timeout == 0.seconds) {
                lock.unlock()
                continuation.resumeWithException(TimeoutException())
            } else {
                // suspend-path: if there is no message available or there are pending consumer requests, place
                // the continuation in the consumer requests queue and suspend the coroutine until it can resume
                val consumerRequest = ConsumerRequest(
                    startTime = System.currentTimeMillis(),
                    deadline = timeout.inWholeMilliseconds,
                    continuation = continuation
                )
                consumerQueue.add(consumerRequest)
                lock.unlock()
            }
            completeProducerRequests(observedProducers)
        }
        return message
    }

    /**
     * Completes all pending consumer requests that were marked as **resumable**.
     * This method should not be used inside a lock because resuming a coroutine
     * can have side effects, such as executing code that was waiting
     * for the coroutine to resume and that could hold the lock indefinitely.
     * @param observedConsumers the list of pending consumer requests that were observed when the
     * coroutine entered the suspend function.
     */
    private fun completeConsumerRequests(observedConsumers: List<ConsumerRequest<T>>) =
        observedConsumers.forEach {
            if (it.canResume) {
                if (it.message == null) {
                    // if a consumer request has no message, it means that it was resumed
                    // because the timeout was reached, and as such a message wasn't dequeued
                    it.continuation.resumeWithException(TimeoutException())
                } else {
                    val retrievedMessage = it.message
                    requireNotNull(retrievedMessage) {
                        "message cannot be null on a resumed consumer request with a message"
                    }
                    it.continuation.resume(retrievedMessage)
                }
            }
        }

    /**
     * Completes all pending producer requests that were marked as **resumable**.
     * This method should not be used inside a lock because resuming a coroutine
     * can have side effects, such as executing code that was waiting
     * for the coroutine to resume and that could hold the lock indefinitely.
     * @param observedProducers the list of pending producer requests that were observed when the
     * coroutine entered the suspend function.
     */
    private fun completeProducerRequests(observedProducers: List<ProducerRequest<T>>) =
        observedProducers.forEach {
            if (it.canResume) {
                it.continuation.resume(Unit)
            }
        }

}