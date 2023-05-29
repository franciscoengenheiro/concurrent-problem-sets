package pt.isel.pc.problemsets.set3

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.utils.ExchangedValue
import pt.isel.pc.problemsets.utils.randomTo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

internal class AsyncMessageQueueTests {

    companion object {
        @OptIn(DelicateCoroutinesApi::class)
        val singleThreadDispatcher: ExecutorCoroutineDispatcher = newSingleThreadContext("single thread dispatcher")
    }

    private val defaultMsg = "message"

    // tests without concurrency stress:
    @Test
    fun `Queue should let a consumer retrieve a value gave by a producer in single message queue`() {
        val capacity = 1
        val queue = AsyncMessageQueue<String>(capacity)
        runBlocking {
            launch {
                queue.enqueue(defaultMsg)
            }
            launch {
                val msg = queue.dequeue(INFINITE)
                assertEquals(defaultMsg, msg)
            }
        }
    }

    @RepeatedTest(3)
    fun `Queue should let a consumer retrieve all values gave by a producer in FIFO order`() {
        val capacity = 50 randomTo 100
        val queue = AsyncMessageQueue<String>(capacity)
        val messageList = List(capacity) { "$defaultMsg-$it" }
        val actualList = mutableListOf<String>()
        runBlocking {
            launch {
                messageList.forEach { msg ->
                    queue.enqueue(msg)
                }
            }
            val job = launch {
                repeat(capacity) {
                    val result = queue.dequeue(INFINITE)
                    actualList.add(result)
                }
            }
            job.join()
            assertEquals(messageList, actualList)
        }
    }

    @Test
    fun `Queue should only operate with a capacity greater than zero`() {
        assertFailsWith<IllegalArgumentException> {
            AsyncMessageQueue<String>(0)
        }
    }

    // Consumer coroutine related tests:
    @RepeatedTest(3)
    fun `Consumer should throw TimeoutException if timeout occurs and could not dequeue in time`() {
        val capacity = 2 randomTo 5
        val queue = AsyncMessageQueue<String>(capacity)
        val timeout = (2 randomTo 5).seconds
        assertFailsWith<TimeoutException> {
            runBlocking {
                launch {
                    queue.dequeue(timeout)
                }
                delay(timeout + 1.seconds)
                queue.enqueue(defaultMsg)
            }
        }
    }

    @Test
    fun `Consumer thread which does not want to wait to dequeue leaves immediatly`() {
        val capacity = 2
        val queue = AsyncMessageQueue<String>(capacity)
        // the queue is empty, so the consumer thread should leave immediatly without suspending
        assertFailsWith<TimeoutException> {
            runBlocking {
                queue.dequeue(ZERO)
            }
        }
    }

    // Tests with concurrency stress:
    @RepeatedTest(5)
    fun `An arbitrary number of producer and consumer threads should be able to exchange messages without losing any exchanged`() {
        val capacity = 50 randomTo 100
        val queue = AsyncMessageQueue<ExchangedValue>(capacity)
        val messageList = List(capacity) { "$defaultMsg-$it" }
        val originalMsgs = ConcurrentLinkedQueue<ExchangedValue>()
        val exchangedMsgs = ConcurrentHashMap<ExchangedValue, Unit>()
        val retrievedMsgs = ConcurrentLinkedQueue<ExchangedValue>()
        val failedExchangedMsgs = ConcurrentLinkedQueue<ExchangedValue>()
        runBlocking(singleThreadDispatcher) {
            // launch producer coroutines
            repeat(capacity) {
                launch {
                    var repetionId = 0
                    repeat(messageList.size) {
                        val value = ExchangedValue(it, repetionId++)
                        originalMsgs.add(value)
                        queue.enqueue(value)
                        if (exchangedMsgs.putIfAbsent(value, Unit) != null) {
                            throw AssertionError(
                                "The value $value has already been exchanged by another producer thread")
                        }
                    }
                }
            }
            // launch consumer coroutines
            repeat(capacity) {
                launch {
                    repeat(messageList.size) {
                        val msg = queue.dequeue(INFINITE)
                        retrievedMsgs.add(msg)
                    }
                }
            }
        }
    }

    @Test
    fun `Check if an arbitrary number of consumer threads is timedout`() {
        TODO()
    }

    @RepeatedTest(5)
    fun `Check if FIFO order is preserved when multiple producer and consumer threads exchange messages`() {
        TODO()
    }
}