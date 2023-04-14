package pt.isel.pc.problemsets.set1

import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ThreadPoolExecutorWithFutureTests {
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

}
