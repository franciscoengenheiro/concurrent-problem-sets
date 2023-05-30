package pt.isel.pc.problemsets.set1

import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.utils.ExchangedValue
import pt.isel.pc.problemsets.utils.MultiThreadTestHelper
import pt.isel.pc.problemsets.utils.randomTo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class BlockingMessageQueueTests {

    private val defaultMsg = "message"

    // tests without concurrency stress:
    @Test
    fun `Queue should let a consumer thread retrieve a value gave by a producer thread`() {
        val capacity = 1
        val queue = BlockingMessageQueue<String>(capacity)
        val testHelper = MultiThreadTestHelper(10.seconds)
        testHelper.createAndStartThread {
            val couldEnqueue = queue.tryEnqueue(defaultMsg, Duration.INFINITE)
            assertTrue(couldEnqueue)
        }
        Thread.sleep(1000)
        testHelper.createAndStartThread {
            val result = queue.tryDequeue(capacity, Duration.INFINITE)
            assertNotNull(result)
            assertEquals(capacity, result.size)
            assertEquals(defaultMsg, result.first())
        }
        testHelper.join()
    }

    @Test
    fun `Queue should let a consumer thread retrieve all values gave by a producer thread in FIFO order`() {
        val capacity = 10
        val queue = BlockingMessageQueue<String>(capacity)
        val messageList = List(capacity) { "$defaultMsg-$it" }
        val testHelper = MultiThreadTestHelper(10.seconds)
        // Add two elements to the queue
        testHelper.createAndStartThread {
            repeat(capacity) {
                val couldEnqueue = queue.tryEnqueue(messageList[it], Duration.INFINITE)
                assertTrue(couldEnqueue)
            }
        }
        Thread.sleep(1000)
        testHelper.createAndStartThread {
            val result = queue.tryDequeue(capacity, Duration.INFINITE)
            assertNotNull(result)
            assertEquals(capacity, result.size)
            assertEquals(messageList, result)
        }
        testHelper.join()
    }

    @Test
    fun `Queue should only operate with a capacity greater than zero`() {
        assertFailsWith<IllegalArgumentException> {
            BlockingMessageQueue<String>(0)
        }
    }

    @Test
    fun `Consumer threads should only be able to dequeue nOfMessages between 1 and capacity`() {
        val capacity = 2
        val queue = BlockingMessageQueue<String>(capacity)
        assertFailsWith<IllegalArgumentException> {
            queue.tryDequeue(capacity + 1, Duration.INFINITE)
        }
        assertFailsWith<IllegalArgumentException> {
            queue.tryDequeue(capacity - capacity, Duration.INFINITE)
        }
    }

    // Producer threads related tests
    @Test
    fun `Producer thread should be blocked trying to enqueue a message in a full queue`() {
        val capacity = 1
        val queue = BlockingMessageQueue<String>(capacity)
        val testHelper = MultiThreadTestHelper(10.seconds)
        // This test could not be generic (for any capacity) since having control of the producer thread
        // which is the "last" one to start is required
        val pth1 = testHelper.createAndStartThread {
            queue.tryEnqueue(defaultMsg, Duration.INFINITE)
        }
        val pth2 = testHelper.createAndStartThread {
            queue.tryEnqueue(defaultMsg, Duration.INFINITE)
        }
        // Wait for the producer threads to start
        Thread.sleep(1000)
        assertEquals(Thread.State.TERMINATED, pth1.state)
        assertEquals(Thread.State.TIMED_WAITING, pth2.state)
    }

    @Test
    fun `Producer thread should return false when timeout expires`() {
        val capacity = 1
        val queue = BlockingMessageQueue<String>(capacity)
        val testHelper = MultiThreadTestHelper(10.seconds)
        // This test could not be generic (for any capacity) since having control of the producer thread,
        // which is the "last" one to start, is required
        testHelper.createAndStartThread {
            val couldEnqueue = queue.tryEnqueue(defaultMsg, Duration.INFINITE)
            assertTrue(couldEnqueue)
        }
        // The queue is full, so the producer thread should time out
        testHelper.createAndStartThread {
            val couldEnqueue = queue.tryEnqueue(defaultMsg, Duration.ZERO)
            assertFalse(couldEnqueue)
        }
        testHelper.join()
    }

    @Test
    fun `Producer thread should throw InterruptedException if interruption occurs and could not dequeue in time`() {
        val capacity = 1
        val queue = BlockingMessageQueue<String>(capacity)
        val testHelper = MultiThreadTestHelper(10.seconds)
        // This test could not be generic (for any capacity) since having control of the producer thread,
        // which is the "last" one to start, is required
        testHelper.createAndStartThread {
            val couldEnqueue = queue.tryEnqueue(defaultMsg, Duration.INFINITE)
            assertTrue(couldEnqueue)
        }
        // The queue is full, so the producer thread should time out
        val pth2 = testHelper.createAndStartThread {
            assertFailsWith<InterruptedException> {
                queue.tryEnqueue(defaultMsg, Duration.INFINITE)
            }
        }
        Thread.sleep(1000)
        pth2.interrupt()
        testHelper.join()
    }

    @Test
    fun `Producer thread which does not want to wait to enqueue leaves immediatly`() {
        val capacity = 1
        val queue = BlockingMessageQueue<String>(capacity)
        val testHelper = MultiThreadTestHelper(2.seconds)
        testHelper.createAndStartThread {
            queue.tryEnqueue(defaultMsg, Duration.ZERO)
        }
        testHelper.createAndStartThread {
            assertFalse(queue.tryEnqueue(defaultMsg, Duration.ZERO))
        }
        testHelper.join()
    }

    // Consumer threads related tests
    @Test
    fun `Consumer thread should be blocked trying to retrieve a message from an empty queue`() {
        val capacity = 10
        val queue = BlockingMessageQueue<String>(capacity)
        val cth = Thread {
            queue.tryDequeue(1, Duration.INFINITE)
        }
        cth.start()
        // Wait for the consumer thread to start
        Thread.sleep(1000)
        assertEquals(Thread.State.TIMED_WAITING, cth.state)
    }

    @Test
    fun `Consumer thread should return null when timeout expires`() {
        val capacity = 10
        val queue = BlockingMessageQueue<String>(capacity)
        val testHelper = MultiThreadTestHelper(10.seconds)
        testHelper.createAndStartThread {
            val result = queue.tryDequeue(1, Duration.ZERO)
            assertNull(result)
        }
        testHelper.join()
    }

    @Test
    fun `Consumer thread should throw InterruptedException if interruption occurs and could not dequeue in time`() {
        val capacity = 1
        val queue = BlockingMessageQueue<String>(capacity)
        val testHelper = MultiThreadTestHelper(10.seconds)
        val cth1 = testHelper.createAndStartThread {
            assertFailsWith<InterruptedException> {
                queue.tryDequeue(capacity, Duration.INFINITE)
            }
        }
        Thread.sleep(1000)
        cth1.interrupt()
        testHelper.join()
    }

    @Test
    fun `Consumer thread which does not want to wait to dequeue leaves immediatly`() {
        val capacity = 1
        val queue = BlockingMessageQueue<String>(capacity)
        val testHelper = MultiThreadTestHelper(2.seconds)
        testHelper.createAndStartThread {
            assertNull(queue.tryDequeue(capacity, Duration.ZERO))
        }
        testHelper.join()
    }

    // Tests with concurrency stress:
    @RepeatedTest(5)
    fun `An arbitrary number of producer and consumer threads should be able to exchange messages without losing any`() {
        val capacity = 20
        val queue = BlockingMessageQueue<ExchangedValue>(capacity)
        val nOfThreads = 24
        val testHelper = MultiThreadTestHelper(10.seconds)
        // Sets
        val originalMsgs = ConcurrentLinkedQueue<ExchangedValue>()
        val exchangedMsgs = ConcurrentHashMap<ExchangedValue, Unit>()
        val retrievedMsgs = ConcurrentLinkedQueue<ExchangedValue>()
        val failedExchangedMsgs = ConcurrentLinkedQueue<ExchangedValue>()
        testHelper.createAndStartMultipleThreads(nOfThreads) { threadId, isTestFinished ->
            // This counter does not need to be thread safe since each thread will have its own counter
            var repetionId = 0
            while (!isTestFinished() && repetionId < 10000) {
                val value = ExchangedValue(threadId, repetionId++)
                originalMsgs.add(value)
                val couldEnqueue = queue.tryEnqueue(value, Duration.ZERO)
                if (couldEnqueue) {
                    if (exchangedMsgs.putIfAbsent(value, Unit) != null)
                        throw AssertionError(
                            "The value $value has already been exchanged by another producer thread")
                } else {
                    // The message was not delivered to the queue because the timeout expired, and this is
                    // the only cause for this to happen, as no producer thread was interrupted in this test
                    failedExchangedMsgs.add(value)
                }
            }
        }
        testHelper.createAndStartMultipleThreads(nOfThreads) { _, isTestFinished ->
            while (!isTestFinished()) {
                val result= queue.tryDequeue(
                    1 randomTo capacity, (100 randomTo 500).milliseconds)
                if (result != null) retrievedMsgs.addAll(result)
            }
        }
        testHelper.join()
        assertTrue(failedExchangedMsgs.isNotEmpty())
        val intersection = failedExchangedMsgs.intersect(exchangedMsgs.keys)
        assertTrue(intersection.isEmpty())
        assertEquals(retrievedMsgs.size, exchangedMsgs.size)
        assertEquals(retrievedMsgs.toSet(), exchangedMsgs.keys)
        val allExchangedMsgs = failedExchangedMsgs + exchangedMsgs.keys
        assertEquals(originalMsgs.size, allExchangedMsgs.size)
        assertEquals(originalMsgs.toSet(), allExchangedMsgs.toSet())
    }

    @Test
    fun `Check if an arbitrary number of consumer threads is timedout`() {
        val capacity = 20
        val queue = BlockingMessageQueue<ExchangedValue>(capacity)
        val nOfProducerThreads = 24
        val nOfConsumerThreads = 10
        val producerTimeout = 1.seconds
        // The consumer timeout should be much smaller than the producer timeout
        // to ensure that some consumer threads are timed out
        val consumerTimeout = producerTimeout / 5
        val testHelper = MultiThreadTestHelper(10.seconds)
        // Sets
        val originalMsgs = ConcurrentLinkedQueue<ExchangedValue>()
        val exchangedMsgs = ConcurrentHashMap<ExchangedValue, Unit>()
        val retrievedMsgs = ConcurrentLinkedQueue<ExchangedValue>()
        val failedExchangedMsgs = ConcurrentLinkedQueue<ExchangedValue>()
        val consumerThreadsTimedout = ConcurrentLinkedQueue<Int>()
        // Create producer threads
        testHelper.createAndStartMultipleThreads(nOfProducerThreads) { threadId, isTestFinished ->
            // This counter does not need to be thread safe since each thread will have its own counter
            var repetionId = 0
            while (!isTestFinished() && repetionId < 100) {
                val value = ExchangedValue(threadId, repetionId++)
                originalMsgs.add(value)
                val couldEnqueue = if (threadId % 2 == 0) queue.tryEnqueue(value, Duration.ZERO)
                                           else queue.tryEnqueue(value, producerTimeout)
                if (couldEnqueue) {
                    if (exchangedMsgs.putIfAbsent(value, Unit) != null) {
                        throw AssertionError(
                            "The value $value has already been exchanged by another producer thread"
                        )
                    }
                } else {
                    // The message was not delivered to the queue because the timeout expired, and this is
                    // the only cause for this to happen, as no producer thread was interrupted in this test
                    failedExchangedMsgs.add(value)
                }
            }
        }
        // Create consumer threads with smaller timeout
        testHelper.createAndStartMultipleThreads(nOfConsumerThreads) { threadId, isTestFinished ->
            while (!isTestFinished()) {
                val result = queue.tryDequeue(1 randomTo capacity, consumerTimeout)
                if (result != null) {
                    retrievedMsgs.addAll(result)
                } else {
                    // The message was not retrieved from the queue because the timeout expired and this is
                    // the only cause for this to happen, as no consumer thread was interrupted in this test
                    consumerThreadsTimedout.add(threadId)
                }
            }
        }
        testHelper.join()
        assertTrue(consumerThreadsTimedout.isNotEmpty())
        assertTrue(failedExchangedMsgs.isNotEmpty())
        val intersection = failedExchangedMsgs.intersect(exchangedMsgs.keys)
        assertTrue(intersection.isEmpty())
        assertEquals(retrievedMsgs.size, exchangedMsgs.size)
        assertEquals(retrievedMsgs.toSet(), exchangedMsgs.keys)
        val allExchangedMsgs = failedExchangedMsgs + exchangedMsgs.keys
        assertEquals(originalMsgs.size, allExchangedMsgs.size)
        assertEquals(originalMsgs.toSet(), allExchangedMsgs.toSet())
    }

    @RepeatedTest(5)
    fun `Check if FIFO order is preserved when multiple producer and consumer threads exchange messages`() {
        val capacity = 10
        val queue = BlockingMessageQueue<ExchangedValue>(capacity)
        val nOfThreads = 24
        val testHelper = MultiThreadTestHelper(10.seconds)
        // Starter values
        val threadsIdsList = List(nOfThreads) { it to -1 }
        // Pair<ThreadId, RepetitionId>
        val exchangedMsgs = ConcurrentHashMap<Int, Int>()
        exchangedMsgs.putAll(threadsIdsList)
        testHelper.createAndStartMultipleThreads(nOfThreads) { threadId, isTestFinished ->
            // This counter does not need to be thread safe since each thread will have its own counter
            var repetionId = 0
            while (!isTestFinished()) {
                val value = ExchangedValue(threadId, repetionId++)
                val couldEnqueue = queue.tryEnqueue(value, (500 randomTo 1000).milliseconds)
                if (couldEnqueue) {
                    val previousRepetion = exchangedMsgs[threadId]
                    requireNotNull(previousRepetion)
                    if (previousRepetion >= repetionId)
                        throw AssertionError(
                            "The value $value has already been exchanged by this producer thread")
                    exchangedMsgs[threadId] = repetionId
                }
            }
        }
        testHelper.createAndStartMultipleThreads(1) { _, isTestFinished ->
            while (!isTestFinished()) {
                queue.tryDequeue(1 randomTo capacity, Duration.ZERO)
            }
        }
        // Wait for all threads to finish
        testHelper.join()
    }
}