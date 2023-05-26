package pt.isel.pc.problemsets.set3

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
    private val producerQueue = ConcurrentLinkedQueue<ProducerRequest<T>>()
    private val consumerQueue = ConcurrentLinkedQueue<ConsumerRequest<T>>()
    private val messageQueue = ConcurrentLinkedQueue<T>()

    /**
     * Enqueues a message into the queue. If the queue is **full**, the operation is suspended until there is space
     * available in the queue.
     * @param message the message to enqueue.
     */
    suspend fun enqueue(message: T): Unit {
        lock.lock()
        // fast-path: if there are no pending producer requests and there is space available, enqueue the message
        if (producerQueue.isEmpty() && messageQueue.size <= capacity) {
            messageQueue.add(message)
            // TODO("complete only one or all pending consumer requests that can be completed?")
            // mark all pending consumer requests as resumable
            // if there is a message available in the queue
            while (consumerQueue.isNotEmpty() && messageQueue.isNotEmpty()) {
                val consumerRequest: ConsumerRequest<T> = consumerQueue.poll()
                consumerRequest.message = messageQueue.poll()
                consumerRequest.canResume = true
            }
            lock.unlock()
            return
        }
        val observedConsumers = consumerQueue.toList()
        // suspend-path: if there is no space available or there are pending producer requests, place
        // the continuation in the producer requests queue and suspend the coroutine until it can resume
        suspendCoroutine { continuation ->
            val producerRequest = ProducerRequest(message, continuation)
            producerQueue.add(producerRequest)
            lock.unlock()
            observedConsumers.forEach {
                if (it.canResume) {
                    if (it.deadline >= System.currentTimeMillis() - it.startTime) {
                        // deadline reached, resume with TimeoutException
                        it.continuation.resumeWithException(TimeoutException())
                    } else {
                        val retrievedMessage = it.message
                        requireNotNull(retrievedMessage) { "message cannot be null on a resumed consumer request" }
                        it.continuation.resume(retrievedMessage)
                    }
                }
            }
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
        // fast-path A: if there are no pending consumer requests and there is at least a
        // message available, dequeue the message
        if (consumerQueue.isEmpty() && messageQueue.isNotEmpty()) {
            val message: T = messageQueue.poll()
            // mark all pending producer requests as resumable if there is space available in the queue
            while (producerQueue.isNotEmpty() && messageQueue.size <= capacity) {
                val producerRequest: ProducerRequest<T> = producerQueue.poll()
                producerRequest.let {
                    messageQueue.add(it.message)
                    it.canResume = true
                }
            }
            lock.unlock()
            return message
        }
        val observedProducers = producerQueue.toList()
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
            // try to complete a pending producer request outside the lock
            observedProducers.forEach {
                if (it.canResume) {
                    it.continuation.resume(Unit)
                }
            }
        }
        return message
    }

}