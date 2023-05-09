package pt.isel.pc.problemsets.set2

import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import pt.isel.pc.problemsets.utils.MultiThreadTestHelper
import pt.isel.pc.problemsets.utils.randomTo
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.seconds

class CyclicBarrierTests {

    // tests without concurrency stress
    private data class SimpleTask(var done: Boolean = false)

    @Test
    fun `A barrier with no parties should throw an exception`() {
        assertFailsWith<IllegalArgumentException> {
            CyclicBarrier(1)
        }
    }

    @Test
    fun `Check if indexes of arrival match the order of which the threads entered the barrier`() {
        val barrier = CyclicBarrier(2)
        val testHelper = MultiThreadTestHelper(30.seconds)
        assertEquals(2, barrier.getParties())
        assertFalse(barrier.isBroken())
        testHelper.createAndStartThread { // th1
            assertEquals(0, barrier.getNumberWaiting())
            assertEquals(1, barrier.await(INFINITE))
        }
        // ensure that th1 is waiting first
        Thread.sleep(2000)
        testHelper.createAndStartThread { // th2
            assertEquals(1, barrier.getNumberWaiting())
            assertEquals(0, barrier.await(INFINITE))
        }
        assertFalse(barrier.isBroken())
        testHelper.join()
    }

    @Test
    fun `A barrier should execute a runnable`() {
        val parties = 2
        val task = SimpleTask()
        val barrier = CyclicBarrier(parties) { task.done = true }
        assertEquals(parties, barrier.getParties())
        assertEquals(0, barrier.getNumberWaiting())
        assertFalse(barrier.isBroken())
        assertFalse { task.done }
        assertEquals(0, barrier.await(Duration.ZERO))
        assertTrue { task.done }
    }

    @Test
    fun `A thread that specifies no timeout and does not complete the barrier throws TimeoutException and breaks the barrier`() {
        val barrier = CyclicBarrier(2)
        assertFalse(barrier.isBroken())
        assertFailsWith<TimeoutException> {
            barrier.await(Duration.ZERO)
        }
        assertTrue(barrier.isBroken())
    }

    @Test
    fun `A thread that calls await on a broken barrier throws BrokenBarrierException`() {
        val barrier = CyclicBarrier(2)
        assertFalse(barrier.isBroken())
        val testHelper = MultiThreadTestHelper(10.seconds)
        val th1 = testHelper.createAndStartThread { barrier.await() }
        // break the barrier
        th1.interrupt()
        testHelper.join()
        assertTrue(barrier.isBroken())
        assertFailsWith<BrokenBarrierException> {
            barrier.await(Duration.ZERO)
        }
    }

    @Test
    fun `A thread that is interrupted on a non-broken barrier throws InterruptedException`() {
        val barrier = CyclicBarrier(2)
        assertFalse(barrier.isBroken())
        val testHelper = MultiThreadTestHelper(10.seconds)
        val th1 = testHelper.createAndStartThread {
            assertFailsWith<InterruptedException> {
                barrier.await()
            }
        }
        // break the barrier
        th1.interrupt()
        testHelper.join()
        assertTrue(barrier.isBroken())
    }

    @Test
    fun `Reset a barrier with no threads waiting`() {
        val barrier = CyclicBarrier(2)
        assertFalse(barrier.isBroken())
        // reset the barrier
        barrier.reset()
        // ensure a new generation was created
        assertFalse(barrier.isBroken())
    }

    @RepeatedTest(3)
    fun `Reset a barrier with one thread waiting`() {
        val barrier = CyclicBarrier(2)
        assertFalse(barrier.isBroken())
        val testHelper = MultiThreadTestHelper(10.seconds)
        testHelper.createAndStartThread {
            assertFailsWith<BrokenBarrierException> {
                barrier.await()
            }
        }
        // Ensure the first thread is waitng at the barrier
        Thread.sleep(1000)
        // reset the barrier
        barrier.reset()
        testHelper.join()
        // ensure a new generation was created
        assertFalse(barrier.isBroken())
    }

    // TODO("ensure a barrier is reusable")
    // TODO("ensure a thread timesout when waiting for a barrier")

    // tests with concurrency stress
    @Test
    fun `Check if indexes of arrival match the order of which the threads entered the barrier with multiple threads`() {
        val parties = 10 randomTo 24
        val barrier = CyclicBarrier(parties)
        val testHelper = MultiThreadTestHelper(INFINITE)
        val expectedIndicesOfArrival = List(parties) { it }.reversed()
        val indicesMap = mutableMapOf<Int, Int>()
        val counter = AtomicInteger(0)
        // Create a map where the key represents the thread index,
        // and the value represents the expected index of arrival
        for (i in 0 until parties) {
            indicesMap[i] = expectedIndicesOfArrival[i]
        }
        testHelper.createAndStartMultipleThreads(parties) { _, _ ->
            val threadId = counter.getAndIncrement()
            val expectedIndex = indicesMap[threadId]
            requireNotNull(expectedIndex)
            // TODO(check-then-act problem here)
            // TODO(Between getting the counter reference and calling await,
            //  another thread might entered the barrier first)
            val actualIndex = barrier.await(INFINITE)
            assertEquals(expectedIndex, actualIndex)
        }
        testHelper.join()
    }
}