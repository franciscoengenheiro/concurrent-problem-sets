package pt.isel.pc.problemsets.set3

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.utils.ExchangedValue
import pt.isel.pc.problemsets.utils.randomTo
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class AsyncMessageQueueTests {

    companion object {
        @OptIn(DelicateCoroutinesApi::class)
        val singleThreadDispatcher: ExecutorCoroutineDispatcher = newSingleThreadContext("single-thread dispatcher")
        @OptIn(DelicateCoroutinesApi::class)
        val multiThreadDispatcher = newFixedThreadPoolContext(3, "multi-thread dispatcher")
    }

    private val defaultMsg = "message"

    // tests without concurrency stress:
    @Test
    fun `Queue should let a consumer retrieve a value gave by a producer in single message queue`() {
        val capacity = 1
        val queue = AsyncMessageQueue<String>(capacity)
        runBlocking(singleThreadDispatcher) {
            launch {
                queue.enqueue(defaultMsg)
            }
            launch {
                val msg = queue.dequeue(INFINITE)
                assertEquals(defaultMsg, msg)
            }
        }
    }

    @Test
    fun `A producer coroutine that is cancelled and not marked as resumed removes its request to enqueue a message`() {
        val capacity = 10
        val queue = AsyncMessageQueue<String>(capacity)
        val messageList = List(capacity) { "$defaultMsg-$it" }
        val cancelledMsg = "cancelledMessage"
        runBlocking(singleThreadDispatcher) {
            val producerJobToCancel = launch {
                try {
                    queue.enqueue(cancelledMsg)
                } catch (e: CancellationException) {
                    println("Producer coroutine was cancelled")
                }
            }
            val producersJob = launch {
                repeat(capacity) {
                    queue.enqueue(messageList[it])
                }
            }
            producersJob.join()
            launch {
                // TODO("where to cancel the producer coroutine?")
                producerJobToCancel.cancel()
                // empty the queue
                repeat(capacity) {
                    val message = queue.dequeue(INFINITE)
                    // this consumer coroutine should not see the canceled message
                    // ensuring the producer coroutine request was removed from the queue
                    assertContains(messageList, message)
                }
            }
        }
    }

    @RepeatedTest(3)
    fun `Queue should let a consumer retrieve all values gave by a producer in FIFO order`() {
        val capacity = 100000 randomTo 500000
        val queue = AsyncMessageQueue<String>(capacity)
        val messageList = List(capacity) { "$defaultMsg-$it" }
        val actualList = mutableListOf<String>()
        runBlocking(singleThreadDispatcher) {
            launch {
                messageList.forEach { msg ->
                    queue.enqueue(msg)
                }
            }
            launch {
                repeat(capacity) {
                    val result = queue.dequeue(INFINITE)
                    actualList.add(result)
                }
            }
        }
        assertEquals(messageList.size, actualList.size)
        assertEquals(messageList.toSet(), actualList.toSet())
    }

    @Test
    fun `Queue should only operate with a capacity greater than zero`() {
        assertFailsWith<IllegalArgumentException> {
            AsyncMessageQueue<String>(0)
        }
    }

    // Consumer coroutine related tests:
    @Test
    fun `Consumer should throw TimeoutException if timeout occurs and could not dequeue in time`() {
        val capacity = 2
        val timeout = 2.seconds
        val queue = AsyncMessageQueue<String>(capacity)
        assertFailsWith<TimeoutException> {
            runBlocking(singleThreadDispatcher) {
                queue.dequeue(timeout)
            }
        }
    }

    @Test
    fun `Consumer which does not want to wait to dequeue leaves immediatly`() {
        val capacity = 2
        val queue = AsyncMessageQueue<String>(capacity)
        // the queue is empty, so the consumer coroutine should leave immediatly without suspending
        assertFailsWith<TimeoutException> {
            runBlocking(singleThreadDispatcher) {
                queue.dequeue(ZERO)
            }
        }
    }

    // Tests with concurrency stress:
    @RepeatedTest(5)
    fun `An arbitrary number of producer and consumer coroutines should be able to exchange messages without losing any`() {
        val capacity = 750 randomTo 1500
        val queue = AsyncMessageQueue<ExchangedValue>(capacity)
        val originalMsgs = LinkedList<ExchangedValue>()
        val exchangedMsgs = HashMap<ExchangedValue, Unit>()
        val retrievedMsgs = LinkedList<ExchangedValue>()
        val nrOfProducers = capacity / 2
        var repetionId = 0
        runBlocking(singleThreadDispatcher) {
            // launch producer coroutines
            repeat(nrOfProducers) {
                launch {
                    repeat(capacity) {
                        val value = ExchangedValue(it, repetionId++)
                        originalMsgs.add(value)
                        queue.enqueue(value)
                        if (exchangedMsgs.putIfAbsent(value, Unit) != null) {
                            throw AssertionError(
                                "The value $value has already been exchanged by another producer coroutine")
                        }
                    }
                }
            }
            // launch consumer coroutines
            repeat(nrOfProducers) {
                launch {
                    repeat(capacity) {
                        val msg = queue.dequeue(INFINITE)
                        retrievedMsgs.add(msg)
                    }
                }
            }
        }
        assertTrue(retrievedMsgs.isNotEmpty())
        assertEquals(retrievedMsgs.size, exchangedMsgs.size)
        assertEquals(retrievedMsgs.toSet(), exchangedMsgs.keys)
        assertEquals(originalMsgs.size, retrievedMsgs.size)
        assertEquals(originalMsgs.toSet(), retrievedMsgs.toSet())
    }

    @Test
    fun `Check if an arbitrary number of consumer coroutines is timedout`() {
        val capacity = 10
        val queue = AsyncMessageQueue<ExchangedValue>(capacity)
        val originalMsgs = LinkedList<ExchangedValue>()
        val exchangedMsgs = HashMap<ExchangedValue, Unit>()
        val retrievedMsgs = LinkedList<ExchangedValue>()
        val nrOfProducers = capacity / 4
        var nrOfTimedoutConsumers = 0
        runBlocking(multiThreadDispatcher) {
            // launch producer coroutines
            repeat(nrOfProducers) { coroutineId ->
                launch {
                    var repetionId = 0
                    repeat(capacity) {
                        val value = ExchangedValue(coroutineId, repetionId++)
                        originalMsgs.add(value)
                        queue.enqueue(value)
                        if (exchangedMsgs.putIfAbsent(value, Unit) != null) {
                            throw AssertionError(
                                "The value $value has already been exchanged by another producer coroutine")
                        }
                    }
                }
            }
            // launch consumer coroutines
            repeat(capacity) {
                launch {
                    repeat(capacity) { idx ->
                        try {
                            val msg = if (idx % 2 == 0) queue.dequeue(2.seconds)
                                                     else queue.dequeue(100.milliseconds)
                            retrievedMsgs.add(msg)
                        } catch (e: TimeoutException) {
                            nrOfTimedoutConsumers++
                        }
                    }
                }
            }
        }
        assertTrue(nrOfTimedoutConsumers > 0)
        assertTrue(retrievedMsgs.isNotEmpty())
        assertEquals(retrievedMsgs.size, exchangedMsgs.size)
        assertEquals(retrievedMsgs.toSet(), exchangedMsgs.keys)
        assertEquals(originalMsgs.size, retrievedMsgs.size)
        assertEquals(originalMsgs.toSet(), retrievedMsgs.toSet())
    }

    @RepeatedTest(3)
    fun `Check if FIFO order is preserved when multiple producer and consumer coroutines exchange messages`() {
        val capacity = 1 // small capacity to increase the change of full queue
        val nrOfProducers = 750 randomTo 1500
        val nrOfRepetions = 500 randomTo 1000
        val queue = AsyncMessageQueue<ExchangedValue>(capacity)
        val testTimeout = 5.seconds
        // Starter values
        val coroutineIdsList = List(nrOfProducers) { it to -1 }
        // Pair<ThreadId, RepetitionId>
        val exchangedMsgs = ConcurrentHashMap<Int, Int>()
        exchangedMsgs.putAll(coroutineIdsList)
        runBlocking(multiThreadDispatcher) {
            withTimeoutOrNull(testTimeout) {
                // launch producer coroutines
                repeat(nrOfProducers) { coroutineId ->
                    launch {
                        var repetionId = 0
                        repeat(nrOfRepetions) {
                            val value = ExchangedValue(coroutineId, repetionId++)
                            queue.enqueue(value)
                            val previousRepetion = exchangedMsgs[coroutineId]
                            requireNotNull(previousRepetion)
                            // ensure that the current coroutine has not exchanged this value before
                            if (previousRepetion >= repetionId)
                                throw AssertionError(
                                    "The value $value has already been exchanged by this producer coroutine"
                                )
                            exchangedMsgs[coroutineId] = repetionId
                        }
                    }
                }
                launch {
                    while (true) {
                        try {
                            queue.dequeue(ZERO)
                        } catch(e: TimeoutException) {
                            // do nothing
                        } finally {
                            if (exchangedMsgs.values.all { it >= nrOfRepetions }) {
                                // All values have been exchanged, exit the consumer coroutine
                                break
                            }
                        }
                    }
                }
            }
        }
    }
}