package pt.isel.pc.problemsets.set1

import pt.isel.pc.problemsets.util.NodeLinkedList
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/**
 * Similar to a [ArrayBlockingQueue], this syncronizer supports the communication between multiple threads
 * or processes. An internal queue is used to store messages that are inserted by producer threads and extracted
 * by consumer threads. This queue orders elements in FIFO (*first-in-first-out*) order to avoid thread starvation.
 * The *head* of the queue is the element that has been on the queue the longest time and the *tail* of the queue
 * the element that has been on the queue the shortest time. New elements are inserted at the tail of the queue,
 * and the queue retrieval operations obtain elements at the head of the queue.
 * The *blocking* term comes from the fact that any attempt to put an element into a full queue will result in the
 * operation blocking and any attempt to take an element from an empty queue will have the same effect.
 * @param T the type of the messages that will be exchanged.
 * @param capacity the maximum number of messages that can be enqueued. Once created, the capacity cannot be changed.
 * @throws IllegalArgumentException if [capacity] is less than 1.
 */
class BlockingMessageQueue<T>(private val capacity: Int) {
    init {
        require(capacity > 0) { "Message queue capacity must be a natural number." }
    }

    private val lock: Lock = ReentrantLock()

    // Each producer request represents a thread request to enqueue a message
    private class ProducerRequest<T>(
        val message: T,
        val condition: Condition,
        var canEnqueue: Boolean = false
    )

    // Each consumer request represents a thread request to dequeue a set of messages
    private class ConsumerRequest<T>(
        val nOfMessages: Int,
        val condition: Condition,
        var messages: List<T> = emptyList(),
        var canDequeue: Boolean = false
    )

    // Queues
    private val producerRequestsQueue = NodeLinkedList<ProducerRequest<T>>()
    private val consumerRequestsQueue = NodeLinkedList<ConsumerRequest<T>>()
    private val messageQueue: NodeLinkedList<T> = NodeLinkedList()

    /**
     * Tries to enqueue a message. If the queue is full, the calling thread will be blocked until
     * the message can be enqueued without exceeding the queue capacity or delivered to a consumer thread.
     * @param message the message to be enqueued.
     * @param timeout the maximum time to wait for the message to be enqueued.
     * @returns true if the message was enqueued or delivered to a consumer thread withing the [timeout] duration,
     * false otherwise.
     * @throws InterruptedException if the current thread is interrupted while waiting to enqueue a [message].
     * Note that if the current thread is interrupted but can enqueue, it will return true and not throw
     * [InterruptedException] unless it's blocked again.
     */
    @Throws(InterruptedException::class)
    fun tryEnqueue(message: T, timeout: Duration): Boolean {
        lock.withLock {
            // fast-path -> The thread that tries to enqueue a message can do it immediately because it
            // is the first thread at the head of the producer requests queue and the message queue is at full
            // capacity, and as such, it can enqueue the message
            if (producerRequestsQueue.empty && messageQueue.count < capacity) {
                messageQueue.enqueue(message)
                tryToCompleteConsumerRequests()
                return true
            }
            // wait-path -> The thread that tries to enqueue a message cannot do it because it is not the first
            // thread at the head of the producer requests queue or the message queue is full, and as such,
            // it must wait for its turn to enqueue a message
            var remainingNanos = timeout.inWholeNanoseconds
            val localRequest = producerRequestsQueue.enqueue(
                ProducerRequest(message, lock.newCondition())
            )
            while (true) {
                try {
                    remainingNanos = localRequest.value.condition.awaitNanos(remainingNanos)
                } catch (e: InterruptedException) {
                    if (localRequest.value.canEnqueue) {
                        // Arm the interrupt flag in order to not lose the interruption request
                        // If this thread is blocked again it will throw an InterruptedException
                        Thread.currentThread().interrupt()
                        // This thread cannot giveup since it's request was completed
                        tryToCompleteConsumerRequests()
                        return true
                    }
                    // Giving-up by interruption, remove value from the producer requests queue
                    producerRequestsQueue.remove(localRequest)
                    tryToCompleteTheNextProducerRequest()
                    throw e
                }
                if (localRequest.value.canEnqueue) {
                    tryToCompleteConsumerRequests()
                    return true
                }
                if (remainingNanos <= 0) {
                    // Giving-up by timeout, remove value from the producer requests queue
                    producerRequestsQueue.remove(localRequest)
                    tryToCompleteTheNextProducerRequest()
                    return false
                }
            }
        }
    }

    /**
     * Tries to dequeue a set of messages. If the queue is empty, the calling thread will be blocked until
     * [nOfMessages] are avalaible to be dequeued.
     * @param nOfMessages the number of messages to be dequeued.
     * @param timeout the maximum time to wait for the messages to be dequeued.
     * @returns A list of messages if the messages were dequeued within the [timeout] duration, null otherwise.
     * @throws InterruptedException if the current thread is interrupted while waiting to dequeue a set of
     * messages. Note that if the current thread is interrupted but can dequeue, it will return the list of messages
     * resulted from that operation and not throw [InterruptedException] unless it's blocked again.
     */
    @Throws(InterruptedException::class)
    fun tryDequeue(nOfMessages: Int, timeout: Duration): List<T>? {
        require(nOfMessages > 0) { "nOfMessages must be greater than zero" }
        lock.withLock {
            // fast-path -> The thread that tries to dequeue a set of messages can do it immediately because it
            // is the first thread at the head of the consumer requests queue and the message queue has enough
            // messages to satisfy the request
            if (consumerRequestsQueue.empty && messageQueue.count >= nOfMessages) {
                val list = dequeueMessages(nOfMessages)
                tryToCompleteTheNextProducerRequest()
                return list
            }
            // wait-path -> The thread that tries to dequeue a set of messages cannot do it because it is not the
            // first thread at the head of the consumer requests queue or the message queue does not have enough
            // messages to satisfy the request, and as such, it must wait for its turn to dequeue
            var remainingNanos = timeout.inWholeNanoseconds
            val localRequest = consumerRequestsQueue.enqueue(
                ConsumerRequest(nOfMessages, lock.newCondition())
            )
            while (true) {
                try {
                    remainingNanos = localRequest.value.condition.awaitNanos(remainingNanos)
                } catch (e: InterruptedException) {
                    if (localRequest.value.canDequeue) {
                        // Arm the interrupt flag in order to not lose the interruption request
                        // If this thread is blocked again it will throw an InterruptedException
                        Thread.currentThread().interrupt()
                        // This thread cannot giveup since it's request was completed
                        tryToCompleteTheNextProducerRequest()
                        return localRequest.value.messages
                    }
                    // Giving-up by interruption, remove value from the queue
                    consumerRequestsQueue.remove(localRequest)
                    tryToCompleteConsumerRequests()
                    throw e
                }
                if (localRequest.value.canDequeue) {
                    tryToCompleteTheNextProducerRequest()
                    return localRequest.value.messages
                }
                if (remainingNanos <= 0) {
                    // Giving-up by timeout, remove value from the queue
                    consumerRequestsQueue.remove(localRequest)
                    tryToCompleteConsumerRequests()
                    return null
                }
            }
        }
    }

    /**
     * Tries to complete the consumer requests that can be completed.
     * A consumer request can be completed if the message queue has enough messages to satisfy the request.
     */
    private fun tryToCompleteConsumerRequests() {
        // Check if Consumer requests can be completed
        while (consumerRequestsQueue.headCondition { it.nOfMessages <= messageQueue.count }) {
            val request = consumerRequestsQueue.pull().value
            request.messages = dequeueMessages(request.nOfMessages)
            request.condition.signal()
            request.canDequeue = true
        }
    }

    /**
     * Tries to complete the next producer request if there is one and the message queue is not full.
     */
    private fun tryToCompleteTheNextProducerRequest() {
        if (producerRequestsQueue.notEmpty && messageQueue.count < capacity) {
            // Take the next producer request from the queue and complete it
            val request = producerRequestsQueue.pull().value
            messageQueue.enqueue(request.message)
            request.condition.signal()
            request.canEnqueue = true
        }
    }

    /**
     * Dequeues a set of messages from the message queue.
     * @param nOfMessages the number of messages to be dequeued.
     * @returns A list of messages retrieved from the message queue.
     */
    private fun dequeueMessages(nOfMessages: Int): List<T> {
        val list: MutableList<T> = mutableListOf()
        for (i in 0 until nOfMessages) {
            val message = messageQueue.pull().value
            list.add(message)
        }
        return list.toList()
    }
}