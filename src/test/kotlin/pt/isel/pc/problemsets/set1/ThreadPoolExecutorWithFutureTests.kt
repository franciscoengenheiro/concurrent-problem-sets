package pt.isel.pc.problemsets.set1

import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.utils.ExchangedValue
import pt.isel.pc.problemsets.utils.MultiThreadTestHelper
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class ThreadPoolExecutorWithFutureTests {
    private val defaultTask = "task"

    // tests without concurrency stress:
    @Test
    fun `Retrieve a Future from a task delegated to a thread pool`() {
        val executor = ThreadPoolExecutorWithFuture(1, Duration.INFINITE)
        val callable: Callable<String> = Callable { defaultTask }
        val result = executor.execute(callable)
        executor.shutdown()
        assertTrue(executor.awaitTermination(5.seconds))
        assertTrue(result.isDone)
        assertFalse(result.isCancelled)
        assertEquals(defaultTask, result.get())
    }

    @Test
    fun `Retrieve a Future that is not yet ready`() {
        val executor = ThreadPoolExecutorWithFuture(1, Duration.INFINITE)
        val callable: Callable<String> = Callable {
            throw Exception()
        }
        val result = executor.execute(callable)
        executor.shutdown()
        assertTrue(executor.awaitTermination(5.seconds))
        assertTrue(result.isDone)
        assertFalse(result.isCancelled)
        assertFailsWith<ExecutionException> { result.get() }
    }

    @Test
    fun `Retrieve a Future with no timeout`() {
        val executor = ThreadPoolExecutorWithFuture(1, Duration.INFINITE)
        val callable: Callable<String> = Callable {
            Thread.sleep(5000)
            defaultTask
        }
        val result = executor.execute(callable)
        assertFalse(result.isDone)
        assertFalse(result.isCancelled)
        assertFailsWith<TimeoutException> {
            assertEquals(defaultTask, result.get(0, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun `Try to cancel a Future`() {
        val executor = ThreadPoolExecutorWithFuture(1, Duration.INFINITE)
        val callable: Callable<String> = Callable {
            Thread.sleep(2000)
            defaultTask
        }
        val result = executor.execute(callable)
        assertTrue(result.cancel(true))
        assertTrue(result.isCancelled)
        assertTrue(result.isDone)
        assertFailsWith<CancellationException> {
            result.get()
        }
    }

    @Test
    fun `Try to cancel a completed Future`() {
        val executor = ThreadPoolExecutorWithFuture(1, Duration.INFINITE)
        val callable: Callable<String> = Callable { defaultTask }
        val result = executor.execute(callable)
        executor.shutdown()
        assertTrue(executor.awaitTermination(5.seconds))
        assertTrue(result.isDone)
        assertFalse(result.isCancelled)
        assertEquals(defaultTask, result.get())
        assertFalse(result.cancel(true))
        assertTrue(result.isDone)
        assertFalse(result.isCancelled)
    }

    // tests with concurrency stress:
    @RepeatedTest(5)
    fun `Executor should cast the correct type when executing a ExecutionRequest`() {
        val executor = ThreadPoolExecutorWithFuture(10, Duration.INFINITE)
        val nOfThreads = 24
        val typesList = listOf(
            1, // Int
            2L, // Long
            3.0f, // Float
            4.0, // Double
            'a', // Char
            true, // Boolean
            "Hello", // String
            listOf(1, "abc", true, null), // List<Any?>
            ExchangedValue(1, 2) // A complex type example
        )
        val testHelper = MultiThreadTestHelper(10.seconds)
        testHelper.createAndStartMultipleThreads(nOfThreads) { _, isTestFinished ->
            while (!isTestFinished()) {
                val type = typesList.random()
                val typeClass = type::class.javaObjectType
                val future = executor.execute { type }
                assertEquals(typeClass, future.get()::class.javaObjectType)
            }
        }
        testHelper.join()
        executor.shutdown()
        assertTrue(executor.awaitTermination(1.seconds))
    }

    @RepeatedTest(5)
    fun `Executor should execute all tasks even with concurrency stress`() {
        val executor = ThreadPoolExecutorWithFuture(10, Duration.INFINITE)
        val nOfThreads = 24
        val nOfAllowedRepetions = 3000
        val expected = List(nOfThreads) { threadId ->
            List(nOfAllowedRepetions) { repetionId -> ExchangedValue(threadId, repetionId + 1) }
        }.flatten()
        val delegatedTasks = ConcurrentLinkedQueue<ExchangedValue>()
        val executedTasks = ConcurrentLinkedQueue<Future<ExchangedValue>>()
        val testHelper = MultiThreadTestHelper(10.seconds)
        testHelper.createAndStartMultipleThreads(nOfThreads) { it, isTestFinished ->
            var repetionId = 0
            while(!isTestFinished() && repetionId < nOfAllowedRepetions) {
                val task = ExchangedValue(it, ++repetionId)
                val future = executor.execute {
                    delegatedTasks.add(task)
                    task
                }
                executedTasks.add(future)
            }
        }
        testHelper.join()
        executor.shutdown()
        assertTrue(executor.awaitTermination(1.seconds))
        assertEquals(expected.size, delegatedTasks.size)
        val intersectionA = expected - delegatedTasks
        assertTrue(intersectionA.isEmpty())
        assertEquals(expected.toSet(), delegatedTasks.toSet())
        assertEquals(expected.size, executedTasks.size)
        // Waiting for a future result should not throw any exception since all tasks should have been executed
        // with success
        val executedResults = executedTasks.map { it.get() }
        val intersectionB = expected - executedResults
        assertTrue(intersectionB.isEmpty())
        assertEquals(expected.toSet(), executedResults.toSet())
    }

    @RepeatedTest(5)
    fun `Executor should finish pending tasks after executor shutdown`() {
        val executor = ThreadPoolExecutorWithFuture(10, Duration.INFINITE)
        val nOfThreads = 100
        val nOfAllowedRepetions = 1000
        val tasksToBeExecuted = List(nOfThreads) { threadId ->
            List(nOfAllowedRepetions) { repetionId -> ExchangedValue(threadId, repetionId + 1) }
        }.flatten()
        val tasksExecuted = ConcurrentLinkedQueue<ExchangedValue>()
        val tasksFailed = ConcurrentLinkedQueue<ExchangedValue>()
        val delegatedTasksCounter = AtomicInteger(0)
        val testHelper = MultiThreadTestHelper(10.seconds)
        testHelper.createAndStartMultipleThreads(nOfThreads) { it, isTestFinished ->
            var repetionId = 0
            while(!isTestFinished() && repetionId < nOfAllowedRepetions) {
                val task = ExchangedValue(it, ++repetionId)
                val future = executor.execute { task }
                try {
                    tasksExecuted.add(future.get())
                    // If the task was executed without RejectedExecutionException, the counter should be incremented
                    // since the task was delegated to the executor prior to shut down
                    delegatedTasksCounter.incrementAndGet()
                } catch (e: ExecutionException) {
                    // Since the callable, in this test, does not throw any exception, the only possible
                    // exception is RejectedExecutionException
                    tasksFailed.add(task)
                }
            }
        }
        // wait until half of the tasks are executed
        while (delegatedTasksCounter.get() >= (nOfThreads * nOfAllowedRepetions) / 2) {
            Thread.sleep(100)
        }
        // shutdown the executor before all tasks are executed
        executor.shutdown()
        testHelper.join()
        // ensure that some tasks were delegated to the executor after the shutdown process
        assertTrue(tasksFailed.isNotEmpty())
        assertTrue(executor.awaitTermination(1.seconds))
        assertEquals(tasksExecuted.size, delegatedTasksCounter.get())
        val allExecutedTasks = tasksExecuted + tasksFailed
        assertEquals(tasksToBeExecuted.size, allExecutedTasks.size)
        val intersection = tasksToBeExecuted - allExecutedTasks
        assertTrue(intersection.isEmpty())
        assertEquals(tasksToBeExecuted.toSet(), allExecutedTasks.toSet())
    }

}
