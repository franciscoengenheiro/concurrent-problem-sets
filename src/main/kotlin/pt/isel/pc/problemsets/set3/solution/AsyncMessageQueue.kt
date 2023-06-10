package pt.isel.pc.problemsets.set3.solution

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import pt.isel.pc.problemsets.util.NodeLinkedList
import java.util.*
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
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

    // Represents a producer coroutine request
    private class ProducerRequest<T>(
        val message: T,
        val continuation: CancellableContinuation<Unit>,
        var canResume: Boolean = false
    )

    // Represents a consumer coroutine request
    private class ConsumerRequest<T>(
        val continuation: CancellableContinuation<T>,
        var message: T? = null,
        var canResume: Boolean = false
    )

    // Queues
    private val producerQueue = NodeLinkedList<ProducerRequest<T>>()
    private val consumerQueue = NodeLinkedList<ConsumerRequest<T>>()
    private val messageQueue = NodeLinkedList<T>()

    /**
     * Enqueues a message into the queue. If the queue is **full**, the operation is suspended until there is space
     * available in the queue.
     * This method is syncronized with the coroutine cancellation protocol.
     * @param message the message to enqueue.
     * @throws CancellationException if the coroutine is canceled.
     */
    @Throws(CancellationException::class)
    suspend fun enqueue(message: T): Unit {
        lock.lock()
        // fast-path: if there are no pending producer requests and there is space available, enqueue the message
        if (producerQueue.empty && messageQueue.count < capacity) {
            messageQueue.enqueue(message)
            var consumerRequest: ConsumerRequest<T>? = null
            // mark a pending consumer request if there is a message available in the queue
            if (consumerQueue.notEmpty && messageQueue.notEmpty) {
                consumerRequest = consumerQueue.pull().value
                consumerRequest.message = messageQueue.pull().value
                consumerRequest.canResume = true
            }
            lock.unlock()
            consumerRequest?.let {
                val msg = it.message
                requireNotNull(msg) { "message cannot be null inside a resumed consumer request" }
                it.continuation.resume(msg)
            }
            return
        }
        // suspend-path: if there is no space available or there are pending producer requests, place
        // the continuation in the producer requests queue and suspend the coroutine until it can resume
        var producerRequestNode: NodeLinkedList.Node<ProducerRequest<T>>? = null
        try {
            return suspendCancellableCoroutine { continuation ->
                val producerRequest = ProducerRequest(message, continuation)
                producerRequestNode = producerQueue.enqueue(producerRequest)
                lock.unlock()
            }
        } catch (ex: CancellationException) {
            lock.withLock {
                val observedRequestNode = producerRequestNode
                if (observedRequestNode != null) {
                    if (observedRequestNode.value.canResume) {
                        return
                    } else {
                        producerQueue.remove(observedRequestNode)
                    }
                }
                throw ex
            }
        }
    }

    /**
     * Dequeues a message from the queue.
     * If the queue is **empty**, the operation is suspended until there is a message
     * available in the queue or the specified [timeout] is reached.
     * This method is syncronized with the coroutine cancellation protocol.
     * @param timeout the maximum time to await while suspended for a message to be available.
     * @return the message dequeued.
     * @throws TimeoutException if the specified [timeout] is reached.
     * @throws CancellationException if the coroutine is canceled.
     */
    @Throws(TimeoutException::class, CancellationException::class)
    suspend fun dequeue(timeout: Duration): T {
        lock.lock()
        // fast-path A: if there are no pending consumer requests and there is at least a
        // message available, dequeue the message
        if (consumerQueue.empty && messageQueue.notEmpty) {
            val message: T = messageQueue.pull().value
            // mark a pending producer request if there is space available in the queue
            var producerRequest: ProducerRequest<T>? = null
            if (producerQueue.notEmpty && messageQueue.count <= capacity) {
                producerRequest = producerQueue.pull().value
                producerRequest.let {
                    messageQueue.enqueue(it.message)
                    it.canResume = true
                }
            }
            lock.unlock()
            producerRequest?.continuation?.resume(Unit)
            return message
        }
        // fast-path B: if the specified timeout is 0, throw a TimeoutException
        if (timeout == 0.seconds) {
            lock.unlock()
            throw TimeoutException()
        }
        var consumerRequestNode: NodeLinkedList.Node<ConsumerRequest<T>>? = null
        return try {
            // the request could be completed even though the timeout was reached
            withTimeoutOrNull(timeout) {
                suspendCancellableCoroutine { continuation ->
                    // suspend-path: if there is no message available or there are pending consumer requests, place
                    // the continuation in the consumer requests queue and suspend the coroutine until it can resume
                    val consumerRequest: ConsumerRequest<T> = ConsumerRequest(continuation)
                    consumerRequestNode = consumerQueue.enqueue(consumerRequest)
                    lock.unlock()
                }
            } ?: messageOrException(consumerRequestNode, TimeoutException())
        } catch (ex: CancellationException) {
            messageOrException(consumerRequestNode, ex)
        }
    }

    /**
     * Observes a consumer request and makes a decision based on the state of that request, to either return
     * the message or throw an exception.
     * If the consumer request node wasn't initialized (start value is null), it means that the consumer request
     * wasn't even placed in the consumer queue, so the exception is thrown.
     * @param observedConsumerRequestNode the node of the consumer request to observe.
     * @param exceptionToThrow the exception to throw if the consumer request cannot resume successfully.
     */
    private fun messageOrException(
        observedConsumerRequestNode: NodeLinkedList.Node<ConsumerRequest<T>>?,
        exceptionToThrow: Exception
    ): T & Any {
        lock.withLock {
            if (observedConsumerRequestNode != null) {
                val consumerRequest = observedConsumerRequestNode.value
                if (consumerRequest.canResume) {
                    val message: T? = consumerRequest.message
                    requireNotNull(message) { "message of a resumed consumer request cannot be null" }
                    return message
                } else {
                    consumerQueue.remove(observedConsumerRequestNode)
                }
            }
            throw exceptionToThrow
        }
    }
}