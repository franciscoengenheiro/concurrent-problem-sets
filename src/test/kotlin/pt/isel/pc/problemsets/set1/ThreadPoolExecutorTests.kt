package pt.isel.pc.problemsets.set1

import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.utils.ExchangedValue
import pt.isel.pc.problemsets.utils.MultiThreadTestHelper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class ThreadPoolExecutorTests {
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
    fun `Executor should execute the received tasks even without keep-alive time`() {
        // Keep-alive time should not matter since a single worker thread always has work to do
        val nOfRunnables = 10
        val executor = ThreadPoolExecutor(1, Duration.ZERO)
        val expected = List(nOfRunnables) { "task $it" }.toSet()
        val testHelper = MultiThreadTestHelper(10.seconds)
        val results: MutableList<String> = mutableListOf()
        testHelper.createAndStartThread {
            repeat(nOfRunnables) {
                executor.execute {
                    val taskName = "task $it"
                    results.add(taskName)
                }
            }
            executor.shutdown()
            assertTrue(executor.awaitTermination(Duration.INFINITE))
            assertEquals(expected, results.toSet())
        }
        testHelper.join()
    }

    @Test
    fun `Executor should use a worker thread that is waiting for work instead of creating another`() {
        val executor = ThreadPoolExecutor(10, Duration.INFINITE)
        val results = ConcurrentLinkedQueue<String>()
        val threadMap: ConcurrentHashMap<Thread, Unit> = ConcurrentHashMap()
        executor.execute {
            threadMap.computeIfAbsent(Thread.currentThread()) { }
            results.add(Thread.currentThread().name)
        }
        // Wait for worker thread to sleep
        Thread.sleep(1000)
        // This next method should awake the previous worker thread instead of creating another
        executor.execute {
            threadMap.computeIfAbsent(Thread.currentThread()) { }
            results.add(Thread.currentThread().name)
        }
        executor.shutdown()
        assertTrue(executor.awaitTermination(Duration.INFINITE))
        assertEquals(2, results.size)
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
                throw IllegalArgumentException("Executor should only log this")
            }
            executor.execute {
                // This should be executed
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
    fun `Execute should throw RejectedExecutionException if executor is in shutdown mode`() {
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

    @Test
    fun `Await termination should return false if a thread waiting is timedout`() {
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
        testHelper.createAndStartThread {
            assertFalse(executor.awaitTermination(1.seconds))
        }
        testHelper.join()
    }

    // tests with concurrency stress:
    @RepeatedTest(5)
    fun `Executor should execute all tasks even with concurrency stress`() {
        val executor = ThreadPoolExecutorWithFuture(10, Duration.INFINITE)
        val nOfThreads = 24
        val nOfAllowedRepetions = 100000
        val expected = List(nOfThreads) { threadId ->
            List(nOfAllowedRepetions) { repetionId -> ExchangedValue(threadId, repetionId + 1) }
        }
        val results = ConcurrentLinkedQueue<ExchangedValue>()
        val testHelper = MultiThreadTestHelper(10.seconds)
        testHelper.createAndStartMultipleThreads(nOfThreads) { it, isTestFinished ->
            var repetionId = 0
            while (!isTestFinished() && repetionId < nOfAllowedRepetions) {
                val task = ExchangedValue(it, ++repetionId)
                executor.execute {
                    results.add(task)
                }
            }
        }
        testHelper.join()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5.seconds))
        assertEquals(expected.size * nOfAllowedRepetions, results.size)
        assertEquals(expected.flatten().toSet(), results.toSet())
    }

    @RepeatedTest(5)
    fun `Executor should finish pending tasks after executor shutdown`() {
        val executor = ThreadPoolExecutor(10, Duration.INFINITE)
        val nOfThreads = 24
        val nOfAllowedRepetions = 100000
        val tasksToBeExecuted = List(nOfThreads) { threadId ->
            List(nOfAllowedRepetions) { repetionId -> ExchangedValue(threadId, repetionId + 1) }
        }.flatten()
        val tasksExecuted = ConcurrentLinkedQueue<ExchangedValue>()
        val tasksFailed = ConcurrentLinkedQueue<ExchangedValue>()
        val testHelper = MultiThreadTestHelper(10.seconds)
        val delegatedTasks = AtomicInteger(0)
        testHelper.createAndStartMultipleThreads(nOfThreads) { it, isTestFinished ->
            var repetionId = 0
            while (!isTestFinished() && repetionId < nOfAllowedRepetions) {
                val task = ExchangedValue(it, ++repetionId)
                try {
                    executor.execute {
                        tasksExecuted.add(task)
                    }
                    // Ensure execute did not throw RejectedExecutionException
                    // which means the task was delegated to the thread pool executor
                    delegatedTasks.incrementAndGet()
                } catch (e: RejectedExecutionException) {
                    // This exception is expected since the executor is shutdown
                    // and the thread is still trying to execute tasks
                    tasksFailed.add(task)
                }
            }
        }
        // wait until half of the tasks are executed
        while (delegatedTasks.get() >= (nOfThreads * nOfAllowedRepetions) / 2) {
            Thread.sleep(100)
        }
        executor.shutdown()
        testHelper.join()
        assertTrue(tasksFailed.isNotEmpty())
        assertEquals(tasksExecuted.size, delegatedTasks.get())
        assertTrue(executor.awaitTermination(1.seconds))
        val allTasks = tasksExecuted + tasksFailed
        assertEquals(tasksToBeExecuted.size, allTasks.size)
        assertEquals(tasksToBeExecuted.toSet(), allTasks.toSet())
    }
}