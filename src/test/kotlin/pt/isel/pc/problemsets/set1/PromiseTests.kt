package pt.isel.pc.problemsets.set1

import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.utils.MultiThreadTestHelper
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PromiseTests {
    @Test
    fun `Promise is not done nor cancelled when created`() {
        val promise = Promise<String>()
        assertFalse(promise.isDone)
        assertFalse(promise.isCancelled)
    }

    @Test
    fun `Promise is done and not cancelled when resolved and should retrieve the result`() {
        val value = "value"
        val promise = Promise<String>()
        promise.resolve(value)
        assertFalse(promise.isCancelled)
        assertTrue(promise.isDone)
        assertEquals(value, promise.get(0, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `Promise is done and not cancelled when rejected`() {
        val promise = Promise<String>()
        promise.reject(Exception())
        assertFalse(promise.isCancelled)
        assertTrue(promise.isDone)
    }

    @Test
    fun `A promise should be able to be cancelled before resolving`() {
        val promise = Promise<String>()
        assertTrue(promise.cancel(true))
        assertTrue(promise.isCancelled)
        assertTrue(promise.isDone)
    }

    @Test
    fun `Promise cannot be cancelled after it is resolved`() {
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
    fun `TimeoutException should be thrown if the timeout exceed`() {
        val promise = Promise<String>()
        assertFailsWith<TimeoutException> {
            promise.get(0, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `A thread waiting for a promise is interrupted`() {
        val promise = Promise<String>()
        val testHelper = MultiThreadTestHelper(5.seconds)
        val th1 = testHelper.createAndStartThread {
            assertFailsWith<InterruptedException> {
                promise.get(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
            }
        }
        // Make sure that the thread is waiting
        Thread.sleep(2000)
        th1.interrupt()
        testHelper.join()
    }

    @Test
    fun `test promises together`() {
        TODO("make an array of promises or something")
    }
}
