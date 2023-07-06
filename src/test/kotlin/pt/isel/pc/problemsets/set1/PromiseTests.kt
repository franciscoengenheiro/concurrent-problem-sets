package pt.isel.pc.problemsets.set1

import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.utils.MultiThreadTestHelper
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

internal class PromiseTests {

    // tests without concurrency stress
    @Test
    fun `A promise is not done nor cancelled when created`() {
        val promise = Promise<String>()
        assertFalse(promise.isDone)
        assertFalse(promise.isCancelled)
    }

    @Test
    fun `A promise is done when resolved and should retrieve the result`() {
        val value = "value"
        val promise = Promise<String>()
        promise.resolve(value)
        assertFalse(promise.isCancelled)
        assertTrue(promise.isDone)
        assertEquals(value, promise.get(0, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `A promise is not done once marked as started`() {
        val promise = Promise<String>()
        promise.start()
        assertFalse(promise.isCancelled)
        assertFalse(promise.isDone)
    }

    @Test
    fun `A started promise cannot be cancelled`() {
        val promise = Promise<String>()
        promise.start()
        assertFalse(promise.cancel(true))
        assertFalse(promise.isCancelled)
        assertFalse(promise.isDone)
    }

    @Test
    fun `A promise is done when rejected`() {
        val promise = Promise<String>()
        promise.reject(Exception())
        assertFalse(promise.isCancelled)
        assertTrue(promise.isDone)
    }

    @Test
    fun `Cannot start a promise twice`() {
        val promise = Promise<String>()
        promise.start()
        assertFailsWith<IllegalStateException> {
            promise.start()
        }
    }

    @Test
    fun `Cannot resolve a promise twice`() {
        val value = "value"
        val promise = Promise<String>()
        promise.resolve(value)
        assertFailsWith<IllegalStateException> {
            promise.resolve(value)
        }
    }

    @Test
    fun `Cannot reject a promise twice`() {
        val promise = Promise<String>()
        promise.reject(Exception())
        assertFailsWith<IllegalStateException> {
            promise.reject(Exception())
        }
    }

    @Test
    fun `A promise is done when cancelled`() {
        val promise = Promise<String>()
        promise.cancel(true)
        assertTrue(promise.isCancelled)
        assertTrue(promise.isDone)
    }

    @Test
    fun `Can only cancel a promise in the pending state`() {
        val promiseA = Promise<String>()
        assertTrue(promiseA.cancel(true))
        assertFailsWith<CancellationException> {
            promiseA.get()
        }
        val promiseB = Promise<String>()
        promiseB.resolve("value")
        assertFalse(promiseB.cancel(true))
        assertFalse(promiseB.isCancelled)
    }


    @Test
    fun `A promise cannot be cancelled after it is resolved`() {
        val value = "value"
        val promise = Promise<String>()
        promise.resolve(value)
        assertFalse(promise.cancel(true))
        assertFalse(promise.isCancelled)
        assertTrue(promise.isDone)
        assertEquals(value, promise.get(0, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `ExecutionException should be thrown if the value of a rejected promise is retrieved`() {
        val promise = Promise<String>()
        promise.reject(Exception())
        assertFailsWith<ExecutionException> {
            promise.get(0, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `A thread does not want to wait for the result of a promise`() {
        val promise = Promise<String>()
        assertFailsWith<TimeoutException> {
            promise.get(0, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `TimeoutException should be thrown if the timeout exceed when waiting for a promise result`() {
        val promise = Promise<String>()
        val testHelper = MultiThreadTestHelper(5.seconds)
        val timeout = 3000L
        testHelper.createAndStartThread {
            assertFailsWith<TimeoutException> {
                promise.get(timeout, TimeUnit.MILLISECONDS)
            }
        }
        // Make sure that the thread waiting exceeds the timeout
        Thread.sleep(timeout + 1000)
        promise.resolve("value")
        testHelper.join()
    }

    @Test
    fun `Nullable promise`() {
        val promise = Promise<Int?>()
        promise.resolve(null)
        assertTrue(promise.isDone)
        assertFalse(promise.isCancelled)
        assertNull(promise.get())
    }

    @Test
    fun `InterruptedException should be thrown if a thread waiting for the result is interrupted`() {
        val promise = Promise<String>()
        val testHelper = MultiThreadTestHelper(5.seconds)
        val th1 = testHelper.createAndStartThread {
            assertFailsWith<InterruptedException> { promise.get() }
        }
        // Make sure that the thread is waiting
        Thread.sleep(2000)
        th1.interrupt()
        testHelper.join()
    }

    // tests with concurrency stress
    @Test
    fun `All threads waiting for a promise result should be see it when its completed`() {
        val testHelper = MultiThreadTestHelper(10.seconds)
        val promise = Promise<Int>()
        val resolvedValue = 43
        val nOfThreads = 100
        val expectedValues = List(nOfThreads) { resolvedValue }
        val retrievedValues: ConcurrentHashMap<Int, Int> = ConcurrentHashMap()
        testHelper.createAndStartMultipleThreads(nOfThreads) { it, willingToWait ->
            while (!willingToWait()) {
                retrievedValues.computeIfAbsent(it) { _ -> promise.get() }
            }
        }
        // Make sure that all threads are waiting
        Thread.sleep(4000)
        promise.resolve(resolvedValue)
        testHelper.join()
        assertEquals(expectedValues.size, retrievedValues.size)
        assertEquals(expectedValues, retrievedValues.entries.map { it.value })
    }
}