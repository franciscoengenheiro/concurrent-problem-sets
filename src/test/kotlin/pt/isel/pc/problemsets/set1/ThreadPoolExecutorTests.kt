package pt.isel.pc.problemsets.set1

import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.utils.ExchangedValue
import pt.isel.pc.problemsets.utils.MultiThreadTestHelper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ThreadPoolExecutorTests {
    // tests without currency stress:
    @Test
    fun `Single thread pool executor should execute received tasks`() {
        val executor = ThreadPoolExecutor(1, Duration.INFINITE)
        val nOfRunnables = 10
        val expected = List(nOfRunnables) { "task $it" }
        val results: MutableList<String> = mutableListOf()
        repeat(nOfRunnables) {
            executor.execute {
                val taskName = "task $it"
                results.add(taskName)
            }
        }
        executor.shutdown()
        assertTrue(executor.awaitTermination(Duration.INFINITE))
        assertEquals(expected, results.toList())
    }

    @Test
    fun `Executor should not execute the received tasks since almost no keep-alive time was given`() {
        val keepAliveTime = 1.milliseconds
        val nOfRunnables = 10
        val executor = ThreadPoolExecutor(nOfRunnables, keepAliveTime)
        val testHelper = MultiThreadTestHelper(10.seconds)
        val results: MutableList<String> = mutableListOf()
        testHelper.createAndStartThread {
            repeat(nOfRunnables) {
                executor.execute {
                    val taskName = "task $it"
                    Thread.sleep(keepAliveTime.inWholeNanoseconds * 1000)
                    // The below code should not be executed
                    results.add(taskName)
                }
            }
            executor.shutdown()
            println(results)
            assertTrue(executor.awaitTermination(Duration.INFINITE))
            assertEquals(mutableListOf(), results.toList())
        }
        testHelper.join()
    }

    @Test
    fun `Executor should use a worker thread that is waiting for work instead of creating another`() {
        val executor = ThreadPoolExecutor(10, Duration.INFINITE)
        val threadMap: ConcurrentHashMap<Thread, Unit> = ConcurrentHashMap()
        executor.execute {
            // Similate almost no work for the first worker thread
            Thread.sleep(1)
            threadMap.computeIfAbsent(Thread.currentThread()) { }
        }
        // Wait for worker thread to sleep
        Thread.sleep(1000)
        // This next method should awake the previous worker thread instead of creating another
        executor.execute {
            // Simulate some work
            Thread.sleep(1000)
            threadMap.computeIfAbsent(Thread.currentThread()) { }
        }
        // Should only have one thread
        assertEquals(1, threadMap.size)
    }

    @Test
    fun `Ensure executor shutdown method awakes waiting worker threads and terminates if no work is avalaible`() {
        val executor = ThreadPoolExecutor(10, Duration.INFINITE)
        val testHelper = MultiThreadTestHelper(10.seconds)
        testHelper.createAndStartThread {
            executor.execute {
                // Similate almost no work for the first worker thread
                Thread.sleep(1)
            }
            // wait for worker thread to sleep
            Thread.sleep(1000)
            executor.shutdown()
            // This test also ensures the awaitTermination method works
            // with only one worker thread, since it will signal the
            // thread that called this method that it's the last thread to finish
            assertTrue(executor.awaitTermination(Duration.INFINITE))
        }
        testHelper.join()
    }

    @Test
    fun `When a worker thread executes a runnable that throws exception should be able to continue executing tasks`() {
        val executor = ThreadPoolExecutor(1, Duration.INFINITE)
        val testHelper = MultiThreadTestHelper(10.seconds)
        val flag = AtomicBoolean(false)
        testHelper.createAndStartThread {
            executor.execute {
                throw IllegalArgumentException("Only log this inside executor")
            }
            executor.execute {
                println("This should be executed")
                flag.set(true)
            }
        }
        // Wait for worker thread to finish both tasks
        Thread.sleep(2000)
        executor.shutdown()
        assertTrue(executor.awaitTermination(Duration.INFINITE))
        testHelper.join()
        assertTrue(flag.get())
    }

    @Test
    fun `Thread pool executor should only operate with number of worker threads above zero`() {
        assertFailsWith<IllegalArgumentException> {
            ThreadPoolExecutor(0, Duration.INFINITE)
        }
    }

    @Test
    fun `Thread pool executor should only operate with keep-alive times above zero`() {
        assertFailsWith<IllegalArgumentException> {
            ThreadPoolExecutor(23, Duration.ZERO)
        }
    }

    @Test
    fun `Execute should throw RejectedExecutionException if executor is in shutdown mode and a thread calls this method`() {
        val executor = ThreadPoolExecutor(1, 1.seconds)
        val results: MutableList<String> = mutableListOf()
        executor.shutdown()
        assertFailsWith<RejectedExecutionException> {
            executor.execute {
                val taskName = "task 0"
                results.add(taskName)
            }
        }
        // Check if the task was not executed
        assertEquals(mutableListOf(), results.toList())
    }

    @Test
    fun `Await termination should return false if executor is not in shutdown mode`() {
        val executor = ThreadPoolExecutor(1, 1.seconds)
        assertFalse(executor.awaitTermination(0.seconds))
    }

    @Test
    fun `Await termination should return true if executor is shutdown even with no timeout`() {
        val executor = ThreadPoolExecutor(1, 1.seconds)
        executor.shutdown()
        assertTrue(executor.awaitTermination(0.seconds))
    }

    @Test
    fun `Await termination should throw InterruptedException if a thread is interrupted while waiting`() {
        val executor = ThreadPoolExecutor(1, Duration.INFINITE)
        val testHelper = MultiThreadTestHelper(10.seconds)
        val nOfRunnables = 10
        repeat(nOfRunnables) {
            executor.execute {
                // Simulate heavy computation
                Thread.sleep(10000)
            }
        }
        executor.shutdown()
        val th1 = testHelper.createAndStartThread {
            assertFailsWith<InterruptedException> {
                executor.awaitTermination(Duration.INFINITE)
            }
        }
        th1.interrupt()
        testHelper.join()
    }

    // tests with concurrency stress:
    @Test
    fun `Executor should execute all tasks even with concurrency stress`() {
        val executor = ThreadPoolExecutor(10, Duration.INFINITE)
        val nOfThreads = 10000
        val nOfAllowedRepetions = 100
        val expected = List(nOfThreads) { threadId ->
            List(nOfAllowedRepetions) { repetionId -> ExchangedValue(threadId, repetionId + 1) }
        }
        val results = ConcurrentLinkedQueue<ExchangedValue>()
        val testHelper = MultiThreadTestHelper(15.seconds)
        testHelper.createAndStartMultipleThreads(nOfThreads) { it, _ ->
            var repetionId = 0
            while (repetionId < nOfAllowedRepetions) {
                executor.execute {
                    val taskName = ExchangedValue(it, ++repetionId)
                    results.add(taskName)
                }
            }
        }
        executor.shutdown()
        assertTrue(executor.awaitTermination(Duration.INFINITE))
        assertEquals(expected.flatten(), results.toList())
        testHelper.join()
    }

    @Test
    fun `Executor should finish pending tasks after executor shutdown`() {
        val executor = ThreadPoolExecutor(10, Duration.INFINITE)
        val nOfThreads = 10000
        val nOfAllowedRepetions = 100
        val expected = List(nOfThreads) { threadId ->
            List(nOfAllowedRepetions) { repetionId -> ExchangedValue(threadId, repetionId + 1) }
        }
        val results = ConcurrentLinkedQueue<ExchangedValue>()
        val testHelper = MultiThreadTestHelper(5.seconds)
        testHelper.createAndStartMultipleThreads(nOfThreads) { it, willingToWaitTimeout ->
            var repetionId = 0
            while (repetionId < nOfAllowedRepetions) {
                try {
                    executor.execute {
                        val taskName = ExchangedValue(it, ++repetionId)
                        results.add(taskName)
                    }
                } catch (e: RejectedExecutionException) {
                    // This exception is expected since the executor is shutdown
                    // and the thread is still trying to execute tasks
                }
            }
        }
        executor.shutdown()
        assertTrue(executor.awaitTermination(Duration.INFINITE))
        assertEquals(expected.flatten(), results.toList())
        testHelper.join()
    }

    @Test
    fun `Calls to AwaitTermination method should block`() {
        val executor = ThreadPoolExecutor(10, Duration.INFINITE)
        val nOfThreads = 10000
        val nOfAllowedRepetions = 100
        val expected = List(nOfThreads) { threadId ->
            List(nOfAllowedRepetions) { repetionId -> ExchangedValue(threadId, repetionId + 1) }
        }
        val results = ConcurrentLinkedQueue<ExchangedValue>()
        val testHelper = MultiThreadTestHelper(5.seconds)
        testHelper.createAndStartMultipleThreads(nOfThreads) { it, willingToWaitTimeout ->
            var repetionId = 0
            while (repetionId < nOfAllowedRepetions) {
                try {
                    executor.execute {
                        Thread.sleep(1000)
                        val taskName = ExchangedValue(it, ++repetionId)
                        results.add(taskName)
                    }
                } catch (e: RejectedExecutionException) {
                    // This exception is expected since the executor is shutdown
                    // and the thread is still trying to execute tasks
                }
            }
        }
        executor.shutdown()
        assertTrue(executor.awaitTermination(Duration.INFINITE))
        assertEquals(expected.flatten(), results.toList())
        testHelper.join()
    }
}